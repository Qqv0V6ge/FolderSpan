package com.folderspan.ui.state.file

import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskRuntimeStoreLock

object ExternalFileResourceLeaseRegistry {
    private data class LeaseEntry(
        val lease: ExternalFileResourceLease,
        var referenceCount: Int,
        var producerHeld: Boolean,
        val release: () -> Unit,
    )

    private val lock = TaskRuntimeStoreLock()
    private val leases = mutableMapOf<String, LeaseEntry>()
    private val taskLeases = mutableMapOf<Long, String>()

    fun register(
        lease: ExternalFileResourceLease,
        release: (() -> Unit)? = null,
    ): Boolean = withLock {
        if (leases.containsKey(lease.id)) return@withLock false
        leases[lease.id] = LeaseEntry(
            lease = lease,
            referenceCount = 1,
            producerHeld = true,
            release = release ?: fun() {
                lease.rootPath?.let(::cleanupExternalFileStagingRoot)
            },
        )
        true
    }

    fun bindTasks(lease: ExternalFileResourceLease, taskKeys: Collection<Long>) {
        if (taskKeys.isEmpty()) {
            releaseProducer(lease.id)
            return
        }
        val replacedLeaseReleases = mutableListOf<() -> Unit>()
        withLock {
            val entry = leases[lease.id] ?: LeaseEntry(
                lease = lease,
                referenceCount = 1,
                producerHeld = true,
                release = fun() {
                    lease.rootPath?.let(::cleanupExternalFileStagingRoot)
                },
            ).also { created -> leases[lease.id] = created }
            taskKeys.distinct().forEach { taskKey ->
                val previousLeaseId = taskLeases.put(taskKey, lease.id)
                if (previousLeaseId == lease.id) return@forEach
                previousLeaseId?.let(::decrementLocked)?.let(replacedLeaseReleases::add)
                entry.referenceCount++
            }
        }
        replacedLeaseReleases.forEach { action -> action() }
        releaseProducer(lease.id)
    }

    fun releaseTask(taskKey: Long) {
        val releaseAction = withLock {
            val leaseId = taskLeases.remove(taskKey) ?: return@withLock null
            decrementLocked(leaseId)
        }
        releaseAction?.invoke()
    }

    fun releaseProducer(leaseId: String) {
        val releaseAction = withLock {
            val entry = leases[leaseId] ?: return@withLock null
            if (!entry.producerHeld) return@withLock null
            entry.producerHeld = false
            decrementLocked(leaseId)
        }
        releaseAction?.invoke()
    }

    fun restoreTaskBindings(tasks: Collection<Task>) {
        val activeRoots = mutableSetOf<String>()
        withLock {
            tasks.forEach { task ->
                val leaseId = task.values[EXTERNAL_FILE_LEASE_ID_TASK_VALUE]
                    ?.takeIf { item -> item.isNotBlank() }
                    ?: return@forEach
                val rootPath = task.values[EXTERNAL_FILE_LEASE_ROOT_TASK_VALUE]
                    ?.takeIf { item -> item.isNotBlank() }
                if (rootPath != null) activeRoots += rootPath
                val entry = leases.getOrPut(leaseId) {
                    val lease = ExternalFileResourceLease(leaseId, rootPath)
                    LeaseEntry(
                        lease = lease,
                        referenceCount = 0,
                        producerHeld = false,
                        release = fun() {
                            rootPath?.let(::cleanupExternalFileStagingRoot)
                        },
                    )
                }
                if (taskLeases.put(task.key, leaseId) != leaseId) {
                    entry.referenceCount++
                }
            }
        }
        reconcileExternalFileStagingRoots(activeRoots)
    }

    internal fun referenceCount(leaseId: String): Int = withLock {
        leases[leaseId]?.referenceCount ?: 0
    }

    internal fun clearForTest() {
        val releaseActions = withLock {
            val actions = leases.values.map { entry -> entry.release }
            leases.clear()
            taskLeases.clear()
            actions
        }
        releaseActions.forEach { action -> runCatching(action) }
    }

    private fun decrementLocked(leaseId: String): (() -> Unit)? {
        val entry = leases[leaseId] ?: return null
        entry.referenceCount--
        if (entry.referenceCount > 0) return null
        leases.remove(leaseId)
        taskLeases.entries.removeAll { (_, boundLeaseId) -> boundLeaseId == leaseId }
        return entry.release
    }

    private inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

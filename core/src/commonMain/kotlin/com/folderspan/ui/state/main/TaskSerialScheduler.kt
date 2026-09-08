package com.folderspan.ui.state.main

import strings.AppStrings

import com.folderspan.data.StatusEnum
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class TaskSerialScheduler(
    private val updateStatus: (Task, StatusEnum) -> Boolean,
    private val deleteTask: (Task) -> Boolean,
    private val getTask: (Long) -> Task?,
    private val onCancelRequested: (Task, String) -> Unit,
    private val onTaskExecutionFailure: (Long, Task?, Exception) -> Unit,
    private val onTaskFinalized: (Long) -> Unit,
) {
    private val cancelKeys = MutableStateFlow<Set<Long>>(emptySet())
    private val pauseKeys = MutableStateFlow<Set<Long>>(emptySet())
    private val queueMutex = Mutex()
    private val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runnableTasks = ArrayDeque<Long>()
    private val resumedTasks = ArrayDeque<Long>()
    private val scheduledTasks = mutableMapOf<Long, ScheduledTask>()
    private var runningTaskKey: Long? = null

    fun requestPause(task: Task) {
        if (task.status == StatusEnum.LOADING) {
            pauseKeys.update { item -> item + task.key }
            updateStatus(task, StatusEnum.PAUSE)
            taskScope.launch(start = CoroutineStart.UNDISPATCHED) {
                queueMutex.withLock {
                    removeQueuedTaskLocked(task.key)
                    if (runningTaskKey == null) {
                        dispatchNextLocked()
                    }
                }
            }
        }
    }

    fun requestResume(task: Task) {
        if (pauseKeys.value.contains(task.key)) {
            pauseKeys.update { item -> item - task.key }
            updateStatus(task, StatusEnum.LOADING)
            taskScope.launch(start = CoroutineStart.UNDISPATCHED) {
                queueMutex.withLock {
                    if (!scheduledTasks.containsKey(task.key)) {
                        return@withLock
                    }
                    if (runningTaskKey != task.key && !cancelKeys.value.contains(task.key)) {
                        enqueueResumedTaskLocked(task.key)
                    }
                    dispatchNextLocked()
                }
            }
        }
    }

    fun requestCancel(task: Task, reason: String = AppStrings.ui_user_cancels_task) {
        cancelKeys.update { item -> item + task.key }
        pauseKeys.update { item -> item - task.key }
        onCancelRequested(task, reason)
        taskScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var deleteImmediately = false
            var pauseWaiter: CompletableDeferred<TaskResumeSignal>? = null
            var executionJob: Job? = null
            queueMutex.withLock {
                removeQueuedTaskLocked(task.key)
                val scheduled = scheduledTasks[task.key]
                when {
                    scheduled == null -> {
                        deleteImmediately = true
                    }

                    !scheduled.started -> {
                        scheduledTasks.remove(task.key)
                        deleteImmediately = true
                    }

                    else -> {
                        scheduled.cancelRequested = true
                        scheduled.cancelReason = reason
                        pauseWaiter = scheduled.pauseWaiter
                        scheduled.pauseWaiter = null
                        executionJob = scheduled.executionJob
                    }
                }
            }
            pauseWaiter?.complete(TaskResumeSignal.Cancel)
            executionJob?.cancel(CancellationException(reason))
            if (deleteImmediately) {
                if (!deleteTask(task)) {
                    clearSignals(task.key)
                }
            }
        }
    }

    fun isTaskCancelled(taskKey: Long): Boolean = cancelKeys.value.contains(taskKey)

    fun isTaskPaused(taskKey: Long): Boolean = pauseKeys.value.contains(taskKey)

    suspend fun awaitIfPaused(taskKey: Long): Boolean {
        while (true) {
            var pauseWaiter: CompletableDeferred<TaskResumeSignal>? = null
            var shouldContinue = false
            var shouldCancel = false
            queueMutex.withLock {
                when {
                    isTaskCancelled(taskKey) -> shouldCancel = true
                    !isTaskPaused(taskKey) -> shouldContinue = true
                    else -> {
                        val scheduled = scheduledTasks[taskKey]
                        if (scheduled == null) {
                            shouldCancel = isTaskCancelled(taskKey)
                            shouldContinue = !shouldCancel
                        } else {
                            pauseWaiter = scheduled.pauseWaiter ?: CompletableDeferred<TaskResumeSignal>().also { waiter ->
                                scheduled.pauseWaiter = waiter
                            }
                            if (runningTaskKey == taskKey) {
                                runningTaskKey = null
                                dispatchNextLocked()
                            }
                        }
                    }
                }
            }
            if (shouldCancel) {
                return false
            }
            if (shouldContinue) {
                return true
            }
            when (pauseWaiter?.await()) {
                TaskResumeSignal.Resume -> Unit
                TaskResumeSignal.Cancel -> return false
                null -> return !isTaskCancelled(taskKey)
            }
        }
    }

    fun clearSignals(taskKey: Long) {
        cancelKeys.update { item -> item - taskKey }
        pauseKeys.update { item -> item - taskKey }
    }

    fun registerTaskHandler(task: Task, work: suspend () -> Unit) {
        taskScope.launch(start = CoroutineStart.UNDISPATCHED) {
            queueMutex.withLock {
                if (scheduledTasks.containsKey(task.key)) {
                    return@withLock
                }
                if (cancelKeys.value.contains(task.key)) {
                    getTask(task.key)?.let(deleteTask) ?: clearSignals(task.key)
                    return@withLock
                }
                scheduledTasks[task.key] = ScheduledTask(work = work)
                if (!pauseKeys.value.contains(task.key) && !cancelKeys.value.contains(task.key)) {
                    enqueueRunnableTaskLocked(task.key)
                }
                dispatchNextLocked()
            }
        }
    }

    private fun launchTask(taskKey: Long, task: ScheduledTask) {
        task.started = true
        val executionJob = taskScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (isTaskCancelled(taskKey)) {
                    val latestTask = getTask(taskKey)
                    if (latestTask != null) {
                        deleteTask(latestTask)
                    } else {
                        clearSignals(taskKey)
                    }
                    return@launch
                }
                task.work()
                if (task.cancelRequested || isTaskCancelled(taskKey)) {
                    throw CancellationException(
                        task.cancelReason.ifBlank { AppStrings.message_task_cancelled }
                    )
                }
            } catch (error: Exception) {
                onTaskExecutionFailure(taskKey, getTask(taskKey), error)
            } finally {
                withContext(NonCancellable) {
                    finalizeTask(taskKey)
                }
            }
        }
        task.executionJob = executionJob
        executionJob.start()
    }

    private fun dispatchNextLocked() {
        if (runningTaskKey != null) return

        while (true) {
            val nextTaskKey = resumedTasks.removeFirstOrNull() ?: runnableTasks.removeFirstOrNull() ?: return
            val scheduled = scheduledTasks[nextTaskKey] ?: continue
            if (cancelKeys.value.contains(nextTaskKey)) {
                scheduledTasks.remove(nextTaskKey)
                getTask(nextTaskKey)?.let(deleteTask) ?: clearSignals(nextTaskKey)
                continue
            }
            if (pauseKeys.value.contains(nextTaskKey)) {
                continue
            }

            runningTaskKey = nextTaskKey
            if (scheduled.started) {
                val pauseWaiter = scheduled.pauseWaiter
                scheduled.pauseWaiter = null
                if (pauseWaiter != null) {
                    pauseWaiter.complete(TaskResumeSignal.Resume)
                    return
                }
                runningTaskKey = null
                continue
            }

            launchTask(nextTaskKey, scheduled)
            return
        }
    }

    private suspend fun finalizeTask(taskKey: Long) {
        queueMutex.withLock {
            scheduledTasks.remove(taskKey)
            onTaskFinalized(taskKey)
            if (runningTaskKey == taskKey) {
                runningTaskKey = null
            }
            dispatchNextLocked()
        }
    }

    private fun enqueueRunnableTaskLocked(taskKey: Long) {
        if (runningTaskKey == taskKey) return
        removeQueuedTaskLocked(taskKey)
        runnableTasks.addLast(taskKey)
    }

    private fun enqueueResumedTaskLocked(taskKey: Long) {
        if (runningTaskKey == taskKey) return
        removeQueuedTaskLocked(taskKey)
        resumedTasks.addLast(taskKey)
    }

    private fun removeQueuedTaskLocked(taskKey: Long): Boolean {
        return removeFromQueueLocked(runnableTasks, taskKey) || removeFromQueueLocked(resumedTasks, taskKey)
    }

    private fun removeFromQueueLocked(queue: ArrayDeque<Long>, taskKey: Long): Boolean {
        var removed = false
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() == taskKey) {
                iterator.remove()
                removed = true
            }
        }
        return removed
    }

    private data class ScheduledTask(
        val work: suspend () -> Unit,
        var started: Boolean = false,
        var pauseWaiter: CompletableDeferred<TaskResumeSignal>? = null,
        var executionJob: Job? = null,
        var cancelRequested: Boolean = false,
        var cancelReason: String = "",
    )

    private enum class TaskResumeSignal {
        Resume,
        Cancel,
    }
}

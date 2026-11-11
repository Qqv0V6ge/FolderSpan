package com.folderspan.ui.state.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** All sync entry points share the same limit, including storage groups. */
internal class SyncRunQueue(private val scope: CoroutineScope) {
    private val lock = TaskRuntimeStoreLock()
    private val jobs = mutableMapOf<Long, Job>()
    private val permits = Semaphore(2)

    fun enqueue(taskId: Long, onQueued: () -> Unit, run: suspend () -> Unit): Boolean {
        lock.lock()
        try {
            if (jobs[taskId]?.isCompleted == false) return false
            val job = scope.launch(start = CoroutineStart.LAZY) { run() }
            jobs[taskId] = job
            job.invokeOnCompletion {
                lock.lock()
                try { if (jobs[taskId] === job) jobs.remove(taskId) } finally { lock.unlock() }
            }
            onQueued()
            job.start()
            return true
        } finally { lock.unlock() }
    }

    suspend fun <T> withPermit(run: suspend () -> T): T = permits.withPermit {
        // Semaphore acquisition can complete without suspending even when its caller was just cancelled.
        currentCoroutineContext().ensureActive()
        run()
    }

    fun cancel(taskId: Long) {
        lock.lock()
        val job = try { jobs[taskId] } finally { lock.unlock() }
        job?.cancel()
    }
}

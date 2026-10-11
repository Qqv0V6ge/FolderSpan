package com.folderspan.editor

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import strings.AppStrings

private const val DEFAULT_FILE_EDITOR_BACKGROUND_PARALLELISM = 2
private val sharedFileEditorBackgroundSlots =
    Semaphore(DEFAULT_FILE_EDITOR_BACKGROUND_PARALLELISM)

enum class FileEditorTaskKind {
    InteractiveRead,
    Prefetch,
    Search,
    LineIndex,
    Analysis,
}

/** Owns background work for one editor session and guarantees session-close cancellation. */
class FileEditorTaskCoordinator(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    maxParallelBackgroundTasks: Int? = null,
) {
    init {
        require(maxParallelBackgroundTasks == null || maxParallelBackgroundTasks > 0)
    }

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + dispatcher)
    private val mutex = Mutex()
    private val backgroundSlots = maxParallelBackgroundTasks?.let { Semaphore(it) }
        ?: sharedFileEditorBackgroundSlots
    private val tasks = mutableMapOf<FileEditorTaskKind, MutableSet<Job>>()

    suspend fun launch(
        kind: FileEditorTaskKind,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val taskScope = this
            if (kind.isResourceIntensive()) {
                backgroundSlots.withPermit { block(taskScope) }
            } else {
                block(taskScope)
            }
        }
        mutex.withLock {
            if (kind == FileEditorTaskKind.InteractiveRead) {
                tasks[FileEditorTaskKind.Prefetch]?.forEach(Job::cancel)
            }
            tasks.getOrPut(kind, ::mutableSetOf).add(job)
        }
        job.invokeOnCompletion {
            scope.launch {
                mutex.withLock {
                    tasks[kind]?.let { jobs ->
                        jobs.remove(job)
                        if (jobs.isEmpty()) tasks.remove(kind)
                    }
                }
            }
        }
        job.start()
        return job
    }

    suspend fun cancel(kind: FileEditorTaskKind) {
        val jobs = mutex.withLock { tasks.remove(kind)?.toList().orEmpty() }
        jobs.forEach(Job::cancel)
    }

    fun close() {
        scope.cancel(AppStrings.ui_file_editor_session_closed)
    }
}

private fun FileEditorTaskKind.isResourceIntensive(): Boolean = when (this) {
    FileEditorTaskKind.Search,
    FileEditorTaskKind.LineIndex,
    FileEditorTaskKind.Analysis -> true

    FileEditorTaskKind.InteractiveRead,
    FileEditorTaskKind.Prefetch -> false
}

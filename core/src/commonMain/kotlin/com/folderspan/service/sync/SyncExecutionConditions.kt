package com.folderspan.service.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Lets an in-flight file finish its result handling, but never starts another file after condition loss. */
internal class SyncExecutionConditions(
    private val scope: CoroutineScope,
    private val unmet: () -> Set<SyncRunCondition>,
    private val stop: (Set<SyncRunCondition>) -> Unit,
    private val graceMillis: Long = 60_000,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SyncExecutionConditions>
    internal class Path(val gate: SyncExecutionConditions) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Path>
        var waiting = false
    }

    private val mutex = Mutex()
    private val paths = mutableSetOf<Path>()
    private var deadline: Job? = null
    private var stopped = false
    private var lostConditions = emptySet<SyncRunCondition>()

    private fun checkLocked() {
        if (lostConditions.isEmpty()) {
            val missing = unmet()
            if (missing.isNotEmpty()) {
                lostConditions = missing.toSet()
                deadline = scope.launch { delay(graceMillis); mutex.withLock { stopLocked() } }
            }
        }
        if (lostConditions.isNotEmpty() && paths.none { !it.waiting }) stopLocked()
    }

    private fun stopLocked() {
        if (!stopped) {
            stopped = true
            stop(lostConditions)
        }
    }

    suspend fun check() = mutex.withLock { checkLocked() }

    internal suspend fun enter(): Path {
        val path = Path(this)
        val allowed = mutex.withLock {
            checkLocked()
            if (lostConditions.isNotEmpty()) false else { paths += path; true }
        }
        if (!allowed) awaitCancellation()
        return path
    }

    internal suspend fun leave(path: Path) = mutex.withLock {
        paths -= path
        checkLocked()
    }

    internal suspend fun beforeFile(path: Path?) {
        val allowed = mutex.withLock {
            // Mark a path waiting before checking, so its next file cannot keep a stopped run alive.
            if (unmet().isNotEmpty() || lostConditions.isNotEmpty()) path?.waiting = true
            checkLocked()
            lostConditions.isEmpty()
        }
        if (!allowed) awaitCancellation()
    }

    suspend fun close() { deadline?.cancel() }
}

/** Enclose one logical path, including recording its result; nested serial work shares the same path. */
internal suspend fun <T> withSyncConditionPath(block: suspend () -> T): T {
    val context = currentCoroutineContext()
    val gate = context[SyncExecutionConditions] ?: return block()
    if (context[SyncExecutionConditions.Path]?.gate === gate) return block()
    val path = gate.enter()
    return try { withContext(path) { block() } }
    finally { withContext(NonCancellable) { gate.leave(path) } }
}

internal suspend fun beforeSyncConditionFile() {
    val context = currentCoroutineContext()
    context[SyncExecutionConditions]?.beforeFile(context[SyncExecutionConditions.Path])
}

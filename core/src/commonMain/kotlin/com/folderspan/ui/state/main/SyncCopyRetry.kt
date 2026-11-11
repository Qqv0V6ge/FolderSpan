package com.folderspan.ui.state.main

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun copySyncFileWithRetry(
    scope: CoroutineScope,
    retries: Int,
    timeoutMillis: Long,
    lastProgressAt: () -> Long?,
    ensureRunning: suspend () -> Unit,
    stopBlockedIo: () -> Unit,
    cleanup: suspend () -> Unit,
    now: () -> Long = ::nowMillis,
    pollMillis: Long = 250,
    stopWaitMillis: Long = 30_000,
    isUncertainFailure: (Throwable) -> Boolean = { false },
    copy: suspend () -> Result<Boolean>,
): Result<Boolean> {
    var failure: Throwable = IllegalStateException("Sync copy failed")
    repeat(retries.coerceIn(0, 5) + 1) {
        ensureRunning()
        val started = now()
        val attempt = scope.async(currentCoroutineContext().minusKey(Job)) {
            try { copy() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
        }
        var stopped = false
        var activityAt = started
        try {
            while (!attempt.isCompleted) {
                val beforeWait = now()
                ensureRunning()
                // Waiting for resume is not transfer inactivity.
                activityAt += (now() - beforeWait).coerceAtLeast(0)
                activityAt = maxOf(activityAt, lastProgressAt() ?: started)
                if (timeoutMillis > 0 && now() - activityAt >= timeoutMillis) {
                    attempt.cancel()
                    runCatching(stopBlockedIo)
                    stopped = withTimeoutOrNull(stopWaitMillis) { attempt.join(); true } == true
                    if (!stopped) return Result.failure(IllegalStateException("Previous sync attempt did not stop"))
                    failure = IllegalStateException("Sync transfer stalled")
                    break
                }
                withTimeoutOrNull(pollMillis) { attempt.join() }
            }
            if (!stopped) {
                val result = attempt.await()
                if (result.getOrDefault(false)) return result
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                failure = error ?: IllegalStateException("Sync copy failed")
            }
        } catch (cancelled: CancellationException) {
            attempt.cancel()
            withContext(NonCancellable) {
                runCatching(stopBlockedIo)
                if (withTimeoutOrNull(stopWaitMillis) { attempt.join(); true } == true) cleanup()
            }
            throw cancelled
        } catch (error: Exception) {
            failure = error
        }
        currentCoroutineContext().ensureActive()
        // An uncertain upload must retain its checkpoint and be verified before another write.
        if (isUncertainFailure(failure)) return Result.failure(failure)
        cleanup()
    }
    return Result.failure(failure)
}

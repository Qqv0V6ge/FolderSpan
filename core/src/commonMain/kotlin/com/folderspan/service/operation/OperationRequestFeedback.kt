package com.folderspan.service.operation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class OperationRequestFeedback(private val nowMillis: () -> Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OperationRequestFeedback>

    private data class State(
        val failed: Boolean = false,
        val ignored: Boolean = false,
        val firstProgressAt: Long = -1,
        val lastProgressAt: Long = -1,
        val longestProgressGap: Long = 0,
    )

    private val startedAt = nowMillis()
    private val state = MutableStateFlow(State())
    val failed: Boolean get() = state.value.failed
    val ignored: Boolean get() = state.value.ignored

    fun failure() { state.update { it.copy(failed = true) } }
    fun ignore() { state.update { it.copy(ignored = true) } }

    fun bytes(delta: Long) {
        if (delta <= 0) return
        val now = nowMillis()
        state.update { previous ->
            previous.copy(
                firstProgressAt = if (previous.firstProgressAt < 0) now else minOf(previous.firstProgressAt, now),
                lastProgressAt = maxOf(previous.lastProgressAt, now),
                longestProgressGap = maxOf(previous.longestProgressGap,
                    if (previous.lastProgressAt < 0) 0 else (now - previous.lastProgressAt).coerceAtLeast(0)),
            )
        }
    }

    fun latencyMillis(): Long {
        val now = nowMillis()
        val sample = state.value
        if (sample.firstProgressAt < 0) return (now - startedAt).coerceAtLeast(0)
        // A progressing file is measured by its response gaps, not its full transfer duration.
        return maxOf(sample.firstProgressAt - startedAt, sample.longestProgressGap, now - sample.lastProgressAt).coerceAtLeast(0)
    }
}

internal suspend fun reportOperationFailure() { currentCoroutineContext()[OperationRequestFeedback]?.failure() }
internal suspend fun reportOperationSkipped() { currentCoroutineContext()[OperationRequestFeedback]?.ignore() }
internal suspend fun reportOperationBytes(delta: Long) { currentCoroutineContext()[OperationRequestFeedback]?.bytes(delta) }

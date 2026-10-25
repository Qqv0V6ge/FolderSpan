package com.folderspan.service.webrtc.controller.core

import com.folderspan.service.webrtc.models.WebRtcIceCandidate
import com.folderspan.service.webrtc.models.usernameFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val WEB_RTC_MAX_PENDING_CANDIDATES = 64
internal const val WEB_RTC_MAX_PENDING_CANDIDATE_BYTES = 128 * 1024
internal const val WEB_RTC_MAX_CANDIDATE_BYTES = 4 * 1024

internal class WebRtcCandidateCapacityException : IllegalStateException("WebRTC pending ICE candidate limit exceeded")

/** Serializes remote SDP and ICE, including candidates received while native SDP work is suspended. */
internal class WebRtcRemoteNegotiation(
    scope: CoroutineScope,
    private val isCurrent: () -> Boolean,
    private val addCandidate: suspend (WebRtcIceCandidate) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private sealed interface Input {
        data class Description(val ufrags: Set<String>, val apply: suspend () -> Unit) : Input
        data class Candidate(val value: WebRtcIceCandidate, val bytes: Int) : Input
    }

    private data class Pending(val count: Int = 0, val bytes: Int = 0, val closed: Boolean = false)

    private val pending = MutableStateFlow(Pending())
    private val inbox = Channel<Input>(WEB_RTC_MAX_PENDING_CANDIDATES + 2)
    private val consumer = scope.launch(start = CoroutineStart.LAZY) {
        val early = ArrayDeque<Input.Candidate>()
        var descriptionReady = false
        var ufrags = emptySet<String>()
        val retiredUfrags = ArrayDeque<String>()
        var descriptions = 0
        try {
            suspend fun submit(input: Input.Candidate) {
                if (!isCurrent()) throw CancellationException("Obsolete WebRTC negotiation")
                val fragment = input.value.usernameFragment()
                when {
                    (fragment != null && fragment in retiredUfrags) || (fragment == null && descriptions > 1) -> Unit
                    fragment != null && ufrags.isNotEmpty() && fragment !in ufrags -> {
                        early.addLast(input)
                        return
                    }
                    else -> addCandidate(input.value)
                }
                pending.update { if (it.closed) it else it.copy(count = it.count - 1, bytes = it.bytes - input.bytes) }
            }
            for (input in inbox) {
                if (!isCurrent()) break
                when (input) {
                    is Input.Description -> {
                        descriptionReady = false
                        input.apply()
                        if (!isCurrent()) break
                        retiredUfrags.addAll(ufrags - input.ufrags)
                        while (retiredUfrags.size > 16) retiredUfrags.removeFirst()
                        ufrags = input.ufrags
                        descriptions++
                        descriptionReady = true
                        repeat(early.size) { submit(early.removeFirst()) }
                    }
                    is Input.Candidate -> if (descriptionReady) submit(input) else early.addLast(input)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            fail(error)
        } finally {
            early.clear()
            close()
        }
    }

    init {
        consumer.start()
    }

    fun description(ufrags: Set<String> = emptySet(), apply: suspend () -> Unit): Boolean =
        enqueue(Input.Description(ufrags, apply))

    fun candidate(value: WebRtcIceCandidate): Boolean {
        if (pending.value.closed) return false
        if (!isCurrent()) { close(); return false }
        if (value.candidate.length > WEB_RTC_MAX_CANDIDATE_BYTES || value.sdpMid.length > 256 || value.sdpMLineIndex < 0) {
            fail(IllegalArgumentException("Invalid or oversized WebRTC ICE candidate"))
            return false
        }
        val bytes = value.candidate.encodeToByteArray().size
        while (true) {
            val old = pending.value
            if (old.closed) return false
            if (bytes > WEB_RTC_MAX_CANDIDATE_BYTES || old.count >= WEB_RTC_MAX_PENDING_CANDIDATES ||
                old.bytes + bytes > WEB_RTC_MAX_PENDING_CANDIDATE_BYTES
            ) {
                fail(WebRtcCandidateCapacityException())
                return false
            }
            if (pending.compareAndSet(old, old.copy(count = old.count + 1, bytes = old.bytes + bytes))) break
        }
        return enqueue(Input.Candidate(value, bytes))
    }

    private fun enqueue(input: Input): Boolean {
        if (pending.value.closed) return false
        if (!isCurrent()) { close(); return false }
        if (inbox.trySend(input).isSuccess) return true
        fail(WebRtcCandidateCapacityException())
        return false
    }

    private fun fail(error: Throwable) {
        if (close() && isCurrent()) onFailure(error)
    }

    fun close(): Boolean {
        while (true) {
            val old = pending.value
            if (old.closed) return false
            if (pending.compareAndSet(old, Pending(closed = true))) break
        }
        inbox.cancel()
        consumer.cancel()
        return true
    }
}

package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.models.WebRtcDataChannel
import com.folderspan.service.webrtc.models.WebRtcPeerConnection
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class WebRtcAndroidIngressTest {
    @Test
    fun firstMessageSurvivesDelayedIncomingChannelSubscription() = runSuspendTest {
        coroutineScope {
            val initiator = WebRtcPeerConnection(emptyList())
            var responder: WebRtcPeerConnection? = null
            var outgoing: WebRtcDataChannel? = null
            var accepted: WebRtcDataChannel? = null
            val jobs = mutableListOf<Job>()
            val initiatorReady = CompletableDeferred<Unit>()
            val responderReady = CompletableDeferred<Unit>()
            val messages = List(4) { messageIndex ->
                ByteArray(16 * 1024) { index ->
                    ((index * 73 + index / 19 + messageIndex * 47 + 0x5B) and 0xFF).toByte()
                }
            }

            try {
                val receiver = WebRtcPeerConnection(emptyList()).also { responder = it }
                jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                    initiator.onIceCandidate.collect { candidate ->
                        responderReady.await()
                        receiver.addIceCandidate(candidate)
                    }
                }
                jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                    receiver.onIceCandidate.collect { candidate ->
                        initiatorReady.await()
                        initiator.addIceCandidate(candidate)
                    }
                }

                withTimeout(30_000L) {
                    val sender = checkNotNull(initiator.createDataChannel("android-ingress-regression"))
                        .also { outgoing = it }
                    val sent = async(start = CoroutineStart.UNDISPATCHED) {
                        sender.onOpen.first()
                        messages.forEachIndexed { index, message ->
                            assertTrue(sender.send(message), "Message $index must be accepted for sending")
                        }
                    }
                    jobs += sent

                    val offer = initiator.createOffer()
                    initiator.setLocalDescription(offer)
                    receiver.setRemoteDescription(offer)
                    responderReady.complete(Unit)
                    val answer = receiver.createAnswer()
                    receiver.setLocalDescription(answer)
                    initiator.setRemoteDescription(answer)
                    initiatorReady.complete(Unit)

                    sent.await()
                    // Leave the incoming channel unconsumed while native message callbacks return.
                    delay(250L)
                    val incoming = receiver.onDataChannel.first().also { accepted = it }
                    val received = withTimeout(5_000L) { incoming.onMessage.take(messages.size).toList() }
                    messages.zip(received).forEachIndexed { messageIndex, (expected, actual) ->
                        val firstMismatch = expected.indices.firstOrNull { index ->
                            actual.getOrNull(index) != expected[index]
                        } ?: minOf(expected.size, actual.size)
                        assertTrue(
                            expected.contentEquals(actual),
                            "Native message $messageIndex changed before subscription: expectedBytes=${expected.size}, " +
                                "receivedBytes=${actual.size}, firstDifferentByte=$firstMismatch",
                        )
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    jobs.forEach { it.cancelAndJoin() }
                    try {
                        outgoing?.close()
                    } finally {
                        try {
                            accepted?.close()
                        } finally {
                            try { initiator.close() } finally { responder?.close() }
                        }
                    }
                }
            }
        }
    }
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.folderspan.service.webrtc

import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.RtcConfiguration
import com.shepeliev.webrtckmp.SignalingState
import com.shepeliev.webrtckmp.onIceCandidate
import com.shepeliev.webrtckmp.onIceGatheringState
import com.shepeliev.webrtckmp.onSignalingStateChange
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class WebRtcIosEventLoopTest {
    @Test
    fun nativeRunLoopExecutesMainScopeAndDelayedContinuation() {
        val scope = MainScope()
        var enteredMainScope = false
        val result = scope.async {
            enteredMainScope = true
            assertTrue(NSThread.isMainThread, "MainScope must execute on the Darwin main thread")
            delay(10L)
        }
        try {
            assertTrue(
                pumpNativeRunLoopUntil(2_000L) { result.isCompleted },
                "MainScope did not finish: entered=$enteredMainScope, callerIsMain=${NSThread.isMainThread}",
            )
            result.getCompleted()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun localOfferDeliversNativeSignalingAndIceEvents() {
        val scope = MainScope()
        val peer = PeerConnection(RtcConfiguration())
        val signalingEvents = mutableListOf<SignalingState>()
        val gatheringEvents = mutableListOf<String>()
        var candidateEvents = 0
        val candidateAddresses = mutableSetOf<String>()
        var stage = "creating DataChannel"
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peer.onSignalingStateChange.collect { signalingEvents += it }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peer.onIceGatheringState.collect { gatheringEvents += it.name }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peer.onIceCandidate.collect {
                candidateEvents++
                candidateAddresses += it.candidate.split(' ').getOrElse(4) { "unknown" }
            }
        }
        val offerResult = scope.async(start = CoroutineStart.UNDISPATCHED) {
            assertNotNull(peer.createDataChannel("ios-event-loop-probe"))
            stage = "creating local offer"
            val offer = peer.createOffer(OfferAnswerOptions())
            assertTrue(offer.sdp.contains("m=application"), "Offer must contain a real DataChannel section")
            stage = "applying local offer"
            peer.setLocalDescription(offer)
            stage = "waiting for native delegate events"
        }
        try {
            // The outer deadline must work even if Dispatchers.Main cannot deliver coroutine timers.
            val completed = pumpNativeRunLoopUntil(5_000L) {
                offerResult.isCancelled ||
                    (offerResult.isCompleted && candidateEvents > 0 &&
                        SignalingState.HaveLocalOffer in signalingEvents && gatheringEvents.isNotEmpty())
            }
            val rawSdpCandidates = peer.localDescription?.sdp?.lineSequence()
                ?.count { it.startsWith("a=candidate:") } ?: 0
            val diagnostic = "stage=$stage; raw signaling=${peer.signalingState}, " +
                "gathering=${peer.iceGatheringState}, ICE=${peer.iceConnectionState}, " +
                "SDP candidates=$rawSdpCandidates; flow signaling=$signalingEvents, " +
                "gathering=$gatheringEvents, candidates=$candidateEvents, addresses=$candidateAddresses"
            assertTrue(offerResult.isCompleted, diagnostic)
            offerResult.getCompleted()
            assertTrue(completed, diagnostic)
            println("IOS_WEBRTC_EVENTS $diagnostic")
        } finally {
            scope.cancel()
            peer.close()
        }
    }

    private fun pumpNativeRunLoopUntil(timeoutMillis: Long, completed: () -> Boolean): Boolean {
        val started = TimeSource.Monotonic.markNow()
        while (!completed() && started.elapsedNow().inWholeMilliseconds < timeoutMillis) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        }
        return completed()
    }
}

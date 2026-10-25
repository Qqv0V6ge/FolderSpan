package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

internal suspend fun verifyRealWebRtcIcePathAndRestart() {
    var closedConnection: WebRtcPeerConnection? = null
    withRealWebRtcDataChannelPair { outgoing, accepted, initiator, responder ->
        closedConnection = initiator
        val path = withTimeout(5_000L) {
            var current = initiator.getSelectedIcePath()
            while (current.kind == WebRtcIcePathKind.Unknown) { delay(20); current = initiator.getSelectedIcePath() }
            current
        }
        assertEquals(WebRtcIcePathKind.Direct, path.kind)
        assertEquals(WebRtcIceProtocol.Udp, path.localProtocol)
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { initiator.getSelectedIcePath() }
        cancelled.cancelAndJoin()
        val first = initiator.createOffer()
        val restart = initiator.createOffer(iceRestart = true)
        fun ufrag(sdp: String) = sdp.lineSequence().first { it.startsWith("a=ice-ufrag:") }
        assertNotEquals(ufrag(first.sdp), ufrag(restart.sdp))
        initiator.setLocalDescription(restart)
        responder.setRemoteDescription(restart)
        val answer = responder.createAnswer()
        responder.setLocalDescription(answer)
        initiator.setRemoteDescription(answer)
        val received = async(start = CoroutineStart.UNDISPATCHED) { accepted.onMessage.first() }
        assertTrue(outgoing.send(byteArrayOf(1, 2, 3)))
        assertContentEquals(byteArrayOf(1, 2, 3), received.await())
        assertEquals(WebRtcDataChannelState.Open, outgoing.state)
        println("WEBRTC_LOCAL_ICE kind=${path.kind} localFamily=${path.localFamily} remoteFamily=${path.remoteFamily} protocol=${path.localProtocol} restartCredentialsChanged=true sameChannel=true")
    }
    assertEquals(WebRtcIcePath(), checkNotNull(closedConnection).getSelectedIcePath())
}

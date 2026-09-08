package com.folderspan.service.webrtc

import com.folderspan.service.session.DEVICE_SESSION_MAX_PAYLOAD_BYTES
import com.folderspan.service.session.DeviceSessionEndpointRole
import com.folderspan.service.session.DeviceSessionPeer
import com.folderspan.service.session.DeviceSessionTransport
import com.folderspan.service.session.DeviceSessionWindowPlan
import com.folderspan.service.webrtc.models.WebRtcDataChannel
import com.folderspan.service.webrtc.models.WebRtcDataChannelState
import com.folderspan.service.webrtc.models.WebRtcPeerConnection
import com.folderspan.service.webrtc.models.WebRtcIceServerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

// Test suites can supply an explicitly configured local relay on restricted hosts.
internal var realWebRtcTestIceServers: List<WebRtcIceServerConfig> = emptyList()

/** Exchanges SDP and ICE in memory; all Session bytes cross real SCTP/DTLS DataChannels. */
internal suspend fun <T> withRealWebRtcSessionPair(
    plan: DeviceSessionWindowPlan = DeviceSessionWindowPlan(
        sessionWindowBytes = 2 * 1024 * 1024,
        streamWindowBytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES,
        maxFileStreams = 8,
    ),
    block: suspend (DeviceSessionPeer, DeviceSessionPeer, WebRtcDataChannel) -> T,
): T = withRealWebRtcDataChannelPair { outgoing, accepted, _, _ ->
    val client = DeviceSessionPeer(
        DeviceSessionTransport(WebRtcDeviceSessionByteChannel(outgoing, this), plan, plan, isClient = true),
        plan,
        DeviceSessionEndpointRole.Client,
    )
    var server: DeviceSessionPeer? = null
    try {
        val remote = DeviceSessionPeer(
            DeviceSessionTransport(WebRtcDeviceSessionByteChannel(accepted, this), plan, plan, isClient = false),
            plan,
            DeviceSessionEndpointRole.Server,
        ).also { server = it }
        client.start(this)
        remote.start(this)
        yield()
        block(client, remote, outgoing)
    } finally {
        withContext(NonCancellable) {
            try { client.close() } finally { server?.close() }
        }
    }
}

internal suspend fun <T> withRealWebRtcDataChannelPair(
    label: String = WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL,
    blockOwnsConnections: Boolean = false,
    block: suspend CoroutineScope.(WebRtcDataChannel, WebRtcDataChannel, WebRtcPeerConnection, WebRtcPeerConnection) -> T,
): T = coroutineScope {
    val initiator = WebRtcPeerConnection(realWebRtcTestIceServers)
    var responderToClose: WebRtcPeerConnection? = null
    var handedToBlock = false
    val jobs = mutableListOf<Job>()
    val initiatorReady = CompletableDeferred<Unit>()
    val responderReady = CompletableDeferred<Unit>()
    var forwardCandidates = 0
    var reverseCandidates = 0
    var initiatorState = "New"
    var responderState = "New"
    var stage = "creating responder"
    try {
        val responder = WebRtcPeerConnection(realWebRtcTestIceServers).also { responderToClose = it }
        jobs += launch(start = CoroutineStart.UNDISPATCHED) {
            initiator.onConnectionStateChange.collect { initiatorState = it.name }
        }
        jobs += launch(start = CoroutineStart.UNDISPATCHED) {
            responder.onConnectionStateChange.collect { responderState = it.name }
        }
        jobs += launch(start = CoroutineStart.UNDISPATCHED) {
            initiator.onIceCandidate.collect { candidate ->
                responderReady.await()
                responder.addIceCandidate(candidate)
                forwardCandidates++
            }
        }
        jobs += launch(start = CoroutineStart.UNDISPATCHED) {
            responder.onIceCandidate.collect { candidate ->
                initiatorReady.await()
                initiator.addIceCandidate(candidate)
                reverseCandidates++
            }
        }
        val incoming = async(start = CoroutineStart.UNDISPATCHED) { responder.onDataChannel.first() }
        jobs += incoming
        stage = "creating DataChannel and offer"
        withTimeout(30_000L) {
            val outgoing = checkNotNull(initiator.createDataChannel(label))
            val offer = initiator.createOffer()
            stage = "applying offer"
            initiator.setLocalDescription(offer)
            responder.setRemoteDescription(offer)
            responderReady.complete(Unit)
            val answer = responder.createAnswer()
            stage = "applying answer"
            responder.setLocalDescription(answer)
            initiator.setRemoteDescription(answer)
            initiatorReady.complete(Unit)
            stage = "waiting for incoming DataChannel"
            val accepted = incoming.await()
            stage = "waiting for both DataChannels to open"
            while (outgoing.state != WebRtcDataChannelState.Open || accepted.state != WebRtcDataChannelState.Open) {
                delay(10L)
            }
            stage = "running DataChannel checks"
            handedToBlock = true
            block(outgoing, accepted, initiator, responder)
        }
    } catch (error: TimeoutCancellationException) {
        error("Real WebRTC timed out while $stage: ICE=$forwardCandidates/$reverseCandidates " +
            "states=$initiatorState/$responderState; ${error.message}")
    } finally {
        withContext(NonCancellable) {
            jobs.forEach { it.cancelAndJoin() }
            if (!handedToBlock || !blockOwnsConnections) {
                try { initiator.close() } finally { responderToClose?.close() }
            }
        }
    }
}

package com.folderspan.service.webrtc

import com.folderspan.service.session.DEVICE_SESSION_RPC_OK
import com.folderspan.service.session.DeviceSessionControlResponse
import com.folderspan.service.session.DeviceSessionEndpointRole
import com.folderspan.service.session.DeviceSessionIncomingStream
import com.folderspan.service.session.DeviceSessionPeer
import com.folderspan.service.session.DeviceSessionTransport
import com.folderspan.service.session.DeviceSessionWindowPlan
import com.folderspan.service.webrtc.models.WebRtcDataChannel
import com.folderspan.service.webrtc.models.WebRtcDataChannelState
import com.folderspan.service.webrtc.models.WebRtcIceCandidate
import com.folderspan.service.webrtc.models.WebRtcPeerConnection
import com.folderspan.service.webrtc.models.WebRtcSdpType
import com.folderspan.service.webrtc.models.WebRtcSessionDescription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
internal data class InteropSignal(
    val kind: String,
    val value: String = "",
    val mid: String = "",
    val line: Int = 0,
)

/** Only signaling crosses HTTP. RPC and file bytes use each platform's production Session stack. */
internal suspend fun verifyWebRtcInterop(
    initiator: Boolean,
    sendSignal: suspend (InteropSignal) -> Unit,
    receiveSignal: suspend () -> InteropSignal,
) = coroutineScope {
    val connection = WebRtcPeerConnection(emptyList())
    val jobs = mutableListOf<kotlinx.coroutines.Job>()
    var peer: DeviceSessionPeer? = null
    try {
        withTimeout(120_000L) {
            val channelReady = CompletableDeferred<WebRtcDataChannel>()
            val remoteFinished = CompletableDeferred<Unit>()
            jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                connection.onIceCandidate.collect {
                    sendSignal(InteropSignal("ice", it.candidate, it.sdpMid, it.sdpMLineIndex))
                }
            }
            jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                channelReady.complete(connection.onDataChannel.first())
            }
            jobs += launch {
                while (true) {
                    val signal = receiveSignal()
                    when (signal.kind) {
                        "offer" -> {
                            connection.setRemoteDescription(WebRtcSessionDescription(WebRtcSdpType.Offer, signal.value))
                            val answer = connection.createAnswer()
                            sendSignal(InteropSignal("answer", answer.sdp))
                            connection.setLocalDescription(answer)
                        }
                        "answer" -> connection.setRemoteDescription(
                            WebRtcSessionDescription(WebRtcSdpType.Answer, signal.value)
                        )
                        "ice" -> connection.addIceCandidate(WebRtcIceCandidate(signal.mid, signal.line, signal.value))
                        "finished" -> remoteFinished.complete(Unit)
                        else -> error("Unexpected interop signal: ${signal.kind}")
                    }
                }
            }
            if (initiator) {
                channelReady.complete(checkNotNull(connection.createDataChannel(WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL)))
                val offer = connection.createOffer()
                sendSignal(InteropSignal("offer", offer.sdp))
                connection.setLocalDescription(offer)
            }
            val channel = channelReady.await()
            assertEquals(WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL, channel.label)
            while (channel.state != WebRtcDataChannelState.Open) delay(10)
            println("WEBRTC_INTEROP_STAGE initiator=$initiator channel=open")
            val plan = DeviceSessionWindowPlan.fromMemory()
            val activePeer = DeviceSessionPeer(
                DeviceSessionTransport(WebRtcDeviceSessionByteChannel(channel, this), plan, plan, isClient = initiator),
                plan,
                if (initiator) DeviceSessionEndpointRole.Client else DeviceSessionEndpointRole.Server,
            )
            peer = activePeer
            var receivedFiles = 0
            var receivedRpc = 0
            var activeStreams = 0
            var peakActiveStreams = 0
            var rpcDuringFile = false
            val firstFileData = CompletableDeferred<Unit>()
            val releasePayload = CompletableDeferred<Unit>()
            activePeer.setRequestHandler { request ->
                assertEquals("Echo", request.method)
                receivedRpc++
                rpcDuringFile = rpcDuringFile || activeStreams > 0
                DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, request.payload)
            }
            activePeer.setStreamOpenHandler { streamId, open ->
                activeStreams++
                peakActiveStreams = maxOf(peakActiveStreams, activeStreams)
                val salt = open.path.substringAfterLast('-').toInt()
                object : DeviceSessionIncomingStream {
                    var offset = 0
                    override suspend fun onData(chunk: ByteArray) {
                        assertRealWebRtcContent(interopBytes(offset, chunk.size, salt), chunk)
                        offset += chunk.size
                        firstFileData.complete(Unit)
                    }
                    override suspend fun onTrailer(payload: ByteArray) {
                        assertEquals(512 * 1024, offset)
                        receivedFiles++
                        activeStreams--
                        activePeer.connection.sendTrailer(streamId)
                    }
                }
            }
            activePeer.start(this)
            val writes = (1..3).map { index ->
                async {
                    val salt = index + if (initiator) 0 else 10
                    activePeer.openWriteStream("/interop-$salt", 512L * 1024, 0L, data = flow {
                        repeat(8) { block ->
                            emit(interopBytes(block * 65536, 65536, salt))
                            if (block == 0) releasePayload.await()
                        }
                    })
                }
            }
            firstFileData.await()
            println("WEBRTC_INTEROP_STAGE initiator=$initiator fileData=received")
            repeat(10) { index ->
                val payload = "interop-$initiator-$index".encodeToByteArray()
                val response = activePeer.rpc("Echo", payload)
                assertEquals(DEVICE_SESSION_RPC_OK, response.status)
                assertContentEquals(payload, response.payload)
            }
            releasePayload.complete(Unit)
            writes.awaitAll()
            println("WEBRTC_INTEROP_STAGE initiator=$initiator writes=complete")
            sendSignal(InteropSignal("finished"))
            remoteFinished.await()
            assertEquals(3, receivedFiles)
            assertEquals(10, receivedRpc)
            assertTrue(peakActiveStreams > 1)
            assertTrue(rpcDuringFile)
            println("WEBRTC_INTEROP initiator=$initiator sentFiles=3 receivedFiles=3 rpc=10 contentVerified=true")
            // Finish child jobs before leaving withTimeout's structured scope.
            activePeer.close()
            jobs.forEach { it.cancelAndJoin() }
        }
    } finally {
        withContext(NonCancellable) {
            jobs.forEach { it.cancelAndJoin() }
            try { peer?.close() } finally { connection.close() }
        }
    }
}

private fun interopBytes(offset: Int, size: Int, salt: Int) =
    ByteArray(size) { index -> ((offset + index) * 31 + (offset + index) / 251 + salt * 17).toByte() }

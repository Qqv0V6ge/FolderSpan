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
import com.folderspan.service.webrtc.models.*
import com.folderspan.service.webrtc.controller.core.WebRtcRemoteNegotiation
import com.folderspan.service.data.DeviceConnectRequest
import kotlinx.coroutines.CoroutineScope
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

@Serializable
internal data class WebRtcInteropConfig(
    val stunUrl: String = "",
    val turnUrl: String = "",
    val turnUsername: String = "",
    val turnPassword: String = "",
    val expectedPath: String = "",
    val expectedFamily: String = "",
    val expectedProtocol: String = "",
    val networkScenario: String = "local",
) {
    fun iceServers() = buildWebRtcIceServers(stunUrl, turnUrl, turnUsername, turnPassword)
    fun assertPath(path: WebRtcIcePath) {
        assertTrue(path.kind != WebRtcIcePathKind.Unknown, "Selected pair must be observable")
        if (expectedPath.isNotEmpty()) assertEquals(expectedPath, path.kind.name)
        if (expectedFamily.isNotEmpty()) {
            assertEquals(expectedFamily, path.localFamily.name)
            assertEquals(expectedFamily, path.remoteFamily.name)
        }
        if (expectedProtocol.isNotEmpty()) {
            assertEquals(expectedProtocol, path.localProtocol.name)
            assertEquals(expectedProtocol, path.remoteProtocol.name)
        }
    }
}

@Serializable
internal data class WebRtcInteropBootstrap(
    val request: DeviceConnectRequest,
    val snapshot: WebRtcIceSnapshot,
    val filePath: String,
    val fileSize: Int,
    val sha256: String,
)

@Serializable
internal data class WebRtcInteropEvidence(
    val observation: WebRtcIceSnapshot,
    val sha256: String,
    val rpcVerified: Boolean,
    val iceRestartApplied: Boolean,
    val pathSamplePhase: String = "before-restart",
)

/** Only signaling crosses HTTP. RPC and file bytes use each platform's production Session stack. */
internal suspend fun verifyWebRtcInterop(
    initiator: Boolean,
    sendSignal: suspend (InteropSignal) -> Unit,
    receiveSignal: suspend () -> InteropSignal,
    config: WebRtcInteropConfig = WebRtcInteropConfig(),
    authenticatedSession: (suspend CoroutineScope.(WebRtcDataChannel, WebRtcPeerConnection, CompletableDeferred<Unit>, suspend () -> Unit) -> Unit)? = null,
) = coroutineScope {
    val connection = WebRtcPeerConnection(config.iceServers())
    val jobs = mutableListOf<kotlinx.coroutines.Job>()
    var peer: DeviceSessionPeer? = null
    var current = true
    var restartAnswer: CompletableDeferred<Unit>? = null
    val remote = WebRtcRemoteNegotiation(this, { current }, connection::addIceCandidate, { throw it })
    suspend fun applyRemote(description: WebRtcSessionDescription) {
        val applied = CompletableDeferred<Unit>()
        check(remote.description(description.sdp.webRtcIceUfrags()) {
            connection.setRemoteDescription(description)
            applied.complete(Unit)
        })
        applied.await()
    }
    try {
        withTimeout(120_000L) {
            val channelReady = CompletableDeferred<WebRtcDataChannel>()
            val remoteFinished = CompletableDeferred<Unit>()
            jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                connection.onIceCandidate.collect {
                    val parts = it.candidate.split(' ')
                    val address = parts.getOrNull(4).orEmpty()
                    val family = when {
                        isWebRtcIpv4(address) -> "IPv4"
                        isWebRtcIpv6(address) -> "IPv6"
                        else -> "Unknown"
                    }
                    println("WEBRTC_INTEROP_CANDIDATE initiator=$initiator family=$family protocol=${parts.getOrNull(2)} type=${parts.getOrNull(7)}")
                    sendSignal(InteropSignal("ice", it.candidate, it.sdpMid, it.sdpMLineIndex))
                }
            }
            jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                channelReady.complete(connection.onDataChannel.first())
            }
            jobs += launch {
                while (true) {
                    val signal = receiveSignal()
                    println("WEBRTC_INTEROP_SIGNAL initiator=$initiator kind=${signal.kind}")
                    when (signal.kind) {
                        "offer" -> {
                            applyRemote(WebRtcSessionDescription(WebRtcSdpType.Offer, signal.value))
                            val answer = connection.createAnswer()
                            sendSignal(InteropSignal("answer", answer.sdp))
                            connection.setLocalDescription(answer)
                        }
                        "answer" -> {
                            applyRemote(WebRtcSessionDescription(WebRtcSdpType.Answer, signal.value))
                            restartAnswer?.complete(Unit)
                        }
                        "ice" -> check(remote.candidate(WebRtcIceCandidate(signal.mid, signal.line, signal.value)))
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
            val channel = withTimeout(30_000L) {
                val ready = channelReady.await()
                assertEquals(WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL, ready.label)
                while (ready.state != WebRtcDataChannelState.Open) delay(10)
                ready
            }
            println("WEBRTC_INTEROP_STAGE initiator=$initiator channel=open")
            if (authenticatedSession != null) {
                authenticatedSession(channel, connection, remoteFinished) {
                    val ordinary = connection.createOffer()
                    val restart = connection.createOffer(iceRestart = true)
                    assertTrue(ordinary.sdp.webRtcIceUfrags().isNotEmpty())
                    assertTrue(ordinary.sdp.webRtcIceUfrags() != restart.sdp.webRtcIceUfrags())
                    val answerReady = CompletableDeferred<Unit>().also { restartAnswer = it }
                    connection.setLocalDescription(restart)
                    sendSignal(InteropSignal("offer", restart.sdp))
                    withTimeout(10_000L) { answerReady.await() }
                    restartAnswer = null
                    assertEquals(WebRtcDataChannelState.Open, channel.state)
                }
                sendSignal(InteropSignal("finished"))
                remoteFinished.await()
                jobs.forEach { it.cancelAndJoin() }
                return@withTimeout
            }
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
    } catch (error: Throwable) {
        println("WEBRTC_INTEROP_FAILED initiator=$initiator error=${error::class.simpleName} iceServiceCode=${connection.lastIceServerErrorCode}")
        throw error
    } finally {
        withContext(NonCancellable) {
            current = false
            remote.close()
            jobs.forEach { it.cancelAndJoin() }
            try { peer?.close() } finally { connection.close() }
        }
    }
}

private fun interopBytes(offset: Int, size: Int, salt: Int) =
    ByteArray(size) { index -> ((offset + index) * 31 + (offset + index) / 251 + salt * 17).toByte() }

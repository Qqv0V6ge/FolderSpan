package com.folderspan.service.webrtc

import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.service.data.DeviceConnectRequest
import com.folderspan.service.message.deviceMessageSha256
import com.folderspan.service.session.*
import com.folderspan.service.webrtc.models.*
import com.folderspan.utils.ProtoBufCodec
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.*

internal suspend fun CoroutineScope.verifyAuthenticatedDiagnosticClient(
    channel: WebRtcDataChannel,
    connection: WebRtcPeerConnection,
    config: WebRtcInteropConfig,
    http: HttpClient,
    bridgeUrl: String,
    restart: suspend () -> Unit,
) {
    val bootstrap = withTimeout(10_000L) {
        var result: WebRtcInteropBootstrap? = null
        while (result == null) {
            val response = http.get("$bridgeUrl/bootstrap")
            if (response.status == HttpStatusCode.OK) result = Json.decodeFromString(response.bodyAsText())
            else { assertEquals(HttpStatusCode.NoContent, response.status); delay(25) }
        }
        result
    }
    println("WEBRTC_INTEROP_STAGE diagnostic=bootstrap-ready")
    val expected = bootstrap.snapshot
    val query = ProtoBufCodec.encode(DeviceSessionIceQuery(expected.connectionAttemptId, expected.negotiation))
    val clientScope = this
    val runtime = withTimeout(15_000L) {
        DeviceSessionClientRuntimeLauncher.start(
            channel = WebRtcDeviceSessionByteChannel(channel, clientScope), scope = clientScope,
            authentication = DeviceSessionClientAuthenticationContext(expected.observerDeviceId) { peer ->
                assertEquals(DEVICE_SESSION_RPC_UNAUTHORIZED, peer.rpc(DEVICE_SESSION_RPC_WEBRTC_ICE, query).status)
                peer.rpcValue<DeviceConnectRequest, DeviceSessionConnectResponse>(DEVICE_SESSION_RPC_CONNECT, bootstrap.request)
            },
        )
    }
    try {
        println("WEBRTC_INTEROP_STAGE diagnostic=authenticated")
        assertEquals(DeviceConnectType.APPROVED, runtime.connectResponse.connection.connectType)
        val response = runtime.peer.rpc(DEVICE_SESSION_RPC_WEBRTC_ICE, query)
        assertEquals(DEVICE_SESSION_RPC_OK, response.status)
        val snapshot = ProtoBufCodec.decode<WebRtcIceSnapshot>(response.payload)
        assertEquals(expected, snapshot)
        val observation = snapshot.asRemoteObservation(bootstrap.request.device.id, expected.observerDeviceId, expected.connectionAttemptId, 0)
        assertNotNull(observation)
        assertEquals(WebRtcIceObservationSource.Remote, observation.source)
        config.assertPath(observation.path)
        for (invalid in listOf(DeviceSessionIceQuery("other-attempt", 0), DeviceSessionIceQuery(expected.connectionAttemptId, 1))) {
            assertEquals(DEVICE_SESSION_RPC_NOT_FOUND, runtime.peer.rpc(DEVICE_SESSION_RPC_WEBRTC_ICE, ProtoBufCodec.encode(invalid)).status)
        }
        val bytes = ByteArray(bootstrap.fileSize)
        var offset = 0
        assertTrue(runtime.clients.readStream(bootstrap.filePath, 0L, bootstrap.fileSize.toLong()) { chunk ->
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }.getOrThrow())
        assertEquals(bootstrap.fileSize, offset)
        assertEquals(bootstrap.sha256, bytes.deviceMessageSha256())
        println("WEBRTC_INTEROP_STAGE diagnostic=file-verified")
        if (config.networkScenario == "turn-udp-cancel") {
            val pendingConnection = WebRtcPeerConnection(config.iceServers())
            val relayReady = CompletableDeferred<Unit>()
            val pendingAttempt = launch {
                try {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        pendingConnection.onIceCandidate.first { it.candidate.split(' ').getOrNull(7) == "relay" }
                        relayReady.complete(Unit)
                    }
                    assertNotNull(pendingConnection.createDataChannel(WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL))
                    pendingConnection.setLocalDescription(pendingConnection.createOffer())
                    awaitCancellation()
                } finally { pendingConnection.close() }
            }
            try { withTimeout(5_000L) { relayReady.await() } } finally { pendingAttempt.cancelAndJoin() }
            assertTrue(pendingAttempt.isCancelled)
            assertEquals(WebRtcIcePath(), pendingConnection.getSelectedIcePath())
            assertNull(pendingConnection.lastIceServerErrorCode)
            assertEquals(DEVICE_SESSION_RPC_OK, runtime.peer.rpc(DEVICE_SESSION_RPC_WEBRTC_ICE, query).status)
            val afterCancel = runtime.clients.readBytes(bootstrap.filePath, 0L, bootstrap.fileSize.toLong()).getOrThrow()
            assertEquals(bootstrap.sha256, afterCancel.deviceMessageSha256())
            println("WEBRTC_INTEROP_CANCEL pendingRelayAllocated=true closed=true activeRpcVerified=true activeFileVerified=true")
        }
        restart()
        assertEquals(DEVICE_SESSION_RPC_OK, runtime.peer.rpc(DEVICE_SESSION_RPC_WEBRTC_ICE, query).status)
        val afterRestart = runtime.clients.readBytes(bootstrap.filePath, 0L, bootstrap.fileSize.toLong()).getOrThrow()
        assertEquals(bootstrap.sha256, afterRestart.deviceMessageSha256())
        val localPath = withTimeout(5_000L) {
            var path = connection.getSelectedIcePath()
            while (interopHasLocalIceStats() && runCatching { config.assertPath(path) }.isFailure) {
                delay(25)
                path = connection.getSelectedIcePath()
            }
            path
        }
        if (interopHasLocalIceStats()) config.assertPath(localPath) else assertEquals(WebRtcIcePath(), localPath)
        println("WEBRTC_ICE_CLIENT_PATH " + Json { encodeDefaults = true }.encodeToString(localPath))
        val evidence = WebRtcInteropEvidence(observation, afterRestart.deviceMessageSha256(), rpcVerified = true, iceRestartApplied = true)
        assertEquals(HttpStatusCode.OK, http.post("$bridgeUrl/evidence") { setBody(Json.encodeToString(evidence)) }.status)
        println("WEBRTC_DIAGNOSTIC_CLIENT source=${observation.source} observer=${observation.observerDeviceId} kind=${observation.path.kind} localFamily=${observation.path.localFamily} remoteFamily=${observation.path.remoteFamily} observerTurnTransport=${observation.path.localTurnTransport} rpcVerified=true restartCredentialsChanged=true localStats=${localPath.kind} sha256=${bytes.deviceMessageSha256()}")
    } finally { runtime.close() }
}

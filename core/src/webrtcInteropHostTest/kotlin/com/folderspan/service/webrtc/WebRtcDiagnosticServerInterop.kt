package com.folderspan.service.webrtc

import com.folderspan.getSocketDevice
import com.folderspan.service.data.DeviceConnectAuthorizationMode
import com.folderspan.service.data.DeviceConnectRequest
import com.folderspan.service.message.deviceMessageSha256
import com.folderspan.service.session.ShareSaveFixture
import com.folderspan.service.session.createWebRtcDeviceSessionServerLauncher
import com.folderspan.service.webrtc.models.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals

internal suspend fun CoroutineScope.verifyAuthenticatedDiagnosticServer(
    channel: WebRtcDataChannel,
    connection: WebRtcPeerConnection,
    config: WebRtcInteropConfig,
    remoteFinished: CompletableDeferred<Unit>,
    publishBootstrap: suspend (WebRtcInteropBootstrap?) -> Unit,
    readEvidence: suspend () -> WebRtcInteropEvidence,
): List<WebRtcInteropEvidence> = ShareSaveFixture().use { fixture ->
    fixture.start()
    fixture.allowOrdinaryRead()
    val source = getSocketDevice()
    val client = source.withCopy(id = "webrtc-interop-client")
    val attempt = "webrtc-interop-attempt"
    val authorization = fixture.devices.deviceSessionBootstrapAuthorizationRegistry.issue(
        initiatorDeviceId = client.id, targetDeviceId = source.id,
        connectionAttemptId = attempt, roleId = 2L,
    )
    val path = withTimeout(5_000L) {
        var path = connection.getSelectedIcePath()
        while (runCatching { config.assertPath(path) }.isFailure) {
            delay(20)
            path = connection.getSelectedIcePath()
        }
        path
    }
    config.assertPath(path)
    val snapshot = WebRtcIceSnapshot(source.id, client.id, attempt, 0, path)
    var authenticated = 0
    val job = launch {
        checkNotNull(createWebRtcDeviceSessionServerLauncher()).run(
            WebRtcDeviceSessionByteChannel(channel, this), client.id, attempt, this, { snapshot },
        ) { authenticated++; println("WEBRTC_INTEROP_STAGE native=authenticated") }
    }
    try {
        publishBootstrap(WebRtcInteropBootstrap(
            DeviceConnectRequest(device = client, authorizationMode = DeviceConnectAuthorizationMode.STANDARD, bootstrapAuthorization = authorization),
            snapshot, fixture.input.toString(), fixture.bytes.size, fixture.bytes.deviceMessageSha256(),
        ))
        println("WEBRTC_INTEROP_STAGE native=bootstrap-ready")
        remoteFinished.await()
        assertEquals(1, authenticated)
        val evidence = readEvidence()
        assertEquals(snapshot.copy(source = WebRtcIceObservationSource.Remote), evidence.observation)
        assertEquals(fixture.bytes.deviceMessageSha256(), evidence.sha256)
        assertEquals(true, evidence.rpcVerified)
        assertEquals(true, evidence.iceRestartApplied)
        println("WEBRTC_DIAGNOSTIC_NATIVE source=Local observer=${source.id} kind=${path.kind} localFamily=${path.localFamily} remoteFamily=${path.remoteFamily} protocol=${path.localProtocol} localTurnTransport=${path.localTurnTransport} authenticated=1 sha256=${fixture.bytes.deviceMessageSha256()}")
        listOf(evidence.copy(observation = snapshot), evidence)
    } finally {
        try { publishBootstrap(null) } finally { job.cancelAndJoin() }
    }
}

package com.folderspan.service.session

import com.folderspan.getSocketDevice
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.service.data.DeviceConnectAuthorizationMode
import com.folderspan.service.data.DeviceConnectRequest
import com.folderspan.service.webrtc.WebRtcDeviceSessionByteChannel
import com.folderspan.service.webrtc.withRealWebRtcDataChannelPair
import com.folderspan.service.webrtc.models.*
import com.folderspan.utils.ProtoBufCodec
import kotlinx.coroutines.*
import kotlin.test.*

class WebRtcIceDiagnosticJvmTest {
    @Test
    fun authenticatedRpcOnlyReportsBoundConnectionAndCurrentNegotiation() = runBlocking {
        ShareSaveFixture().use { fixture ->
            fixture.start()
            val source = getSocketDevice()
            val clientDevice = source.withCopy(id = "diagnostic-client")
            val attempt = "diagnostic-attempt"
            val authorization = fixture.devices.deviceSessionBootstrapAuthorizationRegistry.issue(
                initiatorDeviceId = clientDevice.id, targetDeviceId = source.id, connectionAttemptId = attempt, roleId = 2L,
            )
            withRealWebRtcDataChannelPair { outgoing, incoming, _, native ->
                var snapshot: WebRtcIceSnapshot? = WebRtcIceSnapshot(source.id, clientDevice.id, attempt, 0, native.getSelectedIcePath())
                val initial = assertNotNull(snapshot)
                val probe = CompletableDeferred<DeviceSessionConnectivityProbe>()
                val server = launch {
                    checkNotNull(createWebRtcDeviceSessionServerLauncher()).run(
                        WebRtcDeviceSessionByteChannel(incoming, this), clientDevice.id, attempt, this, { snapshot },
                    ) { probe.complete(it) }
                }
                val runtime = DeviceSessionClientRuntimeLauncher.start(
                    WebRtcDeviceSessionByteChannel(outgoing, this), this,
                    DeviceSessionClientAuthenticationContext(source.id) { peer ->
                        assertEquals(DEVICE_SESSION_RPC_UNAUTHORIZED, peer.rpc(DEVICE_SESSION_RPC_WEBRTC_ICE, ProtoBufCodec.encode(DeviceSessionIceQuery(attempt, 0))).status)
                        peer.rpcValue<DeviceConnectRequest, DeviceSessionConnectResponse>(DEVICE_SESSION_RPC_CONNECT,
                            DeviceConnectRequest(device = clientDevice, authorizationMode = DeviceConnectAuthorizationMode.STANDARD, bootstrapAuthorization = authorization))
                    },
                )
                try {
                    assertEquals(DeviceConnectType.APPROVED, runtime.connectResponse.connection.connectType)
                    suspend fun query(id: String = attempt, revision: Int = 0) = runtime.peer.rpc(
                        DEVICE_SESSION_RPC_WEBRTC_ICE, ProtoBufCodec.encode(DeviceSessionIceQuery(id, revision)),
                    )
                    assertEquals(initial, ProtoBufCodec.decode<WebRtcIceSnapshot>(query().payload))
                    assertEquals(DEVICE_SESSION_RPC_NOT_FOUND, query("other-attempt").status)
                    snapshot = initial.copy(remoteDeviceId = "other-device")
                    assertEquals(DEVICE_SESSION_RPC_NOT_FOUND, query().status)
                    snapshot = null
                    assertEquals(DEVICE_SESSION_RPC_NOT_FOUND, query().status)
                    snapshot = initial.copy(negotiation = 1)
                    assertEquals(DEVICE_SESSION_RPC_NOT_FOUND, query().status)
                    assertEquals(DEVICE_SESSION_RPC_OK, query(revision = 1).status)
                    val boundProbe = probe.await()
                    assertTrue(withTimeout(2_000L) { boundProbe() })
                    fixture.certificates.removeToken(runtime.connectResponse.connection.token)
                    assertFalse(boundProbe())
                    withTimeout(2_000L) { runtime.sessionJob.join() }
                    assertFalse(runtime.sessionJob.isActive)
                } finally {
                    runtime.close()
                    server.cancelAndJoin()
                }
            }
        }
    }
}

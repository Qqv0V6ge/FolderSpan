package com.folderspan.service.session

import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.getSocketDevice
import com.folderspan.service.data.DeviceConnectAuthorizationMode
import com.folderspan.service.data.DeviceConnectRequest
import com.folderspan.service.data.DeviceSessionBootstrapAuthorization
import com.folderspan.service.webrtc.WebRtcDeviceSessionByteChannel
import com.folderspan.service.webrtc.signaling.SignalingDevice
import com.folderspan.service.webrtc.signaling.withOfficialIdentity
import com.folderspan.service.webrtc.withRealWebRtcDataChannelPair
import com.folderspan.ui.state.device.DeviceSharePathGrant
import com.folderspan.ui.state.device.DeviceSharePathScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OfficialWebRtcDeviceSessionJvmTest {
    @Test
    fun sameWireDeviceIdCannotReuseApprovalFileScopeOrRevocationAcrossAccounts() = runBlocking {
        ShareSaveFixture().use { fixture ->
            fixture.start()
            val owner = scopedId("owner", "550e8400-e29b-41d4-a716-446655440001")
            val approved = scopedId("same-device", "550e8400-e29b-41d4-a716-446655440002")
            val other = scopedId("same-device", "550e8400-e29b-41d4-a716-446655440003")
            val registry = fixture.devices.deviceSessionBootstrapAuthorizationRegistry
            val input = fixture.input.toString()
            val outside = fixture.outside.toString()
            val approval = registry.issue(approved, owner, "first-attempt", 2L, fileScope(input))

            connect(owner, other, "first-attempt", approval) { rejected ->
                assertEquals(DeviceConnectType.REJECTED, rejected.connectResponse.connection.connectType)
                assertTrue(rejected.connectResponse.connection.token.isBlank())
            }
            assertEquals(1, registry.pendingCount())

            connect(owner, approved, "first-attempt", approval) { first ->
                val firstToken = first.connectResponse.connection.token
                assertEquals(DeviceConnectType.APPROVED, first.connectResponse.connection.connectType)
                assertEquals(approved, fixture.certificates.getDeviceIdByToken(firstToken))
                assertContentEquals(fixture.bytes, first.clients.readBytes("/payload.bin", 0, fixture.bytes.size.toLong()).getOrThrow())
                assertTrue(first.clients.readBytes("/private.bin", 0, 1).isFailure)

                val secondApproval = registry.issue(other, owner, "second-attempt", 2L, fileScope(outside))
                connect(owner, other, "second-attempt", secondApproval) { second ->
                    val secondToken = second.connectResponse.connection.token
                    assertEquals(DeviceConnectType.APPROVED, second.connectResponse.connection.connectType)
                    assertEquals(other, fixture.certificates.getDeviceIdByToken(secondToken))
                    assertNotEquals(firstToken, secondToken)
                    assertTrue(second.clients.readBytes("/payload.bin", 0, 1).isFailure)

                    fixture.certificates.removeDeviceToken(approved)
                    assertTrue(first.clients.readBytes("/payload.bin", 0, 1).isFailure)
                    assertContentEquals(byteArrayOf(9), second.clients.readBytes("/private.bin", 0, 1).getOrThrow())
                }
            }
            assertEquals(0, registry.pendingCount())
        }
    }

    private fun scopedId(id: String, account: String): String =
        checkNotNull(SignalingDevice(id = id, userUuid = account).withOfficialIdentity()).id

    private fun fileScope(path: String) = DeviceSharePathScope(listOf(DeviceSharePathGrant(path, isDirectory = false)))

    private suspend fun connect(
        owner: String,
        remote: String,
        attempt: String,
        authorization: DeviceSessionBootstrapAuthorization,
        verify: suspend (DeviceSessionClientRuntime) -> Unit,
    ) = coroutineScope {
        withRealWebRtcDataChannelPair { outgoing, incoming, _, _ ->
            val server = launch {
                checkNotNull(createWebRtcDeviceSessionServerLauncher(officialLocalDeviceId = owner)).run(
                    WebRtcDeviceSessionByteChannel(incoming, this), remote, attempt, this, { null },
                ) {}
            }
            var client: DeviceSessionClientRuntime? = null
            try {
                val runtime = DeviceSessionClientRuntimeLauncher.start(
                    channel = WebRtcDeviceSessionByteChannel(outgoing, this),
                    scope = this,
                    authentication = DeviceSessionClientAuthenticationContext(owner) { peer ->
                        peer.rpcValue<DeviceConnectRequest, DeviceSessionConnectResponse>(
                            DEVICE_SESSION_RPC_CONNECT,
                            DeviceConnectRequest(
                                device = getSocketDevice().withCopy(id = remote),
                                authorizationMode = DeviceConnectAuthorizationMode.STANDARD,
                                bootstrapAuthorization = authorization,
                            ),
                        )
                    },
                )
                client = runtime
                verify(runtime)
            } finally {
                client?.close()
                server.cancelAndJoin()
            }
        }
    }
}

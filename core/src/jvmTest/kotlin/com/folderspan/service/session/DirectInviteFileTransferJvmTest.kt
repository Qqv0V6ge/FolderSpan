package com.folderspan.service.session

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.webrtc.WebRtcShareCode
import com.folderspan.getSocketDevice
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.http.tls.DeviceTlsIdentity
import com.folderspan.service.webrtc.controller.MultiPeerWebRtcController
import com.folderspan.service.webrtc.models.WebRtcConfig
import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import com.folderspan.service.webrtc.signaling.generateRoomId
import com.folderspan.utils.ProtoBufCodec
import java.net.InetAddress
import java.net.SocketException
import java.nio.file.Files
import java.security.MessageDigest
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class DirectInviteFileTransferJvmTest {
    @Test
    fun onePasteReadsAuthorizedFileAndSurvivesRoomChanges() = verifyTransfer("127.0.0.1", revoke = false)

    @Test
    fun ipv6InviteUsesTheSameSessionAndRevocationClosesBothEnds() = verifyTransfer("::1", revoke = true)

    @Test
    fun lostAnswerReconnectsUsingTheSameAttempt() = verifyTransfer("127.0.0.1", revoke = false, dropFirstAnswer = true)

    @Test
    fun twoConcurrentInvitesKeepTheirDeviceSessionsIndependent() = newSingleThreadContext("direct-invite-pair").use { dispatcher ->
        runBlocking<Unit>(dispatcher) {
            ShareSaveFixture().use { fixture ->
                fixture.start()
                fixture.allowOrdinaryRead()
                val registry = fixture.devices.deviceSessionBootstrapAuthorizationRegistry
                val inviter = DirectInviteCoordinator(this, registry)
                val receivers = listOf("direct-guest-b", "direct-guest-c").map { id ->
                    DirectInviteCoordinator(this, DeviceSessionBootstrapAuthorizationRegistry()) {
                        getSocketDevice().withCopy(id = id)
                    }
                }
                val loopback = InetAddress.getByName("127.0.0.1")
                val listener = DeviceTlsIdentity.createSessionServerSslContext().serverSocketFactory
                    .createServerSocket(0, 4, loopback) as SSLServerSocket
                val server = launch(Dispatchers.IO) {
                    repeat(receivers.size) {
                        val socket = try {
                            listener.accept() as SSLSocket
                        } catch (error: SocketException) {
                            if (listener.isClosed) return@launch
                            throw error
                        }
                        launch {
                            socket.use {
                                it.advertiseDeviceSessionAlpn()
                                it.soTimeout = 12_000
                                it.startHandshake()
                                check(it.hasDeviceSessionAlpn())
                                DeviceSessionServerRuntimeLauncher.run(
                                    fixture.dispatcher, JvmDeviceSessionByteChannel(it), loopback.hostAddress, this,
                                    DeviceSessionServerApprovalContext.Standard,
                                    directInviteControl = { inviter.controlHandler(currentDirectInviteIssuer()) },
                                )
                            }
                        }
                    }
                }
                try {
                    val invites = receivers.map { inviter.issue(loopback.hostAddress, listener.localPort, 2, "") }
                    val attempts = receivers.zip(invites).map { (receiver, invite) ->
                        async {
                            receiver.connect(WebRtcShareCode.encodeDirectInvite(invite))
                            receiver.states.value.entries.single { it.value == WebRtcConnectionStatus.Connected }.key
                        }
                    }.map { it.await() }
                    assertNotEquals(attempts[0], attempts[1])
                    withTimeout(8_000) {
                        inviter.states.first { states ->
                            states.keys == attempts.toSet() && states.values.all { it == WebRtcConnectionStatus.Connected }
                        }
                    }
                    assertEquals(0, registry.pendingCount())
                    receivers[0].disconnect(attempts[0])
                    withTimeout(8_000) { inviter.states.first { it.keys == setOf(attempts[1]) } }
                    val client = assertNotNull(receivers[1].clients(attempts[1]))
                    val bytes = client.readBytes(fixture.input.toString(), 0, fixture.bytes.size.toLong()).getOrThrow()
                    assertTrue(fixture.bytes.contentEquals(bytes))
                } finally {
                    receivers.forEach { it.stop() }
                    inviter.stop()
                    listener.close()
                    server.cancelAndJoin()
                }
            }
        }
    }

    private fun verifyTransfer(host: String, revoke: Boolean, dropFirstAnswer: Boolean = false) =
        newSingleThreadContext("direct-invite-test").use { dispatcher ->
        runBlocking<Unit>(dispatcher) {
            ShareSaveFixture().use { fixture ->
                fixture.start()
                fixture.allowOrdinaryRead()
                val registry = fixture.devices.deviceSessionBootstrapAuthorizationRegistry
                val inviter = DirectInviteCoordinator(this, registry)
                val receiver = DirectInviteCoordinator(this, DeviceSessionBootstrapAuthorizationRegistry()) {
                    getSocketDevice().withCopy(id = "direct-invite-test-receiver")
                }
                val loopback = InetAddress.getByName(host)
                val listener = DeviceTlsIdentity.createSessionServerSslContext().serverSocketFactory
                    .createServerSocket(0, 2, loopback) as SSLServerSocket
                val outside = Files.createTempFile("outside-direct-invite", ".txt")
                val lostAnswer = CompletableDeferred<DirectInviteAnswer>()
                val server = launch(Dispatchers.IO) {
                    if (dropFirstAnswer) {
                        (listener.accept() as SSLSocket).use { socket ->
                            socket.advertiseDeviceSessionAlpn()
                            socket.soTimeout = 12_000
                            socket.startHandshake()
                            check(socket.hasDeviceSessionAlpn())
                            val channel = JvmDeviceSessionByteChannel(socket)
                            val plan = DeviceSessionWindowPlan.fromMemory()
                            val peer = DeviceSessionPeer(
                                DeviceSessionTransport(channel, plan, plan, isClient = false),
                                plan,
                                DeviceSessionEndpointRole.Server,
                            )
                            val control = inviter.controlHandler(currentDirectInviteIssuer())
                            peer.setRequestHandler { request ->
                                val response = control.handle(request)
                                if (request.method == DIRECT_INVITE_OFFER_RPC && response.isSuccess) {
                                    lostAnswer.complete(ProtoBufCodec.decode(response.payload))
                                    channel.close()
                                }
                                response
                            }
                            peer.start(this).join()
                            peer.close()
                        }
                    }
                    val accepted = try {
                        listener.accept() as SSLSocket
                    } catch (error: SocketException) {
                        if (listener.isClosed) return@launch
                        throw error
                    }
                    accepted.use { socket ->
                        socket.advertiseDeviceSessionAlpn()
                        socket.soTimeout = 12_000
                        socket.startHandshake()
                        check(socket.hasDeviceSessionAlpn())
                        DeviceSessionServerRuntimeLauncher.run(
                            fixture.dispatcher,
                            JvmDeviceSessionByteChannel(socket),
                            loopback.hostAddress,
                            this,
                            DeviceSessionServerApprovalContext.Standard,
                            directInviteControl = { inviter.controlHandler(currentDirectInviteIssuer()) },
                        )
                    }
                }
                val roomController = MultiPeerWebRtcController(this)
                try {
                    val invite = inviter.issue(loopback.hostAddress, listener.localPort, 2, "")
                    receiver.connect(WebRtcShareCode.encodeDirectInvite(invite))
                    val attemptId = receiver.states.value.entries.single { it.value == WebRtcConnectionStatus.Connected }.key
                    if (dropFirstAnswer) {
                        assertEquals(attemptId, withTimeout(1_000) { lostAnswer.await() }.authorization.connectionAttemptId)
                    }
                    assertEquals(0, registry.pendingCount())
                    val client = assertNotNull(receiver.clients(attemptId))
                    withTimeout(10_000) {
                        roomController.connect(WebRtcConfig(wssUrl = "ws://127.0.0.1:1", roomId = generateRoomId(), iceServers = emptyList(), unreliableMode = false))
                        roomController.connect(WebRtcConfig(wssUrl = "ws://127.0.0.1:1", roomId = generateRoomId(), iceServers = emptyList(), unreliableMode = false))
                        roomController.disconnect()
                        val device = Device(
                            id = "direct:$attemptId", name = "Direct test peer", pathSeparator = "/",
                            host = mutableMapOf(), type = DeviceType.JVM, token = "", pathClient = client, fileClient = client,
                            transportType = DeviceTransportType.WebRtc,
                        )
                        val entry = device.paths.getList(fixture.input.parent.toString()).getOrThrow()
                            .single { it.name == fixture.input.fileName.toString() }
                        assertEquals(FileProtocol.Device, entry.protocol)
                        assertEquals(device.id, entry.protocolId)
                        assertEquals(device.id, device.files.get(entry.path).getOrThrow().protocolId)
                        assertEquals(device.id, device.files.getInfo(entry.path).getOrThrow().protocolId)
                        val received = device.files.readBytes(entry.path, 0L, fixture.bytes.size.toLong()).getOrThrow()
                        val digest = MessageDigest.getInstance("SHA-256")
                        assertTrue(digest.digest(fixture.bytes).contentEquals(digest.digest(received)))
                        assertTrue(client.readBytes(outside.toString(), 0L, 1L).isFailure)
                    }
                    if (revoke) fixture.certificates.removeDeviceToken("direct-invite-test-receiver")
                    else receiver.disconnect(attemptId)
                    withTimeout(8_000) { inviter.states.first { it.isEmpty() } }
                    withTimeout(8_000) { receiver.states.first { it.isEmpty() } }
                } finally {
                    receiver.stop()
                    inviter.stop()
                    roomController.stop()
                    listener.close()
                    server.cancelAndJoin()
                    Files.deleteIfExists(outside)
                }
            }
        }
    }
}

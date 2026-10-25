package com.folderspan.service.webrtc

import com.folderspan.getSocketDevice
import com.folderspan.service.data.DeviceConnectAuthorizationMode
import com.folderspan.service.data.DeviceConnectRequest
import com.folderspan.service.message.deviceMessageSha256
import com.folderspan.service.session.*
import com.folderspan.service.webrtc.controller.MultiPeerWebRtcController
import com.folderspan.service.webrtc.models.*
import com.folderspan.service.webrtc.signaling.SignalingDevice
import com.folderspan.service.webrtc.signaling.SignalingMessage
import com.folderspan.utils.ProtoBufCodec
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/** Room approval/transport are fixtures; recovery uses the production controller and Session. */
class WebRtcControllerNetworkJvmTest {
    @Test
    fun recoverAuthenticatedSessionAfterAddressChange() = runBlocking {
        withTimeout(90_000L) {
            ShareSaveFixture().use { fixture ->
                fixture.start()
                fixture.allowOrdinaryRead()
                val role = checkNotNull(System.getenv("FOLDERSPAN_WEBRTC_RECOVERY_ROLE"))
                val server = role == "server"
                val other = if (server) "client" else "server"
                val url = checkNotNull(System.getenv("FOLDERSPAN_WEBRTC_INTEROP_URL")) + "/$role"
                val config = Json.decodeFromString<WebRtcInteropConfig>(checkNotNull(System.getenv("FOLDERSPAN_WEBRTC_INTEROP_CONFIG")))
                val json = Json { encodeDefaults = true }
                val http = HttpClient()
                suspend fun post(route: String, value: String) {
                    assertEquals(HttpStatusCode.OK, http.post("$url/$route") { setBody(value) }.status)
                }
                suspend fun read(route: String): String {
                    val response = http.get("$url/$route")
                    assertTrue(response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NoContent)
                    return response.bodyAsText()
                }
                suspend fun awaitValue(route: String): String = withTimeout(15_000L) {
                    var value = read(route)
                    while (value.isEmpty()) { delay(25); value = read(route) }
                    value
                }
                val job = SupervisorJob(coroutineContext[Job])
                val controller = MultiPeerWebRtcController(CoroutineScope(coroutineContext + job))
                fun field(name: String) = controller.javaClass.getDeclaredField(name).apply { isAccessible = true }
                fun setController(name: String, value: Any?) = field(name).set(controller, value)
                var offerCount = 0
                var authentications = 0
                try {
                    val local = getSocketDevice()
                    post("$role-state", buildJsonObject { put("id", local.id) }.toString())
                    val remoteId = Json.parseToJsonElement(awaitValue("$other-state")).jsonObject.getValue("id").jsonPrimitive.content
                    assertNotEquals(local.id, remoteId)
                    val attempt = "network-recovery-attempt"
                    val bootstrap = if (server) {
                        WebRtcInteropBootstrap(
                            DeviceConnectRequest(
                                device = local.withCopy(id = remoteId), authorizationMode = DeviceConnectAuthorizationMode.STANDARD,
                                bootstrapAuthorization = fixture.devices.deviceSessionBootstrapAuthorizationRegistry.issue(
                                    initiatorDeviceId = remoteId, targetDeviceId = local.id, connectionAttemptId = attempt, roleId = 2L,
                                ),
                            ),
                            WebRtcIceSnapshot(local.id, remoteId, attempt, 0, WebRtcIcePath()),
                            fixture.input.toString(), fixture.bytes.size, fixture.bytes.deviceMessageSha256(),
                        ).also { post("bootstrap", json.encodeToString(it)) }
                    } else Json.decodeFromString<WebRtcInteropBootstrap>(awaitValue("bootstrap"))
                    val rtcConfig = WebRtcConfig("ws://fixture.invalid", "fixture", iceServers = config.iceServers(), unreliableMode = false)
                    setController("currentConfig", rtcConfig)
                    setController("currentLocalDeviceId", local.id)
                    @Suppress("UNCHECKED_CAST")
                    (field("_connectionStatus").get(controller) as MutableStateFlow<WebRtcConnectionStatus>).value = WebRtcConnectionStatus.Connected
                    setController("signalingClient", object : WebRtcRoomConnectionProbeClient {
                        override val messages = emptyFlow<SignalingMessage>()
                        override val openedEvents = emptyFlow<Unit>()
                        override val closedEvents = emptyFlow<Unit>()
                        override val failureEvents = emptyFlow<Throwable>()
                        override fun connect(scope: CoroutineScope) = Unit
                        override suspend fun close() = Unit
                        override suspend fun send(message: SignalingMessage) {
                            if (message.type == "offer") offerCount++
                            post("incoming", json.encodeToString(message))
                        }
                    })
                    if (server) {
                        val launcher = field("serverLauncher").get(controller) as WebRtcDeviceSessionServerLauncher
                        setController("serverLauncher", WebRtcDeviceSessionServerLauncher { channel, id, connectionAttempt, scope, snapshot, authenticated ->
                            launcher.run(channel, id, connectionAttempt, scope, snapshot) { probe ->
                                authentications++
                                authenticated(probe)
                            }
                        })
                    }
                    @Suppress("UNCHECKED_CAST")
                    (field("approvedPeerIds").get(controller) as MutableSet<String>).add(remoteId)
                    val session = controller.javaClass.getDeclaredMethod("ensureSession", String::class.java, SignalingDevice::class.java)
                        .apply { isAccessible = true }.invoke(controller, remoteId, SignalingDevice(remoteId))
                    fun get(name: String): Any? = session.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(session)
                    fun set(name: String, value: Any?) = session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
                    fun invoke(name: String) = controller.javaClass.getDeclaredMethod(name, session.javaClass).apply { isAccessible = true }.invoke(controller, session)
                    set("connectionAttemptId", attempt)
                    set("isOfferer", server)
                    set("isDeviceSessionClient", !server)
                    if (!server) set("bootstrapAuthorization", bootstrap.request.bootstrapAuthorization)
                    invoke("setupPeerConnection")
                    val signaling = launch {
                        val handle = controller.javaClass.getDeclaredMethod("handleSignal", SignalingMessage::class.java).apply { isAccessible = true }
                        while (isActive) {
                            val signal = read("outgoing")
                            if (signal.isEmpty()) delay(25) else handle.invoke(controller, Json.decodeFromString<SignalingMessage>(signal))
                        }
                    }
                    if (server) invoke("startOfferIfNeeded")
                    suspend fun awaitPath(negotiation: Int, kind: WebRtcIcePathKind): WebRtcIceSnapshot = withTimeout(40_000L) {
                        while (true) {
                            val snapshot = get("localIce") as? WebRtcIceSnapshot
                            if (get("status") == WebRtcConnectionStatus.Connected && snapshot?.negotiation == negotiation && snapshot.path.kind == kind) return@withTimeout snapshot
                            check(get("peerConnection") != null) { "Controller closed: ${controller.lastError.value}" }
                            delay(25)
                        }
                        error("unreachable")
                    }
                    val before = awaitPath(0, WebRtcIcePathKind.Direct)
                    config.assertPath(before.path)
                    val connection = get("peerConnection")
                    val channel = get("sessionChannel") as WebRtcDataChannel
                    val carrier = get("byteChannel")
                    val generation = get("generation")
                    val ufrags = get("localUfrags")
                    val runtime = get("clientRuntime") as? DeviceSessionClientRuntime
                    suspend fun verifyBusiness(negotiation: Int, kind: WebRtcIcePathKind, activeRuntime: DeviceSessionClientRuntime? = runtime, activeAttempt: String = attempt) {
                        val active = assertNotNull(activeRuntime)
                        val query = DeviceSessionIceQuery(activeAttempt, negotiation)
                        withTimeout(5_000L) {
                            while (true) {
                                val response = active.peer.rpc(DEVICE_SESSION_RPC_WEBRTC_ICE, ProtoBufCodec.encode(query))
                                if (response.status == DEVICE_SESSION_RPC_OK) {
                                    val remote = ProtoBufCodec.decode<WebRtcIceSnapshot>(response.payload)
                                    if (remote.path.kind == kind) {
                                        assertEquals(negotiation, remote.negotiation)
                                        assertEquals(remoteId, remote.observerDeviceId)
                                        assertEquals(local.id, remote.remoteDeviceId)
                                        assertEquals(activeAttempt, remote.connectionAttemptId)
                                        assertEquals(WebRtcIceObservationSource.Local, remote.source)
                                        break
                                    }
                                }
                                delay(25)
                            }
                        }
                        val bytes = active.clients.readBytes(bootstrap.filePath, 0L, bootstrap.fileSize.toLong()).getOrThrow()
                        assertEquals(bootstrap.sha256, bytes.deviceMessageSha256())
                    }
                    if (!server) verifyBusiness(0, WebRtcIcePathKind.Direct)
                    post("$role-state", buildJsonObject { put("id", local.id); put("stage", "ready") }.toString())
                    println("WEBRTC_RECOVERY_READY role=$role")
                    while (get("negotiation") == 0) {
                        check(get("peerConnection") != null) { "Controller closed before recovery: ${controller.lastError.value}" }
                        delay(25)
                    }
                    assertEquals(true, get("recovering"))
                    assertEquals(WebRtcConnectionStatus.Connecting, get("status"))
                    assertNull(get("localIce"))
                    assertNull(get("remoteIce"))
                    assertNull(controller.deviceSessionClients(remoteId))
                    post("$role-state", buildJsonObject { put("id", local.id); put("stage", "recovering") }.toString())
                    val recoveringAt = System.nanoTime()
                    while (read("control") != "changed") delay(25)
                    val after = awaitPath(1, WebRtcIcePathKind.Relay)
                    config.copy(expectedPath = "Relay").assertPath(after.path)
                    assertSame(connection, get("peerConnection"))
                    assertSame(channel, get("sessionChannel"))
                    assertSame(carrier, get("byteChannel"))
                    assertSame(runtime, get("clientRuntime"))
                    assertEquals(generation, get("generation"))
                    assertEquals(attempt, get("connectionAttemptId"))
                    assertEquals(WebRtcDataChannelState.Open, channel.state)
                    assertNotEquals(ufrags, get("localUfrags"))
                    assertEquals(false, get("recovering"))
                    assertEquals(if (server) 2 else 0, offerCount)
                    if (server) assertEquals(1, authentications) else {
                        assertNull(get("bootstrapAuthorization"))
                        verifyBusiness(1, WebRtcIcePathKind.Relay)
                        assertEquals(DEVICE_SESSION_RPC_NOT_FOUND, runtime!!.peer.rpc(
                            DEVICE_SESSION_RPC_WEBRTC_ICE, ProtoBufCodec.encode(DeviceSessionIceQuery(attempt, 0)),
                        ).status)
                    }
                    val evidence = buildJsonObject {
                        put("id", local.id); put("stage", "recovered"); put("phase", 3); put("role", role)
                        put("before", json.encodeToJsonElement(before)); put("after", json.encodeToJsonElement(after))
                        put("sameAuthorization", true); put("samePeerConnection", true); put("sameChannel", true)
                        put("sameSession", true); put("restartRounds", 1); put("offerCount", offerCount)
                        put("observedRecovering", true); put("statisticsCleared", true)
                        put("recoveryMilliseconds", (System.nanoTime() - recoveringAt) / 1_000_000)
                        if (server) put("authentications", authentications) else {
                            put("rpcVerified", true); put("sha256", bootstrap.sha256)
                        }
                    }
                    post("$role-state", evidence.toString())
                    suspend fun awaitOtherPhase(phase: Int) {
                        while ((Json.parseToJsonElement(read("$other-state")).jsonObject["phase"]?.jsonPrimitive?.intOrNull ?: 0) < phase) delay(25)
                    }
                    suspend fun phase(value: Int) = post("$role-state", buildJsonObject { put("id", local.id); put("phase", value) }.toString())
                    awaitOtherPhase(3)
                    if (server) channel.close()
                    withTimeout(5_000L) { while (get("peerConnection") != null) delay(25) }
                    assertEquals("", get("connectionAttemptId"))
                    assertNull(get("clientRuntime"))
                    assertNull(get("localIce"))
                    assertNull(get("recoveryTimeout"))
                    val nextAttempt = "network-recovery-new-attempt"
                    val renewed = if (server) {
                        bootstrap.copy(
                            request = DeviceConnectRequest(
                                device = bootstrap.request.device, authorizationMode = DeviceConnectAuthorizationMode.STANDARD,
                                bootstrapAuthorization = fixture.devices.deviceSessionBootstrapAuthorizationRegistry.issue(
                                    initiatorDeviceId = remoteId, targetDeviceId = local.id, connectionAttemptId = nextAttempt, roleId = 2L,
                                ),
                            ),
                            snapshot = bootstrap.snapshot.copy(connectionAttemptId = nextAttempt),
                        ).also {
                            assertNotEquals(bootstrap.request.bootstrapAuthorization, it.request.bootstrapAuthorization)
                            post("bootstrap", json.encodeToString(it))
                        }
                    } else withTimeout(5_000L) {
                        var value = Json.decodeFromString<WebRtcInteropBootstrap>(read("bootstrap"))
                        while (value.snapshot.connectionAttemptId != nextAttempt) {
                            delay(25)
                            value = Json.decodeFromString(read("bootstrap"))
                        }
                        value
                    }
                    @Suppress("UNCHECKED_CAST")
                    (field("approvedPeerIds").get(controller) as MutableSet<String>).add(remoteId)
                    set("connectionAttemptId", nextAttempt)
                    set("isOfferer", server)
                    set("isDeviceSessionClient", !server)
                    if (!server) set("bootstrapAuthorization", renewed.request.bootstrapAuthorization)
                    invoke("setupPeerConnection")
                    phase(4)
                    awaitOtherPhase(4)
                    if (server) invoke("startOfferIfNeeded")
                    val reconnected = awaitPath(0, WebRtcIcePathKind.Relay)
                    assertEquals(nextAttempt, reconnected.connectionAttemptId)
                    assertNotSame(connection, get("peerConnection"))
                    assertNotSame(channel, get("sessionChannel"))
                    assertNotSame(carrier, get("byteChannel"))
                    if (server) {
                        assertEquals(2, authentications)
                        assertEquals(3, offerCount)
                    } else {
                        val renewedRuntime = get("clientRuntime") as? DeviceSessionClientRuntime
                        assertNotSame(runtime, renewedRuntime)
                        verifyBusiness(0, WebRtcIcePathKind.Relay, renewedRuntime, nextAttempt)
                    }
                    phase(5)
                    awaitOtherPhase(5)
                    controller.disconnectPeer(remoteId)
                    withTimeout(5_000L) { while (get("peerConnection") != null) delay(25) }
                    assertNull(get("iceSampling"))
                    assertNull(get("recoveryTimeout"))
                    val finished = JsonObject(evidence + buildJsonObject {
                        put("stage", "finished"); put("phase", 6); put("permanentCloseObserved", true)
                        put("newConnectionAuthorized", true); put("newAttempt", nextAttempt); put("cancelledNewConnection", true)
                        if (server) put("totalAuthenticationsAfterNewConnection", authentications)
                    })
                    post("$role-state", finished.toString())
                    println("WEBRTC_RECOVERY_EVIDENCE $finished")
                    awaitOtherPhase(6)
                    signaling.cancelAndJoin()
                } finally {
                    controller.disconnect()
                    job.cancelAndJoin()
                    http.close()
                }
            }
        }
    }
}

package com.folderspan.service.webrtc

import com.folderspan.service.session.*
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.service.data.DeviceConnectResponse
import com.folderspan.service.data.DeviceConnectAuthorizationMode
import com.folderspan.service.http.archive.FolderSpanArchiveStreamCapabilities
import com.folderspan.utils.ProtoBufCodec
import com.folderspan.service.webrtc.controller.MultiPeerWebRtcController
import com.folderspan.service.webrtc.controller.core.WebRtcRemoteNegotiation
import com.folderspan.service.webrtc.models.*
import com.folderspan.service.webrtc.signaling.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WebRtcControllerLifecycleJvmTest {
    @Test
    fun cancellingOfferOrCandidateSendDoesNotDisconnectRoomOrOtherPeers() = runBlocking {
        for (blockedType in listOf("offer", "ice")) {
            LifecycleFixture().use { f ->
                val healthy = f.session("healthy")
                f.setup(healthy)
                f.attach(healthy)
                val healthyConnection = f.get(healthy, "peerConnection")
                val pending = f.session("pending")
                f.setup(pending)
                f.set(pending, "isOfferer", true)
                f.blockedSignalType = blockedType
                f.startOffer(pending)
                withTimeout(2_000L) { while (!f.sendStarted.isCompleted) { f.runCurrent(); delay(10) } }
                val offer = f.get(pending, "negotiationJob") as Job
                f.controller.disconnectPeer("pending")
                f.runCurrent()
                if (blockedType == "offer") assertTrue(offer.isCancelled)
                assertEquals(WebRtcConnectionStatus.Connected, f.controller.connectionStatus.value)
                assertSame(healthyConnection, f.get(healthy, "peerConnection"))
                assertEquals(WebRtcConnectionStatus.Connected, f.get(healthy, "status"))
                assertNull(f.controller.lastError.value)
            }
        }
    }

    @Test
    fun unchangedNativeConnectedStateCanRecoverWithExistingSessionPong() = runBlocking {
        LifecycleFixture().use { f ->
            val session = f.session("peer")
            f.setup(session)
            f.attach(session)
            f.set(session, "isOfferer", true)
            f.set(session, "remoteUfrags", setOf("current"))
            f.state(session, WebRtcPeerConnectionState.Connected)
            f.ice(SignalingMessage(type = "ice", code = "ice-restart", sdp = "current", from = SignalingDevice("peer"), connectionAttemptId = "attempt-peer"))
            withTimeout(2_000L) { while (f.signals.none { it.type == "offer" }) { f.runCurrent(); delay(10) } }
            f.set(session, "recoveryDescriptionApplied", true)
            f.verifyRecovery(session)
            f.runCurrent()
            assertEquals(false, f.get(session, "recovering"))
            assertEquals(WebRtcConnectionStatus.Connected, f.get(session, "status"))
            assertEquals(1, f.authentications)
        }
    }

    @Test
    fun timedOutAndOldNegotiationStatisticsCannotOverwriteCurrentResults() = runBlocking {
        LifecycleFixture().use { f ->
            val session = f.session("peer")
            f.setup(session)
            f.attach(session)
            withRealWebRtcSessionPair { client, server, _ ->
                var requests = 0
                val first = CompletableDeferred<Unit>()
                val third = CompletableDeferred<Unit>()
                server.setRequestHandler { request ->
                    if (request.method != DEVICE_SESSION_RPC_WEBRTC_ICE) DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, request.payload)
                    else {
                        val index = ++requests
                        val query = ProtoBufCodec.decode<DeviceSessionIceQuery>(request.payload)
                        if (index == 1) first.await()
                        if (index == 3) third.await()
                        val snapshot = WebRtcIceSnapshot("peer", "local", query.connectionAttemptId, query.negotiation,
                            WebRtcIcePath(kind = if (index == 2) WebRtcIcePathKind.Direct else WebRtcIcePathKind.Relay))
                        DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, ProtoBufCodec.encode(snapshot))
                    }
                }
                f.bindSamplingRuntime(session, client)
                f.sample(session)
                suspend fun awaitRequests(count: Int) = withTimeout(2_000L) {
                    while (requests < count) { f.runCurrent(); delay(10) }
                }
                suspend fun echo() { assertEquals(DEVICE_SESSION_RPC_OK, client.rpc("Echo", byteArrayOf(1)).status); f.runCurrent() }
                try {
                    awaitRequests(1)
                    f.advance(2_000)
                    assertNull(f.get(session, "remoteIce"))
                    assertEquals(0, f.pendingRpcCount(client))
                    echo()
                    f.advance(2_000)
                    awaitRequests(2)
                    echo()
                    assertEquals(WebRtcIcePathKind.Direct, (f.get(session, "remoteIce") as WebRtcIceSnapshot).path.kind)
                    first.complete(Unit)
                    echo()
                    assertEquals(WebRtcIcePathKind.Direct, (f.get(session, "remoteIce") as WebRtcIceSnapshot).path.kind)
                    f.advance(1_999)
                    assertEquals(2, requests)
                    f.advance(1)
                    awaitRequests(3)
                    f.state(session, WebRtcPeerConnectionState.Disconnected)
                    f.runCurrent()
                    assertEquals(0, f.pendingRpcCount(client))
                    third.complete(Unit)
                    echo()
                    assertNull(f.get(session, "remoteIce"))
                    assertNull(f.get(session, "localIce"))
                    f.controller.disconnectPeer("peer")
                    val replacement = f.session("peer")
                    f.setup(replacement)
                    assertNull(f.get(replacement, "remoteIce"))
                    assertEquals(false, f.get(replacement, "authenticated"))
                } finally {
                    first.complete(Unit)
                    third.complete(Unit)
                    f.set(session, "clientRuntime", null)
                }
            }
        }
    }

    @Test
    fun deniedDiagnosticsRemainUnknownWithoutClosingBusinessSession() = runBlocking {
        LifecycleFixture().use { f ->
            val session = f.session("peer")
            f.setup(session)
            f.attach(session)
            withRealWebRtcSessionPair { client, server, _ ->
                val denied = CompletableDeferred<Unit>()
                server.setRequestHandler { request ->
                    if (request.method == DEVICE_SESSION_RPC_WEBRTC_ICE) {
                        denied.complete(Unit)
                        DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_FORBIDDEN)
                    } else DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, request.payload)
                }
                f.bindSamplingRuntime(session, client)
                f.sample(session)
                try {
                    withTimeout(2_000L) { while (!denied.isCompleted) { f.runCurrent(); delay(10) } }
                    assertEquals(DEVICE_SESSION_RPC_OK, client.rpc("Echo", byteArrayOf(2)).status)
                    f.runCurrent()
                    assertNull(f.get(session, "remoteIce"))
                    assertEquals(WebRtcConnectionStatus.Connected, f.get(session, "status"))
                } finally { (f.get(session, "iceSampling") as? Job)?.cancel(); f.set(session, "clientRuntime", null) }
            }
        }
    }

    @Test
    fun approvalWaitDoesNotStartIceDeadlineAndObsoleteDeadlineCannotCloseReplacement() = runBlocking {
        LifecycleFixture().use { f ->
            val session = f.session("peer", approved = false)
            f.controller.connectPeer("peer")
            f.advance(60_000)
            assertNull(f.get(session, "peerConnection"))
            f.approved += "peer"
            f.setup(session)
            f.advance(1_000)
            f.setup(session)
            val replacement = f.get(session, "peerConnection")
            f.advance(29_000)
            assertSame(replacement, f.get(session, "peerConnection"))
            assertEquals(WebRtcConnectionStatus.Connecting, f.get(session, "status"))
            f.advance(1_000)
            assertNull(f.get(session, "peerConnection"))
            assertEquals(WebRtcConnectionStatus.Error, f.get(session, "status"))
            assertTrue(f.controller.lastError.value.orEmpty().contains("TURN"))
            assertEquals("", f.get(session, "connectionAttemptId"))
        }
    }

    @Test
    fun candidatesRequireApprovalAndAttemptAndAreDiscardedAfterCancellation() = runBlocking {
        LifecycleFixture().use { f ->
            val session = f.session("peer")
            f.setup(session)
            (f.get(session, "remoteNegotiation") as WebRtcRemoteNegotiation).close()
            val submitted = mutableListOf<String>()
            val queue = WebRtcRemoteNegotiation(f.scope, { true }, { submitted += it.candidate }, { throw it })
            f.set(session, "remoteNegotiation", queue)
            queue.description {}
            fun signal(attempt: String) = SignalingMessage(type = "ice", from = SignalingDevice("peer"), connectionAttemptId = attempt,
                candidate = SignalingIceCandidate("0", 0, "candidate"))
            f.approved.clear()
            f.ice(signal("attempt-peer"))
            f.approved += "peer"
            f.ice(signal("old-attempt"))
            f.runCurrent()
            assertTrue(submitted.isEmpty())
            f.ice(signal("attempt-peer"))
            f.runCurrent()
            assertEquals(listOf("candidate"), submitted)
            f.controller.disconnectPeer("peer")
            f.ice(signal("attempt-peer"))
            f.advance(60_000)
            assertFalse(queue.candidate(WebRtcIceCandidate("0", 0, "late")))
            assertEquals(listOf("candidate"), submitted)
            assertNull(f.controller.lastError.value)
        }
    }

    @Test
    fun authenticationHasSeparateFifteenSecondDeadline() = runBlocking {
        LifecycleFixture().use { f ->
            val session = f.session("peer")
            f.setup(session)
            f.attach(session, authenticate = false)
            f.advance(14_999)
            assertNotNull(f.get(session, "peerConnection"))
            f.advance(1)
            assertEquals(WebRtcConnectionStatus.Error, f.get(session, "status"))
            assertNull(f.get(session, "peerConnection"))
        }
    }

    @Test
    fun recoveryMergesRepeatedNotificationsAndKeepsAuthenticatedCarrier() = runBlocking {
        LifecycleFixture().use { f ->
            val session = f.session("peer")
            f.setup(session)
            f.attach(session)
            f.set(session, "isOfferer", true)
            f.set(session, "remoteUfrags", setOf("old-fragment"))
            val connection = f.get(session, "peerConnection")
            val channel = f.get(session, "sessionChannel")
            val carrier = f.get(session, "byteChannel")
            f.set(session, "localIce", WebRtcIceSnapshot("local", "peer", "attempt-peer", 0, WebRtcIcePath()))
            f.state(session, WebRtcPeerConnectionState.Disconnected)
            repeat(3) { f.state(session, WebRtcPeerConnectionState.Disconnected) }
            withTimeout(2_000) { while (f.signals.none { it.type == "offer" }) { f.runCurrent(); delay(10) } }
            assertEquals(1, f.signals.count { it.type == "offer" })
            assertSame(connection, f.get(session, "peerConnection"))
            assertSame(channel, f.get(session, "sessionChannel"))
            assertSame(carrier, f.get(session, "byteChannel"))
            assertNull(f.get(session, "localIce"))
            assertEquals(1, f.authentications)
            assertEquals(WebRtcConnectionStatus.Connecting, f.get(session, "status"))
            assertNull(f.controller.deviceSessionClients("peer"))
            f.answer(SignalingMessage(type = "answer", from = SignalingDevice("peer"), connectionAttemptId = "attempt-peer", sdp = "a=ice-ufrag:old-fragment"))
            f.runCurrent()
            assertEquals(WebRtcConnectionStatus.Connecting, f.get(session, "status"))
            // Native SDP application and CONNECTED are independent boundaries; both are required.
            f.state(session, WebRtcPeerConnectionState.Connected)
            assertEquals(WebRtcConnectionStatus.Connecting, f.get(session, "status"))
            f.set(session, "recoveryDescriptionApplied", true)
            f.state(session, WebRtcPeerConnectionState.Connected)
            f.runCurrent()
            assertEquals(WebRtcConnectionStatus.Connected, f.get(session, "status"))
            assertEquals(false, f.get(session, "recovering"))
            assertEquals(1, f.authentications)
            assertSame(carrier, f.get(session, "byteChannel"))
            assertNull(f.get(session, "bootstrapAuthorization"))
            f.advance(30_000)
            assertSame(connection, f.get(session, "peerConnection"))
        }
    }

    @Test
    fun responderRequestsSingleRecoveryAndOldRequestCannotRestartNewNegotiation() = runBlocking {
        LifecycleFixture().use { f ->
            val session = f.session("peer")
            f.setup(session)
            f.attach(session)
            f.set(session, "isOfferer", false)
            f.set(session, "localUfrags", setOf("responder-fragment"))
            repeat(3) { f.state(session, WebRtcPeerConnectionState.Disconnected) }
            f.runCurrent()
            assertEquals(1, f.signals.count { it.code == "ice-restart" })
            assertTrue(f.signals.none { it.type == "offer" })
            f.set(session, "isOfferer", true)
            f.set(session, "remoteUfrags", setOf("new-fragment"))
            f.set(session, "recovering", false)
            f.ice(SignalingMessage(type = "ice", code = "ice-restart", sdp = "old-fragment", from = SignalingDevice("peer"), connectionAttemptId = "attempt-peer"))
            f.runCurrent()
            assertTrue(f.signals.none { it.type == "offer" })
        }
    }

    @Test
    fun recoveryTimeoutAndPermanentFailureOnlyCloseAffectedPeer() = runBlocking {
        for (terminal in listOf("timeout", "failed", "closed", "channel", "cancel", "revoked", "signaling")) {
            LifecycleFixture().use { f ->
                val session = f.session("peer")
                f.setup(session)
                f.attach(session)
                f.set(session, "isOfferer", false)
                val other = f.session("other")
                f.setup(other)
                // A second already authenticated carrier is an independent fixture boundary.
                f.set(other, "authenticated", true)
                f.set(other, "status", WebRtcConnectionStatus.Connected)
                f.set(other, "sessionChannel", LifecycleChannel())
                val healthyConnection = f.get(other, "peerConnection")
                f.state(session, WebRtcPeerConnectionState.Disconnected)
                when (terminal) {
                    "failed" -> f.state(session, WebRtcPeerConnectionState.Failed)
                    "closed" -> f.state(session, WebRtcPeerConnectionState.Closed)
                    "channel" -> { (f.get(session, "sessionChannel") as LifecycleChannel).close(); f.runCurrent() }
                    "cancel", "revoked" -> f.controller.disconnectPeer("peer")
                    "signaling" -> {
                        f.controllerField("signalingClient", null)
                        f.state(session, WebRtcPeerConnectionState.Disconnected)
                    }
                }
                f.advance(30_000)
                assertNull(f.get(session, "peerConnection"), terminal)
                assertEquals("", f.get(session, "connectionAttemptId"), terminal)
                assertNull(f.get(session, "iceSampling"), terminal)
                assertNull(f.get(session, "recoveryTimeout"), terminal)
                assertSame(healthyConnection, f.get(other, "peerConnection"), terminal)
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class LifecycleFixture : AutoCloseable {
    val dispatcher = StandardTestDispatcher()
    private val job = SupervisorJob()
    val scope = CoroutineScope(job + dispatcher)
    val controller = MultiPeerWebRtcController(scope)
    val signals = mutableListOf<SignalingMessage>()
    var blockedSignalType: String? = null
    val sendStarted = CompletableDeferred<Unit>()
    var authentications = 0
    @Suppress("UNCHECKED_CAST")
    val approved = controller.javaClass.getDeclaredField("approvedPeerIds").apply { isAccessible = true }.get(controller) as MutableSet<String>
    init {
        controllerField("currentConfig", WebRtcConfig("ws://test.invalid", "room", iceServers = emptyList(), unreliableMode = false))
        controllerField("currentLocalDeviceId", "local")
        @Suppress("UNCHECKED_CAST")
        (controller.javaClass.getDeclaredField("_connectionStatus").apply { isAccessible = true }.get(controller) as MutableStateFlow<WebRtcConnectionStatus>).value = WebRtcConnectionStatus.Connected
        controllerField("signalingClient", object : WebRtcRoomConnectionProbeClient {
            override val messages = emptyFlow<SignalingMessage>()
            override val openedEvents = emptyFlow<Unit>()
            override val closedEvents = emptyFlow<Unit>()
            override val failureEvents = emptyFlow<Throwable>()
            override fun connect(scope: CoroutineScope) = Unit
            override suspend fun send(message: SignalingMessage) {
                signals += message
                if (message.type == blockedSignalType) { sendStarted.complete(Unit); awaitCancellation() }
            }
            override suspend fun close() = Unit
        })
    }
    fun controllerField(name: String, value: Any?) { controller.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(controller, value) }
    fun get(session: Any, name: String): Any? = session.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(session)
    fun set(session: Any, name: String, value: Any?) { session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value) }
    fun session(id: String, approved: Boolean = true): Any = controller.javaClass.getDeclaredMethod("ensureSession", String::class.java, SignalingDevice::class.java)
        .apply { isAccessible = true }.invoke(controller, id, SignalingDevice(id)).also {
            set(it, "connectionAttemptId", "attempt-$id")
            if (approved) this.approved += id
        }
    fun setup(session: Any) { controller.javaClass.getDeclaredMethod("setupPeerConnection", session.javaClass).apply { isAccessible = true }.invoke(controller, session); runCurrent() }
    fun state(session: Any, state: WebRtcPeerConnectionState) { controller.javaClass.getDeclaredMethod("handlePeerConnectionState", session.javaClass, WebRtcPeerConnectionState::class.java).apply { isAccessible = true }.invoke(controller, session, state) }
    fun ice(message: SignalingMessage) { controller.javaClass.getDeclaredMethod("handleIce", SignalingMessage::class.java).apply { isAccessible = true }.invoke(controller, message) }
    fun answer(message: SignalingMessage) { controller.javaClass.getDeclaredMethod("handleAnswer", SignalingMessage::class.java).apply { isAccessible = true }.invoke(controller, message) }
    fun sample(session: Any) { controller.javaClass.getDeclaredMethod("startIceSampling", session.javaClass).apply { isAccessible = true }.invoke(controller, session) }
    fun startOffer(session: Any) { controller.javaClass.getDeclaredMethod("startOfferIfNeeded", session.javaClass).apply { isAccessible = true }.invoke(controller, session) }
    fun verifyRecovery(session: Any) { controller.javaClass.getDeclaredMethod("verifyIceRecovery", session.javaClass).apply { isAccessible = true }.invoke(controller, session) }
    fun bindSamplingRuntime(session: Any, peer: DeviceSessionPeer) {
        // Authentication is a fixture boundary here; diagnostic RPC and cancellation use a real carrier.
        val plan = DeviceSessionWindowPlan.fromMemory()
        val status = plan.toTransferStatus(plan.maxFileStreams, plan.recommendedChunkBytes(), 0L)
        val capabilities = FolderSpanArchiveStreamCapabilities.local()
        val runtime = DeviceSessionClientRuntime(peer, job,
            DeviceSessionConnectResponse(DeviceConnectResponse(DeviceConnectType.APPROVED, "sampling-token", DeviceConnectAuthorizationMode.STANDARD),
                plan.maxFileStreams, plan.recommendedChunkBytes(), capabilities),
            createDeviceSessionClients(peer, status, capabilities), status, null)
        set(session, "clientRuntime", runtime)
    }
    fun pendingRpcCount(peer: DeviceSessionPeer): Int {
        val endpoint = peer.javaClass.getDeclaredField("controlEndpoint").apply { isAccessible = true }.get(peer)
        return (endpoint.javaClass.getDeclaredField("pending").apply { isAccessible = true }.get(endpoint) as Map<*, *>).size
    }
    suspend fun attach(session: Any, authenticate: Boolean = true) {
        val started = CompletableDeferred<Unit>()
        controllerField("serverLauncher", WebRtcDeviceSessionServerLauncher { _, _, _, _, _, onAuthenticated ->
            if (authenticate) { onAuthenticated { true }; authentications++ }
            started.complete(Unit)
            awaitCancellation()
        })
        controller.javaClass.getDeclaredMethod("attachSessionChannel", session.javaClass, WebRtcDataChannel::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, session, LifecycleChannel(), get(session, "generation"))
        runCurrent()
        withTimeout(2_000) {
            while (!started.isCompleted) { runCurrent(); delay(10) }
            started.await()
        }
        (get(session, "iceSampling") as? Job)?.cancel()
        set(session, "iceSampling", null)
    }
    fun runCurrent() = dispatcher.scheduler.runCurrent()
    fun advance(ms: Long) { dispatcher.scheduler.advanceTimeBy(ms); runCurrent() }
    override fun close() { controller.disconnect(); job.cancel(); runCurrent(); runBlocking { job.join() } }
}

private class LifecycleChannel : WebRtcDataChannel {
    override val label = WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL
    override var state = WebRtcDataChannelState.Open
    override val bufferedAmount = 0L
    override val onOpen = emptyFlow<Unit>()
    override val onClose = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val onMessage = emptyFlow<ByteArray>()
    override fun send(data: ByteArray) = state == WebRtcDataChannelState.Open
    override fun close() { state = WebRtcDataChannelState.Closed; onClose.tryEmit(Unit) }
}

package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.controller.MultiPeerWebRtcController
import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import com.folderspan.service.webrtc.models.WebRtcConfig
import com.folderspan.service.data.DeviceSessionBootstrapAuthorization
import com.folderspan.service.data.DeviceSessionBootstrapAuthorizationType
import com.folderspan.service.webrtc.models.WebRtcDataChannel
import com.folderspan.service.webrtc.models.WebRtcDataChannelState
import com.folderspan.service.webrtc.models.WebRtcPeerConnection
import com.folderspan.service.webrtc.signaling.SignalingDevice
import com.folderspan.service.webrtc.signaling.SignalingMessage
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebRtcControllerFailureJvmTest {
    @Test
    fun delayedApprovalCannotRestartFailedAttempt() = runSuspendTest {
        val controllerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        val controller = MultiPeerWebRtcController(controllerScope)
        try {
            val remote = SignalingDevice("failed-peer")
            controller.javaClass.getDeclaredField("currentConfig").apply { isAccessible = true }.set(
                controller, WebRtcConfig(wssUrl = "ws://127.0.0.1:1", roomId = "test-room", iceServers = emptyList(), unreliableMode = false),
            )
            val session = controller.javaClass.getDeclaredMethod("ensureSession", String::class.java, SignalingDevice::class.java)
                .apply { isAccessible = true }.invoke(controller, remote.id, remote)
            session.javaClass.getDeclaredField("connectionAttemptId").apply { isAccessible = true }.set(session, "failed-attempt")
            @Suppress("UNCHECKED_CAST")
            val pending = controller.javaClass.getDeclaredField("pendingOutboundApprovalIds").apply { isAccessible = true }
                .get(controller) as MutableSet<String>
            pending += remote.id
            controller.javaClass.getDeclaredMethod("failPeerSession", session.javaClass, Throwable::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(controller, session, IllegalStateException("test failure"), "visible failure")
            controller.javaClass.getDeclaredMethod("handleSignal", SignalingMessage::class.java).apply { isAccessible = true }
                .invoke(controller, SignalingMessage(
                    type = "connect-approved", from = remote, connectionAttemptId = "failed-attempt",
                    bootstrapAuthorization = DeviceSessionBootstrapAuthorization(
                        DeviceSessionBootstrapAuthorizationType.WEB_RTC_PREAPPROVED, "test-opaque", "failed-attempt",
                    ),
                ))
            assertEquals(WebRtcConnectionStatus.Error, controller.peerSessionStates.value.single().status)
            assertEquals("visible failure", controller.lastError.value)
            assertNull(session.javaClass.getDeclaredField("peerConnection").apply { isAccessible = true }.get(session))
            assertNull(controller.deviceSessionClients(remote.id))
        } finally {
            controller.disconnect()
            controllerScope.cancel()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun obsoleteAnswerAndChannelCannotMutateReplacementPeer() = runSuspendTest {
        val dispatcher = StandardTestDispatcher()
        val parent = SupervisorJob()
        val controllerScope = CoroutineScope(parent + dispatcher)
        val controller = MultiPeerWebRtcController(controllerScope)
        val oldPeer = WebRtcPeerConnection(emptyList())
        var newPeerOwned = false
        val newPeer = WebRtcPeerConnection(emptyList())
        try {
            val remote = SignalingDevice("reconnected-peer")
            val ensure = controller.javaClass.getDeclaredMethod("ensureSession", String::class.java, SignalingDevice::class.java)
                .apply { isAccessible = true }
            val session = ensure.invoke(controller, remote.id, remote)
            fun field(name: String) = session.javaClass.getDeclaredField(name).apply { isAccessible = true }
            val peer = field("peerConnection")
            peer.set(session, oldPeer)
            field("connectionAttemptId").set(session, "old-attempt")
            @Suppress("UNCHECKED_CAST")
            val approved = controller.javaClass.getDeclaredField("approvedPeerIds").apply { isAccessible = true }
                .get(controller) as MutableSet<String>
            approved += remote.id
            val answer = controller.javaClass.getDeclaredMethod("handleAnswer", SignalingMessage::class.java)
                .apply { isAccessible = true }
            answer.invoke(controller, SignalingMessage(
                type = "answer", from = remote, connectionAttemptId = "old-attempt", sdp = "invalid-old-sdp",
            ))
            // Replace the connection before the queued signaling coroutine runs.
            field("generation").setInt(session, 1)
            field("connectionAttemptId").set(session, "new-attempt")
            field("status").set(session, WebRtcConnectionStatus.Connected)
            peer.set(session, newPeer)
            newPeerOwned = true
            withTimeout(2_000L) {
                do {
                    dispatcher.scheduler.runCurrent()
                    delay(10L)
                } while (parent.children.any { it.isActive })
            }
            assertSame(newPeer, peer.get(session), "An obsolete answer must not close the replacement connection")
            assertEquals(WebRtcConnectionStatus.Connected, field("status").get(session))
            assertNull(controller.lastError.value)
            var discarded = false
            val oldChannel = object : WebRtcDataChannel {
                override val label = "control"
                override val state = WebRtcDataChannelState.Open
                override val bufferedAmount = 0L
                override val onOpen = emptyFlow<Unit>()
                override val onClose = emptyFlow<Unit>()
                override val onMessage = emptyFlow<ByteArray>()
                override fun send(data: ByteArray) = true
                override fun close() { discarded = true }
            }
            controller.javaClass.getDeclaredMethod(
                "attachSessionChannel", session.javaClass, WebRtcDataChannel::class.java, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(controller, session, oldChannel, 0)
            assertTrue(discarded, "A late channel from the old generation must be released")
            assertSame(newPeer, peer.get(session), "A late channel must not close the replacement connection")
            assertEquals(WebRtcConnectionStatus.Connected, field("status").get(session))
            assertNull(controller.lastError.value)
        } finally {
            controller.disconnect()
            controllerScope.cancel()
            try { oldPeer.close() } finally { if (!newPeerOwned) newPeer.close() }
        }
    }

    @Test
    fun incompatibleNativeChannelClosesPeerAndKeepsLocalizedError() = runSuspendTest {
        for (label in listOf("control", "folderspan-device-session-v2")) {
            withRealWebRtcDataChannelPair(label, blockOwnsConnections = true) { _, incoming, initiator, responder ->
                val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val controller = MultiPeerWebRtcController(controllerScope)
                var controllerOwnsResponder = false
                try {
                    // Inject the already negotiated native carrier at the private signaling boundary;
                    // use the production acceptance/failure path, without issuing an approval request.
                    val ensure = controller.javaClass.getDeclaredMethod(
                        "ensureSession", String::class.java, SignalingDevice::class.java,
                    ).apply { isAccessible = true }
                    val session = ensure.invoke(controller, "legacy-peer", SignalingDevice("legacy-peer"))
                    val peerField = session.javaClass.getDeclaredField("peerConnection").apply { isAccessible = true }
                    peerField.set(session, responder)
                    controllerOwnsResponder = true
                    val attach = controller.javaClass.getDeclaredMethod(
                        "attachSessionChannel", session.javaClass, WebRtcDataChannel::class.java, Int::class.javaPrimitiveType,
                    ).apply { isAccessible = true }
                    attach.invoke(controller, session, incoming, 0)
                    assertEquals(WebRtcConnectionStatus.Error, controller.peerSessionStates.value.single().status)
                    assertEquals(AppStrings.ui_webrtc_session_protocol_incompatible, controller.lastError.value)
                    assertNull(peerField.get(session), "Rejected native peer must release its PeerConnection")
                    assertNull(controller.deviceSessionClients("legacy-peer"))
                } finally {
                    controller.disconnect()
                    controllerScope.cancel()
                    try { initiator.close() } finally {
                        if (!controllerOwnsResponder) responder.close()
                    }
                }
            }
        }
    }
}

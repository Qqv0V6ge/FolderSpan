package com.folderspan.service.webrtc

import com.folderspan.service.data.DeviceSessionBootstrapAuthorization
import com.folderspan.service.data.DeviceSessionBootstrapAuthorizationType
import com.folderspan.service.webrtc.signaling.HttpWebRtcSignalingHub
import com.folderspan.service.webrtc.signaling.HttpWebRtcSignalingRole
import com.folderspan.service.webrtc.signaling.SignalingDevice
import com.folderspan.service.webrtc.signaling.SignalingMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpWebRtcSignalingHubTest {
    @Test
    fun preapprovedBootstrapAuthorizationIsRoutedOnlyToItsTarget() {
        val hub = HttpWebRtcSignalingHub()
        val app = SignalingDevice(id = "app")
        val browserA = SignalingDevice(id = "browser-a")
        val browserB = SignalingDevice(id = "browser-b")
        val hostJoin = hub.registerHost(app)
        val browserAJoin = hub.join(HttpWebRtcSignalingRole.Browser, browserA)
        val browserBJoin = hub.join(HttpWebRtcSignalingRole.Browser, browserB)
        val authorization = DeviceSessionBootstrapAuthorization(
            type = DeviceSessionBootstrapAuthorizationType.WEB_RTC_PREAPPROVED,
            opaqueAuthorization = "opaque-authorization",
            connectionAttemptId = "attempt-a",
        )

        val response = hub.send(
            clientId = app.id,
            clientToken = hostJoin.clientToken,
            message = SignalingMessage(
                type = "connect-approved",
                from = app,
                to = browserA,
                connectionAttemptId = "attempt-a",
                bootstrapAuthorization = authorization,
            ),
        )

        assertTrue(response.accepted)
        assertEquals(
            authorization,
            hub.poll(browserA.id, browserAJoin.clientToken, 0)
                .messages.single { it.type == "connect-approved" }
                .bootstrapAuthorization,
        )
        assertTrue(
            hub.poll(browserB.id, browserBJoin.clientToken, 0)
                .messages.none { it.bootstrapAuthorization != null },
        )
    }

    @Test
    fun browserJoinExposesAppHostOnly() {
        val hub = HttpWebRtcSignalingHub()
        val app = SignalingDevice(id = "app", name = "Desktop")
        val browserA = SignalingDevice(id = "browser-a", name = "Browser A")
        val browserB = SignalingDevice(id = "browser-b", name = "Browser B")

        val hostJoin = hub.registerHost(app)
        val browserAJoin = hub.join(HttpWebRtcSignalingRole.Browser, browserA)
        hub.join(HttpWebRtcSignalingRole.Browser, browserB)

        assertEquals(emptyList(), hostJoin.peers)
        assertEquals(listOf(app), browserAJoin.peers)
        assertEquals(
            listOf("browser-a", "browser-b"),
            hub.poll(app.id, hostJoin.clientToken, 0).messages.mapNotNull { it.from?.id }
        )
        assertTrue(hub.poll(browserA.id, browserAJoin.clientToken, 0).messages.none { it.from?.id == browserB.id })
    }

    @Test
    fun browserJoinsAreCappedToAvoidUnboundedPeerGrowth() {
        val hub = HttpWebRtcSignalingHub()
        hub.registerHost(SignalingDevice(id = "app"))

        val accepted = (1..32).map { index ->
            hub.join(HttpWebRtcSignalingRole.Browser, SignalingDevice(id = "browser-$index"))
        }
        val rejected = hub.join(HttpWebRtcSignalingRole.Browser, SignalingDevice(id = "browser-over-limit"))

        assertTrue(accepted.all { response -> response.code == null })
        assertEquals("PEER_LIMIT_EXCEEDED", rejected.code)
    }

    @Test
    fun sendThrottlesABrowserThatFloodsHostSignaling() {
        val hub = HttpWebRtcSignalingHub()
        val app = SignalingDevice(id = "app")
        val browser = SignalingDevice(id = "browser")
        hub.registerHost(app)
        val browserJoin = hub.join(HttpWebRtcSignalingRole.Browser, browser)

        val accepted = (1..32).map {
            hub.send(
                clientId = browser.id,
                clientToken = browserJoin.clientToken,
                message = SignalingMessage(
                    type = "connect-request",
                    from = browser,
                    to = app,
                    connectionAttemptId = "attempt-$it",
                )
            )
        }
        val rejected = hub.send(
            clientId = browser.id,
            clientToken = browserJoin.clientToken,
            message = SignalingMessage(
                type = "connect-request",
                from = browser,
                to = app,
                connectionAttemptId = "attempt-rate-limited",
            )
        )

        assertTrue(accepted.all { response -> response.accepted })
        assertFalse(rejected.accepted)
        assertEquals("RATE_LIMITED", rejected.code)
    }

    @Test
    fun browserCannotSendConnectApprovedSignal() {
        val hub = HttpWebRtcSignalingHub()
        val app = SignalingDevice(id = "app")
        val browser = SignalingDevice(id = "browser")
        hub.registerHost(app)
        val browserJoin = hub.join(HttpWebRtcSignalingRole.Browser, browser)

        val response = hub.send(
            clientId = browser.id,
            clientToken = browserJoin.clientToken,
            message = SignalingMessage(
                type = "connect-approved",
                from = browser,
                to = app,
                connectionAttemptId = "forged-attempt",
                bootstrapAuthorization = DeviceSessionBootstrapAuthorization(
                    type = DeviceSessionBootstrapAuthorizationType.WEB_RTC_PREAPPROVED,
                    opaqueAuthorization = "forged-bootstrap-authorization",
                    connectionAttemptId = "forged-attempt",
                ),
            ),
        )

        assertFalse(response.accepted)
        assertEquals("PEER_NOT_ALLOWED", response.code)
    }

    @Test
    fun oversizedDeviceAndMessageFieldsAreRejected() {
        val hub = HttpWebRtcSignalingHub()
        val app = SignalingDevice(id = "app")
        val browser = SignalingDevice(id = "browser")
        val longName = "x".repeat(257)
        val longSdp = "v".repeat(16 * 1024 + 1)
        val hostJoin = hub.registerHost(app)

        val invalidJoin = hub.join(
            HttpWebRtcSignalingRole.Browser,
            SignalingDevice(id = "oversized-browser", name = longName)
        )
        val browserJoin = hub.join(HttpWebRtcSignalingRole.Browser, browser)
        val invalidSend = hub.send(
            clientId = browser.id,
            clientToken = browserJoin.clientToken,
            message = SignalingMessage(type = "offer", from = browser, to = app, sdp = longSdp)
        )

        assertTrue(hostJoin.clientToken.isNotBlank())
        assertEquals("INVALID_DEVICE", invalidJoin.code)
        assertFalse(invalidSend.accepted)
        assertEquals("INVALID_MESSAGE", invalidSend.code)
    }

    @Test
    fun joiningNewHostReplacesPreviousHost() {
        val hub = HttpWebRtcSignalingHub()
        val oldApp = SignalingDevice(id = "app-old", name = "Old Desktop")
        val newApp = SignalingDevice(id = "app-new", name = "New Desktop")
        val browser = SignalingDevice(id = "browser", name = "Browser")

        hub.registerHost(oldApp)
        hub.registerHost(newApp)
        val browserJoin = hub.join(HttpWebRtcSignalingRole.Browser, browser)

        assertEquals(listOf(newApp), browserJoin.peers)
    }

    @Test
    fun discoverHostReturnsAppWithoutJoiningBrowser() {
        val hub = HttpWebRtcSignalingHub()
        val app = SignalingDevice(
            id = "app",
            name = "Desktop",
            pathSeparator = "/",
            host = "192.168.1.10",
            port = 12040,
            type = "JVM",
            connectType = "Connect"
        )

        val hostJoin = hub.registerHost(app)
        val discovered = hub.discoverHost()

        assertEquals(
            SignalingDevice(id = "app", name = "Desktop", pathSeparator = "/", type = "JVM"),
            discovered.host
        )
        assertTrue(hub.poll(app.id, hostJoin.clientToken, 0).messages.isEmpty())
    }

    @Test
    fun externalJoinCannotRegisterHostRole() {
        val hub = HttpWebRtcSignalingHub()
        val result = hub.join(HttpWebRtcSignalingRole.Host, SignalingDevice(id = "fake-host"))

        assertEquals("HOST_JOIN_FORBIDDEN", result.code)
        assertEquals(null, hub.discoverHost().host)
    }

    @Test
    fun browserJoinCannotReuseAnExistingPeerId() {
        val hub = HttpWebRtcSignalingHub()
        val app = SignalingDevice(id = "app", name = "Desktop")
        val browser = SignalingDevice(id = "browser")
        val hostJoin = hub.registerHost(app)
        val browserJoin = hub.join(HttpWebRtcSignalingRole.Browser, browser)

        val hostCollision = hub.join(HttpWebRtcSignalingRole.Browser, SignalingDevice(id = app.id, name = "Fake Host"))
        val browserCollision = hub.join(HttpWebRtcSignalingRole.Browser, SignalingDevice(id = browser.id, name = "Imposter"))

        assertEquals("DEVICE_ALREADY_JOINED", hostCollision.code)
        assertEquals("DEVICE_ALREADY_JOINED", browserCollision.code)
        assertTrue(hostCollision.clientToken.isBlank())
        assertTrue(browserCollision.clientToken.isBlank())
        assertEquals(SignalingDevice(id = app.id, name = app.name), hub.discoverHost().host)
        assertTrue(hub.poll(app.id, hostJoin.clientToken, 0).messages.mapNotNull { it.from?.id }.contains(browser.id))
        assertTrue(hub.poll(browser.id, browserJoin.clientToken, 0).messages.none { it.from?.id == app.id })
    }

    @Test
    fun pollAndLeaveRequireMatchingClientToken() {
        val hub = HttpWebRtcSignalingHub()
        val app = SignalingDevice(id = "app")
        val browser = SignalingDevice(id = "browser")
        val hostJoin = hub.registerHost(app)
        hub.join(HttpWebRtcSignalingRole.Browser, browser)

        assertTrue(hub.poll(app.id, "bad-token", 0).messages.isEmpty())
        assertFalse(hub.leave(app.id, "bad-token").accepted)
        assertEquals(SignalingDevice(id = app.id), hub.discoverHost().host)
        assertTrue(hub.leave(app.id, hostJoin.clientToken).accepted)
        assertEquals(null, hub.discoverHost().host)
    }

    @Test
    fun sendRequiresMatchingClientIdAndToken() {
        val hub = HttpWebRtcSignalingHub()
        val app = SignalingDevice(id = "app")
        val browser = SignalingDevice(id = "browser")
        val hostJoin = hub.registerHost(app)
        hub.join(HttpWebRtcSignalingRole.Browser, browser)

        val forged = hub.send(
            clientId = app.id,
            clientToken = "bad-token",
            message = SignalingMessage(
                type = "answer",
                from = app,
                to = browser,
                sdp = "v=0"
            )
        )
        val mismatch = hub.send(
            clientId = browser.id,
            clientToken = hostJoin.clientToken,
            message = SignalingMessage(
                type = "answer",
                from = app,
                to = browser,
                sdp = "v=0"
            )
        )

        assertFalse(forged.accepted)
        assertEquals("UNAUTHORIZED", forged.code)
        assertFalse(mismatch.accepted)
        assertEquals("CLIENT_MISMATCH", mismatch.code)
    }

    @Test
    fun browserCannotSendSignalingToAnotherBrowser() {
        val hub = HttpWebRtcSignalingHub()
        val app = SignalingDevice(id = "app")
        val browserA = SignalingDevice(id = "browser-a")
        val browserB = SignalingDevice(id = "browser-b")
        hub.registerHost(app)
        val browserAJoin = hub.join(HttpWebRtcSignalingRole.Browser, browserA)
        hub.join(HttpWebRtcSignalingRole.Browser, browserB)

        val result = hub.send(
            clientId = browserA.id,
            clientToken = browserAJoin.clientToken,
            SignalingMessage(
                type = "offer",
                from = browserA,
                to = browserB,
                sdp = "v=0",
                connectionAttemptId = "attempt-browser-routing",
            )
        )

        assertFalse(result.accepted)
        assertEquals("PEER_NOT_ALLOWED", result.code)
        assertTrue(hub.poll(browserB.id, "bad-token", 0).messages.none { it.type == "offer" })
    }

    @Test
    fun appRoutesSignalingToTheSelectedBrowserOnly() {
        val hub = HttpWebRtcSignalingHub()
        val app = SignalingDevice(id = "app")
        val browserA = SignalingDevice(id = "browser-a")
        val browserB = SignalingDevice(id = "browser-b")
        val hostJoin = hub.registerHost(app)
        val browserAJoin = hub.join(HttpWebRtcSignalingRole.Browser, browserA)
        val browserBJoin = hub.join(HttpWebRtcSignalingRole.Browser, browserB)

        val result = hub.send(
            clientId = app.id,
            clientToken = hostJoin.clientToken,
            SignalingMessage(
                type = "answer",
                from = app,
                to = browserA,
                sdp = "v=0",
                connectionAttemptId = "attempt-browser-a",
            )
        )

        assertTrue(result.accepted)
        assertEquals(listOf("answer"), hub.poll(browserA.id, browserAJoin.clientToken, 0).messages.map { it.type })
        assertTrue(hub.poll(browserB.id, browserBJoin.clientToken, 0).messages.none { it.type == "answer" })
    }

    @Test
    fun browserLeaveNotifiesHostButNotOtherBrowsers() {
        val hub = HttpWebRtcSignalingHub()
        val app = SignalingDevice(id = "app")
        val browserA = SignalingDevice(id = "browser-a")
        val browserB = SignalingDevice(id = "browser-b")
        val hostJoin = hub.registerHost(app)
        val browserAJoin = hub.join(HttpWebRtcSignalingRole.Browser, browserA)
        hub.join(HttpWebRtcSignalingRole.Browser, browserB)
        val appCursor = hub.poll(app.id, hostJoin.clientToken, 0).nextCursor

        hub.leave(browserA.id, browserAJoin.clientToken)

        assertEquals(listOf("peer-left"), hub.poll(app.id, hostJoin.clientToken, appCursor).messages.map { it.type })
        assertTrue(hub.poll(browserB.id, "bad-token", 0).messages.none { it.type == "peer-left" })
    }
}

package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.signaling.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebRtcSignalingTransportTest {
    @Test
    fun httpUrlsUseHttpSignalingTransport() {
        assertEquals(WebRtcSignalingTransport.Http, resolveWebRtcSignalingTransport("http://127.0.0.1:12040"))
    }

    @Test
    fun websocketUrlsUseWebsocketSignalingTransport() {
        assertEquals(WebRtcSignalingTransport.WebSocket, resolveWebRtcSignalingTransport("ws://127.0.0.1:8443/ws"))
        assertEquals(WebRtcSignalingTransport.WebSocket, resolveWebRtcSignalingTransport("wss://example.test/ws"))
        assertEquals(WebRtcSignalingTransport.WebSocket, resolveWebRtcSignalingTransport("https://example.test/webrtc"))
    }

    @Test
    fun roomConfigNeverAcceptsEmptyRoomId() {
        assertFalse(isWebRtcConfigRoomIdValidForTransport(""))
    }

    @Test
    fun browserHttpConfigUsesHttpSignalingUrl() {
        val config = buildBrowserWebRtcConfig(" http://127.0.0.1:12040 ")

        assertEquals("http://127.0.0.1:12040", config.wssUrl)
        assertEquals(BROWSER_WEBRTC_ROOM_ID, config.roomId)
        assertTrue(config.iceServers.isNotEmpty())
    }

    @Test
    fun browserDeviceSignalingBaseUrlAlwaysUsesHttpSignaling() {
        assertEquals(
            "http://192.168.1.20:12040",
            buildBrowserWebRtcDeviceBaseUrl("192.168.1.20", 12040)
        )
        assertEquals(
            "http://127.0.0.1:12040",
            buildBrowserWebRtcDeviceBaseUrl("127.0.0.1", 12040)
        )
        assertEquals(
            "http://localhost:12040",
            buildBrowserWebRtcDeviceBaseUrl("localhost", 12040)
        )
        assertEquals(
            "http://[fe80::1]:12040",
            buildBrowserWebRtcDeviceBaseUrl("fe80::1", 12040)
        )
        assertEquals("", buildBrowserWebRtcDeviceBaseUrl("", 12040))
        assertEquals("", buildBrowserWebRtcDeviceBaseUrl("192.168.1.20", 0))
    }

    @Test
    fun browserSignalingBaseUrlAcceptsOnlyHttpDevicePorts() {
        assertTrue(isAllowedBrowserWebRtcSignalingBaseUrl("http://192.168.1.20:12040"))
        assertTrue(isAllowedBrowserWebRtcSignalingBaseUrl("http://localhost:12040"))
        assertTrue(isAllowedBrowserWebRtcSignalingBaseUrl("http://127.0.0.1:12040"))
        assertFalse(isAllowedBrowserWebRtcSignalingBaseUrl("https://192.168.1.20:12040"))
        assertFalse(isAllowedBrowserWebRtcSignalingBaseUrl("https://localhost:12040"))
        assertFalse(isAllowedBrowserWebRtcSignalingBaseUrl("https://127.0.0.1:12040"))
        assertFalse(isAllowedBrowserWebRtcSignalingBaseUrl("http://"))
        assertFalse(isAllowedBrowserWebRtcSignalingBaseUrl("ws://192.168.1.20:12040"))
    }

    @Test
    fun browserSignalingEnableAlsoRequiresSecureBrowserContext() {
        assertTrue(canEnableBrowserWebRtcSignaling("http://192.168.1.20:12040", browserSecureContext = true))
        assertFalse(canEnableBrowserWebRtcSignaling("http://192.168.1.20:12040", browserSecureContext = false))
        assertFalse(canEnableBrowserWebRtcSignaling("https://192.168.1.20:12040", browserSecureContext = true))
        assertFalse(canEnableBrowserWebRtcSignaling("https://192.168.1.20:12040", browserSecureContext = false))
    }
}

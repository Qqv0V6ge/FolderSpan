package com.folderspan.service.webui

import kotlin.test.*

class WebUiWireTest {
    @Test
    fun binaryBodyRoundTripsWithoutJsonExpansion() {
        val body = ByteArray(WEB_UI_WIRE_BODY_LIMIT) { (it % 251).toByte() }
        val header = WebUiWireHeader("request", 91, "POST", "$WEB_UI_PLATFORM_PATH/devices/transport?handle=x&operation=control")
        val encoded = WebUiWire.encode(WebUiWirePacket(header, body))
        val result = WebUiWire.decode(encoded)
        assertEquals(header, result.header)
        assertContentEquals(body, result.body)
        assertTrue(encoded.size < body.size + 1024)
    }

    @Test
    fun malformedAndOversizedMessagesAreRejectedBeforeDispatch() {
        assertFailsWith<IllegalArgumentException> { WebUiWire.decode(byteArrayOf(0, 0, 0, 1)) }
        assertFailsWith<IllegalArgumentException> { WebUiWire.decode(byteArrayOf(-1, -1, -1, -1, 0)) }
        assertFailsWith<IllegalArgumentException> { WebUiWire.encode(WebUiWirePacket(WebUiWireHeader("reply"), ByteArray(WEB_UI_WIRE_BODY_LIMIT + 1))) }
        assertFailsWith<IllegalArgumentException> { WebUiWire.encode(WebUiWirePacket(WebUiWireHeader("request", path = "x".repeat(WEB_UI_WIRE_HEADER_LIMIT)))) }
    }

    @Test
    fun bodyRoutesStayOutsideManagementConnection() {
        assertFalse(isWebUiCommandRoute("PUT", "$WEB_UI_PLATFORM_PATH/files/content?path=x"))
        assertFalse(isWebUiCommandRoute("POST", "$WEB_UI_PLATFORM_PATH/files/append?path=x"))
        assertFalse(isWebUiCommandRoute("GET", "$WEB_UI_PLATFORM_PATH/http/download?handle=x"))
        assertFalse(isWebUiCommandRoute("GET", "$WEB_UI_PLATFORM_PATH/networks/downloads?handle=x"))
        assertFalse(isWebUiCommandRoute("GET", "$WEB_UI_PLATFORM_PATH/../login"))
        assertFalse(isWebUiCommandRoute("GET", "https://foreign.example/api/web-ui/platform/files"))
        assertTrue(isWebUiCommandRoute("POST", "$WEB_UI_PLATFORM_PATH/files/create"))
        assertTrue(isWebUiCommandRoute("POST", "$WEB_UI_PLATFORM_PATH/compression"))
    }
}

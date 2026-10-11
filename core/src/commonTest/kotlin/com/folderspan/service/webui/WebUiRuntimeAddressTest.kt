package com.folderspan.service.webui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class WebUiRuntimeAddressTest {
    @Test
    fun fileQueryEncodesUtf8BytesAndReservedCharacters() {
        assertEquals("%2F%E6%96%87%E4%BB%B6%2F%C3%A9%20%2B%23%3F.txt", encodeWebUiQuery("/文件/é +#?.txt"))
        assertEquals("Az09-_.~", encodeWebUiQuery("Az09-_.~"))
    }

    @Test
    fun oneOriginBuildsHttpUrlsAndRejectsUnsafeTargets() {
        val local = WebUiRuntimeAddress.parse(DEFAULT_WEB_UI_ADDRESS)
        assertEquals("http://127.0.0.1:12043/api/web-ui/platform/files/info?path=%2F", local.httpUrl("/api/web-ui/platform/files/info?path=%2F"))
        assertEquals("https://runtime.example:8443", WebUiRuntimeAddress.parse("runtime.example:8443", true).origin)
        assertEquals("https://[2001:db8::1]:8443/api/web-ui/events", WebUiRuntimeAddress.parse("https://[2001:db8::1]:8443/").httpUrl(WEB_UI_EVENTS_PATH))
        for (invalid in listOf("", "https://user:secret@host", "https://host/path", "https://host?", "https://host#", "https://host:0", "https://host:65536", "https://host:", "file:///tmp", "https://host\\path", "host name", "https://[notipv6]", "https://[2001:::1]", "http://300.1.1.1", "http://host|name", "http://host%20name")) {
            assertFails(invalid) { WebUiRuntimeAddress.parse(invalid) }
        }
        assertFails { WebUiRuntimeAddress.parse("http://localhost:12043", securePage = true) }
        assertFails { local.httpUrl("//other.example/api/web-ui/login") }
    }
}

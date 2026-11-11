package com.folderspan.service.webui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebUiStorageContentSecurityTest {
    @Test
    fun passwordTransportOnlyTreatsExplicitLoopbackHostsAsLocal() {
        for (host in listOf("localhost", "LOCALHOST", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1")) {
            assertTrue(isStorageContentLoopbackHost(host), host)
        }
        for (host in listOf("127.1", "127.0.0.2", "0177.0.0.1", "2130706433", "localhost.example",
            "localhost.", "localhost@remote.example", "192.0.2.1", "::ffff:127.0.0.1", "")) {
            assertFalse(isStorageContentLoopbackHost(host), host)
        }
    }
}

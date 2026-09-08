package com.folderspan.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ShareNetworkUtilsTest {
    @Test
    fun parsesSmbShareFromSchemeAddress() {
        val parsed = assertNotNull(parseSmbAddress("smb://server.local/media", 445))

        assertEquals("server.local:445", parsed.normalizedHost)
        assertEquals("media", parsed.shareName)
        assertEquals("smb://server.local:445/media", parsed.displayAddress)
    }

    @Test
    fun parsesSmbShareFromUncAddress() {
        val parsed = assertNotNull(parseSmbAddress("\\\\server.local\\documents", 445))

        assertEquals("server.local:445", parsed.normalizedHost)
        assertEquals("documents", parsed.shareName)
        assertEquals("server.local:445\\documents", parsed.displayAddress)
    }

    @Test
    fun keepsSmbShareBlankWhenAddressHasOnlyHost() {
        val parsed = assertNotNull(parseSmbAddress("server.local", 445))

        assertEquals("server.local:445", parsed.normalizedHost)
        assertEquals("", parsed.shareName)
        assertEquals("server.local:445", parsed.displayAddress)
    }
}

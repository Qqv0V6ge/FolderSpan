package com.folderspan.service.http.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkShareHttpsTest {
    @Test
    fun defaultHttpsPortUsesSamePublicPort() {
        assertEquals(1204, defaultLinkShareHttpsPort(1204))
        assertEquals(65535, defaultLinkShareHttpsPort(65535))
        assertNull(defaultLinkShareHttpsPort(0))
    }

    @Test
    fun consentKeyIncludesEndpointAndFingerprint() {
        val key = buildLinkShareHttpsConsentKey(
            host = "Example.LOCAL",
            port = 1204,
            tlsFingerprintSha256 = "aa:bb cc"
        )

        assertEquals("v1:example.local:1204:AABBCC", key)
    }
}

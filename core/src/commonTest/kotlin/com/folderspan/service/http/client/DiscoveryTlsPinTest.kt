package com.folderspan.service.http.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscoveryTlsPinTest {
    @Test
    fun handshakeFingerprintIsNormalizedAndUsedAsPin() {
        assertEquals("AABBCC", requireHandshakeTlsPin("aa:bb:cc"))
    }

    @Test
    fun missingHandshakeFingerprintCannotBePinned() {
        assertNull(requireHandshakeTlsPin(""))
        assertNull(requireHandshakeTlsPin(null))
        assertNull(requireHandshakeTlsPin("   "))
    }
}

class LeafCertificateCaptureTest {
    @Test
    fun acceptNormalizesAndTakeClears() {
        val capture = LeafCertificateCapture()
        capture.accept("aa:bb:cc")

        assertEquals("AABBCC", capture.take())
        assertNull(capture.take())
    }

    @Test
    fun blankFingerprintIsIgnored() {
        val capture = LeafCertificateCapture()
        capture.accept("   ")

        assertNull(capture.take())
    }

    @Test
    fun latestHandshakeReplacesPrevious() {
        val capture = LeafCertificateCapture()
        capture.accept("aa")
        capture.accept("bb:cc")

        assertEquals("BBCC", capture.take())
    }
}

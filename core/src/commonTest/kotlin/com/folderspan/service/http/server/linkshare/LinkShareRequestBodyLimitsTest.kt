package com.folderspan.service.http.server.linkshare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkShareRequestBodyLimitsTest {
    @Test
    fun bufferedBodyLimitIsFarBelowStreamingUploadLimit() {
        assertTrue(LinkShareRequestBodyLimits.MAX_BUFFERED_REQUEST_BODY_BYTES <= 8L * 1024L * 1024L)
        assertTrue(LinkShareRequestBodyLimits.MAX_STREAMING_REQUEST_BODY_BYTES <= 128L * 1024L * 1024L)
        assertTrue(
            LinkShareRequestBodyLimits.MAX_BUFFERED_REQUEST_BODY_BYTES <
                LinkShareRequestBodyLimits.MAX_STREAMING_REQUEST_BODY_BYTES,
        )
    }

    @Test
    fun shareUploadStillStreamsAndDoesNotMaterializeBodyArray() {
        assertTrue(LinkShareRequestBodyLimits.isStreamingUploadPath("/api/share/upload"))
        assertTrue(LinkShareRequestBodyLimits.isStreamingUploadPath("/api/share/upload?name=a.txt"))
        assertFalse(LinkShareRequestBodyLimits.isStreamingUploadPath("/"))
        assertFalse(LinkShareRequestBodyLimits.isStreamingUploadPath("/auth"))
        assertFalse(LinkShareRequestBodyLimits.allowsNonEmptyBody("GET"))
        assertFalse(LinkShareRequestBodyLimits.allowsNonEmptyBody("HEAD"))
        assertFalse(LinkShareRequestBodyLimits.allowsNonEmptyBody("OPTIONS"))
        assertTrue(LinkShareRequestBodyLimits.allowsNonEmptyBody("POST"))
    }

    @Test
    fun defaultShareUsesStreamingLimitOnlyOnUploadPath() {
        val buffered = LinkShareRequestBodyLimits.MAX_BUFFERED_REQUEST_BODY_BYTES
        assertEquals(
            LinkShareRequestBodyLimits.MAX_STREAMING_REQUEST_BODY_BYTES,
            LinkShareRequestBodyLimits.maxBodyBytes("/api/share/upload", buffered),
        )
        assertEquals(
            buffered,
            LinkShareRequestBodyLimits.maxBodyBytes("/", buffered),
        )
        assertEquals(
            1024L * 1024L,
            LinkShareRequestBodyLimits.maxBodyBytes("/api/share/upload", 1024L * 1024L),
        )
        assertEquals(
            1024L * 1024L,
            LinkShareRequestBodyLimits.maxBodyBytes("/mcp", 1024L * 1024L),
        )
    }

    @Test
    fun missingClientCookieReusesStableIdForSameSource() {
        val first = deriveStableLinkShareClientId("192.168.1.20", "FolderSpan Test")
        val second = deriveStableLinkShareClientId("192.168.1.20", "FolderSpan Test")
        val other = deriveStableLinkShareClientId("192.168.1.21", "FolderSpan Test")
        assertEquals(first, second)
        assertTrue(first.isSafeLinkShareToken())
        assertEquals(LINK_SHARE_DERIVED_CLIENT_ID_LENGTH, first.length)
        assertTrue(first != other)
    }
}

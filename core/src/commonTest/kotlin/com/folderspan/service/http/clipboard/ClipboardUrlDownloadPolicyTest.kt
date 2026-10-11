package com.folderspan.service.http.clipboard

import strings.AppStrings

import com.folderspan.test.ChineseLocalizationTest
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipboardUrlDownloadPolicyTest : ChineseLocalizationTest() {
    private val native = ClipboardUrlDownloadPlatformCapabilities(
        platformName = "Desktop",
        canSetCookie = true,
        canSetUserAgent = true,
        canControlAutomaticHeaders = true,
        browserCredentialsOmitted = false,
    )

    @Test
    fun responseClassificationRejectsHtmlAndAuthentication() {
        assertEquals(
            ClipboardUrlResponseKind.Html,
            classifyClipboardUrlResponse(
                200,
                mapOf(HttpHeaders.ContentType to "text/html; charset=utf-8"),
                "https://example.com/index.html",
            ).kind,
        )
        assertEquals(
            ClipboardUrlResponseKind.AuthenticationRequired,
            classifyClipboardUrlResponse(401, emptyMap(), "https://example.com/file").kind,
        )
        assertEquals(
            ClipboardUrlResponseKind.Downloadable,
            classifyClipboardUrlResponse(
                200,
                mapOf(HttpHeaders.ContentDisposition to "attachment"),
                "https://example.com/",
            ).kind,
        )
        assertEquals(
            ClipboardUrlResponseKind.MissingFileIdentity,
            classifyClipboardUrlResponse(200, emptyMap(), "https://example.com/").kind,
        )
    }

    @Test
    fun fileNameUsesExtendedDispositionBeforeOtherSources() {
        assertEquals(
            AppStrings.ui_test_clipboard_url_download_policy_report_2026_pdf,
            resolveClipboardDownloadFileName(
                "attachment; filename=old.pdf; filename*=UTF-8''%E6%8A%A5%E5%91%8A%202026.pdf",
                "https://example.com/path/final.bin",
                "application/pdf",
                1L,
            ),
        )
        assertEquals("name.txt", sanitizeClipboardDownloadFileName("../safe/name.txt"))
        assertEquals("download-7.pdf", resolveClipboardDownloadFileName(null, "https://example.com/", "application/pdf", 7L))
        assertNull(sanitizeClipboardDownloadFileName(".."))
    }

    @Test
    fun requestHeadersNeverInventCredentialsAndCookieIsInitialOriginOnly() {
        val config = validConfig(automaticHeaders = true)
        val sameOrigin = buildClipboardUrlRequestHeaders(config, "https://example.com/next")
        assertEquals("a=b", sameOrigin.values[HttpHeaders.Cookie])
        assertTrue(sameOrigin.sendsTaskCookie)
        assertFalse(sameOrigin.values.containsKey(HttpHeaders.Authorization))
        assertFalse(sameOrigin.values.containsKey(HttpHeaders.Referrer))

        val crossOrigin = buildClipboardUrlRequestHeaders(config, "https://cdn.example.net/file")
        assertFalse(crossOrigin.sendsTaskCookie)
        assertFalse(crossOrigin.values.containsKey(HttpHeaders.Cookie))
    }

    @Test
    fun rangeHeadersRemainWhenAutomaticHeadersAreDisabled() {
        val config = validConfig(automaticHeaders = false)
        val headers = buildClipboardUrlRequestHeaders(
            config,
            config.url,
            range = 10L..19L,
            ifRange = "\"stable\"",
        ).values
        assertEquals("bytes=10-19", headers[HttpHeaders.Range])
        assertEquals("\"stable\"", headers[HttpHeaders.IfRange])
        assertFalse(headers.containsKey(HttpHeaders.Accept))
        assertFalse(headers.containsKey(HttpHeaders.AcceptEncoding))
    }

    @Test
    fun retryPolicyIsBoundedAndRejectsPermanentFailures() {
        assertTrue(isClipboardDownloadRetryable(ClipboardUrlFailureType.TransportInterrupted))
        assertTrue(isClipboardDownloadRetryable(ClipboardUrlFailureType.HttpStatus, 429))
        assertTrue(isClipboardDownloadRetryable(ClipboardUrlFailureType.HttpStatus, 503))
        assertFalse(isClipboardDownloadRetryable(ClipboardUrlFailureType.HttpStatus, 404))
        assertFalse(isClipboardDownloadRetryable(ClipboardUrlFailureType.Tls))
        assertEquals(30_000L, clipboardDownloadRetryDelayMillis(8, maximumDelayMillis = 30_000L))
        assertEquals(30_000L, clipboardDownloadRetryDelayMillis(0, retryAfter = "120", maximumDelayMillis = 30_000L))
    }

    @Test
    fun rangesAreContiguousAndRequireMatchingVersion() {
        val ranges = planClipboardByteRanges(totalBytes = 10L, requestedThreads = 3)
        assertEquals(listOf(0L..3L, 4L..6L, 7L..9L), ranges.map(ClipboardByteRange::asLongRange))
        val validator = clipboardRangeValidator("\"v1\"", null)!!
        assertTrue(
            validateClipboardRangeResponse(
                status = 206,
                contentRange = "bytes 4-6/10",
                expected = ranges[1],
                expectedTotal = 10L,
                expectedValidator = validator,
                etag = "\"v1\"",
                lastModified = null,
            )
        )
        assertFalse(
            validateClipboardRangeResponse(
                status = 206,
                contentRange = "bytes 4-6/10",
                expected = ranges[1],
                expectedTotal = 10L,
                expectedValidator = validator,
                etag = "\"v2\"",
                lastModified = null,
            )
        )
    }

    @Test
    fun redirectMustRemainHttpWithoutUserInfo() {
        assertEquals(
            "https://example.com/files/a.zip",
            validateClipboardRedirect("https://example.com/base", "/files/a.zip"),
        )
        assertNull(validateClipboardRedirect("https://example.com/base", "file:///tmp/a"))
        assertNull(validateClipboardRedirect("https://example.com/base", "https://user:pass@example.com/a"))
    }

    private fun validConfig(automaticHeaders: Boolean): ClipboardUrlDownloadTaskConfig {
        val result = ClipboardUrlDownloadDraft(
            rawText = "https://example.com/file",
            url = "https://example.com/file",
            targetSummary = "https://example.com",
            cookie = "a=b",
            userAgent = "FolderSpan/1.0 (Desktop) ClipboardUrlDownload/1",
            automaticHeaders = automaticHeaders,
            capabilities = native,
        ).validate()
        return (result as ClipboardUrlDownloadConfigResult.Valid).config
    }
}

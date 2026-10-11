package com.folderspan.service.http.clipboard

import strings.AppStrings

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import com.folderspan.test.runSuspendTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ClipboardUrlDownloadEngineTest {
    @Test
    fun singleGetStreamsToStagingWithTaskHeaders()= runSuspendTest {
        val requests = mutableListOf<Map<String, String>>()
        val client = mockClient { request ->
            requests += request.headers.entries().associate { (name, values) -> name to values.joinToString() }
            respond(
                content = ByteReadChannel("file-data".encodeToByteArray()),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf("9"),
                    HttpHeaders.ContentType to listOf("application/octet-stream"),
                    HttpHeaders.ContentDisposition to listOf("attachment; filename=sample.bin"),
                ),
            )
        }
        val config = config()
        val result = ClipboardUrlDownloadEngine(
            clientProvider = { client },
            stagingFactory = MemoryClipboardDownloadStagingFactory(),
        ).download(config)

        val success = assertIs<ClipboardUrlDownloadResult.Success>(result)
        assertEquals("sample.bin", success.staged.displayName)
        assertContentEquals("file-data".encodeToByteArray(), success.staged.bytes)
        assertEquals("a=b", requests.single()[HttpHeaders.Cookie])
        assertEquals(DEFAULT_AGENT, requests.single()[HttpHeaders.UserAgent])
        assertTrue(requests.single().containsKey(HttpHeaders.Accept))
        assertEquals("", config.cookie)
        assertEquals("", config.userAgent)
    }

    @Test
    fun missingContentLengthIsRejectedWithoutPublishing()= runSuspendTest {
        val body = "stream-without-size".encodeToByteArray()
        val progress = mutableListOf<ClipboardUrlDownloadProgress>()
        val staging = TrackingStagingFactory()
        val client = mockClient {
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/octet-stream"),
                    HttpHeaders.ContentDisposition to listOf("attachment; filename=unknown-size.bin"),
                ),
            )
        }

        val result = ClipboardUrlDownloadEngine(
            clientProvider = { client },
            stagingFactory = staging,
        ).download(config(), onProgress = { progress += it })

        assertEquals(
            ClipboardUrlDownloadError.MissingFileSize,
            assertIs<ClipboardUrlDownloadResult.Failure>(result).error,
        )
        assertTrue(progress.isEmpty())
        assertEquals(1, staging.cleanupCount)
    }

    @Test
    fun unknownRangeTotalFallsBackAndRejectsFullResponseWithoutLength()= runSuspendTest {
        val body = "unknown-range-total".encodeToByteArray()
        var fullGets = 0
        val progress = mutableListOf<ClipboardUrlDownloadProgress>()
        val staging = TrackingStagingFactory()
        val client = mockClient { request ->
            when (request.headers[HttpHeaders.Range]) {
                "bytes=0-0" -> respond(
                    content = ByteReadChannel(byteArrayOf(body.first())),
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("application/octet-stream"),
                        HttpHeaders.ContentRange to listOf("bytes 0-0/*"),
                        HttpHeaders.ETag to listOf("\"v1\""),
                    ),
                )

                null -> {
                    fullGets++
                    respond(
                        content = ByteReadChannel(body),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
                    )
                }

                else -> error(AppStrings.ui_test_clipboard_url_download_engine_unknown_total_size_should_not_initiate)
            }
        }

        val result = ClipboardUrlDownloadEngine(
            clientProvider = { client },
            stagingFactory = staging,
        ).download(config(threads = 4), onProgress = { progress += it })

        assertEquals(
            ClipboardUrlDownloadError.MissingFileSize,
            assertIs<ClipboardUrlDownloadResult.Failure>(result).error,
        )
        assertEquals(1, fullGets)
        assertTrue(progress.isEmpty())
        assertEquals(2, staging.cleanupCount)
    }

    @Test
    fun retryableStatusUsesBoundedRetryThenSucceeds()= runSuspendTest {
        var calls = 0
        val waits = mutableListOf<Long>()
        val client = mockClient {
            calls++
            if (calls == 1) {
                respondError(HttpStatusCode.ServiceUnavailable, headers = headersOf(HttpHeaders.RetryAfter, "1"))
            } else {
                respond(
                    ByteReadChannel(byteArrayOf(1, 2)),
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("application/octet-stream"),
                        HttpHeaders.ContentLength to listOf("2"),
                    ),
                )
            }
        }
        val result = ClipboardUrlDownloadEngine(
            clientProvider = { client },
            stagingFactory = MemoryClipboardDownloadStagingFactory(),
            retryDelay = { waits += it },
            jitter = { 0.5 },
        ).download(config())

        assertIs<ClipboardUrlDownloadResult.Success>(result)
        assertEquals(2, calls)
        assertEquals(listOf(1_000L), waits)
    }

    @Test
    fun retryableStatusStopsAfterConfiguredRetryCount()= runSuspendTest {
        var calls = 0
        val client = mockClient {
            calls++
            respondError(HttpStatusCode.ServiceUnavailable)
        }
        val result = ClipboardUrlDownloadEngine(
            clientProvider = { client },
            stagingFactory = MemoryClipboardDownloadStagingFactory(),
            retryDelay = {},
            jitter = { 0.5 },
        ).download(config(retries = 2))

        assertEquals(3, calls)
        assertEquals(
            ClipboardUrlDownloadError.HttpFailure,
            assertIs<ClipboardUrlDownloadResult.Failure>(result).error,
        )
    }

    @Test
    fun transportFailureIsRetried()= runSuspendTest {
        var calls = 0
        val client = mockClient {
            calls++
            if (calls == 1) throw IOException("interrupted")
            respond(
                ByteReadChannel(byteArrayOf(1)),
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/octet-stream"),
                    HttpHeaders.ContentLength to listOf("1"),
                ),
            )
        }
        val result = ClipboardUrlDownloadEngine(
            clientProvider = { client },
            stagingFactory = MemoryClipboardDownloadStagingFactory(),
            retryDelay = {},
        ).download(config(retries = 1))

        assertIs<ClipboardUrlDownloadResult.Success>(result)
        assertEquals(2, calls)
    }

    @Test
    fun htmlAndAuthenticationAreNotRetried()= runSuspendTest {
        var calls = 0
        val client = mockClient {
            calls++
            respond(
                ByteReadChannel("<html/>".encodeToByteArray()),
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val result = ClipboardUrlDownloadEngine(
            clientProvider = { client },
            stagingFactory = MemoryClipboardDownloadStagingFactory(),
            retryDelay = { error(AppStrings.ui_test_clipboard_url_download_engine_should_not_retry) },
        ).download(config())
        assertEquals(ClipboardUrlDownloadError.HtmlContent, assertIs<ClipboardUrlDownloadResult.Failure>(result).error)
        assertEquals(1, calls)
    }

    @Test
    fun knownSizeIsDownloadedWithoutAFixedPolicyLimit()= runSuspendTest {
        val result = ClipboardUrlDownloadEngine(
            clientProvider = {
                mockClient {
                    respond(
                        ByteReadChannel(ByteArray(8)),
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/octet-stream"),
                            HttpHeaders.ContentLength to listOf("8"),
                        ),
                    )
                }
            },
            stagingFactory = MemoryClipboardDownloadStagingFactory(),
            policy = ClipboardUrlDownloadPolicy(chunkSize = 2),
        ).download(config())

        assertEquals(8L, assertIs<ClipboardUrlDownloadResult.Success>(result).staged.size)
    }

    @Test
    fun declaredSizeAboveFormerNativeLimitIsNotRejectedBeforeStreaming()= runSuspendTest {
        val declaredSize = 4L * 1024L * 1024L * 1024L + 1L
        val progress = mutableListOf<ClipboardUrlDownloadProgress>()
        val client = mockClient {
            respond(
                ByteReadChannel(byteArrayOf(1)),
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/octet-stream"),
                    HttpHeaders.ContentLength to listOf(declaredSize.toString()),
                ),
            )
        }

        val result = ClipboardUrlDownloadEngine(
            clientProvider = { client },
            stagingFactory = MemoryClipboardDownloadStagingFactory(),
        ).download(config(retries = 0), onProgress = { progress += it })

        assertEquals(
            ClipboardUrlDownloadError.TransportInterrupted,
            assertIs<ClipboardUrlDownloadResult.Failure>(result).error,
        )
        assertEquals(listOf(declaredSize), progress.map { it.totalBytes })
        assertEquals(listOf(1L), progress.map { it.bytesReceived })
    }

    @Test
    fun rangeProbeUsesContentRangeTotalWhenContentLengthIsMissing()= runSuspendTest {
        val client = mockClient { request ->
            when (request.headers[HttpHeaders.Range]) {
                "bytes=0-0" -> respond(
                    ByteReadChannel(byteArrayOf('A'.code.toByte())),
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("application/octet-stream"),
                        HttpHeaders.ContentRange to listOf("bytes 0-0/8"),
                        HttpHeaders.ETag to listOf("\"v1\""),
                        HttpHeaders.ContentDisposition to listOf("attachment; filename=range.bin"),
                    ),
                )

                "bytes=0-3" -> rangeResponse(0, 3, "ABCD")
                "bytes=4-7" -> rangeResponse(4, 7, "EFGH")
                else -> error(AppStrings.ui_test_clipboard_url_download_engine_unexpected_range)
            }
        }

        val result = ClipboardUrlDownloadEngine(
            clientProvider = { client },
            stagingFactory = MemoryClipboardDownloadStagingFactory(),
        ).download(config(threads = 2))

        assertContentEquals(
            "ABCDEFGH".encodeToByteArray(),
            assertIs<ClipboardUrlDownloadResult.Success>(result).staged.bytes,
        )
    }

    @Test
    fun validRangesAreDownloadedAndMergedInOrder()= runSuspendTest {
        val seenRanges = mutableListOf<String>()
        val client = mockClient { request ->
            val range = request.headers[HttpHeaders.Range].orEmpty()
            seenRanges += range
            when (range) {
                "bytes=0-0" -> rangeResponse(0, 0, "A")
                "bytes=0-3" -> rangeResponse(0, 3, "ABCD")
                "bytes=4-7" -> rangeResponse(4, 7, "EFGH")
                else -> error(AppStrings.ui_test_clipboard_url_download_engine_unexpected_range)
            }
        }
        val result = ClipboardUrlDownloadEngine(
            clientProvider = { client },
            stagingFactory = MemoryClipboardDownloadStagingFactory(),
        ).download(config(threads = 2))
        val success = assertIs<ClipboardUrlDownloadResult.Success>(result)
        assertContentEquals("ABCDEFGH".encodeToByteArray(), success.staged.bytes)
        assertTrue("bytes=0-0" in seenRanges)
        assertTrue("bytes=0-3" in seenRanges)
        assertTrue("bytes=4-7" in seenRanges)
    }

    @Test
    fun ignoredRangeCleansSegmentsAndFallsBackToSingleGet()= runSuspendTest {
        var fullGets = 0
        val client = mockClient { request ->
            when (request.headers[HttpHeaders.Range]) {
                "bytes=0-0" -> rangeResponse(0, 0, "A")
                null -> {
                    fullGets++
                    respond(
                        ByteReadChannel("ABCDEFGH".encodeToByteArray()),
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/octet-stream"),
                            HttpHeaders.ContentLength to listOf("8"),
                        ),
                    )
                }
                else -> respond(
                    ByteReadChannel("ABCDEFGH".encodeToByteArray()),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
                )
            }
        }
        val result = ClipboardUrlDownloadEngine(
            clientProvider = { client },
            stagingFactory = MemoryClipboardDownloadStagingFactory(),
        ).download(config(threads = 2))
        assertContentEquals(
            "ABCDEFGH".encodeToByteArray(),
            assertIs<ClipboardUrlDownloadResult.Success>(result).staged.bytes,
        )
        assertEquals(1, fullGets)
    }

    @Test
    fun changedRangeValidatorCleansSegmentsAndFallsBackToSingleGet()= runSuspendTest {
        var fullGets = 0
        val client = mockClient { request ->
            when (request.headers[HttpHeaders.Range]) {
                "bytes=0-0" -> rangeResponse(0, 0, "A")
                "bytes=0-3" -> rangeResponse(0, 3, "ABCD", etag = "\"v2\"")
                "bytes=4-7" -> rangeResponse(4, 7, "EFGH")
                null -> {
                    fullGets++
                    respond(
                        ByteReadChannel("ABCDEFGH".encodeToByteArray()),
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/octet-stream"),
                            HttpHeaders.ContentLength to listOf("8"),
                        ),
                    )
                }
                else -> error(AppStrings.ui_test_clipboard_url_download_engine_unexpected_range)
            }
        }
        val result = ClipboardUrlDownloadEngine(
            clientProvider = { client },
            stagingFactory = MemoryClipboardDownloadStagingFactory(),
        ).download(config(threads = 2))

        assertContentEquals(
            "ABCDEFGH".encodeToByteArray(),
            assertIs<ClipboardUrlDownloadResult.Success>(result).staged.bytes,
        )
        assertEquals(1, fullGets)
    }

    @Test
    fun manualRedirectDropsCookieAcrossOriginsAndDoesNotAdoptSetCookie()= runSuspendTest {
        val seen = mutableListOf<Triple<String, String?, String?>>()
        val client = mockClient { request ->
            seen += Triple(
                request.url.host,
                request.headers[HttpHeaders.Cookie],
                request.headers[HttpHeaders.UserAgent],
            )
            if (request.url.host == "example.com") {
                respond(
                    ByteReadChannel.Empty,
                    status = HttpStatusCode.Found,
                    headers = headersOf(
                        HttpHeaders.Location to listOf("https://cdn.example.net/file.bin"),
                        HttpHeaders.SetCookie to listOf("server=ignored"),
                    ),
                )
            } else {
                respond(
                    ByteReadChannel(byteArrayOf(7)),
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("application/octet-stream"),
                        HttpHeaders.ContentLength to listOf("1"),
                    ),
                )
            }
        }
        val result = ClipboardUrlDownloadEngine(
            clientProvider = { client },
            stagingFactory = MemoryClipboardDownloadStagingFactory(),
        ).download(config())
        assertIs<ClipboardUrlDownloadResult.Success>(result)
        assertEquals("a=b", seen[0].second)
        assertEquals(null, seen[1].second)
        assertEquals(DEFAULT_AGENT, seen[1].third)
    }

    @Test
    fun cancellationIsPropagatedAndStagingIsCleaned()= runSuspendTest {
        val client = mockClient { throw CancellationException("test") }
        val staging = TrackingStagingFactory()
        assertFailsWith<CancellationException> {
            ClipboardUrlDownloadEngine(
                clientProvider = { client },
                stagingFactory = staging,
            ).download(config())
        }
        assertEquals(1, staging.cleanupCount)
    }

    private fun mockClient(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            expectSuccess = false
            followRedirects = false
        }

    private fun MockRequestHandleScope.rangeResponse(
        first: Long,
        last: Long,
        text: String,
        etag: String = "\"v1\"",
    ) = respond(
        ByteReadChannel(text.encodeToByteArray()),
        status = HttpStatusCode.PartialContent,
        headers = headersOf(
            HttpHeaders.ContentType to listOf("application/octet-stream"),
            HttpHeaders.ContentLength to listOf(text.encodeToByteArray().size.toString()),
            HttpHeaders.ContentRange to listOf("bytes $first-$last/8"),
            HttpHeaders.ETag to listOf(etag),
            HttpHeaders.ContentDisposition to listOf("attachment; filename=range.bin"),
        ),
    )

    private fun config(threads: Int = 1, retries: Int = 2): ClipboardUrlDownloadTaskConfig {
        val draft = ClipboardUrlDownloadDraft(
            rawText = "https://example.com/file.bin?secret=1",
            url = "https://example.com/file.bin?secret=1",
            targetSummary = "https://example.com",
            cookie = "a=b",
            userAgent = DEFAULT_AGENT,
            threads = threads.toString(),
            retries = retries.toString(),
            capabilities = NATIVE,
        )
        return (draft.validate() as ClipboardUrlDownloadConfigResult.Valid).config
    }

    private class TrackingStagingFactory : ClipboardDownloadStagingFactory {
        var cleanupCount = 0
            private set

        override suspend fun create(): ClipboardDownloadStagingBatch {
            val delegate = MemoryClipboardDownloadStagingFactory().create()
            return object : ClipboardDownloadStagingBatch by delegate {
                override suspend fun cleanup() {
                    cleanupCount++
                    delegate.cleanup()
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_AGENT = "FolderSpan/1.0 (Desktop) ClipboardUrlDownload/1"
        val NATIVE = ClipboardUrlDownloadPlatformCapabilities(
            platformName = "Desktop",
            canSetCookie = true,
            canSetUserAgent = true,
            canControlAutomaticHeaders = true,
            browserCredentialsOmitted = false,
        )
    }
}

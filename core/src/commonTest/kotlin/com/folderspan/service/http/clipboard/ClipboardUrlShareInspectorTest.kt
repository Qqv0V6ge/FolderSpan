package com.folderspan.service.http.clipboard

import com.folderspan.service.http.server.linkshare.LinkShareHttpResponseBodyWriter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import com.folderspan.test.runSuspendTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipboardUrlShareInspectorTest {
    @AfterTest
    fun tearDown() {
        ClipboardUrlShareSourceRegistry.releaseAll()
    }

    @Test
    fun inspectionRequestsOnlyAProbeAndBuildsAFileSimpleInfo()= runSuspendTest {
        val ranges = mutableListOf<String?>()
        val inspector = DefaultClipboardUrlShareInspector(
            clientProvider = {
                mockClient { request ->
                    ranges += request.headers[HttpHeaders.Range]
                    respond(
                        content = ByteReadChannel(byteArrayOf(7)),
                        status = HttpStatusCode.PartialContent,
                        headers = headersOf(
                            HttpHeaders.ContentLength to listOf("1"),
                            HttpHeaders.ContentRange to listOf("bytes 0-0/4096"),
                            HttpHeaders.ContentType to listOf("application/pdf"),
                            HttpHeaders.ContentDisposition to listOf("attachment; filename=report.pdf"),
                        ),
                    )
                }
            },
            capabilities = NATIVE,
        )

        val success = assertIs<ClipboardUrlShareInspectionResult.Success>(
            inspector.inspect("https://example.test/report.pdf?token=secret#fragment")
        )

        assertEquals(listOf<String?>("bytes=0-0"), ranges)
        assertEquals("report.pdf", success.file.name)
        assertEquals(4096L, success.file.size)
        assertEquals("application/pdf", success.file.mineType)
        assertFalse(success.file.isDirectory)
        assertFalse(success.file.path.contains("example.test"))
        assertFalse(success.file.path.contains("secret"))
        assertNotNull(ClipboardUrlShareSourceRegistry.find(success.file.path))

        success.release()
        assertNull(ClipboardUrlShareSourceRegistry.find(success.file.path))
    }

    @Test
    fun registeredSourceStreamsOnlyTheRequestedRange()= runSuspendTest {
        val ranges = mutableListOf<String?>()
        val inspector = DefaultClipboardUrlShareInspector(
            clientProvider = {
                mockClient { request ->
                    val range = request.headers[HttpHeaders.Range]
                    ranges += range
                    when (range) {
                        "bytes=0-0" -> respond(
                            content = ByteReadChannel("A".encodeToByteArray()),
                            status = HttpStatusCode.PartialContent,
                            headers = headersOf(
                                HttpHeaders.ContentLength to listOf("1"),
                                HttpHeaders.ContentRange to listOf("bytes 0-0/8"),
                                HttpHeaders.ContentType to listOf("application/octet-stream"),
                                HttpHeaders.ContentDisposition to listOf("attachment; filename=data.bin"),
                            ),
                        )

                        "bytes=2-4" -> respond(
                            content = ByteReadChannel("CDE".encodeToByteArray()),
                            status = HttpStatusCode.PartialContent,
                            headers = headersOf(
                                HttpHeaders.ContentLength to listOf("3"),
                                HttpHeaders.ContentRange to listOf("bytes 2-4/8"),
                                HttpHeaders.ContentType to listOf("application/octet-stream"),
                            ),
                        )

                        else -> error("unexpected range: $range")
                    }
                }
            },
            capabilities = NATIVE,
        )
        val success = assertIs<ClipboardUrlShareInspectionResult.Success>(
            inspector.inspect("https://example.test/data.bin")
        )
        val source = assertNotNull(ClipboardUrlShareSourceRegistry.find(success.file.path))
        val writer = RecordingWriter()

        source.streamRange(startOffset = 2L, endOffsetExclusive = 5L, writer = writer)

        assertEquals(listOf<String?>("bytes=0-0", "bytes=2-4"), ranges)
        assertContentEquals("CDE".encodeToByteArray(), writer.bytes())
        assertTrue(writer.flushed)
    }

    @Test
    fun invalidOrCredentialedUrlIsRejectedBeforeCreatingAClient()= runSuspendTest {
        var clients = 0
        val inspector = DefaultClipboardUrlShareInspector(
            clientProvider = {
                clients++
                error("client should not be created")
            },
            capabilities = NATIVE,
        )

        val result = inspector.inspect("https://user:secret@example.test/file.bin")

        assertEquals(
            ClipboardUrlDownloadError.InvalidUrl,
            assertIs<ClipboardUrlShareInspectionResult.Failure>(result).error,
        )
        assertEquals(0, clients)
    }

    private fun mockClient(handler: io.ktor.client.engine.mock.MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            expectSuccess = false
            followRedirects = false
        }

    private class RecordingWriter : LinkShareHttpResponseBodyWriter {
        private val chunks = mutableListOf<ByteArray>()
        var flushed = false
            private set

        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
            chunks += bytes.copyOfRange(offset, offset + length)
        }

        override suspend fun flush() {
            flushed = true
        }

        fun bytes(): ByteArray = chunks.fold(byteArrayOf()) { result, chunk -> result + chunk }
    }

    private companion object {
        val NATIVE = ClipboardUrlDownloadPlatformCapabilities(
            platformName = "Desktop",
            canSetCookie = true,
            canSetUserAgent = true,
            canControlAutomaticHeaders = true,
            browserCredentialsOmitted = false,
        )
    }
}

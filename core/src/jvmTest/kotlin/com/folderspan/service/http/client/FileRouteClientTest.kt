package com.folderspan.service.http.client

import com.folderspan.createSettings
import com.folderspan.exception.DeviceCopyUnsupportedException
import com.folderspan.service.data.toSerializableResult
import com.folderspan.service.file.DeviceFileClient
import com.folderspan.service.operation.HttpTransferRuntimeMemoryStatus
import com.folderspan.service.operation.HttpTransferRuntimeTuning
import com.folderspan.service.operation.HttpTransferStatus
import com.folderspan.service.operation.HttpTransferStatusHeaders
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.ProtoBufCodec
import com.folderspan.utils.SettingsUtils
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class FileRouteClientTest {
    @Test
    fun copyPathMapsMissingEndpointToExplicitUnsupportedError() = runBlocking {
        var captured = null as HttpRequestData?
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = ByteReadChannel(ByteArray(0)),
                status = HttpStatusCode.NotFound,
            )
        }
        val httpClient = HttpClient(engine) {
            defaultRequest {
                url("http://localhost")
            }
        }
        val client = FileRouteClient(httpClient, HttpRouteClientManager())

        val result = client.copyPath("/source.txt", "/target.txt")

        assertEquals("/api/files/copy", captured?.url?.encodedPath)
        assertTrue(result.exceptionOrNull() is DeviceCopyUnsupportedException)
    }

    @Test
    fun createFoldersUpdatesFileTransferStatusFromResponseHeaders() = runBlocking {
        val transferStatus = HttpTransferStatus(
            recommendedParallelRequests = 2,
            maxParallelRequests = 8,
            activeRequests = 7,
            busy = true,
            retryAfterMillis = 50L,
            sampledAtMillis = 42L,
        )
        var captured = null as HttpRequestData?
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = ByteReadChannel(
                    ProtoBufCodec.encode(
                        Result.success(listOf(Result.success(true).toSerializableResult())).toSerializableResult()
                    )
                ),
                status = HttpStatusCode.OK,
                headers = Headers.build {
                    append(HttpHeaders.ContentType, "application/protobuf")
                    HttpTransferStatusHeaders.encode(transferStatus).forEach { (name, value) ->
                        append(name, value)
                    }
                },
            )
        }
        val httpClient = HttpClient(engine) {
            defaultRequest {
                url("http://localhost")
            }
        }
        val client = FileRouteClient(httpClient, HttpRouteClientManager())

        val result = client.createFolders(listOf("/target"))

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
        assertEquals("/api/files/create-folder", captured?.url?.encodedPath)
        assertNotNull(captured)
        val latestStatus = client.transferStatus()
        assertEquals(2, latestStatus.recommendedParallelRequests)
        assertEquals(7, latestStatus.activeRequests)
        assertTrue(latestStatus.busy)
    }

    @Test
    fun createFoldersRetriesTransientTlsEofOnce() = runBlocking {
        var attempts = 0
        val engine = MockEngine {
            attempts++
            if (attempts == 1) {
                throw java.io.EOFException("Not enough data available")
            }
            respond(
                content = ByteReadChannel(
                    ProtoBufCodec.encode(
                        Result.success(listOf(Result.success(true).toSerializableResult())).toSerializableResult()
                    )
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/protobuf"),
            )
        }
        val httpClient = HttpClient(engine) {
            defaultRequest {
                url("http://localhost")
            }
        }
        val client = FileRouteClient(httpClient, HttpRouteClientManager())

        val result = client.createFolders(listOf("/target"))

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
        assertEquals(2, attempts)
    }

    @Test
    fun downloadBytesToFileWritesWholeRangeAsOneLocalBlock() = runBlocking {
        SettingsUtils.init(createSettings())
        withTempDir("file-route-client-download-") { tempDir ->
            val payload = ByteArray(HttpRouteClientManager.RAW_BYTE_READ_BUFFER_BYTES + 12_345) { index ->
                (index % 251).toByte()
            }
            val target = File(tempDir, "download.bin")
            val progressEvents = mutableListOf<Pair<Long, Int>>()
            var captured = null as HttpRequestData?
            val engine = MockEngine { request ->
                captured = request
                respond(
                    content = ByteReadChannel(payload),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, payload.size.toString())
                )
            }
            val httpClient = HttpClient(engine) {
                defaultRequest {
                    url("http://localhost")
                }
            }
            val client = FileRouteClient(httpClient, HttpRouteClientManager())

            val result = client.downloadBytesToFile(
                remotePath = "/remote.bin",
                startOffset = 0L,
                endOffset = payload.size.toLong(),
                localPath = target.absolutePath,
                fileSize = payload.size.toLong(),
                localOffset = 0L,
            ) { offset, bytesWritten ->
                progressEvents += offset to bytesWritten
            }

            assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
            assertTrue(result.getOrThrow())
            assertEquals("/api/files/read-bytes", captured?.url?.encodedPath)
            assertNotNull(captured)
            assertEquals(listOf(0L to payload.size), progressEvents)
            assertEquals(payload.size.toLong(), target.length())
            assertContentEquals(
                payload,
                FileUtils.readFileRange(
                    FileAccessPermission.Allowed,
                    target.absolutePath,
                    0L,
                    payload.size.toLong(),
                ).getOrThrow()
            )
        }
    }

    @Test
    fun streamFileRangeToFileUsesLongStreamEndpointAndReusableLocalBuffer() = runBlocking {
        SettingsUtils.init(createSettings())
        withTempDir("file-route-client-stream-") { tempDir ->
            val payload = ByteArray(HttpRouteClientManager.RAW_BYTE_READ_BUFFER_BYTES + 65_537) { index ->
                (index % 239).toByte()
            }
            val target = File(tempDir, "stream.bin")
            val localOffset = 4L
            val fileSize = payload.size.toLong() + localOffset
            val progressEvents = mutableListOf<Pair<Long, Int>>()
            var captured = null as HttpRequestData?
            val engine = MockEngine { request ->
                captured = request
                respond(
                    content = ByteReadChannel(payload),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, payload.size.toString())
                )
            }
            val httpClient = HttpClient(engine) {
                defaultRequest {
                    url("http://localhost")
                }
            }
            val client = FileRouteClient(httpClient, HttpRouteClientManager())

            val result = client.streamFileRangeToFile(
                remotePath = "/remote.bin",
                startOffset = 0L,
                endOffset = payload.size.toLong(),
                localPath = target.absolutePath,
                fileSize = fileSize,
                localOffset = localOffset,
            ) { offset, bytesWritten ->
                progressEvents += offset to bytesWritten
            }

            assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
            assertTrue(result.getOrThrow())
            assertEquals("/api/files/stream-file", captured?.url?.encodedPath)
            assertNotNull(captured)
            assertEquals(payload.size, progressEvents.sumOf { it.second })
            assertEquals(fileSize, target.length())
            assertContentEquals(
                payload,
                FileUtils.readFileRange(
                    FileAccessPermission.Allowed,
                    target.absolutePath,
                    localOffset,
                    localOffset + payload.size.toLong(),
                ).getOrThrow()
            )
        }
    }

    @Test
    fun downloadRangeToFileKeepsSmallRangesOnReadBytesRoute() = runBlocking {
        SettingsUtils.init(createSettings())
        withStableRuntimeMemory {
            withTempDir("file-route-client-range-small-") { tempDir ->
                val payload = ByteArray(1024) { index -> (index % 127).toByte() }
                val target = File(tempDir, "small.bin")
                var captured = null as HttpRequestData?
                val engine = MockEngine { request ->
                    captured = request
                    respond(
                        content = ByteReadChannel(payload),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength, payload.size.toString())
                    )
                }
                val httpClient = HttpClient(engine) {
                    defaultRequest {
                        url("http://localhost")
                    }
                }
                val client = FileRouteClient(httpClient, HttpRouteClientManager())

                val result = client.downloadRangeToFile(
                    remotePath = "/remote-small.bin",
                    startOffset = 0L,
                    endOffset = payload.size.toLong(),
                    localPath = target.absolutePath,
                    fileSize = payload.size.toLong(),
                ) { _, _ -> }

                assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
                assertEquals("/api/files/read-bytes", captured?.url?.encodedPath)
            }
        }
    }

    @Test
    fun downloadRangeToFilePrefersStreamFileForLargeRanges() = runBlocking {
        SettingsUtils.init(createSettings())
        withStableRuntimeMemory {
            withTempDir("file-route-client-range-large-") { tempDir ->
                val payload = ByteArray(4 * 1024 * 1024 + 1) { index -> (index % 193).toByte() }
                val target = File(tempDir, "large.bin")
                var captured = null as HttpRequestData?
                val engine = MockEngine { request ->
                    captured = request
                    respond(
                        content = ByteReadChannel(payload),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength, payload.size.toString())
                    )
                }
                val httpClient = HttpClient(engine) {
                    defaultRequest {
                        url("http://localhost")
                    }
                }
                val client = FileRouteClient(httpClient, HttpRouteClientManager())

                val result = client.downloadRangeToFile(
                    remotePath = "/remote-large.bin",
                    startOffset = 0L,
                    endOffset = payload.size.toLong(),
                    localPath = target.absolutePath,
                    fileSize = payload.size.toLong(),
                ) { _, _ -> }

                assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
                assertEquals("/api/files/stream-file", captured?.url?.encodedPath)
            }
        }
    }

    @Test
    fun downloadRangeToFileKeepsDeviceToLocalRangesOnStreamFileAfterPlannerGrowth() = runBlocking {
        SettingsUtils.init(createSettings())
        withStableRuntimeMemory {
            withTempDir("file-route-client-range-grown-") { tempDir ->
                val payload = ByteArray(4 * 1024 * 1024 + 1) { index -> (index % 197).toByte() }
                val target = File(tempDir, "grown.bin")
                val capturedPaths = mutableListOf<String>()
                val engine = MockEngine { request ->
                    capturedPaths += request.url.encodedPath
                    respond(
                        content = ByteReadChannel(payload),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength, payload.size.toString())
                    )
                }
                val httpClient = HttpClient(engine) {
                    defaultRequest {
                        url("http://localhost")
                    }
                }
                val client = FileRouteClient(httpClient, HttpRouteClientManager())
                repeat(4) {
                    client.adaptiveTransferPlan()
                    client.downloadRangeToFile(
                        remotePath = "/remote-grown.bin",
                        startOffset = 0L,
                        endOffset = payload.size.toLong(),
                        localPath = target.absolutePath,
                        fileSize = payload.size.toLong(),
                    ) { _, _ -> }
                }

                val result = client.downloadRangeToFile(
                    remotePath = "/remote-grown.bin",
                    startOffset = 0L,
                    endOffset = payload.size.toLong(),
                    localPath = target.absolutePath,
                    fileSize = payload.size.toLong(),
                ) { _, _ -> }

                assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
                assertTrue(capturedPaths.isNotEmpty())
                assertTrue(
                    capturedPaths.all { path -> path == "/api/files/stream-file" },
                    "Device->Local range downloads must stay on stream-file after planner growth: $capturedPaths"
                )
            }
        }
    }

    @Test
    fun deviceFileClientDownloadRangeToFilePrefersStreamFileForLargeRanges() = runBlocking {
        SettingsUtils.init(createSettings())
        withStableRuntimeMemory {
            withTempDir("file-route-client-interface-range-large-") { tempDir ->
                val payload = ByteArray(4 * 1024 * 1024 + 1) { index -> (index % 181).toByte() }
                val target = File(tempDir, "large-interface.bin")
                val progressEvents = mutableListOf<Pair<Long, Int>>()
                var captured = null as HttpRequestData?
                val engine = MockEngine { request ->
                    captured = request
                    respond(
                        content = ByteReadChannel(payload),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength, payload.size.toString())
                    )
                }
                val httpClient = HttpClient(engine) {
                    defaultRequest {
                        url("http://localhost")
                    }
                }
                val client: DeviceFileClient = FileRouteClient(httpClient, HttpRouteClientManager())

                val result = client.downloadRangeToFile(
                    remotePath = "/remote-large.bin",
                    startOffset = 0L,
                    endOffset = payload.size.toLong(),
                    localPath = target.absolutePath,
                    fileSize = payload.size.toLong(),
                ) { offset, bytesWritten ->
                    progressEvents += offset to bytesWritten
                }

                assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
                assertEquals("/api/files/stream-file", captured?.url?.encodedPath)
                assertEquals(payload.size, progressEvents.sumOf { it.second })
                assertContentEquals(
                    payload,
                    FileUtils.readFileRange(
                        FileAccessPermission.Allowed,
                        target.absolutePath,
                        0L,
                        payload.size.toLong(),
                    ).getOrThrow()
                )
            }
        }
    }

    @Test
    fun writeBytesKeepsConnectionReusableForByteRoute() = runBlocking {
        val payload = byteArrayOf(1, 2, 3, 4)
        var captured = null as HttpRequestData?
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = ByteReadChannel(ProtoBufCodec.encode(Result.success(true).toSerializableResult())),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/protobuf")
            )
        }
        val httpClient = HttpClient(engine) {
            defaultRequest {
                url("http://localhost")
            }
        }
        val client = FileRouteClient(httpClient, HttpRouteClientManager())

        val result = client.writeBytes(
            fileSize = payload.size.toLong(),
            blockIndex = 0L,
            blockLength = payload.size.toLong(),
            path = "/remote.bin",
            byteArray = payload,
            startOffset = 0L,
        )

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
        val capturedRequest = assertNotNull(captured)
        assertEquals("/api/files/write-bytes", capturedRequest.url.encodedPath)
        assertNull(capturedRequest.headers[HttpHeaders.Connection])
    }

    @Test
    fun streamUploadRangeFromLocalUsesStreamUploadRouteAndStreamingBody() = runBlocking {
        SettingsUtils.init(createSettings())
        withTempDir("file-route-client-stream-upload-") { tempDir ->
            val payload = ByteArray(1024 * 1024 + 17) { index -> (index % 241).toByte() }
            val source = File(tempDir, "source.bin")
            source.writeBytes(payload)
            val progressEvents = mutableListOf<Pair<Long, Int>>()
            var captured = null as HttpRequestData?
            val engine = MockEngine { request ->
                captured = request
                respond(
                    content = ByteReadChannel(ProtoBufCodec.encode(Result.success(true).toSerializableResult())),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/protobuf")
                )
            }
            val httpClient = HttpClient(engine) {
                defaultRequest {
                    url("http://localhost")
                }
            }
            val client = FileRouteClient(httpClient, HttpRouteClientManager())

            val result = client.streamUploadRangeFromLocal(
                sourcePath = source.absolutePath,
                destinationPath = "/remote/target.bin",
                fileSize = payload.size.toLong(),
                sourceOffset = 0L,
                destinationOffset = 0L,
                expectedBytes = payload.size.toLong(),
            ) { offset, bytesWritten ->
                progressEvents += offset to bytesWritten
            }

            assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
            val capturedRequest = assertNotNull(captured)
            assertEquals("/api/files/stream-upload", capturedRequest.url.encodedPath)
            val body = assertIs<OutgoingContent.WriteChannelContent>(capturedRequest.body)
            assertEquals(payload.size.toLong(), body.contentLength)
        }
    }

    private inline fun withTempDir(prefix: String, block: (File) -> Unit) {
        val tempDir = Files.createTempDirectory(prefix).toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private inline fun withStableRuntimeMemory(block: () -> Unit) {
        try {
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(
                HttpTransferRuntimeMemoryStatus(
                    availableHeapBytes = 512L * 1024L * 1024L,
                    maxHeapBytes = 1024L * 1024L * 1024L,
                )
            )
            block()
        } finally {
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(null)
        }
    }
}

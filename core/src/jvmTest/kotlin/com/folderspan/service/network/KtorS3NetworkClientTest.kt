package com.folderspan.service.network

import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.NetworkProtocol
import com.folderspan.data.main.network.S3DriveExtras
import com.folderspan.exception.NetworkUnsupportedException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KtorS3NetworkClientTest {

    @Test
    fun incompleteOrCyclicDirectoryPagesFailInsteadOfReturningAnEmptyOrPartialView() = runBlocking {
        val invalid = listOf(
            "<ListBucketResult></ListBucketResult>",
            "<ListBucketResult><IsTruncated>true</IsTruncated></ListBucketResult>",
            "<ListBucketResult><IsTruncated>false</IsTruncated>",
            "<ListBucketResult><IsTruncated>false</IsTruncated><NextContinuationToken>contradiction</NextContinuationToken></ListBucketResult>",
            "<ListBucketResult><IsTruncated>false</IsTruncated><Contents><Key>bad</Key><Size>invalid</Size></Contents></ListBucketResult>",
        )
        for (body in invalid) HttpClient(MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml"))
        }).use { http ->
            assertTrue(KtorS3NetworkClient(buildNetwork().connectionConfig(), http).list("/").isFailure)
        }
        var requests = 0
        HttpClient(MockEngine {
            requests++
            respond("<ListBucketResult><IsTruncated>true</IsTruncated><NextContinuationToken>same</NextContinuationToken></ListBucketResult>",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml"))
        }).use { http ->
            assertTrue(KtorS3NetworkClient(buildNetwork().connectionConfig(), http).list("/").isFailure)
            assertEquals(2, requests)
        }
        requests = 0
        HttpClient(MockEngine {
            requests++
            if (requests == 1) respond("<ListBucketResult><IsTruncated>true</IsTruncated><NextContinuationToken>next</NextContinuationToken><Contents><Key>first</Key><Size>1</Size></Contents></ListBucketResult>",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml"))
            else respond("unavailable", HttpStatusCode.ServiceUnavailable)
        }).use { http ->
            assertTrue(KtorS3NetworkClient(buildNetwork().connectionConfig(), http).list("/").isFailure)
            assertEquals(2, requests)
        }
    }


    @Test fun emptyDirectoryDeletionRemovesOnlyMarkerAndRetainsConcurrentChild(): Unit = runBlocking {
        for (alreadyNonEmpty in listOf(false, true)) {
            val requests = mutableListOf<HttpRequestData>()
            val objects = mutableSetOf("folder/").apply { if (alreadyNonEmpty) add("folder/new") }
            HttpClient(MockEngine { request ->
                requests += request
                when (request.method) {
                    HttpMethod.Get -> respond("<ListBucketResult><IsTruncated>false</IsTruncated>" + objects.joinToString("") { "<Contents><Key>$it</Key><Size>4</Size></Contents>" } + "</ListBucketResult>",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml"))
                    HttpMethod.Delete -> {
                        objects.add("folder/new")
                        objects.remove(request.url.encodedPath.removePrefix("/demo-bucket/"))
                        respond("", HttpStatusCode.NoContent)
                    }
                    else -> error("Unexpected request ${request.method}")
                }
            }).use { http ->
                val client = KtorS3NetworkClient(buildNetwork().connectionConfig(), http)
                assertEquals(!alreadyNonEmpty, client.deleteEmptyDirectory(StoragePath("/folder")).isSuccess)
                assertTrue("folder/new" in objects)
                assertEquals(if (alreadyNonEmpty) emptyList() else listOf("/demo-bucket/folder/"),
                    requests.filter { it.method == HttpMethod.Delete }.map { it.url.encodedPath })
                assertTrue(client.deleteEmptyDirectory("/").isFailure)
                assertTrue(client.deleteEmptyDirectory("/folder/../other").isFailure)
            }
        }
    }

    @Test fun failedMoveCopyDoesNotDeleteSource(): Unit = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        HttpClient(MockEngine { request ->
            requests += request
            when (request.method) {
                HttpMethod.Get -> respond("<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml"))
                HttpMethod.Head -> respond("", HttpStatusCode.OK)
                HttpMethod.Put -> respond("<Error><Code>Denied</Code></Error>", HttpStatusCode.Forbidden)
                else -> error("Unexpected destructive request ${request.method}")
            }
        }).use { http ->
            val client = KtorS3NetworkClient(buildNetwork().connectionConfig(), http)
            assertTrue(client.move(StoragePath("/source.txt"), StoragePath("/archive/new.txt")).isFailure)
            assertTrue(requests.any { it.method == HttpMethod.Put })
            assertFalse(requests.any { it.method == HttpMethod.Delete })
        }
    }
    @Test
    fun listedObjectsAndVirtualDirectoriesHaveKnownNonLinkMetadata() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """
                    <ListBucketResult><IsTruncated>false</IsTruncated>
                      <CommonPrefixes><Prefix>nested/</Prefix></CommonPrefixes>
                      <Contents><Key>file.txt</Key><Size>7</Size></Contents>
                    </ListBucketResult>
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/xml"),
            )
        }
        HttpClient(engine).use { httpClient ->
            val client = KtorS3NetworkClient(buildNetwork().connectionConfig(), httpClient)
            val entries = client.list("/").getOrThrow()

            assertEquals(setOf("/nested", "/file.txt"), entries.map { it.path }.toSet())
            assertTrue(entries.single { it.path == "/nested" }.isDirectory)
            assertTrue(entries.all { it.isSymbolicLinkKnown && !it.isSymbolicLink })
        }
    }

    @Test
    fun copyFileUsesS3ServerSideCopyWithoutDownloadUploadOrDeleteFallback() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel(COPY_OBJECT_SUCCESS_RESPONSE),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/xml"),
            )
        }
        val client = KtorS3NetworkClient(buildNetwork().connectionConfig(), HttpClient(engine))

        val result = client.copyFile("/source/file.txt", "/target/file.txt")

        assertTrue(result.isSuccess)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/demo-bucket/target/file.txt", request.url.encodedPath)
        assertEquals("demo-bucket/source/file.txt", request.headers["x-amz-copy-source"])
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            request.headers["x-amz-content-sha256"],
        )
    }

    @Test
    fun copyFileTreatsEmbeddedS3ErrorInSuccessfulHttpResponseAsFailure() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel(
                    """<Error><Code>AccessDenied</Code><Message>copy denied</Message></Error>"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/xml"),
            )
        }
        val client = KtorS3NetworkClient(buildNetwork().connectionConfig(), HttpClient(engine))

        val result = client.copyFile("/source/file.txt", "/target/file.txt")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("AccessDenied"))
        assertEquals(1, requests.size)
    }

    @Test
    fun copyFileFailsWhenSuccessfulHttpResponseBodyCannotBeRead() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val brokenBody = ByteChannel().apply {
                cancel(IllegalStateException("broken copy response"))
            }
            respond(
                content = brokenBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/xml"),
            )
        }
        val client = KtorS3NetworkClient(buildNetwork().connectionConfig(), HttpClient(engine))

        val result = client.copyFile("/source/file.txt", "/target/file.txt")

        assertTrue(result.isFailure)
        assertEquals(1, requests.size)
    }

    @Test
    fun copyFileRejectsEmptySuccessfulHttpResponse() = runBlocking {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.OK,
                headers = headersOf(),
            )
        }
        val client = KtorS3NetworkClient(buildNetwork().connectionConfig(), HttpClient(engine))

        val result = client.copyFile("/source/file.txt", "/target/file.txt")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("CopyObjectResult"))
    }

    @Test
    fun copyFileReportsUnsupportedWhenServerDoesNotImplementCopyObject() = runBlocking {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.NotImplemented,
                headers = headersOf(),
            )
        }
        val client = KtorS3NetworkClient(buildNetwork().connectionConfig(), HttpClient(engine))

        val result = client.copyFile("/source/file.txt", "/target/file.txt")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NetworkUnsupportedException)
    }

    @Test
    fun createFolderPreservesTrailingSlashInRequestPath() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.NotFound,
                    headers = headersOf()
                )

                HttpMethod.Put -> respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.OK,
                    headers = headersOf()
                )

                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = KtorS3NetworkClient(buildNetwork().connectionConfig(), HttpClient(engine))

        val result = client.createFolder("/parent/new-folder")

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                HttpMethod.Head to "/demo-bucket/parent/",
                HttpMethod.Put to "/demo-bucket/parent/",
                HttpMethod.Head to "/demo-bucket/parent/new-folder/",
                HttpMethod.Put to "/demo-bucket/parent/new-folder/",
            ),
            requests.map { it.method to it.url.encodedPath }
        )
    }

    @Test
    fun uploadCreatesParentDirectoryMarkersBeforeUploadingObject() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.NotFound,
                    headers = headersOf()
                )

                HttpMethod.Put -> respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.OK,
                    headers = headersOf()
                )

                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = KtorS3NetworkClient(buildNetwork().connectionConfig(), HttpClient(engine))
        val localFile = createTempFile(prefix = "s3-parent-", suffix = ".txt").toFile()
        localFile.writeText("hello")

        try {
            val result = client.upload(
                localPath = localFile.absolutePath,
                remotePath = "/parent/new-folder/file.txt",
                size = localFile.length(),
                onProgress = { _, _ -> }
            )

            assertTrue(result.isSuccess)
            assertEquals(
                listOf(
                    HttpMethod.Head to "/demo-bucket/parent/new-folder/file.txt",
                    HttpMethod.Head to "/demo-bucket/parent/",
                    HttpMethod.Put to "/demo-bucket/parent/",
                    HttpMethod.Head to "/demo-bucket/parent/new-folder/",
                    HttpMethod.Put to "/demo-bucket/parent/new-folder/",
                    HttpMethod.Put to "/demo-bucket/parent/new-folder/file.txt",
                ),
                requests.map { it.method to it.url.encodedPath }
            )
        } finally {
            localFile.delete()
        }
    }

    @Test
    fun createFolderSkipsExistingParentMarker() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when (request.method) {
                HttpMethod.Head -> {
                    val status = if (request.url.encodedPath == "/demo-bucket/parent/") {
                        HttpStatusCode.OK
                    } else {
                        HttpStatusCode.NotFound
                    }
                    respond(
                        content = ByteReadChannel.Empty,
                        status = status,
                        headers = headersOf()
                    )
                }

                HttpMethod.Put -> respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.OK,
                    headers = headersOf()
                )

                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = KtorS3NetworkClient(buildNetwork().connectionConfig(), HttpClient(engine))

        val result = client.createFolder("/parent/new-folder")

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                HttpMethod.Head to "/demo-bucket/parent/",
                HttpMethod.Head to "/demo-bucket/parent/new-folder/",
                HttpMethod.Put to "/demo-bucket/parent/new-folder/",
            ),
            requests.map { it.method to it.url.encodedPath }
        )
    }

    @Test
    fun createFolderPreservesTrailingSlashInRequestPathForLeafMarker() = runBlocking {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Put && request.url.encodedPath == "/demo-bucket/parent/new-folder/") {
                captured = request
            }
            respond(
                content = ByteReadChannel.Empty,
                status = if (request.method == HttpMethod.Head) HttpStatusCode.NotFound else HttpStatusCode.OK,
                headers = headersOf()
            )
        }
        val client = KtorS3NetworkClient(buildNetwork().connectionConfig(), HttpClient(engine))

        val result = client.createFolder("/parent/new-folder")

        assertTrue(result.isSuccess)
        val request = assertNotNull(captured)
        assertEquals("/demo-bucket/parent/new-folder/", request.url.encodedPath)
    }

    @Test
    fun renameEmptyDirectoryPreservesMarkerObjectSlash() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.NotFound,
                    headers = headersOf()
                )

                HttpMethod.Get -> {
                    assertEquals("2", request.url.parameters["list-type"])
                    assertEquals("source/", request.url.parameters["prefix"])
                    respond(
                        content = ByteReadChannel(LIST_SOURCE_DIRECTORY_RESPONSE),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/xml")
                    )
                }

                HttpMethod.Put -> {
                    assertEquals("/demo-bucket/target/", request.url.encodedPath)
                    assertEquals("demo-bucket/source/", request.headers["x-amz-copy-source"])
                    assertEquals(listOf("demo-bucket/source/"), request.headers.getAll("x-amz-copy-source"))
                    assertEquals(
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                        request.headers["x-amz-content-sha256"]
                    )
                    respond(
                        content = ByteReadChannel(COPY_OBJECT_SUCCESS_RESPONSE),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/xml"),
                    )
                }

                HttpMethod.Delete -> {
                    assertEquals("/demo-bucket/source/", request.url.encodedPath)
                    respond(
                        content = ByteReadChannel.Empty,
                        status = HttpStatusCode.NoContent,
                        headers = headersOf()
                    )
                }

                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = KtorS3NetworkClient(buildNetwork().connectionConfig(), HttpClient(engine))

        val result = client.rename("/source", "/target")

        assertTrue(result.isSuccess)
        assertTrue(requests.any { it.method == HttpMethod.Get && it.url.parameters["prefix"] == "source/" })
        assertTrue(requests.any { it.method == HttpMethod.Put && it.url.encodedPath == "/demo-bucket/target/" })
        assertTrue(requests.any { it.method == HttpMethod.Delete && it.url.encodedPath == "/demo-bucket/source/" })
    }

    @Test
    fun uploadEncodesPlusAndAtInObjectKey() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.NotFound,
                    headers = headersOf()
                )

                HttpMethod.Put -> respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.OK,
                    headers = headersOf()
                )

                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = KtorS3NetworkClient(buildNetwork().connectionConfig(), HttpClient(engine))
        val localFile = createTempFile(prefix = "s3-upload-", suffix = ".txt").toFile()
        localFile.writeText("hello")

        try {
            val remotePath =
                "/node_modules/.pnpm/@inquirer+select@4.4.2/node_modules/@inquirer/core/file.d.ts"

            val result = client.upload(
                localFile.absolutePath,
                remotePath,
                localFile.length(),
                onProgress = { _, _ -> }
            )

            assertTrue(result.isSuccess)
            val expectedPath =
                "/demo-bucket/node_modules/.pnpm/%40inquirer%2Bselect%404.4.2/node_modules/%40inquirer/core/file.d.ts"
            val headRequest = requests.firstOrNull { it.method == HttpMethod.Head && !it.url.encodedPath.endsWith('/') }
            val putRequest = requests.lastOrNull { it.method == HttpMethod.Put && !it.url.encodedPath.endsWith('/') }
            assertNotNull(headRequest)
            assertNotNull(putRequest)
            assertEquals(expectedPath, headRequest.url.encodedPath)
            assertEquals(expectedPath, putRequest.url.encodedPath)
        } finally {
            localFile.delete()
        }
    }

    @Test
    fun resumedDownloadRejectsMismatchedContentRangeWithoutChangingTheExistingFile() = runBlocking {
        val destination = createTempFile(prefix = "s3-range-", suffix = ".bin").toFile()
        val original = byteArrayOf(1, 2, 3)
        destination.writeBytes(original)
        var requests = 0
        val client = KtorS3NetworkClient(buildNetwork().connectionConfig(), HttpClient(MockEngine { request ->
            requests++
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("bytes=3-", request.headers[HttpHeaders.Range])
            respond(
                content = ByteReadChannel(byteArrayOf(4, 5, 6)),
                status = HttpStatusCode.PartialContent,
                headers = headersOf(HttpHeaders.ContentRange, "bytes 2-4/6"),
            )
        }))
        try {
            assertTrue(client.download("/source.bin", destination.absolutePath, 6L) { _, _ -> }.isFailure)
            assertEquals(1, requests)
            assertContentEquals(original, destination.readBytes())
        } finally {
            client.close()
            destination.delete()
        }
    }

    @Test
    fun multipartRetryQueriesServerAndSkipsAnAlreadyConfirmedPart() = runBlocking {
        val directory = createTempDirectory(prefix = "s3-multipart-retry-").toFile()
        val source = directory.resolve("source.bin")
        val partSize = 8L * 1024 * 1024
        RandomAccessFile(source, "rw").use { it.setLength(2 * partSize) }
        val uploadedParts = mutableListOf<Int>()
        var creates = 0
        var queries = 0
        var completes = 0
        var retrying = false
        fun newClient() = KtorS3NetworkClient(buildNetwork().connectionConfig(), HttpClient(MockEngine { request ->
            val uploadId = request.url.parameters["uploadId"]
            when {
                request.method == HttpMethod.Head -> respond(
                    content = ByteReadChannel.Empty, status = HttpStatusCode.NotFound,
                )
                request.method == HttpMethod.Post && "uploads" in request.url.parameters.names() -> {
                    creates++
                    respond("<InitiateMultipartUploadResult><UploadId>resume-upload</UploadId></InitiateMultipartUploadResult>")
                }
                request.method == HttpMethod.Get && uploadId == "resume-upload" -> {
                    assertTrue(retrying)
                    queries++
                    respond("""
                        <ListPartsResult><IsTruncated>false</IsTruncated><Part>
                        <PartNumber>1</PartNumber><ETag>&quot;first-part&quot;</ETag><Size>$partSize</Size>
                        </Part></ListPartsResult>
                    """.trimIndent())
                }
                request.method == HttpMethod.Put && uploadId == "resume-upload" -> {
                    val part = checkNotNull(request.url.parameters["partNumber"]).toInt()
                    uploadedParts += part
                    if (part == 2 && !retrying) respond("interrupted", HttpStatusCode.ServiceUnavailable)
                    else respond("", HttpStatusCode.OK,
                        headersOf(HttpHeaders.ETag, if (part == 1) "\"first-part\"" else "\"second-part\""))
                }
                request.method == HttpMethod.Post && uploadId == "resume-upload" -> {
                    assertTrue(retrying)
                    completes++
                    respond("<CompleteMultipartUploadResult><ETag>&quot;complete&quot;</ETag></CompleteMultipartUploadResult>")
                }
                else -> error("Unexpected S3 request: ${request.method} ${request.url}")
            }
        }))
        try {
            val firstClient = newClient()
            try {
                assertTrue(firstClient.upload(source.absolutePath, "/target.bin", source.length()) { _, _ -> }.isFailure)
            } finally { firstClient.close() }
            assertEquals(listOf(1, 2), uploadedParts)
            assertEquals(1, directory.listFiles().orEmpty().count { it.name.endsWith(".state") })
            retrying = true
            val progress = mutableListOf<Long>()
            val recoveredClient = newClient()
            try {
                assertTrue(recoveredClient.upload(source.absolutePath, "/target.bin", source.length()) { done, _ ->
                    progress += done
                }.getOrThrow())
            } finally { recoveredClient.close() }
            assertEquals(listOf(1, 2, 2), uploadedParts)
            assertEquals(1, creates)
            assertEquals(1, queries)
            assertEquals(1, completes)
            assertEquals(partSize, progress.first())
            assertEquals(source.length(), progress.last())
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".state") })
            assertEquals(2 * partSize, source.length())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun buildNetwork(): Network {
        return Network(
            name = "S3",
            pathSeparator = "/",
            protocol = NetworkProtocol.S3.name,
            host = "https://example.com",
            username = "access-key",
            password = "secret-key",
            extras = NetworkDriveExtras(
                s3 = S3DriveExtras(
                    bucket = "demo-bucket",
                    region = "us-east-1",
                    endpoint = "https://example.com",
                    forcePathStyle = true
                )
            )
        )
    }

    private companion object {
        private const val COPY_OBJECT_SUCCESS_RESPONSE = """
<CopyObjectResult>
  <ETag>&quot;copy-etag&quot;</ETag>
  <LastModified>2026-07-18T00:00:00.000Z</LastModified>
</CopyObjectResult>
"""

        private const val LIST_SOURCE_DIRECTORY_RESPONSE = """
<ListBucketResult><IsTruncated>false</IsTruncated>
  <Contents>
    <Key>source/</Key>
    <LastModified>2026-03-18T00:00:00.000Z</LastModified>
    <Size>0</Size>
  </Contents>
</ListBucketResult>
"""
    }
}

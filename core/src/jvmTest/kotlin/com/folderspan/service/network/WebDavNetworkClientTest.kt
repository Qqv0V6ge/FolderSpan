package com.folderspan.service.network

import com.folderspan.data.main.network.*
import com.folderspan.exception.NetworkUnsupportedException

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebDavNetworkClientTest {
    @Test
    fun copyFileUsesWebDavCopyWithoutDownloadOrUploadFallback() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Created,
                headers = headersOf()
            )
        }
        val client = WebDavNetworkClient(
            buildNetwork(WebDavDriveExtras()),
            HttpClient(engine),
        )

        val result = client.copyFile("/source/file.txt", "/target/file.txt")

        assertTrue(result.isSuccess)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals(HttpMethod("COPY"), request.method)
        assertEquals("/webdav/source/file.txt", request.url.encodedPath)
        assertEquals(
            "https://example.com/webdav/target/file.txt",
            request.headers[HttpHeaders.Destination],
        )
        assertEquals("T", request.headers["Overwrite"])
    }

    @Test
    fun copyFileReportsUnsupportedWhenServerRejectsCopyMethod() = runBlocking {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.MethodNotAllowed,
                headers = headersOf(),
            )
        }
        val client = WebDavNetworkClient(
            buildNetwork(WebDavDriveExtras()),
            HttpClient(engine),
        )

        val result = client.copyFile("/source/file.txt", "/target/file.txt")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NetworkUnsupportedException)
    }

    @Test
    fun listAddsTokenAndCustomHeadersAndParsesEntries() = runBlocking {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = ByteReadChannel(PROPFIND_RESPONSE),
                status = HttpStatusCode.MultiStatus,
                headers = headersOf(HttpHeaders.ContentType, "application/xml")
            )
        }
        val httpClient = HttpClient(engine)
        val network = buildNetwork(
            WebDavDriveExtras(
                authType = WebDavAuthType.Token,
                token = "secret-token",
                tokenHeaderName = "Authorization",
                tokenPrefix = "Bearer",
                headers = mapOf(
                    "Authorization" to "Override",
                    "X-Custom" to "Value"
                )
            )
        )
        val client = WebDavNetworkClient(network, httpClient)

        val result = client.list("/")
        assertTrue(result.isSuccess)
        val entries = result.getOrDefault(emptyList())
        assertEquals(1, entries.size)
        assertEquals("file.txt", entries.first().name)
        assertEquals("/file.txt", entries.first().path)
        assertFalse(entries.first().isSymbolicLinkKnown)

        val request = captured
        assertNotNull(request)
        assertEquals("Override", request.headers[HttpHeaders.Authorization])
        assertEquals("Value", request.headers["X-Custom"])
    }

    @Test
    fun customAuthHeadersStayOnTheOriginalHostWhenRedirectsAreDisabled() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://other.example/webdav/file.txt"),
            )
        }
        val client = WebDavNetworkClient(
            buildNetwork(
                WebDavDriveExtras(
                    authType = WebDavAuthType.Token,
                    token = "redirect-token",
                    tokenHeaderName = "X-Auth-Token",
                    tokenPrefix = "",
                    headers = mapOf("X-Custom-Auth" to "session-token"),
                )
            ),
            HttpClient(engine) {
                followRedirects = false
                expectSuccess = false
            },
        )

        val result = client.list("/")

        assertTrue(result.isFailure)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("example.com", request.url.host)
        assertEquals("redirect-token", request.headers["X-Auth-Token"])
        assertEquals("session-token", request.headers["X-Custom-Auth"])
    }

    @Test
    fun listSkipsUnsafeAndUnrelatedHrefEntries() = runBlocking {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(UNSAFE_PROPFIND_RESPONSE),
                status = HttpStatusCode.MultiStatus,
                headers = headersOf(HttpHeaders.ContentType, "application/xml"),
            )
        }
        val client = WebDavNetworkClient(
            buildNetwork(WebDavDriveExtras()),
            HttpClient(engine),
        )

        val entries = client.list("/").getOrThrow()

        assertEquals(1, entries.size)
        assertEquals("file.txt", entries.single().name)
        assertEquals("/file.txt", entries.single().path)
        assertFalse(entries.any { entry -> entry.path.contains("..") })
        assertFalse(entries.any { entry -> entry.path.contains("secret") })
        assertFalse(entries.any { entry -> entry.path.contains("other") })
    }

    @Test
    fun listSkipsEncodedUnsafeHrefEntriesAndKeepsFollowingValidEntry() = runBlocking {
        val hrefs = listOf(
            "/webdav/%2e%2e/secret.txt",
            "/webdav/folder%2F..%2Fsecret.txt",
            "/webdav/folder%5C..%5Csecret.txt",
            "/webdav/empty//file.txt",
            "/webdav/null%00.txt",
            "/webdav/safe%20file.txt",
        )
        val xml = """
            <d:multistatus xmlns:d="DAV:">
                ${hrefs.joinToString("\n") { href -> "<d:response><d:href>$href</d:href></d:response>" }}
            </d:multistatus>
        """.trimIndent()
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(xml),
                status = HttpStatusCode.MultiStatus,
                headers = headersOf(HttpHeaders.ContentType, "application/xml"),
            )
        }
        val client = WebDavNetworkClient(
            buildNetwork(WebDavDriveExtras()),
            HttpClient(engine),
        )

        val entries = client.list("/").getOrThrow()

        assertEquals(listOf("safe file.txt"), entries.map { it.name })
        assertEquals(listOf("/safe file.txt"), entries.map { it.path })
    }

    @Test
    fun writeOperationsRejectUnsafePathsWithoutSendingRequests() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Created,
                headers = headersOf(),
            )
        }
        val client = WebDavNetworkClient(
            buildNetwork(WebDavDriveExtras()),
            HttpClient(engine),
        )
        val unsafePath = "/../secret.txt"
        val operations: List<suspend () -> Result<Boolean>> = listOf(
            { client.copyFile("/file.txt", unsafePath) },
            { client.rename("/file.txt", unsafePath) },
            { client.delete(unsafePath, isDirectory = false) },
            { client.createFolder(unsafePath) },
            {
                client.uploadFromSource(
                    remotePath = unsafePath,
                    size = 0L,
                    onProgress = { _, _ -> },
                    readChunk = { null },
                )
            },
        )

        for (operation in operations) {
            val failure = runCatching { operation().getOrThrow() }.exceptionOrNull()
            assertIs<IllegalArgumentException>(failure)
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun uploadFromSourcePutsStreamingBodyFromChunks() = runBlocking {
        var capturedMethod: HttpMethod? = null
        var capturedPath: String? = null
        var capturedLength: Long? = null
        var capturedBody: ByteArray? = null
        val engine = MockEngine { request ->
            capturedMethod = request.method
            capturedPath = request.url.encodedPath
            val body = assertIs<OutgoingContent.WriteChannelContent>(request.body)
            capturedLength = body.contentLength
            capturedBody = body.collectBytes()
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Created,
                headers = headersOf(),
            )
        }
        val client = WebDavNetworkClient(
            buildNetwork(WebDavDriveExtras()),
            HttpClient(engine),
        )
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val chunks = listOf(
            payload.copyOfRange(0, 3),
            payload.copyOfRange(3, 7),
        )
        var index = 0

        val result = client.uploadFromSource(
            remotePath = "/file.bin",
            size = payload.size.toLong(),
            onProgress = { _, _ -> },
        ) {
            chunks.getOrNull(index++)
        }

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
        assertEquals(HttpMethod.Put, capturedMethod)
        assertEquals("/webdav/file.bin", capturedPath)
        assertEquals(payload.size.toLong(), capturedLength)
        assertContentEquals(payload, capturedBody)
    }

    @Test
    fun uploadFromSourceFailsWhenSizeIsUnknown() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Created,
                headers = headersOf(),
            )
        }
        val client = WebDavNetworkClient(
            buildNetwork(WebDavDriveExtras()),
            HttpClient(engine),
        )

        val result = client.uploadFromSource(
            remotePath = "/file.bin",
            size = -1L,
            onProgress = { _, _ -> },
            readChunk = { byteArrayOf(1) },
        )

        assertTrue(result.isFailure)
        assertEquals(AppStrings.network_stream_upload_size_required, result.exceptionOrNull()?.message)
        assertTrue(requests.isEmpty())
    }

    private suspend fun OutgoingContent.WriteChannelContent.collectBytes(): ByteArray = coroutineScope {
        val channel = ByteChannel(autoFlush = true)
        val reader = async { channel.toByteArray() }
        writeTo(channel)
        channel.flushAndClose()
        reader.await()
    }

    private fun buildNetwork(webdav: WebDavDriveExtras): Network {
        return Network(
            name = "WebDav",
            pathSeparator = "/",
            protocol = NetworkProtocol.WebDav.name,
            host = "https://example.com/webdav",
            username = "user",
            password = "pass",
            extras = NetworkDriveExtras(webdav = webdav)
        )
    }

    private companion object {
        private const val PROPFIND_RESPONSE = """
<?xml version=\"1.0\" encoding=\"utf-8\"?>
<d:multistatus xmlns:d=\"DAV:\">
  <d:response>
    <d:href>/webdav/</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype><d:collection/></d:resourcetype>
      </d:prop>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/webdav/file.txt</d:href>
    <d:propstat>
      <d:prop>
        <d:displayname>file.txt</d:displayname>
        <d:getcontentlength>12</d:getcontentlength>
      </d:prop>
    </d:propstat>
  </d:response>
</d:multistatus>
"""

        private const val UNSAFE_PROPFIND_RESPONSE = """
<?xml version=\"1.0\" encoding=\"utf-8\"?>
<d:multistatus xmlns:d=\"DAV:\">
  <d:response>
    <d:href>/webdav/</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype><d:collection/></d:resourcetype>
      </d:prop>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/webdav/file.txt</d:href>
    <d:propstat>
      <d:prop>
        <d:displayname>file.txt</d:displayname>
        <d:getcontentlength>12</d:getcontentlength>
      </d:prop>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/webdav/../secret.txt</d:href>
    <d:propstat>
      <d:prop>
        <d:displayname>secret.txt</d:displayname>
        <d:getcontentlength>4</d:getcontentlength>
      </d:prop>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/other/escaped.txt</d:href>
    <d:propstat>
      <d:prop>
        <d:displayname>escaped.txt</d:displayname>
        <d:getcontentlength>4</d:getcontentlength>
      </d:prop>
    </d:propstat>
  </d:response>
</d:multistatus>
"""
    }
}

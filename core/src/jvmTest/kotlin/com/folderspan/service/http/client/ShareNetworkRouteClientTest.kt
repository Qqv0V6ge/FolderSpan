package com.folderspan.service.http.client

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareNetworkRouteClientTest {
    @Test
    fun getListAuthenticatesWithPostBodyAndSessionHeader() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            linkShareMockResponse(request)
        }
        val httpClient = HttpClient(engine)
        val client = ShareNetworkRouteClient(
            baseUrl = "http://localhost:8080",
            password = "secret",
            userAgent = "TestAgent",
            httpClient = httpClient
        )

        val result = client.getList("/")
        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        assertEquals(2, captured.size)

        val authRequest = captured.first()
        assertEquals(HttpMethod.Post, authRequest.method)
        assertEquals("/auth", authRequest.url.encodedPath)
        assertNull(authRequest.url.parameters["pwd"])
        assertTrue(authRequestBody(authRequest).contains("pwd=secret"))
        assertFalse(authRequest.url.toString().contains("pwd="))

        val listRequest = captured.last()
        assertEquals(HttpMethod.Get, listRequest.method)
        assertEquals("/", listRequest.url.encodedPath)
        assertEquals("true", listRequest.headers["X-API-Request"])
        assertEquals("TestAgent", listRequest.headers[HttpHeaders.UserAgent])
        assertEquals(SESSION_TOKEN, listRequest.headers["X-FolderSpan-Link-Session"])
        assertNull(listRequest.url.parameters["pwd"])
    }

    @Test
    fun writeBytesReusesSessionAndOmitsPasswordQuery() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            linkShareMockResponse(request)
        }
        val httpClient = HttpClient(engine)
        val client = ShareNetworkRouteClient(
            baseUrl = "http://localhost:8080",
            password = "secret",
            userAgent = "TestAgent",
            httpClient = httpClient
        )

        val output = StringBuilder()
        val result = client.writeBytes("/file.txt") { chunk, _, _ ->
            output.append(chunk.decodeToString())
            Result.success(Unit)
        }
        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        assertEquals("data", output.toString())
        assertEquals(2, captured.size)
        assertEquals(HttpMethod.Post, captured.first().method)
        assertEquals("/auth", captured.first().url.encodedPath)
        val download = captured.last()
        assertEquals("/file.txt", download.url.encodedPath)
        assertEquals(SESSION_TOKEN, download.headers["X-FolderSpan-Link-Session"])
        assertNull(download.url.parameters["pwd"])
    }

    @Test
    fun getListWithoutPasswordSkipsAuthPost() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            respond(
                content = ByteReadChannel("[]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(engine)
        val client = ShareNetworkRouteClient(
            baseUrl = "http://localhost:8080",
            password = "",
            userAgent = "TestAgent",
            httpClient = httpClient
        )

        val result = client.getList("/")
        assertTrue(result.isSuccess)
        assertEquals(1, captured.size)
        assertEquals(HttpMethod.Get, captured.single().method)
        assertNull(captured.single().headers["X-FolderSpan-Link-Session"])
        assertNull(captured.single().url.parameters["pwd"])
    }

    @Test
    fun calculateCompletedBlocksUsesTransferredBytesInsteadOfReadCount() {
        val totalBytes = HttpRouteClientManager.MAX_LENGTH.toLong() * 1024L
        val totalBlocks = 1024L

        assertEquals(
            1L,
            calculateCompletedBlocks(98_722L, totalBytes, HttpRouteClientManager.MAX_LENGTH, totalBlocks)
        )
        assertEquals(
            2L,
            calculateCompletedBlocks(
                (2L * HttpRouteClientManager.MAX_LENGTH.toLong()) - 1L,
                totalBytes,
                HttpRouteClientManager.MAX_LENGTH,
                totalBlocks
            )
        )
    }

    @Test
    fun calculateCompletedBlocksClampsToTotalBlocks() {
        val totalBytes = 3L * HttpRouteClientManager.MAX_LENGTH.toLong()
        val totalBlocks = 3L

        assertEquals(
            0L,
            calculateCompletedBlocks(0L, totalBytes, HttpRouteClientManager.MAX_LENGTH, totalBlocks)
        )
        assertEquals(
            3L,
            calculateCompletedBlocks(totalBytes, totalBytes, HttpRouteClientManager.MAX_LENGTH, totalBlocks)
        )
        assertEquals(
            3L,
            calculateCompletedBlocks(totalBytes + 2048L, totalBytes, HttpRouteClientManager.MAX_LENGTH, totalBlocks)
        )
    }

    @Test
    fun parseLinkShareSessionTokenReadsFirstCookiePair() {
        assertEquals(
            SESSION_TOKEN,
            parseLinkShareSessionToken(
                listOf("FolderSpanLinkShareSession=$SESSION_TOKEN; Path=/; HttpOnly; SameSite=Lax")
            ),
        )
        assertNull(parseLinkShareSessionToken(listOf("FolderSpanLinkShareClient=abc")))
        assertNotNull(parseLinkShareSessionToken(listOf("FolderSpanLinkShareSession=$SESSION_TOKEN")))
    }
}

private const val SESSION_TOKEN = "sessiontoken12345678"

private fun MockRequestHandleScope.linkShareMockResponse(request: HttpRequestData): HttpResponseData {
    if (request.method == HttpMethod.Post && request.url.encodedPath == "/auth") {
        return respond(
            content = ByteReadChannel(ByteArray(0)),
            status = HttpStatusCode.Found,
            headers = headersOf(
                HttpHeaders.Location to listOf("/"),
                HttpHeaders.SetCookie to listOf(
                    "FolderSpanLinkShareSession=$SESSION_TOKEN; Path=/; HttpOnly; SameSite=Lax"
                ),
            ),
        )
    }
    val body = if (request.url.encodedPath == "/file.txt") "data" else "[]"
    return respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json")
    )
}

private fun authRequestBody(request: HttpRequestData): String {
    val body = request.body
    return if (body is TextContent) body.text else ""
}

package com.folderspan.service.network

import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkProtocol
import com.folderspan.test.runSuspendTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpDirectoryNetworkClientTest {
    private fun config() = NetworkConnectionConfig("HttpDirectory", "https://example.test/archive/", "alice", "secret")
    private fun http(engine: MockEngine) = HttpClient(engine) { followRedirects = false }

    @Test
    fun directoryKeepsOnlyImmediateSafeChildrenAndDecodesNames() = runSuspendTest {
        val engine = MockEngine { respond("", HttpStatusCode.OK) }
        val http = http(engine)
        try {
            val client = HttpDirectoryNetworkClient(config(), http)
            val entries = client.parseDirectory("""
                <a href="a%20b.txt">Wrong display name</a>
                <a href="child/">child/</a>
                <a href="a%20b.txt">duplicate</a>
                <a href="../">Parent directory</a>
                <a href="https://evil.test/archive/secret">external</a>
                <a href="/outside/secret">outside root</a>
                <a href="nested/deep.txt">nested</a>
                <a href="%2e%2e/secret">escaped parent</a>
                <a href="%2Fsecret">escaped separator</a>
                <a href="secret?token=other">query</a>
                <a href="javascript:alert(1)">script</a>
                <script><a href="fake.txt">fake</a></script>
            """.trimIndent(), Url(config().host))
            assertEquals(listOf("a b.txt", "child"), entries.map { it.name })
            assertEquals(listOf("/a b.txt", "/child"), entries.map { it.path })
            assertEquals(listOf(false, true), entries.map { it.isDirectory })
            assertTrue(entries.all { it.isSymbolicLinkKnown && it.size == -1L })
        } finally { http.close() }
    }

    @Test
    fun listingUsesConfiguredRootAndPercentEncodesFileNames() = runSuspendTest {
        val requested = mutableListOf<String>()
        val http = http(MockEngine { request ->
            requested += request.url.toString()
            respond("<a href='r%C3%A9sum%C3%A9%2B.txt'>file</a>", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"))
        })
        try {
            val client = HttpDirectoryNetworkClient(config(), http)
            val entries = client.list("/a b").getOrThrow()
            assertEquals(listOf("https://example.test/archive/a%20b/"), requested)
            assertEquals("/a b/résumé+.txt", entries.single().path)
            assertTrue(client.list("/../outside").isFailure)
            assertEquals(1, requested.size)
        } finally { http.close() }
    }

    @Test
    fun downloadRejectsCrossOriginRedirectBeforeCredentialsLeaveServer() = runSuspendTest {
        var calls = 0
        val http = http(MockEngine { request ->
            calls++
            assertEquals("example.test", request.url.host)
            assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().startsWith("Basic "))
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.test/secret"))
        })
        try {
            val client = HttpDirectoryNetworkClient(config(), http)
            assertTrue(client.downloadByChunks("/file", -1L) { _, _ -> Result.success(Unit) }.isFailure)
            assertEquals(1, calls)
        } finally { http.close() }
    }

    @Test
    fun downloadValidatesLengthAndPreservesCancellation() = runSuspendTest {
        val http = http(MockEngine {
            respond(ByteReadChannel("payload".encodeToByteArray()), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentLength, "7"))
        })
        try {
            val client = HttpDirectoryNetworkClient(config(), http)
            val bytes = mutableListOf<Byte>()
            assertTrue(client.downloadByChunks("/file", 7L) { chunk, total ->
                assertEquals(7L, total)
                bytes.addAll(chunk.toList())
                Result.success(Unit)
            }.getOrThrow())
            assertEquals("payload", bytes.toByteArray().decodeToString())
            assertTrue(client.downloadByChunks("/file", 99L) { _, _ -> error("must reject before consuming") }.isFailure)
            assertFailsWith<CancellationException> {
                client.downloadByChunks("/file", 7L) { _, _ -> throw CancellationException("stop") }
            }
        } finally { http.close() }
    }

    @Test
    fun readOnlyIsEnforcedByProviderAndNetworkWithoutRequests() = runSuspendTest {
        val http = http(MockEngine { error("Read-only operation must not send a request") })
        try {
            val client = HttpDirectoryNetworkClient(config(), http)
            assertFalse(client.capabilities.write)
            assertTrue(client.upload("local", "/file", 1L) { _, _ -> }.isFailure)
            assertTrue(client.uploadFromSource("/file", 1L, readChunk = { error("must not read source") }).isFailure)
            assertTrue(client.rename("/file", "/other").isFailure)
            assertTrue(client.delete("/file", false).isFailure)
            assertTrue(client.createFile("/file").isFailure)
            assertTrue(client.createFolder("/folder").isFailure)
            val network = object : Network("HTTP", "/", NetworkProtocol.HttpDirectory.name, config().host, "", "") {
                override val client: StorageProvider = client
            }
            assertTrue(network.menuPermission.read)
            assertTrue(network.menuPermission.copy)
            assertFalse(network.menuPermission.write)
            assertFalse(network.menuPermission.delete)
            assertTrue(network.rename("/", "file", "other").isFailure)
            assertTrue(network.delete("/file", false).isFailure)
            assertTrue(network.createFile("/", "file").isFailure)
        } finally { http.close() }
    }
}

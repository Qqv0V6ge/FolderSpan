package com.folderspan.service.webui

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequestBody
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponseBody
import com.folderspan.service.mcp.file.FileEndpointErrorCode
import com.folderspan.service.mcp.file.FileEndpointException
import com.folderspan.service.mcp.file.LocalFileEndpointGateway
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebUiSecurityRegressionTest {
    private class Fixture(trustedOrigins: Set<String> = emptySet()) {
        val json = WebUiPlatformClient.json
        private val settings = MapSettings().also(SettingsUtils::init)
        private val credentials = WebUiCredentialStore(settings)
        private val secret = checkNotNull(credentials.ensureInitialized().oneTimeSecret)
        val handler = WebUiRequestHandler(
            credentials, WebUiSessionStore(), WebUiSecurityPolicy(trustedOrigins) { setOf("127.0.0.1") },
            PlaceholderWebUiDocumentRoot(), WebUiFileTestPlatform(),
        )

        suspend fun request(
            method: String,
            path: String,
            token: String = "",
            bytes: ByteArray = byteArrayOf(),
            origin: String? = "http://127.0.0.1:12043",
            extraHeaders: Map<String, List<String>> = emptyMap(),
        ): LinkShareHttpResponse = handler.handleCommand(LinkShareHttpRequest.from(method, path, headers = buildMap {
            put("Host", listOf("127.0.0.1:12043"))
            origin?.let { put("Origin", listOf(it)) }
            if (token.isNotEmpty()) put(WEB_UI_SESSION_HEADER, listOf(token))
            put(WEB_UI_PROTOCOL_HEADER, listOf(WEB_UI_PROTOCOL_VERSION.toString()))
            putAll(extraHeaders)
        }, body = LinkShareHttpRequestBody.Bytes(bytes)))

        suspend fun login(origin: String? = "http://127.0.0.1:12043") = request(
            "POST", WEB_UI_LOGIN_PATH, origin = origin,
            bytes = json.encodeToString(WebUiLoginRequest(secret, WEB_UI_PROTOCOL_VERSION)).encodeToByteArray(),
        )

        suspend fun token(): String = json.decodeFromString<WebUiLoginResponse>(
            (login().body as LinkShareHttpResponseBody.Bytes).bytes.decodeToString(),
        ).sessionToken

        fun pathBody(path: String) = json.encodeToString(WebUiPathRequest(path)).encodeToByteArray()
    }

    private inline fun <reified T> LinkShareHttpResponse.value(): T = WebUiPlatformClient.json.decodeFromString(
        (body as LinkShareHttpResponseBody.Bytes).bytes.decodeToString(),
    )

    @Test
    fun readsKeepPermissionDeniedForNonDirectoryAncestors(): Unit = runBlocking {
        val fixture = Fixture()
        val root = Files.createTempDirectory("webui-read-nondirectory-").toRealPath()
        val file = Files.createFile(root.resolve("file.txt"))
        try {
            val token = fixture.token()
            val directoryPath = file.resolve("child").toString()
            val error = assertFailsWith<FileEndpointException> { LocalFileEndpointGateway().openDirectory(directoryPath) }
            assertEquals(FileEndpointErrorCode.PermissionDenied, error.code)
            assertEquals(403, fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/directories", token,
                fixture.pathBody(directoryPath)).statusCode)
            val path = encodeWebUiQuery(file.resolve("child/nested").toString())
            for (operation in listOf("details", "content")) {
                assertEquals(403, fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/$operation?path=$path", token).statusCode)
            }
        } finally {
            fixture.handler.stop()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun readsRejectParentLeafAndDanglingLinksBeforeCanonicalization(): Unit = runBlocking {
        val fixture = Fixture()
        val root = Files.createTempDirectory("webui-read-links-").toRealPath()
        val selected = Files.createDirectory(root.resolve("selected"))
        val outside = Files.createDirectory(root.resolve("outside"))
        val nested = Files.createDirectory(outside.resolve("nested"))
        val file = Files.writeString(outside.resolve("file.txt"), "outside-content")
        Files.writeString(nested.resolve("file.txt"), "outside-content")
        val empty = Files.createFile(outside.resolve("empty.txt"))
        val link = Files.createSymbolicLink(selected.resolve("link"), outside)
        val leaf = Files.createSymbolicLink(selected.resolve("leaf.txt"), file)
        val emptyLeaf = Files.createSymbolicLink(selected.resolve("empty.txt"), empty)
        val dangling = Files.createSymbolicLink(selected.resolve("dangling"), root.resolve("missing"))
        try {
            val token = fixture.token()
            for (target in listOf(link.resolve("file.txt"), link.resolve("nested/file.txt"),
                link.resolve("missing.txt"), leaf, emptyLeaf, dangling, dangling.resolve("file.txt"))) {
                for (operation in listOf("info", "details", "content")) {
                    val response = fixture.request("GET",
                        "$WEB_UI_PLATFORM_PATH/files/$operation?path=${encodeWebUiQuery(target.toString())}", token)
                    assertEquals(403, response.statusCode, operation)
                    assertFalse((response.body as LinkShareHttpResponseBody.Bytes).bytes.decodeToString().contains("outside-content"))
                }
            }
            // Leading whitespace makes this a non-absolute path; reject it without retargeting it.
            for (operation in listOf("details", "content")) {
                assertEquals(400, fixture.request("GET",
                    "$WEB_UI_PLATFORM_PATH/files/$operation?path=${encodeWebUiQuery("  ${link.resolve("file.txt")}  ")}",
                    token).statusCode)
            }
            for (target in listOf(link, link.resolve("nested"), dangling)) {
                assertEquals(403, fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/directories", token,
                    fixture.pathBody(target.toString())).statusCode)
            }
            for (target in listOf(link.resolve("../file.txt"), selected.resolve("missing/../link/file.txt"))) {
                val query = encodeWebUiQuery(target.toString())
                assertTrue(fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/info?path=$query", token).statusCode !in 200..299)
                for (operation in listOf("details", "content")) {
                    assertEquals(400, fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/$operation?path=$query", token).statusCode)
                }
            }
        } finally {
            fixture.handler.stop()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun ordinaryReadsKeepMetadataRangesAndLinkEntryListing(): Unit = runBlocking {
        val fixture = Fixture()
        val root = Files.createTempDirectory("webui-ordinary-reads-").toRealPath()
        val selected = Files.createDirectory(root.resolve("selected"))
        val file = Files.writeString(selected.resolve("file.txt"), "ordinary")
        val nested = Files.createDirectory(selected.resolve("nested"))
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.writeString(outside.resolve("unlisted.txt"), "outside-content")
        val link = Files.createSymbolicLink(selected.resolve("link"), outside)
        try {
            val token = fixture.token()
            val path = encodeWebUiQuery(file.toString())
            val info = fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/info?path=$path", token)
            assertEquals(200, info.statusCode)
            assertEquals(file.toString(), info.value<FileSimpleInfo>().path)
            assertEquals(8L, info.value<FileSimpleInfo>().size)
            val dotInfo = fixture.request("GET",
                "$WEB_UI_PLATFORM_PATH/files/info?path=${encodeWebUiQuery(nested.resolve("../file.txt").toString())}", token)
            assertEquals(200, dotInfo.statusCode)
            assertEquals(file.toString(), dotInfo.value<FileSimpleInfo>().path)
            val details = fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/details?path=$path", token)
            assertEquals(200, details.statusCode)
            assertEquals(file.toString(), details.value<FileInfo>().path)
            assertEquals(8L, details.value<FileInfo>().size)
            val directoryPath = encodeWebUiQuery(selected.toString())
            assertEquals(3L, fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/info?path=$directoryPath", token)
                .value<FileSimpleInfo>().size)
            assertEquals(-1L, fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/details?path=$directoryPath", token)
                .value<FileInfo>().size)
            val range = fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/content?path=$path&offset=2&length=3", token)
            assertEquals(200, range.statusCode)
            assertEquals("din", (range.body as LinkShareHttpResponseBody.Bytes).bytes.decodeToString())
            assertEquals("bytes 2-4/8", range.header("Content-Range"))
            val eof = fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/content?path=$path&offset=8&length=3", token)
            assertEquals(200, eof.statusCode)
            assertTrue((eof.body as LinkShareHttpResponseBody.Bytes).bytes.isEmpty())
            assertEquals("bytes */8", eof.header("Content-Range"))
            assertEquals(400, fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/content?path=$path&offset=9", token).statusCode)
            assertEquals(400, fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/content?path=$path&length=0", token).statusCode)
            assertEquals(404, fixture.request("GET",
                "$WEB_UI_PLATFORM_PATH/files/info?path=${encodeWebUiQuery(selected.resolve("missing").toString())}", token).statusCode)
            val opened = fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/directories", token,
                fixture.pathBody(selected.toString()))
            assertEquals(200, opened.statusCode)
            val handle = opened.value<WebUiHandle>().id
            val listed = fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/directories?handle=$handle", token)
            assertEquals(200, listed.statusCode)
            val batch = listed.value<WebUiDirectoryBatch>()
            assertTrue(batch.finished)
            assertTrue(batch.issues.isEmpty())
            assertEquals(setOf("file.txt", "nested", "link"), batch.entries.map { it.name }.toSet())
            val linkEntry = batch.entries.single { it.path == link.toString() }
            assertTrue(linkEntry.isSymbolicLinkKnown)
            assertTrue(linkEntry.isSymbolicLink)
            assertFalse(linkEntry.isDirectory)
        } finally {
            fixture.handler.stop()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun mutationsRejectParentLinksIncludingMissingAndDotPaths(): Unit = runBlocking {
        val fixture = Fixture()
        val sandbox = Files.createTempDirectory("webui-symlink-").toRealPath()
        val selected = Files.createDirectory(sandbox.resolve("selected"))
        val outside = Files.createDirectory(sandbox.resolve("outside"))
        val tree = Files.createDirectory(outside.resolve("tree"))
        val kept = Files.writeString(tree.resolve("kept.txt"), "keep")
        val file = Files.writeString(outside.resolve("file.txt"), "original")
        val link = Files.createSymbolicLink(selected.resolve("link"), outside)
        val dangling = Files.createSymbolicLink(selected.resolve("dangling"), sandbox.resolve("missing"))
        try {
            val token = fixture.token()
            for (parent in listOf(link, dangling, link.resolve(".."), selected.resolve("missing/../link"))) {
                assertEquals(403, fixture.request("POST",
                    "$WEB_UI_PLATFORM_PATH/files/append?path=${encodeWebUiQuery(parent.resolve("file.txt").toString())}",
                    token, byteArrayOf(1)).statusCode)
                assertEquals(403, fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/mkdirs", token,
                    fixture.pathBody(parent.resolve("new/nested").toString())).statusCode)
                assertEquals(403, fixture.request("DELETE",
                    "$WEB_UI_PLATFORM_PATH/files/tree?path=${encodeWebUiQuery(parent.resolve("tree").toString())}", token).statusCode)
            }
            assertEquals("original", Files.readString(file))
            assertEquals("keep", Files.readString(kept))
            assertFalse(Files.exists(outside.resolve("new")))
            assertFalse(Files.exists(sandbox.resolve("file.txt")))
            assertFalse(Files.exists(sandbox.resolve("new")))
            assertFalse(Files.exists(sandbox.resolve("missing")))
            val listed = fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/directories", token,
                fixture.pathBody(link.toString()))
            assertTrue(listed.statusCode !in 200..299)
            val read = fixture.request("GET",
                "$WEB_UI_PLATFORM_PATH/files/content?path=${encodeWebUiQuery(link.resolve("file.txt").toString())}&offset=0&length=16",
                token)
            assertTrue(read.statusCode !in 200..299)
            assertFalse((read.body as? LinkShareHttpResponseBody.Bytes)?.bytes?.decodeToString()?.contains("original") == true)
            for (path in listOf(link, dangling)) {
                assertEquals(403, fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/mkdirs", token,
                    fixture.pathBody(path.toString())).statusCode)
                assertEquals(403, fixture.request("DELETE",
                    "$WEB_UI_PLATFORM_PATH/files/tree?path=${encodeWebUiQuery(path.toString())}", token).statusCode)
            }
        } finally {
            fixture.handler.stop()
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun gatewayMutationsCannotBypassTheParentLinkBoundary(): Unit = runBlocking {
        val fixture = Fixture()
        val root = Files.createTempDirectory("webui-gateway-links-").toRealPath()
        val outside = Files.createDirectory(root.resolve("outside"))
        val kept = Files.writeString(outside.resolve("kept.txt"), "keep")
        val link = Files.createSymbolicLink(root.resolve("link"), outside)
        try {
            val token = fixture.token()
            assertEquals(403, fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/create", token,
                fixture.pathBody(link.resolve("created.txt").toString())).statusCode)
            assertEquals(403, fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/mkdir", token,
                fixture.pathBody(link.resolve("created-dir").toString())).statusCode)
            assertEquals(403, fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/rename", token,
                fixture.json.encodeToString(WebUiRenameRequest(link.toString(), "kept.txt", "renamed.txt")).encodeToByteArray()).statusCode)
            val path = encodeWebUiQuery(link.resolve("kept.txt").toString())
            assertEquals(403, fixture.request("PUT", "$WEB_UI_PLATFORM_PATH/files/content?path=$path&size=4&offset=0",
                token, "edit".encodeToByteArray()).statusCode)
            assertEquals(403, fixture.request("DELETE", "$WEB_UI_PLATFORM_PATH/files?path=$path", token).statusCode)
            val leafLink = Files.createSymbolicLink(root.resolve("file-link"), kept)
            assertEquals(403, fixture.request("PUT",
                "$WEB_UI_PLATFORM_PATH/files/content?path=${encodeWebUiQuery(leafLink.toString())}&size=4&offset=0",
                token, "edit".encodeToByteArray()).statusCode)
            Files.createSymbolicLink(root.resolve("rename-target"), kept)
            assertEquals(403, fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/rename", token,
                fixture.json.encodeToString(WebUiRenameRequest(root.toString(), "outside", "rename-target")).encodeToByteArray()).statusCode)
            assertEquals("keep", Files.readString(kept))
            assertFalse(Files.exists(outside.resolve("created.txt")))
            assertFalse(Files.exists(outside.resolve("created-dir")))
            assertFalse(Files.exists(outside.resolve("renamed.txt")))
        } finally {
            fixture.handler.stop()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun ordinaryMutationsAndTreeCleanupRetainNativeSemantics(): Unit = runBlocking {
        val fixture = Fixture()
        val root = Files.createTempDirectory("webui-ordinary-").toRealPath()
        val outside = Files.createTempDirectory("webui-kept-").toRealPath()
        val kept = Files.writeString(outside.resolve("kept.txt"), "keep")
        try {
            val token = fixture.token()
            val created = root.resolve("created.bin")
            assertEquals(200, fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/create", token,
                fixture.pathBody(created.toString())).statusCode)
            assertEquals(200, fixture.request("PUT",
                "$WEB_UI_PLATFORM_PATH/files/content?path=${encodeWebUiQuery(created.toString())}&size=4&offset=0",
                token, "data".encodeToByteArray()).statusCode)
            assertEquals(200, fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/mkdir", token,
                fixture.pathBody(root.resolve("created-dir").toString())).statusCode)
            assertEquals(200, fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/rename", token,
                fixture.json.encodeToString(WebUiRenameRequest(root.toString(), "created.bin", "renamed.bin")).encodeToByteArray()).statusCode)
            assertEquals("data", Files.readString(root.resolve("renamed.bin")))
            assertEquals(200, fixture.request("DELETE",
                "$WEB_UI_PLATFORM_PATH/files?path=${encodeWebUiQuery(root.resolve("renamed.bin").toString())}", token).statusCode)
            assertEquals(400, fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/create", token,
                fixture.pathBody("relative.bin")).statusCode)
            assertEquals(400, fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/rename", token,
                fixture.json.encodeToString(WebUiRenameRequest(root.toString(), "..", "renamed")).encodeToByteArray()).statusCode)
            val nested = root.resolve("new/nested")
            repeat(2) {
                assertEquals(200, fixture.request("POST", "$WEB_UI_PLATFORM_PATH/files/mkdirs", token,
                    fixture.pathBody(nested.toString())).statusCode)
            }
            val file = nested.resolve("file.txt")
            val fileUrl = "$WEB_UI_PLATFORM_PATH/files/append?path=${encodeWebUiQuery(file.toString())}"
            assertEquals(200, fixture.request("POST", fileUrl, token, "first".encodeToByteArray()).statusCode)
            assertEquals(200, fixture.request("POST", fileUrl, token, "second".encodeToByteArray()).statusCode)
            assertEquals("firstsecond", Files.readString(file))
            assertTrue(fixture.request("POST",
                "$WEB_UI_PLATFORM_PATH/files/append?path=${encodeWebUiQuery(root.resolve("missing/file.txt").toString())}",
                token, byteArrayOf(1)).statusCode !in 200..299)
            Files.createSymbolicLink(nested.resolve("outside"), outside)
            val symlink = encodeWebUiQuery(nested.resolve("outside").toString())
            assertEquals("true", (fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/symlink?path=$symlink", token)
                .body as LinkShareHttpResponseBody.Bytes).bytes.decodeToString())
            assertEquals(outside.toString(), fixture.json.decodeFromString<String>(
                (fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/canonical?path=$symlink", token)
                    .body as LinkShareHttpResponseBody.Bytes).bytes.decodeToString()))
            val treeUrl = "$WEB_UI_PLATFORM_PATH/files/tree?path=${encodeWebUiQuery(root.resolve("new").toString())}"
            repeat(2) { assertEquals(200, fixture.request("DELETE", treeUrl, token).statusCode) }
            assertFalse(Files.exists(root.resolve("new")))
            assertEquals("keep", Files.readString(kept))
            assertEquals(400, fixture.request("DELETE",
                "$WEB_UI_PLATFORM_PATH/files/tree?path=${encodeWebUiQuery(root.root.toString())}", token).statusCode)
        } finally {
            fixture.handler.stop()
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun foreignOriginsCannotLoginPreflightOrUseAnExistingSession(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val token = fixture.token()
            val origin = "https://independent.example"
            val responses = listOf(
                fixture.login(origin),
                fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/roots", token, origin = origin),
                fixture.request("OPTIONS", "$WEB_UI_PLATFORM_PATH/files/append", origin = origin, extraHeaders = mapOf(
                    "Access-Control-Request-Method" to listOf("POST"),
                    "Access-Control-Request-Headers" to listOf(WEB_UI_SESSION_HEADER),
                )),
            )
            responses.forEach { response ->
                assertEquals(403, response.statusCode)
                assertEquals(null, response.header("Access-Control-Allow-Origin"))
            }
        } finally { fixture.handler.stop() }
    }

    @Test
    fun allowedApiResponsesAndStaticAssetsNeverAdvertiseWildcardCors(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            assertEquals(200, fixture.login().statusCode)
            val response = fixture.request("GET", WEB_UI_BOOTSTRAP_PATH)
            assertEquals(200, response.statusCode)
            assertEquals("http://127.0.0.1:12043", response.header("Access-Control-Allow-Origin"))
            assertTrue(response.header("Vary").orEmpty().split(',').any { it.trim().equals("Origin", true) })
            assertEquals(401, fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/roots").statusCode)
            for (path in listOf("/", "/missing.js")) {
                assertEquals(null, fixture.request("GET", path).header("Access-Control-Allow-Origin"))
            }
            assertEquals(null, fixture.request("GET", WEB_UI_BOOTSTRAP_PATH, origin = null).header("Access-Control-Allow-Origin"))
        } finally { fixture.handler.stop() }
    }

    @Test
    fun explicitlyTrustedWebCanLoginPreflightAndUsePlatformOperations(): Unit = runBlocking {
        val origin = "http://localhost:8080"
        val fixture = Fixture(setOf(origin))
        try {
            val login = fixture.login(origin)
            assertEquals(200, login.statusCode)
            assertEquals(origin, login.header("Access-Control-Allow-Origin"))
            val token = fixture.json.decodeFromString<WebUiLoginResponse>(
                (login.body as LinkShareHttpResponseBody.Bytes).bytes.decodeToString()).sessionToken
            assertEquals(200, fixture.request("GET", "$WEB_UI_PLATFORM_PATH/files/roots", token, origin = origin).statusCode)
            val headers = mapOf(
                "Access-Control-Request-Method" to listOf("POST"),
                "Access-Control-Request-Headers" to listOf("Content-Type, $WEB_UI_SESSION_HEADER, $WEB_UI_PROTOCOL_HEADER"),
            )
            val preflight = fixture.request("OPTIONS", "$WEB_UI_PLATFORM_PATH/files/append", origin = origin, extraHeaders = headers)
            assertEquals(204, preflight.statusCode)
            assertEquals(origin, preflight.header("Access-Control-Allow-Origin"))
            assertTrue(preflight.header("Access-Control-Allow-Headers").orEmpty().contains(WEB_UI_SESSION_HEADER.lowercase()))
            assertEquals(403, fixture.request("OPTIONS", WEB_UI_LOGIN_PATH, origin = origin, extraHeaders =
                headers + ("Access-Control-Request-Headers" to listOf("X-Untrusted"))).statusCode)
            assertEquals(403, fixture.request("OPTIONS", WEB_UI_LOGIN_PATH, origin = null, extraHeaders = headers).statusCode)
            assertEquals(403, fixture.request("POST", WEB_UI_LOGIN_PATH, origin = origin, extraHeaders =
                mapOf("Origin" to listOf(origin, "https://evil.example"))).statusCode)
            assertEquals(403, fixture.request("GET", "/", extraHeaders = mapOf("Host" to listOf("evil.example"))).statusCode)
        } finally { fixture.handler.stop() }
    }
}

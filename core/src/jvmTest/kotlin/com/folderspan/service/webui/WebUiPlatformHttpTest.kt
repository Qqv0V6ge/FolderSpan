package com.folderspan.service.webui

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.http.server.linkshare.*
import com.folderspan.service.mcp.file.FileEndpointErrorCode
import com.folderspan.service.mcp.file.FileEndpointException
import com.folderspan.service.mcp.file.LocalFileEndpointGateway
import com.folderspan.settings.RuntimeScopedSettings
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.*

class WebUiPlatformHttpTest {
    @Test
    fun dropDirectoryChecksRuntimePermissionsAndRejectsSymbolicLinks(): Unit = runBlocking {
        val root = Files.createTempDirectory("webui-drop-directory-").toRealPath()
        val platform = JvmWebUiPlatform()
        val session = WebUiPlatformSession("drop-directory")
        suspend fun request(path: Path) = platform.handle(LinkShareHttpRequest.from(
            "GET", "$WEB_UI_PLATFORM_PATH/files/directory-writable?path=${encodeWebUiQuery(path.toString())}",
        ), session)
        suspend fun writable(path: Path): Boolean {
            val response = request(path)
            assertEquals(200, response.statusCode)
            return WebUiPlatformClient.json.decodeFromString<Boolean>(
                (response.body as LinkShareHttpResponseBody.Bytes).bytes.decodeToString(),
            )
        }
        try {
            val directory = Files.createDirectory(root.resolve("target"))
            assertTrue(writable(directory))
            val file = Files.writeString(root.resolve("file.txt"), "payload")
            assertFalse(writable(file))
            Files.createSymbolicLink(root.resolve("link"), directory)
            val denied = assertFailsWith<FileEndpointException> { request(root.resolve("link")) }
            assertEquals(FileEndpointErrorCode.PermissionDenied, denied.code)
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                val permissions = Files.getPosixFilePermissions(directory)
                try {
                    Files.setPosixFilePermissions(directory, setOf(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE,
                    ))
                    assertEquals(Files.isWritable(directory), writable(directory))
                } finally {
                    Files.setPosixFilePermissions(directory, permissions)
                }
            }
        } finally {
            session.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun fileRangesDirectoryBatchesAndSessionBoundaries(): Unit = runBlocking {
        val settings = MapSettings()
        SettingsUtils.init(settings)
        val credentials = WebUiCredentialStore(settings)
        val secret = checkNotNull(credentials.ensureInitialized().oneTimeSecret)
        val sessions = WebUiSessionStore()
        val handler = WebUiRequestHandler(credentials, sessions, WebUiSecurityPolicy { setOf("127.0.0.1") },
            PlaceholderWebUiDocumentRoot(), WebUiFileTestPlatform())
        val json = WebUiPlatformClient.json
        suspend fun request(method: String, path: String, token: String = "", bytes: ByteArray = byteArrayOf(), version: Int = WEB_UI_PROTOCOL_VERSION): LinkShareHttpResponse = handler.handleCommand(
            LinkShareHttpRequest.from(method, path, headers = buildMap {
                put("Host", listOf("127.0.0.1:12043"))
                put("Origin", listOf("http://127.0.0.1:12043"))
                if (token.isNotEmpty()) put(WEB_UI_SESSION_HEADER, listOf(token))
                put(WEB_UI_PROTOCOL_HEADER, listOf(version.toString()))
            }, body = LinkShareHttpRequestBody.Bytes(bytes)),
        )
        fun LinkShareHttpResponse.bytes() = (body as LinkShareHttpResponseBody.Bytes).bytes
        suspend fun login() = json.decodeFromString<WebUiLoginResponse>(request("POST", WEB_UI_LOGIN_PATH,
            bytes = json.encodeToString(WebUiLoginRequest(secret, WEB_UI_PROTOCOL_VERSION)).encodeToByteArray()).bytes().decodeToString())
        val root = Files.createTempDirectory("platform-test-").toRealPath()
        handler.start()
        try {
            assertEquals(401, request("POST", "$WEB_UI_PLATFORM_PATH/files/create").statusCode)
            assertEquals(426, request("POST", WEB_UI_LOGIN_PATH, bytes = json.encodeToString(WebUiLoginRequest(secret, 3)).encodeToByteArray()).statusCode)
            val first = login()
            val second = login()
            val token = first.sessionToken
            assertEquals(426, request("GET", WEB_UI_SESSION_PATH, token).statusCode)
            assertEquals(426, request("GET", "$WEB_UI_PLATFORM_PATH/files/roots", token, version = 3).statusCode)
            val target = root.resolve("file.bin")
            val path = encodeWebUiQuery(target.toString())
            val content = ByteArray(WEB_UI_FILE_STREAM_CHUNK_BYTES + 19) { (it % 239).toByte() }
            assertEquals(200, request("POST", "$WEB_UI_PLATFORM_PATH/files/create", token,
                json.encodeToString(WebUiPathRequest(target.toString())).encodeToByteArray()).statusCode)
            content.asList().chunked(WEB_UI_FILE_STREAM_CHUNK_BYTES).forEachIndexed { index, chunk ->
                assertEquals(200, request("PUT", "$WEB_UI_PLATFORM_PATH/files/content?path=$path&size=${content.size}&offset=${index * WEB_UI_FILE_STREAM_CHUNK_BYTES}", token, chunk.toByteArray()).statusCode)
            }
            assertContentEquals(content, Files.readAllBytes(target))
            assertContentEquals(content.copyOfRange(WEB_UI_FILE_STREAM_CHUNK_BYTES - 4, content.size),
                request("GET", "$WEB_UI_PLATFORM_PATH/files/content?path=$path&offset=${WEB_UI_FILE_STREAM_CHUNK_BYTES - 4}&length=23", token).bytes())
            assertEquals(400, request("PUT", "$WEB_UI_PLATFORM_PATH/files/content?path=$path&size=1&offset=1", token, byteArrayOf(1)).statusCode)
            repeat(130) { Files.createFile(root.resolve("entry-$it")) }
            val opened = request("POST", "$WEB_UI_PLATFORM_PATH/files/directories", token,
                json.encodeToString(WebUiPathRequest(root.toString())).encodeToByteArray())
            val handle = json.decodeFromString<WebUiHandle>(opened.bytes().decodeToString()).id
            assertTrue(request("GET", "$WEB_UI_PLATFORM_PATH/files/directories?handle=$handle", second.sessionToken).statusCode !in 200..299)
            val firstBatch = json.decodeFromString<WebUiDirectoryBatch>(request("GET", "$WEB_UI_PLATFORM_PATH/files/directories?handle=$handle", token).bytes().decodeToString())
            assertEquals(128, firstBatch.entries.size)
            assertFalse(firstBatch.finished)
            val lastBatch = json.decodeFromString<WebUiDirectoryBatch>(request("GET", "$WEB_UI_PLATFORM_PATH/files/directories?handle=$handle", token).bytes().decodeToString())
            assertEquals(3, lastBatch.entries.size)
            assertTrue(lastBatch.finished)
            val cache = java.nio.file.Path.of(checkNotNull(first.host).cache)
            assertTrue(Files.isDirectory(cache))
            val nested = Files.createDirectories(cache.resolve("nested/empty"))
            Files.writeString(nested.parent.resolve("payload.txt"), "temporary data")
            Files.createSymbolicLink(nested.parent.resolve("outside"), target)
            val disposable = Files.createDirectories(cache.resolve("discard/child"))
            Files.writeString(disposable.resolve("data.txt"), "temporary data")
            assertEquals(200, request("DELETE", "$WEB_UI_PLATFORM_PATH/files/tree?path=${encodeWebUiQuery(disposable.parent.toString())}", token).statusCode)
            assertFalse(Files.exists(disposable.parent))
            assertEquals(400, request("DELETE", "$WEB_UI_PLATFORM_PATH/files/tree?path=${encodeWebUiQuery(root.root.toString())}", token).statusCode)
            assertEquals(404, request("GET", "$WEB_UI_PLATFORM_PATH/files/info?path=${encodeWebUiQuery(root.resolve("missing").toString())}", token).statusCode)
            assertEquals(200, request("POST", WEB_UI_LOGOUT_PATH, token).statusCode)
            assertFalse(Files.exists(cache))
            assertEquals(401, request("DELETE", "$WEB_UI_PLATFORM_PATH/files?path=$path", token).statusCode)
            assertTrue(Files.exists(target))
        } finally {
            handler.stop()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun directoryBatchesIncludeImmediateChildCountsWithoutFollowingLinks(): Unit = runBlocking {
        val root = Files.createTempDirectory("webui-directory-count-").toRealPath()
        val platform = JvmWebUiPlatform()
        val session = WebUiPlatformSession("directory-count")
        val json = WebUiPlatformClient.json
        suspend fun request(method: String, suffix: String, body: ByteArray = byteArrayOf()): LinkShareHttpResponse =
            platform.handle(LinkShareHttpRequest.from(method, WEB_UI_PLATFORM_PATH + suffix,
                body = LinkShareHttpRequestBody.Bytes(body)), session)
        fun LinkShareHttpResponse.bytes() = (body as LinkShareHttpResponseBody.Bytes).bytes
        try {
            Files.createDirectory(root.resolve("empty"))
            val folder = Files.createDirectory(root.resolve("folder"))
            Files.writeString(folder.resolve("child.txt"), "child")
            Files.writeString(folder.resolve(".hidden"), "hidden")
            val nested = Files.createDirectory(folder.resolve("nested"))
            Files.writeString(nested.resolve("descendant.txt"), "descendant")
            Files.createSymbolicLink(folder.resolve("nested-link"), nested)
            Files.createSymbolicLink(root.resolve("folder-link"), folder)
            Files.write(root.resolve("file.bin"), byteArrayOf(1, 2, 3, 4))
            repeat(130) { Files.createFile(root.resolve("entry-$it")) }
            // The general-purpose cursor stays cheap; Web listing supplies the display count.
            assertEquals(-1L, LocalFileEndpointGateway().info(folder.toString()).getOrThrow().size)
            val handle = json.decodeFromString<WebUiHandle>(request("POST", "/files/directories",
                json.encodeToString(WebUiPathRequest(root.toString())).encodeToByteArray()).bytes().decodeToString()).id
            val first = json.decodeFromString<WebUiDirectoryBatch>(request("GET", "/files/directories?handle=$handle").bytes().decodeToString())
            val last = json.decodeFromString<WebUiDirectoryBatch>(request("GET", "/files/directories?handle=$handle").bytes().decodeToString())
            assertEquals(128, first.entries.size)
            assertFalse(first.finished)
            assertEquals(6, last.entries.size)
            assertTrue(last.finished)
            val entries = (first.entries + last.entries).associateBy { it.name }
            assertEquals(0L, entries.getValue("empty").size)
            assertEquals(4L, entries.getValue("folder").size)
            assertEquals(4L, entries.getValue("file.bin").size)
            assertTrue(entries.getValue("folder-link").isSymbolicLink)
            assertFalse(entries.getValue("folder-link").isDirectory)
            val nativeEntries = PathUtils.getFileAndFolder(FileAccessPermission.Allowed, root.toString())
                .getOrThrow().associateBy { it.name }
            assertEquals(nativeEntries.keys, entries.keys)
            for ((name, native) in nativeEntries) {
                val web = entries.getValue(name)
                assertEquals(native.isDirectory, web.isDirectory, name)
                assertEquals(native.size, web.size, name)
                assertEquals(native.isSymbolicLink, web.isSymbolicLink, name)
            }
        } finally {
            session.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun unreadableDirectoryCountMatchesNativeFallback(): Unit = runBlocking {
        assumeTrue(Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir"))).supportsFileAttributeView("posix"))
        val root = Files.createTempDirectory("webui-unreadable-count-").toRealPath()
        val blocked = Files.createDirectory(root.resolve("blocked"))
        Files.writeString(blocked.resolve("child.txt"), "unreadable")
        val permissions = Files.getPosixFilePermissions(blocked)
        val session = WebUiPlatformSession("unreadable-count")
        val platform = JvmWebUiPlatform()
        val json = WebUiPlatformClient.json
        try {
            Files.setPosixFilePermissions(blocked, emptySet<PosixFilePermission>())
            // A privileged test process can still enumerate the directory; it cannot reproduce this case.
            assumeTrue(blocked.toFile().listFiles() == null)
            val opened = platform.handle(LinkShareHttpRequest.from("POST", "$WEB_UI_PLATFORM_PATH/files/directories",
                body = LinkShareHttpRequestBody.Bytes(json.encodeToString(WebUiPathRequest(root.toString())).encodeToByteArray())), session)
            val handle = json.decodeFromString<WebUiHandle>((opened.body as LinkShareHttpResponseBody.Bytes).bytes.decodeToString()).id
            val response = platform.handle(LinkShareHttpRequest.from("GET", "$WEB_UI_PLATFORM_PATH/files/directories?handle=$handle"), session)
            val batch = json.decodeFromString<WebUiDirectoryBatch>((response.body as LinkShareHttpResponseBody.Bytes).bytes.decodeToString())
            val native = PathUtils.getFileAndFolder(FileAccessPermission.Allowed, root.toString()).getOrThrow().single()
            assertTrue(batch.finished)
            assertEquals(0L, native.size)
            assertEquals(native.size, batch.entries.single().size)
        } finally {
            session.close()
            Files.setPosixFilePermissions(blocked, permissions)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun systemRootDirectoryCountsRemainMetadataOnly(): Unit = runBlocking {
        assumeTrue(PathUtils.getPathSeparator() == "/" && Files.isDirectory(Path.of("/dev")))
        val platform = JvmWebUiPlatform()
        val session = WebUiPlatformSession("system-root-count")
        val json = WebUiPlatformClient.json
        suspend fun request(method: String, suffix: String, body: ByteArray = byteArrayOf()) = platform.handle(
            LinkShareHttpRequest.from(method, WEB_UI_PLATFORM_PATH + suffix, body = LinkShareHttpRequestBody.Bytes(body)), session)
        try {
            val opened = request("POST", "/files/directories", json.encodeToString(WebUiPathRequest("/")).encodeToByteArray())
            val handle = json.decodeFromString<WebUiHandle>((opened.body as LinkShareHttpResponseBody.Bytes).bytes.decodeToString()).id
            val entries = mutableListOf<FileSimpleInfo>()
            do {
                val response = request("GET", "/files/directories?handle=$handle")
                val batch = json.decodeFromString<WebUiDirectoryBatch>((response.body as LinkShareHttpResponseBody.Bytes).bytes.decodeToString())
                entries += batch.entries
            } while (!batch.finished)
            val native = PathUtils.getFileAndFolder(FileAccessPermission.Allowed, "/").getOrThrow().associateBy { it.name }
            val web = entries.associateBy { it.name }
            assertEquals(native.getValue("dev").size, web.getValue("dev").size)
            native["builddir"]?.let { assertEquals(it.size, web.getValue("builddir").size) }
            for (name in listOf("dev", "proc", "sys")) {
                val entry = web[name] ?: continue
                assertTrue(entry.size >= 0L, name)
                for (operation in listOf("info", "details", "content")) {
                    assertFailsWith<IllegalArgumentException> {
                        request("GET", "/files/$operation?path=${encodeWebUiQuery(entry.path)}")
                    }
                }
                assertFailsWith<IllegalArgumentException> {
                    request("POST", "/files/directories", json.encodeToString(WebUiPathRequest(entry.path)).encodeToByteArray())
                }
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun eventRepliesOverflowExpiryAndCancellationReleaseResources(): Unit = runBlocking {
        var now = 0L
        val session = WebUiPlatformSession("session", now = { now })
        var closed = 0
        val handle = session.own(Any()) { closed++ }
        val callback = session.scope.async { session.callback(handle, "decision", "question") }
        val event = session.poll().single()
        assertTrue(event.requiresReply)
        session.reply(event.id, "answer".encodeToByteArray())
        assertEquals("answer", callback.await().decodeToString())
        assertFailsWith<IllegalArgumentException> { session.reply(event.id, byteArrayOf()) }
        now += WEB_UI_RESOURCE_LEASE_MS
        assertTrue(session.expired())
        // Overflow originating inside a session worker must not join itself.
        val worker = session.scope.async {
            repeat(WEB_UI_EVENT_LIMIT + 1) { session.emit(handle, "packet") }
        }
        runCatching { withTimeout(5_000) { worker.await() } }
        withTimeout(5_000) { session.close() }
        assertEquals(1, closed)
        assertFailsWith<IllegalStateException> { session.resource(handle) }
        session.close()
        assertEquals(1, closed)

        val cancelled = WebUiPlatformSession("cancelled")
        val waiting = cancelled.scope.async { cancelled.callback("listener", "request", "") }
        cancelled.poll()
        withTimeout(5_000) { cancelled.close() }
        assertTrue(waiting.isCancelled)
    }

    @Test
    fun browserRecordsAreTargetScopedAndClearCannotEraseOtherTargets() {
        val storage = MapSettings()
        val first = RuntimeScopedSettings(storage, "https://one.example")
        val second = RuntimeScopedSettings(storage, "https://two.example")
        first.putString("bookmark", "/first")
        first.putLong("task", 123L)
        second.putString("bookmark", "/second")
        assertEquals("/first", RuntimeScopedSettings(storage, "https://one.example").getString("bookmark", ""))
        assertEquals(setOf("bookmark", "task"), first.keys)
        first.clear()
        assertEquals(0, first.size)
        assertEquals("/second", second.getString("bookmark", ""))
    }
}

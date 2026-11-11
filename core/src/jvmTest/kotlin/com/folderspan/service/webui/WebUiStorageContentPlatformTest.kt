package com.folderspan.service.webui

import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.StorageContentLocationReference
import com.folderspan.service.http.server.linkshare.*
import com.folderspan.service.network.*
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class WebUiStorageContentPlatformTest {
    private class Fixture {
        val directory = Files.createTempDirectory(Files.createDirectories(java.nio.file.Path.of(
            com.folderspan.utils.PathUtils.getCachePath())), "password-platform-test")
        val settings = MapSettings()
        val store = TestStorageContentVaultStore()
        val contexts = mutableListOf<StorageContentHostContext>()
        val sessions = mutableListOf<WebUiPlatformSession>()
        val config = NetworkConnectionConfig("FTP", "fixture.invalid", "alice", "protocol-login")
        fun session(token: String, now: () -> Long = { 0 }) =
            WebUiPlatformSession(token, now).also(sessions::add)
        suspend fun request(session: WebUiPlatformSession, operation: String, input: WebUiStorageContentRequest,
            scheme: String = "http", host: String = "127.0.0.1", peer: String = "127.0.0.1"): LinkShareHttpResponse =
            storageContentPlatformRequest(LinkShareHttpRequest.from("POST",
                "$WEB_UI_PLATFORM_PATH/storage/content/$operation", remoteHost = peer, scheme = scheme, host = host,
                body = LinkShareHttpRequestBody.Bytes(platformJson.encodeToString(input).encodeToByteArray())),
                session, createContext = {
                    StorageContentHostContext(StorageContentKeyStore(settings), settings, directory,
                        providerFactory = { store.provider() }).also(contexts::add)
                }, resolveTask = { _, reference -> reference.hashCode().toLong() })
        suspend fun privateFile(session: WebUiPlatformSession, input: WebUiStorageContentRequest, method: String,
            arguments: String = "", body: ByteArray = byteArrayOf()): LinkShareHttpResponse {
            fun encode(value: String) = java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
            val query = "?handle=" + encode(input.handle) + "&taskKey=" + input.taskKey +
                "&taskReference=" + encode(input.taskReference) + "&path=" + encode(input.stagedPath) + arguments
            return storageContentPlatformRequest(LinkShareHttpRequest.from(method,
                "$WEB_UI_PLATFORM_PATH/storage/content/private-file$query",
                body = LinkShareHttpRequestBody.Bytes(body)), session)
        }
        suspend fun open(session: WebUiPlatformSession): WebUiStorageContentRequest = WebUiStorageContentRequest(
            platformJson.decodeFromString<WebUiHandle>(request(session, "open", WebUiStorageContentRequest("")).bytes().decodeToString()).id,
            config)
        suspend fun close() {
            sessions.forEach { it.close() }
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun passwordLocationIsRemotePortableButItsUnlockIsSessionOwned(): Unit = runBlocking {
        val fixture = Fixture()
        val first = fixture.session("first")
        val second = fixture.session("second")
        val password = "content password with spaces "
        try {
            val firstInput = fixture.open(first)
            val inspected = platformJson.decodeFromString<StorageContentLocationStatus>(
                fixture.request(first, "inspect", firstInput).bytes().decodeToString())
            assertTrue(inspected.available && inspected.writable)
            assertNull(inspected.reference)
            assertEquals(0, fixture.store.writes)
            val created = fixture.request(first, "setup-password", firstInput.copy(password = password))
            assertEquals(200, created.statusCode)
            val reference = platformJson.decodeFromString<StorageContentLocationReference>(created.bytes().decodeToString())
            val configured = fixture.config.copy(extras = NetworkDriveExtras(contentEncryption = reference))
            val base = firstInput.copy(config = configured)
            assertTrue(fixture.request(first, "status", base).status().unlocked)
            assertFalse(created.bytes().decodeToString().contains(password))
            assertEquals(setOf("formatVersion", "enabled", "root", "vaultId"),
                platformJson.parseToJsonElement(created.bytes().decodeToString()).let { it as kotlinx.serialization.json.JsonObject }.keys)
            assertFails { fixture.request(second, "status", base) }
            val other = fixture.open(second).copy(config = configured)
            val seen = platformJson.decodeFromString<StorageContentLocationStatus>(
                fixture.request(second, "inspect", other).bytes().decodeToString())
            assertEquals(reference.vaultId, seen.reference?.vaultId)
            assertFalse(fixture.request(second, "status", other).status().unlocked)
            val unchanged = fixture.store.snapshot()
            val wrong = fixture.request(second, "unlock-password", other.copy(password = "wrong"))
            assertEquals(400, wrong.statusCode)
            val error = platformJson.decodeFromString<WebUiJsonError>(wrong.bytes().decodeToString())
            assertEquals(STORAGE_CONTENT_ERROR_PREFIX + StorageContentErrorCode.UnlockFailed.name, error.code)
            assertFalse(error.error.contains("wrong"))
            assertEquals(unchanged, fixture.store.snapshot())
            assertEquals(200, fixture.request(second, "unlock-password", other.copy(password = password)).statusCode)
            assertTrue(fixture.request(second, "status", other).status().unlocked)
            assertEquals(200, fixture.request(second, "lock", other).statusCode)
            assertFalse(fixture.request(second, "status", other).status().unlocked)
            assertEquals(400, fixture.request(second, "unlock", other).statusCode)
        } finally { fixture.close() }
    }

    @Test
    fun plaintextRemotePasswordOperationsAreRejectedBeforeBodyParsingAndNeverWrite(): Unit = runBlocking {
        val fixture = Fixture()
        val session = fixture.session("secure-channel")
        try {
            for (operation in listOf("setup-password", "unlock-password")) {
                for ((host, peer) in listOf(
                    "remote.example" to "192.0.2.10", "127.0.0.1" to "192.0.2.10",
                    "localhost.example" to "127.0.0.1", "127.1" to "127.0.0.1",
                )) {
                    val failure = assertFails {
                        storageContentPlatformRequest(LinkShareHttpRequest.from("POST",
                            "$WEB_UI_PLATFORM_PATH/storage/content/$operation", host = host, remoteHost = peer,
                            body = LinkShareHttpRequestBody.Bytes("{invalid private input".encodeToByteArray())), session)
                    }
                    assertEquals("Content passwords require HTTPS or a local Runtime", failure.message)
                }
            }
            val queryFailure = assertFails {
                storageContentPlatformRequest(LinkShareHttpRequest.from("POST",
                    "$WEB_UI_PLATFORM_PATH/storage/content/setup-password?password=private",
                    remoteHost = "127.0.0.1",
                    body = LinkShareHttpRequestBody.Bytes(byteArrayOf())), session)
            }
            assertEquals("Content passwords must use the request body", queryFailure.message)
            assertEquals(0, fixture.store.writes)
            val input = fixture.open(session)
            assertEquals(200, fixture.request(session, "setup-password", input.copy(password = "https content password"),
                scheme = "https", host = "remote.example", peer = "192.0.2.10").statusCode)
        } finally { fixture.close() }
    }

    @Test
    fun malformedRequestsDoNotExposePasswordInputAndRecoveryEndpointsAreUnavailable(): Unit = runBlocking {
        val fixture = Fixture()
        val session = fixture.session("malformed")
        val secret = "CONTENT_PASSWORD_MUST_NOT_APPEAR"
        try {
            val failure = assertFails {
                storageContentPlatformRequest(LinkShareHttpRequest.from("POST",
                    "$WEB_UI_PLATFORM_PATH/storage/content/setup-password", remoteHost = "127.0.0.1",
                    body = LinkShareHttpRequestBody.Bytes(("invalid JSON: " + secret).encodeToByteArray())), session)
            }
            assertEquals("Invalid content encryption request", failure.message)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains(secret))
            val input = fixture.open(session).copy(password = secret)
            assertFalse(input.toString().contains(secret))
            val wrongOperation = assertFails { fixture.request(session, "status", input) }
            assertEquals("Content password is not valid for this operation", wrongOperation.message)
            assertFalse(wrongOperation.toString().contains(secret))
            for (operation in listOf("create", "export", "verify", "import")) {
                val result = fixture.request(session, operation, input)
                assertEquals(400, result.statusCode)
                assertFalse(result.bytes().decodeToString().contains(secret))
            }
            assertEquals(0, fixture.store.writes)
        } finally { fixture.close() }
    }

    @Test
    fun expiredLeaseDeletesOnlyOwnedPlaintextAndReconnectRequiresPasswordAgain(): Unit = runBlocking {
        var now = 0L
        val fixture = Fixture()
        val session = fixture.session("lease") { now }
        val next = fixture.session("reconnected") { now }
        try {
            val initial = fixture.open(session)
            val created = fixture.request(session, "setup-password", initial.copy(password = "lease content password"))
            val reference = platformJson.decodeFromString<StorageContentLocationReference>(created.bytes().decodeToString())
            val configured = fixture.config.copy(extras = fixture.config.extras.copy(contentEncryption = reference))
            val base = initial.copy(config = configured)
            val context = fixture.contexts.last()
            val staged = fixture.request(session, "stage", base.copy(taskKey = 73, entryId = "plain",
                taskReference = UUID.randomUUID().toString()))
            val stage = java.nio.file.Path.of(platformJson.decodeFromString<String>(staged.bytes().decodeToString()))
            Files.writeString(stage, "session-owned plaintext")
            val unrelated = Files.writeString(fixture.directory.resolve("unrelated"), "keep")
            session.touchLease()
            now += WEB_UI_RESOURCE_LEASE_MS - 1
            assertFalse(session.expired())
            now++
            assertTrue(session.expired())
            session.close()
            assertFalse(Files.exists(stage))
            assertTrue(Files.exists(unrelated))
            assertFalse(context.status(configured).getOrThrow().unlocked)
            assertFails { fixture.request(session, "status", base) }
            assertFails { fixture.request(next, "status", base) }
            val reopened = fixture.open(next).copy(config = configured)
            assertFalse(fixture.request(next, "status", reopened).status().unlocked)
            assertEquals(200, fixture.request(next, "unlock-password", reopened.copy(password = "lease content password")).statusCode)
            assertTrue(fixture.request(next, "status", reopened).status().unlocked)
        } finally { fixture.close() }
    }

    @Test
    fun taskReferencesAndStagesCannotCrossContexts(): Unit = runBlocking {
        val fixture = Fixture()
        val first = fixture.session("first-tasks")
        val second = fixture.session("second-tasks")
        try {
            val base = fixture.open(first)
            val task = base.copy(taskKey = 73, entryId = "entry", taskReference = UUID.randomUUID().toString())
            fixture.request(first, "stage", task)
            assertFails { fixture.request(first, "prepare", task.copy(stagedPath = "/not-owned")) }
            assertFails { fixture.request(first, "cleanup", task.copy(taskReference = UUID.randomUUID().toString())) }
            assertFails { fixture.request(second, "cleanup", task) }
            val other = fixture.open(second)
            fixture.request(second, "stage", task.copy(handle = other.handle, taskReference = UUID.randomUUID().toString()))
            assertFails { fixture.request(second, "cleanup", task.copy(handle = other.handle)) }
        } finally { fixture.close() }
    }

    @Test
    fun privateStageIoIsBoundedAndCannotCrossItsHandleTaskOrRegisteredIdentity(): Unit = runBlocking {
        val fixture = Fixture()
        val session = fixture.session("private-stage")
        val other = fixture.session("other-stage")
        try {
            val base = fixture.open(session).copy(taskKey = 99, entryId = "plain", taskReference = UUID.randomUUID().toString())
            val path = platformJson.decodeFromString<String>(fixture.request(session, "stage", base).bytes().decodeToString())
            val stage = base.copy(stagedPath = path)
            assertFails { checkedPlatformPath(path) }
            fixture.privateFile(session, stage, "PUT", "&size=5&offset=0", byteArrayOf(1, 2))
            fixture.privateFile(session, stage, "PUT", "&size=5&offset=2", byteArrayOf(3, 4, 5))
            val info = platformJson.decodeFromString<com.folderspan.data.file.FileSimpleInfo>(
                fixture.privateFile(session, stage, "GET", "&metadata=true").bytes().decodeToString())
            assertEquals(5L, info.size)
            assertContentEquals(byteArrayOf(1, 2, 3, 4, 5),
                fixture.privateFile(session, stage, "GET", "&offset=0&length=5").bytes())
            assertFails { fixture.privateFile(session, stage, "GET", "&offset=4&length=2") }
            assertFails { fixture.privateFile(session, stage, "PUT", "&size=1048577&offset=0", ByteArray(1048577)) }
            assertFails { fixture.privateFile(session, stage.copy(taskKey = 100), "GET", "&metadata=true") }
            assertFails { fixture.privateFile(session, stage.copy(taskReference = UUID.randomUUID().toString()), "DELETE") }
            assertFails { fixture.privateFile(other, stage, "GET", "&metadata=true") }
            val unrelated = Files.write(fixture.directory.resolve("not-registered"), byteArrayOf(9))
            assertFails { fixture.privateFile(session, stage.copy(stagedPath = unrelated.toString()), "DELETE") }
            assertContentEquals(byteArrayOf(9), Files.readAllBytes(unrelated))
            val identity = platformJson.decodeFromString<String>(fixture.request(session, "stage-identity", stage).bytes().decodeToString())
            assertEquals(200, fixture.request(session, "stage-complete", stage.copy(expectedIdentity = identity)).statusCode)
            assertFails { fixture.request(session, "stage-complete", stage.copy(expectedIdentity = "wrong-identity")) }
            val original = java.nio.file.Path.of(path)
            val moved = original.resolveSibling(original.fileName.toString() + ".moved")
            Files.move(original, moved)
            Files.write(original, byteArrayOf(7))
            assertFails { fixture.privateFile(session, stage, "GET", "&metadata=true") }
            Files.delete(original)
            Files.move(moved, original)
            assertEquals(200, fixture.privateFile(session, stage, "DELETE").statusCode)
            assertEquals(200, fixture.privateFile(session, stage, "DELETE").statusCode)
        } finally { fixture.close() }
    }

    @Test
    fun preparedCipherIsReadOnlyAndPublicationCanTargetAnotherOwnedStage(): Unit = runBlocking {
        val fixture = Fixture()
        val session = fixture.session("private-pipeline")
        try {
            val initial = fixture.open(session)
            val reference = platformJson.decodeFromString<StorageContentLocationReference>(
                fixture.request(session, "setup-password", initial.copy(password = "private pipeline password")).bytes().decodeToString())
            val config = initial.config!!.copy(extras = NetworkDriveExtras(contentEncryption = reference))
            val base = initial.copy(config = config, taskKey = 110, entryId = "file", taskReference = UUID.randomUUID().toString())
            val plain = byteArrayOf(3, 1, 4, 1, 5)
            suspend fun stage(entry: String): String = platformJson.decodeFromString<String>(
                fixture.request(session, "stage", base.copy(entryId = entry)).bytes().decodeToString())
            val source = com.folderspan.data.file.FileSimpleInfo.pathFileSimpleInfo("/original.txt").copy(isDirectory = false, size = plain.size.toLong())
            val input = base.copy(stagedPath = stage("upload"), file = source)
            fixture.privateFile(session, input, "PUT", "&size=5&offset=0", plain.copyOf())
            val prepared = platformJson.decodeFromString<StoragePreparedContentUpload>(
                fixture.request(session, "prepare", input).bytes().decodeToString())
            assertFalse(prepared.uploaded || prepared.committed)
            val cipher = base.copy(stagedPath = prepared.path)
            val bytes = fixture.privateFile(session, cipher, "GET", "&offset=0&length=" + prepared.size).bytes()
            assertTrue(bytes.size > plain.size)
            assertFails { fixture.privateFile(session, cipher, "PUT", "&size=0&offset=0") }
            assertFails { fixture.privateFile(session, cipher, "DELETE") }
            assertFails { fixture.request(session, "stage-complete", cipher.copy(expectedIdentity = "anything")) }
            assertEquals(200, fixture.privateFile(session, input, "DELETE").statusCode)
            val target = StoragePath("/logical.txt")
            fixture.request(session, "begin", base.copy(target = target))
            val rawTarget = requireNotNull(prepared.target)
            fixture.store.files[rawTarget.path] = bytes.copyOf()
            fixture.request(session, "record", base.copy(entry = fixture.store.provider().stat(rawTarget).getOrThrow()))
            val logical = source.copy(path = target.path)
            val resolved = platformJson.decodeFromString<StorageContentDownload>(
                fixture.request(session, "resolve-download", base.copy(file = logical)).bytes().decodeToString())
            val download = base.copy(stagedPath = stage("download"), file = resolved.file)
            fixture.privateFile(session, download, "PUT", "&size=" + bytes.size + "&offset=0", bytes.copyOf())
            val destination = stage("published")
            assertTrue(platformJson.decodeFromString<Boolean>(fixture.request(session, "publish",
                download.copy(destinationPath = destination)).bytes().decodeToString()))
            assertContentEquals(plain, fixture.privateFile(session, base.copy(stagedPath = destination),
                "GET", "&offset=0&length=5").bytes())
            Files.deleteIfExists(java.nio.file.Path.of(prepared.path))
            val retry = base.copy(stagedPath = stage("retry"), file = source)
            fixture.privateFile(session, retry, "PUT", "&size=5&offset=0", plain.copyOf())
            val confirmed = platformJson.decodeFromString<StoragePreparedContentUpload>(
                fixture.request(session, "prepare", retry).bytes().decodeToString())
            assertTrue(confirmed.committed && confirmed.uploaded)
            assertEquals("", confirmed.path)
        } finally { fixture.close() }
    }

}

private fun LinkShareHttpResponse.bytes() = (body as LinkShareHttpResponseBody.Bytes).bytes
private fun LinkShareHttpResponse.status() = platformJson.decodeFromString<StorageContentStatus>(bytes().decodeToString())

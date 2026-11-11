package com.folderspan.service.network

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.StorageContentLocationReference
import com.folderspan.service.webui.*
import com.folderspan.test.runSuspendTest
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TestTaskRuntimePersistenceStore
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.russhwolf.settings.StorageSettings
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.random.Random
import kotlin.test.*

/** Real browser Network orchestration and a real Runtime; private fixture only supplies test credentials. */
class NetworkContentEncryptionRuntimeAcceptanceBrowserTest {
    @Test fun ftpPassive() = execute(Case("ftpPassive", "FTP"))
    @Test fun ftpActive() = execute(Case("ftpActive", "FTP", active = true))
    @Test fun ftpsPassive() = execute(Case("ftpsPassive", "FTPS"))
    @Test fun ftpsActive() = execute(Case("ftpsActive", "FTPS", active = true))
    @Test fun sftpPassword() = execute(Case("sftpPassword", "SFTP"))
    @Test fun sftpPrivateKey() = execute(Case("sftpPrivateKey", "SFTP_KEY"))
    @Test fun smb() = execute(Case("smb", "SMB"))
    @Test fun nfsV3() = execute(Case("nfsV3", "NFS3"))
    @Test fun nfsV4() = execute(Case("nfsV4", "NFS4"))
    @Test fun webDav() = execute(Case("webDav", "WebDAV"))
    @Test fun s3() = execute(Case("s3", "S3"))
    @Test fun googleDrive() = execute(Case("googleDrive", "GoogleDrive"))
    @Test fun dropbox() = execute(Case("dropbox", "Dropbox"))
    @Test fun nextcloud() = execute(Case("nextcloud", "Nextcloud"))
    @Test fun seafile() = execute(Case("seafile", "Seafile"))

    private data class Case(val name: String, val fixture: String, val active: Boolean = false)

    private fun execute(case: Case) = runSuspendTest {
        coroutineScope {
            val fixtureUrl = storageContentFixtureUrl(runtime = true)
                ?: error("Set private FOLDERSPAN_STORAGE_CONTENT_RUNTIME_FIXTURE_URL and select enabled test methods")
            val plan = Json.parseToJsonElement(exchange(fixtureUrl)).jsonObject
            val fixture = plan.getValue("fixtures").jsonObject[case.fixture]?.jsonObject
                ?: error("Missing disposable service; filter this method and record it as skipped")
            val runtime = WebUiRuntimeAddress.parse(plan.getValue("runtimeOrigin").jsonPrimitive.content)
            val runtimeSecret = plan.getValue("runtimeSecret").jsonPrimitive.content
            val resultsUrl = fixtureUrl.substringBeforeLast('/') + "/results"
            val settings = StorageSettings()
            val before = settings.keys.associateWith { settings.getStringOrNull(it) }
            val originalConnection = WebUiPlatformClient.connection
            val originalMode = WebUiAttachment.mode
            val client = WebUiRemoteClient(this)
            val createdAccounts = mutableListOf<String>()
            val networks = mutableListOf<Network>()
            val downloadPaths = mutableListOf<String>()
            var remoteRoot: StoragePath? = null
            var localDirectory: String? = null
            var stage = "login"
            val started = now()
            val password = "browser content password " + unique()
            val forbidden = mutableListOf(runtimeSecret, password, "incorrect browser content password")
            try {
                // Six complete cloud snapshots preserve byte-level safety checks and need a real-service budget.
                withTimeout(900_000) {
                    suspend fun login() {
                        WebUiAttachment.mode = WebUiMode.Attached
                        WebUiAttachment.target = runtime
                        client.login(runtimeSecret).getOrThrow()
                    }
                    login()
                    var config = Json.decodeFromJsonElement<NetworkConnectionConfig>(fixture.getValue("config"))
                    if (case.active) config = config.copy(extras = config.extras.copy(ftp = config.extras.ftp.copy(passiveMode = false)))
                    collectSecrets(fixture, forbidden)
                    if (config.protocol in setOf("GoogleDrive", "Dropbox", "Nextcloud", "Seafile")) {
                        val id = fixture["runtimeAccountId"]?.jsonPrimitive?.content ?: if (config.protocol == "Seafile") {
                            val credential = fixture.getValue("credential").jsonObject
                            StorageAccountPlatform.installSeafile(SeafileAuthorizationRequest(
                                server = config.host, token = credential.getValue("accessToken").jsonPrimitive.content,
                            )).also { createdAccounts += it.id }.id
                        } else error("Authorize this Runtime account before selecting the test method")
                        assertTrue(StorageAccountPlatform.accounts().any { it.id == id })
                        config = config.copy(extras = config.extras.copy(cloud = config.extras.cloud.copy(
                            accountId = id, connectionId = "browser-" + unique(),
                        )))
                    }
                    stage = "isolate"
                    val parent = fixture["root"]?.jsonPrimitive?.content ?: "/"
                    val directory = parent.trimEnd('/') + "/browser-" + case.name + "-" + unique()
                    println("RUNTIME_ENCRYPTION owned-root case=" + case.name + " name=" + directory.substringAfterLast('/'))
                    withRaw(config) { provider ->
                        assertTrue(provider.createFolder(directory).getOrThrow())
                        val entry = provider.stat(StoragePath(directory)).getOrThrow()
                        remoteRoot = StoragePath(entry.path, entry.reference)
                    }
                    val root = remoteRoot!!
                    val ordinary = "ordinary before encryption".encodeToByteArray()
                    withRaw(config) { writeRaw(it, StoragePath("$directory/ordinary.txt"), ordinary) }
                    val pending = config.copy(extras = config.extras.copy(
                        contentEncryption = StorageContentLocationReference(root = root),
                    ))
                    val beforeInspect = snapshot(config, root)
                    val inspected = StorageContentPlatform.inspectPasswordLocation(pending).getOrThrow()
                    assertTrue(inspected.available && inspected.writable)
                    assertNull(inspected.reference)
                    assertEquals(beforeInspect, snapshot(config, root))
                    stage = "setup"
                    val chars = password.toCharArray()
                    val reference = try { StorageContentPlatform.setupPassword(pending, chars).getOrThrow() }
                        finally { chars.fill('\u0000') }
                    val encrypted = pending.copy(extras = pending.extras.copy(contentEncryption = reference))
                    val first = network(encrypted).also { networks += it }
                    assertTrue(StorageContentPlatform.status(encrypted).getOrThrow().unlocked)
                    val payload = ByteArray(320 * 1024 + 17) { ((it * 31 + 7) % 251).toByte() }
                    stage = "upload"
                    println("RUNTIME_ENCRYPTION " + case.name + " public upload")
                    upload(first, "$directory/managed.bin", payload)
                    first.close()
                    client.logout()

                    // A fresh authenticated browser/Runtime session has no previous password context.
                    stage = "second-session"
                    login()
                    assertFalse(StorageContentPlatform.status(encrypted).getOrThrow().unlocked)
                    assertEquals(reference.vaultId, StorageContentPlatform.inspectPasswordLocation(encrypted).getOrThrow().reference?.vaultId)
                    val beforeWrong = snapshot(config, root)
                    val wrong = StorageContentPlatform.unlockPassword(encrypted, "incorrect browser content password".toCharArray())
                    assertEquals(StorageContentErrorCode.UnlockFailed, assertIs<StorageContentException>(wrong.exceptionOrNull()).code)
                    assertEquals(beforeWrong, snapshot(config, root), "Wrong password must not change remote bytes")
                    val originalPassword = password.toCharArray()
                    try { StorageContentPlatform.unlockPassword(encrypted, originalPassword).getOrThrow() }
                    finally { originalPassword.fill('\u0000') }
                    val second = network(encrypted).also { networks += it }
                    val entries = second.getList(directory, null, null).getOrThrow()
                    assertEquals(setOf("ordinary.txt", "managed.bin"), entries.map { it.name }.toSet())
                    val managed = entries.single { it.name == "managed.bin" }
                    assertEquals(payload.size.toLong(), managed.size)
                    stage = "verified-download"
                    val cache = plan.getValue("localDownloadRoot").jsonPrimitive.content.trimEnd('/') + "/case-" + unique()
                    localDirectory = cache
                    assertTrue(FileUtils.createFolder(FileAccessPermission.Allowed, cache).getOrThrow())
                    suspend fun download(file: FileSimpleInfo, name: String): ByteArray {
                        val path = "$cache/$name"
                        downloadPaths += path
                        assertTrue(second.downloadFileToLocal(file, path) { _, _ -> }.getOrThrow())
                        val size = FileUtils.getFile(FileAccessPermission.Allowed, path).getOrThrow().size
                        assertTrue(size >= 0 && size <= 1024 * 1024)
                        return FileUtils.readFileRange(FileAccessPermission.Allowed, path, 0, size).getOrThrow()
                    }
                    assertContentEquals(payload, download(managed, "managed.bin"))
                    assertContentEquals(ordinary, download(entries.single { it.name == "ordinary.txt" }, "ordinary.txt"))
                    stage = "rename"
                    assertTrue(second.rename(managed, "renamed.bin").getOrThrow())
                    val renamed = second.getList(directory, null, null).getOrThrow().single { it.name == "renamed.bin" }
                    assertContentEquals(payload, download(renamed, "renamed.bin"))

                    stage = "interruption"
                    val replacement = ByteArray(640 * 1024 + 37) { ((it * 17 + 13) % 239).toByte() }
                    var offset = 0
                    var interrupted = false
                    val result = try {
                        second.uploadFromSource(StoragePath("$directory/renamed.bin"), replacement.size.toLong(), { done, _ ->
                            if (done > 0) { interrupted = true; throw CancellationException("acceptance interruption") }
                        }) {
                            if (offset == replacement.size) null else {
                                val end = minOf(offset + 64 * 1024, replacement.size)
                                replacement.copyOfRange(offset, end).also { offset = end }
                            }
                        }
                    } catch (_: CancellationException) {
                        // Preserve the method timeout; the deliberate progress interruption leaves this job active.
                        currentCoroutineContext().ensureActive()
                        Result.failure(CancellationException("acceptance interruption"))
                    }
                    assertTrue(interrupted && (result.isFailure || result.getOrNull() == false))
                    val reconciled = second.getList(directory, null, null).getOrThrow()
                    assertEquals(setOf("ordinary.txt", "renamed.bin"), reconciled.map { it.name }.toSet())
                    val after = download(reconciled.single { it.name == "renamed.bin" }, "reconciled.bin")
                    assertTrue(after.contentEquals(payload) || after.contentEquals(replacement))

                    stage = "locked-upload"
                    val beforeLock = snapshot(config, root)
                    StorageContentPlatform.lock(encrypted).getOrThrow()
                    var consumed = false
                    assertTrue(second.uploadFromSource(StoragePath("$directory/locked.bin"), 1L, { _, _ -> }) {
                        if (consumed) null else byteArrayOf(1).also { consumed = true }
                    }.isFailure)
                    assertEquals(beforeLock, snapshot(config, root))
                    val unlock = password.toCharArray()
                    try { StorageContentPlatform.unlockPassword(encrypted, unlock).getOrThrow() } finally { unlock.fill('\u0000') }
                    stage = "delete"
                    assertTrue(second.delete(second.getList(directory, null, null).getOrThrow().single { it.name == "renamed.bin" }).getOrThrow())
                    assertEquals(listOf("ordinary.txt"), second.getList(directory, null, null).getOrThrow().map { it.name })
                    for (key in settings.keys) {
                        val value = settings.getStringOrNull(key).orEmpty()
                        assertTrue(forbidden.filter(String::isNotBlank).none { value.contains(it) }, "Secrets must remain out of browser storage")
                    }
                    StorageContentPlatform.lock(encrypted).getOrThrow()
                    println("RUNTIME_ENCRYPTION workflow completed case=" + case.name)
                    stage = "cleanup"
                    withRaw(config) { deleteFixture(it, root) }
                }
                record(resultsUrl, case.name, "passed", now() - started, "")
                println("RUNTIME_ENCRYPTION passed case=" + case.name)
            } catch (error: Throwable) {
                val reason = buildString {
                    append(stage)
                    remoteRoot?.path?.trimEnd('/')?.substringAfterLast('/')?.takeIf {
                        it.startsWith("browser-" + case.name + "-") && it.all { char -> char.isLetterOrDigit() || char == '-' }
                    }?.let { append(":root:").append(it) }
                    generateSequence(error) { it.cause }.take(6).forEach { cause ->
                        append(':').append(cause::class.simpleName ?: "Error")
                        if (cause is StorageContentException) append(':').append(cause.code.name)
                    }
                }.take(200)
                record(resultsUrl, case.name, "failed", now() - started, reason)
                // Exception classes and enum codes are safe; arbitrary provider messages can contain secrets.
                throw AssertionError("Real Runtime case=" + case.name + " failure=" + reason)
            } finally {
                try {
                    for (network in networks) runCatching { network.close() }
                    if (WebUiAttachment.hasSession) {
                        for (path in downloadPaths) runCatching { FileUtils.deleteFile(FileAccessPermission.Allowed, path) }
                        localDirectory?.let { runCatching { FileUtils.deleteEmptyDirectory(FileAccessPermission.Allowed, it) } }
                        for (id in createdAccounts) runCatching { StorageAccountPlatform.remove(id) }
                        runCatching { client.logout() }
                    }
                } finally {
                    client.disconnect()
                    WebUiPlatformClient.connection = originalConnection
                    for (key in settings.keys) if (key !in before) settings.remove(key)
                    for ((key, value) in before) if (value != null && settings.getStringOrNull(key) != value) settings.putString(key, value)
                    WebUiAttachment.reset()
                    WebUiAttachment.mode = originalMode
                }
            }
        }
    }

    private fun network(config: NetworkConnectionConfig) =
        object : Network("Runtime acceptance", config.pathSeparator, config.protocol, config.host, config.username, config.password, extras = config.extras) {
            override val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        }

    private suspend fun upload(network: Network, path: String, bytes: ByteArray) {
        var offset = 0
        assertTrue(network.uploadFromSource(StoragePath(path), bytes.size.toLong(), { _, _ -> }) {
            if (offset == bytes.size) null else {
                val end = minOf(offset + 64 * 1024, bytes.size)
                bytes.copyOfRange(offset, end).also { offset = end }
            }
        }.getOrThrow())
    }

    private suspend fun <T> withRaw(config: NetworkConnectionConfig, block: suspend (StorageProvider) -> T): T {
        val provider = NetworkClientFactory.create(config)
        return try { block(provider) } finally { provider.close() }
    }

    private suspend fun deleteFixture(provider: StorageProvider, root: StoragePath) {
        val boundary = root.path.trimEnd('/')
        require(boundary.isNotEmpty() && boundary != "/")
        val visited = mutableSetOf<String>()
        suspend fun remove(directory: StoragePath) {
            val path = directory.path.trimEnd('/')
            check(path == boundary || path.startsWith("$boundary/"))
            check(visited.add(path))
            for (entry in provider.list(directory).getOrThrow()) {
                check(entry.name.isNotBlank() && entry.name != "." && entry.name != ".." &&
                    '/' !in entry.name && '\\' !in entry.name)
                val child = StoragePath(entry.path, entry.reference)
                val childPath = child.path.trimEnd('/')
                check(childPath.startsWith("$path/") && childPath.removePrefix("$path/") == entry.name)
                if (entry.isDirectory && !entry.isSymbolicLink) remove(child)
                else assertTrue(provider.delete(child, false).getOrThrow())
            }
            check(provider.list(directory).getOrThrow().isEmpty())
            // Complete raw enumeration above proved this owned directory empty, so this is an explicit RMDIR.
            assertTrue(provider.delete(directory, true).getOrThrow())
        }
        remove(root)
    }

    private suspend fun writeRaw(provider: StorageProvider, path: StoragePath, bytes: ByteArray) {
        var sent = false
        assertTrue(provider.uploadFromSource(path, bytes.size.toLong(), { _, _ -> }) {
            if (sent) null else bytes.also { sent = true }
        }.getOrThrow())
    }

    private suspend fun snapshot(config: NetworkConnectionConfig, root: StoragePath): Map<String, String> = withRaw(config) { provider ->
        val entries = linkedMapOf<String, String>()
        val pending = ArrayDeque<StoragePath>()
        val seen = mutableSetOf<String>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            check(seen.add(directory.path))
            for (entry in provider.list(directory).getOrThrow()) {
                val path = StoragePath(entry.path, entry.reference)
                if (entry.isDirectory) { entries[entry.path.trimEnd('/') + "/"] = "directory"; pending.add(path) }
                else {
                    val chunks = mutableListOf<ByteArray>()
                    assertTrue((provider as ChunkReadableNetworkClient).downloadByChunks(path, entry.size) { bytes, _ ->
                        chunks += bytes.copyOf(); Result.success(Unit)
                    }.getOrThrow())
                    val joined = ByteArray(chunks.sumOf { it.size })
                    var offset = 0
                    for (chunk in chunks) { chunk.copyInto(joined, offset); offset += chunk.size }
                    entries[entry.path] = Base64.Default.encode(joined)
                }
            }
        }
        entries
    }

    private fun collectSecrets(element: JsonElement, output: MutableList<String>) {
        if (element is JsonObject) for ((key, value) in element) {
            if (value is JsonPrimitive && key in setOf("password", "accessToken", "privateKey", "token", "secretAccessKey")) output += value.content
            else collectSecrets(value, output)
        }
    }

    private suspend fun record(url: String, case: String, status: String, elapsed: Double, reason: String) {
        exchange(url, buildJsonObject { put("case", case); put("status", status); put("elapsedMs", elapsed); put("reason", reason) }.toString())
    }

    private suspend fun exchange(url: String, body: String? = null): String {
        val response = WebUiBrowserHttp.exchange(if (body == null) "GET" else "POST", url,
            body = body?.encodeToByteArray(), contentType = if (body == null) null else "application/json")
        assertEquals(200, response.status, "Private acceptance fixture request")
        return response.body.decodeToString()
    }

    private fun unique() = Random.nextLong().toString(16).replace("-", "") + Random.nextLong().toString(16).replace("-", "")
    private fun now(): Double = Clock.System.now().toEpochMilliseconds().toDouble()
}

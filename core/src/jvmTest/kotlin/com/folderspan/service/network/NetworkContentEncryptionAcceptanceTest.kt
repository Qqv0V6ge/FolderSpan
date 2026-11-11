package com.folderspan.service.network

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.StorageContentLocationReference
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TestTaskRuntimePersistenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

/**
 * Opt-in, destructive only within a fresh random subdirectory of a disposable service.
 * The private JSON contains protocol fixtures, never production credentials.
 * Real Network APIs perform all managed IO; raw providers only seed and inspect server data.
 */
class NetworkContentEncryptionAcceptanceTest {
    @Test fun ftpPassive() = verify("FTP")
    @Test fun ftpActive() = verify("FTP") { it.copy(extras = it.extras.copy(ftp = it.extras.ftp.copy(passiveMode = false))) }
    @Test fun ftpsPassive() = verify("FTPS")
    @Test fun ftpsActive() = verify("FTPS") { it.copy(extras = it.extras.copy(ftp = it.extras.ftp.copy(passiveMode = false))) }
    @Test fun sftpPassword() = verify("SFTP")
    @Test fun sftpPrivateKey() = verify("SFTP_KEY")
    @Test fun smb() = verify("SMB")
    @Test fun nfsV3() = verify("NFS3")
    @Test fun nfsV4() = verify("NFS4")
    @Test fun webDav() = verify("WebDAV")
    @Test fun s3() = verify("S3")
    @Test fun googleDrive() = verify("GoogleDrive")
    @Test fun dropbox() = verify("Dropbox")
    @Test fun nextcloud() = verify("Nextcloud")
    @Test fun seafile() = verify("Seafile")

    private fun verify(case: String, transform: (NetworkConnectionConfig) -> NetworkConnectionConfig = { it }) = runBlocking {
        val fixture = readNetworkContentEncryptionFixture(case)
        val credential = fixture["credential"]?.let { decodeFixture<StorageCredential>(it) }
        val installedAccounts = mutableListOf<String>()
        var raw: StorageProvider? = null
        var first: Network? = null
        var second: Network? = null
        val local = Files.createTempDirectory("folderspan-encryption-acceptance-")
        var root: StoragePath? = null
        var passed = false
        try {
            val accountA = credential?.let { StorageAccounts.install(it.copy(id = UUID.randomUUID().toString()))
                .also { account -> installedAccounts += account.id } }
            val accountB = credential?.let { StorageAccounts.install(it.copy(id = UUID.randomUUID().toString()))
                .also { account -> installedAccounts += account.id } }
            // Six full cloud snapshots require many real network round trips.
            withTimeout(15 * 60_000L) {
                var base = transform(decodeFixture<NetworkConnectionConfig>(fixture.getValue("config")))
                base = base.copy(extras = base.extras.copy(contentEncryption = null,
                    cloud = if (accountA == null) base.extras.cloud else base.extras.cloud.copy(
                        connectionId = "acceptance-a-" + UUID.randomUUID(), accountId = accountA.id,
                    ),
                ))
                val provider = NetworkClientFactory.create(base)
                raw = provider
                val parent = fixture["root"]?.jsonPrimitive?.content ?: provider.rootLocation.path
                val directory = parent.trimEnd('/') + "/encryption-" + UUID.randomUUID()
                assertTrue(provider.createFolder(directory).getOrThrow())
                val rootEntry = provider.stat(StoragePath(directory)).getOrThrow()
                val location = StoragePath(rootEntry.path, rootEntry.reference)
                root = location

                val ordinary = "ordinary file before encryption 密文位置".encodeToByteArray()
                val ordinaryPath = StoragePath("$directory/ordinary.txt")
                writeRaw(provider, ordinaryPath, ordinary)
                val uninitialized = base.copy(extras = base.extras.copy(
                    contentEncryption = StorageContentLocationReference(root = location),
                ))
                val beforeInspect = snapshot(provider, location)
                val initial = StorageContentPlatform.inspectPasswordLocation(uninitialized).getOrThrow()
                assertTrue(initial.available && initial.writable)
                assertNull(initial.reference)
                assertEquals(beforeInspect, snapshot(provider, location), "Inspect must not initialize a location")

                val password = " acceptance 密码 " + UUID.randomUUID() + " "
                val reference = StorageContentPlatform.setupPassword(uninitialized, password.toCharArray()).getOrThrow()
                assertTrue(reference.enabled)
                val configA = uninitialized.copy(extras = uninitialized.extras.copy(contentEncryption = reference))
                first = network(configA)
                val configB = configA.copy(extras = configA.extras.copy(
                    contentEncryption = reference.copy(),
                    cloud = configA.extras.cloud.copy(
                        connectionId = "acceptance-b-" + UUID.randomUUID(),
                        accountId = accountB?.id ?: configA.extras.cloud.accountId,
                    ),
                ))
                second = network(configB)
                val a = first!!
                val b = second!!
                assertTrue(StorageContentPlatform.status(configA).getOrThrow().unlocked)
                assertFalse(StorageContentPlatform.status(configB).getOrThrow().unlocked,
                    "A different local reference must not borrow the first reference's unlock")
                val inspected = StorageContentPlatform.inspectPasswordLocation(configB).getOrThrow()
                assertEquals(reference.vaultId, inspected.reference?.vaultId)

                val payload = ByteArray(320 * 1024 + 17) { ((it * 31 + 7) % 251).toByte() }
                upload(a, StoragePath("$directory/managed.bin"), payload)
                val afterUpload = snapshot(provider, location)
                assertTrue(StorageContentPlatform.unlockPassword(configB, "incorrect acceptance password".toCharArray()).isFailure)
                assertFalse(StorageContentPlatform.status(configB).getOrThrow().unlocked)
                assertEquals(afterUpload, snapshot(provider, location), "Wrong passwords must not modify any remote byte")
                StorageContentPlatform.unlockPassword(configB, password.toCharArray()).getOrThrow()
                assertTrue(StorageContentPlatform.status(configB).getOrThrow().unlocked)

                val entries = b.getList(directory, null, null).getOrThrow()
                assertEquals(setOf("ordinary.txt", "managed.bin"), entries.map { it.name }.toSet(),
                    "Managed listings must hide reserved objects while preserving ordinary files")
                val managed = entries.single { it.name == "managed.bin" }
                assertEquals(payload.size.toLong(), managed.size)
                assertContentEquals(payload, download(b, managed, local.resolve("managed.bin")))
                assertContentEquals(ordinary, download(b, entries.single { it.name == "ordinary.txt" }, local.resolve("ordinary.txt")))
                if (case == "Nextcloud") {
                    val secondPayload = "second authorized connection writes independently".encodeToByteArray()
                    upload(b, StoragePath("$directory/second-writer.bin"), secondPayload)
                    val fromSecond = a.getList(directory, null, null).getOrThrow().single { it.name == "second-writer.bin" }
                    assertContentEquals(secondPayload, download(a, fromSecond, local.resolve("second-writer.bin")))
                    assertTrue(a.delete(fromSecond).getOrThrow())
                    assertEquals(setOf("ordinary.txt", "managed.bin"), b.getList(directory, null, null).getOrThrow().map { it.name }.toSet())
                }
                assertTrue(a.rename(managed, "renamed.bin").getOrThrow())
                val renamed = b.getList(directory, null, null).getOrThrow().single { it.name == "renamed.bin" }
                assertContentEquals(payload, download(b, renamed, local.resolve("renamed.bin")))

                // Interrupt the public upload at its first progress callback with transferred bytes.
                // The old revision or a fully authenticated new revision may be visible; partial data may not.
                val replacement = ByteArray(640 * 1024 + 37) { ((it * 17 + 13) % 239).toByte() }
                var interruptionObserved = false
                var offset = 0
                val interrupted = try {
                    a.uploadFromSource(StoragePath("$directory/renamed.bin"), replacement.size.toLong(), { completed, _ ->
                        if (completed > 0) {
                            interruptionObserved = true
                            throw CancellationException("acceptance interruption")
                        }
                    }) {
                        if (offset == replacement.size) null else {
                            val end = minOf(offset + 64 * 1024, replacement.size)
                            replacement.copyOfRange(offset, end).also { offset = end }
                        }
                    }
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    Result.failure(error)
                }
                if (!interruptionObserved) {
                    throw AssertionError("The transfer must reach a progress callback before cancellation", interrupted.exceptionOrNull())
                }
                assertTrue(interrupted.isFailure || interrupted.getOrNull() == false)
                val reconciled = a.getList(directory, null, null).getOrThrow()
                assertEquals(setOf("ordinary.txt", "renamed.bin"), reconciled.map { it.name }.toSet())
                val afterInterruption = download(a, reconciled.single { it.name == "renamed.bin" }, local.resolve("reconciled.bin"))
                assertTrue(afterInterruption.contentEquals(payload) || afterInterruption.contentEquals(replacement),
                    "Interrupted writes must expose a complete authenticated revision")
                assertContentEquals(ordinary, readRaw(provider, ordinaryPath, ordinary.size.toLong()))

                val bytesBeforeLock = snapshot(provider, location)
                StorageContentPlatform.lock(configB).getOrThrow()
                var lockedRead = false
                assertTrue(b.uploadFromSource(StoragePath("$directory/locked.bin"), 1L, { _, _ -> }) {
                    if (lockedRead) null else byteArrayOf(1).also { lockedRead = true }
                }.isFailure)
                assertEquals(bytesBeforeLock, snapshot(provider, location), "Locked uploads must not write plaintext")
                StorageContentPlatform.unlockPassword(configB, password.toCharArray()).getOrThrow()
                val toDelete = b.getList(directory, null, null).getOrThrow().single { it.name == "renamed.bin" }
                assertTrue(a.delete(toDelete).getOrThrow())
                assertEquals(listOf("ordinary.txt"), a.getList(directory, null, null).getOrThrow().map { it.name })
                assertContentEquals(ordinary, readRaw(provider, ordinaryPath, ordinary.size.toLong()))
                StorageContentPlatform.lock(configA).getOrThrow()
                StorageContentPlatform.lock(configB).getOrThrow()
                passed = true
                println("ENCRYPTION_ACCEPTANCE passed case=$case service=" + fixture["serviceVersion"]?.jsonPrimitive?.content.orEmpty())
            }
        } finally {
            try {
                try {
                    first?.close()
                    second?.close()
                    if (passed) root?.let { location ->
                        val cleanup = runCatching { raw?.let { deleteNetworkContentEncryptionFixture(it, location) } }
                        if (cleanup.isFailure) {
                            println("ENCRYPTION_ACCEPTANCE cleanup failed case=$case root=" + location.path +
                                " type=" + cleanup.exceptionOrNull()!!::class.simpleName)
                            cleanup.getOrThrow()
                        }
                        println("ENCRYPTION_ACCEPTANCE cleaned case=$case root=" + location.path)
                    }
                    else root?.let { println("ENCRYPTION_ACCEPTANCE retained failed fixture case=$case root=" + it.path) }
                } finally { raw?.close() }
            } finally {
                val removals = installedAccounts.map { id -> runCatching { StorageAccounts.store.remove(id) } }
                local.toFile().deleteRecursively()
                removals.firstOrNull { it.isFailure }?.getOrThrow()
            }
        }
    }

    private inline fun <reified T> decodeFixture(element: JsonElement): T =
        try { Json.decodeFromJsonElement<T>(element) }
        catch (_: Exception) { throw IllegalArgumentException("Malformed private acceptance fixture field") }

    private fun network(config: NetworkConnectionConfig): Network =
        object : Network("Encryption acceptance", config.pathSeparator, config.protocol, config.host, config.username,
            config.password, extras = config.extras) {
            override val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        }

    private suspend fun upload(network: Network, path: StoragePath, bytes: ByteArray) {
        var offset = 0
        assertTrue(network.uploadFromSource(path, bytes.size.toLong(), { _, _ -> }) {
            if (offset == bytes.size) null else {
                val end = minOf(offset + 64 * 1024, bytes.size)
                bytes.copyOfRange(offset, end).also { offset = end }
            }
        }.getOrThrow())
    }

    private suspend fun download(network: Network, file: FileSimpleInfo, target: Path): ByteArray {
        assertTrue(network.downloadFileToLocal(file, target.toString()) { _, _ -> }.getOrThrow())
        return Files.readAllBytes(target)
    }

    private suspend fun writeRaw(provider: StorageProvider, path: StoragePath, bytes: ByteArray) {
        var sent = false
        assertTrue(provider.uploadFromSource(path, bytes.size.toLong(), { _, _ -> }) {
            if (sent) null else bytes.also { sent = true }
        }.getOrThrow())
    }

    private suspend fun readRaw(provider: StorageProvider, path: StoragePath, size: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        assertTrue((provider as ChunkReadableNetworkClient).downloadByChunks(path, size) { bytes, _ ->
            output.write(bytes)
            Result.success(Unit)
        }.getOrThrow())
        return output.toByteArray()
    }

    private suspend fun snapshot(provider: StorageProvider, root: StoragePath): Map<String, String> {
        val files = sortedMapOf<String, String>()
        val pending = ArrayDeque<StoragePath>()
        val visited = mutableSetOf<String>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            check(visited.add(directory.path)) { "Unexpected directory cycle" }
            for (entry in provider.list(directory).getOrThrow()) {
                val path = StoragePath(entry.path, entry.reference)
                if (entry.isDirectory) { files[entry.path.trimEnd('/') + "/"] = "directory"; pending.add(path) }
                else files[entry.path] = MessageDigest.getInstance("SHA-256")
                    .digest(readRaw(provider, path, entry.size)).joinToString("") { "%02x".format(it.toInt() and 255) }
            }
        }
        return files
    }
}

internal fun readNetworkContentEncryptionFixture(case: String): JsonObject {
    val file = System.getenv("FOLDERSPAN_ENCRYPTION_ACCEPTANCE_CONFIG")
    assumeTrue("Set a private FOLDERSPAN_ENCRYPTION_ACCEPTANCE_CONFIG for real-service acceptance", !file.isNullOrBlank())
    val path = Path.of(file!!)
    val permissions = Files.getPosixFilePermissions(path)
    assertTrue(permissions.none { it in setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
        PosixFilePermission.OTHERS_EXECUTE) }, "Real-service credentials must be owner-only")
    val fixtures = try { Json.parseToJsonElement(Files.readString(path)).jsonObject }
    catch (_: Exception) { throw IllegalArgumentException("Malformed private acceptance fixture JSON") }
    assumeTrue("No disposable $case service configured; not a passing real-service acceptance", case in fixtures)
    return fixtures.getValue(case).jsonObject
}

/** Delete only this test's known subtree, respecting providers whose directory delete is nonrecursive. */
internal suspend fun deleteNetworkContentEncryptionFixture(provider: StorageProvider, root: StoragePath) {
    val boundary = root.path.trimEnd('/')
    require(boundary.isNotEmpty() && boundary != "/") { "A disposable fixture subtree is required" }
    val visited = mutableSetOf<String>()
    suspend fun remove(directory: StoragePath) {
        val path = directory.path.trimEnd('/')
        check(path == boundary || path.startsWith("$boundary/")) { "Fixture cleanup escaped its subtree" }
        check(visited.add(path)) { "Unexpected fixture directory cycle" }
        for (entry in provider.list(directory).getOrThrow()) {
            check(entry.name.isNotBlank() && entry.name != "." && entry.name != ".." &&
                '/' !in entry.name && '\\' !in entry.name) { "Unsafe fixture child name" }
            val child = StoragePath(entry.path, entry.reference)
            val childPath = child.path.trimEnd('/')
            check(childPath.startsWith("$path/") && childPath.removePrefix("$path/") == entry.name) {
                "Fixture child path differs from its listed parent"
            }
            if (entry.isDirectory && !entry.isSymbolicLink) remove(child)
            else assertTrue(provider.delete(child, false).getOrThrow())
        }
        check(provider.list(directory).getOrThrow().isEmpty()) { "Fixture directory is not empty after cleanup" }
        val emptyDelete = provider.deleteEmptyDirectory(directory)
        if (emptyDelete.exceptionOrNull() is com.folderspan.exception.NetworkUnsupportedException) {
            // The directory was explicitly verified empty before invoking providers' directory delete.
            assertTrue(provider.delete(directory, true).getOrThrow())
        } else assertTrue(emptyDelete.getOrThrow())
    }
    remove(root)
}

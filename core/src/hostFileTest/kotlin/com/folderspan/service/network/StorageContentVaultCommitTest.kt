package com.folderspan.service.network

import com.folderspan.test.createInMemorySettings
import com.folderspan.test.runSuspendTest
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class StorageContentVaultCommitTest {
    private val fs = FileSystem.SYSTEM
    private class Vault(val store: TestStorageContentVaultStore, val access: StorageContentVaultAccess,
        val unlocked: StorageContentPasswordLocation.Unlocked, val directory: Path, val settings: Settings)
    private suspend fun withVault(afterWrite: suspend (TestStorageContentVaultStore, String) -> Unit = { _, _ -> },
        beforeRead: suspend (String) -> Unit = {}, test: suspend (Vault) -> Unit) {
        val store = TestStorageContentVaultStore()
        val settings = createInMemorySettings()
        val provider = store.provider(afterWrite = { afterWrite(store, it) }, beforeRead = beforeRead)
        val access = StorageContentVaultAccess(provider, StoragePath("/"), settings, "owner")
        val unlocked = access.initializeOrUnlock("vault-commit-password".toCharArray())
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("vault-commit-test-" + Uuid.random())
        StorageContentFileProtection.createPrivateDirectory(directory)
        try { test(Vault(store, access, unlocked, directory, settings)) }
        finally { unlocked.key.fill(0); fs.deleteRecursively(directory) }
    }

    private fun file(vault: Vault, name: String, text: String = "authenticated content",
        entryId: String = Uuid.random().toString(), parents: List<String> = emptyList()): Pair<StorageContentCommit, String> {
        val objectId = Uuid.random().toString()
        val source = text.encodeToByteArray()
        val path = StorageContentFileProtection.createPrivateTempFile(vault.directory, ".cipher-")
        fs.sink(path).buffer().use {
            StorageContentEncryptionV1.encrypt(Buffer().write(source), it, vault.unlocked.key,
                storageContentObjectScope(vault.unlocked.vaultId, objectId))
        }
        val ciphertext = fs.read(path) { readByteArray() }
        return StorageContentCommit(vaultId = vault.unlocked.vaultId, operationId = Uuid.random().toString(),
            entryId = entryId, parentId = vault.unlocked.vaultId, name = name, parents = parents,
            kind = StorageContentCommitKind.File, objectId = objectId, ciphertextLength = ciphertext.size.toLong(),
            ciphertextDigest = StorageContentCrypto.sha256(ciphertext).toByteString().hex(),
            size = source.size.toLong()) to path.toString()
    }

    @Test
    fun independentObjectAndRecordAreVerifiedAndOrdinaryFilesAreNeverReplaced() = runSuspendTest {
        withVault { vault ->
            vault.store.files["/one.txt"] = "ordinary original".encodeToByteArray()
            val (record, path) = file(vault, "one.txt")
            vault.access.publish(record, vault.unlocked.key, path)
            assertEquals(listOf(record), vault.access.history(record.vaultId, vault.unlocked.key).liveEntries())
            assertEquals("ordinary original", vault.store.files.getValue("/one.txt").decodeToString())
            assertTrue(vault.store.files.keys.any { it.endsWith(".object") })
            assertTrue(vault.store.files.keys.any { it.endsWith(".commit") })
        }
    }

    @Test
    fun historyReadsRootOnceAndBoundsConcurrentFreshVerification() = runSuspendTest {
        var measuring = false
        var active = 0
        var peak = 0
        val reads = mutableMapOf<String, Int>()
        withVault(beforeRead = { path ->
            if (measuring) {
                reads[path] = (reads[path] ?: 0) + 1
                active++
                peak = maxOf(peak, active)
                try { yield() } finally { active-- }
            }
        }) { vault ->
            repeat(5) { index ->
                val (record, path) = file(vault, "file-$index")
                vault.access.publish(record, vault.unlocked.key, path)
            }
            measuring = true
            assertEquals(5, vault.access.history(vault.unlocked.vaultId, vault.unlocked.key).liveEntries().size)
            assertEquals(1, reads.filterKeys { it.endsWith(".root") }.values.sum())
            assertEquals(5, reads.filterKeys { it.endsWith(".commit") }.values.sum())
            assertEquals(5, reads.filterKeys { it.endsWith(".object") }.values.sum())
            assertEquals(4, peak)
            assertEquals(0, active)

            val checkpoint = vault.settings.getString("auth.storage.password-location.checkpoint." +
                StorageContentCrypto.sha256(vault.unlocked.vaultId.encodeToByteArray()).toByteString().hex(), "")
            val objectPath = vault.store.files.keys.first { it.endsWith(".object") }
            val original = vault.store.files.getValue(objectPath)
            vault.store.files[objectPath] = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            assertFails { vault.access.history(vault.unlocked.vaultId, vault.unlocked.key) }
            assertEquals(checkpoint, vault.settings.getString("auth.storage.password-location.checkpoint." +
                StorageContentCrypto.sha256(vault.unlocked.vaultId.encodeToByteArray()).toByteString().hex(), ""))
            assertEquals(0, active)
        }
    }

    @Test
    fun metadataAuthenticatesRecordsWithoutReadingUnrelatedContentAndFullVerificationStillFailsDamage() = runSuspendTest {
        val reads = mutableListOf<String>()
        withVault(beforeRead = { reads += it }) { vault ->
            val (record, path) = file(vault, "file")
            vault.access.publish(record, vault.unlocked.key, path)
            val objectPath = vault.store.files.keys.single { it.endsWith(".object") }
            val bytes = vault.store.files.getValue(objectPath)
            vault.store.files[objectPath] = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            reads.clear()
            assertEquals(listOf(record), vault.access.metadataHistory(record.vaultId, vault.unlocked.key).liveEntries())
            assertEquals(0, reads.count { it.endsWith(".object") })
            assertEquals(1, reads.count { it.endsWith(".commit") })
            assertFails { vault.access.history(record.vaultId, vault.unlocked.key) }
            assertFails { vault.access.verifyObject(record) }
            assertFails { vault.access.publish(record, vault.unlocked.key, path) }

            vault.store.files[objectPath] = bytes.dropLast(1).toByteArray()
            assertFails { vault.access.metadataHistory(record.vaultId, vault.unlocked.key) }
            vault.store.files.remove(objectPath)
            assertFails { vault.access.metadataHistory(record.vaultId, vault.unlocked.key) }
            vault.store.files[objectPath] = bytes
            assertEquals(listOf(record), vault.access.history(record.vaultId, vault.unlocked.key).liveEntries())

            val commitPath = vault.store.files.keys.single { it.endsWith(".commit") }
            val commit = vault.store.files.getValue(commitPath)
            vault.store.files[commitPath] = commit.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            assertFails { vault.access.metadataHistory(record.vaultId, vault.unlocked.key) }
            vault.store.files[commitPath] = commit
            vault.store.files.remove(commitPath)
            assertFails { vault.access.metadataHistory(record.vaultId, vault.unlocked.key) }
        }
    }

    @Test
    fun publicationReusesItsVerifiedScanAndStillChecksTheObjectAfterTheCommit() = runSuspendTest {
        withVault { vault ->
            val (record, path) = file(vault, "selected")
            vault.store.provider().upload(path, StoragePath("/.folderspan-vault/" + record.objectId + ".object"),
                record.ciphertextLength).getOrThrow()
            val provider = vault.store.provider()
            var lists = 0
            val counted = object : StorageProvider by provider,
                ChunkReadableNetworkClient by (provider as ChunkReadableNetworkClient) {
                override suspend fun list(path: StoragePath, requestId: String?, batchId: String?): Result<List<NetworkFileEntry>> {
                    lists++
                    return provider.list(path, requestId, batchId)
                }
            }
            val access = StorageContentVaultAccess(counted, StoragePath("/"), vault.settings, "counted")
            access.publish(record, vault.unlocked.key)
            assertTrue(lists <= 8, "Publication should not rescan between a verified view and its synchronous checks: $lists")
            assertEquals(listOf(record), access.history(record.vaultId, vault.unlocked.key).liveEntries())
        }
        withVault(afterWrite = { store, path ->
            if (path.endsWith(".commit")) {
                val name = store.files.keys.single { it.endsWith(".object") }
                store.files[name] = store.files.getValue(name).copyOf().also {
                    it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                }
            }
        }) { vault ->
            val (record, path) = file(vault, "changed-during-publication")
            assertFails { vault.access.publish(record, vault.unlocked.key, path) }
            assertTrue(vault.store.files.keys.any { it.endsWith(".commit") })
            assertFails { vault.access.publish(record, vault.unlocked.key, path) }
            assertFails { vault.access.history(record.vaultId, vault.unlocked.key) }
        }
    }

    private fun addDirectoryRecord(vault: Vault, name: String) {
        val record = StorageContentCommit(vaultId = vault.unlocked.vaultId,
            operationId = Uuid.random().toString(), entryId = Uuid.random().toString(),
            parentId = vault.unlocked.vaultId, name = name, kind = StorageContentCommitKind.Directory)
        vault.store.files["/.folderspan-vault/" + record.operationId + ".commit"] =
            StorageContentCommitCodec.encode(record, vault.unlocked.key)
    }

    @Test
    fun metadataBatchesAreBoundedAndAuthenticateEveryCurrentRecordAcrossTheBoundary() = runSuspendTest {
        withVault { vault ->
            repeat(33) { addDirectoryRecord(vault, "directory-$it") }
            val provider = vault.store.provider()
            val batches = mutableListOf<List<String>>()
            val batched = object : StorageProvider by provider,
                ChunkReadableNetworkClient by (provider as ChunkReadableNetworkClient) {
                override suspend fun downloadContentMetadata(directory: StoragePath, entries: List<NetworkFileEntry>,
                    maxEntryBytes: Int): Result<Map<String, ByteArray>?> = storageResult {
                    assertEquals("/.folderspan-vault", directory.path)
                    assertEquals(65584, maxEntryBytes)
                    batches += entries.map { it.name }
                    entries.associate { it.name to vault.store.files.getValue(it.path).copyOf() }
                }
            }
            val access = StorageContentVaultAccess(batched, StoragePath("/"), vault.settings, "batch")
            assertEquals(33, access.metadataHistory(vault.unlocked.vaultId, vault.unlocked.key).liveEntries().size)
            assertEquals(listOf(32, 3), batches.map { it.size })
            assertEquals(35, batches.flatten().toSet().size)
            val last = vault.store.files.keys.last { it.endsWith(".commit") }
            vault.store.files[last] = vault.store.files.getValue(last).copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            assertFails { access.metadataHistory(vault.unlocked.vaultId, vault.unlocked.key) }
        }
    }

    @Test
    fun invalidOrCancelledMetadataBatchesNeverFallBackOrAdvanceTheCheckpoint() = runSuspendTest {
        withVault { vault ->
            addDirectoryRecord(vault, "observed")
            vault.access.metadataHistory(vault.unlocked.vaultId, vault.unlocked.key)
            val checkpointName = "auth.storage.password-location.checkpoint." +
                StorageContentCrypto.sha256(vault.unlocked.vaultId.encodeToByteArray()).toByteString().hex()
            val checkpoint = vault.settings.getString(checkpointName, "")
            addDirectoryRecord(vault, "new")
            val provider = vault.store.provider(beforeRead = { error("Batch errors must not fall back to individual reads") })
            var fault = ""
            val batched = object : StorageProvider by provider,
                ChunkReadableNetworkClient by (provider as ChunkReadableNetworkClient) {
                override suspend fun downloadContentMetadata(directory: StoragePath, entries: List<NetworkFileEntry>,
                    maxEntryBytes: Int): Result<Map<String, ByteArray>?> = storageResult {
                    if (fault == "cancel") throw CancellationException("injected batch cancellation")
                    if (fault == "error") error("injected batch error")
                    val bodies = entries.associate { it.name to vault.store.files.getValue(it.path).copyOf() }.toMutableMap()
                    val target = bodies.keys.first { it.endsWith(if (fault == "descriptor") ".location" else
                        if (fault == "root") ".root" else ".commit") }
                    when (fault) {
                        "missing" -> bodies.remove(target)
                        "unexpected" -> bodies["unexpected"] = byteArrayOf()
                        "truncated" -> bodies[target] = bodies.getValue(target).dropLast(1).toByteArray()
                        else -> bodies.getValue(target).let { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                    }
                    bodies
                }
            }
            val access = StorageContentVaultAccess(batched, StoragePath("/"), vault.settings, "batch-fault")
            for (injected in listOf("missing", "unexpected", "truncated", "descriptor", "root", "commit", "error", "cancel")) {
                fault = injected
                if (fault == "cancel") assertFailsWith<CancellationException> {
                    access.metadataHistory(vault.unlocked.vaultId, vault.unlocked.key)
                } else assertFails { access.metadataHistory(vault.unlocked.vaultId, vault.unlocked.key) }
                assertEquals(checkpoint, vault.settings.getString(checkpointName, ""), fault)
            }
        }
    }

    @Test
    fun cancelledParallelVerificationJoinsReadersWithoutAdvancingCheckpoint() = runSuspendTest {
        var interrupt = false
        var active = 0
        withVault(beforeRead = { path ->
            if (interrupt && path.endsWith(".commit")) {
                active++
                try {
                    yield()
                    throw CancellationException("injected concurrent verification cancellation")
                } finally { active-- }
            }
        }) { vault ->
            repeat(5) { index ->
                val (record, path) = file(vault, "file-$index")
                vault.access.publish(record, vault.unlocked.key, path)
            }
            val checkpointName = "auth.storage.password-location.checkpoint." +
                StorageContentCrypto.sha256(vault.unlocked.vaultId.encodeToByteArray()).toByteString().hex()
            val checkpoint = vault.settings.getString(checkpointName, "")
            interrupt = true
            assertFailsWith<CancellationException> { vault.access.history(vault.unlocked.vaultId, vault.unlocked.key) }
            assertEquals(0, active)
            assertEquals(checkpoint, vault.settings.getString(checkpointName, ""))
            interrupt = false
            assertEquals(5, vault.access.history(vault.unlocked.vaultId, vault.unlocked.key).liveEntries().size)
        }
    }

    @Test
    fun lostResponsesReconcileOriginalOperationWithoutReuploadingOrDeletingAnyFile() = runSuspendTest {
        for ((suffix, cancellation) in listOf(".object" to false, ".commit" to false, ".object" to true, ".commit" to true)) {
            var interrupted = false
            withVault(afterWrite = { _, path ->
                if (!interrupted && path.endsWith(suffix)) {
                    interrupted = true
                    if (cancellation) throw CancellationException("injected publication cancellation")
                    error("injected response loss")
                }
            }) { vault ->
                val (record, path) = file(vault, "retry")
                assertFails { vault.access.publish(record, vault.unlocked.key, path) }
                val before = vault.store.snapshot()
                vault.access.publish(record, vault.unlocked.key, path)
                before.forEach { (name, bytes) -> assertEquals(bytes, vault.store.snapshot()[name]) }
                assertEquals(4, vault.store.writes)
                assertEquals(listOf(record), vault.access.history(record.vaultId, vault.unlocked.key).liveEntries())
            }
        }
    }

    @Test
    fun truncatedObjectsStayInvisibleAndTruncatedCommitsFailInsteadOfBecomingEmptyHistory() = runSuspendTest {
        for (suffix in listOf(".object", ".commit")) {
            withVault(afterWrite = { store, path ->
                if (path.endsWith(suffix)) store.files[path] = store.files.getValue(path).dropLast(1).toByteArray()
            }) { vault ->
                vault.store.files["/original"] = byteArrayOf(99)
                val (record, path) = file(vault, "damaged")
                assertFails { vault.access.publish(record, vault.unlocked.key, path) }
                val writes = vault.store.writes
                if (suffix == ".object") {
                    assertFalse(vault.store.files.keys.any { it.endsWith(".commit") })
                    assertEquals(emptyList(), vault.access.history(record.vaultId, vault.unlocked.key).liveEntries())
                } else assertFails { vault.access.history(record.vaultId, vault.unlocked.key) }
                assertFails { vault.access.publish(record, vault.unlocked.key, path) }
                assertEquals(writes, vault.store.writes)
                assertEquals(listOf(99.toByte()), vault.store.files.getValue("/original").toList())
            }
        }
    }

    @Test
    fun concurrentDifferentEntriesMergeWhileSameEntryUpdatesAndDeletionPreserveBranches() = runSuspendTest {
        withVault(afterWrite = { _, _ -> yield() }) { vault ->
            val a = file(vault, "a")
            val b = file(vault, "b")
            coroutineScope {
                listOf(a, b).map { (record, path) ->
                    async { vault.access.publish(record, vault.unlocked.key, path) }
                }.awaitAll()
            }
            assertEquals(setOf("a", "b"), vault.access.history(a.first.vaultId, vault.unlocked.key).liveEntries().map { it.name }.toSet())
            val updated = file(vault, "a", "new revision", a.first.entryId, listOf(a.first.operationId))
            val deletion = a.first.copy(operationId = Uuid.random().toString(), parents = listOf(a.first.operationId),
                kind = StorageContentCommitKind.Delete, objectId = "", ciphertextLength = 0, ciphertextDigest = "", size = 0)
            coroutineScope {
                listOf(
                    async { runCatching { vault.access.publish(updated.first, vault.unlocked.key, updated.second) } },
                    async { runCatching { vault.access.publish(deletion, vault.unlocked.key) } },
                ).awaitAll()
            }
            val history = vault.access.history(a.first.vaultId, vault.unlocked.key)
            assertEquals(setOf(updated.first.operationId, deletion.operationId), history.heads.getValue(a.first.entryId).map { it.operationId }.toSet())
            assertFailsWith<StorageContentRevisionConflictException> { history.liveEntries() }
        }
    }

    @Test
    fun deletingTheMemoryCacheRebuildsFromRemoteAndSameLengthTamperingStillFails() = runSuspendTest {
        withVault { vault ->
            val (record, path) = file(vault, "cached")
            vault.access.publish(record, vault.unlocked.key, path)
            val name = "/.folderspan-vault/" + record.operationId + ".commit"
            val original = vault.store.files.getValue(name)
            vault.store.files[name] = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            assertFails { vault.access.history(record.vaultId, vault.unlocked.key) }
            vault.store.files[name] = original
            val rebuilt = StorageContentVaultAccess(vault.store.provider(), StoragePath("/"), vault.settings, "new-cache")
            assertEquals(listOf(record), rebuilt.history(record.vaultId, vault.unlocked.key).liveEntries())
            assertEquals(listOf(record), vault.access.history(record.vaultId, vault.unlocked.key).liveEntries())
        }
    }

    @Test
    fun aStaleConcurrentViewCannotReplaceANewerObservedCheckpoint() = runSuspendTest {
        lateinit var observed: Vault
        lateinit var additional: Pair<StorageContentCommit, String>
        var active = false
        var injected = false
        withVault(beforeRead = { path ->
            if (active && !injected && path.endsWith(".object")) {
                injected = true
                val second = StorageContentVaultAccess(observed.store.provider(), StoragePath("/"), observed.settings, "second")
                second.publish(additional.first, observed.unlocked.key, additional.second)
            }
        }) { vault ->
            observed = vault
            val (first, path) = file(vault, "first")
            vault.access.publish(first, vault.unlocked.key, path)
            additional = file(vault, "second")
            active = true
            assertFails { vault.access.history(first.vaultId, vault.unlocked.key) }
            assertTrue(injected)
            assertEquals(setOf("first", "second"),
                vault.access.history(first.vaultId, vault.unlocked.key).liveEntries().map { it.name }.toSet())
        }
    }

    @Test
    fun observedHistoryCannotRollBackButFreshHostsMakeNoClaimAboutHiddenUnknownHistory() = runSuspendTest {
        withVault { vault ->
            val (record, path) = file(vault, "one")
            vault.access.publish(record, vault.unlocked.key, path)
            val name = "/.folderspan-vault/" + record.operationId + ".commit"
            val bytes = vault.store.files.remove(name)!!
            assertFails { vault.access.history(record.vaultId, vault.unlocked.key) }
            val fresh = StorageContentVaultAccess(vault.store.provider(), StoragePath("/"), createInMemorySettings(), "fresh")
            assertEquals(emptyList(), fresh.history(record.vaultId, vault.unlocked.key).liveEntries())
            vault.store.files[name] = bytes
            assertEquals(listOf(record), fresh.history(record.vaultId, vault.unlocked.key).liveEntries())
            assertEquals(listOf(record), vault.access.history(record.vaultId, vault.unlocked.key).liveEntries())
        }
    }
}

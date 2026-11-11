package com.folderspan.service.network

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.StorageContentLocationReference
import com.folderspan.test.createInMemorySettings
import com.folderspan.test.runSuspendTest
import okio.ByteString.Companion.toByteString
import kotlin.test.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class StorageContentLogicalAccessTest {
    private class Fixture {
        val store = TestStorageContentVaultStore().apply {
            directories.add("/.folderspan-vault")
            files["/.folderspan-vault/$VAULT.location"] = descriptor.copyOf()
            files["/.folderspan-vault/$VAULT.root"] = StorageContentCommitCodec.encode(
                StorageContentCommit(vaultId = VAULT, operationId = VAULT, entryId = VAULT, parentId = "",
                    name = "", kind = StorageContentCommitKind.Root, ciphertextDigest = hash(descriptor)), KEY)
        }
        val provider = store.provider()
        val reference = StorageContentLocationReference(enabled = true, vaultId = VAULT)
        val access = StorageContentVaultAccess(provider, reference.root, createInMemorySettings(), "logical-test")
        val logical = StorageContentLogicalAccess(provider, reference, access, KEY)

        fun file(path: String, value: String) { store.files[path] = value.encodeToByteArray() }
        fun directory(path: String) { store.directories.add(path) }
        suspend fun managed(path: String, size: Long = 7): StorageContentCommit {
            val objectId = Uuid.random().toString()
            val ciphertext = ByteArray(160) { it.toByte() }
            val record = logical.prepareFile(StoragePath(path), Uuid.random().toString(), objectId,
                ciphertext.size.toLong(), hash(ciphertext), size, 123)
            store.files["/.folderspan-vault/$objectId.object"] = ciphertext
            access.publish(record, KEY)
            return record
        }
        fun logical(provider: StorageProvider) = StorageContentLogicalAccess(provider, reference,
            StorageContentVaultAccess(provider, reference.root, createInMemorySettings(), "another-local-owner"), KEY)
    }

    @Test
    fun mergedListingClassifiesFromAuthenticatedRecordsAndMapsManagedObjects() = runSuspendTest {
        val f = Fixture()
        f.file("/plain.txt", "FSPANENC ordinary bytes")
        val record = f.managed("/encrypted.txt")
        val entries = f.logical.list(StoragePath("/"))
        assertEquals(listOf("encrypted.txt", "plain.txt"), entries.map { it.name })
        assertEquals(7L, entries.first().size)
        val managed = f.logical.resolveDownload(info(entries.first()))
        assertEquals("/.folderspan-vault/${record.objectId}.object", managed.target.path)
        assertEquals(160L, managed.size)
        assertEquals(record.operationId, managed.file.storageReference?.content?.revisionId)
        val ordinary = f.logical.resolveDownload(info(entries.last()))
        assertEquals("/plain.txt", ordinary.target.path)
        assertEquals("plain.txt", ordinary.file.storageReference?.content?.ordinaryPath)
        assertEquals(f.store.files.getValue("/plain.txt").size.toLong(), ordinary.size)
        assertNull(f.logical.stat(StoragePath("/missing")))
        assertTrue(f.logical.contains(StoragePath("/ordinary")))
        assertFalse(f.logical.contains(StoragePath("/ordinary", StorageReference(content =
            StorageContentEntryReference(Uuid.random().toString(), VAULT)))))
    }

    @Test
    fun metadataListingsAndDownloadResolutionNeverReadCiphertextObjects() = runSuspendTest {
        val f = Fixture()
        f.managed("/first.txt")
        f.managed("/second.txt")
        val reads = mutableListOf<String>()
        val logical = f.logical(f.store.provider(beforeRead = { path ->
            reads += path
            if (path.endsWith(".object")) fail("Metadata reads must not download ciphertext")
        }))
        val listed = logical.list(StoragePath("/"))
        assertEquals(setOf("first.txt", "second.txt"), listed.map { it.name }.toSet())
        val selected = listed.single { it.name == "first.txt" }
        assertEquals(selected, logical.stat(StoragePath(selected.path, selected.reference)))
        assertEquals(selected.reference?.content?.revisionId,
            logical.resolveDownload(info(selected)).file.storageReference?.content?.revisionId)
        assertTrue(reads.any { it.endsWith(".commit") })
        assertFalse(reads.any { it.endsWith(".object") })
    }

    @Test
    fun unrelatedSameLengthDamageDoesNotBlockNewFilesOrSelectedRenameAndDelete() = runSuspendTest {
        val f = Fixture()
        val selected = f.managed("/selected.txt")
        val unrelated = f.managed("/unrelated.txt")
        val unrelatedPath = "/.folderspan-vault/${unrelated.objectId}.object"
        f.store.files[unrelatedPath] = f.store.files.getValue(unrelatedPath).copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        val reads = mutableListOf<String>()
        val logical = f.logical(f.store.provider(beforeRead = { reads += it }))
        val writes = f.store.writes
        val prepared = logical.prepareFile(StoragePath("/new.txt"), Uuid.random().toString(), Uuid.random().toString(),
            160, hash(ByteArray(160)), 10, 456)
        assertTrue(prepared.parents.isEmpty())
        assertEquals(writes, f.store.writes)
        assertFalse(reads.any { it.endsWith(".object") })
        assertTrue(logical.rename(StoragePath("/selected.txt"), StoragePath("/renamed.txt")))
        assertTrue(logical.delete(StoragePath("/renamed.txt"), false))
        assertTrue(reads.any { it == "/.folderspan-vault/${selected.objectId}.object" })
        assertFalse(unrelatedPath in reads)
        assertEquals(listOf("unrelated.txt"), logical.list(StoragePath("/")).map { it.name })
        assertTrue(unrelatedPath in f.store.files)
    }

    @Test
    fun overwritesRenamesAndDeletesRejectDamagedSelectedObjectsWithoutWrites() = runSuspendTest {
        for (operation in listOf("overwrite", "rename", "delete")) {
            val f = Fixture()
            val selected = f.managed("/selected.txt")
            val path = "/.folderspan-vault/${selected.objectId}.object"
            f.store.files[path] = f.store.files.getValue(path).copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            val before = f.store.snapshot()
            val writes = f.store.writes
            assertFails(operation) {
                when (operation) {
                    "overwrite" -> f.logical.prepareFile(StoragePath("/selected.txt"), Uuid.random().toString(),
                        Uuid.random().toString(), 160, hash(ByteArray(160)), 10, 456)
                    "rename" -> f.logical.rename(StoragePath("/selected.txt"), StoragePath("/renamed.txt"))
                    else -> f.logical.delete(StoragePath("/selected.txt"), false)
                }
            }
            assertEquals(before, f.store.snapshot(), operation)
            assertEquals(writes, f.store.writes, operation)
        }
    }

    @Test
    fun directoryRenameAndDeleteVerifySelectedSubtreeBeforeAnyOrdinaryDataIsRemoved() = runSuspendTest {
        for (operation in listOf("rename", "delete")) {
            val f = Fixture()
            f.directory("/parent")
            f.file("/parent/ordinary.txt", "preserve ordinary sibling")
            val child = f.managed("/parent/child.txt")
            val path = "/.folderspan-vault/${child.objectId}.object"
            f.store.files[path] = f.store.files.getValue(path).copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            val before = f.store.snapshot()
            val writes = f.store.writes
            assertFails(operation) {
                if (operation == "rename") f.logical.rename(StoragePath("/parent"), StoragePath("/renamed"))
                else f.logical.delete(StoragePath("/parent"), true)
            }
            assertEquals(before, f.store.snapshot(), operation)
            assertEquals(writes, f.store.writes, operation)
            assertTrue("/parent" in f.store.directories, operation)
        }
    }

    @Test
    fun directoryRenameCarriesOrdinaryChildrenWithoutPhysicalRenameOrLocalIdentity() = runSuspendTest {
        val f = Fixture()
        f.directory("/old")
        f.directory("/old/sub")
        f.file("/old/plain.txt", "plain")
        f.file("/old/sub/nested.txt", "nested")
        f.managed("/old/encrypted.txt")
        val listedBeforeRename = f.logical.list(StoragePath("/old")).single { it.name == "plain.txt" }
        val beforeObjects = f.store.files.filterKeys { it.endsWith(".object") }.mapValues { it.value.toList() }
        assertTrue(f.logical.rename(StoragePath("/old"), StoragePath("/renamed")))
        assertEquals(listOf("renamed"), f.logical.list(StoragePath("/")).map { it.name })
        assertTrue("/old" in f.store.directories)
        assertFalse("/renamed" in f.store.directories)
        val reopened = f.logical(f.provider)
        val plain = reopened.list(StoragePath("/renamed")).single { it.name == "plain.txt" }
        assertEquals("/renamed/plain.txt", plain.path)
        assertEquals("/old/plain.txt", reopened.resolveDownload(info(plain)).target.path)
        val followed = reopened.resolveDownload(info(listedBeforeRename))
        assertEquals("/renamed/plain.txt", followed.file.path)
        assertEquals("/old/plain.txt", followed.target.path)
        assertEquals(listedBeforeRename.reference?.id, plain.reference?.id)
        val nested = reopened.list(StoragePath("/renamed/sub")).single()
        assertEquals("/old/sub/nested.txt", reopened.resolveDownload(info(nested)).target.path)
        assertEquals(beforeObjects, f.store.files.filterKeys { it.endsWith(".object") }.mapValues { it.value.toList() })
        val binding = f.access.history(VAULT, KEY).liveEntries().single { it.name == "renamed" }.ordinaryDirectory
        assertEquals("old", binding?.relativePath)
        assertNull(binding?.reference?.content)
        assertTrue(binding?.reference?.ancestorPath.isNullOrEmpty())
    }

    @Test
    fun virtualDirectoriesMaterializeBackingOnlyForOrdinaryWritesAndKeepItAfterRename() = runSuspendTest {
        val f = Fixture()
        f.logical.createDirectory(StoragePath("/virtual"), encrypted = true)
        f.logical.createDirectory(StoragePath("/virtual/child"), encrypted = true)
        assertFalse("/virtual" in f.store.directories)
        assertTrue(f.logical.list(StoragePath("/virtual/child")).isEmpty())
        val target = f.logical.resolveOrdinaryUpload(StoragePath("/virtual/child/plain.txt"))
        assertEquals("/virtual/child/plain.txt", target.path)
        f.file(target.path, "ordinary")
        f.logical.rename(StoragePath("/virtual"), StoragePath("/moved"))
        val child = f.logical.list(StoragePath("/moved/child")).single()
        assertEquals("/moved/child/plain.txt", child.path)
        assertEquals(target.path, f.logical.resolveDownload(info(child)).target.path)
        assertEquals(listOf("moved"), f.logical.list(StoragePath("/")).map { it.name })
    }

    @Test
    fun ordinaryAndManagedNameCollisionsAndStaleRevisionsFailWithoutWrites() = runSuspendTest {
        val f = Fixture()
        f.file("/plain.txt", "ordinary")
        var writes = f.store.writes
        assertConflict { f.managed("/plain.txt") }
        assertEquals(writes, f.store.writes)
        f.managed("/managed.txt")
        val selected = f.logical.list(StoragePath("/")).single { it.name == "managed.txt" }
        writes = f.store.writes
        assertConflict { f.logical.resolveOrdinaryUpload(StoragePath("/managed.txt")) }
        assertEquals(writes, f.store.writes)
        assertEquals("fsp-content:$VAULT:" + selected.reference?.content?.entryId, selected.reference?.id)
        assertEquals("fsp-content:$VAULT:$VAULT", selected.reference?.parentId)
        f.logical.rename(StoragePath(selected.path, selected.reference), StoragePath("/renamed.txt"))
        val renamed = f.logical.resolveDownload(info(selected))
        assertEquals("/renamed.txt", renamed.file.path)
        assertEquals(selected.reference?.id, renamed.file.storageReference?.id)
        f.managed("/renamed.txt", size = 8)
        val failure = assertFailsWith<StorageContentException> { f.logical.resolveDownload(info(selected)) }
        assertEquals(StorageContentErrorCode.SourceChanged, failure.code)
        f.file("/renamed.txt", "unexpected ordinary collision")
        assertConflict { f.logical.list(StoragePath("/")) }
    }

    @Test
    fun deletionRemovesOrdinaryDescendantsTombstonesManagedEntriesAndPreservesMovedBindings() = runSuspendTest {
        val f = Fixture()
        f.directory("/outer")
        f.directory("/outer/sub")
        f.directory("/outer/shared")
        f.file("/outer/plain.txt", "ordinary")
        f.file("/outer/sub/nested.txt", "nested")
        f.file("/outer/shared/safe.txt", "safe")
        val encrypted = f.managed("/outer/encrypted.txt")
        f.logical.rename(StoragePath("/outer/shared"), StoragePath("/safe"))
        assertTrue(f.logical.delete(StoragePath("/outer"), true))
        assertEquals(listOf("safe"), f.logical.list(StoragePath("/")).map { it.name })
        assertFalse("/outer/plain.txt" in f.store.files)
        assertFalse("/outer/sub/nested.txt" in f.store.files)
        assertFalse("/outer/sub" in f.store.directories)
        assertTrue("/outer/shared/safe.txt" in f.store.files)
        assertTrue("/.folderspan-vault/${encrypted.objectId}.object" in f.store.files)
        assertEquals(StorageContentCommitKind.Delete, f.access.history(VAULT, KEY).head(encrypted.entryId)?.kind)
        assertTrue(f.logical.delete(StoragePath("/safe"), true))
        assertTrue(f.logical.list(StoragePath("/")).isEmpty())
        f.file("/outer/new.txt", "new data after deletion")
        assertConflict { f.logical.list(StoragePath("/")) }
        assertEquals("new data after deletion", f.store.files.getValue("/outer/new.txt").decodeToString())
    }

    @Test
    fun deletionPreflightRejectsUnknownNamespacesBeforeRemovingAnyOrdinaryData() = runSuspendTest {
        val f = Fixture()
        f.directory("/parent")
        f.directory("/parent/.folderspan-vault")
        f.file("/parent/plain.txt", "preserve")
        val before = f.store.snapshot()
        val writes = f.store.writes
        assertConflict { f.logical.delete(StoragePath("/parent"), true) }
        assertEquals(before, f.store.snapshot())
        assertEquals(writes, f.store.writes)
    }

    @Test
    fun newDataDuringDeletionRemainsVisibleAndPreventsDirectoryTombstone() = runSuspendTest {
        val f = Fixture()
        f.directory("/parent")
        f.file("/parent/old.txt", "old")
        val wrapped = object : StorageProvider by f.provider,
            ChunkReadableNetworkClient by (f.provider as ChunkReadableNetworkClient) {
            override suspend fun delete(path: StoragePath, isDirectory: Boolean): Result<Boolean> {
                val result = f.provider.delete(path, isDirectory)
                if (path.path == "/parent/old.txt") f.file("/parent/new.txt", "new concurrent data")
                return result
            }
        }
        val logical = f.logical(wrapped)
        val failure = assertFailsWith<StorageContentException> { logical.delete(StoragePath("/parent"), true) }
        assertEquals(StorageContentErrorCode.SourceChanged, failure.code)
        assertEquals(listOf("parent"), logical.list(StoragePath("/")).map { it.name })
        assertEquals(listOf("new.txt"), logical.list(StoragePath("/parent")).map { it.name })
        assertTrue(f.access.history(VAULT, KEY).liveEntries().any { it.name == "parent" })
    }

    @Test
    fun replacingAnExactNativeDirectoryIdentityIsNeverHiddenAsItsOldBinding() = runSuspendTest {
        val f = Fixture()
        f.directory("/ordinary")
        var directoryId = "original-native-directory"
        val wrapped = object : StorageProvider by f.provider,
            ChunkReadableNetworkClient by (f.provider as ChunkReadableNetworkClient) {
            override suspend fun list(path: StoragePath, requestId: String?, batchId: String?): Result<List<NetworkFileEntry>> =
                f.provider.list(path, requestId, batchId).map { entries ->
                    entries.map { if (it.path == "/ordinary") it.copy(reference = StorageReference(id = directoryId)) else it }
                }
        }
        val logical = f.logical(wrapped)
        logical.createDirectory(StoragePath("/ordinary/virtual"), encrypted = true)
        assertEquals(listOf("ordinary"), logical.list(StoragePath("/")).map { it.name })
        directoryId = "replacement-native-directory"
        assertConflict { logical.list(StoragePath("/")) }
    }

    @Test
    fun ordinaryReferenceListedBeforeParentBindingFollowsItsLaterLogicalRename() = runSuspendTest {
        val f = Fixture()
        f.directory("/original")
        f.file("/original/plain.txt", "ordinary")
        val beforeBinding = f.logical.list(StoragePath("/original")).single()
        assertEquals(VAULT, beforeBinding.reference?.content?.entryId)
        f.logical.rename(StoragePath("/original"), StoragePath("/renamed"))
        val followed = f.logical.resolveDownload(info(beforeBinding))
        assertEquals("/renamed/plain.txt", followed.file.path)
        assertEquals("/original/plain.txt", followed.target.path)
        // A newly derived path is a new logical intent, rather than an old physical locator.
        val root = f.logical.stat(StoragePath("/"))!!
        val freshTarget = StoragePath("/original/new.txt", root.reference!!.forPath("/", "/original/new.txt", true))
        assertFails { f.logical.resolveOrdinaryUpload(freshTarget) }
    }

    @Test
    fun ordinaryNativeReplacementAndMetadataChangesRejectTheFrozenSource() = runSuspendTest {
        val f = Fixture()
        f.file("/plain.txt", "first")
        var nativeId = "original-native-file"
        var updated = 100L
        val wrapped = object : StorageProvider by f.provider,
            ChunkReadableNetworkClient by (f.provider as ChunkReadableNetworkClient) {
            override suspend fun list(path: StoragePath, requestId: String?, batchId: String?): Result<List<NetworkFileEntry>> =
                f.provider.list(path, requestId, batchId).map { entries ->
                    entries.map { if (it.path == "/plain.txt") it.copy(updatedDate = updated,
                        reference = StorageReference(id = nativeId)) else it }
                }
        }
        val logical = f.logical(wrapped)
        val frozen = info(logical.list(StoragePath("/")).single())
        nativeId = "replacement-native-file"
        assertEquals(StorageContentErrorCode.SourceChanged,
            assertFailsWith<StorageContentException> { logical.resolveDownload(frozen) }.code)
        nativeId = "original-native-file"
        updated = 101
        assertEquals(StorageContentErrorCode.SourceChanged,
            assertFailsWith<StorageContentException> { logical.resolveDownload(frozen) }.code)
        updated = 100
        f.file("/plain.txt", "a longer replacement")
        assertEquals(StorageContentErrorCode.SourceChanged,
            assertFailsWith<StorageContentException> { logical.resolveDownload(frozen) }.code)
    }

    @Test
    fun excessivelyDeepPathsFailBeforeRecursiveTraversalOrWrites() = runSuspendTest {
        val f = Fixture()
        val writes = f.store.writes
        assertConflict { f.logical.stat(StoragePath("/" + List(257) { "d" }.joinToString("/"))) }
        assertEquals(writes, f.store.writes)
    }

    @Test
    fun nativeDirectoryAliasesNeverBecomeASecondLogicalDirectory() = runSuspendTest {
        val f = Fixture()
        f.directory("/physical")
        val wrapped = object : StorageProvider by f.provider,
            ChunkReadableNetworkClient by (f.provider as ChunkReadableNetworkClient) {
            override suspend fun list(path: StoragePath, requestId: String?, batchId: String?): Result<List<NetworkFileEntry>> =
                f.provider.list(path, requestId, batchId).map { entries -> entries.map {
                    if (it.path in listOf("/physical", "/externally-renamed")) it.copy(reference = StorageReference(id = "same-native-directory")) else it
                } }
        }
        val logical = f.logical(wrapped)
        logical.createDirectory(StoragePath("/physical/virtual"), encrypted = true)
        f.store.directories.remove("/physical")
        f.store.directories.add("/externally-renamed")
        assertEquals(listOf("physical"), logical.list(StoragePath("/")).map { it.name })
        val writes = f.store.writes
        assertFails { logical.createDirectory(StoragePath("/externally-renamed/another"), encrypted = true) }
        assertEquals(writes, f.store.writes)
    }

    @Test
    fun alreadyAuthenticatedDuplicateNativeBindingsAreAConflict() = runSuspendTest {
        val f = Fixture()
        for (name in listOf("first", "second")) {
            f.access.publish(StorageContentCommit(vaultId = VAULT, operationId = Uuid.random().toString(),
                entryId = Uuid.random().toString(), parentId = VAULT, name = name,
                kind = StorageContentCommitKind.Directory,
                ordinaryDirectory = StorageContentDirectoryBinding(name, StorageReference(id = "same-native-directory"))), KEY)
        }
        assertConflict { f.logical.list(StoragePath("/")) }
    }

    @Test
    fun prepareNeverAdoptsAnUnobservedConcurrentFileRevisionAsItsParent() = runSuspendTest {
        val f = Fixture()
        val original = f.managed("/target.txt")
        val replacementBytes = ByteArray(160) { 7 }
        val replacement = original.copy(operationId = Uuid.random().toString(), parents = listOf(original.operationId),
            objectId = Uuid.random().toString(), ciphertextDigest = hash(replacementBytes), size = 9)
        f.store.files["/.folderspan-vault/${replacement.objectId}.object"] = replacementBytes
        var replaced = false
        val racing = f.store.provider(beforeRead = { path ->
            if (!replaced && path == "/.folderspan-vault/${original.objectId}.object") {
                replaced = true
                f.access.publish(replacement, KEY)
            }
        })
        val failure = assertFailsWith<StorageContentException> {
            f.logical(racing).prepareFile(StoragePath("/target.txt"), Uuid.random().toString(), Uuid.random().toString(),
                160, hash(ByteArray(160)), 10, 456)
        }
        assertEquals(StorageContentErrorCode.SourceChanged, failure.code)
        assertEquals(replacement, f.access.history(VAULT, KEY).head(original.entryId))
    }

    @Test
    fun virtualBackingDoesNotAdoptANonEmptyDirectoryCreatedByAnotherWriter() = runSuspendTest {
        val f = Fixture()
        f.logical.createDirectory(StoragePath("/virtual"), encrypted = true)
        val virtual = f.access.history(VAULT, KEY).liveEntries().single()
        val wrapped = object : StorageProvider by f.provider,
            ChunkReadableNetworkClient by (f.provider as ChunkReadableNetworkClient) {
            override suspend fun createFolder(path: StoragePath): Result<Boolean> {
                val result = f.provider.createFolder(path)
                f.file(path.path + "/unobserved.txt", "preserve concurrent ordinary data")
                return result
            }
        }
        assertConflict { f.logical(wrapped).resolveOrdinaryUpload(StoragePath("/virtual/new.txt")) }
        assertEquals(virtual, f.access.history(VAULT, KEY).head(virtual.entryId))
        assertEquals("preserve concurrent ordinary data", f.store.files.getValue("/virtual/unobserved.txt").decodeToString())
    }

    @Test
    fun trailingSlashOnAConfiguredRootDoesNotTurnItsNamespaceIntoANestedVault() = runSuspendTest {
        val f = Fixture()
        val materials = f.store.files.toMap()
        f.store.files.clear()
        materials.forEach { (path, bytes) -> f.store.files["/scope" + path] = bytes }
        f.store.directories.clear()
        f.store.directories.addAll(listOf("/", "/scope", "/scope/.folderspan-vault"))
        f.file("/scope/plain.txt", "ordinary")
        val wrapped = object : StorageProvider by f.provider,
            ChunkReadableNetworkClient by (f.provider as ChunkReadableNetworkClient) {
            override suspend fun list(path: StoragePath, requestId: String?, batchId: String?) =
                f.provider.list(path.copy(path = path.path.trimEnd('/').ifEmpty { "/" }), requestId, batchId)
        }
        val reference = f.reference.copy(root = StoragePath("/scope/"))
        val logical = StorageContentLogicalAccess(wrapped, reference,
            StorageContentVaultAccess(wrapped, reference.root, createInMemorySettings(), "trailing-root"), KEY)
        assertEquals(listOf("plain.txt"), logical.list(StoragePath("/scope/")).map { it.name })
        assertEquals(0, f.store.writes)
    }

    @Test
    fun duplicateRawNamesAreAnExplicitConflict() = runSuspendTest {
        val f = Fixture()
        f.file("/plain.txt", "ordinary")
        val wrapped = object : StorageProvider by f.provider,
            ChunkReadableNetworkClient by (f.provider as ChunkReadableNetworkClient) {
            override suspend fun list(path: StoragePath, requestId: String?, batchId: String?): Result<List<NetworkFileEntry>> =
                f.provider.list(path, requestId, batchId).map { entries ->
                    if (path.path == "/") entries + entries.single { it.name == "plain.txt" } else entries
                }
        }
        assertConflict { f.logical(wrapped).list(StoragePath("/")) }
    }

    private suspend fun assertConflict(block: suspend () -> Unit) {
        val failure = assertFailsWith<StorageContentException> { block() }
        assertEquals(StorageContentErrorCode.Conflict, failure.code)
    }

    companion object {
        private const val VAULT = "f05ab1e5-aeab-43f9-b123-508994e63cba"
        private val KEY = ByteArray(32) { (it + 1).toByte() }
        private val descriptor by lazy { StorageContentPasswordLocation.create(VAULT, KEY, "logical-test-password".toCharArray()) }
        private fun hash(bytes: ByteArray) = StorageContentCrypto.sha256(bytes).toByteString().hex()
        private fun info(entry: NetworkFileEntry) = FileSimpleInfo(name = entry.name, isDirectory = entry.isDirectory,
            isHidden = entry.isHidden, path = entry.path, mineType = "", size = entry.size,
            createdDate = entry.createdDate, updatedDate = entry.updatedDate, storageReference = entry.reference)
    }
}

package com.folderspan.service.network

import com.folderspan.test.runSuspendTest
import java.util.UUID
import kotlin.test.*

class StorageContentManifestTest {
    private val scope = "test-content-scope"
    private val anchor = "00000000-0000-0000-0000-000000000001"
    private val key = ByteArray(32) { it.toByte() }
    private fun record(path: String = "/file") = StorageContentManifestRecord(
        operationId = UUID.randomUUID().toString(), path = path, objectId = "stable-object",
        ciphertextDigest = "a".repeat(64), committed = true,
    )

    @Test fun authenticatedCatalogRejectsWrongDeviceScopeKeyAndCorruption() {
        val document = StorageContentManifest(scope = scope, anchor = anchor, records = listOf(record()))
        val bytes = StorageContentManifestAuthentication.encode(document, key)
        assertEquals(document, StorageContentManifestAuthentication.decode(bytes, key, scope, anchor))
        assertFails { StorageContentManifestAuthentication.decode(bytes, key, "other-scope", anchor) }
        assertFails { StorageContentManifestAuthentication.decode(bytes, key, scope, UUID.randomUUID().toString()) }
        assertFails { StorageContentManifestAuthentication.decode(bytes, ByteArray(32) { 7 }, scope, anchor) }
        val damaged = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertFails { StorageContentManifestAuthentication.decode(damaged, key, scope, anchor) }
        assertFails { StorageContentManifestAuthentication.decode(ByteArray(STORAGE_CONTENT_MANIFEST_LIMIT + 1), key, scope, anchor) }
    }

    @Test fun invalidCatalogCannotBeAuthenticatedOrReuseOperationIdentity() {
        val first = record()
        for (records in listOf(listOf(first, first.copy(path = "/other")),
            listOf(first.copy(path = "/../outside")), listOf(first.copy(objectId = "")),
            listOf(first.copy(ciphertextDigest = "not-a-digest")))) {
            assertFails { StorageContentManifestAuthentication.encode(
                StorageContentManifest(scope = scope, anchor = anchor, records = records), key) }
        }
    }

    @Test fun initializationProbesConditionsAndRetryPreservesExistingRecords() = runSuspendTest {
        val provider = MemoryManifestProvider()
        val access = StorageContentManifestAccess(provider, scope, anchor, key)
        access.initialize()
        val entry = record()
        access.update { it.copy(records = it.records + entry) }
        val generation = access.read().generation
        access.initialize()
        assertEquals(listOf(entry), access.read().records)
        assertTrue(access.read().generation > generation)
        assertTrue(provider.createConflicts >= 2 && provider.staleConflicts >= 2)
    }

    @Test fun ignoredConditionsNeverEnableAndMissingOrForeignCatalogNeverBecomesEmpty() = runSuspendTest {
        for (ignored in listOf("create", "stale")) {
            val provider = MemoryManifestProvider(ignored)
            val access = StorageContentManifestAccess(provider, scope, anchor, key)
            assertFails { access.initialize() }
        }
        val provider = MemoryManifestProvider()
        val access = StorageContentManifestAccess(provider, scope, anchor, key)
        assertFails { access.read() }
        assertFails { access.update { it } }
        assertNull(provider.snapshot)
        provider.snapshot = StorageContentManifestSnapshot("1", StorageContentManifestAuthentication.encode(
            StorageContentManifest(scope = "foreign", anchor = anchor), key))
        assertFails { access.initialize() }
        assertEquals("foreign", StorageContentManifestAuthentication.decode(
            provider.snapshot!!.bytes, key, "foreign", anchor).scope)
    }

    @Test fun conflictRereadsAndMergesInsteadOfDroppingConcurrentFileIdentity() = runSuspendTest {
        val provider = MemoryManifestProvider()
        val access = StorageContentManifestAccess(provider, scope, anchor, key)
        access.initialize()
        val concurrent = record("/concurrent")
        val own = record("/own")
        provider.beforeNextUpdate = {
            val current = StorageContentManifestAuthentication.decode(provider.snapshot!!.bytes, key, scope, anchor)
            provider.replace(StorageContentManifestAuthentication.encode(
                current.copy(generation = current.generation + 1, records = current.records + concurrent), key))
        }
        access.update { it.copy(records = it.records + own) }
        assertEquals(setOf(concurrent, own), access.read().records.toSet())
    }

    private class MemoryManifestProvider(private val ignore: String? = null) :
        StorageProvider by UnsupportedNetworkClient("manifest-test") {
        override val contentManifestConditionalWrites = true
        var snapshot: StorageContentManifestSnapshot? = null
        var createConflicts = 0
        var staleConflicts = 0
        var beforeNextUpdate: (() -> Unit)? = null
        private var revision = 0
        override suspend fun readContentManifest(anchor: String) = Result.success(snapshot)
        fun replace(bytes: ByteArray): StorageContentManifestSnapshot =
            StorageContentManifestSnapshot((++revision).toString(), bytes.copyOf()).also { snapshot = it }
        override suspend fun compareAndSetContentManifest(anchor: String, expectedRevision: String?, bytes: ByteArray):
            Result<StorageContentManifestSnapshot> {
            if (expectedRevision != null) beforeNextUpdate?.also { beforeNextUpdate = null; it() }
            if (expectedRevision == null && snapshot != null && ignore != "create") {
                createConflicts++; return Result.failure(StorageContentManifestConflictException())
            }
            if (expectedRevision != null && expectedRevision != snapshot?.revision && ignore != "stale") {
                staleConflicts++; return Result.failure(StorageContentManifestConflictException())
            }
            return Result.success(replace(bytes))
        }
    }
}

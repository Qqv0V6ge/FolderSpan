package com.folderspan.service.network

import com.folderspan.test.createInMemorySettings
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageContentVaultAccessTest {
    private val password = "independent-location-password".toCharArray()
    private val root = StoragePath("/")

    @Test
    fun anotherLocalOwnerRestoresFromRemotePasswordMaterialWithoutAnyCas() = runSuspendTest {
        val store = TestStorageContentVaultStore()
        store.files["/historical-plaintext.txt"] = "ordinary".encodeToByteArray()
        val first = StorageContentVaultAccess(store.provider(), root, createInMemorySettings(), "account-a/connection-a")
            .initializeOrUnlock(password)
        try {
            val second = StorageContentVaultAccess(store.provider(), root, createInMemorySettings(), "account-b/connection-b")
                .initializeOrUnlock(password)
            try {
                assertEquals(first.vaultId, second.vaultId)
                assertContentEquals(first.key, second.key)
            } finally { second.key.fill(0) }
            assertEquals(2, store.writes)
            assertEquals("ordinary", store.files.getValue("/historical-plaintext.txt").decodeToString())
            assertFalse(store.provider().contentManifestConditionalWrites)
        } finally { first.key.fill(0) }
    }

    @Test
    fun wrongPasswordCorruptedDescriptorMissingRootAndKnownMissingLocationNeverWrite() = runSuspendTest {
        val store = TestStorageContentVaultStore()
        val first = StorageContentVaultAccess(store.provider(), root, createInMemorySettings(), "a").initializeOrUnlock(password)
        val vaultId = first.vaultId
        first.key.fill(0)
        val host = StorageContentVaultAccess(store.provider(), root, createInMemorySettings(), "b")
        val original = store.snapshot()
        val writes = store.writes
        assertFails { host.unlock("wrong".toCharArray(), vaultId) }
        assertEquals(original, store.snapshot())
        val descriptorPath = "/.folderspan-vault/$vaultId.location"
        val descriptor = store.files.getValue(descriptorPath)
        store.files[descriptorPath] = descriptor.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFails { host.initializeOrUnlock(password) }
        assertEquals(writes, store.writes)
        store.files[descriptorPath] = descriptor
        store.files.remove("/.folderspan-vault/$vaultId.root")
        assertFails { host.initializeOrUnlock(password, vaultId) }
        store.files.remove(descriptorPath)
        assertFails { host.initializeOrUnlock(password, vaultId) }
        assertEquals(writes, store.writes)
    }

    @Test
    fun legacyAndUnknownNamespaceMaterialArePreserved() = runSuspendTest {
        for (name in listOf("/.folderspan-content.json", "/.folderspan-vault/ordinary.txt",
            "/.folderspan-vault/00112233-4455-6677-8899-aabbccddeeff.object")) {
            val store = TestStorageContentVaultStore()
            store.directories.add("/.folderspan-vault")
            store.files[name] = "keep".encodeToByteArray()
            val original = store.snapshot()
            assertFails {
                StorageContentVaultAccess(store.provider(), root, createInMemorySettings(), "owner")
                    .initializeOrUnlock(password)
            }
            assertEquals(original, store.snapshot())
            assertEquals(0, store.writes)
        }
    }

    @Test
    fun cancellationOrLostResponseRetriesOriginalCandidateWithoutReplacingAnyMaterial() = runSuspendTest {
        for (suffix in listOf(".root", ".location")) {
            val store = TestStorageContentVaultStore()
            val settings = createInMemorySettings()
            var interrupted = false
            val provider = store.provider(afterWrite = { name ->
                if (!interrupted && name.endsWith(suffix)) {
                    interrupted = true
                    throw CancellationException("injected publication interruption")
                }
            })
            val access = StorageContentVaultAccess(provider, root, settings, "owner")
            assertFailsWith<CancellationException> { access.initializeOrUnlock(password) }
            val before = store.snapshot()
            val unlocked = access.initializeOrUnlock(password)
            try {
                before.forEach { (name, bytes) -> assertEquals(bytes, store.snapshot()[name]) }
                assertEquals(2, store.writes)
                assertEquals(2, store.files.size)
            } finally { unlocked.key.fill(0) }
        }
    }

    @Test
    fun delayedCandidatesFreezeBothClientsWhenTheyBecomeVisible() = runSuspendTest {
        val store = TestStorageContentVaultStore()
        val firstAccess = StorageContentVaultAccess(store.provider(), root, createInMemorySettings(), "a")
        val first = firstAccess.initializeOrUnlock(password)
        val hidden = store.files.keys.toMutableSet()
        val secondAccess = StorageContentVaultAccess(store.provider(hidden), root, createInMemorySettings(), "b")
        val second = secondAccess.initializeOrUnlock(password)
        try {
            assertTrue(first.vaultId != second.vaultId)
            hidden.clear()
            val original = store.snapshot()
            val writes = store.writes
            assertFailsWith<StorageContentInitializationConflictException> { firstAccess.unlock(password, first.vaultId) }
            assertFailsWith<StorageContentInitializationConflictException> { secondAccess.unlock(password, second.vaultId) }
            assertFailsWith<StorageContentInitializationConflictException> { firstAccess.initializeOrUnlock(password) }
            assertEquals(writes, store.writes)
            assertEquals(original, store.snapshot())
        } finally { first.key.fill(0); second.key.fill(0) }
    }

    @Test
    fun concurrentInitializationNeverOverwritesAnotherCandidatesData() = runSuspendTest {
        val store = TestStorageContentVaultStore()
        coroutineScope {
            val attempts = (1..2).map { owner ->
                async {
                    runCatching {
                        StorageContentVaultAccess(store.provider(), root, createInMemorySettings(), owner.toString())
                            .initializeOrUnlock(password)
                    }
                }
            }.awaitAll()
            attempts.forEach { it.getOrNull()?.key?.fill(0) }
        }
        assertTrue(store.files.isNotEmpty())
        assertTrue(store.files.keys.all { it.startsWith("/.folderspan-vault/") })
        val original = store.snapshot()
        val locations = store.files.keys.count { it.endsWith(".location") }
        val roots = store.files.keys.count { it.endsWith(".root") }
        assertTrue(locations <= roots && roots <= 2)
        val fresh = StorageContentVaultAccess(store.provider(), root, createInMemorySettings(), "fresh")
        if (roots > 1) assertFailsWith<StorageContentInitializationConflictException> { fresh.initializeOrUnlock(password) }
        assertEquals(original, store.snapshot())
    }
}

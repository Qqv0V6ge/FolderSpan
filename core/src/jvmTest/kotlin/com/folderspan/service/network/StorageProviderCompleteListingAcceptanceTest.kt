package com.folderspan.service.network

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.*

/** Raw providers must include hidden entries and read back mutations even after a warmed directory listing. */
class StorageProviderCompleteListingAcceptanceTest {
    @Test fun ftpCompleteListing() = verify("FTP")
    @Test fun ftpsCompleteListing() = verify("FTPS")
    @Test fun smbFreshCompleteListing() = verify("SMB")

    private fun verify(case: String) = runBlocking {
        val fixture = readNetworkContentEncryptionFixture(case)
        val config = try { Json.decodeFromJsonElement<NetworkConnectionConfig>(fixture.getValue("config")) }
        catch (_: Exception) { throw IllegalArgumentException("Malformed private complete-listing fixture") }
        val provider = NetworkClientFactory.create(config)
        var root: StoragePath? = null
        var passed = false
        try {
            withTimeout(120_000) {
                val parent = fixture["root"]?.jsonPrimitive?.content ?: provider.rootLocation.path
                val path = parent.trimEnd('/') + "/listing-" + UUID.randomUUID()
                assertTrue(provider.createFolder(path).getOrThrow())
                val created = provider.stat(StoragePath(path)).getOrThrow()
                root = StoragePath(created.path, created.reference)
                write(provider, StoragePath(path + "/ordinary.bin"))
                assertEquals(listOf("ordinary.bin"), provider.list(root!!).getOrThrow().map { it.name })
                val hidden = StoragePath(path + "/.folderspan-vault")
                assertTrue(provider.createFolder(hidden).getOrThrow())
                assertTrue(provider.list(root!!).getOrThrow().any { it.name == ".folderspan-vault" && it.isDirectory },
                    "The complete internal listing must include a newly created hidden namespace")
                write(provider, StoragePath(hidden.path + "/first.bin"))
                assertEquals(listOf("first.bin"), provider.list(hidden).getOrThrow().map { it.name })
                write(provider, StoragePath(hidden.path + "/later.bin"))
                assertEquals(setOf("first.bin", "later.bin"), provider.list(hidden).getOrThrow().map { it.name }.toSet(),
                    "Read-after-write verification must not reuse an older directory snapshot")
                passed = true
            }
        } finally {
            try {
                if (passed) root?.let { deleteNetworkContentEncryptionFixture(provider, it) }
                else root?.let { println("COMPLETE_LISTING retained failed disposable root=" + it.path) }
            } finally { provider.close() }
        }
        println("COMPLETE_LISTING passed case=" + case)
    }

    private suspend fun write(provider: StorageProvider, path: StoragePath) {
        var sent = false
        assertTrue(provider.uploadFromSource(path, 3, { _, _ -> }) {
            if (sent) null else byteArrayOf(1, 2, 3).also { sent = true }
        }.getOrThrow())
    }
}

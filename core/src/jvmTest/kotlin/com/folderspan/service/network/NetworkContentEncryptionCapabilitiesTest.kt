package com.folderspan.service.network

import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.StorageContentLocationReference
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkContentEncryptionCapabilitiesTest {
    private val location = StorageContentLocationReference(
        root = StoragePath("/vault"), vaultId = "7395784c-8f4f-4d64-b126-4179ffbdb0a1")
    private val request = StorageShareRequest(StorageShareKind.LINK, "reader")

    private class NativeOperations : StorageProvider by TestStorageContentVaultStore().provider() {
        val calls = mutableListOf<Pair<String, StoragePath>>()
        val advanced = StorageAdvancedCapabilities(
            features = StorageAdvancedFeature.entries.associateWith { StorageFeatureAvailability.AVAILABLE },
            shareRoles = setOf("reader"),
        )
        override suspend fun advancedCapabilities(path: StoragePath): Result<StorageAdvancedCapabilities> {
            calls += "capabilities" to path
            return Result.success(advanced)
        }
        override suspend fun listVersions(path: StoragePath, cursor: String?): Result<StoragePage<StorageVersion>> {
            calls += "versions" to path
            return Result.success(StoragePage(listOf(StorageVersion(path, "native-version"))))
        }
        override suspend fun listShares(path: StoragePath, cursor: String?): Result<StoragePage<StorageShare>> {
            calls += "shares" to path
            return Result.success(StoragePage(listOf(StorageShare(path, "native-share", StorageShareKind.LINK, "reader"))))
        }
        override suspend fun createShare(path: StoragePath, request: StorageShareRequest, confirmed: Boolean): Result<StorageShare> {
            calls += "create" to path
            return Result.success(StorageShare(path, "native-share", request.kind, request.role, url = "https://fixture.invalid/share"))
        }
        override suspend fun restoreVersion(version: StorageVersion, confirmed: Boolean): Result<NetworkFileEntry> {
            calls += "restore" to version.file
            return Result.success(NetworkFileEntry("plain.txt", version.file.path, false, reference = version.file.reference))
        }
    }

    private fun network(provider: StorageProvider, reference: StorageContentLocationReference?) =
        object : Network("capabilities", "/", "SFTP", "fixture.invalid", "alice", "",
            extras = NetworkDriveExtras(contentEncryption = reference)) {
            override val client = provider
        }

    private suspend fun assertUnavailable(network: Network, path: StoragePath) {
        assertEquals(StorageAdvancedCapabilities(), network.storageAdvancedCapabilities(path).getOrThrow())
        assertTrue(network.storageVersions(path).isFailure)
        assertTrue(network.storageShares(path).isFailure)
        for (confirmed in listOf(false, true)) {
            assertTrue(network.createStorageShare(path, request, confirmed).isFailure)
            assertTrue(network.restoreStorageVersion(StorageVersion(path, "native-version"), confirmed).isFailure)
        }
    }

    @Test
    fun passwordLocationKeepsNativeVersionsAndSharesUnavailableWhenDisabled(): Unit = runBlocking {
        val provider = NativeOperations()
        for (enabled in listOf(true, false)) {
            val network = network(provider, location.copy(enabled = enabled))
            assertUnavailable(network, StoragePath("/vault"))
            assertUnavailable(network, StoragePath("/vault/file.txt", StorageReference("managed-entry")))
        }
        assertTrue(provider.calls.isEmpty(), "Rejected native operations must not reach the provider or publish a share")
    }

    @Test
    fun privateMetadataCannotBeSharedEvenWithoutAPasswordLocation(): Unit = runBlocking {
        val provider = NativeOperations()
        val network = network(provider, null)
        assertUnavailable(network, StoragePath("/outside/.folderspan-vault/private.object"))
        assertUnavailable(network, StoragePath("/outside/.FOLDERSPAN-VAULT/private.commit"))
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun ordinaryFilesOutsideTheLocationKeepNativeReferencesAndCapabilities(): Unit = runBlocking {
        val provider = NativeOperations()
        val network = network(provider, location)
        val path = StoragePath("/vault-other/plain.txt", StorageReference("native-file", "native-parent"))
        assertEquals(provider.advanced, network.storageAdvancedCapabilities(path).getOrThrow())
        assertEquals(path, network.storageVersions(path).getOrThrow().entries.single().file)
        assertEquals(path, network.storageShares(path).getOrThrow().entries.single().file)
        assertEquals(path, network.createStorageShare(path, request, true).getOrThrow().file)
        assertEquals(path.reference, network.restoreStorageVersion(StorageVersion(path, "native-version"), false).getOrThrow().reference)
        assertEquals(listOf("capabilities", "versions", "shares", "create", "restore"), provider.calls.map { it.first })
        assertTrue(provider.calls.all { it.second == path })
    }
}

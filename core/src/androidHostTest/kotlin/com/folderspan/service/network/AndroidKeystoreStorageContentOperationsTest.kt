package com.folderspan.service.network

import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidKeystoreStorageContentOperationsTest {
    private val config = NetworkConnectionConfig("Nextcloud", "fixture.invalid", "", "",
        extras = NetworkDriveExtras(cloud = CloudDriveExtras(connectionId = "fixture", rootId = "/fixture")))

    @Test
    fun unavailableKeystoreBlocksContentKeysWithoutInitializingTheHost() = runBlocking {
        var initialized = 0
        val operations = AndroidKeystoreStorageContentOperations(
            requireProtection = { error("Keystore unavailable") },
            hasContentKey = { true },
            requireNoRemoteCatalog = { error("Existing local key must fail before the probe") },
            operationsFactory = { initialized++; UnsupportedStorageContentOperations },
        )
        assertTrue(operations.status(config).isFailure)
        assertTrue(operations.create(config).exceptionOrNull()?.message?.contains("Android Keystore") == true)
        assertTrue(operations.beforeMutation(config, StoragePath("/file")).isFailure)
        assertTrue(operations.beginUpload(config, 1, "entry", StoragePath("/file")).isFailure)
        assertEquals(0, initialized)
    }

    @Test
    fun unavailableKeystoreNeverFallsBackToRawPasswordLocationOperations() = runBlocking {
        var initialized = 0
        val passwordLocation = config.copy(protocol = "FTP", extras = NetworkDriveExtras(
            contentEncryption = com.folderspan.data.main.network.StorageContentLocationReference(
                enabled = true, vaultId = "00112233-4455-6677-8899-aabbccddeeff")))
        val operations = AndroidKeystoreStorageContentOperations(
            requireProtection = { error("Keystore unavailable") }, hasContentKey = { false },
            requireNoRemoteCatalog = { error("Password locations must fail before an ordinary probe") },
            operationsFactory = { initialized++; UnsupportedStorageContentOperations })
        assertTrue(operations.setupPassword(passwordLocation, "content-password".toCharArray()).isFailure)
        assertTrue(operations.unlockPassword(passwordLocation, "content-password".toCharArray()).isFailure)
        assertTrue(operations.logicalList(passwordLocation, StoragePath("/")).isFailure)
        assertTrue(operations.resolveUploadTarget(passwordLocation, StoragePath("/file")).isFailure)
        assertTrue(operations.beginUpload(passwordLocation, 1, "entry", StoragePath("/file")).isFailure)
        assertEquals(0, initialized)
    }

    @Test
    fun ordinaryScopeStillRequiresSuccessfulRemoteCatalogProbe() = runBlocking {
        var probes = 0
        val operations = AndroidKeystoreStorageContentOperations(
            requireProtection = { error("Keystore unavailable") },
            hasContentKey = { false },
            requireNoRemoteCatalog = { probes++ },
            operationsFactory = { UnsupportedStorageContentOperations },
        )
        assertFalse(operations.status(config).getOrThrow().available)
        operations.beforeMutation(config, StoragePath("/ordinary")).getOrThrow()
        operations.beginUpload(config, 1, "ordinary", StoragePath("/ordinary")).getOrThrow()
        assertEquals(3, probes)
        assertTrue(operations.create(config).isFailure)
        assertTrue(operations.setEnabled(config, true).isFailure)
    }

    @Test
    fun remoteCatalogWithoutLocalKeyAndUnknownLocalStateNeverAllowRawFallback() = runBlocking {
        for (localReadFails in listOf(false, true)) {
            var initialized = 0
            val operations = AndroidKeystoreStorageContentOperations(
                requireProtection = { error("Keystore unavailable") },
                hasContentKey = { if (localReadFails) error("Local vault cannot be read") else false },
                requireNoRemoteCatalog = { error("Remote authenticated catalog exists") },
                operationsFactory = { initialized++; UnsupportedStorageContentOperations },
            )
            // Network calls getOrThrow here before taking its raw-download path.
            assertTrue(operations.status(config).isFailure)
            assertTrue(operations.beforeMutation(config, StoragePath("/file")).isFailure)
            assertTrue(operations.beginUpload(config, 1, "entry", StoragePath("/file")).isFailure)
            assertEquals(0, initialized)
        }
    }
}

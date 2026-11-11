package com.folderspan.service.network

import com.russhwolf.settings.MapSettings
import kotlin.test.*

class StorageContentKeyStoreTest {
    @Test
    fun backupVerificationAndExplicitUnlockProtectKeyLifecycle() {
        val settings = MapSettings()
        val password = "test recovery password".toCharArray()
        val scope = "connection-A/library-A/folder-A"
        val store = StorageContentKeyStore(settings)
        store.create(scope)
        store.markManifestInitialized(scope)
        assertEquals(false to false, store.status(scope))
        assertFails { store.setEnabled(scope, true) }
        val backup = store.exportRecovery(scope, password)
        assertFails { store.verifyBackupAndEnable(scope, backup, "incorrect password".toCharArray()) }
        store.verifyBackupAndEnable(scope, backup, password)
        val key = store.keyForOperation(scope)
        store.lock(scope)
        assertEquals(true to false, store.status(scope))
        assertFails { store.keyForOperation(scope) }
        val restarted = StorageContentKeyStore(settings)
        assertFails { restarted.keyForOperation(scope) }
        restarted.unlock(scope)
        assertContentEquals(key, restarted.keyForOperation(scope))
        store.close()
        restarted.close()
    }

    @Test
    fun recoveryAcrossHostsDoesNotReplaceExistingKeysOrCrossAccountScope() {
        val password = "test recovery password".toCharArray()
        val scope = "connection-A/library-A/folder-A"
        val original = StorageContentKeyStore(MapSettings())
        original.create(scope)
        original.markManifestInitialized(scope)
        val backup = original.exportRecovery(scope, password)
        original.verifyBackupAndEnable(scope, backup, password)
        val settings = MapSettings()
        val restored = StorageContentKeyStore(settings)
        assertFails { restored.importRecovery("another-account", backup, password) }
        restored.importRecovery(scope, backup, password)
        restored.setEnabled(scope, true)
        restored.unlock(scope)
        assertContentEquals(original.keyForOperation(scope), restored.keyForOperation(scope))
        settings.putString("auth.storage.account.account-A", "oauth")
        settings.remove("auth.storage.account.account-A")
        assertContentEquals(original.keyForOperation(scope), restored.keyForOperation(scope))
        val other = StorageContentKeyStore(MapSettings())
        other.create(scope)
        other.markManifestInitialized(scope)
        assertFails { other.importRecovery(scope, backup, password) }
        original.close(); restored.close(); other.close()
    }
}

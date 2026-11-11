package com.folderspan.service.network

import com.folderspan.settings.IosStrictKeychainStore
import com.folderspan.settings.createKeychainQuery
import com.folderspan.settings.requireStrictKeychainStatus
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.NSUserDefaultsSettings
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFRelease
import platform.Security.SecItemCopyMatching
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecMissingEntitlement
import platform.Security.errSecNotAvailable
import platform.Security.errSecSuccess

/** These tests intentionally fail when the native runner cannot access Keychain. */
@OptIn(ExperimentalForeignApi::class)
class StorageContentKeyStoreIosTest {
    @Test
    fun strictProtectionRejectsUnavailableAndUnauthorizedKeychainStatuses() {
        listOf("read", "list", "update", "write", "delete").forEach { operation ->
            listOf(errSecMissingEntitlement, errSecNotAvailable, errSecInteractionNotAllowed).forEach { status ->
                assertFailsWith<IllegalStateException> {
                    requireStrictKeychainStatus(status, operation, allowNotFound = true)
                }
            }
        }
        requireStrictKeychainStatus(errSecSuccess, "read")
        requireStrictKeychainStatus(errSecItemNotFound, "read", allowNotFound = true)
        assertFailsWith<IllegalStateException> { requireStrictKeychainStatus(errSecItemNotFound, "write") }
    }

    @Test
    fun contentSettingsUseRealKeychainAndNeverNSUserDefaults() {
        val key = contentKeyName(newScope())
        val settings = createStorageContentSettings()
        val plaintext = NSUserDefaultsSettings.Factory().create("FolderSpan")
        try {
            assertNull(settings.getStringOrNull(key))
            settings.putString(key, "first-test-value")
            assertNativeRecord(key)
            assertTrue(key in IosStrictKeychainStore.keys())
            assertTrue(IosStrictKeychainStore.get(key) == "first-test-value")
            assertNull(plaintext.getStringOrNull(key))

            settings.putString(key, "updated-test-value")
            assertNativeRecord(key)
            assertTrue(IosStrictKeychainStore.get(key) == "updated-test-value")
            assertNull(plaintext.getStringOrNull(key))

            settings.remove(key)
            assertFalse(IosStrictKeychainStore.contains(key))
            assertNativeRecord(key, exists = false)
        } finally {
            runCatching { IosStrictKeychainStore.remove(key) }
            plaintext.remove(key)
        }
    }

    @Test
    fun verifiedBackupLocksAcrossInstancesAndSurvivesOauthRemoval() {
        val scope = newScope()
        val wrongScope = "$scope/another-account"
        val keyName = contentKeyName(scope)
        val oauthKey = "auth.storage.account.ios-test-${Random.nextLong()}"
        val password = "iOS recovery 测试 🔐".toCharArray()
        val settings = createStorageContentSettings()
        val store = StorageContentKeyStore()
        var restarted: StorageContentKeyStore? = null
        var expectedKey: ByteArray? = null
        try {
            store.create(scope)
            assertNativeRecord(keyName)
            assertEquals(false to false, store.status(scope))
            assertFails { store.setEnabled(scope, true) }
            assertFails { store.keyForOperation(scope) }
            assertFails { store.exportRecovery(scope, password) }
            store.markManifestInitialized(scope)
            expectedKey = store.keyForManifest(scope)
            val backup = store.exportRecovery(scope, password)
            val before = assertNotNull(settings.getStringOrNull(keyName))
            assertFails { store.verifyBackupAndEnable(scope, backup, "wrong recovery password".toCharArray()) }
            assertTrue(before == settings.getStringOrNull(keyName))
            assertEquals(false to false, store.status(scope))
            assertFails { store.importRecovery(wrongScope, backup, password) }
            assertFalse(store.hasKey(wrongScope))

            store.verifyBackupAndEnable(scope, backup, password)
            assertEquals(true to true, store.status(scope))
            val unlockedKey = store.keyForOperation(scope)
            try { assertTrue(expectedKey.contentEquals(unlockedKey)) } finally { unlockedKey.fill(0) }
            store.lock(scope)
            assertEquals(true to false, store.status(scope))
            assertFails { store.keyForReadOperation(scope) }

            restarted = StorageContentKeyStore()
            assertEquals(true to false, restarted.status(scope))
            assertFails { restarted.keyForOperation(scope) }
            restarted.unlock(scope)
            val restoredKey = restarted.keyForOperation(scope)
            try { assertTrue(expectedKey.contentEquals(restoredKey)) } finally { restoredKey.fill(0) }

            settings.putString(oauthKey, "oauth-test-value")
            settings.remove(oauthKey)
            assertNativeRecord(keyName)
            val afterRemoval = restarted.keyForOperation(scope)
            try { assertTrue(expectedKey.contentEquals(afterRemoval)) } finally { afterRemoval.fill(0) }
            assertNull(NSUserDefaultsSettings.Factory().create("FolderSpan").getStringOrNull(keyName))
        } finally {
            store.close()
            restarted?.close()
            expectedKey?.fill(0)
            password.fill('\u0000')
            runCatching { IosStrictKeychainStore.remove(keyName) }
            runCatching { IosStrictKeychainStore.remove(oauthKey) }
        }
    }

    @Test
    fun recoveryCannotReplaceInitializedKeyOrManifestAnchor() {
        val scope = newScope()
        val keyName = contentKeyName(scope)
        val password = "iOS independent recovery".toCharArray()
        val origin = StorageContentKeyStore(MapSettings())
        val target = StorageContentKeyStore()
        var targetKey: ByteArray? = null
        try {
            origin.create(scope)
            origin.markManifestInitialized(scope)
            val otherKeyBackup = origin.exportRecovery(scope, password)
            target.create(scope)
            target.markManifestInitialized(scope)
            val before = assertNotNull(IosStrictKeychainStore.get(keyName))
            assertFails { target.importRecovery(scope, otherKeyBackup, password) }
            assertTrue(before == IosStrictKeychainStore.get(keyName))

            targetKey = target.keyForManifest(scope)
            val otherAnchorBackup = StorageContentRecoveryV2.exportRecovery(
                targetKey, scope, origin.manifestAnchor(scope), password,
            )
            assertFails { target.importRecovery(scope, otherAnchorBackup, password) }
            assertTrue(before == IosStrictKeychainStore.get(keyName))
            assertEquals(false to false, target.status(scope))
            assertNativeRecord(keyName)
        } finally {
            origin.close()
            target.close()
            targetKey?.fill(0)
            password.fill('\u0000')
            runCatching { IosStrictKeychainStore.remove(keyName) }
        }
    }

    @Test
    fun recoveryCanReplaceAnUninitializedUnverifiedCreationWithoutAutoUnlocking() {
        val scope = newScope()
        val keyName = contentKeyName(scope)
        val password = "iOS pending creation recovery".toCharArray()
        val origin = StorageContentKeyStore(MapSettings())
        val target = StorageContentKeyStore()
        var expectedKey: ByteArray? = null
        try {
            origin.create(scope)
            origin.markManifestInitialized(scope)
            expectedKey = origin.keyForManifest(scope)
            val backup = origin.exportRecovery(scope, password)
            target.create(scope)
            assertFalse(target.manifestInitialized(scope))
            assertFalse(target.backupVerified(scope))
            target.importRecovery(scope, backup, password)
            assertTrue(target.manifestInitialized(scope))
            assertTrue(target.backupVerified(scope))
            assertEquals(false to false, target.status(scope))
            assertFails { target.keyForReadOperation(scope) }
            target.setEnabled(scope, true)
            target.unlock(scope)
            val restoredKey = target.keyForOperation(scope)
            try { assertTrue(expectedKey.contentEquals(restoredKey)) } finally { restoredKey.fill(0) }
            assertNativeRecord(keyName)
        } finally {
            origin.close()
            target.close()
            expectedKey?.fill(0)
            password.fill('\u0000')
            runCatching { IosStrictKeychainStore.remove(keyName) }
        }
    }

    private fun newScope(): String = "ios-keychain-test/connection-${Random.nextLong()}/library/folder"

    private fun contentKeyName(scope: String): String =
        "auth.storage.content." + StorageContentCrypto.sha256(scope.encodeToByteArray())
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun assertNativeRecord(key: String, exists: Boolean = true) {
        val query = createKeychainQuery(key)
        try {
            assertEquals(if (exists) errSecSuccess else errSecItemNotFound, SecItemCopyMatching(query, null))
        } finally {
            CFRelease(query)
        }
    }
}

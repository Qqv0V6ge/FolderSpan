package com.folderspan.settings

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class FileAesGcmSecretVaultTest {
    @Test
    fun roundTripsValuesWithoutLeavingPlaintextOnDisk() {
        val directory = Files.createTempDirectory("folderspan-secure-settings")
        try {
            val vault = FileAesGcmSecretVault(directory)
            vault.put("auth.accessToken", "token-value")
            vault.put(com.folderspan.utils.SettingsUtils.KEY_CRYPTO_KEY, "crypto-key")

            val reloaded = FileAesGcmSecretVault(directory)
            assertEquals("token-value", reloaded.get("auth.accessToken"))
            assertEquals("crypto-key", reloaded.get(com.folderspan.utils.SettingsUtils.KEY_CRYPTO_KEY))

            val valuesFile = directory.resolve("values.v1")
            val masterKey = directory.resolve("master.key")
            assertTrue(valuesFile.isRegularFile())
            assertTrue(masterKey.isRegularFile())
            val blob = valuesFile.readBytes().decodeToString()
            assertFalse(blob.contains("token-value"))
            assertFalse(blob.contains("crypto-key"))
            assertOwnerOnlyFile(masterKey)
            assertOwnerOnlyFile(valuesFile)

            val reopened = FileAesGcmSecretVault(directory)
            assertEquals("token-value", reopened.get("auth.accessToken"))
            assertOwnerOnlyFile(masterKey)
            assertOwnerOnlyFile(valuesFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertOwnerOnlyFile(path: java.nio.file.Path) {
        if (!path.exists()) return
        val permissions = runCatching { Files.getPosixFilePermissions(path) }.getOrNull() ?: return
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            permissions,
        )
    }
}

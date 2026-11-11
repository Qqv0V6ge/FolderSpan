@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.folderspan.service.network

import com.folderspan.test.createInMemorySettings
import com.folderspan.test.runSuspendTest
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import okio.FileSystem
import okio.Path
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.posix.lstat
import platform.posix.stat

class StorageContentFileProtectionIosTest {
    private val fs = FileSystem.SYSTEM

    private fun permissions(path: Path): Int = memScoped {
        val attributes = alloc<stat>()
        check(lstat(path.toString(), attributes.ptr) == 0)
        attributes.st_mode.toInt() and 511
    }

    private fun assertPrivateDataProtection(path: Path) = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        error.value = null
        val resources = NSURL.fileURLWithPath(path.toString())
            .resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error.ptr)
        assertNull(error.value)
        val excluded = when (val value = resources?.get(NSURLIsExcludedFromBackupKey)) {
            is NSNumber -> value.boolValue
            is Boolean -> value
            else -> false
        }
        assertTrue(excluded, "Private content must be excluded from backup")
        error.value = null
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path.toString(), error.ptr)
        assertNull(error.value)
        assertTrue(attributes != null, "File attributes must be readable on every target")
        val protection = attributes?.get(NSFileProtectionKey)
        if (storageContentSupportsFileProtectionReadback) {
            assertEquals(NSFileProtectionCompleteUntilFirstUserAuthentication, protection)
        } else {
            assertTrue(protection == null || protection == NSFileProtectionCompleteUntilFirstUserAuthentication,
                "Simulator must expose no protection class or the exact requested class")
            println("STORAGE_CONTENT_SIMULATOR_FILE_PROTECTION_READBACK=${protection ?: "unavailable"}")
        }
    }

    @Test
    fun realHostStagesAndPrivateFilesUseOwnerOnlyPermissionsFromCreation() = runSuspendTest {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("content-ios-permissions-" + Uuid.random())
        val settings = createInMemorySettings()
        try {
            StorageContentHostContext(StorageContentKeyStore(settings), settings, root).use { host ->
                val stage = host.createStage(7, "permissions").getOrThrow()
                val path = StorageContentFileProtection.normalizedAbsolutePath(stage)
                assertEquals(448, permissions(root))
                assertEquals(448, permissions(root / "7"))
                assertEquals(384, permissions(path))
                val cipher = StorageContentFileProtection.createPrivateTempFile(root / "7", ".cipher-")
                assertEquals(384, permissions(cipher))
                listOf(root, root / "7", path, cipher).forEach(::assertPrivateDataProtection)
                host.close()
                assertTrue(fs.exists(cipher))
            }
        } finally { fs.deleteRecursively(root) }
    }

    @Test
    fun atomicPublicationRequiresAuthenticatedSiblingAndKeepsExistingBytesOnFailure() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("content-ios-publish-" + Uuid.random())
        StorageContentFileProtection.createPrivateDirectory(root)
        try {
            val previous = root / "existing"
            fs.write(previous) { write(byteArrayOf(9)) }
            val temporary = StorageContentFileProtection.createPrivateTempFile(root, ".authenticated-")
            fs.write(temporary) { write(byteArrayOf(1, 2, 3)) }
            val other = root / "other"
            StorageContentFileProtection.createPrivateDirectory(other)
            assertFails { StorageContentFileProtection.atomicMove(temporary, other / "existing") }
            assertContentEquals(byteArrayOf(9), fs.read(previous) { readByteArray() })
            assertTrue(fs.exists(temporary))
            StorageContentFileProtection.atomicMove(temporary, previous)
            assertContentEquals(byteArrayOf(1, 2, 3), fs.read(previous) { readByteArray() })
            assertEquals(384, permissions(previous))
            assertPrivateDataProtection(previous)
        } finally { fs.deleteRecursively(root) }
    }
}

package com.folderspan.utils

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosOpenInPlaceRootTest {
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    @Test
    fun openRootKeepsEveryRegisteredFile() {
        val testId = NSUUID().UUIDString
        val root = NSTemporaryDirectory().trimEnd('/')
        val firstPath = "$root/folderspan-open-$testId-first.txt"
        val secondPath = "$root/folderspan-open-$testId-second.txt"

        try {
            listOf(firstPath, secondPath).forEachIndexed { index, path ->
                val bytes = "shared-file-$index".encodeToByteArray()
                val data = bytes.usePinned { pinned ->
                    NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
                }
                assertTrue(NSFileManager.defaultManager.createFileAtPath(path, data, null))
                IosSecurityScopeStore.register(NSURL.fileURLWithPath(path))
                val metadata = FileUtils.getFile(FileAccessPermission.Allowed, path)
                assertTrue(metadata.isSuccess, "metadata($path): ${metadata.exceptionOrNull()?.message}")
            }

            assertTrue(IosSecurityScopeStore.registeredPaths().containsAll(listOf(firstPath, secondPath)))

            val openRoot = PathUtils.getFileAndFolder(FileAccessPermission.Allowed, "open://")
            assertTrue(openRoot.isSuccess, openRoot.exceptionOrNull()?.message.orEmpty())

            val openedPaths = openRoot.getOrThrow().map { it.path }.toSet()
            assertEquals(setOf(firstPath, secondPath), openedPaths.intersect(setOf(firstPath, secondPath)))
        } finally {
            FileUtils.deleteFile(FileAccessPermission.Allowed, firstPath)
            FileUtils.deleteFile(FileAccessPermission.Allowed, secondPath)
        }
    }
}

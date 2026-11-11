package com.folderspan.utils

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacDirectoryAccessStoreTest {
    private class Bridge : MacDirectoryAccessBridge {
        var selection: AuthorizedMacDirectory? = null
        val restored = mutableMapOf<String, AuthorizedMacDirectory>()
        override fun selectDirectory(): AuthorizedMacDirectory? = selection
        override fun resolveDirectory(bookmark: String): AuthorizedMacDirectory =
            restored[bookmark] ?: error("Unavailable")
    }

    @Test
    fun selectionPersistsLocallyAndRestoresTheResolvedPath() {
        val file = Files.createTempDirectory("mac-bookmarks-test").resolve("access.json")
        try {
            val bridge = Bridge().apply { selection = AuthorizedMacDirectory("/chosen", "bookmark-1") }
            val first = MacDirectoryAccessStore(file, bridge)
            assertEquals("/chosen", first.selectDirectory())
            assertEquals(listOf("/chosen"), first.accessibleDirectories())
            val restarted = Bridge().apply {
                restored["bookmark-1"] = AuthorizedMacDirectory("/moved", "bookmark-2")
            }
            val second = MacDirectoryAccessStore(file, restarted)
            assertTrue(second.accessibleDirectories().isEmpty())
            second.restore()
            assertEquals(listOf("/moved"), second.accessibleDirectories())
            assertTrue(Files.readString(file).contains("bookmark-2"))
            assertFalse(Files.readString(file).contains("bookmark-1"))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(file.parent)
        }
    }

    @Test
    fun failedRestoreKeepsBookmarkWithoutAdvertisingAccess() {
        val file = Files.createTempDirectory("mac-bookmarks-test").resolve("access.json")
        try {
            val bridge = Bridge().apply { selection = AuthorizedMacDirectory("/offline", "bookmark") }
            MacDirectoryAccessStore(file, bridge).selectDirectory()
            val before = Files.readString(file)
            val restored = MacDirectoryAccessStore(file, Bridge())
            restored.restore()
            assertTrue(restored.accessibleDirectories().isEmpty())
            assertEquals(before, Files.readString(file))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(file.parent)
        }
    }

    @Test
    fun cancellationDoesNotWriteRegistry() {
        val file = Files.createTempDirectory("mac-bookmarks-test").resolve("access.json")
        try {
            assertEquals(null, MacDirectoryAccessStore(file, Bridge()).selectDirectory())
            assertFalse(Files.exists(file))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(file.parent)
        }
    }
}

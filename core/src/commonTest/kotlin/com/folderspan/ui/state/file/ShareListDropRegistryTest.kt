package com.folderspan.ui.state.file

import com.folderspan.data.file.FileSimpleInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareListDropRegistryTest {
    @AfterTest
    fun tearDown() {
        ShareListDropRegistry.clearForTest()
        ShareListDropResourceRegistry.releaseAll()
    }

    @Test
    fun deliverReturnsFalseWithoutReceiver() {
        assertFalse(ShareListDropRegistry.deliver(listOf(file("/drop/file.txt"))))
    }

    @Test
    fun registeredReceiverGetsNormalizedEntries() {
        var received = emptyList<FileSimpleInfo>()
        val receiver: ShareListDropReceiver = { files -> received = files }

        ShareListDropRegistry.register(receiver)

        assertTrue(
            ShareListDropRegistry.deliver(
                listOf(
                    file("/drop/folder", isDirectory = true),
                    file("/drop/folder/file.txt"),
                    file("/drop/folder/file.txt"),
                    file(""),
                )
            )
        )
        assertEquals(
            listOf("/drop/folder", "/drop/folder/file.txt"),
            received.map { item -> item.path },
        )
        assertTrue(received.first().isDirectory)
    }

    @Test
    fun normalizationDropsEmptyPayloadEntries() {
        assertTrue(normalizeShareListDropFiles(emptyList()).isEmpty())
        assertTrue(normalizeShareListDropFiles(listOf(file(""))).isEmpty())
    }

    @Test
    fun unregisterOnlyClearsCurrentReceiver() {
        var deliveries = 0
        val current: ShareListDropReceiver = { deliveries++ }
        val stale: ShareListDropReceiver = { deliveries += 100 }

        ShareListDropRegistry.register(current)

        assertFalse(ShareListDropRegistry.unregister(stale))
        assertTrue(ShareListDropRegistry.deliver(listOf(file("/drop/file.txt"))))
        assertEquals(1, deliveries)
        assertTrue(ShareListDropRegistry.unregister(current))
        assertFalse(ShareListDropRegistry.deliver(listOf(file("/drop/other.txt"))))
    }

    @Test
    fun resourceBatchIsReleasedAfterLastReferencedPathIsRemoved() {
        var releaseCount = 0
        ShareListDropResourceRegistry.register(listOf("/drop/a", "/drop/b")) {
            releaseCount++
        }

        ShareListDropResourceRegistry.retainReferencedPaths(listOf("/drop/b"))
        assertEquals(0, releaseCount)

        ShareListDropResourceRegistry.retainReferencedPaths(emptyList())
        assertEquals(1, releaseCount)
    }

    private fun file(path: String, isDirectory: Boolean = false): FileSimpleInfo {
        return FileSimpleInfo(
            name = path.substringAfterLast('/'),
            isDirectory = isDirectory,
            isHidden = false,
            path = path,
            mineType = if (isDirectory) "" else "text/plain",
            size = 0,
            createdDate = 0,
            updatedDate = 0,
        )
    }
}

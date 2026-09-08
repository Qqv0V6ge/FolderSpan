package com.folderspan.data.main.share

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.DeviceType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareSessionTest {
    @Test
    fun sessionShareOnlyExposesListAndReadCapabilities() = runTest {
        var listedPath = ""
        var listedRoots = false
        var readRange: LongRange? = null
        var disconnected = false
        val session = object : ShareSession {
            override val isActive = true

            override suspend fun listRoots(): Result<List<FileSimpleInfo>> {
                listedRoots = true
                return Result.success(listOf(file("/shared/report.txt")))
            }

            override suspend fun list(path: String): Result<List<FileSimpleInfo>> {
                listedPath = path
                return Result.success(listOf(file("/shared/report.txt")))
            }

            override suspend fun readBytes(
                path: String,
                startOffset: Long,
                endOffset: Long,
            ): Result<ByteArray> {
                assertEquals("/shared/report.txt", path)
                readRange = startOffset..endOffset
                return Result.success(byteArrayOf(1, 2, 3))
            }

            override suspend fun readStream(
                path: String,
                startOffset: Long,
                endOffset: Long,
                onChunk: suspend (ByteArray) -> Unit,
            ): Result<Boolean> = Result.success(true)

            override fun disconnect(): Boolean {
                disconnected = true
                return true
            }
        }
        val share = Share(
            id = "sender",
            name = "Sender",
            pathSeparator = "/",
            session = session,
            type = DeviceType.JVM,
        )

        val roots = share.getRootList().getOrThrow()
        val entries = share.getFileList("/shared").getOrThrow()
        val bytes = share.readBytes("/shared/report.txt", 0L, 2L).getOrThrow()

        assertEquals("/shared", listedPath)
        assertTrue(listedRoots)
        assertEquals(0L..2L, readRange)
        assertContentEquals(byteArrayOf(1, 2, 3), bytes)
        assertEquals(FileProtocol.Share, entries.single().protocol)
        assertEquals("sender", entries.single().protocolId)
        assertEquals(FileProtocol.Share, roots.single().protocol)
        assertEquals("sender", roots.single().protocolId)
        assertTrue(share.menuPermission.read)
        assertTrue(share.menuPermission.copy)
        assertFalse(share.menuPermission.write)
        assertFalse(share.menuPermission.paste)
        assertFalse(share.menuPermission.delete)
        assertTrue(share.disconnect())
        assertTrue(disconnected)
    }

    private fun file(path: String): FileSimpleInfo {
        return FileSimpleInfo(
            name = path.substringAfterLast('/'),
            isDirectory = false,
            isHidden = false,
            path = path,
            mineType = "text/plain",
            size = 3L,
            createdDate = 0L,
            updatedDate = 0L,
        )
    }
}

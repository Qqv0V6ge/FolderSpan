package com.folderspan.ui.components.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSelectorShareDataSourceTest {
    @Test
    fun shareDirectoriesHaveAnExplicitDataSourceAndKeepTheirEndpoint() = runTest {
        val session = Session()
        val share = share(session)
        val source = createFileSelectorDataSource(share)
        assertEquals(listOf("/allowed"), source.loadRootPaths().getOrThrow().map { it.path })
        val entries = source.loadFiles("/allowed").getOrThrow()
        assertEquals(listOf("/allowed/child", "/allowed/file.txt"), entries.map { it.path })
        assertTrue(entries.all { it.protocol == FileProtocol.Share && it.protocolId == "share" })
        assertFalse(share.menuPermission.write)
        assertFalse(share.menuPermission.paste)
    }

    @Test
    fun pathsOutsideAuthorizedDirectoryRootsNeverReachTheSessionListCall() = runTest {
        val session = Session()
        val source = createFileSelectorDataSource(share(session))
        listOf("/outside", "/allowed-other", "/file.txt", "/allowed/../outside").forEach { path ->
            assertTrue(source.loadFiles(path).isFailure)
        }
        assertTrue(session.listed.isEmpty())
    }

    @Test
    fun eachDirectoryLoadRechecksCurrentAuthorizationAndSessionActivity() = runTest {
        val session = Session()
        val source = createFileSelectorDataSource(share(session))
        assertTrue(source.loadFiles("/allowed").isSuccess)
        session.roots = listOf(directory("/other"))
        assertTrue(source.loadFiles("/allowed").isFailure)
        session.active = false
        assertTrue(source.loadRootPaths().isFailure)
        assertTrue(source.loadFiles("/other").isFailure)
        assertEquals(listOf("/allowed"), session.listed)
    }

    private fun share(session: ShareSession) = Share("share", "Share", "/", session, type = DeviceType.JVM)

    private class Session : ShareSession {
        var active = true
        override val isActive: Boolean get() = active
        var roots = listOf(directory("/allowed"), directory("/file.txt").copy(isDirectory = false))
        val listed = mutableListOf<String>()
        override suspend fun listRoots() = Result.success(roots)
        override suspend fun list(path: String): Result<List<FileSimpleInfo>> {
            listed += path
            return Result.success(listOf(directory("$path/child"), directory("$path/file.txt").copy(isDirectory = false)))
        }
        override suspend fun readBytes(path: String, startOffset: Long, endOffset: Long) = Result.success(byteArrayOf())
        override suspend fun readStream(path: String, startOffset: Long, endOffset: Long, onChunk: suspend (ByteArray) -> Unit) = Result.success(true)
        override fun disconnect(): Boolean { active = false; return true }
    }

    companion object {
        private fun directory(path: String) = FileSimpleInfo.pathFileSimpleInfo(path).copy(name = path.substringAfterLast('/'))
    }
}

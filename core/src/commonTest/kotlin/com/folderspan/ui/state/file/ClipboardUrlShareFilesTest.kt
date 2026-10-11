package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.service.http.clipboard.ClipboardStagedDownload
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadError
import com.folderspan.service.http.clipboard.ClipboardUrlShareInspectionResult
import com.folderspan.service.http.clipboard.ClipboardUrlShareInspector
import com.folderspan.test.runSuspendTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipboardUrlShareFilesTest {
    @AfterTest
    fun cleanup() = ClipboardUrlShareFiles.clear()

    @Test
    fun systemShareRootListsUrlFilesWhenPlatformHasNoContentUris() = runSuspendTest {
        var releases = 0
        val original = FileSimpleInfo("download.bin", isDirectory = false, isHidden = false,
            path = "/.folderspan-url-share/one/download.bin", mineType = "application/octet-stream",
            size = 12L, createdDate = 0L, updatedDate = 0L)
        val shared = ClipboardUrlShareFiles.add(original, "https://example.test/download.bin") { releases++ }
        val files = readSystemShareFiles { Result.failure(UnsupportedOperationException()) }.getOrThrow()
        assertEquals(listOf(shared), files)
        assertEquals(FileProtocol.Share, shared.protocol)
        assertEquals(SYSTEM_SHARE_DESK_ID, shared.protocolId)
        assertEquals(12L, files.single().size)
        assertEquals("https://example.test/download.bin", ClipboardUrlShareFiles.downloadUrl(shared))
        assertNull(ClipboardUrlShareFiles.downloadUrl(original))
        assertEquals(0, releases)
        ClipboardUrlShareFiles.clear()
        assertEquals(1, releases)
    }

    @Test
    fun openingAnotherUrlKeepsBothEntriesAvailable() = runSuspendTest {
        for (name in listOf("first.bin", "second.bin")) {
            val file = FileSimpleInfo(name, isDirectory = false, isHidden = false,
                path = "/.folderspan-url-share/$name", mineType = "application/octet-stream",
                size = 1L, createdDate = 0L, updatedDate = 0L)
            ClipboardUrlShareFiles.add(file, "https://example.test/$name") {}
        }
        assertEquals(listOf("first.bin", "second.bin"),
            readSystemShareFiles { Result.success(emptyList()) }.getOrThrow().map { it.name })
    }

    @Test
    fun downloadedFileIsAddedToShareListAndReleasedAfterItsLastReference() {
        var releases = 0
        val staged = ClipboardStagedDownload(
            id = "download-1",
            displayName = "report.pdf",
            size = 128L,
            contentType = "application/pdf",
            localPath = "/tmp/report.pdf",
            release = { releases++ },
        )

        assertTrue(ClipboardUrlShareFiles.add(staged))
        val shared = ClipboardUrlShareFiles.list().single()
        assertEquals("/tmp/report.pdf", shared.path)
        assertEquals("report.pdf", shared.name)
        assertEquals(128L, shared.size)
        assertEquals("application/pdf", shared.mineType)
        assertEquals(FileProtocol.Share, shared.protocol)
        assertEquals(SYSTEM_SHARE_DESK_ID, shared.protocolId)
        assertNull(ClipboardUrlShareFiles.downloadUrl(shared))
        assertEquals(0, releases)

        ClipboardUrlShareFiles.clear()
        assertEquals(1, releases)
    }

    @Test
    fun downloadedFileWithoutLocalPathIsNotAdded() {
        val staged = ClipboardStagedDownload(
            id = "invalid-download", displayName = "report.pdf", size = 128L,
            contentType = "application/pdf", localPath = " ", release = {},
        )
        assertFalse(ClipboardUrlShareFiles.add(staged))
        assertTrue(ClipboardUrlShareFiles.list().isEmpty())
    }

    @Test
    fun openedUrlMetadataAppearsInSystemShareFileList() = runSuspendTest {
        var inspections = 0
        var releases = 0
        val remoteFile = FileSimpleInfo(
            name = "remote.bin", isDirectory = false, isHidden = false,
            path = "/.folderspan-url-share/1/remote.bin", mineType = "application/octet-stream",
            size = 512L, createdDate = 0L, updatedDate = 0L,
        )
        val url = "https://example.test/remote.bin"
        val inspector = ClipboardUrlShareInspector { requestedUrl ->
            assertEquals(url, requestedUrl)
            inspections++
            ClipboardUrlShareInspectionResult.Success(remoteFile) { releases++ }
        }

        assertTrue(ClipboardUrlShareFiles.inspectAndAdd(url, inspector))
        assertEquals(1, inspections)
        val shared = ClipboardUrlShareFiles.list().single()
        assertEquals(remoteFile.name, shared.name)
        assertEquals(remoteFile.size, shared.size)
        assertEquals(FileProtocol.Share, shared.protocol)
        assertEquals(SYSTEM_SHARE_DESK_ID, shared.protocolId)
        assertEquals(url, ClipboardUrlShareFiles.downloadUrl(shared))
        assertEquals(0, releases)

        ClipboardUrlShareFiles.clear()
        assertEquals(1, releases)
    }

    @Test
    fun failedUrlInspectionDoesNotChangeShareList() = runSuspendTest {
        val existing = FileSimpleInfo("existing.bin", isDirectory = false, isHidden = false,
            path = "/existing.bin", mineType = "application/octet-stream",
            size = 1L, createdDate = 0L, updatedDate = 0L)
        ClipboardUrlShareFiles.add(existing, release = {})
        val before = ClipboardUrlShareFiles.list()
        val inspector = ClipboardUrlShareInspector {
            ClipboardUrlShareInspectionResult.Failure(ClipboardUrlDownloadError.HttpFailure)
        }

        assertFalse(ClipboardUrlShareFiles.inspectAndAdd("https://example.test/missing.bin", inspector))
        assertEquals(before, ClipboardUrlShareFiles.list())
    }
}

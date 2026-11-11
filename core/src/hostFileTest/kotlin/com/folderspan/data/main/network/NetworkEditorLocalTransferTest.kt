package com.folderspan.data.main.network

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.NetworkEditorTransferTest.DownloadCall
import com.folderspan.data.main.network.NetworkEditorTransferTest.RecordingNetworkClient
import com.folderspan.data.main.network.NetworkEditorTransferTest.UploadCall
import com.folderspan.service.network.StorageProvider
import com.folderspan.test.runSuspendTest
import okio.FileSystem
import okio.use
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkEditorLocalTransferTest {
    @Test
    fun editorTransfersDelegateWithoutBufferingWholeFile() = runSuspendTest {
        val client = RecordingNetworkClient()
        val network = object : Network(
            name = "S3",
            pathSeparator = "/",
            protocol = NetworkProtocol.S3.name,
            host = "https://example.invalid",
            username = "",
            password = "",
        ) {
            override val client: StorageProvider = client
        }
        val file = FileSimpleInfo(
            name = "large.txt",
            isDirectory = false,
            isHidden = false,
            path = "/large.txt",
            mineType = ".txt",
            size = 128L * 1024 * 1024,
            createdDate = 0L,
            updatedDate = 0L,
            protocol = FileProtocol.Network,
            protocolId = network.protocolId,
        )
        val downloadProgress = mutableListOf<Pair<Long, Long>>()

        withTemporaryUploadSource(file.size) { localPath ->
            val download = network.downloadFileToLocal(file, "/cache/editor-large.txt") { completed, total ->
                downloadProgress += completed to total
            }
            val upload = network.uploadFileFromLocal(
                localPath = localPath,
                remotePath = file.path,
                size = file.size,
            )

            assertTrue(download.getOrThrow())
            assertTrue(upload.getOrThrow())
            assertEquals(
                DownloadCall(file.path, "/cache/editor-large.txt", file.size),
                client.downloadCall,
            )
            assertEquals(
                UploadCall(localPath, file.path, file.size),
                client.uploadCall,
            )
            assertEquals(listOf(file.size / 2 to file.size, file.size to file.size), downloadProgress)
        }
    }

    @Test
    fun uploadFileFromLocalKeepsNestedSafeRelativePath() = runSuspendTest {
        val client = RecordingNetworkClient()
        val network = object : Network(
            name = "S3",
            pathSeparator = "/",
            protocol = NetworkProtocol.S3.name,
            host = "https://example.invalid",
            username = "",
            password = "",
        ) {
            override val client: StorageProvider = client
        }

        withTemporaryUploadSource(3L) { localPath ->
            val result = network.uploadFileFromLocal(
                localPath = localPath,
                remotePath = "/dst/folder/sub/b.txt",
                size = 3L,
            )

            assertTrue(result.getOrThrow())
            assertEquals(
                UploadCall(localPath, "/dst/folder/sub/b.txt", 3L),
                client.uploadCall,
            )
        }
    }

    private suspend fun withTemporaryUploadSource(size: Long, block: suspend (String) -> Unit) {
        val fs = FileSystem.SYSTEM
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("network-editor-source-" + Random.nextLong(1, Long.MAX_VALUE))
        var created = false
        try {
            fs.openReadWrite(path, mustCreate = true).use { handle ->
                created = true
                // A real sparse file preserves the large-source contract without allocating its body.
                handle.write(size - 1, byteArrayOf(1), 0, 1)
            }
            block(path.toString())
        } finally {
            if (created) fs.delete(path)
        }
    }
}

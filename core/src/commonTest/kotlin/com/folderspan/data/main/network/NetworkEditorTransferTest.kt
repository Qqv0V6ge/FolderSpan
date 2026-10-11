package com.folderspan.data.main.network

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.network.NetworkClient
import com.folderspan.service.network.NetworkFileEntry
import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkEditorTransferTest {
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
            override val client: NetworkClient = client
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

        val download = network.downloadFileToLocal(file, "/cache/editor-large.txt") { completed, total ->
            downloadProgress += completed to total
        }
        val upload = network.uploadFileFromLocal(
            localPath = "/cache/editor-save.txt",
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
            UploadCall("/cache/editor-save.txt", file.path, file.size),
            client.uploadCall,
        )
        assertEquals(listOf(file.size / 2 to file.size, file.size to file.size), downloadProgress)
    }

    @Test
    fun uploadFromSourceRejectsParentDirectoryPathBeforeClientWrite() = runSuspendTest {
        val client = RecordingNetworkClient()
        val network = object : Network(
            name = "S3",
            pathSeparator = "/",
            protocol = NetworkProtocol.S3.name,
            host = "https://example.invalid",
            username = "",
            password = "",
        ) {
            override val client: NetworkClient = client
        }

        val result = network.uploadFromSource(
            remotePath = "/dst/folder/../outside.bin",
            size = 4L,
            onProgress = { _, _ -> },
            readChunk = { null },
        )

        assertTrue(result.isFailure)
        assertTrue(client.uploadFromSourceCalls.isEmpty())
    }

    @Test
    fun createFolderRejectsParentDirectoryName() = runSuspendTest {
        val client = RecordingNetworkClient()
        val network = object : Network(
            name = "S3",
            pathSeparator = "/",
            protocol = NetworkProtocol.S3.name,
            host = "https://example.invalid",
            username = "",
            password = "",
        ) {
            override val client: NetworkClient = client
        }

        val result = network.createFolder("/dst", "..")

        assertTrue(result.isFailure)
        assertTrue(client.createdFolders.isEmpty())
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
            override val client: NetworkClient = client
        }

        val result = network.uploadFileFromLocal(
            localPath = "/cache/sub/b.txt",
            remotePath = "/dst/folder/sub/b.txt",
            size = 3L,
        )

        assertTrue(result.getOrThrow())
        assertEquals(
            UploadCall("/cache/sub/b.txt", "/dst/folder/sub/b.txt", 3L),
            client.uploadCall,
        )
    }

    private data class DownloadCall(val remotePath: String, val localPath: String, val size: Long)
    private data class UploadCall(val localPath: String, val remotePath: String, val size: Long)

    private class RecordingNetworkClient : NetworkClient {
        var downloadCall: DownloadCall? = null
        var uploadCall: UploadCall? = null
        val uploadFromSourceCalls = mutableListOf<String>()
        val createdFolders = mutableListOf<String>()

        override suspend fun list(
            path: String,
            requestId: String?,
            batchId: String?,
        ): Result<List<NetworkFileEntry>> = Result.success(emptyList())

        override suspend fun download(
            remotePath: String,
            localPath: String,
            size: Long,
            onProgress: (Long, Long) -> Unit,
        ): Result<Boolean> {
            downloadCall = DownloadCall(remotePath, localPath, size)
            onProgress(size / 2, size)
            onProgress(size, size)
            return Result.success(true)
        }

        override suspend fun upload(
            localPath: String,
            remotePath: String,
            size: Long,
            onProgress: (Long, Long) -> Unit,
        ): Result<Boolean> {
            uploadCall = UploadCall(localPath, remotePath, size)
            return Result.success(true)
        }

        override suspend fun uploadFromSource(
            remotePath: String,
            size: Long,
            onProgress: (Long, Long) -> Unit,
            readChunk: suspend () -> ByteArray?,
        ): Result<Boolean> {
            uploadFromSourceCalls += remotePath
            while (readChunk() != null) {
                // drain
            }
            return Result.success(true)
        }

        override suspend fun rename(path: String, newPath: String): Result<Boolean> = Result.success(true)

        override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> = Result.success(true)

        override suspend fun createFolder(path: String): Result<Boolean> {
            createdFolders += path
            return Result.success(true)
        }

        override suspend fun createFile(path: String): Result<Boolean> = Result.success(true)
    }
}

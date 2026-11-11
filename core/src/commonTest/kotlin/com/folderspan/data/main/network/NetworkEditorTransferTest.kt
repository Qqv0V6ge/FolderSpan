package com.folderspan.data.main.network

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.network.StorageProvider
import com.folderspan.service.network.NetworkFileEntry
import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkEditorTransferTest {
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
            override val client: StorageProvider = client
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
            override val client: StorageProvider = client
        }

        val result = network.createFolder("/dst", "..")

        assertTrue(result.isFailure)
        assertTrue(client.createdFolders.isEmpty())
    }

    @Test
    fun renameDirectoryWithTrailingSeparatorKeepsDestinationInParent() = runSuspendTest {
        val client = RecordingNetworkClient()
        val network = object : Network(
            name = "Nextcloud",
            pathSeparator = "/",
            protocol = NetworkProtocol.Nextcloud.name,
            host = "https://example.invalid",
            username = "",
            password = "",
        ) {
            override val client: StorageProvider = client
        }

        for ((path, expected) in listOf(
            "/old/" to "/renamed",
            "/parent/old/" to "/parent/renamed",
            "/parent/old" to "/parent/renamed",
        )) {
            val file = FileSimpleInfo.nullFileSimpleInfo().withCopy(
                name = "old",
                path = path,
                isDirectory = true,
                protocol = FileProtocol.Network,
                protocolId = network.protocolId,
            )

            assertTrue(network.rename(file, "renamed").getOrThrow())
            assertEquals(path to expected, client.renameCalls.last())
        }
    }

    internal data class DownloadCall(val remotePath: String, val localPath: String, val size: Long)
    internal data class UploadCall(val localPath: String, val remotePath: String, val size: Long)

    internal class RecordingNetworkClient : StorageProvider {
        var downloadCall: DownloadCall? = null
        var uploadCall: UploadCall? = null
        val uploadFromSourceCalls = mutableListOf<String>()
        val createdFolders = mutableListOf<String>()
        val renameCalls = mutableListOf<Pair<String, String>>()

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

        override suspend fun rename(path: String, newPath: String): Result<Boolean> {
            renameCalls += path to newPath
            return Result.success(true)
        }

        override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> = Result.success(true)

        override suspend fun createFolder(path: String): Result<Boolean> {
            createdFolders += path
            return Result.success(true)
        }

        override suspend fun createFile(path: String): Result<Boolean> = Result.success(true)
    }
}

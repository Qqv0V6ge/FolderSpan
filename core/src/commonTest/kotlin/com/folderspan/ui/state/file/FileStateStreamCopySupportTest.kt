package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.exception.AuthorityException
import com.folderspan.service.http.archive.selectSmallFileArchiveBatches
import com.folderspan.service.operation.TraversalEndpointKind
import com.folderspan.test.runSuspendTest
import com.folderspan.ui.state.main.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileStateStreamCopySupportTest {

    @Test
    fun systemShareNeverResolvesARemoteShareSession() {
        assertFalse(shouldResolveRemoteShareSession(SYSTEM_SHARE_DESK_ID))
        assertFalse(shouldResolveRemoteShareSession(""))
        assertFalse(shouldResolveRemoteShareSession("   "))
        assertTrue(shouldResolveRemoteShareSession("remote-share-id"))
    }

    @Test
    fun sequentialRangeReadFeedsStreamingUploadWithoutLocalStaging() = runSuspendTest {
        val payload = "device-or-share-bytes".encodeToByteArray()
        val uploaded = ArrayList<Byte>()
        val progress = mutableListOf<Pair<Long, Long>>()
        val deleted = mutableListOf<String>()
        val readChunk = sequentialRangeReadChunk(
            size = payload.size.toLong(),
            chunkSize = 7,
            readRange = { startOffset, endOffset ->
                Result.success(payload.copyOfRange(startOffset.toInt(), endOffset.toInt()))
            },
        )

        val result = streamUploadFileToNetwork(
            remotePath = "/remote/out.bin",
            size = payload.size.toLong(),
            onProgress = { done, total -> progress += done to total },
            readChunk = readChunk,
            uploadFromSource = { remotePath, size, onProgress, next ->
                assertEquals("/remote/out.bin", remotePath)
                assertEquals(payload.size.toLong(), size)
                while (true) {
                    val chunk = next() ?: break
                    uploaded += chunk.toList()
                    onProgress(uploaded.size.toLong(), size)
                }
                Result.success(true)
            },
            deletePartial = { path -> deleted += path },
        )

        assertTrue(result.isSuccess)
        assertContentEquals(payload, uploaded.toByteArray())
        assertEquals(emptyList(), deleted)
        assertTrue(progress.isNotEmpty())
        assertEquals(payload.size.toLong() to payload.size.toLong(), progress.last())
    }

    @Test
    fun failedStreamingUploadDeletesPartialRemoteAndFails() = runSuspendTest {
        val deleted = mutableListOf<String>()
        val result = streamUploadFileToNetwork(
            remotePath = "/remote/partial.bin",
            size = 8L,
            onProgress = { _, _ -> },
            readChunk = sequentialRangeReadChunk(
                size = 8L,
                chunkSize = 4,
                readRange = { _, _ -> Result.success(ByteArray(4)) },
            ),
            uploadFromSource = { _, _, _, next ->
                next()
                Result.failure(IllegalStateException("mid-upload"))
            },
            deletePartial = { path -> deleted += path },
        )

        assertTrue(result.isFailure)
        assertEquals(listOf("/remote/partial.bin"), deleted)
    }

    @Test
    fun canceledStreamingUploadDeletesPartialRemoteAndRethrows() = runSuspendTest {
        val deleted = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            streamUploadFileToNetwork(
                remotePath = "/remote/canceled.bin",
                size = 8L,
                onProgress = { _, _ -> },
                readChunk = { ByteArray(4) },
                uploadFromSource = { _, _, _, _ ->
                    throw CancellationException("canceled")
                },
                deletePartial = { path -> deleted += path },
            )
        }

        assertEquals("canceled", thrown.message)
        assertEquals(listOf("/remote/canceled.bin"), deleted)
    }

    @Test
    fun zeroByteFileCreatesRemoteEmptyFileInsteadOfUploading() = runSuspendTest {
        val network = RecordingNetworkAccess()
        val result = createRemoteEmptyFile(network, "/remote/empty.bin", "/")

        assertTrue(result.getOrThrow())
        assertEquals(listOf("/remote/empty.bin"), network.createdFiles)
        assertTrue(network.uploaded.isEmpty())
        assertTrue(network.createdFolders.isEmpty())
    }

    @Test
    fun directoryCopyCreatesFoldersThenStreamsEachFileAndReportsEntryProgress() = runSuspendTest {
        val network = RecordingNetworkAccess()
        val copied = mutableListOf<String>()
        val progress = mutableListOf<Triple<Int, Int, String>>()
        val source = directory("/src/folder")
        val destination = directory("/dst/folder")
        val tree = mapOf(
            "/src/folder" to listOf(
                directory("/src/folder/sub"),
                file("/src/folder/a.txt", size = 5L),
                file("/src/folder/empty.bin", size = 0L),
            ),
            "/src/folder/sub" to listOf(
                file("/src/folder/sub/b.txt", size = 3L),
            ),
        )

        val result = streamCopyDirectoryToNetwork(
            source = source,
            destination = destination,
            sourceSeparator = "/",
            destSeparator = "/",
            networkAccess = network,
            ensureRunning = {},
            traversalKind = TraversalEndpointKind.Device,
            listChildren = { directory ->
                Result.success(tree[directory.path].orEmpty())
            },
            copyFile = { sourceFile, destPath ->
                copied += destPath
                if (sourceFile.size == 0L) {
                    createRemoteEmptyFile(network, destPath, "/")
                } else {
                    Result.success(true)
                }
            },
            onEntryProgress = { processed, total, path ->
                progress += Triple(processed, total, path)
            },
        )

        assertTrue(result.getOrThrow())
        assertEquals(listOf("/dst/folder", "/dst/folder/sub"), network.createdFolders)
        assertEquals(
            listOf("/dst/folder/a.txt", "/dst/folder/empty.bin", "/dst/folder/sub/b.txt").sorted(),
            copied.sorted(),
        )
        assertEquals(listOf("/dst/folder/empty.bin"), network.createdFiles)
        assertEquals(4, progress.last().first)
        assertEquals(4, progress.last().second)
        assertEquals(progress.map { item -> item.first }, listOf(1, 2, 3, 4))
        assertTrue(progress.all { item -> item.second == 4 })
    }

    @Test
    fun emptyDirectoryCreatesFolderAndReportsSingleEntryProgress() = runSuspendTest {
        val network = RecordingNetworkAccess()
        val progress = mutableListOf<Triple<Int, Int, String>>()

        val result = streamCopyDirectoryToNetwork(
            source = directory("/src/empty"),
            destination = directory("/dst/empty"),
            sourceSeparator = "/",
            destSeparator = "/",
            networkAccess = network,
            ensureRunning = {},
            traversalKind = TraversalEndpointKind.Share,
            listChildren = { Result.success(emptyList()) },
            copyFile = { _, _ -> error("no files expected") },
            onEntryProgress = { processed, total, path ->
                progress += Triple(processed, total, path)
            },
        )

        assertTrue(result.getOrThrow())
        assertEquals(listOf("/dst/empty"), network.createdFolders)
        assertEquals(listOf(Triple(1, 1, "/dst/empty")), progress)
        assertTrue(network.uploaded.isEmpty())
        assertTrue(network.createdFiles.isEmpty())
    }

    @Test
    fun folderProgressUsesEntryCountsNotChunkBlocks() {
        assertEquals(3, streamNetworkProgressBlocks((2L * 1024L * 1024L) + 1L))
        assertEquals(1, streamNetworkProgressBlocks(0L))
        assertEquals(2, streamNetworkProgressCur(1024L * 1024L + 1L, 3L * 1024L * 1024L))
    }

    @Test
    fun relativeRemotePathRejectsParentDirectorySegments() {
        val error = assertFailsWith<AuthorityException> {
            relativeRemotePath(
                sourceRoot = "/src/folder",
                sourcePath = "/src/folder/../outside.txt",
                destRoot = "/dst/folder",
                sourceSeparator = "/",
                destSeparator = "/",
            )
        }
        assertEquals(AppStrings.ui_remote_path_invalid, error.message)
    }

    @Test
    fun relativeRemotePathKeepsNestedSafeRelativePaths() {
        assertEquals(
            "/dst/folder/sub/b.txt",
            relativeRemotePath(
                sourceRoot = "/src/folder",
                sourcePath = "/src/folder/sub/b.txt",
                destRoot = "/dst/folder",
                sourceSeparator = "/",
                destSeparator = "/",
            ),
        )
    }

    @Test
    fun resolveLocalCopyPathRejectsParentSegments() {
        val parent = assertFailsWith<AuthorityException> {
            resolveLocalCopyPath(
                sourceRoot = "/src/folder",
                sourcePath = "/src/folder/../outside.txt",
                destRoot = "/dst/folder",
                sourceSeparator = "/",
            )
        }
        assertEquals(AppStrings.ui_remote_path_invalid, parent.message)

        val nestedParent = assertFailsWith<AuthorityException> {
            resolveLocalCopyPath(
                sourceRoot = "/src/folder",
                sourcePath = "/src/folder/sub/../../outside.txt",
                destRoot = "/dst/folder",
                sourceSeparator = "/",
            )
        }
        assertEquals(AppStrings.ui_remote_path_invalid, nestedParent.message)
    }

    @Test
    fun resolveLocalCopyPathKeepsNestedSafeRelativePaths() {
        val destRoot = "/dst/folder"
        val resolved = resolveLocalCopyPath(
            sourceRoot = "/src/folder",
            sourcePath = "/src/folder/sub/b.txt",
            destRoot = destRoot,
            sourceSeparator = "/",
        )
        assertTrue(resolved.startsWith(destRoot))
        assertTrue(resolved.contains("sub"))
        assertTrue(resolved.endsWith("b.txt"))
        assertFalse(resolved.contains(".."))
    }

    @Test
    fun resolveDirectoryCopyTargetPathRejectsParentOnDeviceDestination() {
        val error = assertFailsWith<AuthorityException> {
            resolveDirectoryCopyTargetPath(
                sourceRoot = "/src/folder",
                sourcePath = "/src/folder/../outside.txt",
                destRoot = "/dst/folder",
                sourceSeparator = "/",
                destSeparator = "/",
                destIsLocal = false,
            )
        }
        assertEquals(AppStrings.ui_remote_path_invalid, error.message)
    }

    @Test
    fun archivePlannerLeavesUnsafeRelativePathsOutOfBatchesAndCopyJailRejectsThem() {
        val selection = selectSmallFileArchiveBatches(
            items = listOf(
                file("/src/folder/ok.txt", size = 8L),
                file("/src/folder/../outside.txt", size = 8L),
            ),
            sourcePath = FileSimpleInfo::path,
            relativePath = { entry -> entry.path.removePrefix("/src/folder").trimStart('/') },
            size = FileSimpleInfo::size,
        )
        val batchPaths = selection.batches.flatMap { batch ->
            batch.requestEntries.map { request -> request.relativePath }
        }
        assertTrue(batchPaths.none { path -> path.contains("..") })
        val remainingUnsafe = selection.remainingItems.filter { entry -> entry.path.contains("..") }
        assertTrue(remainingUnsafe.isNotEmpty())
        remainingUnsafe.forEach { entry ->
            assertFailsWith<AuthorityException> {
                resolveLocalCopyPath(
                    sourceRoot = "/src/folder",
                    sourcePath = entry.path,
                    destRoot = "/dst/folder",
                    sourceSeparator = "/",
                )
            }
        }
    }

    @Test
    fun directoryCopyRejectsParentDirectoryEntriesBeforeWriting() = runSuspendTest {
        val network = RecordingNetworkAccess()
        val copied = mutableListOf<String>()
        val result = streamCopyDirectoryToNetwork(
            source = directory("/src/folder"),
            destination = directory("/dst/folder"),
            sourceSeparator = "/",
            destSeparator = "/",
            networkAccess = network,
            ensureRunning = {},
            traversalKind = TraversalEndpointKind.Device,
            listChildren = { directory ->
                Result.success(
                    if (directory.path == "/src/folder") {
                        listOf(file("/src/folder/../outside.txt", size = 4L))
                    } else {
                        emptyList()
                    },
                )
            },
            copyFile = { _, destPath ->
                copied += destPath
                Result.success(true)
            },
        )

        assertTrue(result.isFailure)
        assertIs<AuthorityException>(result.exceptionOrNull())
        assertEquals(emptyList(), copied)
        assertTrue(network.uploaded.isEmpty())
        assertTrue(network.createdFolders.isEmpty())
        assertTrue(network.createdFiles.isEmpty())
        assertTrue(network.uploaded.none { item -> item.first.contains("outside") })
    }

    @Test
    fun createRemoteFolderRejectsParentDirectoryPath() = runSuspendTest {
        val network = RecordingNetworkAccess()
        val result = createRemoteFolder(network, "/dst/folder/..", "/")

        assertTrue(result.isFailure)
        assertIs<AuthorityException>(result.exceptionOrNull())
        assertTrue(network.createdFolders.isEmpty())
    }

    private fun directory(path: String): FileSimpleInfo = file(path, isDirectory = true, size = 0L)

    private fun file(
        path: String,
        isDirectory: Boolean = false,
        size: Long = 1L,
    ): FileSimpleInfo = FileSimpleInfo(
        name = path.substringAfterLast('/'),
        isDirectory = isDirectory,
        isHidden = false,
        path = path,
        mineType = if (isDirectory) "" else "text/plain",
        size = size,
        createdDate = 0L,
        updatedDate = 0L,
        protocol = FileProtocol.Device,
        protocolId = "device-1",
    )
}

private class RecordingNetworkAccess : NetworkAccess {
    override val protocolId: String = "network-1"
    val uploaded = mutableListOf<Pair<String, ByteArray>>()
    val createdFolders = mutableListOf<String>()
    val createdFiles = mutableListOf<String>()
    val deleted = mutableListOf<String>()

    override suspend fun getList(
        path: String,
        requestId: String?,
        batchId: String?,
    ): Result<List<FileSimpleInfo>> = Result.success(emptyList())

    override fun traverse(
        path: String,
        requestId: String?,
        batchId: String?,
    ): Flow<Result<List<FileSimpleInfo>>> = flow {
        emit(Result.success(emptyList()))
    }

    override fun getRootPaths(): List<PathInfo> = emptyList()

    override fun getFile(path: String): Result<FileSimpleInfo> =
        Result.failure(IllegalStateException(path))

    override suspend fun copyTo(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
    ): Result<Boolean> = Result.failure(UnsupportedOperationException())

    override suspend fun downloadFileToLocal(
        file: FileSimpleInfo,
        localPath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Boolean> = Result.failure(UnsupportedOperationException())

    override suspend fun uploadFileFromLocal(
        localPath: String,
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit,
    ): Result<Boolean> = Result.failure(UnsupportedOperationException())

    override suspend fun uploadFromSource(
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit,
        readChunk: suspend () -> ByteArray?,
    ): Result<Boolean> {
        val payload = ArrayList<Byte>()
        while (true) {
            val chunk = readChunk() ?: break
            payload += chunk.toList()
            onProgress(payload.size.toLong(), if (size >= 0L) size else payload.size.toLong())
        }
        uploaded += remotePath to payload.toByteArray()
        return Result.success(true)
    }

    override suspend fun rename(path: String, oldName: String, newName: String): Result<Boolean> =
        Result.success(true)

    override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> {
        deleted += path
        return Result.success(true)
    }

    override suspend fun createFolder(path: String, name: String): Result<Boolean> {
        createdFolders += joinRemotePath(path, name, "/")
        return Result.success(true)
    }

    override suspend fun createFile(path: String, name: String): Result<Boolean> {
        createdFiles += joinRemotePath(path, name, "/")
        return Result.success(true)
    }
}

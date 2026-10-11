package com.folderspan.service.http.archive

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.data.CopyPathControlAction
import com.folderspan.service.data.CopyPathProgress
import com.folderspan.service.data.RenameInfo
import com.folderspan.service.file.DeviceFileClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmallFileArchiveTransferCoordinatorTest {
    @Test
    fun negotiationUsesNoneForLanAndZstdForWan() = runTest {
        val source = FakeArchiveDeviceClient(localCapabilities())
        val target = FakeArchiveDeviceClient(localCapabilities())

        assertEquals(
            FolderSpanArchiveCompressionCodec.NONE,
            SmallFileArchiveTransferCoordinator(
                source,
                target,
                SmallFileArchiveTransportPreference.LAN,
            ).negotiate().codec,
        )
        assertEquals(
            FolderSpanArchiveCompressionCodec.ZSTD,
            SmallFileArchiveTransferCoordinator(
                source,
                target,
                SmallFileArchiveTransportPreference.WAN,
            ).negotiate().codec,
        )
    }

    @Test
    fun negotiationRejectsPeersWithoutCurrentArchiveCapabilities() = runTest {
        val source = FakeArchiveDeviceClient(localCapabilities())
        val target = FakeArchiveDeviceClient(FolderSpanArchiveStreamCapabilities.unsupported())

        assertFailsWith<FolderSpanArchiveUnsupportedException> {
            SmallFileArchiveTransferCoordinator(
                source,
                target,
                SmallFileArchiveTransportPreference.LAN,
            ).negotiate()
        }
    }

    @Test
    fun selectionHonorsBothPeersAdvertisedFileLimit() = runTest {
        val source = FakeArchiveDeviceClient(localCapabilities())
        val target = FakeArchiveDeviceClient(localCapabilities().copy(maxFileBytes = 2L))
        val items = listOf("one" to 1L, "two" to 2L, "large" to 3L)

        val selection = SmallFileArchiveTransferCoordinator(
            source,
            target,
            SmallFileArchiveTransportPreference.LAN,
        ).select(
            items = items,
            sourcePath = { (name, _) -> "/source/$name" },
            relativePath = { (name, _) -> name },
            size = { (_, size) -> size },
        )

        assertEquals(listOf("one", "two"), selection?.batches?.single()?.items?.map { it.first })
        assertEquals(listOf("large"), selection?.remainingItems?.map { it.first })
    }

    @Test
    fun deviceRelayStreamsFsar2AndUsesTargetCommitResult() = runTest {
        val data = mapOf(
            "/source/a" to byteArrayOf(1, 2, 3),
            "/source/b" to byteArrayOf(4, 5),
        )
        val source = FakeArchiveDeviceClient(localCapabilities(), sourceFiles = data)
        val target = FakeArchiveDeviceClient(localCapabilities())
        val entries = data.map { (path, bytes) ->
            FolderSpanArchiveEntryRequest(path, path.substringAfterLast('/'), bytes.size.toLong())
        }

        val result = SmallFileArchiveTransferCoordinator(
            source,
            target,
            SmallFileArchiveTransportPreference.WAN,
        ).transferBatch(entries, "/target").getOrThrow()

        assertEquals(listOf("a", "b"), result.completedRelativePaths)
        assertContentEquals(data.getValue("/source/a"), target.receivedFiles.getValue("a"))
        assertContentEquals(data.getValue("/source/b"), target.receivedFiles.getValue("b"))
        assertEquals(FolderSpanArchiveCompressionCodec.ZSTD, source.lastReadOptions?.codec)
        assertEquals(FolderSpanArchiveCompressionCodec.ZSTD, target.lastPreludeCodec)
        assertEquals(2, SMALL_FILE_ARCHIVE_RELAY_CAPACITY)
    }

    @Test
    fun deviceRelayReportsOnlyTargetCommittedEntriesAndProgressesThemOnce() = runTest {
        val data = linkedMapOf(
            "/source/first" to byteArrayOf(1, 2, 3),
            "/source/second" to byteArrayOf(4, 5),
            "/source/third" to byteArrayOf(6),
        )
        val source = FakeArchiveDeviceClient(localCapabilities(), sourceFiles = data)
        val target = FakeArchiveDeviceClient(localCapabilities(), failCompletingPath = "second")
        val entries = data.map { (path, bytes) ->
            FolderSpanArchiveEntryRequest(path, path.substringAfterLast('/'), bytes.size.toLong())
        }
        val progress = mutableMapOf<String, Long>()

        val result = SmallFileArchiveTransferCoordinator(
            source,
            target,
            SmallFileArchiveTransportPreference.WAN,
        ).transferBatch(entries, "/target") { path, bytes ->
            progress[path] = progress.getOrElse(path) { 0L } + bytes
        }

        assertTrue(result.isFailure)
        val committed = result.exceptionOrNull()!!.completedArchiveTransferResult()
        assertEquals(listOf("first"), committed.completedRelativePaths)
        assertEquals(3L, committed.committedFileBytes)
        assertEquals(mapOf("first" to 3L), progress)
        assertContentEquals(data.getValue("/source/first"), target.receivedFiles.getValue("first"))
        assertTrue("second" !in target.receivedFiles)
        assertTrue("third" !in target.receivedFiles)
    }

    private fun localCapabilities() = FolderSpanArchiveStreamCapabilities(
        codecs = listOf(FolderSpanArchiveCompressionCodec.NONE, FolderSpanArchiveCompressionCodec.ZSTD),
        maxEntries = FOLDER_SPAN_ARCHIVE_MAX_ENTRIES,
        maxFileBytes = FOLDER_SPAN_ARCHIVE_SMALL_FILE_THRESHOLD_BYTES,
        maxBatchBytes = FOLDER_SPAN_ARCHIVE_MAX_PAYLOAD_BYTES,
    )
}

private class FakeArchiveDeviceClient(
    private val capabilities: FolderSpanArchiveStreamCapabilities,
    private val sourceFiles: Map<String, ByteArray> = emptyMap(),
    private val failCompletingPath: String? = null,
) : DeviceFileClient {
    val receivedFiles = linkedMapOf<String, ByteArray>()
    var lastReadOptions: FolderSpanArchiveStreamOptions? = null
    var lastPreludeCodec: Int? = null

    override suspend fun archiveCapabilities(): FolderSpanArchiveStreamCapabilities = capabilities

    override suspend fun readArchiveStream(
        request: FolderSpanArchiveReadRequest,
        onChunk: suspend (ByteArray) -> Unit,
    ): Result<Boolean> = runCatching {
        lastReadOptions = request.options
        ArchiveStreamEncoder.encode(request.entries, request.options) { entry ->
            flowOf(sourceFiles.getValue(entry.sourcePath))
        }.collect { chunk -> onChunk(chunk) }
        true
    }

    override suspend fun writeArchiveStream(
        request: FolderSpanArchiveWriteRequest,
        chunks: Flow<ByteArray>,
    ): Result<FolderSpanArchiveTransferResult> = runCatching {
        val monitored = kotlinx.coroutines.flow.flow {
            chunks.collect { chunk ->
                if (lastPreludeCodec == null && chunk.size >= FolderSpanArchiveCodec.MAGIC.size + 4) {
                    val headerSize = FolderSpanArchiveCodec.readHeaderLength(chunk, FolderSpanArchiveCodec.MAGIC.size)
                    if (chunk.size >= FolderSpanArchiveCodec.MAGIC.size + 4 + headerSize) {
                        lastPreludeCodec = FolderSpanArchiveCodec.decodeStreamHeader(
                            chunk.copyOfRange(
                                FolderSpanArchiveCodec.MAGIC.size + 4,
                                FolderSpanArchiveCodec.MAGIC.size + 4 + headerSize,
                            )
                        ).codec
                    }
                }
                emit(chunk)
            }
        }
        ArchiveStreamDecoder.decode(monitored, MapArchiveSink(receivedFiles, failCompletingPath))
    }

    override suspend fun renames(renameInfos: List<RenameInfo>) = unsupported<List<Result<Boolean>>>()
    override suspend fun createFolders(paths: List<String>) = unsupported<List<Result<Boolean>>>()
    override suspend fun createFiles(paths: List<String>) = unsupported<List<Result<Boolean>>>()
    override suspend fun deletes(paths: List<String>) = unsupported<List<Result<Boolean>>>()
    override suspend fun writeBytes(
        fileSize: Long,
        blockIndex: Long,
        blockLength: Long,
        path: String,
        byteArray: ByteArray,
        startOffset: Long,
    ) = unsupported<Boolean>()
    override suspend fun readBytes(path: String, startOffset: Long, endOffset: Long) = unsupported<ByteArray>()
    override suspend fun getFileByPath(path: String) = unsupported<FileSimpleInfo>()
    override suspend fun getFileByPathAndName(path: String, name: String) = unsupported<FileSimpleInfo>()
    override suspend fun getFileInfoByPath(path: String) = unsupported<FileInfo>()
    override suspend fun getFileInfoByPathAndName(path: String, name: String) = unsupported<FileInfo>()
    override suspend fun readFileLines(path: String) = unsupported<List<String>>()
    override suspend fun appendToFile(path: String, content: String) = unsupported<Boolean>()
    override suspend fun copyPath(
        srcPath: String,
        destPath: String,
        onProgress: (suspend (CopyPathProgress) -> Unit)?,
        requestId: String?,
    ) = unsupported<Boolean>()
    override suspend fun controlCopy(requestId: String, action: CopyPathControlAction) = unsupported<Boolean>()

    private fun <T> unsupported(): Result<T> = Result.failure(UnsupportedOperationException())
}

private class MapArchiveSink(
    private val files: MutableMap<String, ByteArray>,
    private val failCompletingPath: String? = null,
) : FolderSpanArchiveEntrySink {
    private var path = ""
    private val chunks = mutableListOf<ByteArray>()

    override suspend fun begin(header: FolderSpanArchiveEntryHeader) {
        path = header.relativePath
        chunks.clear()
    }

    override suspend fun write(header: FolderSpanArchiveEntryHeader, chunk: ByteArray) {
        chunks += chunk
    }

    override suspend fun complete(header: FolderSpanArchiveEntryHeader) {
        check(header.relativePath != failCompletingPath) { "target rejected ${header.relativePath}" }
        if (header.kind == FolderSpanArchiveEntryKind.File) {
            val bytes = ByteArray(chunks.sumOf { it.size })
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(bytes, destinationOffset = offset)
                offset += chunk.size
            }
            files[path] = bytes
        }
        path = ""
        chunks.clear()
    }
}

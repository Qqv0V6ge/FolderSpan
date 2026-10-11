package com.folderspan.service.http.archive

import com.folderspan.utils.ProtoBufCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArchiveStreamCodecTest {
    @Test
    fun capabilityNormalizationIgnoresFutureValuesWithoutEnablingThem() {
        val normalized = FolderSpanArchiveStreamCapabilities(
            codecs = listOf(77, FolderSpanArchiveCompressionCodec.NONE),
            maxEntries = 2,
            maxFileBytes = 16L,
            maxBatchBytes = 32L,
        ).normalized()

        assertEquals(listOf(FolderSpanArchiveCompressionCodec.NONE), normalized.codecs)
        assertFailsWith<IllegalArgumentException> {
            FolderSpanArchiveStreamOptions(codec = 77).validated()
        }
    }

    @Test
    fun fsar2NoneMatchesGoldenFixtureAndRejectsFsar1() = runTest {
        val entry = fileRequest("a", byteArrayOf(0x2A))

        val fsar2 = encode(listOf(entry), FolderSpanArchiveStreamOptions())
        assertContentEquals(
            "465341520200000006100118012010000000070a0161100118012a00000000".hexToBytes(),
            fsar2,
        )
        assertDecodedEntries(fsar2, listOf(entry))
        assertArchiveFailure("4653415201000000070a0161100118012a00000000".hexToBytes())
    }

    @Test
    fun fsar2ZstdMatchesGoldenFixtureAndRoundTrips() = runTest {
        val entry = fileRequest("a", byteArrayOf(0x2A))
        val encoded = encode(
            listOf(entry),
            FolderSpanArchiveStreamOptions(
                codec = FolderSpanArchiveCompressionCodec.ZSTD,
            ),
        )

        assertContentEquals(
            ZSTD_GOLDEN_HEX.hexToBytes(),
            encoded,
        )
        assertDecodedEntries(encoded, listOf(entry))
    }

    @Test
    fun roundTripHandlesEveryBoundaryZeroByteFilesAndMaximumFile() = runTest {
        val maximum = ByteArray(FOLDER_SPAN_ARCHIVE_SMALL_FILE_THRESHOLD_BYTES.toInt()) { index ->
            (index % 251).toByte()
        }
        val entries = listOf(
            directoryRequest("empty"),
            fileRequest("empty/zero.bin", ByteArray(0)),
            fileRequest("maximum.bin", maximum),
        )
        val encoded = encode(
            entries,
            FolderSpanArchiveStreamOptions(
                codec = FolderSpanArchiveCompressionCodec.ZSTD,
            ),
            sourceChunkBytes = 7_919,
        )

        val sink = RecordingArchiveSink()
        val result = ArchiveStreamDecoder.decode(
            chunks = encoded.arbitraryChunks(1, 2, 3, 5, 8, 13, 21, 34),
            sink = sink,
        )

        assertEquals(entries.map { it.request.relativePath }, result.completedRelativePaths)
        assertEquals(maximum.size.toLong(), result.committedFileBytes)
        assertEquals(maximum.size, sink.maxEntryBytes)
        assertTrue(sink.maxWriteChunkBytes <= FOLDER_SPAN_ARCHIVE_BUFFER_BYTES)
        assertContentEquals(maximum, sink.files.getValue("maximum.bin"))
        assertContentEquals(ByteArray(0), sink.files.getValue("empty/zero.bin"))
    }

    @Test
    fun plannerRoundTripsMoreThanOneArchiveBatch() = runTest {
        val entries = List(FOLDER_SPAN_ARCHIVE_TARGET_BATCH_ENTRIES * 2 + 88) { index ->
            fileRequest("nested/file-$index.txt", byteArrayOf((index % 251).toByte()))
        }
        val selection = selectSmallFileArchiveBatches(
            items = entries,
            sourcePath = { entry -> entry.request.sourcePath },
            relativePath = { entry -> entry.request.relativePath },
            size = { entry -> entry.bytes.size.toLong() },
        )

        assertEquals(listOf(256, 256, 88), selection.batches.map { batch -> batch.items.size })
        assertTrue(selection.remainingItems.isEmpty())
        val completed = mutableListOf<String>()
        selection.batches.forEach { batch ->
            val encoded = encode(
                entries = batch.items,
                options = FolderSpanArchiveStreamOptions(),
            )
            completed += ArchiveStreamDecoder.decode(
                chunks = encoded.arbitraryChunks(1, 64, 3, 511),
                sink = RecordingArchiveSink(),
            ).completedRelativePaths
        }

        assertEquals(entries.map { entry -> entry.request.relativePath }, completed)
    }

    @Test
    fun decoderRejectsUnsafeDuplicateForgedTruncatedAndTrailingInputs() = runTest {
        val unsafePaths = listOf("/absolute", "slash\\path", ".", "..", "nested/../escape")
        unsafePaths.forEach { path ->
            val bytes = rawFsar2(
                FolderSpanArchiveEntryHeader(path, FolderSpanArchiveEntryKind.File, 0L),
                ByteArray(0),
            )
            assertArchiveFailure(bytes)
        }

        val duplicateHeader = FolderSpanArchiveEntryHeader("duplicate", FolderSpanArchiveEntryKind.File, 0L)
        val duplicateBytes = rawFsar2(
            duplicateHeader,
            ByteArray(0),
            duplicateHeader,
            ByteArray(0),
        )
        assertArchiveFailure(duplicateBytes)

        val valid = encode(
            listOf(fileRequest("valid", byteArrayOf(1, 2, 3))),
            FolderSpanArchiveStreamOptions(),
        )
        assertArchiveFailure(valid.copyOf(valid.size - 1))
        assertArchiveFailure(valid + byteArrayOf(99))

        val forged = rawFsar2(
            FolderSpanArchiveEntryHeader(
                relativePath = "forged",
                kind = FolderSpanArchiveEntryKind.File,
                size = FOLDER_SPAN_ARCHIVE_SMALL_FILE_THRESHOLD_BYTES + 1,
            ),
            ByteArray(0),
        )
        assertArchiveFailure(forged)
    }

    @Test
    fun decoderRejectsUnknownVersionAndCodec() = runTest {
        val unknownVersion = FolderSpanArchiveCodec.MAGIC_PREFIX + byteArrayOf(99) + ByteArray(4)
        val versionFailure = runCatching {
            ArchiveStreamDecoder.decode(flowOf(unknownVersion), RecordingArchiveSink())
        }.exceptionOrNull()
        assertIs<IllegalArgumentException>(versionFailure)

        val header = ProtoBufCodec.encode(
            FolderSpanArchiveStreamHeader(
                codec = 99,
                entryCount = 1,
                declaredFileBytes = 0,
                declaredFrameBytes = 4,
            )
        )
        val unknownCodec = FolderSpanArchiveCodec.MAGIC +
            FolderSpanArchiveCodec.headerLengthBytes(header.size) + header + ByteArray(4)
        val codecFailure = runCatching {
            ArchiveStreamDecoder.decode(flowOf(unknownCodec), RecordingArchiveSink())
        }.exceptionOrNull()
        assertIs<IllegalArgumentException>(codecFailure)
    }

    @Test
    fun zstdDecoderRejectsForgedExpandedEntryBeforeWritingPayload() = runTest {
        val forgedHeader = FolderSpanArchiveCodec.encodeHeader(
            FolderSpanArchiveEntryHeader(
                relativePath = "expanded.bin",
                kind = FolderSpanArchiveEntryKind.File,
                size = FOLDER_SPAN_ARCHIVE_SMALL_FILE_THRESHOLD_BYTES + 1L,
            )
        )
        val frames = FolderSpanArchiveCodec.headerLengthBytes(forgedHeader.size) + forgedHeader
        val compressedChunks = mutableListOf<ByteArray>()
        FolderSpanArchivePlatformCompression.compress(
            FolderSpanArchiveCompressionCodec.ZSTD,
            flowOf(frames),
        ).collect { chunk -> compressedChunks += chunk }
        val prelude = FolderSpanArchiveCodec.encodeStreamPrelude(
            FolderSpanArchiveStreamHeader(
                codec = FolderSpanArchiveCompressionCodec.ZSTD,
                entryCount = 1,
                declaredFileBytes = 0L,
                declaredFrameBytes = 4L,
            )
        )

        val failure = runCatching {
            ArchiveStreamDecoder.decode(
                chunks = flowOf(prelude, *compressedChunks.toTypedArray()),
                sink = RecordingArchiveSink(),
            )
        }.exceptionOrNull()

        assertIs<FolderSpanArchiveStreamException>(failure)
        assertTrue(failure.partialResult.completedRelativePaths.isEmpty())
    }

    @Test
    fun partialResultOnlyContainsFullyCommittedEntries() = runTest {
        val first = fileRequest("first", byteArrayOf(1, 2, 3))
        val second = fileRequest("second", byteArrayOf(4, 5, 6))
        val encoded = encode(
            listOf(first, second),
            FolderSpanArchiveStreamOptions(),
        )
        val truncated = encoded.copyOf(encoded.size - 5)

        val failure = runCatching {
            ArchiveStreamDecoder.decode(truncated.arbitraryChunks(2, 1, 4), RecordingArchiveSink())
        }.exceptionOrNull()

        val archiveFailure = assertIs<FolderSpanArchiveStreamException>(failure)
        assertEquals(listOf("first"), archiveFailure.partialResult.completedRelativePaths)
        assertEquals(3L, archiveFailure.partialResult.committedFileBytes)
    }

    private suspend fun assertDecodedEntries(
        encoded: ByteArray,
        entries: List<TestEntry>,
    ) {
        val sink = RecordingArchiveSink()
        val progress = mutableMapOf<String, Long>()
        val result = ArchiveStreamDecoder.decode(
            chunks = encoded.arbitraryChunks(1, 4, 2, 9),
            sink = sink,
            onFileBytes = { path, bytes -> progress[path] = progress.getOrElse(path) { 0L } + bytes },
        )
        assertEquals(entries.map { it.request.relativePath }, result.completedRelativePaths)
        entries.filterNot { it.request.directory }.forEach { entry ->
            assertContentEquals(entry.bytes, sink.files.getValue(entry.request.relativePath))
            assertEquals(entry.bytes.size.toLong(), progress[entry.request.relativePath] ?: 0L)
        }
    }

    private suspend fun assertArchiveFailure(bytes: ByteArray) {
        val failure = runCatching {
            ArchiveStreamDecoder.decode(bytes.arbitraryChunks(3, 1, 7), RecordingArchiveSink())
        }.exceptionOrNull()
        assertTrue(failure is FolderSpanArchiveStreamException || failure is IllegalArgumentException)
    }

    private suspend fun encode(
        entries: List<TestEntry>,
        options: FolderSpanArchiveStreamOptions,
        sourceChunkBytes: Int = 2,
    ): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        ArchiveStreamEncoder.encode(
            entries = entries.map(TestEntry::request),
            options = options,
            readFileChunks = { request ->
                entries.first { it.request.sourcePath == request.sourcePath }.bytes.fixedChunks(sourceChunkBytes)
            },
        ).collect { chunk -> chunks += chunk }
        return chunks.concatenate()
    }

    private fun fileRequest(path: String, bytes: ByteArray): TestEntry = TestEntry(
        request = FolderSpanArchiveEntryRequest(
            sourcePath = "/source/$path",
            relativePath = path,
            size = bytes.size.toLong(),
        ),
        bytes = bytes,
    )

    private fun directoryRequest(path: String): TestEntry = TestEntry(
        request = FolderSpanArchiveEntryRequest(
            sourcePath = "/source/$path",
            relativePath = path,
            directory = true,
        ),
        bytes = ByteArray(0),
    )

    private fun rawFsar2(vararg values: Any): ByteArray {
        val frameChunks = mutableListOf<ByteArray>()
        var frameBytes = 4L
        var index = 0
        while (index < values.size) {
            val header = values[index] as FolderSpanArchiveEntryHeader
            val data = values[index + 1] as ByteArray
            val encodedHeader = ProtoBufCodec.encode(header)
            frameChunks += FolderSpanArchiveCodec.headerLengthBytes(encodedHeader.size)
            frameChunks += encodedHeader
            frameChunks += data
            frameBytes += 4L + encodedHeader.size + data.size
            index += 2
        }
        frameChunks += FolderSpanArchiveCodec.headerLengthBytes(0)
        val streamHeader = ProtoBufCodec.encode(
            FolderSpanArchiveStreamHeader(
                codec = FolderSpanArchiveCompressionCodec.NONE,
                entryCount = values.size / 2,
                declaredFileBytes = values.filterIsInstance<ByteArray>().sumOf(ByteArray::size).toLong(),
                declaredFrameBytes = frameBytes,
            )
        )
        return (
            listOf(
                FolderSpanArchiveCodec.MAGIC,
                FolderSpanArchiveCodec.headerLengthBytes(streamHeader.size),
                streamHeader,
            ) + frameChunks
        ).concatenate()
    }

    private data class TestEntry(
        val request: FolderSpanArchiveEntryRequest,
        val bytes: ByteArray,
    )

    private class RecordingArchiveSink : FolderSpanArchiveEntrySink {
        val files = linkedMapOf<String, ByteArray>()
        private var currentPath: String? = null
        private val currentChunks = mutableListOf<ByteArray>()
        var maxWriteChunkBytes = 0
            private set
        var maxEntryBytes = 0
            private set

        override suspend fun begin(header: FolderSpanArchiveEntryHeader) {
            check(currentPath == null)
            currentPath = header.relativePath
            currentChunks.clear()
        }

        override suspend fun write(header: FolderSpanArchiveEntryHeader, chunk: ByteArray) {
            check(currentPath == header.relativePath)
            maxWriteChunkBytes = maxOf(maxWriteChunkBytes, chunk.size)
            currentChunks += chunk
        }

        override suspend fun complete(header: FolderSpanArchiveEntryHeader) {
            check(currentPath == header.relativePath)
            if (header.kind == FolderSpanArchiveEntryKind.File) {
                val bytes = currentChunks.concatenate()
                files[header.relativePath] = bytes
                maxEntryBytes = maxOf(maxEntryBytes, bytes.size)
            }
            currentPath = null
            currentChunks.clear()
        }

        override suspend fun abort(header: FolderSpanArchiveEntryHeader, cause: Throwable) {
            currentPath = null
            currentChunks.clear()
        }
    }

    private companion object {
        // zstd-kmp 0.4.0, level 1. The FSAR2 prelude remains uncompressed.
        const val ZSTD_GOLDEN_HEX =
            "465341520200000008080110011801201028b52ffd0048810000000000070a0161100118012a00000000"
    }
}

private fun ByteArray.fixedChunks(size: Int): Flow<ByteArray> = flow {
    var offset = 0
    while (offset < this@fixedChunks.size) {
        val end = minOf(this@fixedChunks.size, offset + size)
        emit(copyOfRange(offset, end))
        offset = end
    }
}

private fun ByteArray.arbitraryChunks(vararg sizes: Int): Flow<ByteArray> = flow {
    var offset = 0
    var index = 0
    while (offset < this@arbitraryChunks.size) {
        val size = sizes[index % sizes.size]
        val end = minOf(this@arbitraryChunks.size, offset + size)
        emit(copyOfRange(offset, end))
        offset = end
        index++
    }
}

private fun List<ByteArray>.concatenate(): ByteArray {
    val output = ByteArray(sumOf(ByteArray::size))
    var offset = 0
    forEach { chunk ->
        chunk.copyInto(output, destinationOffset = offset)
        offset += chunk.size
    }
    return output
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

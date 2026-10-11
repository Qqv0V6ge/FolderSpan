package com.folderspan.editor

import strings.AppStrings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

internal class VirtualFileEditorContentSource(
    override val size: Long,
    override val canWrite: Boolean = false,
    private val byteAt: (Long) -> Byte = { 0x41 },
) : FileEditorContentSource {
    override val capabilities = FileEditorSourceCapabilities(
        supportsStreamedReplace = canWrite,
        supportsSaveAs = true,
    )
    val reads = mutableListOf<LongRange>()
    var closed = false

    override suspend fun readRange(
        startOffset: Long,
        endOffsetExclusive: Long,
    ): Result<ByteArray> = runCatching {
        require(startOffset >= 0L && endOffsetExclusive in startOffset..size)
        val length = endOffsetExclusive - startOffset
        require(length <= Int.MAX_VALUE)
        reads += startOffset until endOffsetExclusive
        ByteArray(length.toInt()) { index -> byteAt(startOffset + index) }
    }

    override suspend fun replaceContent(
        newSize: Long,
        content: Flow<ByteArray>,
        expectedSnapshot: FileEditorSourceSnapshot?,
        onProgress: suspend (Long, Long) -> Unit,
    ): Result<Unit> = Result.failure(FileEditorUnsupportedOperationException(AppStrings.ui_test_file_editor_content_source_fixtures_flow_replacement))

    override suspend fun close() {
        closed = true
    }
}

internal class RecordingFileEditorContentSource(
    initial: ByteArray,
    override val canWrite: Boolean = true,
    supportsRangeWrite: Boolean = canWrite,
    supportsAtomicReplace: Boolean = canWrite,
) : FileEditorContentSource {
    private var data = initial.copyOf()
    private var revision = 1L

    override val size: Long
        get() = data.size.toLong()

    override val capabilities = FileEditorSourceCapabilities(
        supportsRangeWrite = supportsRangeWrite,
        supportsStreamedReplace = canWrite,
        supportsSaveAs = true,
        supportsAtomicReplace = supportsAtomicReplace,
    )

    val reads = mutableListOf<LongRange>()
    val writes = mutableListOf<Pair<Long, ByteArray>>()
    val replacementChunkSizes = mutableListOf<Int>()
    var readFailure: Throwable? = null
    var writeFailure: Throwable? = null
    var failWriteAfterMutationAtCall: Int? = null
    var writeCallCount: Int = 0
    var replaceFailure: Throwable? = null
    var failReplacementAfterChunks: Int? = null
    var cancelReplacementAfterChunks: Int? = null
    var failReplacementVerification: Boolean = false
    var closed = false

    override suspend fun currentSnapshot(): Result<FileEditorSourceSnapshot> = Result.success(
        FileEditorSourceSnapshot(
            size = size,
            updatedAt = revision,
            revision = revision.toString(),
        )
    )

    override suspend fun readRange(
        startOffset: Long,
        endOffsetExclusive: Long,
    ): Result<ByteArray> {
        readFailure?.let { return Result.failure(it) }
        return runCatching {
            require(startOffset >= 0L && endOffsetExclusive in startOffset..size)
            reads += startOffset until endOffsetExclusive
            data.copyOfRange(startOffset.toInt(), endOffsetExclusive.toInt())
        }
    }

    override suspend fun writeRange(
        startOffset: Long,
        data: ByteArray,
        expectedSnapshot: FileEditorSourceSnapshot?,
        onProgress: suspend (Long, Long) -> Unit,
    ): Result<Unit> {
        if (!canWrite || !capabilities.supportsRangeWrite) {
            return Result.failure(FileEditorUnsupportedOperationException(AppStrings.ui_long_range_writing))
        }
        validateSnapshot(expectedSnapshot).exceptionOrNull()?.let { return Result.failure(it) }
        writeFailure?.let { return Result.failure(it) }
        return runCatching {
            writeCallCount += 1
            require(startOffset >= 0L && startOffset + data.size <= size)
            data.copyInto(this.data, startOffset.toInt())
            writes += startOffset to data.copyOf()
            revision += 1L
            if (failWriteAfterMutationAtCall == writeCallCount) {
                failWriteAfterMutationAtCall = null
                throw IllegalStateException(AppStrings.ui_test_file_editor_content_source_fixtures_test_injection_range_written_failed)
            }
            onProgress(data.size.toLong(), data.size.toLong())
        }
    }

    override suspend fun replaceContent(
        newSize: Long,
        content: Flow<ByteArray>,
        expectedSnapshot: FileEditorSourceSnapshot?,
        onProgress: suspend (Long, Long) -> Unit,
    ): Result<Unit> {
        if (!canWrite || !capabilities.supportsStreamedReplace) {
            return Result.failure(FileEditorUnsupportedOperationException(AppStrings.ui_test_file_editor_content_source_fixtures_flow_replacement))
        }
        validateSnapshot(expectedSnapshot).exceptionOrNull()?.let { return Result.failure(it) }
        replaceFailure?.let { return Result.failure(it) }
        return runCatching {
            val output = ArrayList<Byte>()
            var chunkCount = 0
            var completed = 0L
            content.collect { chunk ->
                chunkCount += 1
                if (cancelReplacementAfterChunks == chunkCount) {
                    throw CancellationException(AppStrings.ui_test_file_editor_content_source_fixtures_test_cancel_flow_replacement)
                }
                if (failReplacementAfterChunks == chunkCount) {
                    throw IllegalStateException(AppStrings.ui_test_file_editor_content_source_fixtures_test_injection_of_stream)
                }
                replacementChunkSizes += chunk.size
                chunk.forEach(output::add)
                completed += chunk.size
                onProgress(completed, newSize)
            }
            check(output.size.toLong() == newSize)
            if (failReplacementVerification) {
                throw IllegalStateException(AppStrings.ui_test_file_editor_content_source_fixtures_test_injection_flow_replacement)
            }
            data = ByteArray(output.size) { index -> output[index] }
            revision += 1L
            if (newSize == 0L) onProgress(0L, 0L)
        }
    }

    override suspend fun close() {
        closed = true
    }

    fun bytes(): ByteArray = data.copyOf()

    fun simulateExternalChange(bytes: ByteArray) {
        data = bytes.copyOf()
        revision += 1L
    }

    private suspend fun validateSnapshot(expected: FileEditorSourceSnapshot?): Result<Unit> {
        if (expected == null) return Result.success(Unit)
        return currentSnapshot().mapCatching { actual ->
            if (actual != expected) throw FileEditorSourceChangedException(expected, actual)
        }
    }
}

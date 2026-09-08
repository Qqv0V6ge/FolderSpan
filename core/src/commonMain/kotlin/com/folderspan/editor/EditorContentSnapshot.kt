package com.folderspan.editor

import kotlinx.coroutines.flow.Flow
import strings.AppStrings

internal sealed interface EditorContentSnapshotSegment {
    val length: Long

    suspend fun read(relativeStart: Long, relativeEndExclusive: Long): ByteArray

    data class Source(
        val source: FileEditorContentSource,
        val sourceOffset: Long,
        override val length: Long,
    ) : EditorContentSnapshotSegment {
        override suspend fun read(relativeStart: Long, relativeEndExclusive: Long): ByteArray =
            source.readRange(
                sourceOffset + relativeStart,
                sourceOffset + relativeEndExclusive,
            ).getOrThrow()
    }

    data class Bytes(
        val value: ByteArray,
    ) : EditorContentSnapshotSegment {
        override val length: Long = value.size.toLong()

        override suspend fun read(relativeStart: Long, relativeEndExclusive: Long): ByteArray =
            value.copyOfRange(relativeStart.toInt(), relativeEndExclusive.toInt())
    }
}

/** Immutable logical view of the document at the moment a background operation starts. */
internal class EditorContentSnapshotSource(
    segments: List<EditorContentSnapshotSegment>,
    revision: String,
) : FileEditorContentSource {
    private val segments = segments.filter { it.length > 0L }.map { segment ->
        if (segment is EditorContentSnapshotSegment.Bytes) {
            EditorContentSnapshotSegment.Bytes(segment.value.copyOf())
        } else {
            segment
        }
    }
    private val segmentStarts = LongArray(this.segments.size).also { starts ->
        var offset = 0L
        this.segments.forEachIndexed { index, segment ->
            starts[index] = offset
            offset += segment.length
        }
    }
    private val snapshot = FileEditorSourceSnapshot(
        size = this.segments.lastIndex.takeIf { it >= 0 }?.let { lastIndex ->
            segmentStarts[lastIndex] + this.segments[lastIndex].length
        } ?: 0L,
        revision = revision,
    )

    override val size: Long
        get() = snapshot.size
    override val canWrite: Boolean = false
    override val capabilities: FileEditorSourceCapabilities = FileEditorSourceCapabilities(
        supportsStreamedReplace = false,
        supportsSaveAs = false,
    )

    override suspend fun currentSnapshot(): Result<FileEditorSourceSnapshot> = Result.success(snapshot)

    override suspend fun readRange(
        startOffset: Long,
        endOffsetExclusive: Long,
    ): Result<ByteArray> = runCatching {
        require(startOffset >= 0L && endOffsetExclusive in startOffset..size) {
            AppStrings.ui_invalid_range_for_editing_snapshot
        }
        val length = endOffsetExclusive - startOffset
        require(length <= Int.MAX_VALUE) { AppStrings.ui_read_range_too_large_for_editing_snapshot }
        if (length == 0L) return@runCatching byteArrayOf()

        val output = ByteArray(length.toInt())
        var outputOffset = 0
        var segmentIndex = firstOverlappingSegment(startOffset)
        while (segmentIndex < segments.size) {
            val segment = segments[segmentIndex]
            val logicalStart = segmentStarts[segmentIndex]
            if (logicalStart >= endOffsetExclusive) break
            val logicalEnd = logicalStart + segment.length
            val overlapStart = maxOf(startOffset, logicalStart)
            val overlapEnd = minOf(endOffsetExclusive, logicalEnd)
            if (overlapEnd > overlapStart) {
                val bytes = segment.read(
                    overlapStart - logicalStart,
                    overlapEnd - logicalStart,
                )
                check(bytes.size.toLong() == overlapEnd - overlapStart) {
                    AppStrings.editor_snapshot_segment_read_length_mismatch
                }
                bytes.copyInto(output, outputOffset)
                outputOffset += bytes.size
            }
            segmentIndex += 1
        }
        check(outputOffset == output.size) { AppStrings.ui_read_length_does_not_match }
        output
    }

    override suspend fun replaceContent(
        newSize: Long,
        content: Flow<ByteArray>,
        expectedSnapshot: FileEditorSourceSnapshot?,
        onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit,
    ): Result<Unit> = Result.failure(FileEditorUnsupportedOperationException(AppStrings.ui_insert_edit_snapshot))

    private fun firstOverlappingSegment(offset: Long): Int {
        var low = 0
        var high = segments.size
        while (low < high) {
            val middle = (low + high) ushr 1
            val end = segmentStarts[middle] + segments[middle].length
            if (end <= offset) low = middle + 1 else high = middle
        }
        return low
    }
}

package com.folderspan.editor

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import strings.AppStrings

const val EDITOR_LINE_INDEX_CHECKPOINT_LINES: Long = 4096L

data class EditorLineCheckpoint(
    val lineNumber: Long,
    val byteOffset: Long,
)

data class EditorSparseLineIndex(
    val sourceSnapshot: FileEditorSourceSnapshot,
    val encoding: EditorTextEncoding,
    val checkpoints: List<EditorLineCheckpoint>,
    val indexedThroughOffset: Long,
    val totalLineCount: Long?,
    val complete: Boolean,
) {
    fun cacheKey(): String = editorLineIndexCacheKey(sourceSnapshot, encoding)

    fun encode(): ByteArray = buildString {
        appendLine("FSLI1")
        appendLine(sourceSnapshot.size)
        appendLine(sourceSnapshot.updatedAt ?: "-")
        appendLine(sourceSnapshot.revision.orEmpty().encodeToByteArray().toHex())
        appendLine(encoding.name)
        appendLine(indexedThroughOffset)
        appendLine(totalLineCount ?: "-")
        appendLine(if (complete) 1 else 0)
        checkpoints.forEach { appendLine("${it.lineNumber}:${it.byteOffset}") }
    }.encodeToByteArray()

    companion object {
        fun decode(bytes: ByteArray): EditorSparseLineIndex? = runCatching {
            val lines = bytes.decodeToString().lineSequence().toList()
            require(lines.size >= 8 && lines[0] == "FSLI1")
            val snapshot = FileEditorSourceSnapshot(
                size = lines[1].toLong(),
                updatedAt = lines[2].takeUnless { it == "-" }?.toLong(),
                revision = lines[3].hexToBytes().decodeToString().ifEmpty { null },
            )
            EditorSparseLineIndex(
                sourceSnapshot = snapshot,
                encoding = EditorTextEncoding.valueOf(lines[4]),
                indexedThroughOffset = lines[5].toLong(),
                totalLineCount = lines[6].takeUnless { it == "-" }?.toLong(),
                complete = lines[7] == "1",
                checkpoints = lines.drop(8).filter(String::isNotBlank).map { value ->
                    val separator = value.indexOf(':')
                    require(separator > 0)
                    EditorLineCheckpoint(
                        lineNumber = value.substring(0, separator).toLong(),
                        byteOffset = value.substring(separator + 1).toLong(),
                    )
                },
            )
        }.getOrNull()
    }
}

fun editorLineIndexCacheKey(
    sourceSnapshot: FileEditorSourceSnapshot,
    encoding: EditorTextEncoding,
): String = buildString {
    append(sourceSnapshot.size)
    append('_')
    append(sourceSnapshot.updatedAt ?: "none")
    append('_')
    append(sourceSnapshot.revision.orEmpty().encodeToByteArray().toHex())
    append('_')
    append(encoding.name)
}

class EditorSparseLineIndexer(
    private val source: FileEditorContentSource,
    private val encoding: EditorTextEncoding,
    private val hasBom: Boolean = false,
    private val chunkSize: Int = DEFAULT_FILE_EDITOR_SCAN_CHUNK_SIZE,
) {
    init {
        require(chunkSize > 0)
    }

    suspend fun build(
        onProgress: suspend (indexedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<EditorSparseLineIndex> = runCatching {
        val initialSnapshot = source.currentSnapshot().getOrThrow()
        val scanner = LineBoundaryScanner(encoding)
        val checkpoints = mutableListOf(EditorLineCheckpoint(1L, bomLength()))
        var lineNumber = 1L
        var offset = bomLength()
        while (offset < initialSnapshot.size) {
            currentCoroutineContext().ensureActive()
            val end = minOf(offset + chunkSize, initialSnapshot.size)
            val bytes = source.readRange(offset, end).getOrThrow()
            check(bytes.size.toLong() == end - offset)
            scanner.accept(bytes, offset).forEach { lineOffset ->
                lineNumber += 1L
                if ((lineNumber - 1L) % EDITOR_LINE_INDEX_CHECKPOINT_LINES == 0L) {
                    checkpoints += EditorLineCheckpoint(lineNumber, lineOffset)
                }
            }
            offset = end
            onProgress(offset, initialSnapshot.size)
        }
        scanner.finish(initialSnapshot.size).forEach { lineOffset ->
            lineNumber += 1L
            if ((lineNumber - 1L) % EDITOR_LINE_INDEX_CHECKPOINT_LINES == 0L) {
                checkpoints += EditorLineCheckpoint(lineNumber, lineOffset)
            }
        }
        val finalSnapshot = source.currentSnapshot().getOrThrow()
        if (finalSnapshot != initialSnapshot) {
            throw FileEditorSourceChangedException(initialSnapshot, finalSnapshot)
        }
        EditorSparseLineIndex(
            sourceSnapshot = initialSnapshot,
            encoding = encoding,
            checkpoints = checkpoints,
            indexedThroughOffset = initialSnapshot.size,
            totalLineCount = lineNumber,
            complete = true,
        )
    }

    suspend fun lineToByte(index: EditorSparseLineIndex, targetLine: Long): Result<Long> = runCatching {
        require(targetLine >= 1L)
        index.totalLineCount?.let { require(targetLine <= it) }
        validate(index)
        val checkpoint = index.checkpoints.lastOrNull { it.lineNumber <= targetLine }
            ?: EditorLineCheckpoint(1L, bomLength())
        if (checkpoint.lineNumber == targetLine) return@runCatching checkpoint.byteOffset
        scanFrom(checkpoint.byteOffset, checkpoint.lineNumber) { line, offset ->
            if (line == targetLine) offset else null
        } ?: throw IllegalArgumentException(AppStrings.ui_line_number_exceeds_file_range)
    }

    suspend fun byteToLine(index: EditorSparseLineIndex, targetOffset: Long): Result<Long> = runCatching {
        require(targetOffset in 0L..index.sourceSnapshot.size)
        validate(index)
        val checkpoint = index.checkpoints.lastOrNull { it.byteOffset <= targetOffset }
            ?: EditorLineCheckpoint(1L, bomLength())
        var result = checkpoint.lineNumber
        scanFrom(checkpoint.byteOffset, checkpoint.lineNumber) { line, offset ->
            if (offset > targetOffset) result else {
                result = line
                null
            }
        }
        result
    }

    private suspend fun <T> scanFrom(
        startOffset: Long,
        startLine: Long,
        match: (line: Long, offset: Long) -> T?,
    ): T? {
        val scanner = LineBoundaryScanner(encoding)
        var line = startLine
        var offset = startOffset
        while (offset < source.size) {
            currentCoroutineContext().ensureActive()
            val end = minOf(offset + chunkSize, source.size)
            val bytes = source.readRange(offset, end).getOrThrow()
            for (lineOffset in scanner.accept(bytes, offset)) {
                line += 1L
                match(line, lineOffset)?.let { return it }
            }
            offset = end
        }
        for (lineOffset in scanner.finish(source.size)) {
            line += 1L
            match(line, lineOffset)?.let { return it }
        }
        return null
    }

    private suspend fun validate(index: EditorSparseLineIndex) {
        val current = source.currentSnapshot().getOrThrow()
        if (current != index.sourceSnapshot) {
            throw FileEditorSourceChangedException(index.sourceSnapshot, current)
        }
    }

    private fun bomLength(): Long = if (!hasBom) {
        0L
    } else {
        when (encoding) {
            EditorTextEncoding.UTF8 -> 3L
            EditorTextEncoding.UTF16_LE, EditorTextEncoding.UTF16_BE -> 2L
            else -> 0L
        }.coerceAtMost(source.size)
    }
}

private class LineBoundaryScanner(private val encoding: EditorTextEncoding) {
    private var pendingCr = false
    private var pendingCrEnd = 0L
    private var pendingByte: Byte? = null
    private var pendingByteOffset = 0L

    fun accept(bytes: ByteArray, absoluteStart: Long): List<Long> {
        val boundaries = mutableListOf<Long>()
        if (encoding == EditorTextEncoding.UTF16_LE || encoding == EditorTextEncoding.UTF16_BE) {
            var index = 0
            pendingByte?.let { first ->
                if (bytes.isNotEmpty()) {
                    processCodeUnit(first, bytes[0], pendingByteOffset + 2L, boundaries)
                    pendingByte = null
                    index = 1
                }
            }
            while (index + 1 < bytes.size) {
                processCodeUnit(bytes[index], bytes[index + 1], absoluteStart + index + 2L, boundaries)
                index += 2
            }
            if (index < bytes.size) {
                pendingByte = bytes[index]
                pendingByteOffset = absoluteStart + index
            }
        } else {
            bytes.forEachIndexed { index, byte ->
                processValue(byte.toInt() and 0xFF, absoluteStart + index + 1L, boundaries)
            }
        }
        return boundaries
    }

    fun finish(endOffset: Long): List<Long> = buildList {
        if (pendingByte != null) pendingByte = null
        if (pendingCr) {
            add(pendingCrEnd.coerceAtMost(endOffset))
            pendingCr = false
        }
    }

    private fun processCodeUnit(first: Byte, second: Byte, endOffset: Long, output: MutableList<Long>) {
        val firstValue = first.toInt() and 0xFF
        val secondValue = second.toInt() and 0xFF
        val value = if (encoding == EditorTextEncoding.UTF16_LE) {
            firstValue or (secondValue shl 8)
        } else {
            (firstValue shl 8) or secondValue
        }
        processValue(value, endOffset, output)
    }

    private fun processValue(value: Int, endOffset: Long, output: MutableList<Long>) {
        if (pendingCr) {
            if (value == 0x0A) {
                output += endOffset
                pendingCr = false
                return
            }
            output += pendingCrEnd
            pendingCr = false
        }
        when (value) {
            0x0D -> {
                pendingCr = true
                pendingCrEnd = endOffset
            }
            0x0A -> output += endOffset
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

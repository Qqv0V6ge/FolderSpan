package com.folderspan.editor

import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import strings.AppStrings

const val EDITOR_SEARCH_REGEX_MAX_MATCH_BYTES: Int = 1024 * 1024
const val EDITOR_SEARCH_RESULT_DETAIL_LIMIT: Int = 10_000
const val DEFAULT_FILE_EDITOR_SCAN_CHUNK_SIZE: Int = 1024 * 1024
const val EDITOR_SEARCH_MAX_QUERY_COUNT: Int = 256
const val EDITOR_SEARCH_MAX_TEXT_PATTERN_BYTES: Int = 256 * 1024
const val EDITOR_SEARCH_MAX_TOTAL_PATTERN_BYTES: Int = 1024 * 1024
const val EDITOR_SEARCH_MAX_REGEX_PATTERN_CHARS: Int = 16 * 1024
private const val DEFAULT_SEARCH_BATCH_SIZE = 64

enum class EditorSearchMode {
    Text,
    Regex,
}

enum class EditorSearchDirection {
    Forward,
    Backward,
}

data class EditorSearchRange(
    val startOffset: Long,
    val endOffsetExclusive: Long,
) {
    init {
        require(startOffset >= 0L && endOffsetExclusive >= startOffset)
    }
}

data class EditorSearchRequest(
    val mode: EditorSearchMode,
    val queries: List<String>,
    val range: EditorSearchRange,
    val encoding: EditorTextEncoding = EditorTextEncoding.UTF8,
    val caseSensitive: Boolean = true,
    val wholeWord: Boolean = false,
    val direction: EditorSearchDirection = EditorSearchDirection.Forward,
) {
    init {
        require(queries.isNotEmpty() && queries.all { it.isNotEmpty() })
        require(queries.size <= EDITOR_SEARCH_MAX_QUERY_COUNT) {
            AppStrings.ui_search_keywords_cannot_exceed_arg0.format(arg0 = (EDITOR_SEARCH_MAX_QUERY_COUNT).toString())
        }
        if (mode == EditorSearchMode.Regex) {
            require(queries.size == 1)
            require(queries.single().length <= EDITOR_SEARCH_MAX_REGEX_PATTERN_CHARS) {
                AppStrings.ui_regular_expressions_cannot_exceed_arg0_characters.format(arg0 = (EDITOR_SEARCH_MAX_REGEX_PATTERN_CHARS).toString())
            }
        }
    }
}

data class EditorSearchResult(
    val startOffset: Long,
    val endOffsetExclusive: Long,
    val keyword: String,
    val preview: String,
    val lineNumber: Long? = null,
)

data class EditorSearchSummary(
    val totalMatches: Long,
    val scannedBytes: Long,
    val sourceSnapshot: FileEditorSourceSnapshot,
)

data class EditorReplaceRequest(
    val search: EditorSearchRequest,
    val replacement: String,
)

data class EditorReplaceSummary(
    val replacedCount: Int,
    val skippedOverlappingCount: Int = 0,
)

class EditorRegexMatchLimitException(limitBytes: Int) :
    IllegalArgumentException(AppStrings.ui_a_regular_expression_cannot_exceed_arg0_bytes.format(arg0 = (limitBytes).toString()))

class LargeFileSearchEngine(
    private val source: FileEditorContentSource,
    private val codec: EditorTextCodec,
    private val chunkSize: Int = DEFAULT_FILE_EDITOR_SCAN_CHUNK_SIZE,
    private val batchSize: Int = DEFAULT_SEARCH_BATCH_SIZE,
) {
    init {
        require(chunkSize > 0)
        require(batchSize > 0)
    }

    suspend fun search(
        request: EditorSearchRequest,
        onBatch: suspend (List<EditorSearchResult>) -> Unit,
        onProgress: suspend (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<EditorSearchSummary> = try {
        require(request.range.endOffsetExclusive <= source.size) { AppStrings.ui_search_range_exceeds_the_file }
        val initialSnapshot = source.currentSnapshot().getOrThrow()
        var totalMatches = 0L
        val pending = ArrayList<EditorSearchResult>(batchSize)
        suspend fun publish(result: EditorSearchResult) {
            pending += result
            totalMatches += 1L
            if (pending.size >= batchSize) {
                onBatch(pending.toList())
                pending.clear()
            }
        }

        when (request.mode) {
            EditorSearchMode.Text -> searchExact(request, ::publish, onProgress)
            EditorSearchMode.Regex -> searchRegex(request, ::publish, onProgress)
        }
        if (pending.isNotEmpty()) onBatch(pending.toList())
        val finalSnapshot = source.currentSnapshot().getOrThrow()
        if (finalSnapshot != initialSnapshot) {
            throw FileEditorSourceChangedException(initialSnapshot, finalSnapshot)
        }
        Result.success(
            EditorSearchSummary(
                totalMatches = totalMatches,
                scannedBytes = request.range.endOffsetExclusive - request.range.startOffset,
                sourceSnapshot = finalSnapshot,
            )
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        LogKit.e(AppStrings.ui_large_file_search_failed, error)
        Result.failure(error)
    }

    private suspend fun searchExact(
        request: EditorSearchRequest,
        publish: suspend (EditorSearchResult) -> Unit,
        onProgress: suspend (Long, Long) -> Unit,
    ) {
        val patterns = request.queries.map { query ->
            val bytes = when (request.mode) {
                EditorSearchMode.Text -> codec.encode(query, request.encoding).bytes
                EditorSearchMode.Regex -> error(AppStrings.ui_regular_expressions_cannot_be_used_for_precise_searches)
            }
            require(bytes.isNotEmpty()) { AppStrings.ui_keyword_search_cannot_be_empty }
            require(bytes.size <= EDITOR_SEARCH_MAX_TEXT_PATTERN_BYTES) {
                AppStrings.ui_a_single_search_keyword_cannot_exceed_arg0_bytes.format(arg0 = (EDITOR_SEARCH_MAX_TEXT_PATTERN_BYTES).toString())
            }
            ExactPattern(query, normalizeCase(bytes, request.encoding, 0L, request.caseSensitive))
        }
        require(patterns.sumOf { it.bytes.size } <= EDITOR_SEARCH_MAX_TOTAL_PATTERN_BYTES) {
            AppStrings.editor_search_keyword_length_exceeds_arg0_bytes.format(arg0 = (EDITOR_SEARCH_MAX_TOTAL_PATTERN_BYTES).toString())
        }
        val maxPatternSize = patterns.maxOf { it.bytes.size }
        val carryLimit = maxPatternSize + wordBoundaryByteWidth(request.encoding)
        val lastEmittedStarts = LongArray(patterns.size) { Long.MIN_VALUE }
        var carry = byteArrayOf()
        var offset = request.range.startOffset
        val total = request.range.endOffsetExclusive - request.range.startOffset
        while (offset < request.range.endOffsetExclusive) {
            currentCoroutineContext().ensureActive()
            val end = minOf(offset + chunkSize, request.range.endOffsetExclusive)
            val chunk = source.readRange(offset, end).getOrThrow()
            check(chunk.size.toLong() == end - offset)
            val window = carry + chunk
            val windowStart = offset - carry.size
            val normalized = normalizeCase(window, request.encoding, windowStart, request.caseSensitive)
            patterns.forEachIndexed { patternIndex, pattern ->
                var fromIndex = 0
                while (fromIndex <= normalized.size - pattern.bytes.size) {
                    val matchIndex = normalized.indexOf(pattern.bytes, fromIndex)
                    if (matchIndex < 0) break
                    val absoluteStart = windowStart + matchIndex
                    val absoluteEnd = absoluteStart + pattern.bytes.size
                    if (
                        absoluteStart >= request.range.startOffset &&
                        absoluteEnd <= request.range.endOffsetExclusive &&
                        absoluteStart > lastEmittedStarts[patternIndex] &&
                        (!request.wholeWord || isWholeWord(
                            window,
                            matchIndex,
                            pattern.bytes.size,
                            request.encoding,
                            windowStart,
                            request.range,
                        ))
                    ) {
                        publish(
                            EditorSearchResult(
                                startOffset = absoluteStart,
                                endOffsetExclusive = absoluteEnd,
                                keyword = pattern.keyword,
                                preview = preview(window, matchIndex, pattern.bytes.size, request.encoding),
                            )
                        )
                        lastEmittedStarts[patternIndex] = absoluteStart
                    }
                    fromIndex = matchIndex + 1
                }
            }
            carry = window.takeLastBytes(carryLimit)
            offset = end
            onProgress(offset - request.range.startOffset, total)
        }
        if (total == 0L) onProgress(0L, 0L)
    }

    private suspend fun searchRegex(
        request: EditorSearchRequest,
        publish: suspend (EditorSearchResult) -> Unit,
        onProgress: suspend (Long, Long) -> Unit,
    ) {
        val options = if (request.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val regex = Regex(request.queries.single(), options)
        val total = request.range.endOffsetExclusive - request.range.startOffset
        val primaryChunkSize = maxOf(chunkSize, EDITOR_SEARCH_REGEX_MAX_MATCH_BYTES)
        var primaryStart = request.range.startOffset
        while (primaryStart < request.range.endOffsetExclusive) {
            currentCoroutineContext().ensureActive()
            val primaryEnd = minOf(primaryStart + primaryChunkSize, request.range.endOffsetExclusive)
            val windowEnd = minOf(
                primaryEnd + EDITOR_SEARCH_REGEX_MAX_MATCH_BYTES.toLong(),
                request.range.endOffsetExclusive,
            )
            val bytes = source.readRange(primaryStart, windowEnd).getOrThrow()
            check(bytes.size.toLong() == windowEnd - primaryStart)
            val decoded = codec.decode(bytes, request.encoding, stripBom = primaryStart == 0L).text
            var encodedThroughCharacter = 0
            var encodedThroughBytes = 0
            regex.findAll(decoded).forEach { match ->
                currentCoroutineContext().ensureActive()
                check(match.range.first >= encodedThroughCharacter)
                encodedThroughBytes += codec.encode(
                    decoded.substring(encodedThroughCharacter, match.range.first),
                    request.encoding,
                ).bytes.size
                val prefixBytes = encodedThroughBytes
                val matchBytes = codec.encode(match.value, request.encoding).bytes.size
                encodedThroughCharacter = match.range.last + 1
                encodedThroughBytes += matchBytes
                if (matchBytes > EDITOR_SEARCH_REGEX_MAX_MATCH_BYTES ||
                    (match.range.last == decoded.lastIndex && windowEnd < request.range.endOffsetExclusive)
                ) {
                    throw EditorRegexMatchLimitException(EDITOR_SEARCH_REGEX_MAX_MATCH_BYTES)
                }
                val absoluteStart = primaryStart + prefixBytes
                val absoluteEnd = absoluteStart + matchBytes
                if (
                    absoluteStart < primaryEnd &&
                    absoluteStart >= request.range.startOffset &&
                    absoluteEnd <= request.range.endOffsetExclusive &&
                    (!request.wholeWord || textWholeWord(decoded, match.range.first, match.range.last + 1))
                ) {
                    publish(
                        EditorSearchResult(
                            absoluteStart,
                            absoluteEnd,
                            request.queries.single(),
                            decoded.substring(
                                (match.range.first - 24).coerceAtLeast(0),
                                (match.range.last + 25).coerceAtMost(decoded.length),
                            ),
                        )
                    )
                }
            }
            primaryStart = primaryEnd
            onProgress(primaryStart - request.range.startOffset, total)
        }
        if (total == 0L) onProgress(0L, 0L)
    }

    private fun preview(
        window: ByteArray,
        matchIndex: Int,
        matchSize: Int,
        encoding: EditorTextEncoding,
    ): String {
        val start = (matchIndex - 24).coerceAtLeast(0)
        val end = (matchIndex + matchSize + 24).coerceAtMost(window.size)
        return codec.decode(window.copyOfRange(start, end), encoding, stripBom = false).text
            .replace('\r', ' ')
            .replace('\n', ' ')
            .take(96)
    }
}

class EditorSearchResultStore(
    private val detailLimit: Int = EDITOR_SEARCH_RESULT_DETAIL_LIMIT,
) {
    init {
        require(detailLimit >= 0)
    }

    private val mutableResults = mutableListOf<EditorSearchResult>()
    val results: List<EditorSearchResult> get() = mutableResults
    var totalCount: Long = 0L
        private set
    val detailsTruncated: Boolean get() = totalCount > mutableResults.size

    fun add(batch: List<EditorSearchResult>) {
        totalCount += batch.size
        val remaining = detailLimit - mutableResults.size
        if (remaining > 0) mutableResults += batch.take(remaining)
    }

    fun clear() {
        mutableResults.clear()
        totalCount = 0L
    }

    fun navigate(
        currentIndex: Int?,
        direction: EditorSearchDirection,
        wrap: Boolean,
    ): Int? {
        if (mutableResults.isEmpty()) return null
        val current = currentIndex ?: if (direction == EditorSearchDirection.Forward) -1 else mutableResults.size
        val target = current + if (direction == EditorSearchDirection.Forward) 1 else -1
        if (target in mutableResults.indices) return target
        if (!wrap) return null
        return if (direction == EditorSearchDirection.Forward) 0 else mutableResults.lastIndex
    }
}

private data class ExactPattern(val keyword: String, val bytes: ByteArray)

private fun ByteArray.indexOf(pattern: ByteArray, startIndex: Int): Int {
    if (pattern.isEmpty()) return startIndex.coerceAtMost(size)
    for (index in startIndex.coerceAtLeast(0)..size - pattern.size) {
        var matched = true
        for (patternIndex in pattern.indices) {
            if (this[index + patternIndex] != pattern[patternIndex]) {
                matched = false
                break
            }
        }
        if (matched) return index
    }
    return -1
}

private fun normalizeCase(
    bytes: ByteArray,
    encoding: EditorTextEncoding,
    absoluteStart: Long,
    caseSensitive: Boolean,
): ByteArray {
    if (caseSensitive) return bytes
    val output = bytes.copyOf()
    when (encoding) {
        EditorTextEncoding.UTF16_LE, EditorTextEncoding.UTF16_BE -> {
            var index = if ((absoluteStart and 1L) == 0L) 0 else 1
            while (index + 1 < output.size) {
                val valueIndex = if (encoding == EditorTextEncoding.UTF16_LE) index else index + 1
                val value = output[valueIndex].toInt() and 0xFF
                if (value in 'A'.code..'Z'.code) output[valueIndex] = (value + 32).toByte()
                index += 2
            }
        }
        else -> output.indices.forEach { index ->
            val value = output[index].toInt() and 0xFF
            if (value in 'A'.code..'Z'.code) output[index] = (value + 32).toByte()
        }
    }
    return output
}

private fun wordBoundaryByteWidth(encoding: EditorTextEncoding): Int =
    if (encoding == EditorTextEncoding.UTF16_LE || encoding == EditorTextEncoding.UTF16_BE) 2 else 1

private fun isWholeWord(
    window: ByteArray,
    matchIndex: Int,
    matchSize: Int,
    encoding: EditorTextEncoding,
    windowStart: Long,
    range: EditorSearchRange,
): Boolean {
    val width = wordBoundaryByteWidth(encoding)
    val absoluteStart = windowStart + matchIndex
    val absoluteEnd = absoluteStart + matchSize
    val beforeWord = absoluteStart > range.startOffset &&
        readBoundaryCharacter(window, matchIndex - width, encoding)?.isEditorWordCharacter() == true
    val afterWord = absoluteEnd < range.endOffsetExclusive &&
        readBoundaryCharacter(window, matchIndex + matchSize, encoding)?.isEditorWordCharacter() == true
    return !beforeWord && !afterWord
}

private fun readBoundaryCharacter(bytes: ByteArray, index: Int, encoding: EditorTextEncoding): Char? {
    if (index < 0 || index >= bytes.size) return null
    return when (encoding) {
        EditorTextEncoding.UTF16_LE, EditorTextEncoding.UTF16_BE -> {
            if (index + 1 >= bytes.size) return null
            val first = bytes[index].toInt() and 0xFF
            val second = bytes[index + 1].toInt() and 0xFF
            if (encoding == EditorTextEncoding.UTF16_LE) (first or (second shl 8)).toChar()
            else ((first shl 8) or second).toChar()
        }
        else -> (bytes[index].toInt() and 0xFF).toChar()
    }
}

private fun textWholeWord(text: String, start: Int, endExclusive: Int): Boolean =
    (start == 0 || !text[start - 1].isEditorWordCharacter()) &&
        (endExclusive == text.length || !text[endExclusive].isEditorWordCharacter())

private fun Char.isEditorWordCharacter(): Boolean = isLetterOrDigit() || this == '_'

private fun ByteArray.takeLastBytes(count: Int): ByteArray =
    if (size <= count) copyOf() else copyOfRange(size - count, size)

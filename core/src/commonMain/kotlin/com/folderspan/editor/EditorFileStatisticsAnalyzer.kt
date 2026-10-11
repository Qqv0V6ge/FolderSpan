package com.folderspan.editor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import strings.AppStrings

data class EditorFileStatistics(
    val lineCount: Long,
    val blankLineCount: Long,
    val characterFrequency: Map<Char, Long>,
    val byteDistribution: List<Long>,
    val zeroByteCount: Long,
    val replacementCharacterCount: Long,
    val sourceSnapshot: FileEditorSourceSnapshot,
)

data class EditorKeywordStatistics(
    val occurrences: Map<String, Long>,
    val sourceSnapshot: FileEditorSourceSnapshot,
)

internal data class EditorStatisticsAnalysis(
    val statistics: EditorFileStatistics,
    val keywordStatistics: EditorKeywordStatistics?,
)

enum class EditorStatisticsScope {
    CurrentPage,
    Selection,
    WholeFile,
}

data class EditorStatisticsRequest(
    val scope: EditorStatisticsScope = EditorStatisticsScope.WholeFile,
    val keywordQueries: List<String> = emptyList(),
)

/** Collects text and raw-byte statistics together in one cancellable bounded scan. */
class EditorFileStatisticsAnalyzer(
    private val source: FileEditorContentSource,
    private val codec: EditorTextCodec,
    private val encoding: EditorTextEncoding,
    private val hasBom: Boolean,
    private val chunkSize: Int = DEFAULT_FILE_EDITOR_SCAN_CHUNK_SIZE,
) {
    init {
        require(chunkSize > 0)
    }

    suspend fun analyze(
        range: EditorSearchRange = EditorSearchRange(0L, source.size),
        onProgress: suspend (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<EditorFileStatistics> = analyzeWithKeywords(
        queries = emptyList(),
        range = range,
        onProgress = onProgress,
    ).map(EditorStatisticsAnalysis::statistics)

    internal suspend fun analyzeWithKeywords(
        queries: List<String>,
        range: EditorSearchRange = EditorSearchRange(0L, source.size),
        onProgress: suspend (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<EditorStatisticsAnalysis> = try {
        val normalizedQueries = queries.map(String::trim).filter(String::isNotEmpty).distinct()
        val initialSnapshot = source.currentSnapshot().getOrThrow()
        require(range.endOffsetExclusive <= initialSnapshot.size) { AppStrings.editor_statistics_range_exceeds_file }
        val decoder = codec.incrementalDecoder(encoding)
        val totalBytes = range.endOffsetExclusive - range.startOffset
        val textStatistics = TextStatisticsAccumulator(totalBytes > 0L)
        val keywordCounter = ExactKeywordCounter(codec, encoding, normalizedQueries, range)
        val distribution = LongArray(256)
        var replacementCount = 0L
        var offset = range.startOffset
        var firstDecodedChunk = true
        while (offset < range.endOffsetExclusive) {
            currentCoroutineContext().ensureActive()
            val end = minOf(offset + chunkSize, range.endOffsetExclusive)
            val bytes = source.readRange(offset, end).getOrThrow()
            check(bytes.size.toLong() == end - offset) {
                AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range
            }
            bytes.forEach { byte -> distribution[byte.toInt() and 0xFF] += 1L }
            keywordCounter.accept(bytes, offset)
            val decoded = decoder.accept(bytes)
            replacementCount += decoded.replacementCount
            val text = if (firstDecodedChunk && hasBom && range.startOffset == 0L) {
                decoded.text.removePrefix("\uFEFF")
            } else {
                decoded.text
            }
            firstDecodedChunk = false
            textStatistics.accept(text)
            offset = end
            onProgress(offset - range.startOffset, totalBytes)
        }
        val finalDecoded = decoder.finish()
        replacementCount += finalDecoded.replacementCount
        textStatistics.accept(
            if (firstDecodedChunk && hasBom && range.startOffset == 0L) {
                finalDecoded.text.removePrefix("\uFEFF")
            } else {
                finalDecoded.text
            }
        )
        val textSummary = textStatistics.finish()
        if (totalBytes == 0L) onProgress(0L, 0L)
        val finalSnapshot = source.currentSnapshot().getOrThrow()
        if (finalSnapshot != initialSnapshot) {
            throw FileEditorSourceChangedException(initialSnapshot, finalSnapshot)
        }
        val statistics = EditorFileStatistics(
            lineCount = textSummary.first,
            blankLineCount = textSummary.second,
            characterFrequency = textStatistics.frequencies.toMap(),
            byteDistribution = distribution.toList(),
            zeroByteCount = distribution[0],
            replacementCharacterCount = replacementCount,
            sourceSnapshot = finalSnapshot,
        )
        Result.success(
            EditorStatisticsAnalysis(
                statistics = statistics,
                keywordStatistics = normalizedQueries.takeIf { it.isNotEmpty() }?.let {
                    EditorKeywordStatistics(
                        occurrences = keywordCounter.occurrences(),
                        sourceSnapshot = finalSnapshot,
                    )
                },
            )
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

    suspend fun analyzeKeywords(
        queries: List<String>,
        range: EditorSearchRange = EditorSearchRange(0L, source.size),
        caseSensitive: Boolean = true,
        wholeWord: Boolean = false,
        onProgress: suspend (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<EditorKeywordStatistics> {
        val normalized = queries.map(String::trim).filter(String::isNotEmpty).distinct()
        require(normalized.isNotEmpty()) { AppStrings.ui_keywords_cannot_be_empty }
        val occurrences = normalized.associateWith { 0L }.toMutableMap()
        return LargeFileSearchEngine(source, codec, chunkSize = chunkSize).search(
            request = EditorSearchRequest(
                mode = EditorSearchMode.Text,
                queries = normalized,
                range = range,
                encoding = encoding,
                caseSensitive = caseSensitive,
                wholeWord = wholeWord,
            ),
            onBatch = { batch ->
                batch.forEach { result ->
                    occurrences[result.keyword] = occurrences.getValue(result.keyword) + 1L
                }
            },
            onProgress = onProgress,
        ).map { summary ->
            EditorKeywordStatistics(
                occurrences = occurrences.toMap(),
                sourceSnapshot = summary.sourceSnapshot,
            )
        }
    }
}

private class ExactKeywordCounter(
    codec: EditorTextCodec,
    encoding: EditorTextEncoding,
    queries: List<String>,
    private val range: EditorSearchRange,
) {
    private data class Pattern(
        val keyword: String,
        val bytes: ByteArray,
    )

    private val patterns = queries.map { query ->
        Pattern(
            keyword = query,
            bytes = codec.encode(query, encoding).bytes.also { bytes ->
                require(bytes.isNotEmpty()) { AppStrings.ui_keywords_cannot_be_empty }
                require(bytes.size <= EDITOR_SEARCH_MAX_TEXT_PATTERN_BYTES) {
                    AppStrings.ui_a_single_keyword_cannot_exceed_arg0_bytes.format(arg0 = (EDITOR_SEARCH_MAX_TEXT_PATTERN_BYTES).toString())
                }
            },
        )
    }
    init {
        require(patterns.size <= EDITOR_SEARCH_MAX_QUERY_COUNT) {
            AppStrings.ui_keywords_cannot_exceed_arg0.format(arg0 = (EDITOR_SEARCH_MAX_QUERY_COUNT).toString())
        }
        require(patterns.sumOf { it.bytes.size } <= EDITOR_SEARCH_MAX_TOTAL_PATTERN_BYTES) {
            AppStrings.editor_statistics_keyword_length_exceeds_arg0_bytes.format(arg0 = (EDITOR_SEARCH_MAX_TOTAL_PATTERN_BYTES).toString())
        }
    }

    private val counts = LongArray(patterns.size)
    private val lastEmittedStarts = LongArray(patterns.size) { Long.MIN_VALUE }
    private val carryLimit = (patterns.maxOfOrNull { it.bytes.size } ?: 1) - 1
    private var carry = byteArrayOf()

    fun accept(bytes: ByteArray, absoluteOffset: Long) {
        if (patterns.isEmpty()) return
        val window = carry + bytes
        val windowStart = absoluteOffset - carry.size
        patterns.forEachIndexed { patternIndex, pattern ->
            var fromIndex = 0
            while (fromIndex <= window.size - pattern.bytes.size) {
                val matchIndex = window.indexOfBytes(pattern.bytes, fromIndex)
                if (matchIndex < 0) break
                val absoluteStart = windowStart + matchIndex
                val absoluteEnd = absoluteStart + pattern.bytes.size
                if (
                    absoluteStart >= range.startOffset &&
                    absoluteEnd <= range.endOffsetExclusive &&
                    absoluteStart > lastEmittedStarts[patternIndex]
                ) {
                    counts[patternIndex] += 1L
                    lastEmittedStarts[patternIndex] = absoluteStart
                }
                fromIndex = matchIndex + 1
            }
        }
        carry = if (carryLimit <= 0) {
            byteArrayOf()
        } else {
            window.copyOfRange((window.size - carryLimit).coerceAtLeast(0), window.size)
        }
    }

    fun occurrences(): Map<String, Long> =
        patterns.mapIndexed { index, pattern -> pattern.keyword to counts[index] }.toMap()
}

private fun ByteArray.indexOfBytes(pattern: ByteArray, startIndex: Int): Int {
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

private class TextStatisticsAccumulator(hasContent: Boolean) {
    val frequencies = mutableMapOf<Char, Long>()
    private var lineCount = if (hasContent) 1L else 0L
    private var blankLineCount = 0L
    private var currentLineHasContent = false
    private var pendingCr = false

    fun accept(text: String) {
        var index = 0
        if (pendingCr && text.isNotEmpty()) {
            if (text[0] == '\n') index = 1
            finishLine()
            pendingCr = false
        }
        while (index < text.length) {
            when (val char = text[index]) {
                '\r' -> {
                    if (index + 1 < text.length) {
                        if (text[index + 1] == '\n') index += 1
                        finishLine()
                    } else {
                        pendingCr = true
                    }
                }
                '\n' -> finishLine()
                else -> {
                    frequencies[char] = frequencies.getOrElse(char) { 0L } + 1L
                    currentLineHasContent = true
                }
            }
            index += 1
        }
    }

    fun finish(): Pair<Long, Long> {
        if (pendingCr) {
            finishLine()
            pendingCr = false
        }
        if (lineCount > 0L && !currentLineHasContent) blankLineCount += 1L
        return lineCount to blankLineCount
    }

    private fun finishLine() {
        if (!currentLineHasContent) blankLineCount += 1L
        lineCount += 1L
        currentLineHasContent = false
    }
}

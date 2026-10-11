package com.folderspan.editor

import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import strings.AppStrings

const val DEFAULT_FILE_EDITOR_PAGE_SIZE: Int = 32 * 1024
const val DEFAULT_FILE_EDITOR_CACHE_PAGES: Int = 3
const val FILE_EDITOR_OVERSIZED_THRESHOLD_BYTES: Long = 1_073_741_824L
private const val MAX_TEXT_PAGE_SIZE_MULTIPLIER: Int = 4

enum class FileEditorAccessMode {
    ReadOnly,
    Editable,
}

data class FileEditorDocumentState(
    val fileSize: Long = 0L,
    val currentPage: Long = 0L,
    val pageCount: Long = 1L,
    val pageStartOffset: Long = 0L,
    val pageEndOffsetExclusive: Long = 0L,
    val text: String = "",
    val bytes: ByteArray = byteArrayOf(),
    val canWrite: Boolean = false,
    val sourceCanWrite: Boolean = false,
    val accessMode: FileEditorAccessMode = FileEditorAccessMode.ReadOnly,
    val isOversized: Boolean = false,
    val encoding: EditorTextEncoding = EditorTextEncoding.UTF8,
    val encodingConfidence: EditorEncodingConfidence = EditorEncodingConfidence.Low,
    val hasEncodingBom: Boolean = false,
    val newlineKind: EditorNewlineKind = EditorNewlineKind.None,
    val newlineConversion: EditorNewlineKind? = null,
    val selectionStartOffset: Long? = null,
    val selectionEndOffsetExclusive: Long? = null,
    val currentLineNumber: Long? = null,
    val lineNumberProvisional: Boolean = true,
    val isLineIndexing: Boolean = false,
    val lineIndexProgress: Float = 0f,
    val isSearching: Boolean = false,
    val searchProgress: Float = 0f,
    val searchResults: List<EditorSearchResult> = emptyList(),
    val searchTotalCount: Long = 0L,
    val searchDetailsTruncated: Boolean = false,
    val searchCompleted: Boolean = false,
    val searchError: String? = null,
    val searchHistory: List<EditorSearchHistoryEntry> = emptyList(),
    val isAnalyzing: Boolean = false,
    val analysisProgress: Float = 0f,
    val statistics: EditorFileStatistics? = null,
    val keywordStatistics: EditorKeywordStatistics? = null,
    val statisticsScope: EditorStatisticsScope? = null,
    val analysisCompleted: Boolean = false,
    val analysisError: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val dirty: Boolean = false,
    val modifications: List<EditorModification> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val editHistoryCommandCount: Int = 0,
    val editHistoryStoredBytes: Long = 0L,
    val pendingEditStoredBytes: Long = 0L,
    val saveConflict: EditorSaveConflict? = null,
    val recoveryAvailable: Boolean = false,
    val recoverySourceChanged: Boolean = false,
    val recoveryReviewOnly: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
    val supportsTextMode: Boolean = true,
) {
    // Compatibility aliases for callers created while this API was being introduced.
    val pageIndex: Long
        get() = currentPage
    val isDirty: Boolean
        get() = dirty
    val saveProgress: Float
        get() = progress
}

private data class DecodedTextPage(
    val text: String,
    val prefix: ByteArray,
    val suffix: ByteArray,
    val isText: Boolean,
    val usesFollowingPageBytes: Boolean = false,
)

/**
 * Bounded, page-oriented editing session suitable for direct observation from Compose.
 *
 * Only the current page and at most [maxCachedPages] original pages are retained. Text edits may
 * change a page's byte length.
 * Saving merges replacements with unchanged source pages as a bounded [kotlinx.coroutines.flow.Flow].
 */
class FileEditorDocument(
    private val source: FileEditorContentSource,
    private val pageSize: Int = DEFAULT_FILE_EDITOR_PAGE_SIZE,
    private val maxCachedPages: Int = DEFAULT_FILE_EDITOR_CACHE_PAGES,
    private val taskCoordinator: FileEditorTaskCoordinator = FileEditorTaskCoordinator(),
    private val lineIndexCache: EditorLineIndexCacheStore? = null,
    private val searchHistoryStore: EditorSearchHistoryStore? = null,
    private val backupStore: EditorBackupStore? = null,
    private val editorSessionKey: String? = null,
    private val recoveryJournalStore: EditorRecoveryJournalStore? = null,
    maxEditHistoryCommands: Int = DEFAULT_EDITOR_EDIT_HISTORY_COMMANDS,
    maxEditHistoryBytes: Long = DEFAULT_EDITOR_EDIT_HISTORY_BYTES,
    private val maxPendingEditBytes: Long = DEFAULT_EDITOR_PENDING_EDIT_BYTES,
) {
    init {
        require(pageSize > 0) { AppStrings.ui_pagesize_must_be_greater_than_0 }
        require(maxCachedPages > 0) { AppStrings.ui_maxcachedpages_must_be_greater_than_0 }
        require(source.size >= 0L) { AppStrings.ui_file_size_cannot_be_negative }
        require(maxPendingEditBytes > 0L) { AppStrings.ui_maxpendingeditbytes_must_be_greater_than_0 }
    }

    private data class PageReplacement(
        val originalBytes: ByteArray,
        val bytes: ByteArray,
        val kind: EditorModificationKind,
        val encodingReplacementCount: Int = 0,
        val encodingReplacementSamples: List<EditorEncodingReplacementSample> = emptyList(),
    ) {
        val originalByteCount: Int
            get() = originalBytes.size
        val storedBytes: Long
            get() = originalBytes.size.toLong() + bytes.size.toLong()
    }

    private val operationMutex = Mutex()
    private val pagingService = FileEditorPagingService(
        source = source,
        pageSize = pageSize,
        maxCachedPages = maxCachedPages,
        taskCoordinator = taskCoordinator,
    )
    private val replacements = mutableMapOf<Long, PageReplacement>()
    private val editCommandLog = EditorEditCommandLog(
        maxCommands = maxEditHistoryCommands,
        maxStoredBytes = maxEditHistoryBytes,
    )
    private var currentOriginalPage = byteArrayOf()
    private var currentSize: Long = source.size
    private var sessionSnapshot: FileEditorSourceSnapshot? = null
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recoveryWriteJob: Job? = null
    private var pendingRecoveryJournal: EditorRecoveryJournal? = null
    private var initialized = false
    private var currentTextPrefix = byteArrayOf()
    private var currentTextSuffix = byteArrayOf()
    private var currentTextUsesFollowingPageBytes = false
    private var textCodec: EditorTextCodec? = null
    private var encodingExplicitlySelected = false
    private var backgroundLineIndexer: EditorBackgroundLineIndexer? = null
    private var lineIndexGeneration = 0L
    private var searchGeneration = 0L
    private var analysisGeneration = 0L
    private var contentRevision = 0L

    private val _state = MutableStateFlow(
        FileEditorDocumentState(
            fileSize = currentSize,
            pageCount = pageCount(currentSize),
            canWrite = source.canWrite,
            sourceCanWrite = source.canWrite,
            accessMode = if (source.canWrite) {
                FileEditorAccessMode.Editable
            } else {
                FileEditorAccessMode.ReadOnly
            },
            isOversized = currentSize >= FILE_EDITOR_OVERSIZED_THRESHOLD_BYTES,
            searchHistory = searchHistoryStore?.list().orEmpty(),
        )
    )
    val state: StateFlow<FileEditorDocumentState> = _state.asStateFlow()

    suspend fun initialize(): Result<Unit> = operationMutex.withLock {
        if (initialized) return@withLock Result.success(Unit)
        if (textCodec == null) textCodec = EditorTextCodec.load()
        val snapshot = source.currentSnapshot().getOrElse { error ->
            return@withLock fail(error.message?.ifBlank { null } ?: AppStrings.ui_file_version_information_cannot_be_read)
        }
        currentSize = snapshot.size
        sessionSnapshot = snapshot
        _state.value = _state.value.copy(
            fileSize = currentSize,
            pageCount = pageCount(currentSize),
            isOversized = currentSize >= FILE_EDITOR_OVERSIZED_THRESHOLD_BYTES,
        )
        val loaded = loadPageLocked(0L)
        if (loaded.isSuccess) {
            initialized = true
            loadPendingRecoveryLocked(snapshot)
            restartBackgroundLineIndexLocked()
        }
        loaded
    }

    fun unlockEditing(confirmed: Boolean): Result<Unit> {
        val current = _state.value
        if (!current.sourceCanWrite) return fail(AppStrings.ui_the_content_source_does_not_support_writing)
        if (!confirmed) return fail(AppStrings.ui_enable_editing_before_confirming_changes)
        if (current.isLoading || current.isSaving) return fail(AppStrings.ui_the_editor_is_busy)
        _state.value = current.copy(
            canWrite = true,
            accessMode = FileEditorAccessMode.Editable,
            error = null,
        )
        return Result.success(Unit)
    }

    fun lockEditing(): Result<Unit> {
        val current = _state.value
        if (current.dirty) return fail(AppStrings.ui_there_are_unsaved_changes_so_you_cannot_switch_to_read_only_mode)
        if (current.isLoading || current.isSaving) return fail(AppStrings.ui_the_editor_is_busy)
        _state.value = current.copy(
            canWrite = false,
            accessMode = FileEditorAccessMode.ReadOnly,
            error = null,
        )
        return Result.success(Unit)
    }

    suspend fun setEncoding(encoding: EditorTextEncoding): Result<Unit> = operationMutex.withLock {
        val current = _state.value
        if (!initialized) return@withLock fail(AppStrings.ui_file_not_initialized)
        if (current.isLoading || current.isSaving) return@withLock fail(AppStrings.ui_the_editor_is_busy)
        val codec = textCodec ?: EditorTextCodec.load().also { textCodec = it }
        encodingExplicitlySelected = true
        val page = decodePageWithLookahead(codec, encoding, current)
        currentTextPrefix = page.prefix
        currentTextSuffix = page.suffix
        currentTextUsesFollowingPageBytes = page.usesFollowingPageBytes
        _state.value = current.copy(
            encoding = encoding,
            encodingConfidence = EditorEncodingConfidence.High,
            text = page.text,
            supportsTextMode = page.isText,
            error = null,
        )
        restartBackgroundLineIndexLocked()
        Result.success(Unit)
    }

    fun setNewlineConversion(target: EditorNewlineKind?): Result<Unit> {
        if (target != null && target !in setOf(EditorNewlineKind.LF, EditorNewlineKind.CRLF, EditorNewlineKind.CR)) {
            return fail(AppStrings.ui_line_conversion_is_invalid)
        }
        _state.value = _state.value.copy(
            newlineConversion = target,
            dirty = replacements.isNotEmpty() || target != null,
            error = null,
        )
        scheduleRecoveryJournalWrite()
        return Result.success(Unit)
    }

    fun updateTextSelection(start: Int, end: Int): Result<Unit> {
        val current = _state.value
        if (start !in 0..current.text.length || end !in 0..current.text.length) {
            return fail(AppStrings.ui_the_selection_area_is_invalid)
        }
        val codec = textCodec ?: return fail(AppStrings.ui_text_decoder_not_initialized)
        fun absoluteOffset(index: Int): Long = current.pageStartOffset + currentTextPrefix.size +
                codec.encode(current.text.substring(0, index), current.encoding).bytes.size

        val first = minOf(start, end)
        val last = maxOf(start, end)
        _state.value = current.copy(
            selectionStartOffset = absoluteOffset(first),
            selectionEndOffsetExclusive = absoluteOffset(last),
            error = null,
        )
        return Result.success(Unit)
    }

    fun selectedTextRange(): IntRange? {
        val current = _state.value
        val selectionStart = current.selectionStartOffset ?: return null
        val selectionEnd = current.selectionEndOffsetExclusive ?: return null
        if (selectionStart !in current.pageStartOffset..current.pageEndOffsetExclusive) return null
        if (selectionEnd !in current.pageStartOffset..current.pageEndOffsetExclusive) return null
        val codec = textCodec ?: return null
        fun characterIndex(absoluteOffset: Long): Int {
            val relativeByteCount = (absoluteOffset - current.pageStartOffset - currentTextPrefix.size)
                .coerceIn(0L, current.bytes.size.toLong())
                .toInt()
            var low = 0
            var high = current.text.length
            while (low < high) {
                val middle = (low + high + 1) / 2
                val encodedSize = codec.encode(current.text.substring(0, middle), current.encoding).bytes.size
                if (encodedSize <= relativeByteCount) low = middle else high = middle - 1
            }
            return low
        }
        return characterIndex(selectionStart)..characterIndex(selectionEnd)
    }

    suspend fun goToPage(index: Long): Result<Unit> = operationMutex.withLock {
        val lastIndex = pageCount(currentSize) - 1L
        loadPageLocked(index.coerceIn(0L, lastIndex)).onSuccess { initialized = true }
    }

    suspend fun goToByteOffset(offset: Long): Result<Unit> = operationMutex.withLock {
        if (offset < 0L || offset > currentSize || (currentSize > 0L && offset == currentSize)) {
            return@withLock fail(AppStrings.ui_byte_offset_exceeds_file_range)
        }
        val target = if (currentSize == 0L) 0L else offset
        val page = if (currentSize == 0L) 0L else target / pageSize.toLong()
        loadPageLocked(page).map {
            _state.value = _state.value.copy(
                selectionStartOffset = target,
                selectionEndOffsetExclusive = target,
                error = null,
            )
        }
    }

    suspend fun goToPercentage(percentage: Double): Result<Unit> {
        if (!percentage.isFinite() || percentage !in 0.0..100.0) {
            return fail(AppStrings.ui_the_percentage_must_be_between_0_and_100)
        }
        val offset = if (currentSize <= 1L) {
            0L
        } else {
            ((currentSize - 1L).toDouble() * percentage / 100.0).toLong()
        }
        return goToByteOffset(offset)
    }

    suspend fun startSearch(request: EditorSearchRequest): Result<Unit> = operationMutex.withLock {
        if (!initialized) return@withLock fail(AppStrings.ui_file_not_initialized)
        if (request.range.endOffsetExclusive > currentSize) return@withLock fail(AppStrings.ui_search_range_exceeds_the_file)
        val codec = textCodec ?: return@withLock fail(AppStrings.ui_text_decoder_not_initialized)
        val contentSnapshot = currentContentSnapshot()
        val snapshotRequest = request.forCurrentContent(contentSnapshot)
        val searchHistory = searchHistoryStore?.record(request).orEmpty()
        taskCoordinator.cancel(FileEditorTaskKind.Search)
        searchGeneration += 1L
        val generation = searchGeneration
        val store = EditorSearchResultStore()
        _state.value = _state.value.copy(
            isSearching = true,
            searchProgress = 0f,
            searchResults = emptyList(),
            searchTotalCount = 0L,
            searchDetailsTruncated = false,
            searchCompleted = false,
            searchError = null,
            searchHistory = searchHistory,
            error = null,
        )
        taskCoordinator.launch(FileEditorTaskKind.Search) {
            val totalBytes = snapshotRequest.range.endOffsetExclusive - snapshotRequest.range.startOffset
            val result = LargeFileSearchEngine(contentSnapshot.source, codec).search(
                request = snapshotRequest,
                onBatch = { batch ->
                    store.add(
                        batch.map { result ->
                            result.copy(
                                startOffset = contentSnapshot.logicalOffsetToDocument(result.startOffset),
                                endOffsetExclusive =
                                    contentSnapshot.logicalOffsetToDocument(result.endOffsetExclusive),
                            )
                        }
                    )
                    if (generation == searchGeneration) {
                        _state.value = _state.value.copy(
                            searchResults = store.results.toList(),
                            searchTotalCount = store.totalCount,
                            searchDetailsTruncated = store.detailsTruncated,
                        )
                    }
                },
                onProgress = { completed, _ ->
                    if (generation == searchGeneration) {
                        _state.value = _state.value.copy(
                            searchProgress = if (totalBytes <= 0L) 1f else
                                (completed.toDouble() / totalBytes.toDouble())
                                    .coerceIn(0.0, 1.0).toFloat(),
                        )
                    }
                },
            )
            if (generation == searchGeneration) {
                result.fold(
                    onSuccess = {
                        _state.value = _state.value.copy(
                            isSearching = false,
                            searchProgress = 1f,
                            searchCompleted = true,
                        )
                    },
                    onFailure = { error ->
                        _state.value = _state.value.copy(
                            isSearching = false,
                            searchCompleted = false,
                            searchError = error.message ?: AppStrings.ui_search_failed,
                        )
                    },
                )
            }
        }
        Result.success(Unit)
    }

    suspend fun cancelSearch(): Result<Unit> {
        searchGeneration += 1L
        taskCoordinator.cancel(FileEditorTaskKind.Search)
        _state.value = _state.value.copy(
            isSearching = false,
            searchCompleted = false,
            searchError = null,
        )
        return Result.success(Unit)
    }

    suspend fun deleteSearchHistory(entry: EditorSearchHistoryEntry): Result<Unit> =
        operationMutex.withLock {
            runCatching {
                val store = searchHistoryStore ?: error(AppStrings.ui_search_history_storage_unavailable)
                _state.value = _state.value.copy(searchHistory = store.remove(entry))
            }
        }

    suspend fun clearSearchHistory(): Result<Unit> = operationMutex.withLock {
        runCatching {
            val store = searchHistoryStore ?: error(AppStrings.ui_search_history_storage_unavailable)
            _state.value = _state.value.copy(searchHistory = store.clear())
        }
    }

    suspend fun goToSearchResult(result: EditorSearchResult): Result<Unit> =
        operationMutex.withLock {
            if (result.startOffset < 0L || result.endOffsetExclusive > currentSize) {
                return@withLock fail(AppStrings.ui_search_results_exceed_the_file_range)
            }
            val page = if (currentSize == 0L) 0L else result.startOffset / pageSize.toLong()
            loadPageLocked(page).map {
                _state.value = withEditState(
                    _state.value.copy(
                        fileSize = currentSize,
                        selectionStartOffset = result.startOffset,
                        selectionEndOffsetExclusive = result.endOffsetExclusive,
                        error = null,
                    )
                )
            }
        }

    suspend fun replaceCurrent(
        request: EditorReplaceRequest,
        result: EditorSearchResult,
    ): Result<EditorReplaceSummary> = operationMutex.withLock {
        validateReplaceLocked(request.search).getOrElse { return@withLock Result.failure(it) }
        if (result.startOffset < request.search.range.startOffset ||
            result.endOffsetExclusive > request.search.range.endOffsetExclusive
        ) {
            return@withLock failValue(AppStrings.ui_current_search_results_have_expired_please_re_search)
        }
        applyReplacementResultsLocked(request, listOf(result), skippedOverlapping = 0)
    }

    suspend fun replaceAll(request: EditorReplaceRequest): Result<EditorReplaceSummary> =
        operationMutex.withLock {
            validateReplaceLocked(request.search).getOrElse { return@withLock Result.failure(it) }
            val codec = textCodec ?: return@withLock failValue(AppStrings.ui_text_decoder_not_initialized)
            val contentSnapshot = currentContentSnapshot()
            val snapshotRequest = request.search.forCurrentContent(contentSnapshot)
            val matches = mutableListOf<EditorSearchResult>()
            val searchResult = withContext(Dispatchers.Default) {
                LargeFileSearchEngine(contentSnapshot.source, codec).search(
                    request = snapshotRequest,
                    onBatch = { batch ->
                        if (matches.size + batch.size > EDITOR_SEARCH_RESULT_DETAIL_LIMIT) {
                            throw IllegalStateException(
                                AppStrings.ui_match_items_exceeding_arg0_count_please_adjust_the_replacement_range.format(arg0 = (EDITOR_SEARCH_RESULT_DETAIL_LIMIT).toString())
                            )
                        }
                        matches += batch.map { result ->
                            result.copy(
                                startOffset = contentSnapshot.logicalOffsetToDocument(result.startOffset),
                                endOffsetExclusive =
                                    contentSnapshot.logicalOffsetToDocument(result.endOffsetExclusive),
                            )
                        }
                    },
                )
            }
            searchResult.getOrElse { error ->
                return@withLock failValue(error.message ?: AppStrings.ui_find_and_replace_failed_item)
            }
            val (accepted, skipped) = nonOverlappingReplacementResults(matches)
            applyReplacementResultsLocked(request, accepted, skipped)
        }

    suspend fun startStatistics(keywordQueries: List<String> = emptyList()): Result<Unit> =
        startStatistics(EditorStatisticsRequest(keywordQueries = keywordQueries))

    suspend fun startStatistics(request: EditorStatisticsRequest): Result<Unit> =
        operationMutex.withLock {
            if (!initialized) return@withLock fail(AppStrings.ui_file_not_initialized)
            val codec = textCodec ?: return@withLock fail(AppStrings.ui_text_decoder_not_initialized)
            val queries = request.keywordQueries.map(String::trim).filter(String::isNotEmpty).distinct()
            val contentSnapshot = currentContentSnapshot()
            val range = when (request.scope) {
                EditorStatisticsScope.CurrentPage -> contentSnapshot.currentPageRange
                EditorStatisticsScope.Selection -> contentSnapshot.selectionRange
                    ?: return@withLock fail(AppStrings.ui_please_select_the_content_to_be_counted)

                EditorStatisticsScope.WholeFile -> EditorSearchRange(0L, contentSnapshot.source.size)
            }
            taskCoordinator.cancel(FileEditorTaskKind.Analysis)
            analysisGeneration += 1L
            val generation = analysisGeneration
            val analyzer = EditorFileStatisticsAnalyzer(
                source = contentSnapshot.source,
                codec = codec,
                encoding = _state.value.encoding,
                hasBom = _state.value.hasEncodingBom,
            )
            _state.value = _state.value.copy(
                isAnalyzing = true,
                analysisProgress = 0f,
                statistics = null,
                keywordStatistics = null,
                statisticsScope = request.scope,
                analysisCompleted = false,
                analysisError = null,
            )
            taskCoordinator.launch(FileEditorTaskKind.Analysis) {
                val analysis = analyzer.analyzeWithKeywords(queries, range) { completed, total ->
                    if (generation == analysisGeneration) {
                        val ratio = if (total <= 0L) 1f else completed.toFloat() / total.toFloat()
                        _state.value = _state.value.copy(
                            analysisProgress = ratio.coerceIn(0f, 1f),
                        )
                    }
                }
                val result = analysis.getOrElse { error ->
                    if (generation == analysisGeneration) {
                        _state.value = _state.value.copy(
                            isAnalyzing = false,
                            analysisError = error.message ?: AppStrings.ui_statistics_failed,
                        )
                    }
                    return@launch
                }
                if (generation != analysisGeneration) return@launch
                _state.value = _state.value.copy(
                    isAnalyzing = false,
                    analysisProgress = 1f,
                    statistics = result.statistics,
                    keywordStatistics = result.keywordStatistics,
                    analysisCompleted = true,
                )
            }
            Result.success(Unit)
        }

    suspend fun cancelStatistics(): Result<Unit> {
        analysisGeneration += 1L
        taskCoordinator.cancel(FileEditorTaskKind.Analysis)
        _state.value = _state.value.copy(
            isAnalyzing = false,
            analysisCompleted = false,
            analysisError = null,
        )
        return Result.success(Unit)
    }

    suspend fun goToLine(lineNumber: Long): Result<Unit> = operationMutex.withLock {
        if (lineNumber < 1L) return@withLock fail(AppStrings.ui_line_number_must_be_greater_than_or_equal_to_1)
        val current = _state.value
        _state.value = current.copy(isLineIndexing = true, lineIndexProgress = 0f, error = null)
        val indexer = EditorSparseLineIndexer(
            source = source,
            encoding = current.encoding,
            hasBom = current.hasEncodingBom,
        )
        val background = backgroundLineIndexer
        val indexResult = if (background != null) {
            background.await()
        } else {
            withContext(Dispatchers.Default) {
                indexer.build { completed, total ->
                    _state.value = _state.value.copy(
                        lineIndexProgress = if (total <= 0L) 1f else
                            (completed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat(),
                    )
                }
            }
        }
        val index = indexResult.getOrElse { error ->
            _state.value = _state.value.copy(isLineIndexing = false, error = error.message)
            return@withLock Result.failure(error)
        }
        val offset = withContext(Dispatchers.Default) {
            indexer.lineToByte(index, lineNumber)
        }.getOrElse { error ->
            _state.value = _state.value.copy(isLineIndexing = false, error = error.message)
            return@withLock Result.failure(error)
        }
        val page = if (currentSize == 0L) 0L else offset / pageSize.toLong()
        loadPageLocked(page).map {
            _state.value = _state.value.copy(
                selectionStartOffset = offset,
                selectionEndOffsetExclusive = offset,
                currentLineNumber = lineNumber,
                lineNumberProvisional = false,
                isLineIndexing = false,
                lineIndexProgress = 1f,
                error = null,
            )
        }
    }

    private suspend fun restartBackgroundLineIndexLocked() {
        val cache = lineIndexCache ?: return
        lineIndexGeneration += 1L
        val generation = lineIndexGeneration
        backgroundLineIndexer?.cancel()
        val current = _state.value
        val indexer = EditorBackgroundLineIndexer(
            source = source,
            encoding = current.encoding,
            hasBom = current.hasEncodingBom,
            cache = cache,
            taskCoordinator = taskCoordinator,
            onStateChange = { buildState ->
                if (generation == lineIndexGeneration) {
                    _state.value = _state.value.copy(
                        isLineIndexing = buildState.status == EditorLineIndexBuildStatus.Indexing,
                        lineIndexProgress = buildState.progress,
                    )
                }
            },
        )
        backgroundLineIndexer = indexer
        indexer.start()
    }

    fun updateText(value: String): Result<Unit> {
        val current = _state.value
        if (!current.canWrite) return fail(AppStrings.ui_the_file_only_supports_reading)
        if (current.isLoading || current.isSaving) return fail(AppStrings.ui_the_editor_is_busy)
        if (!initialized) return fail(AppStrings.ui_file_not_initialized)
        if (currentTextUsesFollowingPageBytes) {
            return fail(AppStrings.ui_the_current_page_boundary_contains_cross_page_characters_and_direct)
        }

        val codec = textCodec ?: return fail(AppStrings.ui_text_decoder_not_initialized)
        val encoded = codec.encode(value, current.encoding)
        val editedBytes = encoded.bytes
        val maxEditedPageBytes = pageSize.toLong() * MAX_TEXT_PAGE_SIZE_MULTIPLIER
        if (editedBytes.size.toLong() > maxEditedPageBytes) {
            return fail(AppStrings.ui_text_editing_content_cannot_exceed_arg0_bytes.format(arg0 = (maxEditedPageBytes).toString()))
        }
        val replacement = currentTextPrefix + editedBytes + currentTextSuffix
        return applyCurrentPageEdit(
            kind = EditorModificationKind.TextReplacement,
            replacement = replacement,
            displayText = value,
            encodingReplacementCount = encoded.replacementCount,
            encodingReplacementSamples = encoded.replacementCharacterIndices.take(16).map { index ->
                EditorEncodingReplacementSample(
                    pageIndex = current.currentPage,
                    characterIndex = index,
                    character = value.getOrElse(index) { '\uFFFD' },
                )
            },
        )
    }

    suspend fun undo(): Result<Unit> = operationMutex.withLock {
        val current = _state.value
        if (current.isLoading || current.isSaving) return@withLock fail(AppStrings.ui_the_editor_is_busy)
        val command = editCommandLog.undo() ?: return@withLock fail(AppStrings.ui_no_reversible_changes)
        applyHistoryTransactionLocked(command, useAfter = false)
    }

    suspend fun redo(): Result<Unit> = operationMutex.withLock {
        val current = _state.value
        if (current.isLoading || current.isSaving) return@withLock fail(AppStrings.ui_the_editor_is_busy)
        val command = editCommandLog.redo() ?: return@withLock fail(AppStrings.ui_no_changes_can_be_made)
        applyHistoryTransactionLocked(command, useAfter = true)
    }

    suspend fun buildSavePreview(backupEnabled: Boolean = true): Result<EditorSavePreview> =
        operationMutex.withLock {
            val current = _state.value
            if (!current.canWrite) return@withLock failValue(AppStrings.ui_the_file_only_supports_reading)
            if (!current.dirty) return@withLock failValue(AppStrings.ui_there_currently_no_pending_changes_save)
            if (current.isLoading || current.isSaving) return@withLock failValue(AppStrings.ui_the_editor_is_busy)

            val replacementSnapshot = snapshotReplacements()
            val baseNewSize = calculateBaseNewSize(currentSize, replacementSnapshot)
            val codec = textCodec ?: return@withLock failValue(AppStrings.ui_text_decoder_not_initialized)
            val newSize = calculateOutputSize(
                baseContent = createBaseContentFlow(replacementSnapshot, currentSize),
                baseSize = baseNewSize,
                codec = codec,
                encoding = current.encoding,
                conversion = current.newlineConversion,
            )
            val backupMode = when {
                !backupEnabled -> EditorBackupMode.Disabled
                current.isOversized -> EditorBackupMode.ModifiedRanges
                else -> EditorBackupMode.CompleteFile
            }
            val estimatedBackupBytes = when (backupMode) {
                EditorBackupMode.CompleteFile -> sessionSnapshot?.size ?: currentSize
                EditorBackupMode.ModifiedRanges ->
                    current.modifications.sumOf { it.originalByteCount.toLong() }

                EditorBackupMode.Disabled -> 0L
            }
            Result.success(
                EditorSavePreview(
                    changedRanges = current.modifications,
                    originalSize = sessionSnapshot?.size ?: currentSize,
                    newSize = newSize,
                    encoding = current.encoding,
                    newlineConversion = current.newlineConversion,
                    backupMode = backupMode,
                    estimatedBackupBytes = estimatedBackupBytes,
                    availableBackupBytes = backupStore?.availableBytes(),
                    replacementCharacterCount =
                        replacementSnapshot.values.sumOf(PageReplacement::encodingReplacementCount),
                    replacementCharacterSamples = replacementSnapshot.values
                        .flatMap(PageReplacement::encodingReplacementSamples)
                        .take(16),
                )
            )
        }

    suspend fun save(
        forceOverwriteConfirmed: Boolean = false,
        backupEnabled: Boolean = true,
    ): Result<Unit> = operationMutex.withLock {
        val beforeSave = _state.value
        if (!beforeSave.canWrite) return@withLock fail(AppStrings.ui_the_file_only_supports_reading)
        if (!beforeSave.dirty) return@withLock Result.success(Unit)
        if (beforeSave.recoveryReviewOnly) {
            return@withLock fail(AppStrings.ui_the_file_has_changed_and_the_content_can_only_be_reviewed_or_saved)
        }

        val expectedSnapshot = sessionSnapshot
            ?: return@withLock fail(AppStrings.ui_file_session_missing_source_version_information_please_reload)
        val actualSnapshot = source.currentSnapshot().getOrElse { error ->
            return@withLock fail(error.message?.ifBlank { null } ?: AppStrings.ui_save_before_verifying_file_version)
        }
        if (actualSnapshot != expectedSnapshot && !forceOverwriteConfirmed) {
            val conflict = EditorSaveConflict(expectedSnapshot, actualSnapshot)
            val error = FileEditorSourceChangedException(expectedSnapshot, actualSnapshot)
            _state.value = beforeSave.copy(saveConflict = conflict, error = error.message)
            return@withLock Result.failure(error)
        }
        val writeSnapshot = actualSnapshot

        _state.value = beforeSave.copy(
            isSaving = true,
            progress = 0f,
            saveConflict = null,
            error = null,
        )

        val replacementSnapshot = snapshotReplacements()
        val oldSize = currentSize
        val baseNewSize = calculateBaseNewSize(oldSize, replacementSnapshot)
        val baseContent = createBaseContentFlow(replacementSnapshot, oldSize)
        val conversion = beforeSave.newlineConversion
        val codec = textCodec ?: return@withLock fail(AppStrings.ui_text_decoder_not_initialized)
        val newSize = calculateOutputSize(
            baseContent = baseContent,
            baseSize = baseNewSize,
            codec = codec,
            encoding = beforeSave.encoding,
            conversion = conversion,
        )
        val content = createOutputContentFlow(
            baseContent = baseContent,
            codec = codec,
            encoding = beforeSave.encoding,
            conversion = conversion,
        )

        val backupResult = createBackupBeforeSave(
            enabled = backupEnabled,
            snapshot = writeSnapshot,
            oldSize = writeSnapshot.size,
            replacementSnapshot = replacementSnapshot,
            oversized = beforeSave.isOversized,
        )
        if (backupResult.isFailure) {
            val error = backupResult.exceptionOrNull() ?: IllegalStateException(AppStrings.ui_create_backup_failed)
            _state.value = _state.value.copy(isSaving = false, error = error.message ?: AppStrings.ui_create_backup_failed)
            return@withLock Result.failure(error)
        }

        val canUseRangeWrite =
            conversion == null &&
                    replacementSnapshot.isNotEmpty() &&
                    replacementSnapshot.values.all { it.originalByteCount == it.bytes.size } &&
                    source.capabilities.supportsRangeWrite
        val result = withContext(Dispatchers.Default) {
            if (canUseRangeWrite) {
                saveEqualLengthRanges(
                    replacementSnapshot = replacementSnapshot,
                    expectedSnapshot = writeSnapshot,
                )
            } else {
                runCatching {
                    if (!source.capabilities.supportsStreamedReplace) {
                        throw FileEditorUnsupportedOperationException(AppStrings.ui_replace_with_save_as)
                    }
                    source.replaceContent(
                        newSize = newSize,
                        content = content,
                        expectedSnapshot = writeSnapshot,
                        onProgress = { completedBytes, totalBytes ->
                            updateSaveProgress(completedBytes, totalBytes, newSize)
                        },
                    ).getOrThrow()
                    val savedSnapshot = source.currentSnapshot().getOrThrow()
                    check(savedSnapshot.size == newSize) { AppStrings.ui_file_size_validation_failed_after_saving }
                }
            }
        }

        result.fold(
            onSuccess = {
                currentSize = newSize
                sessionSnapshot = source.currentSnapshot().getOrNull()
                    ?: FileEditorSourceSnapshot(size = newSize)
                replacements.clear()
                editCommandLog.clear()
                clearRecoveryJournalLocked()
                pagingService.clear()
                currentTextPrefix = byteArrayOf()
                currentTextSuffix = byteArrayOf()
                currentTextUsesFollowingPageBytes = false
                val targetPage = beforeSave.currentPage.coerceAtMost(pageCount(currentSize) - 1L)
                _state.value = _state.value.copy(
                    isSaving = false,
                    fileSize = currentSize,
                    dirty = false,
                    modifications = emptyList(),
                    canUndo = false,
                    canRedo = false,
                    editHistoryCommandCount = 0,
                    editHistoryStoredBytes = 0L,
                    pendingEditStoredBytes = 0L,
                    progress = 1f,
                    pageCount = pageCount(currentSize),
                    newlineKind = conversion ?: beforeSave.newlineKind,
                    newlineConversion = null,
                    error = null,
                )
                val loaded = loadPageLocked(targetPage).map {
                    if (conversion != null) {
                        _state.value = _state.value.copy(newlineKind = conversion)
                    }
                }
                if (loaded.isSuccess) backupStore?.cleanup()
                loaded
            },
            onFailure = { error ->
                _state.value = _state.value.copy(
                    isSaving = false,
                    error = error.message?.ifBlank { null } ?: AppStrings.ui_save_file_failed,
                )
                Result.failure(error)
            },
        )
    }

    suspend fun saveAs(
        destination: FileEditorContentSource,
        expectedDestinationSnapshot: FileEditorSourceSnapshot? = null,
        sourceChangeConfirmed: Boolean = false,
    ): Result<Unit> = operationMutex.withLock {
        val current = _state.value
        if (!current.dirty) return@withLock fail(AppStrings.ui_there_currently_no_pending_changes_save)
        if (!source.capabilities.supportsSaveAs) return@withLock fail(AppStrings.ui_current_content_source_does_not_support_saving)
        if (!destination.canWrite || !destination.capabilities.supportsStreamedReplace) {
            return@withLock fail(AppStrings.ui_the_target_content_source_does_not_support_streaming_write_operations)
        }
        val expectedSource = sessionSnapshot
            ?: return@withLock fail(AppStrings.ui_file_session_missing_source_version_information_please_reload)
        val actualSource = source.currentSnapshot().getOrElse { error ->
            return@withLock fail(error.message?.ifBlank { null } ?: AppStrings.ui_save_as_before_but_the_source_file_cannot_be_verified)
        }
        if (actualSource != expectedSource && !sourceChangeConfirmed) {
            val conflict = EditorSaveConflict(expectedSource, actualSource)
            val error = FileEditorSourceChangedException(expectedSource, actualSource)
            _state.value = current.copy(saveConflict = conflict, error = error.message)
            return@withLock Result.failure(error)
        }

        _state.value = current.copy(isSaving = true, progress = 0f, error = null)
        val replacementSnapshot = snapshotReplacements()
        val baseSize = calculateBaseNewSize(currentSize, replacementSnapshot)
        val codec = textCodec ?: return@withLock fail(AppStrings.ui_text_decoder_not_initialized)
        val baseContent = createBaseContentFlow(replacementSnapshot, currentSize)
        val newSize = calculateOutputSize(
            baseContent = baseContent,
            baseSize = baseSize,
            codec = codec,
            encoding = current.encoding,
            conversion = current.newlineConversion,
        )
        val output = createOutputContentFlow(
            baseContent = baseContent,
            codec = codec,
            encoding = current.encoding,
            conversion = current.newlineConversion,
        )
        val validatedOutput = flow {
            output.collect(::emit)
            val latest = source.currentSnapshot().getOrThrow()
            if (latest != actualSource) throw FileEditorSourceChangedException(actualSource, latest)
        }
        val result = withContext(Dispatchers.Default) {
            runCatching {
                source.saveAs(
                    destination = destination,
                    newSize = newSize,
                    content = validatedOutput,
                    expectedDestinationSnapshot = expectedDestinationSnapshot,
                    onProgress = { completed, total -> updateSaveProgress(completed, total, newSize) },
                ).getOrThrow()
                val saved = destination.currentSnapshot().getOrThrow()
                check(saved.size == newSize) { AppStrings.ui_target_size_validation_failed }
            }
        }
        result.fold(
            onSuccess = {
                _state.value = _state.value.copy(isSaving = false, progress = 1f, error = null)
                Result.success(Unit)
            },
            onFailure = { error ->
                _state.value = _state.value.copy(
                    isSaving = false,
                    error = error.message?.ifBlank { null } ?: AppStrings.ui_save_as_failed,
                )
                Result.failure(error)
            },
        )
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun dismissSaveConflict() {
        _state.value = _state.value.copy(saveConflict = null, error = null)
    }

    suspend fun restoreRecovery(allowChangedSourceForReview: Boolean = false): Result<Unit> =
        operationMutex.withLock {
            val journal = pendingRecoveryJournal ?: return@withLock fail(AppStrings.ui_no_recoverable_edit_records)
            if (journal.pageSize != pageSize) return@withLock fail(AppStrings.ui_revert_log_page_size_incompatibility)
            val currentSnapshot = source.currentSnapshot().getOrElse { error ->
                return@withLock fail(error.message?.ifBlank { null } ?: AppStrings.ui_the_log_file_cannot_be_verified_corresponding_to_the_source_file)
            }
            val journalSnapshot = FileEditorSourceSnapshot(
                size = journal.sourceSize,
                updatedAt = journal.sourceUpdatedAt,
                revision = journal.sourceRevision,
            )
            val sourceChanged = currentSnapshot != journalSnapshot
            if (sourceChanged && !allowChangedSourceForReview) {
                _state.value = _state.value.copy(
                    recoverySourceChanged = true,
                    error = AppStrings.ui_the_file_has_changed_so_only_restore_for_review_or_save_as,
                )
                return@withLock Result.failure(IllegalStateException(AppStrings.ui_the_file_has_changed_so_only_restore_for_review_or_save_as))
            }

            replacements.clear()
            editCommandLog.clear()
            currentSize = journal.sourceSize
            journal.pages.forEach { page ->
                val kind = EditorModificationKind.entries.firstOrNull {
                    it.name == page.modificationKind
                } ?: return@withLock fail(AppStrings.ui_restore_logs_include_the_removed_editing_types)
                replacements[page.pageIndex] = PageReplacement(
                    originalBytes = page.originalBytes.copyOf(),
                    bytes = page.replacementBytes.copyOf(),
                    kind = kind,
                    encodingReplacementCount = page.encodingReplacementCount,
                    encodingReplacementSamples = page.encodingReplacementSamples.map { sample ->
                        EditorEncodingReplacementSample(
                            pageIndex = page.pageIndex,
                            characterIndex = sample.characterIndex,
                            character = sample.characterCode.toChar(),
                        )
                    },
                )
            }
            val encoding = EditorTextEncoding.entries.firstOrNull { it.name == journal.encoding }
                ?: _state.value.encoding
            val newline = journal.newlineConversion?.let { name ->
                EditorNewlineKind.entries.firstOrNull { it.name == name }
            }
            encodingExplicitlySelected = true
            sessionSnapshot = if (sourceChanged) journalSnapshot else currentSnapshot
            pendingRecoveryJournal = null
            pagingService.clear()
            _state.value = _state.value.copy(
                encoding = encoding,
                fileSize = currentSize,
                pageCount = pageCount(currentSize),
                newlineConversion = newline,
                recoveryAvailable = false,
                recoverySourceChanged = sourceChanged,
                recoveryReviewOnly = sourceChanged,
                saveConflict = null,
                error = null,
            )
            val targetPage = journal.currentPage.coerceIn(0L, pageCount(currentSize) - 1L)
            loadPageLocked(targetPage).map {
                _state.value = withEditState(_state.value)
            }
        }

    suspend fun discardRecovery(): Result<Unit> = operationMutex.withLock {
        val store = recoveryJournalStore
        val key = editorSessionKey
        if (store != null && key != null) store.remove(key).getOrElse { return@withLock Result.failure(it) }
        pendingRecoveryJournal = null
        _state.value = _state.value.copy(
            recoveryAvailable = false,
            recoverySourceChanged = false,
            recoveryReviewOnly = false,
            error = null,
        )
        Result.success(Unit)
    }

    suspend fun reload(discardChangesConfirmed: Boolean = false): Result<Unit> =
        operationMutex.withLock {
            val beforeReload = _state.value
            if (beforeReload.dirty && !discardChangesConfirmed) {
                return@withLock fail(AppStrings.ui_reload_will_discard_any_pending_modifications_please_confirm_before_reloading)
            }
            if (beforeReload.isLoading || beforeReload.isSaving) {
                return@withLock fail(AppStrings.ui_the_editor_is_busy)
            }
            val snapshot = source.currentSnapshot().getOrElse { error ->
                return@withLock fail(error.message?.ifBlank { null } ?: AppStrings.ui_the_latest_file_version_cannot_be_read)
            }

            taskCoordinator.cancel(FileEditorTaskKind.Search)
            taskCoordinator.cancel(FileEditorTaskKind.LineIndex)
            taskCoordinator.cancel(FileEditorTaskKind.Analysis)
            backgroundLineIndexer?.cancel()
            replacements.clear()
            editCommandLog.clear()
            pagingService.clear()
            currentSize = snapshot.size
            sessionSnapshot = snapshot
            currentTextPrefix = byteArrayOf()
            currentTextSuffix = byteArrayOf()
            currentTextUsesFollowingPageBytes = false
            clearRecoveryJournalLocked()
            _state.value = beforeReload.copy(
                fileSize = currentSize,
                pageCount = pageCount(currentSize),
                isOversized = currentSize >= FILE_EDITOR_OVERSIZED_THRESHOLD_BYTES,
                newlineConversion = null,
                searchResults = emptyList(),
                searchTotalCount = 0L,
                searchCompleted = false,
                isSearching = false,
                isAnalyzing = false,
                statistics = null,
                keywordStatistics = null,
                saveConflict = null,
                error = null,
            )
            val targetPage = beforeReload.currentPage.coerceAtMost(pageCount(currentSize) - 1L)
            loadPageLocked(targetPage).onSuccess {
                _state.value = withEditState(_state.value)
                restartBackgroundLineIndexLocked()
            }
        }

    suspend fun close() {
        operationMutex.withLock {
            recoveryWriteJob?.cancel()
            createRecoveryJournal()?.let { journal ->
                val store = recoveryJournalStore
                val key = editorSessionKey
                if (store != null && key != null) store.write(key, journal)
            }
            recoveryScope.cancel()
            taskCoordinator.close()
            pagingService.clear()
            replacements.clear()
            editCommandLog.clear()
            currentOriginalPage = byteArrayOf()
            currentTextPrefix = byteArrayOf()
            currentTextSuffix = byteArrayOf()
            currentTextUsesFollowingPageBytes = false
            source.close()
        }
    }

    private suspend fun loadPageLocked(index: Long): Result<Unit> {
        val previous = _state.value
        val count = pageCount(currentSize)
        val normalizedIndex = index.coerceIn(0L, count - 1L)
        val (start, end) = pageRange(normalizedIndex, currentSize)
        _state.value = previous.copy(
            currentPage = normalizedIndex,
            pageCount = count,
            pageStartOffset = start,
            pageEndOffsetExclusive = end,
            isLoading = true,
            error = null,
        )

        val originalResult = pagingService.readPage(normalizedIndex, currentSize).map { page -> page.bytes }

        return originalResult.fold(
            onSuccess = { loaded ->
                if (loaded.size.toLong() != end - start) {
                    val error = IllegalStateException(AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range)
                    _state.value = _state.value.copy(isLoading = false, error = error.message)
                    return@fold Result.failure(error)
                }
                val original = loaded.copyOf()
                currentOriginalPage = original.copyOf()
                val bytes = replacements[normalizedIndex]?.bytes?.copyOf() ?: original.copyOf()
                val codec = textCodec ?: error(AppStrings.ui_text_decoder_not_initialized)
                val detection = if (!encodingExplicitlySelected && normalizedIndex == 0L) {
                    codec.detect(bytes)
                } else {
                    EditorEncodingDetection(
                        encoding = previous.encoding,
                        confidence = previous.encodingConfidence,
                        hasBom = previous.hasEncodingBom,
                    )
                }
                val textPage = decodePageWithLookahead(
                    codec = codec,
                    encoding = detection.encoding,
                    state = _state.value.copy(
                        bytes = bytes,
                        pageStartOffset = start,
                        pageEndOffsetExclusive = end,
                        hasEncodingBom = detection.hasBom,
                    ),
                )
                val newlineDetector = EditorNewlineDetector().apply { accept(textPage.text) }
                val newlineKind = newlineDetector.finish().kind
                currentTextPrefix = textPage.prefix
                currentTextSuffix = textPage.suffix
                currentTextUsesFollowingPageBytes = textPage.usesFollowingPageBytes
                _state.value = withEditState(
                    _state.value.copy(
                        text = textPage.text,
                        bytes = bytes,
                        pageStartOffset = start,
                        pageEndOffsetExclusive = end,
                        isLoading = false,
                        dirty = replacements.isNotEmpty() || previous.newlineConversion != null,
                        error = null,
                        supportsTextMode = textPage.isText,
                        encoding = detection.encoding,
                        encodingConfidence = detection.confidence,
                        hasEncodingBom = detection.hasBom,
                        newlineKind = newlineKind,
                        selectionStartOffset = start,
                        selectionEndOffsetExclusive = start,
                    )
                )
                pagingService.prefetchAround(normalizedIndex, currentSize)
                Result.success(Unit)
            },
            onFailure = { error ->
                _state.value = _state.value.copy(
                    text = "",
                    bytes = byteArrayOf(),
                    isLoading = false,
                    error = error.message?.ifBlank { null } ?: AppStrings.file_read_failed,
                )
                Result.failure(error)
            },
        )
    }

    private suspend fun loadPendingRecoveryLocked(currentSnapshot: FileEditorSourceSnapshot) {
        val store = recoveryJournalStore ?: return
        val key = editorSessionKey ?: return
        store.read(key).fold(
            onSuccess = { journal ->
                pendingRecoveryJournal = journal
                val expected = journal?.let {
                    FileEditorSourceSnapshot(it.sourceSize, it.sourceUpdatedAt, it.sourceRevision)
                }
                _state.value = _state.value.copy(
                    recoveryAvailable = journal != null,
                    recoverySourceChanged = expected != null && expected != currentSnapshot,
                )
            },
            onFailure = { error ->
                _state.value = _state.value.copy(
                    error = error.message?.ifBlank { null } ?: AppStrings.ui_log_file_recovery_failed,
                )
            },
        )
    }

    private suspend fun createRecoveryJournal(): EditorRecoveryJournal? {
        val snapshot = sessionSnapshot ?: return null
        val current = _state.value
        if (!current.dirty) return null
        return EditorRecoveryJournal(
            sourceSize = snapshot.size,
            sourceUpdatedAt = snapshot.updatedAt,
            sourceRevision = snapshot.revision,
            pageSize = pageSize,
            encoding = current.encoding.name,
            newlineConversion = current.newlineConversion?.name,
            currentPage = current.currentPage,
            pages = snapshotReplacements().map { (pageIndex, replacement) ->
                EditorRecoveryPage(
                    pageIndex = pageIndex,
                    originalBytes = replacement.originalBytes,
                    replacementBytes = replacement.bytes,
                    modificationKind = replacement.kind.name,
                    encodingReplacementCount = replacement.encodingReplacementCount,
                    encodingReplacementSamples = replacement.encodingReplacementSamples.map { sample ->
                        EditorRecoveryReplacementSample(
                            characterIndex = sample.characterIndex,
                            characterCode = sample.character.code,
                        )
                    },
                )
            }.sortedBy(EditorRecoveryPage::pageIndex),
        )
    }

    private fun scheduleRecoveryJournalWrite() {
        val store = recoveryJournalStore ?: return
        val key = editorSessionKey ?: return
        recoveryWriteJob?.cancel()
        recoveryWriteJob = recoveryScope.launch {
            delay(200L)
            val journal = createRecoveryJournal()
            if (journal == null) {
                store.remove(key)
            } else {
                store.write(key, journal)
                store.cleanup()
            }
        }
    }

    private suspend fun clearRecoveryJournalLocked() {
        recoveryWriteJob?.cancel()
        recoveryWriteJob = null
        pendingRecoveryJournal = null
        _state.value = _state.value.copy(
            recoveryAvailable = false,
            recoverySourceChanged = false,
            recoveryReviewOnly = false,
        )
        val store = recoveryJournalStore ?: return
        val key = editorSessionKey ?: return
        store.remove(key)
    }

    private fun snapshotReplacements(): Map<Long, PageReplacement> =
        replacements.mapValues { (_, replacement) ->
            replacement.copy(
                originalBytes = replacement.originalBytes.copyOf(),
                bytes = replacement.bytes.copyOf(),
                encodingReplacementSamples = replacement.encodingReplacementSamples.toList(),
            )
        }

    private fun validateReplaceLocked(request: EditorSearchRequest): Result<Unit> {
        val current = _state.value
        if (!initialized) return fail(AppStrings.ui_file_not_initialized)
        if (!current.canWrite) return fail(AppStrings.ui_the_file_only_supports_reading)
        if (current.isLoading || current.isSaving || current.isSearching || current.isAnalyzing) {
            return fail(AppStrings.ui_the_editor_is_busy)
        }
        if (request.range.endOffsetExclusive > currentSize) return fail(AppStrings.ui_replace_the_range_that_exceeds_the_file)
        return Result.success(Unit)
    }

    private suspend fun applyReplacementResultsLocked(
        request: EditorReplaceRequest,
        results: List<EditorSearchResult>,
        skippedOverlapping: Int,
    ): Result<EditorReplaceSummary> {
        if (results.isEmpty()) {
            return Result.success(EditorReplaceSummary(0, skippedOverlapping))
        }
        val codec = textCodec ?: return failValue(AppStrings.ui_text_decoder_not_initialized)
        val originalPages = mutableMapOf<Long, ByteArray>()
        val workingPages = mutableMapOf<Long, ByteArray>()

        suspend fun originalPage(pageIndex: Long): ByteArray {
            originalPages[pageIndex]?.let { return it }
            val (start, end) = pageRange(pageIndex, currentSize)
            return source.readRange(start, end).getOrElse { throw it }.also { bytes ->
                check(bytes.size.toLong() == end - start) { AppStrings.ui_replace_the_length_mismatch }
                originalPages[pageIndex] = bytes.copyOf()
            }
        }

        try {
            results.sortedByDescending(EditorSearchResult::startOffset).forEach { result ->
                val startPage = if (currentSize == 0L) 0L else result.startOffset / pageSize.toLong()
                val endAnchor = if (result.endOffsetExclusive > result.startOffset) {
                    result.endOffsetExclusive - 1L
                } else {
                    result.startOffset
                }
                val endPage = if (currentSize == 0L) 0L else
                    endAnchor.coerceAtMost((currentSize - 1L).coerceAtLeast(0L)) / pageSize.toLong()
                val pageIndices = (startPage..endPage).toList()
                pageIndices.forEach { pageIndex ->
                    if (pageIndex !in workingPages) {
                        workingPages[pageIndex] = replacements[pageIndex]?.bytes?.copyOf()
                            ?: originalPage(pageIndex).copyOf()
                    }
                }
                val combined = pageIndices.fold(byteArrayOf()) { bytes, pageIndex ->
                    bytes + workingPages.getValue(pageIndex)
                }
                val combinedStartOffset = startPage * pageSize.toLong()
                val relativeStart = (result.startOffset - combinedStartOffset).toInt()
                val relativeEnd = (result.endOffsetExclusive - combinedStartOffset).toInt()
                if (relativeStart !in 0..combined.size || relativeEnd !in relativeStart..combined.size) {
                    return failValue(AppStrings.ui_search_results_do_not_match_the_current_editing_content_please_re_search)
                }
                val matchedBytes = combined.copyOfRange(relativeStart, relativeEnd)
                val replacementBytes = replacementBytes(
                    request = request,
                    matchedBytes = matchedBytes,
                    codec = codec,
                ).getOrElse { error -> return failValue(error.message ?: AppStrings.ui_invalid_replacement_content) }
                val updated = combined.copyOfRange(0, relativeStart) + replacementBytes +
                    combined.copyOfRange(relativeEnd, combined.size)
                var cursor = 0
                pageIndices.forEachIndexed { index, pageIndex ->
                    val pageBytes = if (index == pageIndices.lastIndex) {
                        updated.copyOfRange(cursor.coerceAtMost(updated.size), updated.size)
                    } else {
                        val originalLength = originalPage(pageIndex).size
                        val end = (cursor + originalLength).coerceAtMost(updated.size)
                        updated.copyOfRange(cursor.coerceAtMost(end), end).also { cursor = end }
                    }
                    workingPages[pageIndex] = pageBytes
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error.message == "PAGE_NOT_LOADED") {
                return failValue(AppStrings.ui_unable_to_read_and_replace_page)
            }
            return failValue(error.message ?: AppStrings.ui_replacement_failed)
        }

        val kind = EditorModificationKind.TextReplacement
        val commands = workingPages.entries.sortedBy { it.key }.mapNotNull { entry ->
            val original = originalPages.getValue(entry.key)
            val beforeReplacement = replacements[entry.key]
            val before = beforeReplacement?.bytes ?: original
            if (before.contentEquals(entry.value)) {
                null
            } else {
                EditorEditCommand(
                    pageIndex = entry.key,
                    pageStartOffset = entry.key * pageSize.toLong(),
                    kind = kind,
                    originalBytes = original,
                    beforeBytes = before,
                    afterBytes = entry.value,
                    beforeEncodingReplacementCount = beforeReplacement?.encodingReplacementCount ?: 0,
                    beforeEncodingReplacementSamples = beforeReplacement?.encodingReplacementSamples.orEmpty(),
                )
            }
        }
        if (commands.isEmpty()) {
            return Result.success(EditorReplaceSummary(0, skippedOverlapping))
        }
        if (kind == EditorModificationKind.TextReplacement) {
            val maxEditedPageBytes = pageSize.toLong() * MAX_TEXT_PAGE_SIZE_MULTIPLIER
            if (commands.any { it.afterBytes.size.toLong() > maxEditedPageBytes }) {
                return failValue(AppStrings.ui_replace_the_single_page_text_with_a_range_that_exceeds_the_safe_editing)
            }
        }
        if (!editCommandLog.canRecord(commands)) {
            return failValue(AppStrings.ui_replace_range_exceeds_the_limit_for_undo_history_storage_please_reduce_the)
        }
        val stagedStoredBytes = replacements.values.sumOf(PageReplacement::storedBytes) -
            commands.sumOf { command -> replacements[command.pageIndex]?.storedBytes ?: 0L } +
            commands.sumOf { command -> command.originalBytes.size.toLong() + command.afterBytes.size.toLong() }
        if (stagedStoredBytes > maxPendingEditBytes) {
            return failValue(AppStrings.ui_the_session_limit_exceeds_arg0_mib.format(arg0 = (maxPendingEditBytes / (1024L * 1024L)).toString()))
        }

        editCommandLog.record(commands)
        commands.forEach { command ->
            if (command.afterBytes.contentEquals(command.originalBytes)) {
                replacements.remove(command.pageIndex)
            } else {
                replacements[command.pageIndex] = PageReplacement(
                    originalBytes = command.originalBytes.copyOf(),
                    bytes = command.afterBytes.copyOf(),
                    kind = command.kind,
                )
            }
        }
        val firstResult = results.minBy(EditorSearchResult::startOffset)
        val targetPage = firstResult.startOffset / pageSize.toLong()
        loadPageLocked(targetPage).getOrElse { return Result.failure(it) }
        _state.value = withEditState(
            _state.value.copy(
                selectionStartOffset = firstResult.startOffset,
                selectionEndOffsetExclusive = firstResult.startOffset,
                error = null,
            )
        )
        markContentChanged()
        scheduleRecoveryJournalWrite()
        return Result.success(EditorReplaceSummary(results.size, skippedOverlapping))
    }

    private fun replacementBytes(
        request: EditorReplaceRequest,
        matchedBytes: ByteArray,
        codec: EditorTextCodec,
    ): Result<ByteArray> = runCatching {
        when (request.search.mode) {
            EditorSearchMode.Text -> {
                val matchedText = codec.decode(
                    matchedBytes,
                    request.search.encoding,
                    stripBom = false,
                ).text
                check(
                    request.search.queries.any { query ->
                        matchedText.equals(query, ignoreCase = !request.search.caseSensitive)
                    }
                ) { AppStrings.ui_search_results_are_expired_please_re_search }
                codec.encode(
                    request.replacement,
                    request.search.encoding,
                ).bytes
            }

            EditorSearchMode.Regex -> {
                val matchedText = codec.decode(
                    matchedBytes,
                    request.search.encoding,
                    stripBom = false,
                ).text
                val options = if (request.search.caseSensitive) {
                    emptySet()
                } else {
                    setOf(RegexOption.IGNORE_CASE)
                }
                val regex = Regex(request.search.queries.single(), options)
                check(regex.containsMatchIn(matchedText)) { AppStrings.ui_regular_search_results_have_expired_please_re_search }
                val replaced = regex.replaceFirst(matchedText, request.replacement)
                codec.encode(replaced, request.search.encoding).bytes
            }
        }
    }

    private data class CurrentContentSnapshot(
        val source: FileEditorContentSource,
        val currentPageRange: EditorSearchRange,
        val selectionRange: EditorSearchRange?,
        val documentOffsetToLogical: (Long) -> Long,
        val logicalOffsetToDocument: (Long) -> Long,
    )

    private fun EditorSearchRequest.forCurrentContent(
        snapshot: CurrentContentSnapshot,
    ): EditorSearchRequest {
        val current = _state.value
        val logicalRange = when {
            this.range.startOffset == 0L && this.range.endOffsetExclusive == currentSize ->
                EditorSearchRange(0L, snapshot.source.size)

            this.range.startOffset == current.pageStartOffset &&
                this.range.endOffsetExclusive == current.pageEndOffsetExclusive -> snapshot.currentPageRange

            current.selectionStartOffset == this.range.startOffset &&
                current.selectionEndOffsetExclusive == this.range.endOffsetExclusive ->
                snapshot.selectionRange ?: EditorSearchRange(
                    snapshot.documentOffsetToLogical(this.range.startOffset),
                    snapshot.documentOffsetToLogical(this.range.endOffsetExclusive),
                )

            else -> EditorSearchRange(
                snapshot.documentOffsetToLogical(this.range.startOffset),
                snapshot.documentOffsetToLogical(this.range.endOffsetExclusive),
            )
        }
        return copy(range = logicalRange)
    }

    private fun currentContentSnapshot(): CurrentContentSnapshot {
        val current = _state.value
        val revision = "editor-$contentRevision"
        val replacementSnapshot = snapshotReplacements()
        val segments = mutableListOf<EditorContentSnapshotSegment>()
        var sourceCursor = 0L
        replacementSnapshot.entries.sortedBy { it.key }.forEach { entry ->
            val (pageStart, pageEnd) = pageRange(entry.key, currentSize)
            if (pageStart > sourceCursor) {
                segments += EditorContentSnapshotSegment.Source(
                    source = source,
                    sourceOffset = sourceCursor,
                    length = pageStart - sourceCursor,
                )
            }
            entry.value.bytes.takeIf { it.isNotEmpty() }?.let { bytes ->
                segments += EditorContentSnapshotSegment.Bytes(bytes)
            }
            sourceCursor = pageEnd
        }
        if (sourceCursor < currentSize) {
            segments += EditorContentSnapshotSegment.Source(
                source = source,
                sourceOffset = sourceCursor,
                length = currentSize - sourceCursor,
            )
        }
        val snapshotSource = EditorContentSnapshotSource(segments, revision)
        val sortedReplacements = replacementSnapshot.entries.sortedBy { it.key }
        fun documentToLogical(offset: Long): Long {
            val normalized = offset.coerceIn(0L, currentSize)
            if (normalized == currentSize) return snapshotSource.size
            val pageIndex = if (currentSize <= 0L) 0L else
                normalized.coerceAtMost(currentSize - 1L) / pageSize.toLong()
            val deltaBefore = sortedReplacements.sumOf { (replacementPage, replacement) ->
                if (replacementPage < pageIndex) {
                    replacement.bytes.size.toLong() - replacement.originalByteCount.toLong()
                } else {
                    0L
                }
            }
            return (normalized + deltaBefore).coerceIn(0L, snapshotSource.size)
        }
        fun logicalToDocument(offset: Long): Long {
            val normalized = offset.coerceIn(0L, snapshotSource.size)
            var delta = 0L
            sortedReplacements.forEach { (pageIndex, replacement) ->
                val (pageStart, pageEnd) = pageRange(pageIndex, currentSize)
                val logicalStart = pageStart + delta
                val logicalEnd = logicalStart + replacement.bytes.size
                when {
                    normalized < logicalStart -> return (normalized - delta).coerceIn(0L, currentSize)
                    normalized < logicalEnd -> return pageStart + (normalized - logicalStart)
                    normalized == logicalEnd -> return pageEnd
                }
                delta += replacement.bytes.size.toLong() - replacement.originalByteCount.toLong()
            }
            return (normalized - delta).coerceIn(0L, currentSize)
        }
        val pageDeltaBefore = replacementSnapshot.entries.sumOf { (pageIndex, replacement) ->
            if (pageIndex < current.currentPage) {
                replacement.bytes.size.toLong() - replacement.originalByteCount.toLong()
            } else {
                0L
            }
        }
        val currentPageLogicalStart = (current.pageStartOffset + pageDeltaBefore)
            .coerceIn(0L, snapshotSource.size)
        val currentPageLogicalLength = replacementSnapshot[current.currentPage]?.bytes?.size?.toLong()
            ?: (current.pageEndOffsetExclusive - current.pageStartOffset)
        val currentPageLogicalEnd = (currentPageLogicalStart + currentPageLogicalLength)
            .coerceIn(currentPageLogicalStart, snapshotSource.size)
        val selectionRange = current.selectionStartOffset?.let { start ->
            current.selectionEndOffsetExclusive?.takeIf { it > start }?.let { end ->
                val relativeStart = (start - current.pageStartOffset)
                    .coerceIn(0L, currentPageLogicalLength)
                val relativeEnd = (end - current.pageStartOffset)
                    .coerceIn(relativeStart, currentPageLogicalLength)
                EditorSearchRange(
                    currentPageLogicalStart + relativeStart,
                    currentPageLogicalStart + relativeEnd,
                )
            }
        }
        return CurrentContentSnapshot(
            source = snapshotSource,
            currentPageRange = EditorSearchRange(currentPageLogicalStart, currentPageLogicalEnd),
            selectionRange = selectionRange,
            documentOffsetToLogical = ::documentToLogical,
            logicalOffsetToDocument = ::logicalToDocument,
        )
    }

    private fun markContentChanged() {
        contentRevision += 1L
        searchGeneration += 1L
        analysisGeneration += 1L
        _state.value = _state.value.copy(
            isSearching = false,
            searchResults = emptyList(),
            searchTotalCount = 0L,
            searchDetailsTruncated = false,
            searchCompleted = false,
            searchError = null,
            isAnalyzing = false,
            statistics = null,
            keywordStatistics = null,
            statisticsScope = null,
            analysisCompleted = false,
            analysisError = null,
        )
    }

    private fun calculateBaseNewSize(
        oldSize: Long,
        replacementSnapshot: Map<Long, PageReplacement>,
    ): Long = replacementSnapshot.values.fold(oldSize) { size, replacement ->
        size + replacement.bytes.size.toLong() - replacement.originalByteCount.toLong()
    }.coerceAtLeast(0L)

    private data class EqualLengthRangeChange(
        val startOffset: Long,
        val originalBytes: ByteArray,
        val replacementBytes: ByteArray,
    )

    private suspend fun createBackupBeforeSave(
        enabled: Boolean,
        snapshot: FileEditorSourceSnapshot,
        oldSize: Long,
        replacementSnapshot: Map<Long, PageReplacement>,
        oversized: Boolean,
    ): Result<EditorBackupHandle?> {
        val store = backupStore ?: return Result.success(null)
        if (!enabled) return Result.success(null)
        val key = editorSessionKey ?: snapshot.revision ?: "editor-${snapshot.size}"
        val estimatedBytes = if (oversized) {
            modifiedBackupRanges(replacementSnapshot).sumOf { it.originalBytes.size.toLong() }
        } else {
            oldSize
        }
        val available = store.availableBytes()
        if (available != null && available < estimatedBytes) {
            return Result.failure(IllegalStateException(AppStrings.ui_backup_space_is_insufficient_requiring_arg0_bytes_available_arg1_bytes.format(arg0 = (estimatedBytes).toString(), arg1 = (available).toString())))
        }
        return if (oversized) {
            store.createRangeBackup(
                key = key,
                snapshot = snapshot,
                ranges = modifiedBackupRanges(replacementSnapshot),
            ).map { it }
        } else {
            store.createCompleteBackup(
                key = key,
                snapshot = snapshot,
                size = oldSize,
                content = createOriginalSourceContentFlow(oldSize),
            ).map { it }
        }
    }

    private fun modifiedBackupRanges(
        replacementSnapshot: Map<Long, PageReplacement>,
    ): List<EditorBackupRange> = replacementSnapshot.mapNotNull { (pageIndex, replacement) ->
        val original = replacement.originalBytes
        val updated = replacement.bytes
        if (original.contentEquals(updated)) return@mapNotNull null
        var prefix = 0
        val sharedSize = minOf(original.size, updated.size)
        while (prefix < sharedSize && original[prefix] == updated[prefix]) prefix += 1
        var suffix = 0
        while (
            suffix < original.size - prefix &&
            suffix < updated.size - prefix &&
            original[original.lastIndex - suffix] == updated[updated.lastIndex - suffix]
        ) {
            suffix += 1
        }
        val originalEnd = original.size - suffix
        EditorBackupRange(
            startOffset = pageIndex * pageSize.toLong() + prefix,
            originalBytes = original.copyOfRange(prefix, originalEnd),
        )
    }.sortedBy(EditorBackupRange::startOffset)

    private fun equalLengthRangeChanges(
        replacementSnapshot: Map<Long, PageReplacement>,
    ): List<EqualLengthRangeChange> = replacementSnapshot.mapNotNull { (pageIndex, replacement) ->
        val original = replacement.originalBytes
        val updated = replacement.bytes
        if (original.size != updated.size || original.contentEquals(updated)) return@mapNotNull null
        var prefix = 0
        while (prefix < original.size && original[prefix] == updated[prefix]) prefix += 1
        var suffix = 0
        while (
            suffix < original.size - prefix &&
            original[original.lastIndex - suffix] == updated[updated.lastIndex - suffix]
        ) {
            suffix += 1
        }
        val end = original.size - suffix
        EqualLengthRangeChange(
            startOffset = pageIndex * pageSize.toLong() + prefix,
            originalBytes = original.copyOfRange(prefix, end),
            replacementBytes = updated.copyOfRange(prefix, end),
        )
    }.sortedBy(EqualLengthRangeChange::startOffset)

    private suspend fun saveEqualLengthRanges(
        replacementSnapshot: Map<Long, PageReplacement>,
        expectedSnapshot: FileEditorSourceSnapshot,
    ): Result<Unit> {
        val changes = equalLengthRangeChanges(replacementSnapshot)
        if (changes.isEmpty()) return Result.success(Unit)
        val totalBytes = changes.sumOf { it.replacementBytes.size.toLong() }
        val attempted = mutableListOf<EqualLengthRangeChange>()
        var completedBytes = 0L
        var expected = expectedSnapshot
        try {
            changes.forEach { change ->
                attempted += change
                source.writeRange(
                    startOffset = change.startOffset,
                    data = change.replacementBytes,
                    expectedSnapshot = expected,
                    onProgress = { written, _ ->
                        updateSaveProgress(completedBytes + written, totalBytes, totalBytes)
                    },
                ).getOrThrow()
                completedBytes += change.replacementBytes.size
                expected = source.currentSnapshot().getOrThrow()
            }
            check(expected.size == currentSize) { AppStrings.ui_the_file_size_changes_after_saving_the_range }
            return Result.success(Unit)
        } catch (saveFailure: Throwable) {
            LogKit.e(AppStrings.ui_editor_saved_failed, saveFailure)
            if (saveFailure is FileEditorSourceChangedException) {
                _state.value = _state.value.copy(
                    saveConflict = EditorSaveConflict(saveFailure.expected, saveFailure.actual),
                )
                return Result.failure(saveFailure)
            }
            var rollbackFailure: Throwable? = null
            var rollbackSnapshot = source.currentSnapshot().getOrElse { error ->
                rollbackFailure = error
                null
            }
            if (rollbackSnapshot != null) {
                attempted.asReversed().forEach { change ->
                    if (rollbackFailure != null) return@forEach
                    val snapshot = rollbackSnapshot ?: return@forEach
                    source.writeRange(
                        startOffset = change.startOffset,
                        data = change.originalBytes,
                        expectedSnapshot = snapshot,
                    ).fold(
                        onSuccess = {
                            rollbackSnapshot = source.currentSnapshot().getOrElse { error ->
                                rollbackFailure = error
                                null
                            }
                        },
                        onFailure = { rollbackFailure = it },
                    )
                }
            }
            if (rollbackFailure == null) sessionSnapshot = rollbackSnapshot
            return Result.failure(EditorSaveRollbackException(saveFailure, rollbackFailure))
        }
    }

    private fun updateSaveProgress(completedBytes: Long, totalBytes: Long, fallbackTotal: Long) {
        val denominator = totalBytes.takeIf { it > 0L } ?: fallbackTotal
        val progress = if (denominator <= 0L) {
            1f
        } else {
            (completedBytes.toDouble() / denominator.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
        _state.value = _state.value.copy(progress = progress)
    }

    private fun createBaseContentFlow(
        replacementSnapshot: Map<Long, PageReplacement>,
        oldSize: Long,
    ) = flow {
        val oldPageCount = pageCount(oldSize)
        var pageIndex = 0L
        while (pageIndex < oldPageCount) {
            val bytes = replacementSnapshot[pageIndex]?.bytes ?: run {
                val (start, end) = pageRange(pageIndex, oldSize)
                if (end == start) {
                    byteArrayOf()
                } else {
                    readDocumentRange(start, end).getOrElse { throw it }.also { loaded ->
                        if (loaded.size.toLong() != end - start) {
                            throw IllegalStateException(AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range)
                        }
                    }
                }
            }
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + pageSize, bytes.size)
                emit(bytes.copyOfRange(offset, end))
                offset = end
            }
            pageIndex += 1L
        }
    }

    private fun createOriginalSourceContentFlow(originalSize: Long) = flow {
        var offset = 0L
        while (offset < originalSize) {
            val end = minOf(offset + pageSize, originalSize)
            val bytes = source.readRange(offset, end).getOrElse { throw it }
            check(bytes.size.toLong() == end - offset) { AppStrings.ui_backup_read_length_mismatch }
            emit(bytes)
            offset = end
        }
    }

    private suspend fun calculateOutputSize(
        baseContent: kotlinx.coroutines.flow.Flow<ByteArray>,
        baseSize: Long,
        codec: EditorTextCodec,
        encoding: EditorTextEncoding,
        conversion: EditorNewlineKind?,
    ): Long {
        if (conversion == null) return baseSize
        var convertedSize = 0L
        val transformer = EditorNewlineByteTransformer(codec, encoding, conversion)
        baseContent.collect { chunk -> convertedSize += transformer.accept(chunk).size }
        convertedSize += transformer.finish().size
        return convertedSize
    }

    private fun createOutputContentFlow(
        baseContent: kotlinx.coroutines.flow.Flow<ByteArray>,
        codec: EditorTextCodec,
        encoding: EditorTextEncoding,
        conversion: EditorNewlineKind?,
    ): kotlinx.coroutines.flow.Flow<ByteArray> {
        if (conversion == null) return baseContent
        return flow {
            val transformer = EditorNewlineByteTransformer(codec, encoding, conversion)
            baseContent.collect { chunk ->
                transformer.accept(chunk).takeIf { it.isNotEmpty() }?.let { emit(it) }
            }
            transformer.finish().takeIf { it.isNotEmpty() }?.let { emit(it) }
        }
    }

    private fun pendingEditBytes(): Long =
        replacements.values.sumOf(PageReplacement::storedBytes)

    private fun applyCurrentPageEdit(
        kind: EditorModificationKind,
        replacement: ByteArray,
        displayText: String,
        selectionStartOffset: Long? = null,
        selectionEndOffsetExclusive: Long? = null,
        encodingReplacementCount: Int = 0,
        encodingReplacementSamples: List<EditorEncodingReplacementSample> = emptyList(),
    ): Result<Unit> {
        val current = _state.value
        if (!current.canWrite) return fail(AppStrings.ui_the_file_only_supports_reading)
        if (current.isLoading || current.isSaving) return fail(AppStrings.ui_the_editor_is_busy)
        if (!initialized) return fail(AppStrings.ui_file_not_initialized)
        val original = currentOriginalPage
        val nextReplacement = if (replacement.contentEquals(original)) {
            null
        } else {
            PageReplacement(
                originalBytes = original.copyOf(),
                bytes = replacement.copyOf(),
                kind = kind,
                encodingReplacementCount = encodingReplacementCount,
                encodingReplacementSamples = encodingReplacementSamples.toList(),
            )
        }
        val currentStoredBytes = replacements[current.currentPage]?.storedBytes ?: 0L
        val pendingStoredBytes = replacements.values.sumOf(PageReplacement::storedBytes) -
                currentStoredBytes + (nextReplacement?.storedBytes ?: 0L)
        if (pendingStoredBytes > maxPendingEditBytes) {
            return fail(AppStrings.ui_the_session_limit_exceeds_arg0_mib.format(arg0 = (maxPendingEditBytes / (1024L * 1024L)).toString()))
        }

        val previousReplacement = replacements[current.currentPage]
        editCommandLog.record(
            EditorEditCommand(
                pageIndex = current.currentPage,
                pageStartOffset = current.pageStartOffset,
                kind = kind,
                originalBytes = original,
                beforeBytes = current.bytes,
                afterBytes = replacement,
                beforeEncodingReplacementCount = previousReplacement?.encodingReplacementCount ?: 0,
                beforeEncodingReplacementSamples =
                    previousReplacement?.encodingReplacementSamples.orEmpty(),
                afterEncodingReplacementCount = encodingReplacementCount,
                afterEncodingReplacementSamples = encodingReplacementSamples,
            )
        )
        if (nextReplacement == null) {
            replacements.remove(current.currentPage)
        } else {
            replacements[current.currentPage] = nextReplacement
        }
        _state.value = withEditState(
            current.copy(
                text = displayText,
                bytes = replacement.copyOf(),
                selectionStartOffset = selectionStartOffset ?: current.selectionStartOffset,
                selectionEndOffsetExclusive =
                    selectionEndOffsetExclusive ?: current.selectionEndOffsetExclusive,
                error = null,
            )
        )
        markContentChanged()
        scheduleRecoveryJournalWrite()
        return Result.success(Unit)
    }

    private suspend fun applyHistoryTransactionLocked(
        transaction: EditorEditTransaction,
        useAfter: Boolean,
    ): Result<Unit> {
        transaction.commands.forEach { command ->
            val targetBytes = if (useAfter) command.afterBytes else command.beforeBytes
            val encodingReplacementCount = if (useAfter) {
                command.afterEncodingReplacementCount
            } else {
                command.beforeEncodingReplacementCount
            }
            val encodingReplacementSamples = if (useAfter) {
                command.afterEncodingReplacementSamples
            } else {
                command.beforeEncodingReplacementSamples
            }
            if (targetBytes.contentEquals(command.originalBytes)) {
                replacements.remove(command.pageIndex)
            } else {
                replacements[command.pageIndex] = PageReplacement(
                    originalBytes = command.originalBytes.copyOf(),
                    bytes = targetBytes.copyOf(),
                    kind = command.kind,
                    encodingReplacementCount = encodingReplacementCount,
                    encodingReplacementSamples = encodingReplacementSamples.toList(),
                )
            }
        }
        val command = transaction.commands.first()
        val targetBytes = if (useAfter) command.afterBytes else command.beforeBytes
        val loaded = loadPageLocked(command.pageIndex)
        val changedRange = buildEditorModification(
            pageIndex = command.pageIndex,
            pageStartOffset = command.pageStartOffset,
            kind = command.kind,
            original = command.originalBytes,
            replacement = targetBytes,
        )
        _state.value = withEditState(
            _state.value.copy(
                selectionStartOffset = changedRange?.startOffset ?: command.pageStartOffset,
                selectionEndOffsetExclusive = changedRange?.endOffsetExclusive ?: command.pageStartOffset,
            )
        )
        markContentChanged()
        scheduleRecoveryJournalWrite()
        return loaded
    }

    private fun withEditState(state: FileEditorDocumentState): FileEditorDocumentState {
        val pageModifications = replacements.mapNotNull { (pageIndex, replacement) ->
            buildEditorModification(
                pageIndex = pageIndex,
                pageStartOffset = pageIndex * pageSize.toLong(),
                kind = replacement.kind,
                original = replacement.originalBytes,
                replacement = replacement.bytes,
            )
        }
        val modifications = pageModifications.sortedBy(EditorModification::startOffset)
        return state.copy(
            dirty = modifications.isNotEmpty() || state.newlineConversion != null,
            modifications = modifications,
            fileSize = currentSize,
            pageCount = pageCount(currentSize),
            canUndo = editCommandLog.canUndo,
            canRedo = editCommandLog.canRedo,
            editHistoryCommandCount = editCommandLog.commandCount,
            editHistoryStoredBytes = editCommandLog.storedBytes,
            pendingEditStoredBytes = pendingEditBytes(),
        )
    }

    private fun pageCount(size: Long): Long {
        if (size <= 0L) return 1L
        return ((size - 1L) / pageSize.toLong()) + 1L
    }

    private fun pageRange(index: Long, size: Long): Pair<Long, Long> {
        if (size <= 0L) return 0L to 0L
        val start = index * pageSize.toLong()
        val end = minOf(start + pageSize.toLong(), size)
        return start to end
    }

    private suspend fun readDocumentRange(
        startOffset: Long,
        endOffsetExclusive: Long,
    ): Result<ByteArray> = source.readRange(startOffset, endOffsetExclusive)

    private suspend fun decodePageWithLookahead(
        codec: EditorTextCodec,
        encoding: EditorTextEncoding,
        state: FileEditorDocumentState,
    ): DecodedTextPage {
        val gbkStartsWithContinuation = if (
            encoding == EditorTextEncoding.GBK &&
            state.pageStartOffset > 0L &&
            state.bytes.isNotEmpty()
        ) {
            val previous = readDocumentRange(state.pageStartOffset - 1L, state.pageStartOffset)
                .getOrNull()
                ?.singleOrNull()
            previous != null &&
                    (previous.toInt() and 0xFF) in 0x81..0xFE &&
                    isGbkTrail(state.bytes.first())
        } else {
            false
        }
        val utf16StartsWithContinuation = if (
            encoding in setOf(EditorTextEncoding.UTF16_LE, EditorTextEncoding.UTF16_BE) &&
            state.pageStartOffset >= 2L &&
            state.bytes.size >= 2
        ) {
            val previous = readDocumentRange(state.pageStartOffset - 2L, state.pageStartOffset)
                .getOrNull()
            previous?.size == 2 &&
                    previous.utf16CodeUnit(encoding) in 0xD800..0xDBFF &&
                    state.bytes.copyOfRange(0, 2).utf16CodeUnit(encoding) in 0xDC00..0xDFFF
        } else {
            false
        }
        val initial = decodeEditorTextPage(
            codec = codec,
            encoding = encoding,
            bytes = state.bytes,
            pageStartOffset = state.pageStartOffset,
            hasPreviousPageBytes = state.pageStartOffset > 0L,
            hasFollowingPageBytes = state.pageEndOffsetExclusive < currentSize,
            hasBom = state.pageStartOffset == 0L && state.hasEncodingBom,
            gbkStartsWithContinuation = gbkStartsWithContinuation,
            utf16StartsWithContinuation = utf16StartsWithContinuation,
        )
        if (state.pageEndOffsetExclusive >= currentSize) return initial
        val required = requiredFollowingBytes(state.bytes, encoding)
        if (required <= 0) return initial
        val lookaheadEnd = minOf(state.pageEndOffsetExclusive + required, currentSize)
        val lookahead = readDocumentRange(state.pageEndOffsetExclusive, lookaheadEnd).getOrElse {
            return initial
        }
        if (lookahead.isEmpty()) return initial
        val decoded = decodeEditorTextPage(
            codec = codec,
            encoding = encoding,
            bytes = state.bytes + lookahead,
            pageStartOffset = state.pageStartOffset,
            hasPreviousPageBytes = state.pageStartOffset > 0L,
            hasFollowingPageBytes = lookaheadEnd < currentSize,
            hasBom = state.pageStartOffset == 0L && state.hasEncodingBom,
            gbkStartsWithContinuation = gbkStartsWithContinuation,
            utf16StartsWithContinuation = utf16StartsWithContinuation,
        )
        return decoded.copy(
            prefix = initial.prefix,
            suffix = initial.suffix,
            usesFollowingPageBytes = true,
        )
    }

    private fun fail(message: String): Result<Unit> {
        val error = IllegalStateException(message)
        _state.value = _state.value.copy(error = message)
        return Result.failure(error)
    }

    private fun <T> failValue(message: String): Result<T> {
        val error = IllegalStateException(message)
        _state.value = _state.value.copy(error = message)
        return Result.failure(error)
    }
}

private fun nonOverlappingReplacementResults(
    results: List<EditorSearchResult>,
): Pair<List<EditorSearchResult>, Int> {
    val sorted = results.sortedWith(
        compareBy<EditorSearchResult> { it.startOffset }
            .thenByDescending { it.endOffsetExclusive - it.startOffset }
            .thenBy { it.keyword },
    )
    val accepted = mutableListOf<EditorSearchResult>()
    var occupiedEnd = Long.MIN_VALUE
    var zeroWidthOffset: Long? = null
    var skipped = 0
    sorted.forEach { result ->
        val zeroWidth = result.startOffset == result.endOffsetExclusive
        val overlaps = result.startOffset < occupiedEnd ||
            (zeroWidth && zeroWidthOffset == result.startOffset)
        if (overlaps) {
            skipped += 1
        } else {
            accepted += result
            occupiedEnd = maxOf(occupiedEnd, result.endOffsetExclusive)
            zeroWidthOffset = result.startOffset.takeIf { zeroWidth }
        }
    }
    return accepted to skipped
}

private fun decodeTextPage(
    bytes: ByteArray,
    hasPreviousPageBytes: Boolean,
    hasFollowingPageBytes: Boolean,
): DecodedTextPage {
    if (bytes.isEmpty()) {
        return DecodedTextPage("", byteArrayOf(), byteArrayOf(), true)
    }

    var prefixLength = 0
    if (hasPreviousPageBytes) {
        while (prefixLength < minOf(3, bytes.size) && bytes[prefixLength].isUtf8Continuation()) {
            prefixLength += 1
        }
    }
    val suffixLength = if (hasFollowingPageBytes) {
        incompleteUtf8SuffixLength(bytes, prefixLength)
    } else {
        0
    }
    val textEnd = bytes.size - suffixLength
    if (textEnd < prefixLength) {
        return DecodedTextPage("", byteArrayOf(), byteArrayOf(), false)
    }

    val visibleBytes = bytes.copyOfRange(prefixLength, textEnd)
    val hasBinaryControl = visibleBytes.any { byte ->
        val value = byte.toInt() and 0xFF
        value == 0 || (value < 0x20 && value != 0x09 && value != 0x0A && value != 0x0D)
    }
    val decoded = runCatching {
        visibleBytes.decodeToString(throwOnInvalidSequence = true)
    }.getOrNull()
    val validPrefix = prefixLength == 0 || hasPreviousPageBytes
    val validSuffix = suffixLength == 0 || hasFollowingPageBytes
    val isText = decoded != null && !hasBinaryControl && validPrefix && validSuffix
    return DecodedTextPage(
        text = decoded ?: visibleBytes.decodeToString(),
        prefix = bytes.copyOfRange(0, prefixLength),
        suffix = bytes.copyOfRange(textEnd, bytes.size),
        isText = isText,
    )
}

private fun decodeEditorTextPage(
    codec: EditorTextCodec,
    encoding: EditorTextEncoding,
    bytes: ByteArray,
    pageStartOffset: Long,
    hasPreviousPageBytes: Boolean,
    hasFollowingPageBytes: Boolean,
    hasBom: Boolean,
    gbkStartsWithContinuation: Boolean = false,
    utf16StartsWithContinuation: Boolean = false,
): DecodedTextPage {
    if (bytes.isEmpty()) return DecodedTextPage("", byteArrayOf(), byteArrayOf(), true)

    val bomLength = if (pageStartOffset == 0L && hasBom) {
        when (encoding) {
            EditorTextEncoding.UTF8 -> 3
            EditorTextEncoding.UTF16_LE, EditorTextEncoding.UTF16_BE -> 2
            else -> 0
        }.coerceAtMost(bytes.size)
    } else {
        0
    }
    var prefixLength = bomLength
    var suffixLength = 0
    when (encoding) {
        EditorTextEncoding.UTF8 -> {
            if (hasPreviousPageBytes) {
                while (
                    prefixLength < minOf(bomLength + 3, bytes.size) &&
                    bytes[prefixLength].isUtf8Continuation()
                ) {
                    prefixLength += 1
                }
            }
            suffixLength = if (hasFollowingPageBytes) {
                incompleteUtf8SuffixLength(bytes, prefixLength)
            } else {
                0
            }
        }

        EditorTextEncoding.UTF16_LE, EditorTextEncoding.UTF16_BE -> {
            if (utf16StartsWithContinuation && prefixLength + 2 <= bytes.size) prefixLength += 2
            val contentStart = pageStartOffset - if (hasBom) 2L else 0L
            if (hasPreviousPageBytes && contentStart % 2L != 0L && prefixLength < bytes.size) {
                prefixLength += 1
            }
            if (hasFollowingPageBytes && (bytes.size - prefixLength) % 2 != 0) {
                suffixLength = 1
            }
        }

        EditorTextEncoding.GBK -> {
            if (gbkStartsWithContinuation && prefixLength < bytes.size) prefixLength += 1
            var index = prefixLength
            while (index < bytes.size) {
                val value = bytes[index].toInt() and 0xFF
                if (value < 0x80) {
                    index += 1
                } else if (index + 1 < bytes.size && isGbkTrail(bytes[index + 1])) {
                    index += 2
                } else {
                    if (hasFollowingPageBytes && index == bytes.lastIndex) suffixLength = 1
                    break
                }
            }
        }

        else -> Unit
    }

    val textEnd = bytes.size - suffixLength
    if (textEnd < prefixLength) {
        return DecodedTextPage("", byteArrayOf(), byteArrayOf(), false)
    }
    val visibleBytes = bytes.copyOfRange(prefixLength, textEnd)
    val decoded = codec.decode(visibleBytes, encoding, stripBom = false)
    val hasBinaryControl = decoded.text.any { char ->
        char == '\u0000' || (char < ' ' && char != '\t' && char != '\n' && char != '\r')
    }
    return DecodedTextPage(
        text = decoded.text,
        prefix = bytes.copyOfRange(0, prefixLength),
        suffix = bytes.copyOfRange(textEnd, bytes.size),
        isText = !decoded.hadErrors && !hasBinaryControl,
    )
}

private fun isGbkTrail(byte: Byte): Boolean =
    (byte.toInt() and 0xFF) in 0x40..0xFE && (byte.toInt() and 0xFF) != 0x7F

private fun ByteArray.utf16CodeUnit(encoding: EditorTextEncoding): Int {
    require(size >= 2)
    val first = this[0].toInt() and 0xFF
    val second = this[1].toInt() and 0xFF
    return if (encoding == EditorTextEncoding.UTF16_LE) {
        first or (second shl 8)
    } else {
        (first shl 8) or second
    }
}

private fun requiredFollowingBytes(bytes: ByteArray, encoding: EditorTextEncoding): Int = when (encoding) {
    EditorTextEncoding.UTF8 -> {
        val suffixLength = incompleteUtf8SuffixLength(bytes, 0)
        if (suffixLength == 0) 0 else {
            val sequenceLength = bytes[bytes.size - suffixLength].utf8SequenceLength()
            (sequenceLength - suffixLength).coerceAtLeast(0)
        }
    }

    EditorTextEncoding.UTF16_LE,
    EditorTextEncoding.UTF16_BE,
        -> when {
        bytes.size % 2 != 0 -> 1
        bytes.size < 2 -> 0
        else -> {
            val first = bytes[bytes.size - 2].toInt() and 0xFF
            val second = bytes.last().toInt() and 0xFF
            val codeUnit = if (encoding == EditorTextEncoding.UTF16_LE) {
                first or (second shl 8)
            } else {
                (first shl 8) or second
            }
            if (codeUnit in 0xD800..0xDBFF) 2 else 0
        }
    }

    EditorTextEncoding.GBK -> {
        var index = 0
        var danglingLead = false
        while (index < bytes.size) {
            val value = bytes[index].toInt() and 0xFF
            if (value < 0x80) {
                index += 1
            } else if (index + 1 < bytes.size && isGbkTrail(bytes[index + 1])) {
                index += 2
            } else {
                danglingLead = index == bytes.lastIndex
                break
            }
        }
        if (danglingLead) 1 else 0
    }

    else -> 0
}

private fun incompleteUtf8SuffixLength(bytes: ByteArray, lowerBound: Int): Int {
    val firstCandidate = maxOf(lowerBound, bytes.size - 4)
    for (index in firstCandidate until bytes.size) {
        val sequenceLength = bytes[index].utf8SequenceLength()
        if (sequenceLength <= 1 || index + sequenceLength <= bytes.size) continue
        val trailingBytes = bytes.size - index - 1
        if ((1..trailingBytes).all { offset -> bytes[index + offset].isUtf8Continuation() }) {
            return bytes.size - index
        }
    }
    return 0
}

private fun Byte.isUtf8Continuation(): Boolean = (toInt() and 0xC0) == 0x80

private fun Byte.utf8SequenceLength(): Int = when (toInt() and 0xFF) {
    in 0x00..0x7F -> 1
    in 0xC2..0xDF -> 2
    in 0xE0..0xEF -> 3
    in 0xF0..0xF4 -> 4
    else -> 0
}

internal fun isLikelyUtf8Text(bytes: ByteArray): Boolean =
    decodeTextPage(
        bytes = bytes,
        hasPreviousPageBytes = false,
        hasFollowingPageBytes = true,
    ).isText

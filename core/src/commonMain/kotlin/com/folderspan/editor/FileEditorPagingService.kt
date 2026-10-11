package com.folderspan.editor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import strings.AppStrings

data class FileEditorPage(
    val index: Long,
    val startOffset: Long,
    val endOffsetExclusive: Long,
    val bytes: ByteArray,
)

/** Bounded LRU page reader shared by editor presentation, navigation and background prefetch. */
class FileEditorPagingService(
    private val source: FileEditorContentSource,
    private val pageSize: Int = DEFAULT_FILE_EDITOR_PAGE_SIZE,
    private val maxCachedPages: Int = DEFAULT_FILE_EDITOR_CACHE_PAGES,
    private val taskCoordinator: FileEditorTaskCoordinator = FileEditorTaskCoordinator(),
) {
    init {
        require(pageSize > 0)
        require(maxCachedPages > 0)
    }

    private val cacheMutex = Mutex()
    private val loadMutex = Mutex()
    private val cache = LinkedHashMap<Long, FileEditorPage>()

    suspend fun readPage(index: Long, fileSize: Long): Result<FileEditorPage> =
        readPageDirect(index, fileSize)

    suspend fun prefetchAround(index: Long, fileSize: Long): List<Job> {
        val count = pageCount(fileSize)
        return listOf(index - 1L, index + 1L)
            .filter { it in 0L until count }
            .map { neighbor ->
                taskCoordinator.launch(FileEditorTaskKind.Prefetch) {
                    readPageDirect(neighbor, fileSize)
                }
            }
    }

    suspend fun cachedPage(index: Long): FileEditorPage? = cacheMutex.withLock {
        val page = cache.remove(index) ?: return@withLock null
        cache[index] = page
        page.copy(bytes = page.bytes.copyOf())
    }

    suspend fun cachedPageCount(): Int = cacheMutex.withLock { cache.size }

    suspend fun clear() {
        cacheMutex.withLock { cache.clear() }
    }

    private suspend fun readPageDirect(index: Long, fileSize: Long): Result<FileEditorPage> {
        require(fileSize >= 0L)
        val normalized = index.coerceIn(0L, pageCount(fileSize) - 1L)
        cachedPage(normalized)?.let { return Result.success(it) }
        return loadMutex.withLock {
            cachedPage(normalized)?.let { return@withLock Result.success(it) }
            val start = normalized * pageSize.toLong()
            val end = minOf(start + pageSize.toLong(), fileSize)
            val result = if (start == end) {
                Result.success(byteArrayOf())
            } else {
                withContext(Dispatchers.Default) {
                    runCatching { source.readRange(start, end).getOrThrow() }
                }
            }
            result.mapCatching { loaded ->
                check(loaded.size.toLong() == end - start) { AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range }
                val page = FileEditorPage(normalized, start, end, loaded.copyOf())
                cacheMutex.withLock {
                    cache.remove(normalized)
                    cache[normalized] = page
                    while (cache.size > maxCachedPages) cache.remove(cache.keys.first())
                }
                page.copy(bytes = page.bytes.copyOf())
            }
        }
    }

    private fun pageCount(size: Long): Long =
        if (size <= 0L) 1L else ((size - 1L) / pageSize.toLong()) + 1L
}

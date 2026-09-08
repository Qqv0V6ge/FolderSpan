package com.folderspan.service.http.archive

internal data class SmallFileArchiveBatch<T>(
    val items: List<T>,
    val requestEntries: List<FolderSpanArchiveEntryRequest>,
)

internal data class SmallFileArchiveSelection<T>(
    val batches: List<SmallFileArchiveBatch<T>>,
    val remainingItems: List<T>,
)

internal fun <T> selectSmallFileArchiveBatches(
    items: List<T>,
    sourcePath: (T) -> String,
    relativePath: (T) -> String,
    size: (T) -> Long,
    hidden: (T) -> Boolean = { false },
    mimeType: (T) -> String = { "" },
    modifiedTimeMillis: (T) -> Long = { 0L },
    minBatchEntries: Int = 2,
    maxBatchEntries: Int = FOLDER_SPAN_ARCHIVE_TARGET_BATCH_ENTRIES,
    maxFileBytes: Long = FOLDER_SPAN_ARCHIVE_SMALL_FILE_THRESHOLD_BYTES,
    maxBatchPayloadBytes: Long = FOLDER_SPAN_ARCHIVE_TARGET_BATCH_PAYLOAD_BYTES,
): SmallFileArchiveSelection<T> {
    val safeMinBatchEntries = minBatchEntries.coerceAtLeast(1)
    val safeMaxBatchEntries = maxBatchEntries.coerceIn(safeMinBatchEntries, FOLDER_SPAN_ARCHIVE_MAX_ENTRIES)
    val safeMaxFileBytes = maxFileBytes.coerceIn(0L, FOLDER_SPAN_ARCHIVE_SMALL_FILE_THRESHOLD_BYTES)
    val safeMaxBatchPayloadBytes = maxBatchPayloadBytes.coerceIn(1L, FOLDER_SPAN_ARCHIVE_MAX_PAYLOAD_BYTES)
    val batches = mutableListOf<SmallFileArchiveBatch<T>>()
    val remaining = mutableListOf<T>()
    var currentItems = mutableListOf<T>()
    var currentEntries = mutableListOf<FolderSpanArchiveEntryRequest>()
    var currentBytes = 0L

    fun flushCurrent() {
        if (currentItems.isEmpty()) return
        if (currentItems.size >= safeMinBatchEntries) {
            batches += SmallFileArchiveBatch(currentItems.toList(), currentEntries.toList())
        } else {
            remaining += currentItems
        }
        currentItems = mutableListOf()
        currentEntries = mutableListOf()
        currentBytes = 0L
    }

    items.forEach { item ->
        val itemSize = size(item)
        val itemSourcePath = sourcePath(item)
        if (
            itemSourcePath.isBlank() ||
            itemSize < 0L ||
            itemSize > safeMaxFileBytes
        ) {
            remaining += item
            return@forEach
        }

        if (
            currentItems.size >= safeMaxBatchEntries ||
            currentBytes + itemSize > safeMaxBatchPayloadBytes
        ) {
            flushCurrent()
        }

        val normalizedRelativePath = runCatching {
            FolderSpanArchiveCodec.normalizeRelativePath(relativePath(item))
        }.getOrElse {
            remaining += item
            return@forEach
        }
        currentItems += item
        currentEntries += FolderSpanArchiveEntryRequest(
            sourcePath = itemSourcePath,
            relativePath = normalizedRelativePath,
            size = itemSize,
            directory = false,
            hidden = hidden(item),
            mimeType = mimeType(item),
            modifiedTimeMillis = modifiedTimeMillis(item),
        )
        currentBytes += itemSize
    }
    flushCurrent()

    return SmallFileArchiveSelection(batches = batches, remainingItems = remaining)
}

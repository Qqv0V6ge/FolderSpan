package com.folderspan.service.http.clipboard

import com.folderspan.utils.PathUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import strings.AppStrings

internal class OkioClipboardDownloadStagingFactory(
    private val fileSystem: FileSystem,
    private val cacheRoot: () -> String = PathUtils::getCachePath,
) : ClipboardDownloadStagingFactory {
    override suspend fun create(): ClipboardDownloadStagingBatch {
        val id = newClipboardStagingId()
        val separator = PathUtils.getPathSeparator()
        val root = listOf(cacheRoot().trimEnd('/', '\\'), "clipboard-url-download", id)
            .filter(String::isNotBlank)
            .joinToString(separator)
            .toPath()
        fileSystem.createDirectories(root)
        return OkioClipboardDownloadStagingBatch(id, root, fileSystem)
    }
}

private class OkioClipboardDownloadStagingBatch(
    private val id: String,
    private val root: Path,
    private val fileSystem: FileSystem,
) : ClipboardDownloadStagingBatch {
    private val mutex = Mutex()
    private val partSizes = mutableMapOf<Int, Long>()
    private var published = false
    private var cleaned = false

    override suspend fun resetPart(index: Int) = mutex.withLock {
        ensureActive()
        val path = partPath(index)
        if (fileSystem.exists(path)) fileSystem.delete(path)
        partSizes.remove(index)
        Unit
    }

    override suspend fun append(index: Int, bytes: ByteArray) = mutex.withLock {
        ensureActive()
        fileSystem.appendingSink(partPath(index), mustExist = false).buffer().use { sink ->
            sink.write(bytes)
        }
        partSizes[index] = (partSizes[index] ?: 0L) + bytes.size
    }

    override suspend fun partSize(index: Int): Long = mutex.withLock { partSizes[index] ?: 0L }

    override suspend fun publish(
        partIndices: List<Int>,
        displayName: String,
        contentType: String?,
        expectedBytes: Long,
    ): ClipboardStagedDownload = mutex.withLock {
        ensureActive()
        val actualBytes = partIndices.sumOf { index -> partSizes[index] ?: 0L }
        if (actualBytes != expectedBytes) throw ClipboardDownloadLengthMismatchException()
        val safeName = sanitizeClipboardDownloadFileName(displayName) ?: "download-$id"
        val mergedPart = root / "$safeName.part"
        if (fileSystem.exists(mergedPart)) fileSystem.delete(mergedPart)
        fileSystem.sink(mergedPart).buffer().use { sink ->
            partIndices.forEach { index ->
                val part = partPath(index)
                fileSystem.source(part).buffer().use { source -> sink.writeAll(source) }
            }
        }
        if (fileSystem.metadata(mergedPart).size != expectedBytes) {
            throw ClipboardDownloadLengthMismatchException()
        }
        val finalPath = root / safeName
        fileSystem.atomicMove(mergedPart, finalPath)
        partIndices.forEach { index ->
            val part = partPath(index)
            if (fileSystem.exists(part)) fileSystem.delete(part)
        }
        published = true
        ClipboardStagedDownload(
            id = id,
            displayName = safeName,
            size = expectedBytes,
            contentType = contentType,
            localPath = finalPath.toString(),
            release = { runCatching { fileSystem.deleteRecursively(root, mustExist = false) } },
        )
    }

    override suspend fun cleanup() = mutex.withLock {
        if (!published && !cleaned) fileSystem.deleteRecursively(root, mustExist = false)
        cleaned = true
        partSizes.clear()
    }

    private fun partPath(index: Int): Path = root / "download.part.$index"

    private fun ensureActive() {
        check(!cleaned && !published) { AppStrings.ui_batch_storage_cannot_be_written }
    }
}

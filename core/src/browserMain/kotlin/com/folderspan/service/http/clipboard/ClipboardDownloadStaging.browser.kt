package com.folderspan.service.http.clipboard

import com.folderspan.service.webui.WebUiPlatformClient
import com.folderspan.service.webui.joinWebUiPath
import com.folderspan.utils.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

actual fun createPlatformClipboardDownloadStagingFactory(): ClipboardDownloadStagingFactory = ClipboardDownloadStagingFactory {
    val id = newClipboardStagingId()
    val root = joinWebUiPath(PathUtils.getCachePath(), "clipboard-$id")
    check(FileUtils.createFolder(FileAccessPermission.Allowed, root).getOrThrow())
    object : ClipboardDownloadStagingBatch {
        private val mutex = Mutex()
        private val sizes = mutableMapOf<Int, Long>()
        private var published = false
        private var cleaned = false
        private fun ensureActive() { check(!published && !cleaned) { "clipboard staging is closed" } }
        private fun part(index: Int): String { require(index >= 0); return joinWebUiPath(root, "part-$index") }
        override suspend fun resetPart(index: Int) = mutex.withLock {
            ensureActive()
            if (sizes.containsKey(index)) FileUtils.deleteFile(FileAccessPermission.Allowed, part(index)).getOrThrow()
            FileUtils.createFile(FileAccessPermission.Allowed, part(index)).getOrThrow()
            sizes[index] = 0
        }
        override suspend fun append(index: Int, bytes: ByteArray) = mutex.withLock {
            ensureActive()
            val size = sizes[index] ?: 0
            check(FileUtils.writeBytes(FileAccessPermission.Allowed, part(index), size + bytes.size, bytes, size).getOrThrow())
            sizes[index] = size + bytes.size
        }
        override suspend fun partSize(index: Int) = mutex.withLock { sizes[index] ?: 0L }
        override suspend fun publish(partIndices: List<Int>, displayName: String, contentType: String?, expectedBytes: Long): ClipboardStagedDownload = mutex.withLock {
            ensureActive()
            if (partIndices.sumOf { sizes[it] ?: 0L } != expectedBytes) throw ClipboardDownloadLengthMismatchException()
            val name = sanitizeClipboardDownloadFileName(displayName) ?: "download-$id"
            val output = joinWebUiPath(root, "result-$name")
            FileUtils.createFile(FileAccessPermission.Allowed, output).getOrThrow()
            var offset = 0L
            partIndices.forEach { index ->
                FileUtils.readFileRangeChunks(FileAccessPermission.Allowed, part(index), 0, sizes[index] ?: 0, 1024L * 1024).collect { chunk ->
                    val bytes = chunk.getOrThrow().second
                    check(FileUtils.writeBytes(FileAccessPermission.Allowed, output, expectedBytes, bytes, offset).getOrThrow())
                    offset += bytes.size
                }
            }
            if (offset != expectedBytes) throw ClipboardDownloadLengthMismatchException()
            partIndices.forEach { FileUtils.deleteFile(FileAccessPermission.Allowed, part(it)).getOrThrow() }
            published = true
            ClipboardStagedDownload(id, name, expectedBytes, contentType, localPath = output, release = { WebUiPlatformClient.releaseTemporary(root) })
        }
        override suspend fun cleanup() = mutex.withLock {
            if (!published && !cleaned) FileUtils.deleteFile(FileAccessPermission.Allowed, root).getOrThrow()
            cleaned = true
            sizes.clear()
        }
    }
}

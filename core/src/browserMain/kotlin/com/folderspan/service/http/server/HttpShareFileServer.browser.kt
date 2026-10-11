package com.folderspan.service.http.server

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.http.server.linkshare.*
import com.folderspan.service.webui.BrowserHttpListener
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PrivateFileStorage

actual class HttpShareFileServer actual constructor(callbacks: PlatformHttpCallbacks) : HttpShareFileServerCommon(callbacks) {
    private val listener = BrowserHttpListener(callbacks)
    actual override suspend fun start(port: Int) { listener.start("link-share", port) }
    actual override suspend fun stop() = listener.stop()
    actual override fun isRunning() = listener.running

    override suspend fun downloadLocalFile(filePath: String, file: FileSimpleInfo?, request: LinkShareHttpRequest, delivery: LinkShareFileDelivery): LinkShareHttpResponse {
        val target = FileUtils.getFile(FileAccessPermission.Allowed, filePath).getOrThrow()
        val presentation = resolveLinkShareFilePresentation(target.name, delivery, file?.mineType ?: target.mineType)
        val rangeHeader = request.header("Range")
        val range = if (rangeHeader == null) null else parseBrowserShareRange(rangeHeader, target.size)
            ?: return LinkShareHttpResponse.bytes(416, headers = listOf(LinkShareHttpHeader("Content-Range", "bytes */${target.size}")))
        val start = range?.first ?: 0L
        val length = range?.let { it.last - it.first + 1 } ?: target.size
        val headers = presentation.headers + if (range == null) emptyList() else listOf(LinkShareHttpHeader("Content-Range", "bytes ${range.first}-${range.last}/${target.size}"))
        val response = LinkShareHttpResponse.stream(if (range == null) 200 else 206, length, presentation.contentType, headers) {
            FileUtils.readFileRangeChunks(FileAccessPermission.Allowed, filePath, start, start + length, 1024L * 1024).collect { write(it.getOrThrow().second) }
        }
        if (PrivateFileStorage.owns(filePath)) return response
        return response.copy(body = (response.body as LinkShareHttpResponseBody.Stream).copy(
            hostFileRange = LinkShareHostFileRange(filePath, start, length),
        ))
    }
}

private fun parseBrowserShareRange(header: String, size: Long): LongRange? {
    if (!header.startsWith("bytes=") || size <= 0) return null
    val parts = header.removePrefix("bytes=").split('-', limit = 2)
    if (parts.size != 2) return null
    if (parts[0].isEmpty()) {
        val suffix = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return (size - suffix).coerceAtLeast(0)..size - 1
    }
    val start = parts[0].toLongOrNull() ?: return null
    val end = if (parts[1].isEmpty()) size - 1 else parts[1].toLongOrNull() ?: return null
    return if (start >= 0 && start < size && end >= start) start..minOf(end, size - 1) else null
}

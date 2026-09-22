package com.folderspan.service.http.server

import com.folderspan.utils.FileAccessPermission
import strings.AppStrings

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.extensions.classNames
import com.folderspan.service.http.server.linkshare.LinkShareFileDelivery
import com.folderspan.service.http.server.linkshare.LinkShareHttpHeader
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.http.server.linkshare.resolveLinkShareFilePresentation
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.SettingsUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSLock

actual class HttpShareFileServer private actual constructor(fileShareState: FileShareState) :
    HttpShareFileServerCommon(fileShareState) {
    private val serverStateLock = NSLock()
    private var httpServer: LinkShareRawHttpServer? = null

    actual companion object {
        private val instanceLock = NSLock()
        private var instance: HttpShareFileServer? = null

        actual fun getInstance(fileShareState: FileShareState): HttpShareFileServer {
            instanceLock.lock()
            return try {
                instance ?: HttpShareFileServer(fileShareState).also { item -> instance = item }
            } finally {
                instanceLock.unlock()
            }
        }
    }

    actual override fun start(port: Int) {
        val httpsPort = defaultLinkShareHttpsPort(port)
        val conflictingFileSharePort = SettingsUtils.fileShare.isEnabled() &&
            SettingsUtils.fileShare.getPort() == port
        if (conflictingFileSharePort) {
            val error = IllegalStateException(AppStrings.ui_file_sharing_service_and_simple_sharing_service_cannot_use_the_same_port_arg0.format(arg0 = (port).toString()))
            val message = error.toServerStartFailureMessage(port)
            LogKit.e(AppStrings.ui_failed_start_httpsharefileserver_ios_raw_arg0.format(arg0 = message), error)
            fileShareState.updateHttpServerRunning(false)
            notifyServerStartFailure(ServerStartNotificationService.SimpleSharing, port, error)
            return
        }

        try {
            serverStateLock.lock()
            try {
                if (httpServer != null) {
                    LogKit.d(AppStrings.ui_httpsharefileserver_ios_raw_already_running_skip_repeated_startup)
                    fileShareState.updateHttpServerRunning(true)
                    return
                }

                LogKit.i(AppStrings.ui_start_httpsharefileserver_ios_raw_http_https_port_arg0.format(arg0 = (port).toString()))
                configureLinkSharePorts(port, httpsPort)
                val http = LinkShareRawHttpServer(
                    scheme = "auto",
                    handler = { request -> dispatchLinkShareRequest(request) },
                )
                http.start(port)
                httpServer = http
                fileShareState.updateHttpServerRunning(true)
                LogKit.i(AppStrings.ui_httpsharefileserver_ios_raw_startup_completed_port_arg0.format(arg0 = (port).toString()))
            } finally {
                serverStateLock.unlock()
            }
        } catch (e: Throwable) {
            val message = e.toServerStartFailureMessage(port)
            LogKit.e(AppStrings.ui_failed_start_httpsharefileserver_ios_raw_arg0.format(arg0 = message), e)
            runBlocking { stop() }
            if (e.isPortInUseFailure()) {
                notifyServerPortInUse(ServerStartNotificationService.SimpleSharing, port)
            } else {
                notifyServerStartFailure(ServerStartNotificationService.SimpleSharing, port, e)
            }
        }
    }

    actual override suspend fun stop() {
        val httpToStop: LinkShareRawHttpServer?
        serverStateLock.lock()
        try {
            httpToStop = httpServer
            httpServer = null
            fileShareState.updateHttpServerRunning(false)
        } finally {
            serverStateLock.unlock()
        }
        withContext(Dispatchers.Default) {
            runCatching { httpToStop?.stop() }
        }
    }

    actual override fun isRunning(): Boolean {
        serverStateLock.lock()
        return try {
            httpServer?.isRunning() == true
        } finally {
            serverStateLock.unlock()
        }
    }

    override suspend fun downloadLocalFile(
        filePath: String,
        file: FileSimpleInfo?,
        request: LinkShareHttpRequest,
        delivery: LinkShareFileDelivery,
    ): LinkShareHttpResponse {
        val resolvedFile = FileUtils.getFile(FileAccessPermission.Allowed, filePath).getOrNull()
            ?: return LinkShareHttpResponse.text(404, AppStrings.ui_arg0_does_not_exist.format(arg0 = filePath))
        val fileSize = resolvedFile.size
        val range = parseRangeHeader(request.header("Range"), fileSize)
        val presentation = resolveLinkShareFilePresentation(
            fileName = resolvedFile.name,
            requestedDelivery = delivery,
            fileMimeTypeHint = file?.mineType ?: resolvedFile.mineType,
        )
        val headers = presentation.headers.toMutableList()
        val statusCode: Int
        val start: Long
        val length: Long
        if (range != null) {
            statusCode = 206
            start = range.first
            length = range.last - range.first + 1
            headers += LinkShareHttpHeader("Content-Range", "bytes ${range.first}-${range.last}/$fileSize")
        } else {
            statusCode = 200
            start = 0L
            length = fileSize
        }
        headers += LinkShareHttpHeader("Content-Length", length.coerceAtLeast(0).toString())
        if (length <= 0L) {
            return LinkShareHttpResponse.bytes(
                bytes = byteArrayOf(),
                contentType = presentation.contentType,
                headers = headers,
            )
        }
        return LinkShareHttpResponse.stream(
            statusCode = statusCode,
            contentLength = length,
            contentType = presentation.contentType,
            headers = headers,
        ) {
            runCatching {
                FileSystem.SYSTEM.openReadOnly(filePath.toPath()).use { handle ->
                    handle.source(start).buffer().use { source ->
                        val buffer = ByteArray(IO_BUFFER_SIZE)
                        var remaining = length
                        while (remaining > 0) {
                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val readCount = source.read(buffer, 0, toRead)
                            if (readCount <= 0) break
                            write(buffer, 0, readCount)
                            remaining -= readCount
                        }
                    }
                }
            }.onFailure { error ->
                if (!error.isExpectedClientDisconnect()) {
                    LogKit.w(AppStrings.ui_failed_read_shared_file_arg0.format(arg0 = (error.message).toString()), error)
                }
            }
        }
    }
}

private const val IO_BUFFER_SIZE = 8 * 1024

internal fun parseRangeHeader(rangeHeader: String?, fileSize: Long): LongRange? {
    if (rangeHeader == null || !rangeHeader.startsWith("bytes=") || fileSize <= 0) return null
    val rangeParts = rangeHeader.substring(6).split("-", limit = 2)
    if (rangeParts.size != 2) return null
    val start = rangeParts[0].toLongOrNull() ?: return null
    val end = if (rangeParts[1].isNotEmpty()) rangeParts[1].toLongOrNull() ?: return null else fileSize - 1
    if (start < 0 || end >= fileSize || start > end) return null
    return start..end
}

private fun Throwable.isExpectedClientDisconnect(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val error = current
        val names = error.classNames().map { item -> item.lowercase() }
        if (names.any { name ->
                name.endsWith("eofexception") ||
                    name.endsWith("closedwritechannelexception") ||
                    name.endsWith("closedreceivechannelexception") ||
                    name.endsWith("closedchannelexception")
            }
        ) {
            return true
        }

        val message = buildString {
            append(error.message.orEmpty())
            append(' ')
            append(error.toString())
        }.lowercase()
        if (
            message.contains("connection reset") ||
            message.contains("broken pipe") ||
            message.contains("socket closed") ||
            message.contains("closed inbound") ||
            message.contains("close_notify") ||
            message.contains("client disconnected") ||
            message.contains("channel was closed") ||
            message.contains("closed write channel")
        ) {
            return true
        }
        current = error.cause
    }
    return false
}

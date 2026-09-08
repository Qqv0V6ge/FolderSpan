package com.folderspan.service.http.server

import strings.AppStrings

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.http.server.linkshare.LinkShareFileDelivery
import com.folderspan.service.http.server.linkshare.LinkShareFilePresentation
import com.folderspan.service.http.server.linkshare.LinkShareHttpHeader
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.http.server.linkshare.resolveLinkShareFilePresentation
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.utils.LogKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.nio.file.Files

actual class HttpShareFileServer private actual constructor(fileShareState: FileShareState) :
    HttpShareFileServerCommon(fileShareState) {
    private val schemeSwitchingHttpProxy = HttpPortSchemeSwitchingProxy()
    private var httpServer: LinkShareRawHttpServer? = null
    private var httpsServer: LinkShareRawHttpServer? = null

    actual companion object {
        @Volatile
        private var instance: HttpShareFileServer? = null

        actual fun getInstance(fileShareState: FileShareState): HttpShareFileServer {
            return instance ?: synchronized(this) {
                instance ?: HttpShareFileServer(fileShareState).also { item -> instance = item }
            }
        }
    }

    actual override fun start(port: Int) {
        if (isRunning()) {
            LogKit.d(AppStrings.ui_httpsharefileserver_already_running_skip_repeated_startup)
            fileShareState.updateHttpServerRunning(true)
            return
        }

        try {
            val httpsPort = defaultLinkShareHttpsPort(port)
            val internalHttpPort = if (httpsPort != null) allocateInternalServerPort(port) else port
            val internalHttpsPort = httpsPort?.let { allocateInternalServerPort(port, internalHttpPort) }
            if (httpsPort != null && internalHttpsPort != null) {
                schemeSwitchingHttpProxy.start(
                    publicHttpPort = port,
                    internalHttpPort = internalHttpPort,
                    internalTlsPort = internalHttpsPort,
                    bindLan = true,
                    allowPlainHttpOnLan = true,
                )
            }
            configureLinkSharePorts(port, httpsPort)
            LogKit.i(
                AppStrings.ui_start_httpsharefileserver_raw_http_https_port_arg0.format(arg0 = (port).toString()) +
                    AppStrings.ui_internal_http_port_arg0_internal_tls_port_arg1.format(arg0 = (internalHttpPort).toString(), arg1 = (internalHttpsPort ?: AppStrings.ui_not_available).toString())
            )

            val http = LinkShareRawHttpServer("http") { request -> dispatchLinkShareRequest(request) }
            http.start(
                port = internalHttpPort,
                host = if (httpsPort != null) "127.0.0.1" else null,
                tls = false
            )
            httpServer = http

            if (internalHttpsPort != null) {
                val https = LinkShareRawHttpServer("https") { request -> dispatchLinkShareRequest(request) }
                https.start(port = internalHttpsPort, host = "127.0.0.1", tls = true)
                httpsServer = https
            }

            fileShareState.updateHttpServerRunning(true)
            LogKit.i(AppStrings.ui_httpsharefileserver_raw_startup_completed_port_arg0.format(arg0 = (port).toString()))
        } catch (e: Throwable) {
            val message = e.toServerStartFailureMessage(port)
            LogKit.e(AppStrings.ui_failed_start_httpsharefileserver_raw_arg0.format(arg0 = message), e)
            runBlocking { stopAfterStartFailure() }
            fileShareState.updateHttpServerRunning(false)
            if (e.isPortInUseFailure()) {
                notifyServerPortInUse(ServerStartNotificationService.SimpleSharing, port)
            } else {
                notifyServerStartFailure(ServerStartNotificationService.SimpleSharing, port, e)
            }
        }
    }

    actual override suspend fun stop() {
        withContext(Dispatchers.IO) {
            stopTransportProxies()
            runCatching { httpsServer?.stop() }
            runCatching { httpServer?.stop() }
            httpsServer = null
            httpServer = null
            fileShareState.updateHttpServerRunning(false)
        }
    }

    actual override fun isRunning(): Boolean {
        return httpServer?.isRunning() == true || httpsServer?.isRunning() == true
    }

    override suspend fun downloadLocalFile(
        filePath: String,
        file: FileSimpleInfo?,
        request: LinkShareHttpRequest,
        delivery: LinkShareFileDelivery,
    ): LinkShareHttpResponse {
        val target = File(filePath)
        if (!target.exists()) {
            return LinkShareHttpResponse.text(404, AppStrings.ui_arg0_does_not_exist.format(arg0 = filePath))
        }
        val platformMimeType = runCatching { Files.probeContentType(target.toPath()) }.getOrNull()
        val presentation = resolveLinkShareFilePresentation(
            fileName = target.name,
            requestedDelivery = delivery,
            fileMimeTypeHint = file?.mineType,
            platformMimeTypeHint = platformMimeType,
        )
        return respondDownload(
            fileSize = target.length(),
            presentation = presentation,
            notFoundMessage = AppStrings.ui_arg0_does_not_exist.format(arg0 = filePath),
            rangeHeader = request.header("Range"),
            inputProvider = { target.inputStream() }
        )
    }

    private fun respondDownload(
        fileSize: Long,
        presentation: LinkShareFilePresentation,
        notFoundMessage: String,
        rangeHeader: String?,
        inputProvider: () -> InputStream?,
    ): LinkShareHttpResponse {
        val input = runCatching { inputProvider() }
            .onFailure { error -> LogKit.w(AppStrings.ui_failed_open_download_stream_arg0.format(arg0 = (error.message).toString())) }
            .getOrNull()
            ?: return LinkShareHttpResponse.text(404, notFoundMessage)

        val headers = presentation.headers.toMutableList()
        val range = parseRangeHeader(rangeHeader, fileSize)
        val statusCode: Int
        val start: Long
        val length: Long?
        if (range != null) {
            statusCode = 206
            start = range.first
            length = range.last - range.first + 1
            headers += LinkShareHttpHeader("Content-Range", "bytes ${range.first}-${range.last}/$fileSize")
            headers += LinkShareHttpHeader("Content-Length", length.toString())
        } else {
            statusCode = 200
            start = 0L
            length = fileSize.takeIf { item -> item >= 0L }
            if (length != null) headers += LinkShareHttpHeader("Content-Length", length.toString())
        }

        return LinkShareHttpResponse.stream(
            statusCode = statusCode,
            contentLength = length,
            contentType = presentation.contentType,
            headers = headers,
        ) {
            input.use { stream ->
                if (start > 0L) stream.skipFully(start)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = length
                while (remaining == null || remaining > 0L) {
                    val readLength = remaining?.let { item -> minOf(buffer.size.toLong(), item).toInt() } ?: buffer.size
                    val read = stream.read(buffer, 0, readLength)
                    if (read <= 0) break
                    write(buffer, 0, read)
                    remaining = remaining?.minus(read.toLong())
                }
            }
        }
    }

    private suspend fun stopAfterStartFailure() {
        stopTransportProxies()
        runCatching { httpsServer?.stop() }
        runCatching { httpServer?.stop() }
        httpsServer = null
        httpServer = null
    }

    private suspend fun stopTransportProxies() {
        runCatching { schemeSwitchingHttpProxy.stop() }
    }

    private fun allocateInternalServerPort(vararg reservedPorts: Int): Int {
        var candidate: Int
        do {
            candidate = ServerSocket(0).use { socket -> socket.localPort }
        } while (reservedPorts.contains(candidate))
        return candidate
    }
}

internal fun parseRangeHeader(rangeHeader: String?, fileSize: Long): LongRange? {
    if (rangeHeader == null || !rangeHeader.startsWith("bytes=") || fileSize <= 0) return null
    val rangeParts = rangeHeader.substring(6).split("-", limit = 2)
    if (rangeParts.size != 2) return null
    val start = rangeParts[0].toLongOrNull() ?: return null
    val end = if (rangeParts[1].isNotEmpty()) rangeParts[1].toLongOrNull() ?: return null else fileSize - 1
    if (start < 0 || end >= fileSize || start > end) return null
    return start..end
}

internal fun InputStream.skipFully(target: Long) {
    var remaining = target
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped <= 0) {
            if (read() == -1) break
            remaining--
        } else {
            remaining -= skipped
        }
    }
}

package com.folderspan.service.webrtc.download

import com.folderspan.service.webrtc.controller.core.IncomingFileChunkWriter
import strings.AppStrings

private const val WEB_RTC_BROWSER_ZIP_PENDING_CHUNK_LIMIT = 256
private const val WEB_RTC_BROWSER_ZIP_PENDING_BYTE_LIMIT = 32L * 1024L * 1024L
private const val WEB_RTC_BROWSER_ZIP_PENDING_OFFSET_WINDOW = 64L * 1024L * 1024L

interface WebRtcBrowserDownloadProvider {
    fun isSecureContext(): Boolean

    fun createFileWriter(fileName: String, mimeType: String?): IncomingFileChunkWriter?

    suspend fun createZipSession(fileName: String, rootName: String, fileCount: Int): WebRtcBrowserZipSession?
}

object WebRtcBrowserDownloadRegistry {
    private var provider: WebRtcBrowserDownloadProvider? = null

    fun install(provider: WebRtcBrowserDownloadProvider) {
        this.provider = provider
    }

    fun createFileWriter(fileName: String, mimeType: String?): IncomingFileChunkWriter? =
        provider?.createFileWriter(fileName, mimeType)

    fun isSecureContext(): Boolean =
        provider?.isSecureContext() == true

    suspend fun createZipSession(fileName: String, rootName: String, fileCount: Int): WebRtcBrowserZipSession? =
        provider?.createZipSession(fileName, rootName, fileCount)
}

interface WebRtcBrowserZipSession {
    suspend fun registerDirectory(relativePath: String): Result<Boolean>

    suspend fun beginFile(relativePath: String): Result<Boolean>

    suspend fun writeFileChunk(fileKey: String, bytes: ByteArray, isLast: Boolean): Result<Boolean>

    suspend fun finalize(): Result<Boolean>

    fun abort(reason: String)
}

internal fun webRtcBrowserZipExpectedEntryCount(directoryCount: Int, fileCount: Int): Int =
    (directoryCount + fileCount + 1).coerceAtLeast(1)

internal fun isAllowedWebRtcBrowserDownloadContext(
    protocol: String,
    hostname: String,
    isSecureContext: Boolean,
): Boolean {
    if (!isSecureContext) return false
    val normalizedProtocol = protocol.trim().lowercase()
    return normalizedProtocol == "https:" || normalizedProtocol == "http:" && hostname.isLocalBrowserDownloadHost()
}

private fun String.isLocalBrowserDownloadHost(): Boolean {
    val normalized = trim().trim('[', ']').lowercase()
    return normalized == "localhost" ||
        normalized == "127.0.0.1" ||
        normalized == "::1" ||
        normalized.endsWith(".localhost")
}

class WebRtcBrowserZipFileWriter(
    private val session: WebRtcBrowserZipSession,
    private val relativePath: String,
) : IncomingFileChunkWriter {
    private val pendingChunks = mutableMapOf<Long, ByteArray>()
    private var pendingBytes = 0L
    private var nextOffset = 0L
    private var begun = false
    private var completed = false

    override suspend fun writeChunk(path: String, fileSize: Long, data: ByteArray, offset: Long): Result<Boolean> {
        if (completed) return Result.success(true)
        if (!begun) {
            val beginResult = session.beginFile(relativePath)
            if (beginResult.isFailure || !beginResult.getOrDefault(false)) return beginResult
            begun = true
        }
        if (offset < nextOffset) {
            return Result.success(true)
        }
        if (offset == nextOffset) {
            val next = nextOffset + data.size.toLong()
            val result = session.writeFileChunk(
                fileKey = relativePath,
                bytes = data,
                isLast = next >= fileSize,
            )
            if (result.isFailure || !result.getOrDefault(false)) {
                return result
            }
            nextOffset = next
            if (nextOffset >= fileSize) {
                completed = true
                pendingChunks.clear()
                pendingBytes = 0L
                return Result.success(true)
            }
            return flushAvailable(fileSize)
        }
        validatePendingChunkWindow(data, offset)?.let { error ->
            pendingChunks.clear()
            pendingBytes = 0L
            return Result.failure(error)
        }
        pendingChunks[offset]?.let { previous ->
            pendingBytes -= previous.size.toLong()
        }
        pendingChunks[offset] = data.copyOf()
        pendingBytes += data.size.toLong()
        return flushAvailable(fileSize)
    }

    override suspend fun commit(path: String, fileSize: Long): Result<Boolean> {
        if (completed) return Result.success(true)
        if (!begun) {
            val beginResult = session.beginFile(relativePath)
            if (beginResult.isFailure || !beginResult.getOrDefault(false)) return beginResult
            begun = true
        }
        val flushResult = flushAvailable(fileSize)
        if (flushResult.isFailure || !flushResult.getOrDefault(false)) return flushResult
        if (nextOffset < fileSize) {
            return Result.failure(IllegalStateException(AppStrings.ui_zip_file_split_missing_arg0.format(arg0 = (relativePath))))
        }
        completed = true
        return session.writeFileChunk(
            fileKey = relativePath,
            bytes = ByteArray(0),
            isLast = true,
        )
    }

    override fun cleanup(path: String) {
        pendingChunks.clear()
        pendingBytes = 0L
    }

    private suspend fun flushAvailable(fileSize: Long): Result<Boolean> {
        while (true) {
            val bytes = pendingChunks.remove(nextOffset) ?: return Result.success(true)
            pendingBytes -= bytes.size.toLong()
            val next = nextOffset + bytes.size.toLong()
            val isLast = next >= fileSize
            val result = session.writeFileChunk(
                fileKey = relativePath,
                bytes = bytes,
                isLast = isLast,
            )
            if (result.isFailure || !result.getOrDefault(false)) {
                pendingChunks[nextOffset] = bytes
                pendingBytes += bytes.size.toLong()
                return result
            }
            nextOffset = next
            if (isLast) {
                completed = true
                pendingChunks.clear()
                pendingBytes = 0L
                return Result.success(true)
            }
        }
    }

    private fun validatePendingChunkWindow(data: ByteArray, offset: Long): IllegalStateException? {
        if (offset - nextOffset > WEB_RTC_BROWSER_ZIP_PENDING_OFFSET_WINDOW) {
            return IllegalStateException(AppStrings.ui_zip_file_chunk_caching_window_exceeds_limit_arg0.format(arg0 = (relativePath)))
        }
        val previousSize = pendingChunks[offset]?.size?.toLong() ?: 0L
        val nextPendingBytes = pendingBytes - previousSize + data.size.toLong()
        if (pendingChunks.size >= WEB_RTC_BROWSER_ZIP_PENDING_CHUNK_LIMIT && !pendingChunks.containsKey(offset)) {
            return IllegalStateException(AppStrings.ui_zip_file_chunk_caching_window_exceeds_limit_arg0.format(arg0 = (relativePath)))
        }
        if (nextPendingBytes > WEB_RTC_BROWSER_ZIP_PENDING_BYTE_LIMIT) {
            return IllegalStateException(AppStrings.ui_zip_file_chunk_caching_window_exceeds_limit_arg0.format(arg0 = (relativePath)))
        }
        return null
    }
}

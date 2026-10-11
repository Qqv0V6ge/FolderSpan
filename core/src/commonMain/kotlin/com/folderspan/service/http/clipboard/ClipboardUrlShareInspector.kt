package com.folderspan.service.http.clipboard

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponseBodyWriter
import com.folderspan.ui.state.main.TaskRuntimeStoreLock
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock

sealed interface ClipboardUrlShareInspectionResult {
    data class Success(
        val file: FileSimpleInfo,
        val release: () -> Unit,
    ) : ClipboardUrlShareInspectionResult

    data class Failure(val error: ClipboardUrlDownloadError) : ClipboardUrlShareInspectionResult
}

fun interface ClipboardUrlShareInspector {
    suspend fun inspect(url: String): ClipboardUrlShareInspectionResult
}

class DefaultClipboardUrlShareInspector(
    private val clientProvider: () -> HttpClient = { ClipboardUrlHttpClientFactory().create() },
    private val policy: ClipboardUrlDownloadPolicy = ClipboardUrlDownloadPolicy(),
    private val capabilities: ClipboardUrlDownloadPlatformCapabilities = clipboardUrlDownloadPlatformCapabilities,
) : ClipboardUrlShareInspector {
    override suspend fun inspect(url: String): ClipboardUrlShareInspectionResult {
        if (!url.isSafeClipboardHttpUrl()) {
            return ClipboardUrlShareInspectionResult.Failure(ClipboardUrlDownloadError.InvalidUrl)
        }
        val config = url.toMetadataRequestConfig(capabilities)
        val client = clientProvider()
        return try {
            val metadata = withTimeout(policy.totalTaskTimeoutMillis) {
                executeClipboardUrlGet(
                    client = client,
                    config = config,
                    policy = policy,
                    range = 0L..0L,
                ) { response, finalUrl ->
                    val headers = response.headers.entries().associate { (name, values) ->
                        name to values.joinToString(", ")
                    }
                    val classification = classifyClipboardUrlResponse(
                        status = response.status.value,
                        headers = headers,
                        finalUrl = finalUrl,
                    )
                    classification.kind.toInspectionError()?.let { error ->
                        throw ClipboardUrlShareInspectionException(error)
                    }
                    val contentRange = parseClipboardContentRange(response.headers[HttpHeaders.ContentRange])
                    val totalBytes = when (response.status.value) {
                        206 -> contentRange
                            ?.takeIf { range -> range.first == 0L && range.last == 0L }
                            ?.total
                            ?: throw ClipboardUrlShareInspectionException(ClipboardUrlDownloadError.RangeRejected)

                        else -> classification.contentLength
                    } ?: throw ClipboardUrlShareInspectionException(ClipboardUrlDownloadError.MissingFileSize)
                    if (totalBytes < 0L) {
                        throw ClipboardUrlShareInspectionException(ClipboardUrlDownloadError.MissingFileSize)
                    }
                    ClipboardUrlShareMetadata(
                        displayName = resolveClipboardDownloadFileName(
                            response.headers[HttpHeaders.ContentDisposition],
                            finalUrl,
                            classification.contentType,
                        ),
                        contentType = classification.contentType,
                        totalBytes = totalBytes,
                    )
                }
            }
            ClipboardUrlShareSourceRegistry.register(
                requestUrl = url,
                metadata = metadata,
                clientProvider = clientProvider,
                policy = policy,
                capabilities = capabilities,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            ClipboardUrlShareInspectionResult.Failure(
                (error as? ClipboardUrlShareInspectionException)?.error
                    ?: error.toClipboardDownloadError()
            )
        } finally {
            config.clearSensitive()
            client.close()
        }
    }
}

internal data class ClipboardUrlShareMetadata(
    val displayName: String,
    val contentType: String?,
    val totalBytes: Long,
)

internal class ClipboardUrlShareSource(
    private val requestUrl: String,
    val totalBytes: Long,
    private val clientProvider: () -> HttpClient,
    private val policy: ClipboardUrlDownloadPolicy,
    private val capabilities: ClipboardUrlDownloadPlatformCapabilities,
) {
    suspend fun streamRange(
        startOffset: Long,
        endOffsetExclusive: Long,
        writer: LinkShareHttpResponseBodyWriter,
    ) {
        require(startOffset >= 0L && endOffsetExclusive > startOffset && endOffsetExclusive <= totalBytes)
        val requestedRange = startOffset..(endOffsetExclusive - 1L)
        val config = requestUrl.toMetadataRequestConfig(capabilities)
        val client = clientProvider()
        try {
            withTimeout(policy.totalTaskTimeoutMillis) {
                executeClipboardUrlGet(
                    client = client,
                    config = config,
                    policy = policy,
                    range = requestedRange,
                ) { response, finalUrl ->
                    validateStreamResponse(
                        status = response.status.value,
                        headers = response.headers.entries().associate { (name, values) ->
                            name to values.joinToString(", ")
                        },
                        contentRangeHeader = response.headers[HttpHeaders.ContentRange],
                        finalUrl = finalUrl,
                        requestedRange = requestedRange,
                    )
                    copyResponseRange(
                        responseStartOffset = if (response.status.value == 206) startOffset else 0L,
                        requestedRange = requestedRange,
                        writer = writer,
                        channel = response.bodyAsChannel(),
                        chunkSize = policy.chunkSize,
                    )
                }
            }
            writer.flush()
        } finally {
            config.clearSensitive()
            client.close()
        }
    }

    private fun validateStreamResponse(
        status: Int,
        headers: Map<String, String>,
        contentRangeHeader: String?,
        finalUrl: String,
        requestedRange: LongRange,
    ) {
        val classification = classifyClipboardUrlResponse(
            status = status,
            headers = headers,
            finalUrl = finalUrl,
        )
        classification.kind.toInspectionError()?.let { error ->
            throw ClipboardUrlShareInspectionException(error)
        }
        when (status) {
            206 -> {
                val actual = parseClipboardContentRange(contentRangeHeader)
                    ?: throw ClipboardUrlShareInspectionException(ClipboardUrlDownloadError.RangeRejected)
                if (
                    actual.first != requestedRange.first ||
                    actual.last != requestedRange.last ||
                    actual.total != totalBytes
                ) {
                    throw ClipboardUrlShareInspectionException(ClipboardUrlDownloadError.ResponseChanged)
                }
            }

            200 -> if (classification.contentLength != totalBytes) {
                throw ClipboardUrlShareInspectionException(ClipboardUrlDownloadError.ResponseChanged)
            }

            else -> throw ClipboardUrlShareInspectionException(ClipboardUrlDownloadError.HttpFailure)
        }
    }
}

internal object ClipboardUrlShareSourceRegistry {
    private val lock = TaskRuntimeStoreLock()
    private val sources = mutableMapOf<String, ClipboardUrlShareSource>()
    private var nextId = 0L

    internal fun register(
        requestUrl: String,
        metadata: ClipboardUrlShareMetadata,
        clientProvider: () -> HttpClient,
        policy: ClipboardUrlDownloadPolicy,
        capabilities: ClipboardUrlDownloadPlatformCapabilities,
    ): ClipboardUrlShareInspectionResult.Success = withLock {
        nextId++
        val path = "/.folderspan-url-share/$nextId/${metadata.displayName}"
        val now = Clock.System.now().toEpochMilliseconds()
        val file = FileSimpleInfo(
            name = metadata.displayName,
            isDirectory = false,
            isHidden = false,
            path = path,
            mineType = metadata.contentType.orEmpty(),
            size = metadata.totalBytes,
            createdDate = now,
            updatedDate = now,
        )
        sources[path] = ClipboardUrlShareSource(
            requestUrl = requestUrl,
            totalBytes = metadata.totalBytes,
            clientProvider = clientProvider,
            policy = policy,
            capabilities = capabilities,
        )
        ClipboardUrlShareInspectionResult.Success(
            file = file,
            release = { release(path) },
        )
    }

    internal fun find(path: String): ClipboardUrlShareSource? = withLock { sources[path] }

    internal fun release(path: String) {
        withLock { sources.remove(path) }
    }

    internal fun releaseAll() {
        withLock { sources.clear() }
    }

    private inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

private suspend fun copyResponseRange(
    responseStartOffset: Long,
    requestedRange: LongRange,
    writer: LinkShareHttpResponseBodyWriter,
    channel: ByteReadChannel,
    chunkSize: Int,
) {
    val buffer = ByteArray(chunkSize.coerceAtLeast(1))
    var responseOffset = responseStartOffset
    var written = 0L
    val expected = requestedRange.last - requestedRange.first + 1L
    while (written < expected) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) throw ClipboardUrlShareInspectionException(ClipboardUrlDownloadError.TransportInterrupted)
        if (read == 0) continue
        val chunkStart = responseOffset
        val chunkEndExclusive = responseOffset + read
        val writeStart = maxOf(chunkStart, requestedRange.first)
        val writeEndExclusive = minOf(chunkEndExclusive, requestedRange.last + 1L)
        if (writeEndExclusive > writeStart) {
            val offset = (writeStart - chunkStart).toInt()
            val length = minOf((writeEndExclusive - writeStart).toInt(), (expected - written).toInt())
            writer.write(buffer, offset, length)
            written += length
        }
        responseOffset = chunkEndExclusive
    }
}

private fun String.toMetadataRequestConfig(
    capabilities: ClipboardUrlDownloadPlatformCapabilities,
): ClipboardUrlDownloadTaskConfig = ClipboardUrlDownloadTaskConfig(
    url = this,
    rawText = "",
    retries = 0,
    cookie = "",
    userAgent = if (capabilities.canSetUserAgent) {
        defaultClipboardUrlUserAgent(capabilities = capabilities)
    } else {
        ""
    },
    automaticHeaders = capabilities.canControlAutomaticHeaders,
    threads = 1,
    capabilities = capabilities,
)

private fun String.isSafeClipboardHttpUrl(): Boolean {
    val parsed = runCatching { Url(this) }.getOrNull() ?: return false
    return parsed.protocol.name in setOf("http", "https") &&
        parsed.host.isNotBlank() &&
        parsed.user == null &&
        parsed.password == null
}

private fun ClipboardUrlResponseKind.toInspectionError(): ClipboardUrlDownloadError? = when (this) {
    ClipboardUrlResponseKind.Downloadable -> null
    ClipboardUrlResponseKind.AuthenticationRequired -> ClipboardUrlDownloadError.AuthenticationRequired
    ClipboardUrlResponseKind.Html -> ClipboardUrlDownloadError.HtmlContent
    ClipboardUrlResponseKind.MissingFileIdentity -> ClipboardUrlDownloadError.MissingFileIdentity
    ClipboardUrlResponseKind.Redirect -> ClipboardUrlDownloadError.RedirectRejected
    ClipboardUrlResponseKind.RetryableFailure,
    ClipboardUrlResponseKind.HttpFailure -> ClipboardUrlDownloadError.HttpFailure
}

private class ClipboardUrlShareInspectionException(
    val error: ClipboardUrlDownloadError,
) : Exception()

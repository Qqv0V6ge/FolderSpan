package com.folderspan.service.http.clipboard

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.random.Random
import strings.AppStrings

sealed interface ClipboardUrlDownloadResult {
    data class Success(val staged: ClipboardStagedDownload) : ClipboardUrlDownloadResult
    data class Failure(
        val error: ClipboardUrlDownloadError,
        val rawText: String,
        val safeReason: String,
    ) : ClipboardUrlDownloadResult
}

interface ClipboardUrlDownloader {
    suspend fun download(
        config: ClipboardUrlDownloadTaskConfig,
        onProgress: suspend (ClipboardUrlDownloadProgress) -> Unit = {},
        onRetry: suspend (attempt: Int, delayMillis: Long) -> Unit = { _, _ -> },
    ): ClipboardUrlDownloadResult
}

class ClipboardUrlDownloadEngine(
    private val clientProvider: () -> HttpClient = { ClipboardUrlHttpClientFactory().create() },
    private val stagingFactory: ClipboardDownloadStagingFactory = createPlatformClipboardDownloadStagingFactory(),
    private val policy: ClipboardUrlDownloadPolicy = ClipboardUrlDownloadPolicy(),
    private val retryDelay: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val jitter: () -> Double = { Random.nextDouble() },
) : ClipboardUrlDownloader {
    override suspend fun download(
        config: ClipboardUrlDownloadTaskConfig,
        onProgress: suspend (ClipboardUrlDownloadProgress) -> Unit,
        onRetry: suspend (attempt: Int, delayMillis: Long) -> Unit,
    ): ClipboardUrlDownloadResult {
        val client = clientProvider()
        var staging: ClipboardDownloadStagingBatch? = null
        return try {
            withTimeout(policy.totalTaskTimeoutMillis) {
                staging = stagingFactory.create()
                val counter = DownloadProgressCounter()
                val prepared = if (config.threads > 1) {
                    try {
                        downloadRanges(client, config, requireNotNull(staging), counter, onProgress, onRetry)
                    } catch (_: ClipboardRangeFallbackException) {
                        requireNotNull(staging).cleanup()
                        staging = stagingFactory.create()
                        counter.clear()
                        downloadSingle(
                            client = client,
                            config = config,
                            staging = requireNotNull(staging),
                            counter = counter,
                            fellBackToSingleThread = true,
                            onProgress = onProgress,
                            onRetry = onRetry,
                        )
                    }
                } else {
                    downloadSingle(client, config, requireNotNull(staging), counter, false, onProgress, onRetry)
                }
                val published = requireNotNull(staging).publish(
                    partIndices = prepared.partIndices,
                    displayName = prepared.displayName,
                    contentType = prepared.contentType,
                    expectedBytes = prepared.totalBytes,
                )
                staging = null
                ClipboardUrlDownloadResult.Success(published)
            }
        } catch (error: Throwable) {
            staging?.cleanup()
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            val category = error.toClipboardDownloadError()
            ClipboardUrlDownloadResult.Failure(category, config.rawText, category.safeReason())
        } finally {
            config.clearSensitive()
            client.close()
        }
    }

    private suspend fun downloadSingle(
        client: HttpClient,
        config: ClipboardUrlDownloadTaskConfig,
        staging: ClipboardDownloadStagingBatch,
        counter: DownloadProgressCounter,
        fellBackToSingleThread: Boolean,
        onProgress: suspend (ClipboardUrlDownloadProgress) -> Unit,
        onRetry: suspend (Int, Long) -> Unit,
    ): PreparedDownload = requestWithRetries(config, onRetry) { attempt ->
        staging.resetPart(SINGLE_PART_INDEX)
        counter.reset(SINGLE_PART_INDEX)
        executeGet(client, config) { response, finalUrl ->
            val metadata = requireDownloadableResponse(response, finalUrl)
            streamResponse(
                response = response,
                staging = staging,
                partIndex = SINGLE_PART_INDEX,
                expectedBytes = metadata.totalBytes,
                counter = counter,
                progress = ClipboardUrlDownloadProgress(
                    bytesReceived = 0L,
                    totalBytes = metadata.totalBytes,
                    attempt = attempt,
                    maxAttempts = config.retries + 1,
                    fellBackToSingleThread = fellBackToSingleThread,
                ),
                onProgress = onProgress,
            )
            val actualBytes = staging.partSize(SINGLE_PART_INDEX)
            if (actualBytes != metadata.totalBytes) {
                throw ClipboardTransportInterruptedException()
            }
            PreparedDownload(
                partIndices = listOf(SINGLE_PART_INDEX),
                displayName = metadata.displayName,
                contentType = metadata.contentType,
                totalBytes = actualBytes,
            )
        }
    }

    private suspend fun downloadRanges(
        client: HttpClient,
        config: ClipboardUrlDownloadTaskConfig,
        staging: ClipboardDownloadStagingBatch,
        counter: DownloadProgressCounter,
        onProgress: suspend (ClipboardUrlDownloadProgress) -> Unit,
        onRetry: suspend (Int, Long) -> Unit,
    ): PreparedDownload {
        val probe = executeGet(client, config, range = 0L..0L) { response, finalUrl ->
            if (response.status.value == 200) throw ClipboardRangeFallbackException()
            val contentRange = parseClipboardContentRange(response.headers[HttpHeaders.ContentRange])
                ?: throw ClipboardRangeFallbackException()
            if (contentRange.first != 0L || contentRange.last != 0L) throw ClipboardRangeFallbackException()
            val metadata = requireDownloadableResponse(
                response = response,
                finalUrl = finalUrl,
                knownFileSize = contentRange.total,
            )
            val validator = clipboardRangeValidator(
                response.headers[HttpHeaders.ETag],
                response.headers[HttpHeaders.LastModified],
            ) ?: throw ClipboardRangeFallbackException()
            val channel = response.bodyAsChannel()
            val probeByte = ByteArray(1)
            val probeRead = withTimeout(policy.firstByteTimeoutMillis) {
                channel.readAvailable(probeByte, 0, 1)
            }
            if (probeRead != 1) throw ClipboardRangeFallbackException()
            RangeProbe(metadata, contentRange.total, validator)
        }
        val ranges = planClipboardByteRanges(probe.totalBytes, config.threads)
        try {
            coroutineScope {
                ranges.map { range ->
                    async {
                        requestWithRetries(config, onRetry) { attempt ->
                            staging.resetPart(range.index)
                            counter.reset(range.index)
                            executeGet(
                                client = client,
                                config = config,
                                range = range.asLongRange(),
                                ifRange = probe.validator.headerValue,
                            ) { response, _ ->
                                val valid = validateClipboardRangeResponse(
                                    status = response.status.value,
                                    contentRange = response.headers[HttpHeaders.ContentRange],
                                    expected = range,
                                    expectedTotal = probe.totalBytes,
                                    expectedValidator = probe.validator,
                                    etag = response.headers[HttpHeaders.ETag],
                                    lastModified = response.headers[HttpHeaders.LastModified],
                                )
                                if (!valid) throw ClipboardRangeFallbackException()
                                streamResponse(
                                    response = response,
                                    staging = staging,
                                    partIndex = range.index,
                                    expectedBytes = range.length,
                                    counter = counter,
                                    progress = ClipboardUrlDownloadProgress(
                                        bytesReceived = 0L,
                                        totalBytes = probe.totalBytes,
                                        attempt = attempt,
                                        maxAttempts = config.retries + 1,
                                        activeSegments = ranges.size,
                                    ),
                                    onProgress = onProgress,
                                )
                                if (staging.partSize(range.index) != range.length) {
                                    throw ClipboardTransportInterruptedException()
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error is ClipboardRangeFallbackException) throw error
            throw error
        }
        return PreparedDownload(
            partIndices = ranges.map(ClipboardByteRange::index),
            displayName = probe.metadata.displayName,
            contentType = probe.metadata.contentType,
            totalBytes = probe.totalBytes,
        )
    }

    private suspend fun <T> executeGet(
        client: HttpClient,
        config: ClipboardUrlDownloadTaskConfig,
        range: LongRange? = null,
        ifRange: String? = null,
        consume: suspend (HttpResponse, finalUrl: String) -> T,
    ): T = executeClipboardUrlGet(client, config, policy, range, ifRange, consume)

    private fun requireDownloadableResponse(
        response: HttpResponse,
        finalUrl: String,
        knownFileSize: Long? = null,
    ): DownloadMetadata {
        val classification = classifyClipboardUrlResponse(
            status = response.status.value,
            headers = response.headers.entries().associate { (name, values) -> name to values.joinToString(", ") },
            finalUrl = finalUrl,
        )
        when (classification.kind) {
            ClipboardUrlResponseKind.Downloadable -> Unit
            ClipboardUrlResponseKind.RetryableFailure -> throw ClipboardRetryableHttpException(
                response.status.value,
                response.headers[HttpHeaders.RetryAfter],
            )
            ClipboardUrlResponseKind.AuthenticationRequired -> throw ClipboardDownloadException(
                ClipboardUrlDownloadError.AuthenticationRequired
            )
            ClipboardUrlResponseKind.Html -> throw ClipboardDownloadException(ClipboardUrlDownloadError.HtmlContent)
            ClipboardUrlResponseKind.MissingFileIdentity -> throw ClipboardDownloadException(
                ClipboardUrlDownloadError.MissingFileIdentity
            )
            ClipboardUrlResponseKind.Redirect -> throw ClipboardDownloadException(ClipboardUrlDownloadError.RedirectRejected)
            ClipboardUrlResponseKind.HttpFailure -> throw ClipboardDownloadException(ClipboardUrlDownloadError.HttpFailure)
        }
        val totalBytes = knownFileSize ?: classification.contentLength
            ?: throw ClipboardDownloadException(ClipboardUrlDownloadError.MissingFileSize)
        return DownloadMetadata(
            displayName = resolveClipboardDownloadFileName(
                response.headers[HttpHeaders.ContentDisposition],
                finalUrl,
                classification.contentType,
            ),
            contentType = classification.contentType,
            totalBytes = totalBytes,
        )
    }

    private suspend fun streamResponse(
        response: HttpResponse,
        staging: ClipboardDownloadStagingBatch,
        partIndex: Int,
        expectedBytes: Long,
        counter: DownloadProgressCounter,
        progress: ClipboardUrlDownloadProgress,
        onProgress: suspend (ClipboardUrlDownloadProgress) -> Unit,
    ) {
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(policy.chunkSize.coerceAtLeast(1))
        var firstRead = true
        var partBytes = 0L
        while (true) {
            val timeout = if (firstRead) policy.firstByteTimeoutMillis else policy.idleReadTimeoutMillis
            val read = withTimeout(timeout) { channel.readAvailable(buffer, 0, buffer.size) }
            firstRead = false
            if (read < 0) break
            if (read == 0) continue
            if (read.toLong() > expectedBytes - partBytes) {
                throw ClipboardDownloadLengthMismatchException()
            }
            val chunk = buffer.copyOf(read)
            staging.append(partIndex, chunk)
            partBytes += read
            val totalReceived = counter.add(partIndex, read.toLong())
            onProgress(progress.copy(bytesReceived = totalReceived))
        }
        if (partBytes != expectedBytes) throw ClipboardTransportInterruptedException()
    }

    private suspend fun <T> requestWithRetries(
        config: ClipboardUrlDownloadTaskConfig,
        onRetry: suspend (Int, Long) -> Unit,
        action: suspend (attempt: Int) -> T,
    ): T {
        var attempt = 1
        while (true) {
            try {
                return action(attempt)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (error is ClipboardRangeFallbackException) throw error
                val retryable = when (error) {
                    is ClipboardRetryableHttpException -> true
                    is ClipboardTransportInterruptedException,
                    is HttpRequestTimeoutException,
                    is ConnectTimeoutException,
                    is SocketTimeoutException,
                    is TimeoutCancellationException -> true
                    is ClipboardDownloadException,
                    is ClipboardDownloadStagingException,
                    is ClipboardDownloadLengthMismatchException -> false
                    is IOException -> !error.isTlsFailure()
                    else -> false
                }
                if (!retryable || attempt > config.retries) throw error
                val waitMillis = clipboardDownloadRetryDelayMillis(
                    retryIndex = attempt - 1,
                    retryAfter = (error as? ClipboardRetryableHttpException)?.retryAfter,
                    jitterUnit = jitter(),
                    maximumDelayMillis = policy.maximumRetryDelayMillis,
                )
                onRetry(attempt + 1, waitMillis)
                retryDelay(waitMillis)
                attempt++
            }
        }
    }
}

internal suspend fun <T> executeClipboardUrlGet(
    client: HttpClient,
    config: ClipboardUrlDownloadTaskConfig,
    policy: ClipboardUrlDownloadPolicy,
    range: LongRange? = null,
    ifRange: String? = null,
    consume: suspend (HttpResponse, finalUrl: String) -> T,
): T {
    var currentUrl = config.url
    var redirects = 0
    while (true) {
        var redirectLocation: String? = null
        var consumed: T? = null
        client.prepareGet(currentUrl) {
            val requestHeaders = buildClipboardUrlRequestHeaders(config, currentUrl, range = range, ifRange = ifRange)
            headers {
                requestHeaders.values.forEach { (name, value) -> append(name, value) }
            }
        }.execute { response ->
            val status = response.status.value
            if (!config.capabilities.isBrowser && status in 300..399) {
                redirectLocation = response.headers[HttpHeaders.Location]
            } else {
                val finalUrl = response.call.request.url.toString()
                val parsedFinal = runCatching { Url(finalUrl) }.getOrNull()
                if (
                    parsedFinal == null ||
                    parsedFinal.protocol.name !in setOf("http", "https") ||
                    parsedFinal.user != null ||
                    parsedFinal.password != null
                ) {
                    throw ClipboardDownloadException(ClipboardUrlDownloadError.RedirectRejected)
                }
                consumed = consume(response, finalUrl)
            }
        }
        consumed?.let { return it }
        if (redirects >= policy.maximumRedirects) {
            throw ClipboardDownloadException(ClipboardUrlDownloadError.RedirectLimit)
        }
        val next = redirectLocation?.let { location -> validateClipboardRedirect(currentUrl, location) }
            ?: throw ClipboardDownloadException(ClipboardUrlDownloadError.RedirectRejected)
        currentUrl = next
        redirects++
    }
}

private data class DownloadMetadata(
    val displayName: String,
    val contentType: String?,
    val totalBytes: Long,
)

private data class RangeProbe(
    val metadata: DownloadMetadata,
    val totalBytes: Long,
    val validator: ClipboardRangeValidator,
)

private data class PreparedDownload(
    val partIndices: List<Int>,
    val displayName: String,
    val contentType: String?,
    val totalBytes: Long,
)

private class DownloadProgressCounter {
    private val mutex = Mutex()
    private val segmentBytes = mutableMapOf<Int, Long>()

    suspend fun add(index: Int, bytes: Long): Long = mutex.withLock {
        segmentBytes[index] = (segmentBytes[index] ?: 0L) + bytes
        segmentBytes.values.sum()
    }

    suspend fun reset(index: Int) = mutex.withLock { segmentBytes.remove(index) }

    suspend fun clear() = mutex.withLock { segmentBytes.clear() }
}

private open class ClipboardDownloadException(val error: ClipboardUrlDownloadError) : Exception()
private class ClipboardRetryableHttpException(val status: Int, val retryAfter: String?) : Exception()
private class ClipboardTransportInterruptedException : Exception()
private class ClipboardRangeFallbackException : Exception()

internal fun Throwable.toClipboardDownloadError(): ClipboardUrlDownloadError = when (this) {
    is ClipboardDownloadException -> error
    is ClipboardDownloadStagingException -> ClipboardUrlDownloadError.StagingFailure
    is ClipboardDownloadLengthMismatchException -> ClipboardUrlDownloadError.ResponseChanged
    is ClipboardTransportInterruptedException -> ClipboardUrlDownloadError.TransportInterrupted
    is ClipboardRetryableHttpException -> ClipboardUrlDownloadError.HttpFailure
    is HttpRequestTimeoutException,
    is ConnectTimeoutException,
    is SocketTimeoutException,
    is TimeoutCancellationException -> ClipboardUrlDownloadError.ReadTimeout
    else -> if (isTlsFailure()) ClipboardUrlDownloadError.TlsFailure else ClipboardUrlDownloadError.TransportInterrupted
}

private fun Throwable.isTlsFailure(): Boolean {
    val typeName = this::class.simpleName.orEmpty().lowercase()
    return typeName.contains("ssl") || typeName.contains("tls") || typeName.contains("certificate")
}

private fun ClipboardUrlDownloadError.safeReason(): String = when (this) {
    ClipboardUrlDownloadError.AuthenticationRequired -> AppStrings.ui_server_requires_authentication
    ClipboardUrlDownloadError.HtmlContent -> AppStrings.ui_the_target_returns_the_webpage_content_and_does_not_save_it_as_a_file
    ClipboardUrlDownloadError.MissingFileIdentity -> AppStrings.ui_response_missing_file_information
    ClipboardUrlDownloadError.MissingFileSize -> AppStrings.ui_the_server_does_not_provide_the_total_file_size
    ClipboardUrlDownloadError.RedirectLimit,
    ClipboardUrlDownloadError.RedirectRejected -> AppStrings.ui_redirecting_to_a_location_that_does_not_comply_with_security_rules
    ClipboardUrlDownloadError.ReadTimeout -> AppStrings.ui_download_waiting_timeout
    ClipboardUrlDownloadError.TlsFailure -> AppStrings.ui_security_connection_failed
    ClipboardUrlDownloadError.ResponseChanged,
    ClipboardUrlDownloadError.RangeRejected -> AppStrings.ui_download_process_has_changed_server_content
    ClipboardUrlDownloadError.BrowserPolicy -> AppStrings.ui_the_request_is_restricted_by_the_browsers_security_policy
    ClipboardUrlDownloadError.Cancelled -> AppStrings.ui_download_canceled
    ClipboardUrlDownloadError.InvalidSettings,
    ClipboardUrlDownloadError.InvalidUrl -> AppStrings.ui_download_settings_or_address_invalid
    ClipboardUrlDownloadError.HttpFailure -> AppStrings.ui_the_server_rejected_the_download_request
    ClipboardUrlDownloadError.TransportInterrupted -> AppStrings.ui_network_transmission_interruption
    ClipboardUrlDownloadError.StagingFailure -> AppStrings.ui_unable_to_save_the_downloaded_content
    ClipboardUrlDownloadError.Unknown -> AppStrings.ui_download_failed
}

private const val SINGLE_PART_INDEX = 0

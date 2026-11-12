package com.folderspan.service.network

import com.folderspan.service.http.client.createNoProxyHttpClient
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*
import kotlinx.serialization.SerializationException

internal class StorageApiException(val status: Int) : Exception("Storage service request failed (HTTP $status)")

private const val STORAGE_READ_ATTEMPTS = 3
private val STORAGE_READ_BACKOFF_MS = longArrayOf(250L, 500L)
private val STORAGE_READ_TRANSPORT_FAILURES = setOf(
    "EOFException", "IOException", "SSLException", "SSLHandshakeException", "SocketException",
    "ConnectException", "ConnectTimeoutException", "HttpRequestTimeoutException",
)
private val STORAGE_DOWNLOAD_HTTP_FAILURE = Regex("""Storage download failed \(HTTP (\d+)\)""")

private fun Throwable.isRetryableStorageReadFailure(): Boolean = generateSequence(this) { it.cause }.any { error ->
    when {
        error is StorageApiException -> error.status == 429 || error.status in 500..599
        error::class.simpleName in STORAGE_READ_TRANSPORT_FAILURES -> true
        else -> STORAGE_DOWNLOAD_HTTP_FAILURE.find(error.message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { it == 429 || it in 500..599 } == true
    }
}

internal class StorageHttpApi(
    private val token: suspend () -> String,
    overrideClient: HttpClient? = null,
    val capabilities: () -> StorageCapabilities = { StorageCapabilities.ReadWrite },
    clientFactory: (HttpClientConfig<*>.() -> Unit) -> HttpClient = ::createNoProxyHttpClient,
) {
    val client = overrideClient ?: clientFactory { expectSuccess = false; followRedirects = false }
    private val ownsClient = overrideClient == null
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun bearerHeaders(): Map<String, String> = mapOf(HttpHeaders.Authorization to "Bearer ${token()}")
    fun close() { if (ownsClient) client.close() }

    /** Bounded retry for idempotent cloud reads; write APIs continue to use [request] directly. */
    internal suspend fun <T> retryStorageRead(
        retryIf: () -> Boolean = { true },
        block: suspend () -> T,
    ): T {
        var failure: Exception? = null
        repeat(STORAGE_READ_ATTEMPTS) { attempt ->
            try { return block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (!retryIf() || !error.isRetryableStorageReadFailure() || attempt == STORAGE_READ_ATTEMPTS - 1) throw error
                failure = error
                delay(STORAGE_READ_BACKOFF_MS[attempt])
            }
        }
        throw checkNotNull(failure)
    }

    internal suspend fun readJson(
        method: HttpMethod,
        url: String,
        body: JsonElement? = null,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = retryStorageRead { json(method, url, body, headers) }

    suspend fun <T> request(
        method: HttpMethod,
        url: String,
        body: JsonElement? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        consume: suspend (HttpResponse) -> T,
    ): T {
        val authorization = bearerHeaders()
        return client.prepareRequest(url) {
            this.method = method
            authorization.forEach { (name, value) -> header(name, value) }
            extraHeaders.forEach { (name, value) -> header(name, value) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
        }.execute { response ->
            if (!response.status.isSuccess()) throw StorageApiException(response.status.value)
            consume(response)
        }
    }

    suspend fun json(method: HttpMethod, url: String, body: JsonElement? = null, headers: Map<String, String> = emptyMap()): JsonElement =
        request(method, url, body, headers) { response -> parseStorageJson(response.readStorageBody(8L * 1024 * 1024)) }

    suspend fun empty(method: HttpMethod, url: String, body: JsonElement? = null) =
        request(method, url, body) {}

    suspend fun upload(
        method: HttpMethod,
        url: String,
        size: Long,
        progress: (Long, Long) -> Unit,
        source: suspend () -> ByteArray?,
        headers: Map<String, String> = emptyMap(),
        authenticated: Boolean = true,
        bodyPrefix: ByteArray = byteArrayOf(),
        bodySuffix: ByteArray = byteArrayOf(),
        bodyContentType: ContentType = ContentType.Application.OctetStream,
    ): JsonElement = uploadResponse(method, url, size, progress, source, headers, authenticated, bodyPrefix, bodySuffix, bodyContentType) { response ->
        if (!response.status.isSuccess()) throw StorageApiException(response.status.value)
        val text = response.readStorageBody(8L * 1024 * 1024)
        if (text.isBlank()) JsonNull else parseStorageJson(text)
    }

    suspend fun <T> uploadResponse(
        method: HttpMethod,
        url: String,
        size: Long,
        progress: (Long, Long) -> Unit,
        source: suspend () -> ByteArray?,
        headers: Map<String, String> = emptyMap(),
        authenticated: Boolean = true,
        bodyPrefix: ByteArray = byteArrayOf(),
        bodySuffix: ByteArray = byteArrayOf(),
        bodyContentType: ContentType = ContentType.Application.OctetStream,
        consume: suspend (HttpResponse) -> T,
    ): T = coroutineScope {
        require(size >= 0 && size <= Long.MAX_VALUE - bodyPrefix.size - bodySuffix.size) { "Storage upload requires a valid size" }
        val authorization = if (authenticated) bearerHeaders() else emptyMap()
        val uploadContext = coroutineContext
        fun reportProgress(sent: Long) {
            try {
                progress(sent, size)
            } catch (cancel: CancellationException) {
                // 引擎可能丢失请求体的关闭原因；仅将用户进度回调的取消传给本次上传。
                uploadContext.cancel(cancel)
                throw cancel
            }
        }
        try {
            client.prepareRequest(url) {
                this.method = method
                authorization.forEach { (name, value) -> header(name, value) }
                headers.forEach { (name, value) -> header(name, value) }
                contentType(bodyContentType)
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override val contentLength = size + bodyPrefix.size + bodySuffix.size
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        channel.writeFully(bodyPrefix)
                        var sent = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytes = try {
                                source()
                            } catch (cancel: CancellationException) {
                                if (currentCoroutineContext().isActive) uploadContext.cancel(cancel)
                                throw cancel
                            } ?: break
                            check(bytes.size <= size - sent) { "Storage upload length exceeded" }
                            channel.writeFully(bytes)
                            sent += bytes.size
                            reportProgress(sent)
                        }
                        check(sent == size) { "Storage upload ended early" }
                        channel.writeFully(bodySuffix)
                        reportProgress(sent)
                    }
                })
            }.execute { response ->
                consume(response)
            }
        } catch (failure: Exception) {
            uploadContext.ensureActive()
            throw failure
        }
    }
}

internal fun JsonElement.storageObject(): JsonObject = this as? JsonObject ?: error("Invalid storage metadata")
internal fun JsonObject.storageString(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: error("Storage metadata has no $key")
internal fun JsonObject.storageOptionalString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.storageLong(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun storageTime(value: String?): Long = value?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0L
internal fun storageUrl(base: String, segments: List<String> = emptyList(), parameters: Map<String, String> = emptyMap()): String =
    URLBuilder(base).apply {
        if (segments.isNotEmpty()) appendPathSegments(segments, encodeSlash = true)
        parameters.forEach { (key, value) -> this.parameters.append(key, value) }
    }.buildString()

internal suspend fun collectStoragePages(fetch: suspend (String?) -> Pair<List<CloudStorageNode>, String?>): List<CloudStorageNode> {
    var cursor: String? = null
    val seen = mutableSetOf<String>()
    val result = mutableListOf<CloudStorageNode>()
    repeat(10_000) {
        currentCoroutineContext().ensureActive()
        val (items, next) = fetch(cursor)
        check(items.size <= 500_000 - result.size) { "Storage directory exceeds the entry limit" }
        result += items
        if (next.isNullOrEmpty()) return result
        check(next.length <= 64 * 1024 && seen.add(next)) { "Storage pagination cursor cycle" }
        cursor = next
    }
    error("Storage directory exceeds the page limit")
}

internal suspend fun uploadLocalStorageFile(
    localPath: String,
    size: Long,
    upload: suspend (Long, suspend () -> ByteArray?) -> Result<Boolean>,
): Result<Boolean> {
    val length = if (size >= 0) size else FileUtils.getFile(FileAccessPermission.Allowed, localPath).getOrThrow().size
    var offset = 0L
    return upload(length) {
        if (offset == length) null else {
            val end = offset + minOf(64 * 1024L, length - offset)
            FileUtils.readFileRange(FileAccessPermission.Allowed, localPath, offset, end).getOrThrow().also {
                check(it.size.toLong() == end - offset) { "Local storage source changed while uploading" }
                offset = end
            }
        }
    }
}

internal fun storageHttpApi(config: NetworkConnectionConfig): StorageHttpApi = StorageHttpApi(
    token = { StorageAccounts.token(config) },
    capabilities = { StorageAccounts.capabilities(config) },
    clientFactory = if (config.protocol == "GoogleDrive" || config.protocol == "Dropbox") {
        { clientConfig -> createCloudStorageHttpClient(clientConfig) }
    } else {
        ::createNoProxyHttpClient
    },
)

internal class StorageUploadChunks(
    private val size: Long,
    private val source: suspend () -> ByteArray?,
) {
    private var pending = byteArrayOf()
    private var position = 0
    private var loaded = 0L
    private var ended = false
    suspend fun next(limit: Int): ByteArray? {
        require(limit in 1..16 * 1024 * 1024)
        val output = okio.Buffer()
        while (output.size < limit && !ended) {
            currentCoroutineContext().ensureActive()
            if (position == pending.size) {
                val bytes = source()
                if (bytes == null) {
                    check(loaded == size) { "Storage upload ended early" }
                    ended = true
                    break
                }
                check(bytes.size <= size - loaded) { "Storage upload length exceeded" }
                pending = bytes
                position = 0
                loaded += bytes.size
                if (bytes.isEmpty()) continue
            }
            val count = minOf(limit - output.size.toInt(), pending.size - position)
            output.write(pending, position, count)
            position += count
        }
        return if (output.size == 0L) null else output.readByteArray()
    }
}

internal fun parseStorageJson(text: String): JsonElement = try { Json.parseToJsonElement(text) } catch (_: SerializationException) { error("Invalid storage response") }

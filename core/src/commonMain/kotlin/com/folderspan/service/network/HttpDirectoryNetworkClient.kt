package com.folderspan.service.network

import com.fleeksoft.ksoup.Ksoup
import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.service.http.client.createNoProxyHttpClient
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.prepareRequest
import io.ktor.http.HttpMethod
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.decodeURLPart
import io.ktor.http.takeFrom
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer
import strings.AppStrings

internal suspend inline fun <T> storageResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancel: CancellationException) {
    throw cancel
} catch (error: Exception) {
    Result.failure(error)
}

internal fun sameStorageOrigin(left: Url, right: Url): Boolean =
    left.protocol == right.protocol && left.host.equals(right.host, ignoreCase = true) && left.port == right.port

internal suspend fun HttpResponse.readStorageBody(limit: Long): String {
    require(limit in 1..Int.MAX_VALUE.toLong())
    val input = bodyAsChannel()
    val body = Buffer()
    val bytes = ByteArray(64 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.readAvailable(bytes, 0, bytes.size)
        if (count < 0) break
        if (count == 0) continue
        check(body.size + count <= limit) { "Storage response exceeds the size limit" }
        body.write(bytes, 0, count)
    }
    return body.readUtf8()
}

internal suspend fun HttpClient.readStorageFile(
    target: Url,
    size: Long,
    headers: Map<String, String> = emptyMap(),
    allowRedirect: (Url) -> Boolean = { false },
    method: HttpMethod = HttpMethod.Get,
    onChunk: suspend (ByteArray, Long) -> Result<Unit>,
): Result<Boolean> = storageResult {
    var current = target
    var finished = false
    val visited = mutableSetOf<String>()
    repeat(6) {
        if (finished) return@repeat
        check(visited.add(current.toString())) { "Storage redirect cycle" }
        var redirect: Url? = null
        prepareRequest(current) {
            this.method = method
            // Credentials belong to the original origin, including when a cloud API returns a CDN URL.
            if (sameStorageOrigin(current, target)) headers.forEach { (name, value) -> header(name, value) }
            header(HttpHeaders.AcceptEncoding, "identity")
        }.execute { response ->
            if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                val location = response.headers[HttpHeaders.Location] ?: error("Storage redirect has no destination")
                val next = URLBuilder(current).apply { takeFrom(location) }.build()
                check(next.user.isNullOrEmpty() && next.password.isNullOrEmpty() && allowRedirect(next)) {
                    "Storage redirect leaves the allowed origin or root"
                }
                redirect = next
            } else {
                check(response.status.isSuccess()) { "Storage download failed (HTTP ${response.status.value})" }
                val expected = response.contentLength() ?: size.takeIf { it >= 0 } ?: -1L
                if (size >= 0 && expected >= 0) check(size == expected) { "Storage file size changed" }
                var received = 0L
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    check(expected < 0 || count <= expected - received) { "Storage download length exceeded" }
                    onChunk(buffer.copyOf(count), expected).getOrThrow()
                    received += count
                }
                check(expected < 0 || received == expected) { "Storage download ended early" }
                if (received == 0L) onChunk(byteArrayOf(), 0).getOrThrow()
                finished = true
            }
        }
        if (redirect != null) current = checkNotNull(redirect)
    }
    check(finished) { "Too many storage redirects" }
    true
}

internal suspend fun downloadStorageFile(
    localPath: String,
    onProgress: (Long, Long) -> Unit,
    read: suspend (suspend (ByteArray, Long) -> Result<Unit>) -> Result<Boolean>,
): Result<Boolean> {
    var offset = 0L
    return read { bytes, total ->
        storageResult {
            val length = if (total >= 0) total else offset + bytes.size
            check(FileUtils.writeBytes(FileAccessPermission.Allowed, localPath, length, bytes, offset).getOrThrow()) {
                "Unable to write the downloaded file"
            }
            offset += bytes.size
            onProgress(offset, if (total >= 0) total else offset)
        }
    }
}

internal class HttpDirectoryNetworkClient(
    private val network: NetworkConnectionConfig,
    overrideClient: HttpClient? = null,
) : StorageProvider, ChunkReadableNetworkClient {
    override val capabilities = StorageCapabilities.ReadOnly
    private val ownsClient = overrideClient == null
    private val client = overrideClient ?: createNoProxyHttpClient {
        expectSuccess = false
        followRedirects = false
    }
    private val root = Url(network.host).also {
        require(it.protocol == URLProtocol.HTTP || it.protocol == URLProtocol.HTTPS) { "An HTTP or HTTPS directory URL is required" }
        require(it.user.isNullOrEmpty() && it.password.isNullOrEmpty()) { "Use the connection credential fields" }
        require(it.parameters.isEmpty() && it.fragment.isEmpty()) { "Directory URL cannot contain a query or fragment" }
        require(safeSegments(it) != null) { "Invalid directory root" }
    }
    private val rootSegments = checkNotNull(safeSegments(root))
    private val credentials = if (network.username.isNotEmpty() || network.password.isNotEmpty()) {
        mapOf(HttpHeaders.Authorization to "Basic ${Base64.Default.encode("${network.username}:${network.password}".encodeToByteArray())}")
    } else emptyMap()

    private fun url(path: String, directory: Boolean = false): Url {
        require(!containsUnsafeNetworkPathSegment(path)) { "Invalid remote directory path" }
        val segments = path.trim('/').takeIf { it.isNotEmpty() }?.split('/') ?: emptyList()
        require(segments.none { isUnsafeNetworkPathSegment(it) || it.any(Char::isISOControl) }) { "Invalid remote directory path" }
        return URLBuilder(root).apply {
            pathSegments = listOf("") + rootSegments + segments + if (directory) listOf("") else emptyList()
        }.build()
    }

    private fun allowed(target: Url): Boolean {
        if (!sameStorageOrigin(root, target) || !target.user.isNullOrEmpty() || !target.password.isNullOrEmpty()) return false
        if (!target.parameters.isEmpty() || target.fragment.isNotEmpty()) return false
        val segments = safeSegments(target) ?: return false
        return segments.size >= rootSegments.size && segments.take(rootSegments.size) == rootSegments
    }

    override suspend fun list(path: String, requestId: String?, batchId: String?): Result<List<NetworkFileEntry>> = storageResult {
        val current = url(path, directory = true)
        client.prepareGet(current) {
            credentials.forEach { (name, value) -> header(name, value) }
        }.execute { response ->
            check(response.status.isSuccess()) { "HTTP directory listing failed (HTTP ${response.status.value})" }
            val mediaType = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';').trim()
            check(mediaType.equals("text/html", true) || mediaType.equals("application/xhtml+xml", true)) {
                "The URL did not return an HTML directory"
            }
            parseDirectory(response.readStorageBody(8L * 1024 * 1024), current)
        }
    }

    internal fun parseDirectory(html: String, current: Url): List<NetworkFileEntry> {
        require(allowed(current)) { "Directory leaves the configured root" }
        val currentSegments = checkNotNull(safeSegments(current))
        val document = Ksoup.parse(html = html, baseUri = current.toString())
        val entries = linkedMapOf<String, NetworkFileEntry>()
        val links = document.select("a[href]")
        check(links.size <= 100_000) { "Too many directory links" }
        for (link in links) {
            val href = link.attr("href")
            if (href.isBlank() || href.any(Char::isISOControl) || href.contains('\\')) continue
            val target = runCatching { Url(link.absUrl("href")) }.getOrNull() ?: continue
            if (!allowed(target)) continue
            val segments = safeSegments(target) ?: continue
            if (segments.size != currentSegments.size + 1 || segments.dropLast(1) != currentSegments) continue
            val name = segments.last()
            val relative = segments.drop(rootSegments.size).joinToString("/", prefix = "/")
            val directory = target.encodedPath.endsWith('/')
            if (relative !in entries) entries[relative] = NetworkFileEntry(
                name = name,
                path = relative,
                isDirectory = directory,
                size = -1L,
                isHidden = name.startsWith('.'),
                isSymbolicLinkKnown = true,
            )
        }
        return entries.values.toList()
    }

    override suspend fun downloadByChunks(
        remotePath: String,
        size: Long,
        onChunk: suspend (ByteArray, Long) -> Result<Unit>,
    ): Result<Boolean> = storageResult {
        client.readStorageFile(url(remotePath), size, credentials, ::allowed, onChunk = onChunk).getOrThrow()
    }

    override suspend fun download(remotePath: String, localPath: String, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> =
        downloadStorageFile(localPath, onProgress) { consume -> downloadByChunks(remotePath, size, consume) }

    private fun readOnly(): Result<Boolean> =
        Result.failure(NetworkUnsupportedException(AppStrings.network_protocol_write_not_supported))

    override suspend fun upload(localPath: String, remotePath: String, size: Long, onProgress: (Long, Long) -> Unit) = readOnly()
    override suspend fun uploadFromSource(remotePath: String, size: Long, onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?) = readOnly()
    override suspend fun rename(path: String, newPath: String) = readOnly()
    override suspend fun delete(path: String, isDirectory: Boolean) = readOnly()
    override suspend fun createFolder(path: String) = readOnly()
    override suspend fun createFile(path: String) = readOnly()
    override suspend fun close() { if (ownsClient) client.close() }

    private fun safeSegments(target: Url): List<String>? {
        val parts = target.encodedPath.trim('/').takeIf { it.isNotEmpty() }?.split('/') ?: return emptyList()
        return parts.map { part ->
            val decoded = runCatching { part.decodeURLPart() }.getOrNull() ?: return null
            if (isUnsafeNetworkPathSegment(decoded) || decoded.any(Char::isISOControl)) return null
            decoded
        }
    }
}

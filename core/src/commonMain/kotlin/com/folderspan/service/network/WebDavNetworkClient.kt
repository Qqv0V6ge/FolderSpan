package com.folderspan.service.network

import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.WebDavAuthType
import com.folderspan.data.main.network.WebDavDriveExtras
import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.service.http.client.createNoProxyHttpClient
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import strings.AppStrings
import io.ktor.client.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

private const val WEBDAV_STREAM_BUFFER_SIZE = 64 * 1024
private const val WEBDAV_CHUNK_SIZE = 1024L * 1024L

internal class WebDavNetworkClient(
    private val network: Network,
    overrideClient: HttpClient? = null,
) : NetworkClient, ChunkReadableNetworkClient {
    private val extras = network.extras.webdav
    private val hasCustomAuthorization = extras.headers.keys.any { key ->
        key.trim().equals(HttpHeaders.Authorization, ignoreCase = true)
    }
    private val client: HttpClient = overrideClient ?: createNoProxyHttpClient {
        expectSuccess = false
        followRedirects = false
        if (extras.authType != WebDavAuthType.Token && !hasCustomAuthorization) {
            install(Auth) {
                when (extras.authType) {
                    WebDavAuthType.Basic -> basic {
                        credentials {
                            BasicAuthCredentials(
                                username = network.username,
                                password = network.password
                            )
                        }
                        sendWithoutRequest { true }
                    }

                    WebDavAuthType.Digest -> digest {
                        credentials {
                            DigestAuthCredentials(
                                username = network.username,
                                password = network.password
                            )
                        }
                    }

                    WebDavAuthType.Token -> Unit
                }
            }
        }
    }
    private val baseUrl = network.host.trim()
    private val baseUrlParsed: Url? = runCatching { Url(baseUrl) }.getOrNull()
    private val baseEncodedPath: String = baseUrlParsed?.encodedPath?.trimEnd('/') ?: ""

    override suspend fun list(
        path: String,
        requestId: String?,
        batchId: String?
    ): Result<List<NetworkFileEntry>> {
        val target = ensureDirectoryPath(normalizeRemotePath(path))
        LogKit.i(AppStrings.ui_webdav_list_start_arg0.format(arg0 = (target)))
        val baseUrlValue = baseUrlParsed ?: return Result.failure(IllegalArgumentException(AppStrings.ui_link_address_invalid))
        return try {
            val response = client.request {
                method = HttpMethod("PROPFIND")
                url(buildUrl(baseUrlValue, target))
                headers.append("Depth", "1")
                headers.append(HttpHeaders.ContentType, ContentType.Application.Xml.toString())
                accept(ContentType.Application.Xml)
                applyWebDavHeaders(this)
                setBody(PROPFIND_BODY)
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                LogKit.w(AppStrings.ui_webdav_list_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (body)))
                return Result.failure(Exception(body))
            }
            val xml = response.bodyAsText()
            val entries = parsePropfindResponse(xml, target)
            LogKit.i(AppStrings.ui_webdav_list_arg0_count_arg1.format(arg0 = (target), arg1 = (entries.size).toString()))
            Result.success(entries)
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_webdav_list_failed_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
    }

    override suspend fun download(
        remotePath: String,
        localPath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> {
        val target = normalizeRemotePath(remotePath)
        LogKit.i(AppStrings.ui_webdav_download_start_arg0.format(arg0 = (target)))
        val baseUrlValue = baseUrlParsed ?: return Result.failure(IllegalArgumentException(AppStrings.ui_link_address_invalid))
        return try {
            client.prepareRequest {
                method = HttpMethod.Get
                url(buildUrl(baseUrlValue, target))
                applyWebDavHeaders(this)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    LogKit.w(AppStrings.ui_webdav_download_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (body)))
                    return@execute Result.failure(Exception(body))
                }
                val createResult = withContext(Dispatchers.Default) {
                    FileUtils.createFile(FileAccessPermission.Allowed, localPath)
                }
                if (createResult.isFailure) {
                    return@execute Result.failure(createResult.exceptionOrNull() ?: Exception(AppStrings.ui_create_file_failure))
                }
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(WEBDAV_STREAM_BUFFER_SIZE)
                var offset = 0L
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    val chunk = buffer.copyOf(read)
                    val fileSize = maxOf(size, offset + read)
                    val writeResult = FileUtils.writeBytes(
                        FileAccessPermission.Allowed,
                        localPath,
                        fileSize,
                        chunk,
                        offset,
                    )
                    if (writeResult.isFailure) {
                        return@execute Result.failure(writeResult.exceptionOrNull() ?: Exception(AppStrings.ui_write_failed))
                    }
                    offset += read
                    val total = if (size > 0L) size else offset
                    onProgress(offset, total)
                }
                val total = if (size > 0L) size else offset
                onProgress(offset, total)
                LogKit.i(AppStrings.ui_webdav_download_successful_arg0.format(arg0 = (target)))
                Result.success(true)
            }
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_webdav_download_failed_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
    }

    override suspend fun downloadByChunks(
        remotePath: String,
        size: Long,
        onChunk: suspend (chunk: ByteArray, totalBytes: Long) -> Result<Unit>,
    ): Result<Boolean> {
        val target = normalizeRemotePath(remotePath)
        LogKit.i(AppStrings.ui_webdav_stream_download_arg0.format(arg0 = (target)))
        val baseUrlValue = baseUrlParsed ?: return Result.failure(IllegalArgumentException(AppStrings.ui_link_address_invalid))
        return try {
            client.prepareRequest {
                method = HttpMethod.Get
                url(buildUrl(baseUrlValue, target))
                applyWebDavHeaders(this)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    LogKit.w(AppStrings.ui_webdav_stream_download_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (body)))
                    return@execute Result.failure(Exception(body))
                }
                val totalBytes = response.contentLength()?.takeIf { it > 0L } ?: size.coerceAtLeast(0L)
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(WEBDAV_STREAM_BUFFER_SIZE)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    val chunkResult = onChunk(buffer.copyOf(read), totalBytes)
                    if (chunkResult.isFailure) {
                        return@execute Result.failure(chunkResult.exceptionOrNull() ?: Exception(AppStrings.ui_flow_download_failed))
                    }
                }
                LogKit.i(AppStrings.ui_webdav_stream_download_successful_arg0.format(arg0 = (target)))
                Result.success(true)
            }
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_webdav_stream_download_failed_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
    }

    override suspend fun upload(
        localPath: String,
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> {
        val target = normalizeRemotePath(remotePath)
        LogKit.i(AppStrings.ui_webdav_upload_start_arg0.format(arg0 = (target)))
        val baseUrlValue = baseUrlParsed ?: return Result.failure(IllegalArgumentException(AppStrings.ui_link_address_invalid))
        return try {
            val response = client.request {
                method = HttpMethod.Put
                url(buildUrl(baseUrlValue, target))
                headers.append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                applyWebDavHeaders(this)
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override val contentLength: Long? = size.takeIf { it > 0L }

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        var doneBytes = 0L
                        FileUtils.readFileChunks(
                            FileAccessPermission.Allowed,
                            localPath,
                            WEBDAV_CHUNK_SIZE,
                        ).collect { result ->
                            val chunk = result.getOrElse { error ->
                                throw error
                            }.second
                            if (chunk.isEmpty()) return@collect
                            channel.writeFully(chunk)
                            doneBytes += chunk.size
                            val total = if (size > 0L) size else doneBytes
                            onProgress(doneBytes, total)
                        }
                        val total = if (size > 0L) size else doneBytes
                        onProgress(doneBytes, total)
                    }
                })
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                LogKit.w(AppStrings.ui_webdav_upload_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (body)))
                return Result.failure(Exception(body))
            }
            LogKit.i(AppStrings.ui_webdav_uploaded_successfully_arg0.format(arg0 = (target)))
            Result.success(true)
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_webdav_upload_failed_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
    }

    override suspend fun uploadFromSource(
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit,
        readChunk: suspend () -> ByteArray?,
    ): Result<Boolean> {
        requireKnownUploadSize(size)?.let { return it }
        val target = normalizeRemotePath(remotePath)
        LogKit.i(AppStrings.ui_webdav_upload_start_arg0.format(arg0 = (target)))
        val baseUrlValue = baseUrlParsed ?: return Result.failure(IllegalArgumentException(AppStrings.ui_link_address_invalid))
        return try {
            val response = client.request {
                method = HttpMethod.Put
                url(buildUrl(baseUrlValue, target))
                headers.append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                applyWebDavHeaders(this)
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override val contentLength: Long? = size.takeIf { it >= 0L }

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        pumpUploadChunks(
                            size = size,
                            onProgress = onProgress,
                            readChunk = readChunk,
                            writeChunk = { chunk -> channel.writeFully(chunk) },
                        ).getOrElse { error -> throw error }
                    }
                })
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                LogKit.w(AppStrings.ui_webdav_upload_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (body)))
                return Result.failure(Exception(body))
            }
            LogKit.i(AppStrings.ui_webdav_uploaded_successfully_arg0.format(arg0 = (target)))
            Result.success(true)
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_webdav_upload_failed_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
    }

    override suspend fun copyFile(sourcePath: String, targetPath: String): Result<Boolean> {
        val normalizedSource = normalizeRemotePath(sourcePath)
        val normalizedTarget = normalizeRemotePath(targetPath)
        if (normalizedSource == "/" || normalizedTarget == "/") {
            return Result.failure(IllegalArgumentException(AppStrings.ui_file_path_cannot_be_empty))
        }
        LogKit.i(AppStrings.ui_webdav_copy_start_arg0_arg1.format(arg0 = (normalizedSource), arg1 = (normalizedTarget)))
        val baseUrlValue = baseUrlParsed ?: return Result.failure(IllegalArgumentException(AppStrings.ui_link_address_invalid))
        return try {
            val response = client.request {
                method = HttpMethod("COPY")
                url(buildUrl(baseUrlValue, normalizedSource))
                headers.append(HttpHeaders.Destination, buildUrl(baseUrlValue, normalizedTarget))
                headers.append("Overwrite", "T")
                applyWebDavHeaders(this)
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                LogKit.w(AppStrings.ui_webdav_copy_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (body)))
                val message = body.ifBlank {
                    "${AppStrings.ui_copy_failed}: HTTP ${response.status.value}"
                }
                val error = when (response.status) {
                    HttpStatusCode.MethodNotAllowed,
                    HttpStatusCode.NotImplemented -> NetworkUnsupportedException(message)

                    else -> Exception(message)
                }
                return Result.failure(error)
            }
            LogKit.i(AppStrings.ui_webdav_copy_successful_arg0_arg1.format(arg0 = (normalizedSource), arg1 = (normalizedTarget)))
            Result.success(true)
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_webdav_copy_failed_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
    }

    override suspend fun rename(path: String, newPath: String): Result<Boolean> {
        val sourcePath = normalizeRemotePath(path)
        val destPath = normalizeRemotePath(newPath)
        LogKit.i(AppStrings.ui_webdav_rename_start_arg0_arg1.format(arg0 = (sourcePath), arg1 = (destPath)))
        val baseUrlValue = baseUrlParsed ?: return Result.failure(IllegalArgumentException(AppStrings.ui_link_address_invalid))
        return try {
            val response = client.request {
                method = HttpMethod("MOVE")
                url(buildUrl(baseUrlValue, sourcePath))
                headers.append(HttpHeaders.Destination, buildUrl(baseUrlValue, destPath))
                headers.append("Overwrite", "T")
                applyWebDavHeaders(this)
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                LogKit.w(AppStrings.ui_webdav_rename_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (body)))
                return Result.failure(Exception(body))
            }
            LogKit.i(AppStrings.ui_webdav_rename_succeeded_arg0_arg1.format(arg0 = (sourcePath), arg1 = (destPath)))
            Result.success(true)
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_webdav_rename_failed_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
    }

    override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> {
        val normalized = normalizeRemotePath(path)
        val target = if (isDirectory) ensureDirectoryPath(normalized) else normalized
        LogKit.i(AppStrings.ui_webdav_delete_arg0.format(arg0 = (target)))
        val baseUrlValue = baseUrlParsed ?: return Result.failure(IllegalArgumentException(AppStrings.ui_link_address_invalid))
        return try {
            val response = client.request {
                method = HttpMethod.Delete
                url(buildUrl(baseUrlValue, target))
                applyWebDavHeaders(this)
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                LogKit.w(AppStrings.ui_webdav_delete_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (body)))
                return Result.failure(Exception(body))
            }
            LogKit.i(AppStrings.ui_webdav_deleted_successfully_arg0.format(arg0 = (target)))
            Result.success(true)
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_webdav_delete_failed_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
    }

    override suspend fun createFolder(path: String): Result<Boolean> {
        val target = ensureDirectoryPath(normalizeRemotePath(path))
        LogKit.i(AppStrings.ui_webdav_creates_a_directory_arg0.format(arg0 = (target)))
        val baseUrlValue = baseUrlParsed ?: return Result.failure(IllegalArgumentException(AppStrings.ui_link_address_invalid))
        return try {
            val response = client.request {
                method = HttpMethod("MKCOL")
                url(buildUrl(baseUrlValue, target))
                applyWebDavHeaders(this)
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                LogKit.w(AppStrings.ui_webdav_creates_a_directory_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (body)))
                return Result.failure(Exception(body))
            }
            LogKit.i(AppStrings.ui_webdav_creates_a_directory_successfully_arg0.format(arg0 = (target)))
            Result.success(true)
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_webdav_creates_a_directory_failed_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
    }

    override suspend fun createFile(path: String): Result<Boolean> {
        val target = normalizeRemotePath(path)
        LogKit.i(AppStrings.ui_webdav_creates_a_file_arg0.format(arg0 = (target)))
        val baseUrlValue = baseUrlParsed ?: return Result.failure(IllegalArgumentException(AppStrings.ui_link_address_invalid))
        return try {
            val response = client.request {
                method = HttpMethod.Put
                url(buildUrl(baseUrlValue, target))
                headers.append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                applyWebDavHeaders(this)
                setBody(ByteArray(0))
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                LogKit.w(AppStrings.ui_webdav_creates_file_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (body)))
                return Result.failure(Exception(body))
            }
            LogKit.i(AppStrings.ui_webdav_created_the_file_successfully_arg0.format(arg0 = (target)))
            Result.success(true)
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_webdav_creates_file_failed_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
    }

    private fun applyWebDavHeaders(builder: HttpRequestBuilder) {
        if (extras.authType == WebDavAuthType.Token) {
            val headerName = extras.tokenHeaderName.trim().ifBlank { HttpHeaders.Authorization }
            val tokenValue = buildTokenHeaderValue(extras)
            if (tokenValue.isNotBlank()) {
                builder.headers.remove(headerName)
                builder.headers.append(headerName, tokenValue)
            }
        }
        extras.headers.forEach { (key, value) ->
            val headerName = key.trim()
            if (headerName.isNotEmpty()) {
                builder.headers.remove(headerName)
                builder.headers.append(headerName, value)
            }
        }
    }

    private fun buildUrl(baseUrl: Url, remotePath: String): String {
        val normalizedPath = normalizeRemotePath(remotePath)
        val encodedPath = encodePath(normalizedPath)
        val basePath = baseUrl.encodedPath.trimEnd('/')
        val combinedPath = when {
            basePath.isBlank() || basePath == "/" -> encodedPath
            encodedPath == "/" -> "$basePath/"
            else -> basePath + encodedPath
        }
        val base = baseUrlString(baseUrl)
        return if (combinedPath == "/") "$base/" else base + combinedPath
    }

    private fun normalizeRemotePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/"
        val separator = network.pathSeparator
        val normalized = if (separator != "/") {
            trimmed.replace(separator, "/")
        } else {
            trimmed
        }
        val withSlash = if (normalized.startsWith("/")) normalized else "/$normalized"
        requireSafeNetworkWritePath(withSlash)
        return withSlash
    }

    private fun ensureDirectoryPath(path: String): String {
        return if (path.endsWith("/")) path else "$path/"
    }

    private fun normalizeComparablePath(path: String): String {
        val normalized = normalizeRemotePath(path)
        return if (normalized != "/") normalized.trimEnd('/') else normalized
    }

    private fun encodePath(path: String): String {
        val normalized = normalizeRemotePath(path)
        val segments = normalized.split('/')
        val encodedSegments = segments.map { segment ->
            if (segment.isEmpty()) segment else segment.encodeURLPathPart()
        }
        val encoded = encodedSegments.joinToString("/")
        return if (encoded.startsWith("/")) encoded else "/$encoded"
    }

    private fun parsePropfindResponse(xml: String, requestedPath: String): List<NetworkFileEntry> {
        val responses = RESPONSE_REGEX.findAll(xml)
        val items = mutableListOf<NetworkFileEntry>()
        val requestedComparable = normalizeComparablePath(requestedPath)
        for (match in responses) {
            val block = match.groupValues.getOrNull(1) ?: continue
            val hrefRaw = extractTagValue(block, "href") ?: continue
            val href = unescapeXml(hrefRaw)
            val hrefPath = extractHrefPath(href)
            val relativeEncoded = stripBasePath(hrefPath)
            val decodedPath = decodePath(relativeEncoded)
            val normalizedPath = try {
                normalizeRemotePath(decodedPath)
            } catch (_: IllegalArgumentException) {
                continue
            }
            val comparable = normalizeComparablePath(normalizedPath)
            if (comparable == requestedComparable) continue

            val name = extractTagValue(block, "displayname")
                ?.let { unescapeXml(it).trim() }
                ?.takeIf { it.isNotEmpty() }
                ?: normalizedPath.trimEnd('/').substringAfterLast('/')
            val isDirectory = COLLECTION_REGEX.containsMatchIn(block) || hrefPath.endsWith("/")
            val size = extractTagValue(block, "getcontentlength")?.trim()?.toLongOrNull() ?: 0L
            val createdDate = parseInstant(extractTagValue(block, "creationdate"))
            val updatedDate = parseInstant(extractTagValue(block, "getlastmodified"))
            val displayPath = toDisplayPath(normalizedPath)

            items += NetworkFileEntry(
                name = name,
                path = displayPath,
                isDirectory = isDirectory,
                size = if (isDirectory) -1 else size,
                createdDate = if(isDirectory) -1 else createdDate,
                updatedDate = if (isDirectory) -1 else updatedDate,
                isHidden = name.startsWith(".")
            )
        }
        return items
    }

    private fun extractHrefPath(href: String): String {
        val trimmed = href.trim()
        if (trimmed.isEmpty()) return trimmed
        val withoutFragment = trimmed.substringBefore('#').substringBefore('?')
        return if (withoutFragment.contains("://")) {
            runCatching { Url(withoutFragment).encodedPath }.getOrElse { withoutFragment }
        } else {
            withoutFragment
        }
    }

    private fun stripBasePath(path: String): String {
        val normalizedBase = when {
            baseEncodedPath.isBlank() || baseEncodedPath == "/" -> ""
            else -> baseEncodedPath
        }
        val normalizedPath = if (!path.startsWith("/")) "/$path" else path
        return if (normalizedBase.isNotEmpty() && normalizedPath.startsWith(normalizedBase)) {
            normalizedPath.removePrefix(normalizedBase).ifBlank { "/" }
        } else {
            "/"
        }
    }

    private fun decodePath(path: String): String {
        return runCatching { path.decodeURLPart() }.getOrDefault(path)
    }

    private fun baseUrlString(baseUrl: Url): String {
        val port = baseUrl.specifiedPort
        val includePort = port != DEFAULT_PORT && port != baseUrl.protocol.defaultPort
        val portSuffix = if (includePort) ":$port" else ""
        return "${baseUrl.protocol.name}://${baseUrl.host}$portSuffix"
    }

    private fun toDisplayPath(path: String): String {
        return if (network.pathSeparator == "/") path else path.replace("/", network.pathSeparator)
    }

    private fun parseInstant(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(value.trim()).toEpochMilliseconds() }.getOrDefault(0L)
    }

    private fun extractTagValue(xml: String, tag: String): String? {
        val escaped = Regex.escape(tag)
        val pattern = "(?is)<\\s*(?:\\w+:)?$escaped\\b[^>]*>(.*?)</\\s*(?:\\w+:)?$escaped\\b>"
        return pattern.toRegex()
            .find(xml)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    private fun unescapeXml(value: String): String {
        return value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun buildTokenHeaderValue(extras: WebDavDriveExtras): String {
        val token = extras.token.trim()
        if (token.isEmpty()) return ""
        val prefix = extras.tokenPrefix.trim()
        return if (prefix.isEmpty()) token else "$prefix $token"
    }

    private companion object {
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname />
    <d:resourcetype />
    <d:getcontentlength />
    <d:getlastmodified />
    <d:creationdate />
  </d:prop>
</d:propfind>
"""
        private val RESPONSE_REGEX = Regex(
            "(?is)<\\s*(?:\\w+:)?response\\b[^>]*>(.*?)</\\s*(?:\\w+:)?response\\b>"
        )
        private val COLLECTION_REGEX = Regex(
            "<\\s*(?:\\w+:)?collection\\b",
            setOf(RegexOption.IGNORE_CASE)
        )
    }
}

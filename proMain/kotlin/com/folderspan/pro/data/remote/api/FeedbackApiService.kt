package com.folderspan.pro.data.remote.api

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.core.common.apiCode
import com.folderspan.pro.core.common.apiDataOrSelf
import com.folderspan.pro.core.common.apiMessage
import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.core.network.ReplayableRequestContent
import com.folderspan.pro.core.network.defaultJson
import com.folderspan.pro.data.mapper.longValue
import com.folderspan.pro.data.mapper.stringValue
import com.folderspan.pro.data.remote.dto.FeedbackDeleteRequest
import com.folderspan.pro.data.remote.dto.FeedbackSubmitRequest
import com.folderspan.pro.data.remote.dto.FeedbackSupplementRequest
import com.folderspan.pro.data.remote.dto.FeedbackUpdateRequest
import com.folderspan.pro.data.remote.dto.FeedbackWithdrawRequest
import com.folderspan.pro.domain.model.FeedbackDownload
import com.folderspan.pro.domain.model.FeedbackListQuery
import com.folderspan.pro.domain.model.FeedbackTransferProgress
import com.folderspan.pro.domain.model.FeedbackTransferProgressCallback
import com.folderspan.pro.domain.model.FeedbackUpload
import com.folderspan.pro.domain.model.MAX_FEEDBACK_ATTACHMENT_BYTES
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.decodeURLPart
import io.ktor.util.generateNonceBlocking
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.JsonObject
import strings.AppStrings

class FeedbackApiService(
    client: HttpClient,
    config: GatewayConfig = GatewayConfig(),
    private val attachmentDownloadClient: HttpClient = client,
) : BaseApiService(client, config) {

    suspend fun categories(): JsonResult = jsonCall {
        client.get(routes.feedback("/categories"))
    }

    suspend fun submit(request: FeedbackSubmitRequest, token: String? = null): JsonResult = jsonCall {
        client.post(routes.feedback()) {
            token?.takeIf(String::isNotBlank)?.let { auth(it) }
            setBody(request)
        }
    }

    suspend fun list(query: FeedbackListQuery, token: String): JsonResult = jsonCall {
        client.get(routes.feedback()) {
            auth(token)
            query.type?.takeIf(String::isNotBlank)?.let { parameter("type", it) }
            query.platform?.takeIf(String::isNotBlank)?.let { parameter("platform", it) }
            query.startTimeEpochSeconds?.let { parameter("startTime", it) }
            query.endTimeEpochSeconds?.let { parameter("endTime", it) }
            parameter("page", query.page.coerceAtLeast(1))
            parameter("pageSize", query.pageSize.coerceIn(1, 100))
        }
    }

    suspend fun update(request: FeedbackUpdateRequest, token: String): JsonResult = jsonCall {
        client.put(routes.feedback()) {
            auth(token)
            setBody(request)
        }
    }

    suspend fun delete(uuids: List<String>, token: String): JsonResult = jsonCall {
        client.delete(routes.feedback()) {
            auth(token)
            setBody(FeedbackDeleteRequest(uuids))
        }
    }

    suspend fun detail(uuid: String, token: String): JsonResult = jsonCall {
        client.get(routes.feedback("/${uuid.pathSegment()}")) { auth(token) }
    }

    suspend fun markRead(uuid: String, token: String): JsonResult = jsonCall {
        client.post(routes.feedback("/${uuid.pathSegment()}/read")) { auth(token) }
    }

    suspend fun supplement(uuid: String, content: String, token: String): JsonResult = jsonCall {
        client.post(routes.feedback("/${uuid.pathSegment()}/supplements")) {
            auth(token)
            setBody(FeedbackSupplementRequest(content))
        }
    }

    suspend fun withdraw(uuid: String, reason: String?, token: String): JsonResult = jsonCall {
        client.post(routes.feedback("/${uuid.pathSegment()}/withdraw")) {
            auth(token)
            setBody(FeedbackWithdrawRequest(reason))
        }
    }

    suspend fun upload(
        uuid: String,
        upload: FeedbackUpload,
        token: String,
        onProgress: FeedbackTransferProgressCallback = {},
    ): JsonResult = jsonCall {
        onProgress(FeedbackTransferProgress(0L, upload.bytes.size.toLong()))
        val response = client.post(routes.feedback("/${uuid.pathSegment()}/attachments")) {
            auth(token)
            val multipartContent = upload.toReplayableMultipartContent(onProgress)
            setBody(multipartContent)
        }
        onProgress(FeedbackTransferProgress(upload.bytes.size.toLong(), upload.bytes.size.toLong()))
        response
    }

    suspend fun deleteAttachment(uuid: String, attachmentUuid: String, token: String): JsonResult = jsonCall {
        client.delete(
            routes.feedback(
                "/${uuid.pathSegment()}/attachments/${attachmentUuid.pathSegment()}",
            ),
        ) { auth(token) }
    }

    suspend fun download(
        uuid: String,
        attachmentUuid: String,
        token: String,
        onProgress: FeedbackTransferProgressCallback = {},
    ): ApiResult<FeedbackDownload> = responseCall(
        request = {
            client.get(
                routes.feedback(
                    "/${uuid.pathSegment()}/attachments/${attachmentUuid.pathSegment()}",
                ),
            ) { auth(token) }
        },
        transform = { response ->
            val metadata = response.feedbackDownloadMetadataOrNull()
            if (metadata == null) {
                response.toFeedbackDownload(onProgress = onProgress)
            } else {
                val attachmentResponse = attachmentDownloadClient.get(metadata.url)
                if (attachmentResponse.status.value !in 200..299) {
                    throw ApiHttpStatusException(
                        statusCode = attachmentResponse.status.value,
                        message = AppStrings.ui_file_download_failed,
                    )
                }
                attachmentResponse.toFeedbackDownload(
                    metadata = metadata,
                    onProgress = onProgress,
                )
            }
        },
    )
}

private data class FeedbackDownloadMetadata(
    val originalName: String?,
    val contentType: String?,
    val size: Long?,
    val url: String,
)

private suspend fun HttpResponse.feedbackDownloadMetadataOrNull(): FeedbackDownloadMetadata? {
    if (!isJsonResponse()) return null
    val payload = defaultJson.parseToJsonElement(bodyAsText())
    val code = payload.apiCode()
    if (code != null && code != 0) {
        throw ApiBusinessException(
            apiCode = code,
            message = payload.apiMessage() ?: AppStrings.ui_file_download_failed,
        )
    }
    val data = payload.apiDataOrSelf() as? JsonObject
        ?: throw IllegalStateException(AppStrings.ui_file_download_failed)
    val size = data.longValue("size")
    require(size == null || size in 0L..MAX_FEEDBACK_ATTACHMENT_BYTES) {
        AppStrings.ui_feedback_attachment_limit
    }
    return FeedbackDownloadMetadata(
        originalName = data.stringValue("originalName"),
        contentType = data.stringValue("contentType"),
        size = size,
        url = data.stringValue("url")
            ?: throw IllegalStateException(AppStrings.ui_file_download_failed),
    )
}

private fun HttpResponse.isJsonResponse(): Boolean =
    headers[HttpHeaders.ContentType]
        ?.let { value -> runCatching { ContentType.parse(value) }.getOrNull() }
        ?.match(ContentType.Application.Json) == true

private suspend fun HttpResponse.toFeedbackDownload(
    metadata: FeedbackDownloadMetadata? = null,
    onProgress: FeedbackTransferProgressCallback,
): FeedbackDownload {
    val responseSize = feedbackContentLength()
    val totalBytes = metadata?.size ?: responseSize
    onProgress(FeedbackTransferProgress(0L, totalBytes))
    val bytes = bodyAsChannel().readAllBytesBounded(
        limit = MAX_FEEDBACK_ATTACHMENT_BYTES,
        totalBytes = totalBytes,
        onProgress = onProgress,
    )
    require(metadata?.size == null || metadata.size == bytes.size.toLong()) {
        AppStrings.ui_file_download_failed
    }
    return FeedbackDownload(
        fileName = sanitizeFeedbackFileName(metadata?.originalName)
            ?: parseFeedbackContentDispositionFileName(headers[HttpHeaders.ContentDisposition]),
        contentType = metadata?.contentType ?: headers[HttpHeaders.ContentType],
        bytes = bytes,
    )
}

private fun FeedbackUpload.toReplayableMultipartContent(
    onProgress: FeedbackTransferProgressCallback,
): OutgoingContent.WriteChannelContent {
    val boundaryNonce = generateNonceBlocking()
        .filter { character ->
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9'
        }
        .take(40)
    require(boundaryNonce.isNotEmpty()) { "Unable to generate multipart boundary" }
    val boundary = "FolderSpanBoundary$boundaryNonce"
    val safeFileName = (sanitizeFeedbackFileName(fileName) ?: "attachment").escapeMultipartQuotedValue()
    val fileContentType = runCatching { ContentType.parse(contentType) }
        .getOrDefault(ContentType.Application.OctetStream)
    val prefix = buildString {
        append("--")
        append(boundary)
        append("\r\n")
        append("Content-Disposition: form-data; name=\"file\"; filename=\"")
        append(safeFileName)
        append("\"\r\n")
        append("Content-Type: ")
        append(fileContentType)
        append("\r\n\r\n")
    }.encodeToByteArray()
    val suffix = "\r\n--$boundary--\r\n".encodeToByteArray()
    val payload = ByteArray(prefix.size + bytes.size + suffix.size)
    prefix.copyInto(payload)
    bytes.copyInto(payload, destinationOffset = prefix.size)
    suffix.copyInto(payload, destinationOffset = prefix.size + bytes.size)

    return ReplayableMultipartContent(
        payload = payload,
        fileOffset = prefix.size,
        fileSize = bytes.size,
        multipartContentType = ContentType.MultiPart.FormData.withParameter("boundary", boundary),
        onProgress = onProgress,
    )
}

private class ReplayableMultipartContent(
    private val payload: ByteArray,
    private val fileOffset: Int,
    private val fileSize: Int,
    private val multipartContentType: ContentType,
    private val onProgress: FeedbackTransferProgressCallback,
) : OutgoingContent.WriteChannelContent(), ReplayableRequestContent {
    override val contentType: ContentType = multipartContentType
    override val contentLength: Long = payload.size.toLong()

    override fun replayableBodyBytes(): ByteArray = payload

    override suspend fun writeTo(channel: ByteWriteChannel) {
        channel.writeFully(payload, 0, fileOffset)
        var written = 0
        while (written < fileSize) {
            val count = minOf(64 * 1024, fileSize - written)
            val startIndex = fileOffset + written
            channel.writeFully(payload, startIndex, startIndex + count)
            written += count
            onProgress(FeedbackTransferProgress(written.toLong(), fileSize.toLong()))
        }
        val suffixOffset = fileOffset + fileSize
        channel.writeFully(payload, suffixOffset, payload.size)
        if (fileSize == 0) onProgress(FeedbackTransferProgress(0L, 0L))
    }
}

private fun HttpResponse.feedbackContentLength(): Long? =
    headers[HttpHeaders.ContentLength]
        ?.toLongOrNull()
        ?.takeIf { it >= 0L }
        ?.also { require(it <= MAX_FEEDBACK_ATTACHMENT_BYTES) { "Attachment exceeds download limit" } }

private fun String.escapeMultipartQuotedValue(): String = buildString(length) {
    for (character in this@escapeMultipartQuotedValue) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> append(character)
        }
    }
}

internal fun parseFeedbackContentDispositionFileName(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val parameters = value.split(';').drop(1).map(String::trim)
    val encoded = parameters.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.trim('"')
        ?.substringAfter("''", missingDelimiterValue = "")
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { it.decodeURLPart() }.getOrDefault(it) }
    val plain = parameters.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.trim('"')
    return sanitizeFeedbackFileName(encoded ?: plain)
}

internal fun sanitizeFeedbackFileName(value: String?): String? = value
    ?.replace('\\', '/')
    ?.substringAfterLast('/')
    ?.filterNot(Char::isISOControl)
    ?.trim()
    ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }

private fun String.pathSegment(): String =
    trim().also { require(it.isNotEmpty()) { "Empty feedback identifier" } }
        .replace("/", "")
        .replace("\\", "")

private suspend fun io.ktor.utils.io.ByteReadChannel.readAllBytesBounded(
    limit: Long,
    totalBytes: Long?,
    onProgress: FeedbackTransferProgressCallback,
): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    var total = 0
    val buffer = ByteArray(64 * 1024)
    while (!isClosedForRead) {
        val read = readAvailable(buffer, 0, buffer.size)
        if (read <= 0) break
        total += read
        require(total.toLong() <= limit) { "Attachment exceeds download limit" }
        chunks += buffer.copyOf(read)
        onProgress(FeedbackTransferProgress(total.toLong(), totalBytes))
    }
    return ByteArray(total).also { result ->
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        onProgress(FeedbackTransferProgress(total.toLong(), totalBytes))
    }
}

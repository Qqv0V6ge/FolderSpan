package com.folderspan.service.http.client

import com.folderspan.exception.AuthorityException
import com.folderspan.exception.EmptyDataException
import com.folderspan.service.data.SerializableResult
import com.folderspan.service.data.toResult
import com.folderspan.utils.ProtoBufCodec
import io.ktor.client.call.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.io.EOFException
import strings.AppStrings

internal fun Throwable.isRetryableDeviceConnectionError(): Boolean {
    var current: Throwable? = this
    var retryable = false
    while (current != null) {
        if (current is CancellationException) return false
        if (current is EOFException || current is HttpRequestTimeoutException) {
            retryable = true
        }
        val message = current.message.orEmpty().lowercase()
        if (
            message.contains("not enough data available") ||
            message.contains("unexpected eof") ||
            message.contains("connection reset") ||
            message.contains("connection closed") ||
            message.contains("broken pipe") ||
            message.contains("timeout") ||
            message.contains("timed out")
        ) {
            retryable = true
        }
        current = current.cause
    }
    return retryable
}

internal fun HttpRequestBuilder.setFolderSpanRequestBody(
    body: ByteArray,
    encrypted: Boolean,
) {
    setBody(body)
}

internal suspend fun HttpResponse.folderSpanBodyBytes(): ByteArray {
    return body()
}

internal fun httpStatusException(
    status: HttpStatusCode,
    message: String,
): Exception {
    val resolvedMessage = message.ifBlank { "HTTP ${status.value} ${status.description}" }
    return when (status) {
        HttpStatusCode.BadRequest -> IllegalArgumentException(resolvedMessage)
        HttpStatusCode.Unauthorized -> AuthorityException(
            message = message.ifBlank { AppStrings.ui_auth_token_invalid }
        )

        HttpStatusCode.Forbidden -> AuthorityException(resolvedMessage)
        HttpStatusCode.NotFound -> EmptyDataException(resolvedMessage)
        else -> Exception(resolvedMessage)
    }
}

internal fun httpExceptionFromBody(
    status: HttpStatusCode,
    bodyBytes: ByteArray,
): Exception {
    val decoded = runCatching {
        ProtoBufCodec.decode<SerializableResult>(bodyBytes)
    }.getOrNull()
    if (decoded != null && decoded.looksLikeHttpResult() && !decoded.isSuccess) {
        val exception = decoded.toResult<Boolean>().exceptionOrNull()
        if (exception is Exception) {
            return exception
        }
    }

    val text = runCatching { bodyBytes.decodeToString() }.getOrDefault("")
    return httpStatusException(status, text)
}

internal inline fun <reified T> decodeHttpResultBody(bodyBytes: ByteArray): Result<T> {
    val wrapped = runCatching {
        ProtoBufCodec.decode<SerializableResult>(bodyBytes)
    }.getOrNull()
    if (wrapped != null && wrapped.looksLikeHttpResult()) {
        return wrapped.toResult()
    }
    return runCatching { ProtoBufCodec.decode<T>(bodyBytes) }
}

internal inline fun <reified T> decodeHttpSuccessBody(bodyBytes: ByteArray): T =
    decodeHttpResultBody<T>(bodyBytes).getOrThrow()

internal fun SerializableResult.looksLikeHttpResult(): Boolean {
    return hasData || data.isNotEmpty() || errorMessage != null || errorType != null
}

internal suspend fun readHttpResponseException(response: HttpResponse): Exception {
    val bytes = response.folderSpanBodyBytes()
    return httpExceptionFromBody(response.status, bytes)
}

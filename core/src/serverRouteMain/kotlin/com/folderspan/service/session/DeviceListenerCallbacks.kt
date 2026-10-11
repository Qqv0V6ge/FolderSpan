package com.folderspan.service.session

import com.folderspan.routes.*
import com.folderspan.service.http.server.linkshare.*
import kotlinx.coroutines.CoroutineScope

internal class DeviceListenerCallbacks(
    val http: suspend (RawHttpRequest) -> RawHttpResponse,
    val approval: suspend (LinkShareHttpRequest) -> LinkShareHttpResponse,
    val accept: suspend (DeviceSessionByteChannel, String?, CoroutineScope) -> Unit,
)

internal fun createDeviceListenerCallbacks(): DeviceListenerCallbacks {
    val dispatcher = RawHttpApiDispatcher()
    return DeviceListenerCallbacks(dispatcher::dispatch, dispatcher::dispatchDeviceShareApproval) { channel, remoteHost, scope ->
        DeviceSessionServerRuntimeLauncher.run(dispatcher, channel, remoteHost, scope, DeviceSessionServerApprovalContext.Standard)
    }
}

internal fun RawHttpRequest.asLinkShareRequest(): LinkShareHttpRequest {
    val reader = bodyReader
    val body = if (reader == null) LinkShareHttpRequestBody.Bytes(body) else object : LinkShareHttpRequestBody {
        override val contentLength get() = reader.contentLength
        override val remainingBytes get() = reader.remainingBytes
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = reader.read(buffer, offset, length)
        override suspend fun discard() = reader.discard()
    }
    val query = queryParameters.entries.joinToString("&") { (key, value) -> "${com.folderspan.service.webui.encodeWebUiQuery(key)}=${com.folderspan.service.webui.encodeWebUiQuery(value)}" }
    return LinkShareHttpRequest.from(method, path + if (query.isEmpty()) "" else "?$query",
        headers.mapValues { listOf(it.value) }, remoteHost, body = body)
}

internal suspend fun LinkShareHttpRequest.asRawHttpRequest(): RawHttpRequest {
    // Raw API buffered control bodies are bounded by its native transport limit.
    val length = body.contentLength
    require(length != null && length in 0..(128L * 1024 * 1024)) { "invalid raw request length" }
    val streaming = shouldStreamRawHttpRequestBody(path, length, false)
    val reader = if (!streaming) null else object : RawHttpRequestBodyReader {
        override val contentLength: Long = length
        override val remainingBytes get() = body.remainingBytes ?: 0L
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = body.read(buffer, offset, length)
        override suspend fun discard() = body.discard()
    }
    val bytes = if (streaming) byteArrayOf() else {
        require(length <= 8 * 1024 * 1024) { "control request too large" }
        ByteArray(length.toInt()).also { result ->
            var offset = 0
            while (offset < result.size) {
                val read = body.read(result, offset, result.size - offset)
                require(read > 0) { "incomplete request body" }
                offset += read
            }
        }
    }
    return RawHttpRequest(method, path, queryParameters = queryParameters.mapValues { it.value.firstOrNull().orEmpty() },
        headers = headers.mapValues { it.value.firstOrNull().orEmpty() }, body = bytes, bodyReader = reader, remoteHost = remoteHost)
}

internal fun RawHttpResponse.asLinkShareResponse(): LinkShareHttpResponse {
    val headers = headers.map { LinkShareHttpHeader(it.key, it.value) }
    return when (val content = body) {
        is RawHttpBody.Bytes -> LinkShareHttpResponse.bytes(statusCode, content.bytes, headers = headers)
        is RawHttpBody.Stream -> LinkShareHttpResponse.stream(statusCode, content.contentLength, headers = headers) {
            val output = this
            content.writer(object : RawHttpResponseBodyWriter {
                override suspend fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
                override suspend fun flush() = output.flush()
            })
        }
    }
}

internal fun LinkShareHttpResponse.asRawHttpResponse(): RawHttpResponse = RawHttpResponse(statusCode, reasonPhrase,
    headers.associate { it.name to it.value }, when (val content = body) {
        LinkShareHttpResponseBody.Empty -> RawHttpBody.Bytes(byteArrayOf())
        is LinkShareHttpResponseBody.Bytes -> RawHttpBody.Bytes(content.bytes)
        is LinkShareHttpResponseBody.Stream -> RawHttpBody.Stream(content.contentLength) {
            val output = this
            content.writer(object : LinkShareHttpResponseBodyWriter {
                override suspend fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
                override suspend fun flush() = output.flush()
            })
        }
    })

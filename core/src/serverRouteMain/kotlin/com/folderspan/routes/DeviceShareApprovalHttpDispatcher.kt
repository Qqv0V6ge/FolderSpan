package com.folderspan.routes

import com.folderspan.service.http.server.linkshare.LinkShareHttpHeader
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponseBody

internal const val DEVICE_SHARE_APPROVAL_MAX_REQUEST_BODY_BYTES = 64L * 1024L
private const val DEVICE_SHARE_HEARTBEAT_PATH = "/api/share/heartbeat"

internal suspend fun RawHttpApiDispatcher.dispatchDeviceShareApproval(
    request: LinkShareHttpRequest,
): LinkShareHttpResponse {
    if (request.path != DEVICE_SHARE_HEARTBEAT_PATH) {
        return LinkShareHttpResponse.bytes(statusCode = 404)
    }
    val rawResponse = dispatch(
        RawHttpRequest(
            method = request.method,
            path = request.path,
            queryParameters = request.queryParameters.mapValues { (_, values) ->
                values.firstOrNull().orEmpty()
            },
            headers = request.headers.mapValues { (_, values) -> values.firstOrNull().orEmpty() },
            body = request.readApprovalBody(),
            remoteHost = request.remoteHost,
        )
    )
    val responseBody = when (val body = rawResponse.body) {
        is RawHttpBody.Bytes -> LinkShareHttpResponseBody.Bytes(body.bytes)
        is RawHttpBody.Stream -> return LinkShareHttpResponse.bytes(statusCode = 500)
    }
    return LinkShareHttpResponse(
        statusCode = rawResponse.statusCode,
        reasonPhrase = rawResponse.reasonPhrase,
        headers = rawResponse.headers.map { (name, value) -> LinkShareHttpHeader(name, value) },
        body = responseBody,
    )
}

private suspend fun LinkShareHttpRequest.readApprovalBody(): ByteArray {
    val contentLength = body.contentLength
    require(contentLength == null || contentLength <= DEVICE_SHARE_APPROVAL_MAX_REQUEST_BODY_BYTES) {
        "Device share approval request is too large"
    }
    val bytes = ByteArray(DEVICE_SHARE_APPROVAL_MAX_REQUEST_BODY_BYTES.toInt())
    var size = 0
    while (true) {
        if (size == bytes.size) {
            require(body.remainingBytes == 0L) { "Device share approval request is too large" }
            break
        }
        val read = body.read(bytes, size, bytes.size - size)
        if (read < 0) break
        if (read == 0) continue
        size += read
    }
    return bytes.copyOf(size)
}

package com.folderspan.routes

import com.folderspan.service.data.WRITE_BYTES_STREAM_REQUEST_HEADER
import com.folderspan.service.http.client.HttpRouteClientManager
import com.folderspan.service.http.crypto.HTTP_ENCRYPTED_PAYLOAD_HEADER
import com.folderspan.service.http.crypto.HTTP_ENCRYPTED_PAYLOAD_VERSION
import com.folderspan.service.operation.HttpTransferStatusHeaders
import com.folderspan.service.webrtc.signaling.HTTP_WEBRTC_CLIENT_TOKEN_HEADER
import com.folderspan.service.webrtc.signaling.HTTP_WEBRTC_HOST_SECRET_HEADER
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RawHttpEncryptedPayloadTest {
    @Test
    fun encryptedPayloadMarkerIsRejectedForDeviceApi() {
        val request = RawHttpRequest(
            method = "POST",
            path = "/api/share/heartbeat",
            headers = mapOf(
                HTTP_ENCRYPTED_PAYLOAD_HEADER.lowercase() to HTTP_ENCRYPTED_PAYLOAD_VERSION,
            ),
            body = byteArrayOf(1, 2, 3),
        )

        val response = RawHttpApiDispatcher(settings = MapSettings()).dispatchBlockingForTest(request)

        assertEquals(400, response.statusCode)
        assertFalse(response.headers.containsKey(HTTP_ENCRYPTED_PAYLOAD_HEADER))
    }

    @Test
    fun deviceApiCorsHeadersOmitWildcardUntilPrivateOrigin() {
        val headers = deviceApiCorsHeaders(RawHttpApiDispatcher())

        assertEquals(null, headers["Access-Control-Allow-Origin"])
        assertEquals(null, headers["Access-Control-Allow-Private-Network"])
        assertEquals("GET, POST, OPTIONS", headers["Access-Control-Allow-Methods"])
        assertEquals("86400", headers["Access-Control-Max-Age"])

        val allowedHeaders = headers.getValue("Access-Control-Allow-Headers").splitHeaderList()
        assertContains(allowedHeaders, "Content-Type")
        assertContains(allowedHeaders, HTTP_WEBRTC_CLIENT_TOKEN_HEADER)
        assertContains(allowedHeaders, HTTP_WEBRTC_HOST_SECRET_HEADER)
        assertContains(allowedHeaders, WRITE_BYTES_STREAM_REQUEST_HEADER)
        assertFalse(HTTP_ENCRYPTED_PAYLOAD_HEADER in allowedHeaders)

        val exposedHeaders = headers.getValue("Access-Control-Expose-Headers").splitHeaderList()
        assertFalse(HTTP_ENCRYPTED_PAYLOAD_HEADER in exposedHeaders)
        assertContains(exposedHeaders, HttpTransferStatusHeaders.VERSION)
        assertContains(exposedHeaders, HttpTransferStatusHeaders.RECOMMENDED_CHUNK_BYTES)
        assertContains(exposedHeaders, HttpTransferStatusHeaders.RETRY_AFTER_MILLIS)
    }

    @Test
    fun fileBatchPreservesExtraTransferStatusHeaders() {
        val extraHeaders = mapOf(
            HttpTransferStatusHeaders.VERSION to "1",
            HttpTransferStatusHeaders.RECOMMENDED_PARALLEL_REQUESTS to "2",
        )

        val response = RawHttpApiDispatcher().fileBatch(
            Result.success(listOf(Result.success(true))),
            extraHeaders = extraHeaders,
        )

        assertEquals("1", response.headers[HttpTransferStatusHeaders.VERSION])
        assertEquals("2", response.headers[HttpTransferStatusHeaders.RECOMMENDED_PARALLEL_REQUESTS])
    }

    @Test
    fun deviceTransferStatusHeadersAdvertiseDeviceDirectParallelLimit() = runBlocking {
        val headers = deviceTransferStatusHeaders()

        assertEquals(
            HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS.toString(),
            headers[HttpTransferStatusHeaders.MAX_PARALLEL_REQUESTS],
        )
    }

    private fun String.splitHeaderList(): List<String> {
        return split(",").map { item -> item.trim() }
    }

    private fun RawHttpApiDispatcher.dispatchBlockingForTest(request: RawHttpRequest): RawHttpResponse =
        runBlocking { dispatch(request) }
}

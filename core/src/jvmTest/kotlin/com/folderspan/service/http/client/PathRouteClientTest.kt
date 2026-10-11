package com.folderspan.service.http.client

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.data.ListRequest
import com.folderspan.service.data.toSerializableResult
import com.folderspan.service.operation.HttpTransferStatus
import com.folderspan.service.operation.HttpTransferStatusHeaders
import com.folderspan.utils.ProtoBufCodec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PathRouteClientTest {
    @Test
    fun listPathUpdatesPathTransferStatusFromResponseHeaders() = runBlocking {
        val responseBody: Map<Pair<FileProtocol, String>, MutableList<FileSimpleInfo>> = emptyMap()
        var captured = null as HttpRequestData?
        val transferStatus = HttpTransferStatus(
            recommendedParallelRequests = 2,
            maxParallelRequests = 8,
            activeRequests = 7,
            busy = true,
            retryAfterMillis = 50L,
            sampledAtMillis = 42L,
        )
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = ByteReadChannel(ProtoBufCodec.encode(Result.success(responseBody).toSerializableResult())),
                status = HttpStatusCode.OK,
                headers = Headers.build {
                    append(HttpHeaders.ContentType, "application/protobuf")
                    HttpTransferStatusHeaders.encode(transferStatus).forEach { (name, value) ->
                        append(name, value)
                    }
                },
            )
        }
        val httpClient = HttpClient(engine) {
            defaultRequest {
                url("http://localhost")
            }
        }
        val client = PathRouteClient(httpClient, HttpRouteClientManager())

        val result = client.listPath(ListRequest("/"))

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
        assertEquals("/api/paths/list", captured?.url?.encodedPath)
        assertNotNull(captured)
        val latestStatus = client.transferStatus()
        assertEquals(2, latestStatus.recommendedParallelRequests)
        assertEquals(7, latestStatus.activeRequests)
        assertTrue(latestStatus.busy)
    }

    @Test
    fun listPathRetriesTransientTlsEofOnce() = runBlocking {
        val responseBody: Map<Pair<FileProtocol, String>, MutableList<FileSimpleInfo>> = emptyMap()
        var attempts = 0
        val engine = MockEngine {
            attempts++
            if (attempts == 1) {
                throw java.io.EOFException("Not enough data available")
            }
            respond(
                content = ByteReadChannel(
                    ProtoBufCodec.encode(Result.success(responseBody).toSerializableResult())
                ),
                status = HttpStatusCode.OK,
                headers = Headers.build {
                    append(HttpHeaders.ContentType, "application/protobuf")
                },
            )
        }
        val httpClient = HttpClient(engine) {
            defaultRequest {
                url("http://localhost")
            }
        }
        val client = PathRouteClient(httpClient, HttpRouteClientManager())

        val result = client.listPath(ListRequest("/storage/emulated/0"))

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
        assertEquals(2, attempts)
    }
}

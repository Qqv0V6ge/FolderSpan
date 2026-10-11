package com.folderspan.service.http.client

import com.folderspan.exception.EmptyDataException
import com.folderspan.service.data.toSerializableResult
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
import kotlin.test.assertTrue

class BookmarkRouteClientTest {
    @Test
    fun reorderBookmarksUsesTheDedicatedRemoteRoute() = runBlocking {
        var captured = null as HttpRequestData?
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = ByteReadChannel(
                    ProtoBufCodec.encode(Result.success(true).toSerializableResult()),
                ),
                status = HttpStatusCode.OK,
                headers = protobufHeaders,
            )
        }
        val client = BookmarkRouteClient(testHttpClient(engine), HttpRouteClientManager())

        val result = client.reorderBookmarks(listOf(9L, 3L, 5L))

        assertTrue(result.getOrThrow())
        assertEquals("/api/bookmarks/reorder", captured?.url?.encodedPath)
    }

    @Test
    fun missingReorderRouteUsesTheStandardHttpFailure() = runBlocking {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.NotFound,
                headers = protobufHeaders,
            )
        }
        val client = BookmarkRouteClient(testHttpClient(engine), HttpRouteClientManager())

        val result = client.reorderBookmarks(listOf(1L, 2L))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is EmptyDataException)
    }
}

private fun testHttpClient(engine: MockEngine) = HttpClient(engine) {
    defaultRequest {
        url("http://localhost")
    }
}

private val protobufHeaders = Headers.build {
    append(HttpHeaders.ContentType, "application/protobuf")
}

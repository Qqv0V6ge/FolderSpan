package com.folderspan.routes

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RawHttpKeepAliveTest {
    @Test
    fun http11DataRoutesCanKeepConnectionWhenCapacityIsIsolated() {
        val request = RawHttpRequest(
            method = "POST",
            path = "/api/files/read-bytes",
            protocolVersion = "HTTP/1.1",
        )

        assertTrue(request.shouldKeepAliveConnection())
        assertFalse(request.shouldKeepAliveConnection(dataKeepAliveEnabled = false))
    }

    @Test
    fun http11ControlRoutesKeepConnectionByDefault() {
        val request = RawHttpRequest(
            method = "POST",
            path = "/api/share/heartbeat",
            protocolVersion = "HTTP/1.1",
        )

        assertTrue(request.shouldKeepAliveConnection())
    }

    @Test
    fun dataRouteClassificationCoversBulkTransferPaths() {
        val transferPaths = listOf(
            "/api/files/read-bytes",
            "/api/files/stream-file",
            "/api/files/stream-upload",
        )

        transferPaths.forEach { path ->
            val request = RawHttpRequest(
                method = "POST",
                path = path,
                protocolVersion = "HTTP/1.1",
                headers = mapOf("connection" to "keep-alive"),
            )

            assertEquals(RawHttpRouteClass.Data, request.routeClass(), "path=$path")
            assertTrue(request.shouldKeepAliveConnection(), "path=$path")
        }
    }

    @Test
    fun controlRouteClassificationCoversShareHeartbeat() {
        val controlPaths = listOf("/api/share/heartbeat")

        controlPaths.forEach { path ->
            val request = RawHttpRequest(
                method = "POST",
                path = path,
                protocolVersion = "HTTP/1.1",
            )

            assertEquals(RawHttpRouteClass.Control, request.routeClass(), "path=$path")
            assertTrue(request.shouldKeepAliveConnection(), "path=$path")
        }
    }

    @Test
    fun pairingCapacityDoesNotStarveControlAdmission() {
        val capacity = RawHttpRouteCapacity(
            maxControlRequests = 1,
            maxDataRequests = 1,
            maxIdleDataKeepAliveConnections = 1,
            maxPairingRequests = 2,
        )
        val first = capacity.tryAcquireForTests(RawHttpRouteClass.Pairing)
        val second = capacity.tryAcquireForTests(RawHttpRouteClass.Pairing)
        assertTrue(first != null)
        assertTrue(second != null)
        assertFalse(capacity.canAcquireForTests(RawHttpRouteClass.Pairing))
        assertTrue(capacity.canAcquireForTests(RawHttpRouteClass.Control))
        first.release()
        second.release()
    }

    @Test
    fun dataCapacityDoesNotConsumeReservedControlAdmission() {
        val capacity = RawHttpRouteCapacity(
            maxControlRequests = 1,
            maxDataRequests = 1,
            maxIdleDataKeepAliveConnections = 1,
        )
        val dataPermit = capacity.tryAcquireForTests(RawHttpRouteClass.Data)
        assertTrue(dataPermit != null)

        assertFalse(capacity.canAcquireForTests(RawHttpRouteClass.Data))
        assertTrue(capacity.canAcquireForTests(RawHttpRouteClass.Control))

        dataPermit.release()
    }

    @Test
    fun dataKeepAliveUsesSeparateCapacityAccounting() {
        val capacity = RawHttpRouteCapacity(
            maxControlRequests = 1,
            maxDataRequests = 1,
            maxIdleDataKeepAliveConnections = 1,
        )
        val keepAlivePermit = capacity.tryAcquireIdleDataKeepAliveForTests()
        assertTrue(keepAlivePermit != null)

        assertFalse(capacity.canAcquireIdleDataKeepAliveForTests())
        assertTrue(capacity.canAcquireForTests(RawHttpRouteClass.Control))

        keepAlivePermit.release()
    }

    @Test
    fun connectionCloseDisablesKeepAlive() {
        val request = RawHttpRequest(
            method = "POST",
            path = "/api/files/read-bytes",
            protocolVersion = "HTTP/1.1",
            headers = mapOf("connection" to "keep-alive, close"),
        )

        assertFalse(request.shouldKeepAliveConnection())
    }

    @Test
    fun http10RequiresExplicitKeepAlive() {
        val defaultRequest = RawHttpRequest(
            method = "POST",
            path = "/api/share/heartbeat",
            protocolVersion = "HTTP/1.0",
        )
        val keepAliveRequest = defaultRequest.copy(
            headers = mapOf("connection" to "Keep-Alive"),
        )

        assertFalse(defaultRequest.shouldKeepAliveConnection())
        assertTrue(keepAliveRequest.shouldKeepAliveConnection())
    }

    @Test
    fun discardUnreadBodyDelegatesToStreamingReader() = runBlocking {
        val reader = TestBodyReader(contentLength = 16L)
        val request = RawHttpRequest(
            method = "POST",
            path = "/api/files/stream-upload",
            protocolVersion = "HTTP/1.1",
            bodyReader = reader,
        )

        assertTrue(request.discardUnreadBody())
        assertTrue(reader.discarded)
    }

    @Test
    fun discardUnreadBodyReturnsFalseWhenReaderFails() = runBlocking {
        val request = RawHttpRequest(
            method = "POST",
            path = "/api/files/stream-upload",
            protocolVersion = "HTTP/1.1",
            bodyReader = TestBodyReader(contentLength = 16L, failDiscard = true),
        )

        assertFalse(request.discardUnreadBody())
    }

    @Test
    fun deviceUploadRouteSupportsStreamingRequestBody() {
        assertFalse(
            shouldStreamRawHttpRequestBody(
                path = "/api/files/stream-file",
                contentLength = 4096L,
                isChunked = false,
            )
        )
        assertTrue(
            shouldStreamRawHttpRequestBody(
                path = "/api/files/stream-upload",
                contentLength = 4096L,
                isChunked = false,
            )
        )
        assertTrue(
            shouldStreamRawHttpRequestBody(
                path = "/api/files/write-bytes",
                contentLength = 4096L,
                isChunked = false,
            )
        )
        assertFalse(
            shouldStreamRawHttpRequestBody(
                path = "/api/files/read-bytes",
                contentLength = 4096L,
                isChunked = false,
            )
        )
        assertFalse(
            shouldStreamRawHttpRequestBody(
                path = "/api/files/stream-file",
                contentLength = 4096L,
                isChunked = true,
            )
        )
    }

    private class TestBodyReader(
        override val contentLength: Long,
        private val failDiscard: Boolean = false,
    ) : RawHttpRequestBodyReader {
        override var remainingBytes: Long = contentLength
            private set
        var discarded: Boolean = false
            private set

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remainingBytes <= 0L) return -1
            val read = minOf(length.toLong(), remainingBytes).toInt()
            remainingBytes -= read.toLong()
            return read
        }

        override suspend fun discard() {
            if (failDiscard) throw IllegalStateException("discard failed")
            remainingBytes = 0L
            discarded = true
        }
    }
}

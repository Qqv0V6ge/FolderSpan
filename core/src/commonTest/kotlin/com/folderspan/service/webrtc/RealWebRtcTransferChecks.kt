package com.folderspan.service.webrtc

import com.folderspan.service.session.DEVICE_SESSION_RPC_OK
import com.folderspan.service.session.DeviceSessionControlResponse
import com.folderspan.service.session.DeviceSessionIncomingStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal suspend fun verifyRealWebRtcSequentialTransfers() {
    withRealWebRtcSessionPair { client, server, _ ->
        server.setRequestHandler { request ->
            DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, request.payload)
        }
        val payload = ByteArray(196_731) { (it % 251).toByte() }
        var received = 0
        server.setStreamOpenHandler { streamId, _ ->
            object : DeviceSessionIncomingStream {
                private var offset = 0

                override suspend fun onData(chunk: ByteArray) {
                    assertRealWebRtcContent(payload.copyOfRange(offset, offset + chunk.size), chunk)
                    offset += chunk.size
                    received += chunk.size
                }

                override suspend fun onTrailer(payload: ByteArray) {
                    server.connection.sendTrailer(streamId)
                }
            }
        }
        repeat(3) { index ->
            client.openWriteStream("/file-$index.bin", payload.size.toLong(), 0L, data = flowOf(payload))
            val echo = "after-file-$index".encodeToByteArray()
            assertContentEquals(echo, client.rpc("Echo", echo).payload)
        }
        assertEquals(3 * payload.size, received)
    }
}

internal suspend fun verifyRealWebRtcConcurrentTransfersAndRpc() {
    withRealWebRtcSessionPair { client, server, _ ->
        coroutineScope {
            server.setRequestHandler { request ->
                DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, request.payload)
            }
            var completed = 0
            var active = 0
            var peakActive = 0
            server.setStreamOpenHandler { streamId, open ->
                val index = open.path.substringAfterLast('-').substringBefore('.').toInt()
                active++
                peakActive = maxOf(peakActive, active)
                object : DeviceSessionIncomingStream {
                    private var received = 0L
                    override suspend fun onData(chunk: ByteArray) {
                        assertRealWebRtcContent(parallelPayload(index, received.toInt(), chunk.size), chunk)
                        received += chunk.size
                    }
                    override suspend fun onTrailer(payload: ByteArray) {
                        assertEquals(2L * 1024 * 1024, received)
                        active--
                        completed++
                        server.connection.sendTrailer(streamId)
                    }
                }
            }
            val writes = (0..3).map { index ->
                async {
                    client.openWriteStream("/parallel-$index.bin", 2L * 1024 * 1024, 0L, data = flow {
                        repeat(32) { block -> emit(parallelPayload(index, block * 65536, 65536)) }
                    })
                }
            }
            repeat(10) { index ->
                val payload = "rpc-$index".encodeToByteArray()
                assertContentEquals(payload, client.rpc("Echo", payload).payload)
            }
            writes.awaitAll()
            assertEquals(4, completed)
            assertTrue(peakActive > 1, "The check must exercise overlapping streams")
        }
    }
}

/** Pauses the producer at a known chunk boundary, then cancels only its stream. */
internal suspend fun verifyRealWebRtcPausedStreamCancellation() {
    withRealWebRtcSessionPair { client, server, _ ->
        coroutineScope {
            val firstChunk = CompletableDeferred<Unit>()
            val reset = CompletableDeferred<Unit>()
            server.setRequestHandler { request ->
                DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, request.payload)
            }
            server.setStreamOpenHandler { _, _ ->
                object : DeviceSessionIncomingStream {
                    override suspend fun onData(chunk: ByteArray) { firstChunk.complete(Unit) }
                    override suspend fun onReset() { reset.complete(Unit) }
                }
            }
            val write = async {
                client.openWriteStream("/cancel.bin", 128 * 1024L, 0L, data = flow {
                    emit(ByteArray(64 * 1024))
                    awaitCancellation()
                })
            }
            firstChunk.await()
            val echo = "while-producer-paused".encodeToByteArray()
            assertContentEquals(echo, client.rpc("Echo", echo).payload)
            write.cancelAndJoin()
            withTimeout(2_000L) { reset.await() }
            assertContentEquals(echo, client.rpc("Echo", echo).payload)
        }
    }
}

internal suspend fun verifyRealWebRtcDisconnectAndFreshSession() {
    withRealWebRtcSessionPair { client, server, channel ->
        supervisorScope {
            val received = CompletableDeferred<Unit>()
            server.setRequestHandler {
                received.complete(Unit)
                awaitCancellation()
            }
            val request = async { client.rpc("Pending", byteArrayOf()) }
            received.await()
            channel.close()
            val result = runCatching { withTimeout(2_000L) { request.await() } }
            assertTrue(result.isFailure, "Disconnect must fail pending RPC")
            assertTrue(result.exceptionOrNull() !is TimeoutCancellationException, "Disconnect must wake the RPC before the deadline")
        }
    }
    verifyRealWebRtcSequentialTransfers()
}

internal suspend fun verifyRealWebRtcPauseAndResume() {
    withRealWebRtcSessionPair { client, server, _ ->
        coroutineScope {
            val firstChunk = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var received = 0
            server.setRequestHandler { request ->
                DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, request.payload)
            }
            server.setStreamOpenHandler { streamId, _ ->
                object : DeviceSessionIncomingStream {
                    override suspend fun onData(chunk: ByteArray) {
                        assertRealWebRtcContent(parallelPayload(5, received, chunk.size), chunk)
                        received += chunk.size
                        firstChunk.complete(Unit)
                    }
                    override suspend fun onTrailer(payload: ByteArray) {
                        assertEquals(128 * 1024, received)
                        server.connection.sendTrailer(streamId)
                    }
                }
            }
            val write = async {
                client.openWriteStream("/resume.bin", 128 * 1024L, 0L, data = flow {
                    emit(parallelPayload(5, 0, 65536))
                    resume.await()
                    emit(parallelPayload(5, 65536, 65536))
                })
            }
            firstChunk.await()
            val echo = "while-paused".encodeToByteArray()
            assertContentEquals(echo, client.rpc("Echo", echo).payload)
            assertEquals(65536, received)
            resume.complete(Unit)
            write.await()
            assertEquals(131072, received)
        }
    }
}

internal suspend fun verifyRealWebRtcPeerIsolation() {
    withRealWebRtcSessionPair { _, _, firstChannel ->
        withRealWebRtcSessionPair { client, server, _ ->
            server.setRequestHandler { request ->
                DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, request.payload)
            }
            firstChannel.close()
            val payload = "other-peer-remains-open".encodeToByteArray()
            assertContentEquals(payload, client.rpc("Echo", payload).payload)
        }
    }
}

private fun parallelPayload(stream: Int, offset: Int, size: Int) =
    ByteArray(size) { index -> ((offset + index) * 31 + (offset + index) / 251 + stream * 17).toByte() }

internal fun assertRealWebRtcContent(expected: ByteArray, actual: ByteArray) {
    assertEquals(expected.size, actual.size)
    val mismatch = expected.indices.firstOrNull { expected[it] != actual[it] }
    assertTrue(mismatch == null, "File content differs at byte $mismatch; chunk size=${actual.size}")
}

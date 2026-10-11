package com.folderspan.service.webrtc

import com.folderspan.service.session.DeviceSessionIncomingStream
import com.folderspan.service.session.DeviceSessionWindowPlan
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.time.TimeSource

internal suspend fun measureRealWebRtcSession(sampleMemory: () -> Long = { 0L }) = withContext(Dispatchers.Default) {
    withRealWebRtcSessionPair(plan = DeviceSessionWindowPlan.fromMemory()) { client, server, channel ->
        coroutineScope {
            server.setStreamOpenHandler { streamId, open ->
                object : DeviceSessionIncomingStream {
                    var received = 0L
                    override suspend fun onData(chunk: ByteArray) { received += chunk.size }
                    override suspend fun onTrailer(payload: ByteArray) {
                        assertEquals(open.fileSize, received)
                        server.connection.sendTrailer(streamId)
                    }
                }
            }
            client.openWriteStream("/warmup.bin", 256L * 1024, 0L, data = flow {
                repeat(4) { emit(Random.nextBytes(64 * 1024)) }
            })
            for (concurrency in listOf(1, 4)) {
                repeat(3) { iteration ->
                    var peakBuffered = 0L
                    val baselineMemory = sampleMemory()
                    var peakMemory = baselineMemory
                    val sampling = launch {
                        while (isActive) {
                            peakBuffered = maxOf(peakBuffered, channel.bufferedAmount)
                            peakMemory = maxOf(peakMemory, sampleMemory())
                            delay(2)
                        }
                    }
                    val mark = TimeSource.Monotonic.markNow()
                    (1..concurrency).map { index -> async {
                        client.openWriteStream("/measure-$index.bin", 4L * 1024 * 1024, 0L, data = flow {
                            repeat(64) { emit(Random.nextBytes(64 * 1024)) }
                        })
                    } }.awaitAll()
                    val elapsedMs = mark.elapsedNow().inWholeMilliseconds
                    sampling.cancelAndJoin()
                    println("WEBRTC_BENCH protocol=session iteration=$iteration concurrent=$concurrency bytes=${concurrency * 4L * 1024 * 1024} elapsedMs=$elapsedMs sampledPeakBuffered=$peakBuffered baselineMemory=$baselineMemory peakMemory=$peakMemory")
                }
            }
        }
    }
}

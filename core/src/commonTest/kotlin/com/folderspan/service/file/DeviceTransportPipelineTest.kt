package com.folderspan.service.file

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import strings.AppStrings

class DeviceTransportPipelineTest {
    @Test
    fun pipelineTransfersAllBytesWithConcurrentWorkers() = runSuspendTest {
        val chunkSize = 1024
        val totalBytes = chunkSize * 6 + 137
        val source = ByteArray(totalBytes) { index -> (index % 251).toByte() }
        val target = ByteArray(totalBytes)
        val concurrencyMutex = Mutex()
        var activeReads = 0
        var maxActiveReads = 0
        var activeWrites = 0
        var maxActiveWrites = 0

        val result = runDeviceTransportPipeline(
            totalBytes = totalBytes.toLong(),
            config = DeviceTransportPipelineConfig(
                chunkSize = chunkSize,
                pipelineDepth = 4,
            ),
            readChunk = { _, startOffset, endOffset ->
                concurrencyMutex.withLock {
                    activeReads += 1
                    maxActiveReads = maxOf(maxActiveReads, activeReads)
                }
                try {
                    delay(20.milliseconds)
                    Result.success(source.copyOfRange(startOffset.toInt(), endOffset.toInt()))
                } finally {
                    concurrencyMutex.withLock {
                        activeReads -= 1
                    }
                }
            },
            writeChunk = { chunk ->
                concurrencyMutex.withLock {
                    activeWrites += 1
                    maxActiveWrites = maxOf(maxActiveWrites, activeWrites)
                }
                try {
                    delay(20.milliseconds)
                    chunk.bytes.copyInto(target, destinationOffset = chunk.startOffset.toInt())
                    Result.success(true)
                } finally {
                    concurrencyMutex.withLock {
                        activeWrites -= 1
                    }
                }
            }
        )

        assertTrue(result.isSuccess)
        assertContentEquals(source, target)
        assertTrue(maxActiveReads > 1, "expected concurrent reads, actual=$maxActiveReads")
        assertTrue(maxActiveWrites > 1, "expected concurrent writes, actual=$maxActiveWrites")
    }

    @Test
    fun pipelineUsesIndependentReadAndWriteParallelism() = runSuspendTest {
        val chunkSize = 256
        val totalBytes = chunkSize * 12
        val source = ByteArray(totalBytes) { index -> (index % 127).toByte() }
        val target = ByteArray(totalBytes)
        val concurrencyMutex = Mutex()
        var activeReads = 0
        var maxActiveReads = 0
        var activeWrites = 0
        var maxActiveWrites = 0

        val result = runDeviceTransportPipeline(
            totalBytes = totalBytes.toLong(),
            config = DeviceTransportPipelineConfig(
                chunkSize = chunkSize,
                readParallelism = 4,
                writeParallelism = 1,
                queueDepth = 4,
            ),
            readChunk = { _, startOffset, endOffset ->
                concurrencyMutex.withLock {
                    activeReads += 1
                    maxActiveReads = maxOf(maxActiveReads, activeReads)
                }
                try {
                    delay(20.milliseconds)
                    Result.success(source.copyOfRange(startOffset.toInt(), endOffset.toInt()))
                } finally {
                    concurrencyMutex.withLock {
                        activeReads -= 1
                    }
                }
            },
            writeChunk = { chunk ->
                concurrencyMutex.withLock {
                    activeWrites += 1
                    maxActiveWrites = maxOf(maxActiveWrites, activeWrites)
                }
                try {
                    delay(50.milliseconds)
                    chunk.bytes.copyInto(target, destinationOffset = chunk.startOffset.toInt())
                    Result.success(true)
                } finally {
                    concurrencyMutex.withLock {
                        activeWrites -= 1
                    }
                }
            },
        )

        assertTrue(result.isSuccess)
        assertContentEquals(source, target)
        assertEquals(4, maxActiveReads)
        assertEquals(1, maxActiveWrites)
    }

    @Test
    fun pipelineFailsWhenReadChunkLengthMismatchesRequestedRange() = runSuspendTest {
        val result = runDeviceTransportPipeline(
            totalBytes = 4096L,
            config = DeviceTransportPipelineConfig(
                chunkSize = 1024,
                pipelineDepth = 2,
            ),
            readChunk = { index, _, _ ->
                if (index == 1) {
                    Result.success(ByteArray(128))
                } else {
                    Result.success(ByteArray(1024))
                }
            },
            writeChunk = {
                Result.success(true)
            }
        )

        assertTrue(result.isFailure)
        assertEquals(true, result.exceptionOrNull()?.message?.contains(AppStrings.ui_test_device_transport_pipeline_length))
    }

    @Test
    fun pipelineFailsAndStopsWorkersWhenWriteChunkFails() = runSuspendTest {
        val chunkSize = 512
        val concurrencyMutex = Mutex()
        var activeReads = 0
        var activeWrites = 0

        val result = withTimeout(1_000L.milliseconds) {
            runDeviceTransportPipeline(
                totalBytes = (chunkSize * 8).toLong(),
                config = DeviceTransportPipelineConfig(
                    chunkSize = chunkSize,
                    readParallelism = 3,
                    writeParallelism = 2,
                    queueDepth = 3,
                ),
                readChunk = { _, startOffset, endOffset ->
                    concurrencyMutex.withLock {
                        activeReads += 1
                    }
                    try {
                        delay(10.milliseconds)
                        Result.success(ByteArray((endOffset - startOffset).toInt()))
                    } finally {
                        withContext(NonCancellable) {
                            concurrencyMutex.withLock {
                                activeReads -= 1
                            }
                        }
                    }
                },
                writeChunk = { chunk ->
                    concurrencyMutex.withLock {
                        activeWrites += 1
                    }
                    try {
                        delay(10.milliseconds)
                        if (chunk.index == 2) {
                            Result.failure(IllegalStateException("forced write failure"))
                        } else {
                            Result.success(true)
                        }
                    } finally {
                        withContext(NonCancellable) {
                            concurrencyMutex.withLock {
                                activeWrites -= 1
                            }
                        }
                    }
                },
            )
        }

        assertTrue(result.isFailure)
        assertEquals(true, result.exceptionOrNull()?.message?.contains("forced write failure"))
        concurrencyMutex.withLock {
            assertEquals(0, activeReads)
            assertEquals(0, activeWrites)
        }
    }
}

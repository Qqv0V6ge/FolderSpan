package com.folderspan.ui.state.main

import com.folderspan.service.operation.OperationParallelismConfig
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class TaskFileTransferRecoveryTest {
    @Test
    fun recoveryThresholdStartsAtThirtyChunks() {
        val threshold = taskFileRecoveryMinBytes()

        assertFalse(shouldUseTaskFileRecovery(threshold - 1L))
        assertTrue(shouldUseTaskFileRecovery(threshold))
    }

    @Test
    fun singleFileCopyAndMoveUseChunkOverallProgress() {
        assertTrue(
            shouldUseChunkOverallProgressForSingleFileTask(
                taskType = TaskType.Copy,
                totalEntries = 1,
                stage = TaskRuntimeStage.COPY,
            )
        )
        assertTrue(
            shouldUseChunkOverallProgressForSingleFileTask(
                taskType = TaskType.Move,
                totalEntries = 2,
                stage = TaskRuntimeStage.COPY,
            )
        )
        assertFalse(
            shouldUseChunkOverallProgressForSingleFileTask(
                taskType = TaskType.Copy,
                totalEntries = 3,
                stage = TaskRuntimeStage.COPY,
            )
        )
        assertFalse(
            shouldUseChunkOverallProgressForSingleFileTask(
                taskType = TaskType.Move,
                totalEntries = 2,
                stage = TaskRuntimeStage.DELETE_SOURCE,
            )
        )
    }

    @Test
    fun copyFileWithTransferCheckpointContinuesFromCompletedChunk() = runSuspendTest {
        val sourceBytes = ByteArray(12) { index -> index.toByte() }
        val targetBytes = ByteArray(12) { 0 }
        sourceBytes.copyInto(targetBytes, startIndex = 0, endIndex = 4)
        val readRanges = mutableListOf<Pair<Long, Long>>()
        val savedCheckpoints = mutableListOf<TaskRuntimeTransferCheckpoint>()
        val checkpoint = TaskRuntimeTransferCheckpoint(
            taskKey = 701L,
            entryId = "copy-entry",
            destPath = "/dest/file.bin",
            fileSize = sourceBytes.size.toLong(),
            chunkSize = 4,
            completedChunks = 1,
            createdAt = 1L,
        )

        val result = copyFileWithTransferCheckpoint(
            taskKey = checkpoint.taskKey,
            entryId = checkpoint.entryId,
            destPath = checkpoint.destPath,
            fileSize = checkpoint.fileSize,
            chunkSize = checkpoint.chunkSize,
            loadCheckpoint = { checkpoint },
            saveCheckpoint = { savedCheckpoints += it },
            deleteCheckpoint = {},
            targetSize = { 4L },
            readChunk = { startOffset, endOffset ->
                readRanges += startOffset to endOffset
                Result.success(sourceBytes.copyOfRange(startOffset.toInt(), endOffset.toInt()))
            },
            writeChunk = { _, startOffset, bytes ->
                bytes.copyInto(targetBytes, destinationOffset = startOffset.toInt())
                Result.success(true)
            },
            ensureRunning = {},
        )

        assertTrue(result.getOrThrow())
        assertEquals(listOf(4L to 8L, 8L to 12L), readRanges)
        assertContentEquals(sourceBytes, targetBytes)
        assertEquals(3, savedCheckpoints.last().completedChunks)
    }

    @Test
    fun copyFileWithTransferCheckpointResetsWhenTargetIsMissing() = runSuspendTest {
        val sourceBytes = ByteArray(8) { index -> (index + 1).toByte() }
        val targetBytes = ByteArray(8) { 0 }
        val readRanges = mutableListOf<Pair<Long, Long>>()
        var deletedCheckpoint = false
        val checkpoint = TaskRuntimeTransferCheckpoint(
            taskKey = 702L,
            entryId = "copy-entry",
            destPath = "/dest/file.bin",
            fileSize = sourceBytes.size.toLong(),
            chunkSize = 4,
            completedChunks = 1,
            createdAt = 1L,
        )

        val result = copyFileWithTransferCheckpoint(
            taskKey = checkpoint.taskKey,
            entryId = checkpoint.entryId,
            destPath = checkpoint.destPath,
            fileSize = checkpoint.fileSize,
            chunkSize = checkpoint.chunkSize,
            loadCheckpoint = { checkpoint },
            saveCheckpoint = {},
            deleteCheckpoint = { deletedCheckpoint = true },
            targetSize = { null },
            readChunk = { startOffset, endOffset ->
                readRanges += startOffset to endOffset
                Result.success(sourceBytes.copyOfRange(startOffset.toInt(), endOffset.toInt()))
            },
            writeChunk = { _, startOffset, bytes ->
                bytes.copyInto(targetBytes, destinationOffset = startOffset.toInt())
                Result.success(true)
            },
            ensureRunning = {},
        )

        assertTrue(result.getOrThrow())
        assertTrue(deletedCheckpoint)
        assertEquals(listOf(0L to 4L, 4L to 8L), readRanges)
        assertContentEquals(sourceBytes, targetBytes)
    }

    @Test
    fun copyFileWithTransferCheckpointUsesAdaptiveParallelChunks() = runSuspendTest {
        val sourceBytes = ByteArray(16) { index -> (index + 1).toByte() }
        val targetBytes = ByteArray(16) { 0 }
        val savedCheckpoints = mutableListOf<TaskRuntimeTransferCheckpoint>()
        val activityMutex = Mutex()
        var activeReads = 0
        var maxActiveReads = 0

        val result = copyFileWithTransferCheckpoint(
            taskKey = 703L,
            entryId = "copy-entry",
            destPath = "/dest/file.bin",
            fileSize = sourceBytes.size.toLong(),
            chunkSize = 4,
            loadCheckpoint = { null },
            saveCheckpoint = { savedCheckpoints += it },
            deleteCheckpoint = {},
            targetSize = { 0L },
            readChunk = { startOffset, endOffset ->
                activityMutex.withLock {
                    activeReads++
                    maxActiveReads = maxOf(maxActiveReads, activeReads)
                }
                try {
                    delay(50.milliseconds)
                    Result.success(sourceBytes.copyOfRange(startOffset.toInt(), endOffset.toInt()))
                } finally {
                    activityMutex.withLock {
                        activeReads--
                    }
                }
            },
            writeChunk = { _, startOffset, bytes ->
                bytes.copyInto(targetBytes, destinationOffset = startOffset.toInt())
                Result.success(true)
            },
            ensureRunning = {},
            operationConfig = OperationParallelismConfig(
                initialParallelism = 2,
                maxParallelism = 2,
                queueCapacity = 4,
                hardMaxParallelism = 2,
            ),
            dynamicMaxParallelismProvider = { 2 },
        )

        assertTrue(result.getOrThrow())
        assertTrue(maxActiveReads > 1)
        assertContentEquals(sourceBytes, targetBytes)
        assertEquals(4, savedCheckpoints.last().completedChunks)
    }

    @Test
    fun copyFileWithTransferCheckpointSerializesRangesWhenParallelChunksAreDisabled() = runSuspendTest {
        val sourceBytes = ByteArray(16) { index -> (index + 1).toByte() }
        val targetBytes = ByteArray(16) { 0 }
        val copiedRanges = mutableListOf<Pair<Long, Long>>()
        val activityMutex = Mutex()
        var activeReads = 0
        var maxActiveReads = 0

        val result = copyFileWithTransferCheckpoint(
            taskKey = 705L,
            entryId = "ordered-session-copy",
            destPath = "/dest/file.bin",
            fileSize = sourceBytes.size.toLong(),
            chunkSize = 4,
            loadCheckpoint = { null },
            saveCheckpoint = {},
            deleteCheckpoint = {},
            targetSize = { 0L },
            readChunk = { startOffset, endOffset ->
                activityMutex.withLock {
                    activeReads++
                    maxActiveReads = maxOf(maxActiveReads, activeReads)
                    copiedRanges += startOffset to endOffset
                }
                try {
                    delay(25.milliseconds)
                    Result.success(sourceBytes.copyOfRange(startOffset.toInt(), endOffset.toInt()))
                } finally {
                    activityMutex.withLock { activeReads-- }
                }
            },
            writeChunk = { _, startOffset, bytes ->
                bytes.copyInto(targetBytes, destinationOffset = startOffset.toInt())
                Result.success(true)
            },
            ensureRunning = {},
            operationConfig = OperationParallelismConfig(
                initialParallelism = 4,
                maxParallelism = 4,
                queueCapacity = 4,
                hardMaxParallelism = 4,
            ),
            dynamicMaxParallelismProvider = { 4 },
            allowParallelChunks = false,
        )

        assertTrue(result.getOrThrow())
        assertEquals(1, maxActiveReads)
        assertEquals(listOf(0L to 4L, 4L to 8L, 8L to 12L, 12L to 16L), copiedRanges)
        assertContentEquals(sourceBytes, targetBytes)
    }

    @Test
    fun copyFileWithTransferCheckpointStreamsFromCheckpointToEofOnce() = runSuspendTest {
        val sourceBytes = ByteArray(12) { index -> (index + 1).toByte() }
        val targetBytes = ByteArray(12) { 0 }
        sourceBytes.copyInto(targetBytes, endIndex = 4)
        val streamedRanges = mutableListOf<Pair<Long, Long>>()
        val savedCheckpoints = mutableListOf<TaskRuntimeTransferCheckpoint>()
        val progressSamples = mutableListOf<TaskFileTransferRecoveryProgress>()
        val checkpoint = TaskRuntimeTransferCheckpoint(
            taskKey = 706L,
            entryId = "continuous-session-copy",
            destPath = "/dest/file.bin",
            fileSize = sourceBytes.size.toLong(),
            chunkSize = 4,
            completedChunks = 1,
            createdAt = 1L,
        )

        val result = copyFileWithTransferCheckpoint(
            taskKey = checkpoint.taskKey,
            entryId = checkpoint.entryId,
            destPath = checkpoint.destPath,
            fileSize = checkpoint.fileSize,
            chunkSize = checkpoint.chunkSize,
            loadCheckpoint = { checkpoint },
            saveCheckpoint = { savedCheckpoints += it },
            deleteCheckpoint = {},
            targetSize = { targetBytes.size.toLong() },
            readChunk = { _, _ ->
                Result.failure(AssertionError("continuous copy must not read fixed-size chunks"))
            },
            writeChunk = { _, _, _ ->
                Result.failure(AssertionError("continuous copy must not write fixed-size chunks"))
            },
            ensureRunning = {},
            copyChunkDirectly = { _, _, _ ->
                Result.failure(AssertionError("continuous copy must not open one stream per checkpoint"))
            },
            copyRemainingDirectly = { startOffset, endOffset, onBytesCommitted ->
                streamedRanges += startOffset to endOffset
                val writes = listOf(2, 4, 2)
                var offset = startOffset
                writes.forEach { length ->
                    sourceBytes.copyInto(
                        destination = targetBytes,
                        destinationOffset = offset.toInt(),
                        startIndex = offset.toInt(),
                        endIndex = offset.toInt() + length,
                    )
                    onBytesCommitted(offset, length)
                    offset += length
                }
                Result.success(true)
            },
            allowParallelChunks = false,
            onProgress = { progressSamples += it },
        )

        assertTrue(result.getOrThrow())
        assertEquals(listOf(4L to 12L), streamedRanges)
        assertContentEquals(sourceBytes, targetBytes)
        val persistedChunks = savedCheckpoints.map { it.completedChunks }
        assertTrue(persistedChunks.isNotEmpty())
        assertEquals(3, persistedChunks.last())
        assertTrue(persistedChunks.all { completedChunks -> completedChunks == 1 || completedChunks == 3 })
        assertEquals(listOf(4L, 6L, 10L, 12L), progressSamples.map { it.transferredBytes })
        assertEquals(listOf(1, 1, 2, 3), progressSamples.map { it.completedChunks })
    }

    @Test
    fun continuousCopyCoalescesHighRateCheckpointPersistenceByCommittedBytes() = runSuspendTest {
        val mebibyte = 1024L * 1024L
        val fileSize = 1024L * mebibyte
        val chunkSize = (4L * mebibyte).toInt()
        val localWriteSize = (256L * 1024L).toInt()
        val savedCompletedChunks = mutableListOf<Int>()

        val result = copyFileWithTransferCheckpoint(
            taskKey = 707L,
            entryId = "continuous-checkpoint-coalescing",
            destPath = "/dest/large.bin",
            fileSize = fileSize,
            chunkSize = chunkSize,
            loadCheckpoint = { null },
            saveCheckpoint = { checkpoint -> savedCompletedChunks += checkpoint.completedChunks },
            deleteCheckpoint = {},
            targetSize = { 0L },
            readChunk = { _, _ -> Result.failure(AssertionError("continuous copy must not read chunks")) },
            writeChunk = { _, _, _ -> Result.failure(AssertionError("continuous copy must not write chunks")) },
            ensureRunning = {},
            copyRemainingDirectly = { startOffset, endOffset, onBytesCommitted ->
                var offset = startOffset
                while (offset < endOffset) {
                    val bytesWritten = minOf(localWriteSize.toLong(), endOffset - offset).toInt()
                    onBytesCommitted(offset, bytesWritten)
                    offset += bytesWritten
                }
                Result.success(true)
            },
        )

        assertTrue(result.getOrThrow())
        assertTrue(savedCompletedChunks.isNotEmpty())
        assertEquals(256, savedCompletedChunks.last())
        assertEquals(savedCompletedChunks.sorted().distinct(), savedCompletedChunks)
        assertTrue(savedCompletedChunks.size <= 17)
        assertTrue(
            savedCompletedChunks.all { completedChunks ->
                completedChunks == 0 || completedChunks in 16..256 && completedChunks % 16 == 0
            }
        )
    }

    @Test
    fun continuousCopyConflatesIntermediateCheckpointsButPersistsLatestForTimePolicy() = runSuspendTest {
        val mebibyte = 1024L * 1024L
        val fileSize = 16L * mebibyte
        val chunkSize = mebibyte.toInt()
        val savedCompletedChunks = mutableListOf<Int>()
        var nowMs = 0L

        val result = copyFileWithTransferCheckpoint(
            taskKey = 708L,
            entryId = "continuous-checkpoint-time-bound",
            destPath = "/dest/time-bound.bin",
            fileSize = fileSize,
            chunkSize = chunkSize,
            loadCheckpoint = { null },
            saveCheckpoint = { checkpoint -> savedCompletedChunks += checkpoint.completedChunks },
            deleteCheckpoint = {},
            targetSize = { 0L },
            readChunk = { _, _ -> Result.failure(AssertionError("continuous copy must not read chunks")) },
            writeChunk = { _, _, _ -> Result.failure(AssertionError("continuous copy must not write chunks")) },
            ensureRunning = {},
            copyRemainingDirectly = { startOffset, endOffset, onBytesCommitted ->
                var offset = startOffset
                while (offset < endOffset) {
                    nowMs += 500L
                    onBytesCommitted(offset, chunkSize)
                    offset += chunkSize
                }
                Result.success(true)
            },
            checkpointNowMs = { nowMs },
        )

        assertTrue(result.getOrThrow())
        assertTrue(savedCompletedChunks.isNotEmpty())
        assertEquals(16, savedCompletedChunks.last())
        assertEquals(savedCompletedChunks.sorted().distinct(), savedCompletedChunks)
        assertTrue(savedCompletedChunks.size <= 5)
        assertTrue(
            savedCompletedChunks.all { completedChunks ->
                completedChunks == 0 || completedChunks in 4..16 && completedChunks % 4 == 0
            }
        )
    }

    @Test
    fun copyFileWithTransferCheckpointCanCopyChunkDirectlyWithoutByteArrayRead() = runSuspendTest {
        val sourceBytes = ByteArray(12) { index -> (index + 1).toByte() }
        val targetBytes = ByteArray(12) { 0 }
        val copiedRanges = mutableListOf<Pair<Long, Long>>()
        var readChunkCalls = 0

        val result = copyFileWithTransferCheckpoint(
            taskKey = 704L,
            entryId = "copy-entry",
            destPath = "/dest/file.bin",
            fileSize = sourceBytes.size.toLong(),
            chunkSize = 4,
            loadCheckpoint = { null },
            saveCheckpoint = {},
            deleteCheckpoint = {},
            targetSize = { 0L },
            readChunk = { _, _ ->
                readChunkCalls++
                Result.failure(AssertionError("readChunk should not be called when direct copy is available"))
            },
            writeChunk = { _, _, _ ->
                Result.failure(AssertionError("writeChunk should not be called when direct copy is available"))
            },
            copyChunkDirectly = { _, startOffset, endOffset ->
                copiedRanges += startOffset to endOffset
                sourceBytes.copyInto(
                    destination = targetBytes,
                    destinationOffset = startOffset.toInt(),
                    startIndex = startOffset.toInt(),
                    endIndex = endOffset.toInt(),
                )
                Result.success(true)
            },
            ensureRunning = {},
        )

        assertTrue(result.getOrThrow())
        assertEquals(0, readChunkCalls)
        assertEquals(listOf(0L to 4L, 4L to 8L, 8L to 12L), copiedRanges)
        assertContentEquals(sourceBytes, targetBytes)
    }
}

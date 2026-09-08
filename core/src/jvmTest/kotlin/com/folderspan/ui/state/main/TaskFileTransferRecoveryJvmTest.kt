package com.folderspan.ui.state.main

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TaskFileTransferRecoveryJvmTest {
    @Test
    fun blockedCheckpointPersistenceDoesNotStopContinuousCommittedByteFlow() = runBlocking {
        val mebibyte = 1024L * 1024L
        val fileSize = 128L * mebibyte
        val chunkSize = (4L * mebibyte).toInt()
        val localWriteSize = (256L * 1024L).toInt()
        val checkpointStarted = CountDownLatch(1)
        val checkpointRelease = CountDownLatch(1)
        val flowContinued = CountDownLatch(1)
        val savedCompletedChunks = ConcurrentLinkedQueue<Int>()

        val copy = async(Dispatchers.Default) {
            copyFileWithTransferCheckpoint(
                taskKey = 709L,
                entryId = "blocked-continuous-checkpoint",
                destPath = "/dest/blocked-checkpoint.bin",
                fileSize = fileSize,
                chunkSize = chunkSize,
                loadCheckpoint = { null },
                saveCheckpoint = { checkpoint ->
                    savedCompletedChunks += checkpoint.completedChunks
                    if (checkpoint.completedChunks == 16) {
                        checkpointStarted.countDown()
                        checkpointRelease.await(5L, TimeUnit.SECONDS)
                    }
                },
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
                        if (offset >= 80L * mebibyte) {
                            flowContinued.countDown()
                        }
                    }
                    Result.success(true)
                },
            )
        }

        try {
            assertTrue(checkpointStarted.await(1L, TimeUnit.SECONDS))
            assertTrue(
                flowContinued.await(1L, TimeUnit.SECONDS),
                "checkpoint persistence must not block committed-byte flow",
            )
        } finally {
            checkpointRelease.countDown()
        }

        assertTrue(withTimeout(5.seconds) { copy.await() }.getOrThrow())
        assertEquals(32, savedCompletedChunks.last())
    }

    @Test
    fun cancellationDoesNotWaitForBlockedCheckpointPersistence() = runBlocking {
        val mebibyte = 1024L * 1024L
        val fileSize = 128L * mebibyte
        val chunkSize = (4L * mebibyte).toInt()
        val checkpointStarted = CountDownLatch(1)
        val checkpointRelease = CountDownLatch(1)

        val copy = async(Dispatchers.Default) {
            copyFileWithTransferCheckpoint(
                taskKey = 710L,
                entryId = "cancel-blocked-checkpoint",
                destPath = "/dest/cancel-blocked-checkpoint.bin",
                fileSize = fileSize,
                chunkSize = chunkSize,
                loadCheckpoint = { null },
                saveCheckpoint = { checkpoint ->
                    if (checkpoint.completedChunks == 16) {
                        checkpointStarted.countDown()
                        checkpointRelease.await(5L, TimeUnit.SECONDS)
                    }
                },
                deleteCheckpoint = {},
                targetSize = { 0L },
                readChunk = { _, _ -> Result.failure(AssertionError("continuous copy must not read chunks")) },
                writeChunk = { _, _, _ -> Result.failure(AssertionError("continuous copy must not write chunks")) },
                ensureRunning = { yield() },
                copyRemainingDirectly = { startOffset, endOffset, onBytesCommitted ->
                    var offset = startOffset
                    while (offset < endOffset) {
                        val bytesWritten = minOf(chunkSize.toLong(), endOffset - offset).toInt()
                        onBytesCommitted(offset, bytesWritten)
                        offset += bytesWritten
                    }
                    Result.success(true)
                },
            )
        }

        try {
            assertTrue(checkpointStarted.await(1L, TimeUnit.SECONDS))
            copy.cancel()
            withTimeout(1.seconds) { copy.join() }
            assertTrue(copy.isCancelled)
        } finally {
            checkpointRelease.countDown()
        }
    }

    @Test
    fun successfulCopyRequiresPersistedEofCheckpoint() = runBlocking {
        val eofFailure = IllegalStateException("checkpoint storage failed")

        val result = copyFileWithTransferCheckpoint(
            taskKey = 711L,
            entryId = "failed-eof-checkpoint",
            destPath = "/dest/failed-eof-checkpoint.bin",
            fileSize = 8L,
            chunkSize = 4,
            loadCheckpoint = { null },
            saveCheckpoint = { checkpoint ->
                if (checkpoint.completedChunks == 2) throw eofFailure
            },
            deleteCheckpoint = {},
            targetSize = { 0L },
            readChunk = { _, _ -> Result.failure(AssertionError("continuous copy must not read chunks")) },
            writeChunk = { _, _, _ -> Result.failure(AssertionError("continuous copy must not write chunks")) },
            ensureRunning = {},
            copyRemainingDirectly = { startOffset, endOffset, onBytesCommitted ->
                onBytesCommitted(startOffset, (endOffset - startOffset).toInt())
                Result.success(true)
            },
        )

        assertEquals(eofFailure, result.exceptionOrNull())
    }
}

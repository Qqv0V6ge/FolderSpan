package com.folderspan.service.http.clipboard

import com.folderspan.utils.WebInMemoryFileStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Clock
import strings.AppStrings

data class ClipboardStagedDownload(
    val id: String,
    val displayName: String,
    val size: Long,
    val contentType: String?,
    val localPath: String? = null,
    val bytes: ByteArray? = null,
    val release: () -> Unit,
)

interface ClipboardDownloadStagingBatch {
    suspend fun resetPart(index: Int)
    suspend fun append(index: Int, bytes: ByteArray)
    suspend fun partSize(index: Int): Long
    suspend fun publish(
        partIndices: List<Int>,
        displayName: String,
        contentType: String?,
        expectedBytes: Long,
    ): ClipboardStagedDownload

    suspend fun cleanup()
}

fun interface ClipboardDownloadStagingFactory {
    suspend fun create(): ClipboardDownloadStagingBatch
}

expect fun createPlatformClipboardDownloadStagingFactory(): ClipboardDownloadStagingFactory

internal class MemoryClipboardDownloadStagingFactory : ClipboardDownloadStagingFactory {
    override suspend fun create(): ClipboardDownloadStagingBatch = MemoryClipboardDownloadStagingBatch()
}

internal class WebClipboardDownloadStagingFactory : ClipboardDownloadStagingFactory {
    override suspend fun create(): ClipboardDownloadStagingBatch =
        WebClipboardDownloadStagingBatch(
            delegate = MemoryClipboardDownloadStagingFactory().create(),
        )
}

private class WebClipboardDownloadStagingBatch(
    private val delegate: ClipboardDownloadStagingBatch,
) : ClipboardDownloadStagingBatch by delegate {
    override suspend fun publish(
        partIndices: List<Int>,
        displayName: String,
        contentType: String?,
        expectedBytes: Long,
    ): ClipboardStagedDownload {
        val memory = delegate.publish(partIndices, displayName, contentType, expectedBytes)
        val bytes = requireNotNull(memory.bytes)
        val root = "/clipboard-url-download/${memory.id}"
        val filePath = "$root/${memory.displayName}"
        return try {
            WebInMemoryFileStore.createDirectory("/clipboard-url-download").getOrThrow()
            WebInMemoryFileStore.createDirectory(root).getOrThrow()
            WebInMemoryFileStore.writeBytes(filePath, memory.size, bytes, 0L).getOrThrow()
            memory.copy(
                localPath = filePath,
                bytes = null,
                release = { WebInMemoryFileStore.deleteDirectory(root) },
            )
        } catch (error: Throwable) {
            WebInMemoryFileStore.deleteDirectory(root)
            memory.release()
            throw error
        }
    }
}

private class MemoryClipboardDownloadStagingBatch : ClipboardDownloadStagingBatch {
    private val id = newClipboardStagingId()
    private val mutex = Mutex()
    private val parts = mutableMapOf<Int, MutableList<ByteArray>>()
    private var cleaned = false

    override suspend fun resetPart(index: Int) = mutex.withLock {
        ensureActive()
        parts.remove(index)
        Unit
    }

    override suspend fun append(index: Int, bytes: ByteArray) = mutex.withLock {
        ensureActive()
        parts.getOrPut(index) { mutableListOf() }.add(bytes.copyOf())
        Unit
    }

    override suspend fun partSize(index: Int): Long = mutex.withLock {
        parts[index].orEmpty().sumOf { bytes -> bytes.size.toLong() }
    }

    override suspend fun publish(
        partIndices: List<Int>,
        displayName: String,
        contentType: String?,
        expectedBytes: Long,
    ): ClipboardStagedDownload = mutex.withLock {
        ensureActive()
        val orderedChunks = partIndices.flatMap { index -> parts[index].orEmpty() }
        val actualBytes = orderedChunks.sumOf { bytes -> bytes.size.toLong() }
        if (actualBytes != expectedBytes) throw ClipboardDownloadLengthMismatchException()
        val merged = ByteArray(actualBytes.toIntExact())
        var offset = 0
        orderedChunks.forEach { chunk ->
            chunk.copyInto(merged, destinationOffset = offset)
            offset += chunk.size
        }
        parts.clear()
        cleaned = true
        ClipboardStagedDownload(
            id = id,
            displayName = displayName,
            size = actualBytes,
            contentType = contentType,
            bytes = merged,
            release = {},
        )
    }

    override suspend fun cleanup() = mutex.withLock {
        cleaned = true
        parts.clear()
    }

    private fun ensureActive() {
        check(!cleaned) { AppStrings.ui_batch_staging_has_been_cleared }
    }
}

class ClipboardDownloadStagingException : Exception()
class ClipboardDownloadLengthMismatchException : Exception()

internal fun newClipboardStagingId(): String = buildString {
    append(Clock.System.now().toEpochMilliseconds())
    append('-')
    append(Random.nextInt().toUInt().toString(16))
}

private fun Long.toIntExact(): Int {
    if (this > Int.MAX_VALUE) throw ClipboardDownloadStagingException()
    return toInt()
}

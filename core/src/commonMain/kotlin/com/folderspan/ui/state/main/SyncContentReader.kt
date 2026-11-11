package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.ChunkReadableNetworkAccess
import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import korlibs.crypto.SHA256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun readVerifiedSyncEndpointState(
    operations: SyncEndpointFileOperations,
    type: SyncEndpointType,
    ref: String,
    desk: DiskBase,
    file: FileSimpleInfo,
    task: Task,
    onRead: (Int) -> Unit = {},
    ensureRunning: suspend () -> Unit = { currentCoroutineContext().ensureActive() },
): Result<SyncEntryState> = try {
    ensureRunning()
    val before = syncComparisonTargetFile(desk,
        operations.readPathInfo(type, ref, desk, file.path, file).getOrThrow() ?: error("File disappeared before comparison"))
    check(!before.isSymbolicLink && before.syncEntryState() == file.syncEntryState()) { "File changed before comparison" }
    val hash = readSyncEndpointSha256(type, desk, before, task, onRead, ensureRunning).getOrThrow()
    ensureRunning()
    val after = syncComparisonTargetFile(desk,
        operations.readPathInfo(type, ref, desk, file.path, before).getOrThrow() ?: error("File disappeared during comparison"))
    check(!after.isSymbolicLink && after.syncEntryState() == before.syncEntryState()) { "File changed during comparison" }
    Result.success(after.syncEntryState().copy(contentSha256 = hash))
} catch (cancelled: CancellationException) { throw cancelled }
catch (error: Exception) { Result.failure(error) }

internal suspend fun syncStreamingContentSha256(
    expectedSize: Long,
    readChunks: suspend (suspend (ByteArray) -> Unit) -> Unit,
    onRead: (Int) -> Unit = {},
    ensureRunning: suspend () -> Unit = { currentCoroutineContext().ensureActive() },
): String {
    require(expectedSize >= 0) { "File size is unknown" }
    val digest = SHA256()
    var received = 0L
    ensureRunning()
    readChunks { bytes ->
        ensureRunning()
        check(bytes.size.toLong() <= expectedSize - received) { "File length changed while comparing" }
        digest.update(bytes)
        received += bytes.size
        onRead(bytes.size)
    }
    ensureRunning()
    check(received == expectedSize) { "File length changed while comparing" }
    return digest.digest().hexLower
}

internal suspend fun readSyncEndpointSha256(
    type: SyncEndpointType,
    desk: DiskBase,
    file: FileSimpleInfo,
    task: Task,
    onRead: (Int) -> Unit = {},
    ensureRunning: suspend () -> Unit = { currentCoroutineContext().ensureActive() },
): Result<String> = try {
    ensureRunning()
    require(!file.isDirectory && !file.isSymbolicLink && file.size >= 0) { "File state cannot be compared" }
    val result = when (type) {
        SyncEndpointType.Local -> Result.success(storageUploadSourceDigest(file.size, ensureRunning) { start, end ->
            FileUtils.readFileRange(FileAccessPermission.Allowed, file.path, start, end).onSuccess { onRead(it.size) }
        })
        SyncEndpointType.Device -> {
            val device = desk as? Device ?: error("Device endpoint unavailable")
            Result.success(storageUploadSourceDigest(file.size, ensureRunning) { start, end ->
                device.files.readBytes(file.path, start, end).onSuccess { onRead(it.size) }
            })
        }
        SyncEndpointType.Network -> {
            val reader = desk as? ChunkReadableNetworkAccess ?: throw NetworkUnsupportedException("Content comparison is unsupported")
            Result.success(syncStreamingContentSha256(file.size, { consume ->
                check(reader.downloadFileByChunks(task, file) { bytes, _ ->
                    consume(bytes)
                    Result.success(Unit)
                }.getOrThrow()) { "Content comparison read failed" }
            }, onRead, ensureRunning))
        }
    }
    ensureRunning()
    result
} catch (cancelled: CancellationException) { throw cancelled }
catch (error: Exception) { Result.failure(error) }

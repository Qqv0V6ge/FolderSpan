package com.folderspan.service.network

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ui.state.main.TaskUploadSourceIdentity
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** No consumer sees bytes until the complete managed file has been authenticated. */
internal suspend fun downloadVerifiedStorageChunks(
    provider: StorageProvider,
    config: NetworkConnectionConfig,
    taskKey: Long,
    file: FileSimpleInfo,
    ensureRunning: suspend () -> Unit,
    onChunk: suspend (ByteArray, Long) -> Result<Unit>,
    content: StorageContentOperations = StorageContentPlatform,
): Result<Boolean> {
    var ciphertext: String? = null
    var plaintext: String? = null
    return try {
        ensureRunning()
        storageContentIdentityError(config, file.storagePath)?.let { throw it }
        val resolved = content.resolveDownload(config, file).getOrThrow()
        if (resolved == null && storageContentContains(config, file.storagePath)) throw StorageEntryNotFoundException()
        val frozen = resolved?.file ?: file
        ciphertext = content.createStage(taskKey, "download-input:" + file.path).getOrThrow()
        plaintext = content.createStage(taskKey, "verified-output:" + file.path).getOrThrow()
        val expectedIdentity = content.stageIdentity(taskKey, ciphertext).getOrThrow()
        var downloadFailure: Throwable? = null
        try {
            val grant = TaskUploadSourceIdentity(frozen, ciphertext, expectedIdentity, taskKey, content, true)
            val downloaded = withContext(grant) {
                provider.download(resolved?.target ?: file.storagePath, ciphertext, resolved?.size ?: file.size)
            }
            downloadFailure = downloaded.exceptionOrNull()
            check(downloaded.getOrThrow()) { "Content download failed" }
        } catch (error: Throwable) {
            downloadFailure = error
            throw error
        } finally {
            finishStorageContentStageWrite(content, taskKey, ciphertext, expectedIdentity, downloadFailure)
        }
        ensureRunning()
        check(content.publishDownload(config, taskKey, frozen, ciphertext, plaintext).getOrThrow()) { "Content verification failed" }
        val size = FileUtils.getFile(FileAccessPermission.Allowed, plaintext).getOrThrow().size
        var offset = 0L
        while (offset < size) {
            ensureRunning()
            val end = minOf(offset + 1024 * 1024, size)
            val bytes = FileUtils.readFileRange(FileAccessPermission.Allowed, plaintext, offset, end).getOrThrow()
            check(bytes.size.toLong() == end - offset) { "Verified content cache changed" }
            onChunk(bytes, size).getOrThrow()
            offset = end
        }
        Result.success(true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    } finally {
        withContext(NonCancellable) {
            ciphertext?.let { FileUtils.deleteFile(FileAccessPermission.Allowed, it) }
            plaintext?.let { FileUtils.deleteFile(FileAccessPermission.Allowed, it) }
        }
    }
}


/** Adopt a legitimate atomic download replacement without replacing the original transfer failure. */
internal suspend fun finishStorageContentStageWrite(content: StorageContentOperations, taskKey: Long,
    path: String, identity: String, originalFailure: Throwable?) {
    val finalized = withContext(NonCancellable) {
        runCatching { content.completeStageWrite(taskKey, path, identity).getOrThrow() }
    }
    finalized.exceptionOrNull()?.let { failure ->
        if (originalFailure == null) throw failure
        if (failure !== originalFailure) originalFailure.addSuppressed(failure)
    }
}

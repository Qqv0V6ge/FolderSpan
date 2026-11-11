package com.folderspan.service.network

import com.folderspan.createSettings
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.ui.state.main.TaskRuntimeEndpointRef
import com.folderspan.ui.state.main.TaskUploadSourceIdentity
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.russhwolf.settings.Settings
import korlibs.crypto.SHA256
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.random.Random

internal fun storageContentContains(config: NetworkConnectionConfig, path: StoragePath): Boolean {
    if (path.reference?.content != null) return true
    val root = config.extras.contentEncryption?.root?.path ?: return false
    return isRemotePathWithinRoot(root, path.path, config.pathSeparator)
}

internal fun storageContentIdentityError(config: NetworkConnectionConfig, path: StoragePath): StorageContentException? {
    val reference = path.reference ?: return null
    val entry = reference.content
    val opaque = listOf(reference.id, reference.parentId, reference.ancestorId).any {
        it.startsWith("fsp-content:") || it.startsWith("fsp-ordinary:")
    }
    return if ((entry != null && entry.vaultId != config.extras.contentEncryption?.vaultId) ||
        entry == null && opaque) StorageContentException(StorageContentErrorCode.SourceChanged,
        "The file reference no longer belongs to this password location") else null
}

internal fun storageContentAncestor(config: NetworkConnectionConfig, path: StoragePath): Boolean {
    val root = config.extras.contentEncryption?.root?.path ?: return false
    return !storageContentContains(config, path) && isRemotePathWithinRoot(path.path, root, config.pathSeparator)
}

private class ForegroundContentLock(val mutex: Mutex = Mutex(), var users: Int = 0)
private val foregroundContentRegistry = Mutex()
private val foregroundContentLocks = mutableMapOf<String, ForegroundContentLock>()

private suspend fun <T> withForegroundContentLock(key: String, block: suspend () -> T): T {
    val lock = foregroundContentRegistry.withLock {
        foregroundContentLocks.getOrPut(key) { ForegroundContentLock() }.also { it.users++ }
    }
    return try { lock.mutex.withLock { block() } }
    finally {
        withContext(NonCancellable) {
            foregroundContentRegistry.withLock {
                if (--lock.users == 0) foregroundContentLocks.remove(key)
            }
        }
    }
}

private fun foregroundContentKey(config: NetworkConnectionConfig, source: FileSimpleInfo, target: StoragePath): String {
    val cloud = config.extras.cloud
    val identity = Json.encodeToString(listOf(
        config.protocol, config.host.substringBefore('?').substringBefore('#').substringAfterLast('@'), config.username,
        cloud.accountId, cloud.connectionId, cloud.rootId, cloud.libraryId,
        config.extras.contentEncryption?.vaultId.orEmpty(),
        config.extras.contentEncryption?.root?.let { Json.encodeToString(StoragePath.serializer(), it) }.orEmpty(),
        WebUiAttachment.target?.origin.orEmpty(),
        Json.encodeToString(TaskRuntimeEndpointRef.serializer(),
            TaskRuntimeEndpointRef(source.protocol, source.protocolId, source.path, source.storageReference)),
        Json.encodeToString(StoragePath.serializer(), target),
    ))
    val hash = SHA256()
    hash.update(identity.encodeToByteArray())
    return "auth.storage.fg." + hash.digest().hexLower
}

/** Editors use persistent operation ownership so an uncertain write is reconciled before another raw put. */
internal suspend fun uploadManagedStorageContent(
    provider: StorageProvider,
    config: NetworkConnectionConfig,
    target: StoragePath,
    size: Long,
    source: FileSimpleInfo? = null,
    onProgress: (Long, Long) -> Unit,
    readChunk: suspend () -> ByteArray?,
    content: StorageContentOperations = StorageContentPlatform,
    settings: Settings = createSettings(),
): Result<Boolean>? {
    storageContentIdentityError(config, target)?.let { return Result.failure(it) }
    if (storageContentAncestor(config, target)) return Result.failure(StorageContentException(
        StorageContentErrorCode.Conflict, "A password location parent cannot be overwritten by an ordinary upload"))
    val passwordLocation = config.extras.contentEncryption != null
    if (passwordLocation && !storageContentContains(config, target)) {
        content.beginUpload(config, 0, "ordinary", target).getOrElse { return Result.failure(it) }
        return null
    }
    val state = content.status(config).getOrElse { return Result.failure(it) }
    if (!passwordLocation && !state.enabled) {
        content.beginUpload(config, 0, "ordinary", target).getOrElse { return Result.failure(it) }
        return null
    }
    val identity = source ?: FileSimpleInfo.nullFileSimpleInfo().copy(path = target.path, size = size)
    val key = foregroundContentKey(config, identity, target)
    return withForegroundContentLock(key) {
        val taskKey = try {
            val existing = settings.getStringOrNull(key)
            val parsed = existing?.toLongOrNull()
            if (existing != null && (parsed == null || parsed <= 0 || parsed.toString() != existing)) {
                throw StorageContentException(StorageContentErrorCode.DamagedLocation,
                    "Protected content operation identity is invalid")
            }
            val operation = parsed ?: Random.nextLong(1, Long.MAX_VALUE)
            // Confirm durable ownership before the execution host can persist a ciphertext spool.
            settings.putString(key, operation.toString())
            operation
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { return@withForegroundContentLock Result.failure<Boolean>(error) }
        var completed = false
        try {
            if (!content.uploadEncryptionEnabled(config, taskKey, "foreground").getOrThrow()) {
                content.beginUpload(config, taskKey, "foreground", target).getOrThrow()
                val actual = content.resolveUploadTarget(config, target).getOrThrow()
                val result = provider.uploadFromSource(actual, size, onProgress, readChunk)
                completed = result.getOrThrow()
                result
            } else {
                check(state.unlocked) { "Content encryption is locked; enter the content password" }
                require(size >= 0) { "Encrypted upload requires a known source size" }
                val stage = content.createStage(taskKey, "foreground").getOrThrow()
                try {
                    var offset = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val bytes = readChunk() ?: break
                        check(bytes.isNotEmpty() && bytes.size.toLong() <= size - offset) { "Upload source length changed" }
                        check(FileUtils.writeBytes(FileAccessPermission.Allowed, stage, size, bytes, offset).getOrThrow()) {
                            "Failed to stage encrypted upload"
                        }
                        offset += bytes.size
                    }
                    check(offset == size) { "Upload source length changed" }
                    val prepared = content.prepareUpload(config, taskKey, "foreground", identity, stage).getOrThrow()
                        ?: error("Content encryption policy changed while preparing this upload")
                    content.beginUpload(config, taskKey, "foreground", target).getOrThrow()
                    if (!prepared.committed) {
                        val actual = prepared.target ?: content.resolveUploadTarget(config, target).getOrThrow()
                        if (!prepared.uploaded) {
                            val sourceIdentity = content.stageIdentity(taskKey, prepared.path).getOrThrow()
                            val grant = TaskUploadSourceIdentity(identity, prepared.path, sourceIdentity, taskKey, content, false)
                            check(withContext(grant) { provider.upload(prepared.path, actual, prepared.size, onProgress) }.getOrThrow()) {
                                "Content upload failed"
                            }
                        }
                        content.recordUploaded(config, taskKey, "foreground", provider.stat(actual).getOrThrow()).getOrThrow()
                    }
                    completed = true
                    onProgress(prepared.size, prepared.size)
                    Result.success(true)
                } finally {
                    withContext(NonCancellable) { FileUtils.deleteFile(FileAccessPermission.Allowed, stage) }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { Result.failure(error) }
        finally {
            if (completed) settings.remove(key)
            if (completed) withContext(NonCancellable) { content.cleanup(taskKey) }
        }
    }
}

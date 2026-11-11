package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.network.*
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import korlibs.crypto.SHA256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal class TaskUploadSourceIdentity(
    val source: FileSimpleInfo,
    internal val privateStagePath: String? = null,
    internal val privateStageIdentity: String = "",
    internal val privateStageTaskKey: Long? = null,
    internal val privateStageOperations: StorageContentOperations? = null,
    internal val privateStageWritable: Boolean = true,
) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TaskUploadSourceIdentity>
}

/** Null means the backend requires the existing file-level retry path. */
internal suspend fun uploadTaskFileWithCheckpoint(
    task: Task,
    taskState: TaskState,
    provider: StorageProvider,
    config: NetworkConnectionConfig,
    source: FileSimpleInfo,
    target: FileSimpleInfo,
    connectionId: String,
    ensureRunning: suspend () -> Unit,
    readRange: suspend (Long, Long) -> Result<ByteArray>,
    onProgress: (Long, Long) -> Unit,
): Result<Boolean>? {
    storageContentIdentityError(config, target.storagePath)?.let { return Result.failure(it) }
    if (storageContentAncestor(config, target.storagePath)) return Result.failure(StorageContentException(
        StorageContentErrorCode.Conflict, "A password location parent cannot be overwritten by an ordinary upload"))
    val identity = currentCoroutineContext()[TaskUploadSourceIdentity]?.source ?: source
    val sourceRef = TaskRuntimeEndpointRef(identity.protocol, identity.protocolId, identity.path, identity.storageReference)
    val entryId = buildCopyRetryEntry(task.taskType, identity, target).entryKey
    var prepared: StoragePreparedContentUpload? = null
    try {
        val scoped = config.extras.contentEncryption == null || storageContentContains(config, target.storagePath)
        if (scoped && StorageContentPlatform.uploadEncryptionEnabled(config, task.key, entryId).getOrThrow()) {
            val content = StorageContentPlatform.status(config).getOrThrow()
            check(content.unlocked) { "Content encryption is locked; enter the content password" }
            require(source.size >= 0) { "Encrypted upload requires a known source size" }
            val stage = StorageContentPlatform.createStage(task.key, entryId).getOrThrow()
            try {
                var offset = 0L
                while (offset < source.size) {
                    ensureRunning()
                    val end = minOf(offset + 1024 * 1024, source.size)
                    val bytes = readRange(offset, end).getOrThrow()
                    check(bytes.size.toLong() == end - offset) { "Upload source length changed" }
                    check(FileUtils.writeBytes(FileAccessPermission.Allowed, stage, source.size, bytes, offset).getOrThrow()) {
                        "Failed to stage encrypted upload"
                    }
                    offset = end
                }
                ensureRunning()
                prepared = StorageContentPlatform.prepareUpload(
                    config, task.key, entryId, identity.copy(size = source.size), stage,
                ).getOrThrow() ?: error("Content encryption was disabled while preparing this upload")
            } finally {
                withContext(NonCancellable) { FileUtils.deleteFile(FileAccessPermission.Allowed, stage) }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        return Result.failure(error)
    }
    try { StorageContentPlatform.beginUpload(config, task.key, entryId, target.storagePath).getOrThrow() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Throwable) { return Result.failure(error) }
    val upload = prepared
    suspend fun forgetConfirmedContentCheckpoint() {
        val checkpoint = taskState.loadRuntimeTransferCheckpoint(task.key, entryId)
        checkpoint?.uploadSession?.let { provider.deleteUploadSession(task.key, it).getOrThrow() }
        taskState.deleteRuntimeTransferCheckpoint(task.key, entryId)
    }
    if (upload?.committed == true) {
        try { forgetConfirmedContentCheckpoint() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { return Result.failure(error) }
        onProgress(upload.size, upload.size)
        return Result.success(true)
    }
    val actualTarget = try {
        upload?.target ?: StorageContentPlatform.resolveUploadTarget(config, target.storagePath).getOrThrow()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Throwable) { return Result.failure(error) }
    if (upload?.uploaded == true) {
        return try {
            StorageContentPlatform.recordUploaded(config, task.key, entryId, provider.stat(actualTarget).getOrThrow()).getOrThrow()
            forgetConfirmedContentCheckpoint()
            onProgress(upload.size, upload.size)
            Result.success(true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { Result.failure(error) }
    }
    val targetRef = TaskRuntimeEndpointRef(target.protocol, target.protocolId, actualTarget.path, actualTarget.reference)
    val fileSize = upload?.size ?: source.size
    val uploadRead: suspend (Long, Long) -> Result<ByteArray> = if (upload == null) readRange
        else { start, end -> FileUtils.readFileRange(FileAccessPermission.Allowed, upload.path, start, end) }
    if (fileSize < 0) return null
    val capabilities = provider.advancedCapabilities(actualTarget).getOrElse { return Result.failure(it) }
    when (capabilities.availability(StorageAdvancedFeature.RESUMABLE_UPLOAD)) {
        StorageFeatureAvailability.UNSUPPORTED -> {
            if (upload == null) return null
            return try {
                val sourceIdentity = StorageContentPlatform.stageIdentity(task.key, upload.path).getOrThrow()
                val result = withContext(TaskUploadSourceIdentity(identity, upload.path, sourceIdentity, task.key, StorageContentPlatform, false)) {
                    provider.upload(upload.path, actualTarget, upload.size, onProgress)
                }
                if (result.getOrDefault(false)) {
                    StorageContentPlatform.recordUploaded(config, task.key, entryId, provider.stat(actualTarget).getOrThrow()).getOrThrow()
                }
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { Result.failure(error) }
        }
        StorageFeatureAvailability.AVAILABLE -> Unit
        else -> return Result.failure(StorageFeatureException(capabilities.availability(StorageAdvancedFeature.RESUMABLE_UPLOAD)))
    }
    val constraints = capabilities.upload
        ?: return Result.failure(IllegalStateException("Storage upload constraints are missing"))
    return recoverStorageUpload(
        taskKey = task.key, entryId = entryId, provider = provider,
        source = sourceRef, target = targetRef, fileSize = fileSize, connectionId = connectionId,
        constraints = constraints, ensureRunning = ensureRunning, readRange = uploadRead,
        loadCheckpoint = { taskState.loadRuntimeTransferCheckpoint(task.key, entryId) },
        saveCheckpoint = taskState::saveRuntimeTransferCheckpoint,
        deleteCheckpoint = { taskState.deleteRuntimeTransferCheckpoint(task.key, entryId) },
        cancelledByUser = { taskState.isTaskCancelled(task.key) },
        onProgress = onProgress,
        onCompleted = if (upload == null) null else { entry ->
            StorageContentPlatform.recordUploaded(config, task.key, entryId, entry).getOrThrow()
        },
    )
}

internal suspend fun recoverStorageUpload(
    taskKey: Long,
    entryId: String,
    provider: StorageProvider,
    source: TaskRuntimeEndpointRef,
    target: TaskRuntimeEndpointRef,
    fileSize: Long,
    connectionId: String,
    constraints: StorageUploadConstraints,
    ensureRunning: suspend () -> Unit,
    readRange: suspend (Long, Long) -> Result<ByteArray>,
    loadCheckpoint: () -> TaskRuntimeTransferCheckpoint?,
    saveCheckpoint: (TaskRuntimeTransferCheckpoint) -> Unit,
    deleteCheckpoint: () -> Unit,
    cancelledByUser: () -> Boolean = { false },
    onProgress: (Long, Long) -> Unit = { _, _ -> },
    onCompleted: (suspend (NetworkFileEntry) -> Unit)? = null,
): Result<Boolean> {
    var session: StorageUploadSession? = null
    var reference: TaskRuntimeUploadSessionReference? = null
    val chunkSize = constraints.maxChunkSize / constraints.alignment * constraints.alignment
    val now = Clock.System.now().toEpochMilliseconds()
    var checkpointCreatedAt = now

    suspend fun persist(value: StorageUploadSession) {
        check(value.size == fileSize && value.target.path == target.path) { "Upload session target changed" }
        session?.let { previous -> check(previous.target == value.target) { "Upload resolved target changed" } }
        check(value.status != StorageUploadStatus.COMPLETED || value.confirmedOffset == fileSize) {
            "Storage upload completion is not confirmed"
        }
        val updated = checkNotNull(reference).copy(confirmedOffset = value.confirmedOffset)
        provider.saveUploadSession(taskKey, updated, value).getOrThrow()
        reference = updated
        saveCheckpoint(TaskRuntimeTransferCheckpoint(
            schemaVersion = 4, taskKey = taskKey, entryId = entryId, destPath = target.path,
            fileSize = fileSize, chunkSize = chunkSize, createdAt = checkpointCreatedAt,
            updatedAt = Clock.System.now().toEpochMilliseconds(), uploadSession = updated,
        ))
        session = value
    }

    suspend fun forget() {
        provider.deleteUploadSession(taskKey, checkNotNull(reference)).getOrThrow()
        deleteCheckpoint()
    }

    return try {
        require(fileSize in 0..constraints.maxFileSize) { "Storage upload file size is unsupported" }
        val owner = provider.uploadSessionOwnerId().getOrThrow()
        val old = loadCheckpoint()?.also { it.requireSupportedFormat() }
        val oldReference = old?.uploadSession
        if (oldReference != null) {
            check(old.taskKey == taskKey && old.entryId == entryId && old.fileSize == fileSize &&
                old.destPath == target.path && oldReference.ownerRuntimeId == owner &&
                oldReference.connectionId == connectionId && oldReference.source == source &&
                oldReference.target == target) { "Upload checkpoint identity changed" }
            checkpointCreatedAt = old.createdAt
        }
        val sourceVersion = storageUploadSourceDigest(fileSize, ensureRunning, readRange)
        if (oldReference != null) {
            check(oldReference.sourceVersion == sourceVersion) { "Upload source changed; restart this file explicitly" }
            reference = oldReference
            val stored = provider.loadUploadSession(taskKey, oldReference).getOrThrow()
                ?: error("Upload session is unavailable; restart this file explicitly")
            session = stored
            persist(provider.queryUpload(stored).getOrThrow())
        } else {
            reference = TaskRuntimeUploadSessionReference(
                sessionId = Uuid.random().toString(), ownerRuntimeId = owner, connectionId = connectionId,
                source = source, target = target, sourceVersion = sourceVersion,
            )
            ensureRunning()
            persist(provider.startUpload(StoragePath(target.path, target.storageReference), fileSize).getOrThrow())
        }
        var current = checkNotNull(session)
        when (current.status) {
            StorageUploadStatus.COMPLETED -> {
                onCompleted?.invoke(current.entry ?: provider.stat(current.target).getOrThrow())
                forget(); onProgress(fileSize, fileSize); return Result.success(true)
            }
            StorageUploadStatus.EXPIRED -> error("Upload session expired; restart this file explicitly")
            StorageUploadStatus.CANCELLED -> error("Upload session was cancelled; restart this file explicitly")
            StorageUploadStatus.UNCERTAIN -> error("Storage upload completion is uncertain")
            StorageUploadStatus.UPLOADING -> Unit
        }
        onProgress(current.confirmedOffset, fileSize)
        // Authenticate the bytes being sent against the same bounded source pass used for recovery.
        val sentDigest = SHA256()
        var hashed = 0L
        while (hashed < current.confirmedOffset) {
            ensureRunning()
            val end = minOf(hashed + 1024 * 1024, current.confirmedOffset)
            val bytes = readRange(hashed, end).getOrThrow()
            check(bytes.size.toLong() == end - hashed) { "Upload source length changed" }
            sentDigest.update(bytes)
            hashed = end
        }
        while (current.confirmedOffset < fileSize) {
            ensureRunning()
            val offset = current.confirmedOffset
            val end = minOf(offset + chunkSize, fileSize)
            val bytes = readRange(offset, end).getOrThrow()
            check(bytes.size.toLong() == end - offset) { "Upload source length changed" }
            constraints.validate(fileSize, offset, bytes.size)
            sentDigest.update(bytes)
            if (end == fileSize) check(sentDigest.digest().hexLower == sourceVersion) {
                "Upload source changed; restart this file explicitly"
            }
            current = provider.uploadChunk(current, offset, bytes).getOrThrow()
            if (current.status == StorageUploadStatus.UNCERTAIN) throw StorageUploadUncertainException(current)
            check(current.confirmedOffset == end || current.status == StorageUploadStatus.COMPLETED) {
                "Storage upload progress must be reconciled before continuing"
            }
            persist(current)
            onProgress(current.confirmedOffset, fileSize)
        }
        ensureRunning()
        current = if (current.status == StorageUploadStatus.COMPLETED) current
            else provider.finishUpload(current).getOrThrow()
        persist(current)
        check(current.status == StorageUploadStatus.COMPLETED) { "Storage upload completion is uncertain" }
        onCompleted?.invoke(current.entry ?: provider.stat(current.target).getOrThrow())
        forget()
        onProgress(fileSize, fileSize)
        Result.success(true)
    } catch (cancelled: CancellationException) {
        if (cancelledByUser() && reference != null && session != null) withContext(NonCancellable) {
            val current = checkNotNull(session)
            val checked = provider.queryUpload(current).getOrNull()
            if (checked?.status == StorageUploadStatus.COMPLETED) {
                forget()
            } else if (checked?.status == StorageUploadStatus.UPLOADING &&
                provider.cancelUpload(checked).isSuccess) {
                forget()
            } else {
                persist(current.copy(status = StorageUploadStatus.UNCERTAIN))
            }
        }
        throw cancelled
    } catch (error: Throwable) {
        if (error is StorageUploadUncertainException && reference != null) persist(error.session)
        Result.failure(error)
    }
}

internal suspend fun storageUploadSourceDigest(
    fileSize: Long,
    ensureRunning: suspend () -> Unit,
    readRange: suspend (Long, Long) -> Result<ByteArray>,
): String {
    val digest = SHA256()
    var offset = 0L
    while (offset < fileSize) {
        ensureRunning()
        val end = minOf(offset + 1024 * 1024, fileSize)
        val bytes = readRange(offset, end).getOrThrow()
        check(bytes.size.toLong() == end - offset) { "Upload source length changed" }
        digest.update(bytes)
        offset = end
    }
    return digest.digest().hexLower
}

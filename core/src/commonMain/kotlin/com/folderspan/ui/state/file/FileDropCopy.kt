package com.folderspan.ui.state.file

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.data.StatusEnum
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import com.folderspan.ui.state.main.TASK_SESSION_TITLE_VALUE_KEY
import com.folderspan.utils.normalizePathForSecurityBoundary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import strings.AppStrings

enum class FileDropTargetKind { Local, Device, Network }

data class FileDropEndpoint(val kind: FileDropTargetKind, val id: String = "")

data class FileDropDestination(
    val endpoint: FileDropEndpoint,
    val rememberedPath: String?,
    val desk: DiskBase? = null,
)

data class FileDropBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(horizontal: Float, vertical: Float): Boolean =
        horizontal >= left && horizontal < right && vertical >= top && vertical < bottom
}

data class FileDragSession(
    val files: List<FileSimpleInfo> = emptyList(),
    val external: Boolean = false,
    val horizontal: Float = 0f,
    val vertical: Float = 0f,
    val hoveredEndpoint: FileDropEndpoint? = null,
    val previewFile: FileSimpleInfo? = files.firstOrNull(),
    val isTouch: Boolean = false,
)

fun snapshotFileDragSelection(
    draggedFile: FileSimpleInfo,
    selectedFiles: List<FileSimpleInfo>,
): List<FileSimpleInfo> {
    val selected = selectedFiles.any { candidate ->
        candidate.protocol == draggedFile.protocol && candidate.protocolId == draggedFile.protocolId &&
            candidate.path == draggedFile.path
    }
    return (if (selected) selectedFiles else listOf(draggedFile))
        .distinctBy { file -> Triple(file.protocol, file.protocolId, file.path) }
        .map { file -> file.copy() }
}

object FileDropSessionRegistry {
    private data class Target(val endpoint: FileDropEndpoint, val bounds: FileDropBounds)
    private val targets = mutableMapOf<Any, Target>()
    private val mutableSession = MutableStateFlow<FileDragSession?>(null)
    val session: StateFlow<FileDragSession?> = mutableSession

    fun register(owner: Any, endpoint: FileDropEndpoint, bounds: FileDropBounds) {
        targets[owner] = Target(endpoint, bounds)
        refreshHover()
    }

    fun unregister(owner: Any) {
        targets.remove(owner)
        refreshHover()
    }

    fun startInternal(
        files: List<FileSimpleInfo>,
        horizontal: Float,
        vertical: Float,
        previewFile: FileSimpleInfo? = files.firstOrNull(),
        isTouch: Boolean = false,
    ) {
        if (files.isEmpty()) return
        mutableSession.value = FileDragSession(
            files = files.map { it.copy() },
            previewFile = previewFile?.withCopy(),
            isTouch = isTouch,
        )
        move(horizontal, vertical)
    }

    fun moveExternal(horizontal: Float, vertical: Float) {
        if (mutableSession.value?.external != true) {
            mutableSession.value = FileDragSession(external = true)
        }
        move(horizontal, vertical)
    }

    fun move(horizontal: Float, vertical: Float) {
        val active = mutableSession.value ?: return
        mutableSession.value = active.copy(
            horizontal = horizontal,
            vertical = vertical,
            hoveredEndpoint = hitTest(horizontal, vertical),
        )
    }

    fun captureEndpoint(): FileDropEndpoint? {
        val active = mutableSession.value ?: return null
        return hitTest(active.horizontal, active.vertical)
    }

    fun end() {
        mutableSession.value = null
    }

    private fun hitTest(horizontal: Float, vertical: Float): FileDropEndpoint? =
        targets.values.lastOrNull { target -> target.bounds.contains(horizontal, vertical) }?.endpoint

    private fun refreshHover() {
        mutableSession.value?.let { active -> move(active.horizontal, active.vertical) }
    }
}

fun retainFileDropResources(files: List<FileSimpleInfo>): ExternalFileResourceLease? {
    val leases = listOfNotNull(
        ShareListDropResourceRegistry.retainForCopy(files.map { it.path }),
        ClipboardUrlShareFiles.retainForCopy(files),
    )
    if (leases.size < 2) return leases.firstOrNull()
    val combined = ExternalFileResourceLease(newExternalFileLeaseId())
    ExternalFileResourceLeaseRegistry.register(combined) {
        leases.forEach { ExternalFileResourceLeaseRegistry.releaseProducer(it.id) }
    }
    return combined
}

fun fileDropTargetsSource(source: FileSimpleInfo, destination: FileSimpleInfo, separator: String): Boolean {
    val localSystemShare = source.protocol == FileProtocol.Share && source.protocolId == SYSTEM_SHARE_DESK_ID &&
        ClipboardUrlShareFiles.downloadUrl(source) == null && !source.path.startsWith("content://")
    val sameEndpoint = if (localSystemShare) destination.protocol == FileProtocol.Local
        else source.protocol == destination.protocol && source.protocolId == destination.protocolId
    if (!sameEndpoint) return false
    val ignoreCase = separator == "\\"
    val sourcePath = normalizePathForSecurityBoundary(source.path, separator)
        ?.let { if (ignoreCase) it.lowercase() else it } ?: return true
    val destinationPath = normalizePathForSecurityBoundary(destination.path, separator)
        ?.let { if (ignoreCase) it.lowercase() else it } ?: return true
    val copiedPath = destinationPath.trimEnd('/') + "/" + if (ignoreCase) source.name.lowercase() else source.name
    return copiedPath == sourcePath || destinationPath == sourcePath ||
        (source.isDirectory && destinationPath.startsWith(sourcePath.trimEnd('/') + "/"))
}

internal suspend fun resolveFileDropDirectory(
    rememberedPath: String?,
    defaultPath: suspend () -> String?,
    validate: suspend (String) -> FileSimpleInfo?,
): FileSimpleInfo? {
    val remembered = rememberedPath?.takeIf { it.isNotBlank() }
    if (remembered != null) validate(remembered)?.let { return it }
    val fallback = defaultPath()?.takeIf { it.isNotBlank() && it != remembered } ?: return null
    return validate(fallback)
}

enum class FileDropCopyStatus { Preparing, WaitingForTarget, Submitted, Failed, Cancelled }

data class FileDropCopyRequest(
    val id: String,
    val destination: FileDropDestination,
    val batch: PreparedExternalFileBatch? = null,
    val status: FileDropCopyStatus = FileDropCopyStatus.Preparing,
    val actualPath: String? = null,
    val taskKeys: List<Long> = emptyList(),
    val error: String? = null,
    val heldLease: ExternalFileResourceLease? = null,
    val holdsBatchReference: Boolean = false,
    val preparationTaskKey: Long = Task(taskType = TaskType.Copy, status = StatusEnum.LOADING).key,
)

class FileDropCopyCoordinator(
    private val scope: CoroutineScope,
    private val resolveTarget: suspend (FileDropDestination) -> ExternalFileImportTarget,
    private val validateSources: suspend (List<FileSimpleInfo>) -> Unit,
    private val execute: suspend (ExternalFileImportTarget, PreparedExternalFileBatch, suspend () -> Unit) -> List<Long>,
    private val taskState: TaskState,
) {
    private val mutableRequests = MutableStateFlow<List<FileDropCopyRequest>>(emptyList())
    val requests: StateFlow<List<FileDropCopyRequest>> = mutableRequests
    private val mutableReceipts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val receipts: SharedFlow<String> = mutableReceipts
    private val jobs = mutableMapOf<String, Job>()
    private val preparations = mutableMapOf<String, suspend () -> PreparedExternalFileBatch>()
    private val submissionMutex = Mutex()

    fun submit(
        destination: FileDropDestination,
        heldLease: ExternalFileResourceLease? = null,
        prepare: suspend () -> PreparedExternalFileBatch,
    ): String {
        val id = newExternalFileLeaseId()
        mutableRequests.value += FileDropCopyRequest(id, destination, heldLease = heldLease)
        preparations[id] = prepare
        registerPreparationTask(mutableRequests.value.last())
        start(id)
        return id
    }

    fun retry(id: String) {
        if (mutableRequests.value.firstOrNull { it.id == id }?.status != FileDropCopyStatus.Failed) return
        start(id)
    }

    fun cancel(id: String) {
        val request = mutableRequests.value.firstOrNull { it.id == id } ?: return
        if (request.status == FileDropCopyStatus.Submitted) return
        update(id) { it.copy(status = FileDropCopyStatus.Cancelled) }
        val job = jobs.remove(id)
        preparations.remove(id)
        mutableRequests.value = mutableRequests.value.filterNot { it.id == id }
        taskState.delete(request.preparationTaskKey)
        if (job != null) {
            job.invokeOnCompletion { releaseRequestResources(request) }
            job.cancel()
        } else releaseRequestResources(request)
    }

    fun dispose() {
        mutableRequests.value.filter { it.status != FileDropCopyStatus.Submitted }.forEach { cancel(it.id) }
    }

    private fun start(id: String) {
        if (jobs[id]?.isActive == true) return
        update(id) { it.copy(status = FileDropCopyStatus.Preparing, error = null) }
        jobs[id] = scope.launch {
            try {
                val request = mutableRequests.value.firstOrNull { it.id == id } ?: return@launch
                ensurePreparationRunning(request)
                val prepared = request.batch ?: preparations.getValue(id).invoke().let { batch ->
                    batch.copy(files = batch.files.map { it.copy() })
                }
                val batch = if (request.batch == null && request.heldLease != null) {
                    val lease = ExternalFileResourceLease(newExternalFileLeaseId(), prepared.lease?.rootPath)
                    ExternalFileResourceLeaseRegistry.register(lease) {
                        prepared.lease?.let { ExternalFileResourceLeaseRegistry.releaseProducer(it.id) }
                        ExternalFileResourceLeaseRegistry.releaseProducer(request.heldLease.id)
                    }
                    prepared.copy(lease = lease)
                } else prepared
                if (mutableRequests.value.none { it.id == id }) {
                    batch.lease?.let { ExternalFileResourceLeaseRegistry.releaseProducer(it.id) }
                    return@launch
                }
                update(id) { it.copy(batch = batch, heldLease = null, status = FileDropCopyStatus.WaitingForTarget) }
                ensurePreparationRunning(request)
                require(batch.files.isNotEmpty()) { AppStrings.ui_share_drop_no_importable_content }
                val target = withTimeout(30_000L) {
                    validateSources(batch.files)
                    resolveTarget(request.destination).also { validateSources(batch.files) }
                }
                require(batch.files.none {
                    !(it.protocol == FileProtocol.Share && it.protocolId == SYSTEM_SHARE_DESK_ID &&
                        batch.sourceDownloadUrls[it.path] != null) &&
                        fileDropTargetsSource(it, target.destination, target.desk.pathSeparator)
                }) {
                    AppStrings.ui_drop_copy_self_path
                }
                update(id) { it.copy(actualPath = target.capturedPath) }
                ensurePreparationRunning(request)
                if (!request.holdsBatchReference && batch.lease != null &&
                    ExternalFileResourceLeaseRegistry.retainReference(batch.lease.id)) {
                    update(id) { it.copy(holdsBatchReference = true) }
                }
                val taskKeys = submissionMutex.withLock {
                    ensurePreparationRunning(request)
                    execute(target, batch) {
                        if (mutableRequests.value.firstOrNull { it.id == id }?.status != FileDropCopyStatus.Submitted) {
                            ensurePreparationRunning(request)
                            update(id) { it.copy(status = FileDropCopyStatus.Submitted) }
                            taskState.delete(request.preparationTaskKey)
                        }
                    }
                }
                if (taskKeys.isEmpty()) {
                    if (mutableRequests.value.firstOrNull { it.id == id }?.status == FileDropCopyStatus.Submitted) {
                        releaseSubmittedRequest(id)
                    } else cancel(id)
                } else {
                    if (mutableRequests.value.firstOrNull { it.id == id }?.holdsBatchReference == true) {
                        batch.lease?.let { ExternalFileResourceLeaseRegistry.releaseReference(it.id) }
                    }
                    update(id) { it.copy(status = FileDropCopyStatus.Submitted, taskKeys = taskKeys) }
                    taskState.delete(request.preparationTaskKey)
                    mutableReceipts.tryEmit(target.capturedPath)
                    preparations.remove(id)
                    mutableRequests.value = mutableRequests.value.filterNot { it.id == id }
                }
            } catch (error: TimeoutCancellationException) {
                failPreparation(id, AppStrings.ui_drop_copy_timeout)
            } catch (error: CancellationException) {
                if (mutableRequests.value.firstOrNull { it.id == id }?.status == FileDropCopyStatus.Submitted) {
                    releaseSubmittedRequest(id)
                } else cancel(id)
                throw error
            } catch (error: Throwable) {
                failPreparation(id, error.message ?: AppStrings.ui_file_copy_failed)
            } finally {
                jobs.remove(id)
            }
        }
    }

    private fun update(id: String, transform: (FileDropCopyRequest) -> FileDropCopyRequest) {
        mutableRequests.value = mutableRequests.value.map { if (it.id == id) transform(it) else it }
        mutableRequests.value.firstOrNull { it.id == id }?.let(::updatePreparationTask)
    }

    private fun failPreparation(id: String, message: String) {
        if (mutableRequests.value.firstOrNull { it.id == id }?.status == FileDropCopyStatus.Submitted) {
            releaseSubmittedRequest(id)
        } else update(id) { it.copy(status = FileDropCopyStatus.Failed, error = message) }
    }

    private fun releaseSubmittedRequest(id: String) {
        val request = mutableRequests.value.firstOrNull { it.id == id } ?: return
        releaseRequestResources(request)
        preparations.remove(id)
        mutableRequests.value = mutableRequests.value.filterNot { it.id == id }
    }

    private suspend fun ensurePreparationRunning(request: FileDropCopyRequest) {
        if (!taskState.awaitSessionTaskRunning(request.preparationTaskKey)) throw CancellationException()
    }

    private fun registerPreparationTask(request: FileDropCopyRequest) {
        taskState.registerSessionTask(
            task = Task(
                taskType = TaskType.Copy,
                key = request.preparationTaskKey,
                status = StatusEnum.LOADING,
                values = mapOf(
                    "path" to request.destination.rememberedPath.orEmpty(),
                    TASK_SESSION_TITLE_VALUE_KEY to AppStrings.ui_drop_copy_preparing_source,
                ),
            ),
            onCancel = { scope.launch { cancel(request.id) } },
            onRetry = { scope.launch { retry(request.id) } },
        )
    }

    private fun updatePreparationTask(request: FileDropCopyRequest) {
        if (request.status == FileDropCopyStatus.Submitted || request.status == FileDropCopyStatus.Cancelled) return
        if (taskState.getTask(request.preparationTaskKey) == null) registerPreparationTask(request)
        val task = taskState.getTask(request.preparationTaskKey) ?: return
        val title = when (request.status) {
            FileDropCopyStatus.Preparing -> AppStrings.ui_drop_copy_preparing_source
            FileDropCopyStatus.WaitingForTarget -> AppStrings.ui_drop_copy_waiting_target
            else -> AppStrings.ui_drop_copy_preparation_failed
        }
        val status = when {
            request.status == FileDropCopyStatus.Failed -> StatusEnum.FAILURE
            task.status == StatusEnum.PAUSE -> StatusEnum.PAUSE
            else -> StatusEnum.LOADING
        }
        taskState.update(task.copy(
            status = status,
            values = task.values + (TASK_SESSION_TITLE_VALUE_KEY to title),
            result = if (request.error == null) emptyMap() else mapOf("preparation" to request.error),
        ))
    }

    private fun releaseRequestResources(request: FileDropCopyRequest) {
        request.batch?.lease?.let { ExternalFileResourceLeaseRegistry.releaseProducer(it.id) }
        if (request.holdsBatchReference) {
            request.batch?.lease?.let { ExternalFileResourceLeaseRegistry.releaseReference(it.id) }
        }
        request.heldLease?.let { ExternalFileResourceLeaseRegistry.releaseProducer(it.id) }
    }
}

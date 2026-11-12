package com.folderspan.ui.state.file

import com.folderspan.createSettings
import com.folderspan.utils.DataEncryptionKey
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.ProtoBufCodec
import korlibs.crypto.HMAC
import kotlinx.serialization.json.Json
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.data.main.share.buildSystemShareDesk
import com.folderspan.extensions.fileOperationKey
import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.service.mcp.file.FileEndpointRef
import com.folderspan.service.mcp.file.validateEndpointLeafName
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskType
import com.folderspan.ui.state.main.parentPathOf
import com.folderspan.ui.state.main.storageUploadSourceDigest
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import com.folderspan.utils.isDropDirectoryWritable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import strings.AppStrings

internal const val COPY_DESTINATION_REQUEST_TASK_VALUE = "copyDestinationRequestId"

class CopyDestinationRequest internal constructor(
    val state: CopyDestinationState,
    val discovery: CopyDestinationDiscovery,
    val operationState: FileOperationState,
    private val submitRequest: suspend () -> CopyBatchAdmissionResult,
    private val resolveDirectoryRequest: suspend (CopyEndpointIdentity, FileSimpleInfo) -> CopyDestination,
    private val addSourcesRequest: suspend (CopyEndpointIdentity, List<FileSimpleInfo>) -> Unit,
    private val removeSourceRequest: suspend (CopyFileSnapshot) -> Unit,
    private val releaseRequest: () -> Unit,
) {
    private var closed = false
    suspend fun submit(): CopyBatchAdmissionResult {
        check(!closed) { AppStrings.ui_target_endpoint_unavailable }
        return submitRequest()
    }
    suspend fun resolveDirectory(endpoint: CopyEndpointIdentity, directory: FileSimpleInfo): CopyDestination {
        check(!closed) { AppStrings.ui_target_endpoint_unavailable }
        return resolveDirectoryRequest(endpoint, directory)
    }

    suspend fun addSources(endpoint: CopyEndpointIdentity, files: List<FileSimpleInfo>) {
        check(!closed) { AppStrings.ui_source_endpoint_unavailable }
        addSourcesRequest(endpoint, files)
    }

    suspend fun removeSource(source: CopyFileSnapshot) {
        check(!closed) { AppStrings.ui_source_endpoint_unavailable }
        removeSourceRequest(source)
    }

    fun close() {
        if (closed) return
        closed = true
        discovery.close()
        releaseRequest()
    }
}

internal class FileCopyDestinationController(private val fileState: FileState) {
    private data class Version(val file: FileSimpleInfo, val digest: String?)
    private class Context(
        val draft: CopyDestinationState,
        val discovery: CopyDestinationDiscovery,
        val operations: FileOperationState,
        bindings: List<CopyDestinationSource>,
        lease: ExternalFileResourceLease?,
    ) {
        val runtimeTarget = WebUiAttachment.target
        val runtimeToken = WebUiAttachment.sessionToken
        val sourceIdentities get() = draft.sources.map { it.endpoint }.toSet()
        val leases = mutableListOf<ExternalFileResourceLease>().apply { lease?.let { add(it) } }
        var bindings = bindings.toList()
        var closed = false
        val versions = mutableMapOf<String, Version>()
        val taskKeys = mutableListOf<Long>()
        val taskOwners = MutableStateFlow<Map<Long, String>>(emptyMap())
        val plans = MutableStateFlow<Map<String, PreparedCopyDestination>>(emptyMap())
    }
    private var runtimeId = newExternalFileLeaseId()
    private var runtimeMarker = WebUiAttachment.target to WebUiAttachment.sessionToken
    private val contexts = MutableStateFlow<Map<String, Context>>(emptyMap())
    private val recoveredContexts = MutableStateFlow<Map<Long, Context>>(emptyMap())
    private val reservations = CopyTargetReservations { owner ->
        (contexts.value.values + recoveredContexts.value.values).any { context ->
            val keys = context.taskOwners.value.filterValues { it == owner }.keys
            context.plans.value.values.any { planOwner(it) == owner } &&
                context.runtimeTarget == WebUiAttachment.target && context.runtimeToken == WebUiAttachment.sessionToken &&
                if (keys.isEmpty()) !context.closed else keys.any { key ->
                    val task = fileState.taskState.getTask(key)
                    task != null && task.status != StatusEnum.SUCCESS && !fileState.taskState.isTaskCancelled(key)
                }
        }
    }
    private val collector = FileStateDirectoryCollector({ Local() }, fileState.deviceState, fileState.networkState)
    private val admissions = mutableMapOf<String, CopyBatchAdmission>()

    fun create(files: List<FileSimpleInfo>, sourceDesk: DiskBase? = null): CopyDestinationRequest {
        recoveredContexts.update { saved -> saved.filterKeys { key ->
            fileState.taskState.getTask(key)?.let { it.status != StatusEnum.SUCCESS && !fileState.taskState.isTaskCancelled(key) } == true
        } }
        val completed = contexts.value.filterValues { context ->
            context.closed && context.taskKeys.none { key -> fileState.taskState.getTask(key)?.let { it.status != StatusEnum.SUCCESS && !fileState.taskState.isTaskCancelled(key) } == true }
        }
        if (completed.isNotEmpty()) {
            contexts.update { it - completed.keys }
            fileState.mainScope.launch { completed.forEach { (id, context) -> admissions[context.draft.runtimeId]?.release(id) } }
        }
        val marker = WebUiAttachment.target to WebUiAttachment.sessionToken
        if (marker != runtimeMarker) {
            runtimeMarker = marker
            runtimeId = newExternalFileLeaseId()
        }
        val admission = admissions.getOrPut(runtimeId) { CopyBatchAdmission(runtimeId, ::prepare, ::enqueue) }
        val discovery = CopyDestinationDiscovery(runtimeId, fileState.mainScope)
        try {
            val draft = CopyDestinationState(runtimeId, discovery.snapshotSources(files, fileState.deviceState, fileState.networkState, sourceDesk))
            discovery.observe(fileState.deviceState, fileState.networkState)
            val bindings = draft.sources.mapNotNull { snapshot -> discovery.source(snapshot.endpoint) }.distinct()
            val context = Context(draft, discovery, FileOperationState(), bindings, retainFileDropResources(files))
            contexts.update { it + (draft.requestId to context) }
            return CopyDestinationRequest(draft, discovery, context.operations,
                submitRequest = { admission.submit(draft) },
                resolveDirectoryRequest = { endpoint, directory -> resolveDirectory(context, endpoint, directory) },
                addSourcesRequest = { endpoint, files -> addSources(context, endpoint, files) },
                removeSourceRequest = { source -> context.draft.sourceMutationMutex.withLock {
                    requireEditableSources(context)
                    draft.replaceSources(draft.sources.filterNot { it.sameLocation(source) })
                    context.versions.clear()
                    context.plans.value = emptyMap()
                } },
                releaseRequest = {
                    context.closed = true
                    context.leases.forEach { ExternalFileResourceLeaseRegistry.releaseProducer(it.id) }
                    if (context.taskKeys.isEmpty()) {
                        contexts.update { it - draft.requestId }
                        fileState.mainScope.launch { admission.release(draft.requestId) }
                    }
                })
        } catch (error: Throwable) {
            discovery.close()
            throw error
        }
    }

    private fun requireEditableSources(context: Context) {
        check(!context.closed) { AppStrings.ui_source_endpoint_unavailable }
        requireRuntime(context)
        check(!context.draft.sourcesLocked.value) { AppStrings.copy_destination_sources_locked }
    }

    private suspend fun addSources(context: Context, endpoint: CopyEndpointIdentity, files: List<FileSimpleInfo>) {
        context.draft.sourceMutationMutex.withLock {
            requireEditableSources(context)
            val binding = context.discovery.source(endpoint) ?: error(AppStrings.ui_source_endpoint_unavailable)
            check(bindingIsCurrent(binding)) { AppStrings.ui_source_endpoint_unavailable }
            val sourceDesk = checkNotNull(binding.desk)
            val snapshots = files.map { selected ->
                require(selected.protocol == endpoint.endpoint.protocol && selected.protocolId == endpoint.endpoint.sourceId)
                val file = stat(sourceDesk, selected)
                check(!file.isSymbolicLink) { AppStrings.file_symbolic_link_copy_not_supported }
                val canonical = if (sourceDesk is Local) PathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, file.path)
                    ?: error(AppStrings.ui_source_endpoint_unavailable) else file.path
                CopyFileSnapshot(endpoint, file, canonical)
            }
            check(!context.closed && bindingIsCurrent(binding)) { AppStrings.ui_source_endpoint_unavailable }
            val additions = snapshots.fold(emptyList<CopyFileSnapshot>()) { known, snapshot ->
                if ((context.draft.sources + known).any { it.sameLocation(snapshot) }) known else known + snapshot
            }
            if (additions.isEmpty()) return@withLock
            val lease = retainFileDropResources(additions.map { it.file })
            try {
                context.draft.replaceSources(context.draft.sources + additions)
            } catch (error: Throwable) {
                lease?.let { ExternalFileResourceLeaseRegistry.releaseProducer(it.id) }
                throw error
            }
            lease?.let { context.leases.add(it) }
            context.bindings = (context.bindings + binding).distinctBy { it.endpoint }
            context.versions.clear()
            context.plans.value = emptyMap()
        }
    }

    private fun requireRuntime(context: Context) {
        check(WebUiAttachment.target == context.runtimeTarget && WebUiAttachment.sessionToken == context.runtimeToken) {
            AppStrings.ui_target_endpoint_unavailable
        }
    }

    private fun validateBindings(context: Context) {
        requireRuntime(context)
        check(context.bindings.filter { it.endpoint in context.sourceIdentities }.all(::bindingIsCurrent)) { AppStrings.ui_target_endpoint_unavailable }
    }

    private fun bindingIsCurrent(binding: CopyDestinationSource): Boolean {
        if (!binding.isCurrent()) return false
        val preferred = fileState.deskType.value
        return when (val captured = binding.desk) {
            is Network -> (if (preferred is Network && preferred.protocolId == captured.protocolId) preferred
                else fileState.networkState.entries.firstOrNull { it.network.protocolId == captured.protocolId }?.network) === captured
            is Device -> (if (preferred is Device && preferred.id == captured.id) preferred
                else fileState.deviceState.devices.firstOrNull { it.id == captured.id }) === captured
            is Share -> fileState.deviceState.shares.firstOrNull { it.id == captured.id } === captured
            else -> true
        }
    }

    private fun desk(context: Context, file: FileSimpleInfo): DiskBase {
        if (file.protocol == FileProtocol.Local) return Local()
        if (file.protocol == FileProtocol.Share && file.protocolId == SYSTEM_SHARE_DESK_ID) return buildSystemShareDesk()
        return checkNotNull(context.bindings.firstOrNull {
            it.endpoint.endpoint.protocol == file.protocol && it.endpoint.endpoint.sourceId == file.protocolId
        }?.desk) { AppStrings.ui_source_endpoint_unavailable }
    }

    private suspend fun stat(desk: DiskBase, file: FileSimpleInfo): FileSimpleInfo = when (desk) {
        is Local -> FileUtils.getFile(FileAccessPermission.Allowed, file.path).getOrThrow()
        is Device -> desk.files.get(file.path).getOrThrow().copy(protocol = file.protocol, protocolId = file.protocolId)
        is NetworkAccess -> desk.getFile(file).getOrThrow()
        is Share -> {
            if (desk.id == SYSTEM_SHARE_DESK_ID) file.copy()
            else {
                val session = checkNotNull(desk.session).also { check(it.isActive) }
                val parent = if (desk.pathSeparator == "\\") parentPathOf(file.path, desk.pathSeparator)
                    else file.path.substringBeforeLast('/', "/").ifEmpty { "/" }
                (session.listRoots().getOrThrow().firstOrNull { it.path == file.path }
                    ?: session.list(parent).getOrThrow().firstOrNull { it.path == file.path })
                    ?.copy(protocol = file.protocol, protocolId = file.protocolId)
                    ?: error(AppStrings.ui_source_endpoint_unavailable)
            }
        }
        else -> error(AppStrings.ui_source_endpoint_unavailable)
    }

    private suspend fun digest(context: Context, file: FileSimpleInfo): String? {
        if (file.isDirectory) return null
        validateBindings(context)
        val source = fileState.openEditorContent(file, canWrite = false).getOrThrow()
        return try {
            storageUploadSourceDigest(source.size, { validateBindings(context) }, source::readRange)
        } finally { source.close() }
    }

    private suspend fun validateVersion(context: Context, file: FileSimpleInfo) {
        val expected = context.versions[file.fileOperationKey()] ?: error(AppStrings.ui_source_endpoint_unavailable)
        val current = stat(desk(context, expected.file), expected.file)
        check(current.isDirectory == expected.file.isDirectory &&
            (current.isDirectory || current.size == expected.file.size) && current.updatedDate == expected.file.updatedDate &&
            digest(context, expected.file) == expected.digest) {
            AppStrings.copy_destination_source_changed
        }
        validateBindings(context)
    }

    private suspend fun resolveDirectory(
        context: Context,
        endpoint: CopyEndpointIdentity,
        selected: FileSimpleInfo,
    ): CopyDestination {
        requireRuntime(context)
        requireWritableCopyDestination(endpoint.endpoint.protocol)
        val source = context.discovery.source(endpoint) ?: error(AppStrings.ui_target_endpoint_unavailable)
        val desk = checkNotNull(source.desk)
        val selectedPath = if (desk is Local) PathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, selected.path)
            ?: error(AppStrings.ui_target_unavailable) else selected.path
        val file = stat(desk, selected.copy(path = selectedPath, protocol = endpoint.endpoint.protocol, protocolId = endpoint.endpoint.sourceId))
        check(file.isDirectory) { AppStrings.ui_target_unavailable }
        val writable = when (desk) {
            is Local -> isDropDirectoryWritable(file.path)
            is Device -> desk.files.checkDirectoryWriteAccess(file.path).getOrThrow() != false
            is Network -> desk.syncCapabilities().getOrThrow().write
            else -> desk.menuPermission?.paste == true
        }
        check(writable) { AppStrings.ui_read_only }
        check(source.isCurrent()) { AppStrings.ui_target_endpoint_unavailable }
        val canonical = if (desk is Local) PathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, file.path)
            ?: error(AppStrings.ui_target_unavailable) else file.path
        return CopyDestination(CopyFileSnapshot(endpoint, file, canonical), file.name.ifBlank { file.path }, source.name)
    }

    private suspend fun prepare(draft: CopyDestinationState, targets: List<CopyDestination>): List<PreparedCopyDestination> {
        val context = contexts.value.getValue(draft.requestId)
        check(!context.closed)
        validateBindings(context)
        val targetSources = targets.map { target ->
            context.discovery.source(target.directory.endpoint) ?: run {
                draft.updateAvailability(target.directory, AppStrings.ui_target_endpoint_unavailable)
                error("${target.connectionName}: ${target.directory.location.path}\n${AppStrings.ui_target_endpoint_unavailable}")
            }
        }
        context.bindings = (context.bindings + targetSources).distinctBy { it.endpoint }
        if (context.versions.isEmpty()) {
            val captured = mutableMapOf<String, Version>()
            for (snapshot in draft.sources) {
                validateEndpointLeafName(snapshot.file.name)
                check(!snapshot.file.isSymbolicLink) { AppStrings.file_symbolic_link_copy_not_supported }
                val root = stat(desk(context, snapshot.file), snapshot.file)
                check(root.size == snapshot.file.size && root.updatedDate == snapshot.file.updatedDate) {
                    AppStrings.copy_destination_source_changed
                }
                val entries = listOf(root) + if (root.isDirectory) collector.collectDirectoryEntries(root, rejectSymbolicLinkEntries = true) else emptyList()
                for (entry in entries) captured[entry.fileOperationKey()] = Version(entry.copy(), digest(context, entry))
            }
            context.versions.putAll(captured)
        }
        context.versions.values.forEach { validateVersion(context, it.file) }
        val canonicalSources = draft.sources.map { snapshot ->
            val file = snapshot.file
            val localShare = file.protocol == FileProtocol.Share && file.protocolId == SYSTEM_SHARE_DESK_ID &&
                ClipboardUrlShareFiles.downloadUrl(file) == null && !file.path.startsWith("content://")
            if (file.protocol != FileProtocol.Local && !localShare) snapshot else {
                val canonical = PathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, file.path)
                    ?: error(AppStrings.ui_source_endpoint_unavailable)
                val endpoint = if (!localShare) snapshot.endpoint else snapshot.endpoint.copy(
                    endpoint = FileEndpointRef(FileProtocol.Local), connectionBinding = "local",
                    pathSeparator = PathUtils.getPathSeparator(), caseSensitive = PathUtils.getPathSeparator() != "\\", pathNamespace = "")
                CopyFileSnapshot(endpoint, if (localShare) file.copy(protocol = FileProtocol.Local, protocolId = "") else file, canonical)
            }
        }
        return targets.zip(targetSources).map { (target, source) -> try {
            requireWritableCopyDestination(target.directory.endpoint.endpoint.protocol)
            check(source.isCurrent()) { AppStrings.ui_target_endpoint_unavailable }
            val targetDesk = checkNotNull(source.desk)
            check(targetDesk.menuPermission?.paste == true) { AppStrings.ui_read_only }
            val directory = stat(targetDesk, target.directory.file)
            check(directory.isDirectory) { AppStrings.ui_target_unavailable }
            val canonicalTarget = if (targetDesk is Local) PathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, directory.path)
                ?: error(AppStrings.ui_target_unavailable) else directory.path
            val currentTarget = CopyFileSnapshot(target.directory.endpoint, directory, canonicalTarget)
            check(target.directory.sameLocation(currentTarget)) { AppStrings.ui_target_endpoint_unavailable }
            check(canonicalSources.none { it.isCopyIntoSelf(currentTarget) }) { AppStrings.ui_drop_copy_self_path }
            if (targetDesk is Local) check(isDropDirectoryWritable(directory.path)) { AppStrings.ui_read_only }
            if (targetDesk is Device) check(targetDesk.files.checkDirectoryWriteAccess(directory.path).getOrThrow() != false) { AppStrings.ui_read_only }
            if (targetDesk is Network) check(targetDesk.syncCapabilities().getOrThrow().write) { AppStrings.ui_read_only }
            val children = when (targetDesk) {
                is Local -> PathUtils.getFileAndFolder(FileAccessPermission.Allowed, directory.path).getOrThrow()
                is Device -> targetDesk.paths.getList(directory.path).getOrThrow()
                is NetworkAccess -> targetDesk.getList(directory).getOrThrow()
                else -> error(AppStrings.ui_read_only)
            }
            check(draft.sources.none { fileDropTargetsSource(it.file, directory, target.directory.endpoint.pathSeparator) }) {
                AppStrings.ui_drop_copy_self_path
            }
            val planned = fileState.pasteTaskExecutor.resolvePasteOperations(directory, draft.sources.map { it.file },
                context.operations, listDestinationChildren = { Result.success(children) }, destinationUnavailableIsFailure = true)
                ?: throw CancellationException(AppStrings.message_task_cancelled)
            PreparedCopyDestination(target, planned, requestId = draft.requestId, validate = {
                validateBindings(context)
                check(source.isCurrent()) { AppStrings.ui_target_endpoint_unavailable }
                resolveDirectory(context, target.directory.endpoint, directory).also { current ->
                    check(target.directory.sameLocation(current.directory)) { AppStrings.ui_target_endpoint_unavailable }
                }
                context.versions.values.forEach { validateVersion(context, it.file) }
                val latest = when (targetDesk) {
                    is Local -> PathUtils.getFileAndFolder(FileAccessPermission.Allowed, directory.path).getOrThrow()
                    is Device -> targetDesk.paths.getList(directory.path).getOrThrow()
                    is NetworkAccess -> targetDesk.getList(directory).getOrThrow()
                    else -> error(AppStrings.ui_read_only)
                }
                check(latest.map(::externalFileDedupeKey).toSet() == children.map(::externalFileDedupeKey).toSet()) {
                    AppStrings.ui_target_exists
                }
            }).also { context.plans.update { plans -> plans + (it.planId to it) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val reason = error.message ?: AppStrings.ui_target_unavailable
            draft.updateAvailability(target.directory, reason)
            throw IllegalStateException("${target.connectionName}: ${target.directory.location.path}\n$reason", error)
        } }
    }

    private suspend fun enqueue(plan: PreparedCopyDestination): List<Long> {
        val context = contexts.value.getValue(plan.requestId)
        check(!context.closed)
        plan.validate()
        reserve(plan)
        val lease = retainFileDropResources(context.draft.sources.map { it.file })
        return fileState.pasteTaskExecutor.enqueuePasteOperations(
            fileOperations = plan.operations, taskType = TaskType.Copy, fallbackMessage = AppStrings.ui_file_copy_failed,
            execute = { task, src, dest ->
                validateTask(task, src)
                fileState.executeCopyTask(task, src, dest).also { validateTask(task, src) }
            }, lease = lease, prepareTask = { task, source, destination -> persistValidation(context, plan, task, source, destination) },
            beforeOperation = { task, source, destination ->
                validateTask(task, source)
                if (destination.protocol == FileProtocol.Device && plan.operations.any { it.dest == destination && it.replaceTarget != null }) {
                    val device = desk(context, destination) as Device
                    check(device.files.checkDirectoryWriteAccess(plan.destination.directory.file.path).getOrThrow() == true) {
                        AppStrings.copy_destination_replace_type_requires_check
                    }
                }
            }, extraTaskValues = mapOf(COPY_DESTINATION_REQUEST_TASK_VALUE to plan.requestId, "copyDestinationBinding" to plan.destination.directory.endpoint.connectionBinding, "copyDestinationPlan" to plan.planId),
        ).also { keys ->
            context.taskKeys += keys
            context.taskOwners.update { owners -> owners + keys.associateWith { planOwner(plan) } }
        }
    }

    private fun planOwner(plan: PreparedCopyDestination): String =
        listOf(plan.requestId, plan.destination.directory.endpoint.connectionBinding, plan.destination.directory.pathKey).joinToString("\u0000")

    private suspend fun reserve(plan: PreparedCopyDestination) {
        val parent = plan.destination.directory
        val prefix = parent.location.path.trimEnd('/', '\\')
        val outputs = plan.operations.map { operation ->
            val relative = operation.dest.path.removePrefix(prefix).trimStart('/', '\\')
            val canonical = parent.pathKey.trimEnd('/') + "/" +
                if (parent.endpoint.pathSeparator == "\\") relative.replace('\\', '/') else relative
            CopyFileSnapshot(parent.endpoint, operation.dest, canonical)
        }
        reservations.claim(planOwner(plan), outputs)
    }

    private fun runtimeScope(): String = Json.encodeToString(listOf(
        WebUiAttachment.target?.origin.orEmpty(),
        createSettings().getString(SettingsUtils.KEY_DEVICE_ID, ""),
    ))

    private fun authorityProof(source: CopyDestinationSource): String {
        if (source.desk is Local) return "local"
        val identity = when (val desk = source.desk) {
            is Network -> Json.encodeToString(desk.connectionConfig())
            is Device -> Json.encodeToString(listOf(desk.id, desk.token,
                fileState.deviceState.socketDevices.firstOrNull { it.id == desk.id }?.tlsFingerprintSha256.orEmpty()))
            // Native share grants are live sessions; a new session must not inherit the old grant.
            is Share -> source.endpoint.connectionBinding
            else -> error(AppStrings.ui_source_endpoint_unavailable)
        }
        return HMAC.hmacSHA256(DataEncryptionKey.keyBytes(), identity.encodeToByteArray()).hexLower
    }

    private fun persistValidation(context: Context, plan: PreparedCopyDestination, task: Task, source: FileSimpleInfo, destination: FileSimpleInfo) {
        val endpoint = context.draft.sources.first { it.file.fileOperationKey() == source.fileOperationKey() }.endpoint
        val root = CopyFileSnapshot(endpoint, source)
        val versions = context.versions.values.filter { version ->
            version.file.protocol == source.protocol && version.file.protocolId == source.protocolId &&
                root.contains(CopyFileSnapshot(endpoint, version.file))
        }.map { CopySavedVersion(it.file, it.digest) }
        check(versions.any { it.file.fileOperationKey() == source.fileOperationKey() })
        val targetSource = context.bindings.first { it.endpoint == plan.destination.directory.endpoint }
        val saved = CopyTaskValidationSnapshot(taskKey = task.key, requestId = plan.requestId, planId = plan.planId,
            runtimeScope = runtimeScope(),
            sources = context.bindings.filter { it.endpoint in context.sourceIdentities }.map { CopySavedAuthority(it.endpoint, authorityProof(it)) },
            target = CopySavedAuthority(targetSource.endpoint, authorityProof(targetSource)), sourceEndpoint = endpoint,
            source = source, destination = destination, directory = plan.destination.directory.file,
            canonicalDirectory = plan.destination.directory.pathKey, versions = versions)
        fileState.taskState.saveCopyValidation(task.key, ProtoBufCodec.encode(saved))
    }

    private fun restoreValidation(task: Task): Context {
        val bytes = fileState.taskState.loadCopyValidation(task.key) ?: error(AppStrings.copy_destination_snapshot_unavailable)
        val saved = ProtoBufCodec.decode<CopyTaskValidationSnapshot>(bytes)
        check(saved.schemaVersion == 1 && saved.taskKey == task.key && saved.runtimeScope == runtimeScope() &&
            saved.requestId == task.values[COPY_DESTINATION_REQUEST_TASK_VALUE] && saved.planId == task.values["copyDestinationPlan"] &&
            saved.target.endpoint.connectionBinding == task.values["copyDestinationBinding"]) { AppStrings.copy_destination_snapshot_unavailable }
        check(saved.source.protocol == task.protocol && saved.source.protocolId == task.protocolId)
        validateEndpointLeafName(saved.source.name)
        check(!saved.source.isSymbolicLink)
        val discovery = CopyDestinationDiscovery(runtimeId, fileState.mainScope)
        try {
            val current = discovery.captureSources(fileState.deviceState, fileState.networkState)
            fun restore(authority: CopySavedAuthority): CopyDestinationSource {
                val candidate = current.singleOrNull { item ->
                    item.endpoint.endpoint == authority.endpoint.endpoint && item.endpoint.pathNamespace == authority.endpoint.pathNamespace &&
                        item.endpoint.pathSeparator == authority.endpoint.pathSeparator && item.endpoint.caseSensitive == authority.endpoint.caseSensitive &&
                        authorityProof(item) == authority.proof && item.isCurrent()
                } ?: error(AppStrings.ui_target_endpoint_unavailable)
                return CopyDestinationSource(authority.endpoint.copy(runtimeId = runtimeId), candidate.name, candidate.desk, candidate.isCurrent, candidate.load)
            }
            val sources = saved.sources.map(::restore)
            val target = restore(saved.target)
            val sourceSnapshot = CopyFileSnapshot(saved.sourceEndpoint.copy(runtimeId = runtimeId), saved.source)
            val draft = CopyDestinationState(runtimeId, listOf(sourceSnapshot), saved.requestId)
            val context = Context(draft, discovery, FileOperationState(), sources, null)
            context.bindings = (sources + target).distinctBy { it.endpoint }
            context.versions.putAll(saved.versions.associate { it.file.fileOperationKey() to Version(it.file, it.digest) })
            check(context.versions.containsKey(saved.source.fileOperationKey()))
            val destination = CopyDestination(CopyFileSnapshot(target.endpoint, saved.directory, saved.canonicalDirectory), saved.directory.name, target.name)
            val plan = PreparedCopyDestination(destination, listOf(PendingFileOperation(saved.source, saved.destination)), saved.requestId, saved.planId) {}
            context.plans.value = mapOf(saved.planId to plan)
            context.taskKeys.add(task.key)
            context.taskOwners.value = mapOf(task.key to planOwner(plan))
            context.closed = true
            var selected = context
            recoveredContexts.update { previous ->
                selected = previous[task.key] ?: context
                previous + (task.key to selected)
            }
            return selected
        } finally { discovery.close() }
    }

    suspend fun validateTask(task: Task, file: FileSimpleInfo?) {
        val requestId = task.values[COPY_DESTINATION_REQUEST_TASK_VALUE] ?: return
        val context = contexts.value[requestId] ?: recoveredContexts.value[task.key] ?: restoreValidation(task)
        validateBindings(context)
        val targetBinding = task.values["copyDestinationBinding"]
        check(context.bindings.any { it.endpoint.connectionBinding == targetBinding && bindingIsCurrent(it) }) {
            AppStrings.ui_target_endpoint_unavailable
        }
        val plan = context.plans.value[task.values["copyDestinationPlan"]] ?: error(AppStrings.ui_target_unavailable)
        reserve(plan)
        val binding = context.bindings.first { it.endpoint.connectionBinding == targetBinding }
        val targetDesk = checkNotNull(binding.desk)
        val directory = stat(targetDesk, plan.destination.directory.file)
        check(directory.isDirectory) { AppStrings.ui_target_unavailable }
        val canonical = if (targetDesk is Local) PathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, directory.path)
            ?: error(AppStrings.ui_target_unavailable) else directory.path
        check(plan.destination.directory.sameLocation(CopyFileSnapshot(binding.endpoint, directory, canonical))) { AppStrings.ui_target_endpoint_unavailable }
        when (targetDesk) {
            is Local -> check(isDropDirectoryWritable(directory.path)) { AppStrings.ui_read_only }
            is Device -> check(targetDesk.files.checkDirectoryWriteAccess(directory.path).getOrThrow() != false) { AppStrings.ui_read_only }
            is Network -> check(targetDesk.syncCapabilities().getOrThrow().write) { AppStrings.ui_read_only }
        }
        if (file != null) validateVersion(context, file)
    }
}

package com.folderspan.ui.state.main

import strings.AppStrings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.service.sync.SyncConditionsMonitor
import com.folderspan.service.sync.SyncRunCondition
import com.folderspan.service.sync.beforeSyncConditionFile
import com.folderspan.service.sync.withSyncConditionPath
import com.folderspan.service.operation.processItemsAdaptive
import com.folderspan.service.operation.reportOperationFailure
import com.folderspan.service.operation.reportOperationSkipped
import com.folderspan.extensions.fileOperationKey
import com.folderspan.service.network.StorageContentPlatform
import com.folderspan.service.network.StorageReference
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.network.buildProtocolId
import com.folderspan.ignore.SupportedIgnoreFileNames
import com.folderspan.ignore.ResolvedIgnoreMatcher
import com.folderspan.ignore.IgnorePreferenceScope
import com.folderspan.ignore.loadResolvedIgnoreMatcherStrict
import com.folderspan.ignore.findExistingSupportedIgnoreFileNames
import com.folderspan.ignore.normalizeSupportedIgnoreFileNames
import com.folderspan.runtime.RuntimeServiceFailures
import com.folderspan.notification.SyncNotificationEvent
import com.folderspan.notification.postSyncNotification
import com.folderspan.notification.clearSyncNotification
import com.folderspan.ui.state.file.FileState
import com.folderspan.utils.PathUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.SyncSnapshotChangeNotifier
import com.folderspan.utils.currentSyncTimeZone
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.mp.KoinPlatformTools
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.milliseconds

private const val TARGET_ENDPOINT_CONNECT_WAIT_MS = 30_000L

internal class SyncModifiedTimeState {
    val mutex = Mutex()
    var checked = false
    var disabled = false
}

internal suspend fun syncComparisonTargetFile(
    desk: DiskBase,
    file: FileSimpleInfo,
    logicalFile: suspend (Network, FileSimpleInfo) -> Result<FileSimpleInfo?> = { network, target ->
        StorageContentPlatform.logicalFile(network.connectionConfig(), target)
    },
): FileSimpleInfo = if (desk is Network) logicalFile(desk, file).getOrThrow() ?: file else file


internal fun preserveSyncRetryIdentity(record: SyncRunRecord, retrySources: List<FileSimpleInfo>?): SyncRunRecord {
    if (retrySources == null || record.failureCount == 0 || record.items.any { !it.success && it.source != null }) return record
    val completed = record.items.filter { it.success }.mapNotNull { it.source?.fileOperationKey() }.toSet()
    val pending = retrySources.filter { it.fileOperationKey() !in completed }
    return record.copy(items = record.items + pending.map { SyncRunItem(it.path, false, record.message, it) })
}

class SyncState(
    private val taskStore: SyncTaskStore,
    private val deviceIdentity: () -> SyncDeviceIdentity = SyncDeviceIdentity::current,
) : KoinComponent {
    internal val localStore: SyncLocalStore? get() = KoinPlatformTools.defaultContext().getOrNull()?.getOrNull()
    internal val fileState by inject<FileState>()
    internal val deviceState by inject<DeviceState>()
    internal val networkState by inject<NetworkState>()
    internal val taskState by inject<TaskState>()
    internal val endpointFiles = SyncEndpointFileOperations()
    internal val conditionsMonitor by lazy {
        KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<SyncConditionsMonitor>() ?: SyncConditionsMonitor(scope)
    }
    internal val conditionRuns by lazy { SyncConditionRuns(scope, { conditionsMonitor.state }, ::getTask,
        ::isTaskOwnedByCurrentDevice, ::hasActiveSyncRun, ::enqueueReadyRun, interrupted = { request ->
            val now = nowMillis()
            onTaskFinished(request.task, SyncRunRecord(request.startedAt, request.task.id, request.trigger, request.startedAt, now,
                SyncRunStatus.Canceled, 0, 0, 0, syncConditionsLostMessage(request.interrupted)))
        }, deferred = { expected ->
            getTask(expected.id)?.takeIf { it.lastStatus == SyncRunStatus.Queued }?.let {
                replaceOrAddTask(it.copy(lastStatus = SyncRunStatus.Idle, lastMessage = ""))
                notifyChanged()
            }
        }, userCanceled = { id -> runningOperationTasks[id]?.let { taskState.isTaskCanceledByUser(it.key) } == true }) }
    val waitingRunConditions: Map<Long, Set<SyncRunCondition>> get() = conditionRuns.waitingConditions
    internal val eventTriggers by lazy { SyncEventTriggerRuntime(this) }

    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runQueue = SyncRunQueue(scope)
    internal val lifecycleMutex = Mutex()
    internal val runPreviews = mutableMapOf<Long, SyncCachedPreview>()
    val taskAttention = androidx.compose.runtime.mutableStateMapOf<Long, SyncTaskAttention>()
    private var started = false
    private var automaticStarted = false
    private val systemScheduler by lazy { createSyncSystemScheduler(this, scope) }
    private var scheduleTimeZone = currentSyncTimeZone()
    private val runLocks = mutableMapOf<Long, Mutex>()
    private val runLocksMutex = Mutex()
    private val runningOperationTasks = androidx.compose.runtime.mutableStateMapOf<Long, Task>()
    internal val activeStorageGroups = androidx.compose.runtime.mutableStateMapOf<Long, List<Task>>()

    /** Returns the current local copy tasks whose existing metrics back the synchronization pages. */
    fun activeOperationTasks(taskId: Long): List<Task> {
        val task = getTask(taskId) ?: return emptyList()
        if (!isTaskOwnedByCurrentDevice(task)) return emptyList()
        val operations = activeStorageGroups[taskId].orEmpty() + listOfNotNull(runningOperationTasks[taskId])
        return operations.distinctBy { it.key }.mapNotNull { taskState.getTask(it.key) }
            .filter { it.status == StatusEnum.LOADING || it.status == StatusEnum.PAUSE }
    }

    internal fun taskStoreForStorageRun(task: SyncTask, record: SyncRunRecord): Boolean = taskStore.withExclusiveAccess {
        val persisted = taskStore.loadTasks().firstOrNull { it.id == task.id } ?: return@withExclusiveAccess false
        if (persisted.ownerDeviceId != task.ownerDeviceId || !isTaskOwnedByCurrentDevice(persisted)) return@withExclusiveAccess false
        taskStore.appendRun(record)
        true
    }

    fun controlStorageRun(taskId: Long, control: StorageGroupControl) {
        if (control == StorageGroupControl.Cancel) conditionRuns.cancel(taskId)
        activeStorageGroups[taskId].orEmpty().forEach { snapshot ->
            val task = taskState.getTask(snapshot.key) ?: snapshot
            if (task.status == StatusEnum.SUCCESS) return@forEach
            when (control) {
                StorageGroupControl.Pause -> taskState.requestPause(task)
                StorageGroupControl.Resume -> taskState.requestResume(task)
                StorageGroupControl.Cancel -> taskState.requestCancel(task)
            }
        }
    }

    fun retryStorageTargets(
        taskId: Long, runId: Long, selected: Set<Long>, selectedFiles: Map<Long, Set<String>> = emptyMap(),
    ) = retryStorageTargets(taskId, runId, selected, selectedFiles, ignoreConditions = false)

    fun retryStorageTargets(
        taskId: Long, runId: Long, selected: Set<Long>, selectedFiles: Map<Long, Set<String>>, ignoreConditions: Boolean,
    ) {
        val record = getRunRecord(taskId, runId) ?: return
        val snapshot = record.policySnapshot ?: return
        val current = getTask(taskId) ?: return
        if (selected.isEmpty() || taskId in activeStorageGroups) return
        // Retry keeps its frozen file policy while current run conditions still govern admission.
        val execution = snapshot.copy(requireWifi = current.requireWifi, requireCharging = current.requireCharging)
        val retry = record.copy(policySnapshot = execution)
        enqueueRun(current, ignoreConditions) {
            runTaskInternal(execution, "retry", storageRetry = retry, selectedTargetKeys = selected, selectedFiles = selectedFiles)
        }
    }

    suspend fun submitStorageCopy(
        name: String, sources: List<FileSimpleInfo>, destinations: List<StorageDestination>,
    ): Result<SyncTask> {
        if (sources.isEmpty() || destinations.isEmpty()) return Result.failure(IllegalArgumentException(AppStrings.ui_no_changes))
        val first = sources.first()
        require(sources.all { it.protocol == first.protocol && it.protocolId == first.protocolId })
        val sourceType = SyncEndpointType.entries.first { toProtocol(it) == first.protocol }
        val template = destinations.first().applyTo(newTaskTemplate()).copy(
            name = name, enabled = false, sourceType = sourceType, sourceRef = first.protocolId,
            sourcePath = first.path, sourceStorageReference = first.storageReference,
            selectedSources = sources.map { it.copy() }, destinations = destinations.toList(),
        )
        return createOrUpdateTask(template).onSuccess { runNowIfIdle(it.id) }
    }


    val tasks = androidx.compose.runtime.mutableStateListOf<SyncTask>()
    val runHistory = androidx.compose.runtime.mutableStateMapOf<Long, List<SyncRunRecord>>()

    var revision by mutableIntStateOf(0)
        private set

    fun startInBackground() { scope.launch { start(backgroundOnly = true) } }

    suspend fun start() = start(backgroundOnly = false)

    internal suspend fun start(backgroundOnly: Boolean) = lifecycleMutex.withLock {
        check(scope.isActive) { "Sync scheduler has stopped" }
        if (!started) {
            loadTasks(recoverInterrupted = true)
            started = true
            systemScheduler.start()
        }
        if (!backgroundOnly && !automaticStarted) {
            eventTriggers.check()
            startScheduler()
            automaticStarted = true
        }
    }

    suspend fun stop() {
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    internal fun notifyChanged() {
        revision++
        systemScheduler.refresh()
    }

    private fun normalizePath(path: String): String {
        return path.trim().ifBlank { PathUtils.getPathSeparator() }
    }

    fun availableEndpoints(): List<SyncEndpoint> {
        val result = mutableListOf<SyncEndpoint>()
        result.add(
            SyncEndpoint(
                type = SyncEndpointType.Local,
                ref = "",
                label = AppStrings.ui_local,
                canRead = true,
                canWrite = true,
            )
        )
        result.addAll(
            deviceState.devices.map { item ->
                val permission = item.menuPermission
                SyncEndpoint(
                    type = SyncEndpointType.Device,
                    ref = item.id,
                    label = item.name,
                    canRead = permission.copy,
                    canWrite = permission.paste,
                )
            }
        )
        result.addAll(
            networkState.entries.filter { it.network.supportsSync }.map { entry ->
                val network = entry.network
                val permission = network.menuPermission
                val label = "${network.name} (${network.protocol})"
                SyncEndpoint(
                    type = SyncEndpointType.Network,
                    ref = network.buildProtocolId(),
                    label = label,
                    canRead = permission.copy,
                    canWrite = permission.paste,
                )
            }
        )
        return result
    }

    private fun endpointReadWrite(type: SyncEndpointType, ref: String): Pair<Boolean, Boolean>? {
        return when (type) {
            SyncEndpointType.Local -> true to true
            SyncEndpointType.Device -> {
                val permission = deviceState.devices.firstOrNull { item -> item.id == ref }?.menuPermission
                if (permission == null) null else permission.copy to permission.paste
            }

            SyncEndpointType.Network -> {
                val permission = networkState.networks.firstOrNull { item -> item.buildProtocolId() == ref && item.supportsSync }?.menuPermission
                if (permission == null) null else permission.copy to permission.paste
            }
        }
    }

    private fun endpointSeparatorForValidation(type: SyncEndpointType, ref: String): String {
        return when (type) {
            SyncEndpointType.Local -> PathUtils.getPathSeparator()
            SyncEndpointType.Device -> {
                deviceState.devices.firstOrNull { item -> item.id == ref }?.pathSeparator ?: PathUtils.getPathSeparator()
            }

            SyncEndpointType.Network -> {
                networkState.networks.firstOrNull { item -> item.buildProtocolId() == ref }?.pathSeparator ?: "/"
            }
        }
    }

    fun validateTask(
        name: String,
        sourceType: SyncEndpointType,
        sourceRef: String,
        sourcePath: String,
        targetType: SyncEndpointType,
        targetRef: String,
        targetPath: String,
        strictEndpointAvailability: Boolean = true,
    ): String? {
        if (name.isBlank()) return AppStrings.ui_task_name_cannot_empty
        if (sourcePath.isBlank()) return AppStrings.ui_source_path_cannot_empty
        if (targetPath.isBlank()) return AppStrings.ui_target_path_cannot_empty
        if (sourceType != SyncEndpointType.Local && sourceRef.isBlank()) {
            return AppStrings.ui_please_select_source_endpoint_first
        }
        if (targetType != SyncEndpointType.Local && targetRef.isBlank()) {
            return AppStrings.ui_please_select_target_endpoint_first
        }

        val normalizedSourcePath = normalizePath(sourcePath)
        val normalizedTargetPath = normalizePath(targetPath)
        if (
            sourceType == targetType &&
            sourceRef == targetRef &&
            normalizedSourcePath == normalizedTargetPath
        ) {
            return AppStrings.ui_source_target_cannot_same
        }
        if (sourceType == targetType && sourceRef == targetRef) {
            val separator = endpointSeparatorForValidation(sourceType, sourceRef)
            if (syncTargetInsideSourcePath(normalizedSourcePath, normalizedTargetPath, separator)) {
                return AppStrings.ui_target_path_cannot_within_source_directory
            }
        }

        if (strictEndpointAvailability) {
            if (sourceType == SyncEndpointType.Device && deviceState.devices.none { item -> item.id == sourceRef }) {
                return AppStrings.ui_source_device_does_not_exist_not_connected
            }
            if (targetType == SyncEndpointType.Device && deviceState.devices.none { item -> item.id == targetRef }) {
                return AppStrings.ui_target_device_does_not_exist_not_connected
            }
            if (sourceType == SyncEndpointType.Network && networkState.networks.none { it.buildProtocolId() == sourceRef }) {
                return AppStrings.ui_source_network_disk_does_not_exist
            }
            if (targetType == SyncEndpointType.Network && networkState.networks.none { it.buildProtocolId() == targetRef }) {
                return AppStrings.ui_target_network_disk_does_not_exist
            }

            val sourceReadWrite = endpointReadWrite(sourceType, sourceRef)
                ?: return AppStrings.ui_source_endpoint_unavailable
            val targetReadWrite = endpointReadWrite(targetType, targetRef)
                ?: return AppStrings.ui_target_endpoint_unavailable

            if (!sourceReadWrite.first) return AppStrings.ui_source_endpoint_does_not_support_reading
            if (!targetReadWrite.second) return AppStrings.ui_target_endpoint_does_not_support_writing
        }

        return null
    }

    fun isTaskOwnedByCurrentDevice(task: SyncTask): Boolean = isSyncTaskOwnedBy(task, deviceIdentity().id)

    suspend fun createOrUpdateTask(
        task: SyncTask,
        confirmReverseTasks: Boolean = false,
        takeOwnership: Boolean = false,
    ): Result<SyncTask> = lifecycleMutex.withLock {
        syncCapabilitySettingsError(task)?.let { return Result.failure(IllegalArgumentException(it)) }
        val existing = getTask(task.id)
        if (existing != null && !isTaskOwnedByCurrentDevice(existing) && !takeOwnership) {
            return Result.failure(IllegalStateException(AppStrings.ui_source_endpoint_unavailable))
        }
        if (task.retryCount !in 0..5 || task.timeoutSeconds != 0 && task.timeoutSeconds !in 30..3600 || task.parallelism !in 1..8) {
            return Result.failure(IllegalArgumentException(AppStrings.sync_invalid_execution_settings))
        }
        if (task.destinations.isNotEmpty()) {
            try {
                requireDistinctStorageDestinations(task.destinations)
                require(task.policyVersion > 0) { AppStrings.ui_storage_rule_version }
                if (task.backup && task.scheduleType == SyncScheduleType.Interval) {
                    require(task.intervalMinutes in 1..525_600) { AppStrings.ui_interval_minutes }
                    require(task.destinations.all { destination ->
                        networkState.entries.any { it.isPersisted && destination.matches(it) }
                    }) { AppStrings.ui_storage_session_rule }
                }
                for (destination in task.destinations) {
                    validateTask(task.name, task.sourceType, task.sourceRef, task.sourcePath,
                        destination.type, destination.ref, destination.path)?.let { error(it) }
                    networkState.validateStorageDestination(destination).getOrThrow()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { return Result.failure(error) }
        }
        val error = validateTask(
            name = task.name,
            sourceType = task.sourceType,
            sourceRef = task.sourceRef,
            sourcePath = task.sourcePath,
            targetType = task.targetType,
            targetRef = task.targetRef,
            targetPath = task.targetPath,
            strictEndpointAvailability = task.id <= 0L || takeOwnership,
        )
        if (error != null) {
            return Result.failure(IllegalArgumentException(error))
        }

        for ((type, ref, write) in listOf(Triple(task.sourceType, task.sourceRef, false), Triple(task.targetType, task.targetRef, true))) {
            if (type != SyncEndpointType.Network) continue
            val network = networkState.entries.firstOrNull { it.network.buildProtocolId() == ref }?.network
            if (network != null) {
                val permissions = network.syncReadWrite().getOrElse { return Result.failure(it) }
                if (if (write) !permissions.second else !permissions.first) {
                    return Result.failure(IllegalArgumentException(if (write) AppStrings.ui_target_endpoint_does_not_support_writing else AppStrings.ui_source_endpoint_does_not_support_reading))
                }
            }
        }
        val normalizedIgnoreFileNames = normalizeSupportedIgnoreFileNames(task.ignoreFileNames)
        if (task.useIgnoreFiles) {
            val unsupported = task.ignoreFileNames.firstOrNull { fileName ->
                fileName !in SupportedIgnoreFileNames
            }
            if (unsupported != null) {
                return Result.failure(
                    IllegalArgumentException(
                        AppStrings.ui_unsupported_ignore_file_arg0.format(arg0 = unsupported)
                    )
                )
            }
            if (normalizedIgnoreFileNames.isEmpty()) {
                return Result.failure(
                    IllegalArgumentException(AppStrings.ui_select_at_least_one_ignore_file)
                )
            }
            val discovered = discoverSourceIgnoreFileNames(
                sourceType = task.sourceType,
                sourceRef = task.sourceRef,
                sourcePath = task.sourcePath,
                storageReference = task.sourceStorageReference,
            ).getOrElse { error -> return Result.failure(error) }
            val missing = normalizedIgnoreFileNames.firstOrNull { fileName -> fileName !in discovered }
            if (missing != null) {
                return Result.failure(
                    IllegalArgumentException(
                        AppStrings.ui_ignore_file_not_found_in_source_arg0.format(arg0 = missing)
                    )
                )
            }
        }

        val now = nowMillis()
        val identity = deviceIdentity()
        val sourceReferenceKeys = task.selectedSources.filter { it.protocol == FileProtocol.Network && !it.isDirectory &&
            !it.storageReference?.id.isNullOrEmpty() }.mapTo(mutableSetOf(), ::syncSelectedSourceKey)
        val normalized = task.copy(
            ownerDeviceId = if (task.id <= 0 || takeOwnership || task.ownerDeviceId.isBlank()) identity.id else task.ownerDeviceId,
            ownerDeviceName = if (task.id <= 0 || takeOwnership || task.ownerDeviceId.isBlank()) identity.name else task.ownerDeviceName,
            policyVersion = if (task.destinations.isNotEmpty()) (getTask(task.id)?.policyVersion ?: 0L) + 1L else task.policyVersion,
            id = task.id.coerceAtLeast(0L),
            sourcePath = normalizePath(task.sourcePath),
            targetPath = normalizePath(task.targetPath),
            ignoreFileNames = normalizedIgnoreFileNames,
            currentSourceReferences = task.currentSourceReferences.filterKeys { it in sourceReferenceKeys },
            createdAt = if (task.createdAt > 0L) task.createdAt else now,
            updatedAt = now,
            nextRunAt = computeNextRun(task.copy(updatedAt = now), now),
        )

        try { normalized.resolvedSelectedSources() }
        catch (error: Exception) { return Result.failure(error) }

        val sourceDesk = resolveEndpoint(normalized.sourceType, normalized.sourceRef)
        val targetDesk = resolveEndpoint(normalized.targetType, normalized.targetRef)
        val sourceLocation = if (sourceDesk != null) endpointFiles.readPathInfo(normalized.sourceType, normalized.sourceRef, sourceDesk, normalized.sourcePath, sourceLocation(normalized)).getOrElse { return Result.failure(it) } else null
        val targetLocation = if (targetDesk != null) endpointFiles.readPathInfo(normalized.targetType, normalized.targetRef, targetDesk, normalized.targetPath, targetLocation(normalized)).getOrElse { return Result.failure(it) } else null
        if (takeOwnership && sourceLocation == null) return Result.failure(IllegalArgumentException(AppStrings.ui_source_path_does_not_exist))
        if (normalized.mode != SyncMode.Update) {
            validateModeEndpoints(normalized)?.let { return Result.failure(it) }
        }
        val reverse = findReverseSyncTasks(normalized, snapshotTasks(), ::endpointSeparatorForValidation, identity.id)
        if (!confirmReverseTasks && reverse.isNotEmpty()) return Result.failure(SyncReverseTaskException(reverse))
        val saved = try {
            withContext(Dispatchers.Default) {
                  taskStore.withExclusiveAccess {
                        if (existing != null) {
                            val persisted = taskStore.loadTasks().firstOrNull { it.id == existing.id }
                            check(persisted != null && persisted.ownerDeviceId == existing.ownerDeviceId) { AppStrings.ui_source_endpoint_unavailable }
                        }
                        taskStore.saveTask(normalized.copy(
                            sourceStorageReference = sourceLocation?.storageReference ?: normalized.sourceStorageReference,
                            targetStorageReference = targetLocation?.storageReference ?: normalized.targetStorageReference,
                        ))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { return Result.failure(error) }

            try { localStore?.reconcile(saved) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { return Result.failure(error) }
            replaceOrAddTask(saved)
            if (existing != null && syncPreviewFingerprint(existing) != syncPreviewFingerprint(saved)) conditionRuns.cancel(saved.id)
            if (existing != null && syncDefinitionFingerprint(existing) != syncDefinitionFingerprint(saved)) clearSyncNotification(saved.id)
            refreshSyncAttention(saved.id)
            notifyChanged()
            SyncSnapshotChangeNotifier.onSyncTasksConfigurationChanged()
            return Result.success(saved)
        }

        private suspend fun validateModeEndpoints(task: SyncTask): Exception? {
            val sources = task.resolvedSelectedSources().takeIf { it.isNotEmpty() } ?: listOf(sourceLocation(task))
            val targets = task.destinations.takeIf { it.isNotEmpty() }?.map { destination ->
                FileSimpleInfo.pathFileSimpleInfo(destination.path).withCopy(protocol = toProtocol(destination.type),
                    protocolId = destination.ref, storageReference = destination.storageReference)
            } ?: listOf(targetLocation(task))
            for ((source, locations) in listOf(true to sources, false to targets)) for (location in locations) {
                val type = when (location.protocol) {
                    FileProtocol.Local -> SyncEndpointType.Local
                    FileProtocol.Device -> SyncEndpointType.Device
                    FileProtocol.Network -> SyncEndpointType.Network
                    else -> return IllegalArgumentException(AppStrings.ui_source_endpoint_unavailable)
                }
                val desk = resolveEndpoint(type, location.protocolId)
                    ?: return IllegalArgumentException(if (source) AppStrings.ui_source_endpoint_unavailable else AppStrings.ui_target_endpoint_unavailable)
                val info = endpointFiles.readPathInfo(type, location.protocolId, desk, location.path, location)
                    .getOrElse { return it as? Exception ?: IllegalStateException(it) }
                    ?: return IllegalArgumentException(if (source) AppStrings.ui_source_path_does_not_exist else AppStrings.ui_target_endpoint_unavailable)
                if (source && task.selectedSources.isNotEmpty() && !location.isDirectory &&
                    (info.name != location.name || info.isDirectory || !location.storageReference?.parentId.isNullOrEmpty() &&
                        info.storageReference?.parentId != location.storageReference.parentId)) {
                    return IllegalArgumentException("Selected source identity changed; select the source again")
                }
                val capabilities = endpointFiles.capabilities(desk).getOrElse { return it as? Exception ?: IllegalStateException(it) }
                syncModeEndpointError(task.mode, source, info.isDirectory || source && task.selectedSources.isNotEmpty(), capabilities)
                    ?.let { return IllegalArgumentException(it) }
            }
            return null
        }

        suspend fun discoverSourceIgnoreFileNames(
            sourceType: SyncEndpointType,
            sourceRef: String,
            sourcePath: String,
            storageReference: StorageReference? = null,
        ): Result<List<String>> {
            val sourceDesk = resolveEndpoint(sourceType, sourceRef)
                ?: return Result.failure(IllegalStateException(AppStrings.ui_source_endpoint_unavailable))
            val normalizedSourcePath = normalizePath(sourcePath)
            val location = FileSimpleInfo.pathFileSimpleInfo(normalizedSourcePath).withCopy(storageReference = storageReference)
            val sourceInfo = endpointFiles.readPathInfo(
                type = sourceType,
                ref = sourceRef,
                desk = sourceDesk,
                path = normalizedSourcePath,
                location = location,
            ).getOrElse { error -> return Result.failure(error) }
            if (sourceInfo?.isDirectory != true) {
                return Result.failure(
                    IllegalArgumentException(AppStrings.ui_advanced_ignore_requires_directory_source)
                )
            }
            return endpointFiles.listChildren(
                type = sourceType,
                ref = sourceRef,
                desk = sourceDesk,
                path = normalizedSourcePath,
                location = sourceInfo,
            ).map(::findExistingSupportedIgnoreFileNames)
        }

        fun newTaskTemplate(): SyncTask {
            val now = nowMillis()
            val identity = deviceIdentity()
            return SyncTask(
                id = 0,
                name = "",
                sourceType = SyncEndpointType.Local,
                sourceRef = "",
                sourcePath = PathUtils.getHomePath(),
                targetType = SyncEndpointType.Local,
                targetRef = "",
                targetPath = PathUtils.getHomePath(),
                ownerDeviceId = identity.id,
                ownerDeviceName = identity.name,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun getTask(taskId: Long): SyncTask? {
            return tasks.firstOrNull { item -> item.id == taskId }
        }

        fun snapshotTasks(): List<SyncTask> = tasks.toList()

        fun duplicateTask(taskId: Long) {
            val source = getTask(taskId) ?: return
            val now = nowMillis()
            val duplicate = source.copy(
                id = 0,
                name = AppStrings.sync_copy_name_arg0.format(arg0 = source.name),
                lastRunAt = 0,
                nextRunAt = 0,
                lastStatus = SyncRunStatus.Idle,
                lastMessage = "",
                createdAt = now,
                updatedAt = now,
            )

            scope.launch(Dispatchers.Main) {
                createOrUpdateTask(duplicate)
            }
        }

        fun deleteTask(taskId: Long) {
            if (taskId in activeStorageGroups) {
                cancelActiveRun(taskId)
                scope.launch {
                    val lock = runLocksMutex.withLock { runLocks[taskId] } ?: return@launch
                    lock.withLock { withContext(Dispatchers.Main) { deleteTask(taskId) } }
                }
                return
            }
            conditionRuns.cancel(taskId)
            runQueue.cancel(taskId)
            runningOperationTasks[taskId]?.let { taskState.requestCancel(it) }
            tasks.removeAll { item -> item.id == taskId }
            taskAttention.remove(taskId)
            runHistory.remove(taskId)
            runLocks.remove(taskId)
            runningOperationTasks.remove(taskId)
            deleteTaskFromStoreAsync(taskId)
            notifyChanged()
            SyncSnapshotChangeNotifier.onSyncTasksConfigurationChanged()
        }

        fun updateEnabled(taskId: Long, enabled: Boolean): Boolean {
            val index = tasks.indexOfFirst { item -> item.id == taskId }
            if (index < 0) return false

            val current = tasks[index]
            if (!isTaskOwnedByCurrentDevice(current)) return false
            conditionRuns.cancel(taskId)
            val now = nowMillis()
            tasks[index] = current.copy(
                enabled = enabled,
                updatedAt = now,
                nextRunAt = computeNextRun(current.copy(enabled = enabled), now)
            )
            saveTaskPatchAsync(current) { persisted ->
                persisted.copy(enabled = enabled, updatedAt = now,
                    nextRunAt = computeNextRun(persisted.copy(enabled = enabled), now))
            }
            notifyChanged()
            SyncSnapshotChangeNotifier.onSyncTasksConfigurationChanged()
            return true
        }

        fun recordsFor(taskId: Long): List<SyncRunRecord> {
            return runHistory[taskId].orEmpty()
        }

        fun clearRunRecords(taskId: Long) {
            if (!runHistory.containsKey(taskId)) return
            runHistory.remove(taskId)
            deleteRunsFromStoreAsync(taskId)
            notifyChanged()
        }

        fun getRunRecord(taskId: Long, runId: Long): SyncRunRecord? {
            return runHistory[taskId].orEmpty().firstOrNull { item -> item.runId == runId }
        }

        internal fun enqueueRun(task: SyncTask, ignoreConditions: Boolean = false, run: suspend () -> Unit): Boolean {
            if (!isTaskOwnedByCurrentDevice(task) || task.id in runningOperationTasks || task.id in activeStorageGroups || runLocks[task.id]?.isLocked == true) return false
            return conditionRuns.offer(task, ignoreConditions, run)
        }

        private fun enqueueReadyRun(task: SyncTask, run: suspend () -> Unit): Boolean {
            return runQueue.enqueue(task.id, onQueued = {
                val current = getTask(task.id) ?: task
                replaceOrAddTask(current.copy(lastStatus = SyncRunStatus.Queued, lastMessage = ""))
                notifyChanged()
            }, run = run)
        }

        internal fun hasActiveSyncRun(taskId: Long): Boolean = taskId in runningOperationTasks || taskId in activeStorageGroups ||
            runLocks[taskId]?.isLocked == true || getTask(taskId)?.lastStatus in listOf(SyncRunStatus.Running, SyncRunStatus.Queued)

        fun runNow(taskId: Long) { runNowIfIdle(taskId) }

        fun runNowIfIdle(taskId: Long): SyncTask? = runNowIfIdle(taskId, ignoreConditions = false)

        fun runNowIfIdle(taskId: Long, ignoreConditions: Boolean): SyncTask? {
            val task = getTask(taskId) ?: return null
            if (requiresSyncPreview(task)) { requestSyncPreviewConfirmation(taskId); return null }
            if (task.lastStatus == SyncRunStatus.Running || task.lastStatus == SyncRunStatus.Queued ||
                taskId in runningOperationTasks || taskId in activeStorageGroups || runLocks[taskId]?.isLocked == true) return null
            if (!enqueueRun(task, ignoreConditions) { runTaskInternal(task, "manual") }) return null
            return getTask(taskId)
        }

        suspend fun failedItemsFor(taskId: Long, runId: Long): List<SyncFailureItem>? =
            withContext(Dispatchers.Default) { taskStore.loadFailedItems(taskId, runId) }

        fun retryFailedItems(taskId: Long, runId: Long) = retryFailedItems(taskId, runId, ignoreConditions = false)

        fun retryFailedItems(taskId: Long, runId: Long, ignoreConditions: Boolean) {
            val record = getRunRecord(taskId, runId) ?: return
            val task = getTask(taskId) ?: return
            if (!isTaskOwnedByCurrentDevice(task)) return
            if (record.targets.isNotEmpty()) {
                retryStorageTargets(taskId, runId, record.targets.filter { it.status != SyncRunStatus.Success }.map { it.operationTaskKey }.toSet(), emptyMap(), ignoreConditions)
                return
            }
            if (record.failureCount <= 0) return
            enqueueRun(task, ignoreConditions) {
                val failed = failedItemsFor(taskId, runId)
                val selected = failed?.takeIf { record.unsavedFailureCount == 0 && it.isNotEmpty() }
                runTaskInternal(task, "retry", retryFailures = selected, retryOfRunId = runId, retryWholeTask = selected == null)
            }
        }

        fun cancelActiveRun(taskId: Long) {
            getTask(taskId)?.let { if (!isTaskOwnedByCurrentDevice(it)) return }
            val canceledWaiting = conditionRuns.cancel(taskId)
            runQueue.cancel(taskId)
            controlStorageRun(taskId, StorageGroupControl.Cancel)
            val operationTask = runningOperationTasks[taskId]
            if (operationTask != null) {
                taskState.requestCancel(operationTask, AppStrings.ui_user_cancels_sync)
            }

            val index = tasks.indexOfFirst { item -> item.id == taskId }
            if (index < 0) return

            val task = tasks[index]
            if (canceledWaiting || task.lastStatus == SyncRunStatus.Running || task.lastStatus == SyncRunStatus.Queued) {
                tasks[index] = task.copy(
                    lastStatus = SyncRunStatus.Canceled,
                    lastMessage = AppStrings.ui_canceled,
                    updatedAt = nowMillis(),
                    nextRunAt = computeNextRun(task, nowMillis()),
                )
                val canceledAt = tasks[index].updatedAt
                saveTaskPatchAsync(task) { persisted ->
                    persisted.copy(lastStatus = SyncRunStatus.Canceled, lastMessage = AppStrings.ui_canceled,
                        updatedAt = canceledAt, nextRunAt = computeNextRun(persisted, canceledAt))
                }
                if (operationTask == null && taskId !in activeStorageGroups) {
                    val now = nowMillis()
                    val canceled = SyncRunRecord(now, task.id, "manual", now, now, SyncRunStatus.Canceled,
                        0, 0, 0, AppStrings.ui_sync_canceled)
                    scope.launch { onTaskFinished(task, canceled, updateRuntime = false) }
                }
                notifyChanged()
            }
        }

        internal suspend fun runTaskInternal(
            task: SyncTask, trigger: String, retrySources: List<FileSimpleInfo>? = null,
            storageRetry: SyncRunRecord? = null, selectedTargetKeys: Set<Long> = emptySet(),
            selectedFiles: Map<Long, Set<String>> = emptyMap(),
            retryFailures: List<SyncFailureItem>? = null,
            retryOfRunId: Long = 0,
            retryWholeTask: Boolean = false,
            preparedPreview: SyncPreparedRun? = null,
            approvedDeletions: Set<SyncPendingDelete> = emptySet(),
            scheduledAt: Long? = null,
            systemScheduled: Boolean = false,
        ) {
            if (!isTaskOwnedByCurrentDevice(task)) return
            val runAgain: suspend () -> Unit = {
                if (systemScheduled) systemScheduler.refresh()
                else runTaskInternal(task, trigger, retrySources, storageRetry, selectedTargetKeys, selectedFiles,
                    retryFailures, retryOfRunId, retryWholeTask, preparedPreview, approvedDeletions, scheduledAt)
            }
            if (conditionRuns.deferIfNeeded(task, runAgain)) return
            val lock = runLocksMutex.withLock { runLocks.getOrPut(task.id) { Mutex() } }
            if (!lock.tryLock()) {
                return
            }

            var operationTask: Task? = null

            try {
                runQueue.withPermit {
                    val latest = getTask(task.id) ?: return@withPermit
                    if (!isTaskOwnedByCurrentDevice(latest) || latest.ownerDeviceId != task.ownerDeviceId) return@withPermit
                    if (scheduledAt != null) {
                        val persisted = withContext(Dispatchers.Default) {
                            taskStore.withExclusiveAccess { taskStore.loadTasks().firstOrNull { it.id == task.id } }
                        }
                        if (!matchesSyncDueRun(task, latest, scheduledAt, nowMillis()) ||
                            !matchesSyncDueRun(task, persisted, scheduledAt, nowMillis())) {
                            if (latest.lastStatus == SyncRunStatus.Queued) updateTaskStatus(latest.id, SyncRunStatus.Idle, "")
                            return@withPermit
                        }
                    }
                    // Update groups retain their saved run settings; a mode change always invalidates that run.
                    if ((task.mode != SyncMode.Update || latest.mode != SyncMode.Update) &&
                        syncDefinitionFingerprint(latest) != syncDefinitionFingerprint(task)) {
                        updateTaskStatus(latest.id, SyncRunStatus.Idle, "")
                        return@withPermit
                    }
                    conditionRuns.execute(task, runAgain, trigger) execute@ {
                        val current = task
                        if (!localStore?.recoveries(current.id).isNullOrEmpty()) {
                            recoverSyncTaskBeforeScan(current, createOperationTask(current))
                        }
                        check(localStore?.recoveries(current.id).isNullOrEmpty()) { "Synchronization has pending recovery operations" }
                        if (current.mode != SyncMode.Update) {
                            if (deferUnconfirmedSyncRun(current)) {
                                updateTaskStatus(current.id, SyncRunStatus.Idle, AppStrings.sync_preview_required)
                                return@execute
                            }
                            val operation = createOperationTask(current)
                            operationTask = operation
                            taskState.beginTaskOutputs(operation)
                            runningOperationTasks[current.id] = operation
                            taskState.addOrUpdate(operation)
                            updateTaskStatus(current.id, SyncRunStatus.Running, AppStrings.ui_synchronously_executing)
                            val started = nowMillis()
                            val record = try {
                                val prepared = preparedPreview ?: withSyncConditionPath { prepareSyncRun(current, operation).getOrThrow() }
                                check(prepared.task.id == current.id && prepared.previewFingerprint == syncPreviewFingerprint(current)) { AppStrings.sync_preview_expired }
                                executeSyncPreparedRun(prepared, operation, trigger, consent = preparedPreview, approvedDeletions = approvedDeletions)
                            } catch (_: CancellationException) {
                                SyncRunRecord(started, current.id, trigger, started, nowMillis(), SyncRunStatus.Canceled, 0, 0, 0, AppStrings.ui_sync_canceled)
                            } catch (error: Exception) {
                                val message = sanitizeSyncMessage(error.message ?: AppStrings.ui_sync_failed)
                                SyncRunRecord(started, current.id, trigger, started, nowMillis(), SyncRunStatus.Failure, 1, 0, 1,
                                    message, listOf(SyncRunItem("", false, message)))
                            }
                            val published = record.copy(retryOfRunId = retryOfRunId,
                                message = if (retryWholeTask) "${AppStrings.sync_retry_whole_task}: ${record.message}" else record.message)
                            withContext(NonCancellable) { onTaskFinished(current, published) }
                            finalizeOperationTask(operation, published, currentCoroutineContext()[SyncConditionRun]?.interrupted.orEmpty())
                            return@execute
                        }
                        if (current.destinations.isNotEmpty()) {
                            updateTaskStatus(current.id, SyncRunStatus.Running, AppStrings.ui_synchronously_executing)
                            executeStorageGroup(current, trigger, storageRetry, selectedTargetKeys, selectedFiles, frozen = preparedPreview)
                            return@execute
                        }
                        for (source in listOf(true, false)) {
                            val endpointTask = if (source) current.copy(targetType = current.sourceType, targetRef = current.sourceRef,
                                targetPath = current.sourcePath, targetStorageReference = current.sourceStorageReference) else current
                            val connectionError = withTimeoutOrNull(TARGET_ENDPOINT_CONNECT_WAIT_MS) {
                                targetEndpointMissingError(endpointTask) ?: connectTargetEndpointIfNeeded(endpointTask) ?: ""
                            } ?: AppStrings.ui_arg0_connection_failed.format(arg0 = if (source) AppStrings.ui_source_endpoint else AppStrings.ui_target_endpoint)
                            if (connectionError.isNotBlank()) {
                                val role = if (source) AppStrings.ui_source_endpoint else AppStrings.ui_target_endpoint
                                val record = skippedRunRecord(current, trigger, sanitizeSyncMessage("$role: $connectionError"))
                                onTaskFinished(current, record.copy(status = SyncRunStatus.Failure, retryOfRunId = retryOfRunId))
                                return@execute
                            }
                        }

                        val operation = createOperationTask(current)
                        operationTask = operation
                        taskState.beginTaskOutputs(operation)
                        runningOperationTasks[current.id] = operation
                        taskState.addOrUpdate(operation)

                        updateTaskStatus(current.id, SyncRunStatus.Running, AppStrings.ui_synchronously_executing)
                        val failedItems = mutableListOf<SyncFailureItem>()
                        val record = if (preparedPreview?.update != null) executeUpdatePreparedRun(preparedPreview, operation, trigger, failedItems)
                            else preserveSyncRetryIdentity(executeTask(current, trigger, operation, retrySources, retryFailures, failedItems), retrySources)
                        withContext(NonCancellable) { onTaskFinished(current, record.copy(retryOfRunId = retryOfRunId,
                            message = if (retryWholeTask) "${AppStrings.sync_retry_whole_task}: ${record.message}" else record.message), failedItems) }
                        finalizeOperationTask(operation, record, currentCoroutineContext()[SyncConditionRun]?.interrupted.orEmpty())
                    }
                }
            } finally {
                operationTask?.let { item ->
                    runningOperationTasks.remove(task.id)
                    taskState.clearSignals(item.key)
                    taskState.taskOutputsFinished(item, interrupted = !currentCoroutineContext().isActive)
                }
                lock.unlock()
            }
        }

        private fun skippedRunRecord(
            task: SyncTask,
            trigger: String,
            message: String,
        ): SyncRunRecord {
            val now = nowMillis()
            return SyncRunRecord(
                runId = now,
                taskId = task.id,
                trigger = trigger,
                startedAt = now,
                endedAt = now,
                status = SyncRunStatus.Failure,
                totalCount = 1,
                successCount = 0,
                failureCount = 1,
                message = message,
                items = listOf(SyncRunItem(path = task.targetPath, success = false, message = message)),
            )
        }

        internal fun createOperationTask(task: SyncTask): Task {
            return Task(
                taskType = TaskType.Copy,
                status = StatusEnum.LOADING,
                values = mapOf("path" to task.sourcePath),
                protocol = toProtocol(task.sourceType),
                protocolId = task.sourceRef,
            )
        }

        internal fun finalizeOperationTask(operationTask: Task, record: SyncRunRecord, conditionsLost: Set<SyncRunCondition> = emptySet()) {
            val status = if (conditionsLost.isEmpty()) record.status else SyncRunStatus.Canceled
            when (status) {
                SyncRunStatus.Success -> {
                    taskState.completeTask(operationTask)
                }

                SyncRunStatus.PartialSuccess,
                SyncRunStatus.Interrupted,
                SyncRunStatus.Failure,
                SyncRunStatus.Canceled,
                SyncRunStatus.Queued,
                SyncRunStatus.Running,
                SyncRunStatus.Idle,
                -> {
                    val message = when (status) {
                        SyncRunStatus.Canceled -> if (conditionsLost.isEmpty()) AppStrings.ui_sync_canceled else syncConditionsLostMessage(conditionsLost)
                        else -> record.message
                    }
                    taskState.putResult(operationTask, "sync_result", message)
                    taskState.updateStatus(operationTask, StatusEnum.FAILURE)
                }
            }
        }

        internal suspend fun updateTaskStatus(taskId: Long, status: SyncRunStatus, message: String) {
            var updatedTask: SyncTask? = null
            withContext(Dispatchers.Main) {
                val index = tasks.indexOfFirst { item -> item.id == taskId }
                if (index < 0) return@withContext

                val task = tasks[index]
                val nextTask = task.copy(
                    lastStatus = status,
                    lastMessage = message,
                    updatedAt = nowMillis(),
                )
                updatedTask = nextTask
                tasks[index] = nextTask
                notifyChanged()
            }
            updatedTask?.let { task ->
                withContext(Dispatchers.Default) {
                    taskStore.updateOwnedSyncTask(task) { persisted ->
                        persisted.copy(lastStatus = status, lastMessage = message, updatedAt = task.updatedAt)
                    }
                }
            }
        }

        internal suspend fun ensureOperationRunning(taskKey: Long) {
            currentCoroutineContext().ensureActive()
            if (!taskState.awaitIfPaused(taskKey)) {
                throw CancellationException(AppStrings.message_task_cancelled)
            }
            if (taskState.isTaskCancelled(taskKey)) {
                throw CancellationException(AppStrings.message_task_cancelled)
            }
        }

        internal fun appendRunItem(
            items: MutableList<SyncRunItem>,
            path: String,
            success: Boolean,
            message: String,
            source: FileSimpleInfo? = null,
        ) {
            if (items.size >= RUN_ITEM_LIMIT) {
                if (success) return
                val successIndex = items.indexOfLast { it.success }
                if (successIndex < 0) return
                items.removeAt(successIndex)
            }
            items.add(
                SyncRunItem(
                    path = path,
                    success = success,
                    message = sanitizeSyncMessage(message),
                    source = source,
                )
            )
        }

        internal fun sourceLocation(task: SyncTask) = FileSimpleInfo.pathFileSimpleInfo(task.sourcePath).withCopy(
            protocol = toProtocol(task.sourceType), protocolId = task.sourceRef, storageReference = task.sourceStorageReference,
        ).let { root -> task.resolvedSelectedSources().firstOrNull { !it.isDirectory && it.path == task.sourcePath }
            ?.let { root.copy(storageReference = it.storageReference) } ?: root }

        internal suspend fun rememberSyncSourceReference(expected: SyncTask, baseline: SyncBaseline) {
            val selected = expected.selectedSources.firstOrNull { !it.isDirectory && it.protocol == FileProtocol.Network &&
                !it.storageReference?.id.isNullOrEmpty() && it.name == baseline.relativePath }
                ?: return
            val reference = baseline.selectedSourceReference ?: return
            val key = syncSelectedSourceKey(selected)
            val fingerprint = syncDefinitionFingerprint(expected)
            val saved = lifecycleMutex.withLock {
                withContext(Dispatchers.Default) {
                    taskStore.withExclusiveAccess {
                        val current = taskStore.loadTasks().firstOrNull { it.id == expected.id }
                        check(current != null && current.ownerDeviceId == expected.ownerDeviceId && isTaskOwnedByCurrentDevice(current) &&
                            syncDefinitionFingerprint(current) == fingerprint) { "Synchronization definition changed before saving its source reference" }
                        val updated = current.copy(currentSourceReferences = current.currentSourceReferences + (key to reference),
                            sourceStorageReference = if (current.sourcePath == selected.path) reference else current.sourceStorageReference)
                        updated.resolvedSelectedSources()
                        if (updated == current) current else taskStore.saveTask(updated.copy(updatedAt = nowMillis()))
                    }
                }.also { task ->
                    withContext(Dispatchers.Main) {
                        val latest = getTask(task.id)
                        check(latest != null && latest.ownerDeviceId == task.ownerDeviceId && syncDefinitionFingerprint(latest) == fingerprint) {
                            "Synchronization was removed or changed before saving its source reference"
                        }
                        replaceOrAddTask(task)
                        notifyChanged()
                    }
                }
            }
            if (saved.currentSourceReferences != expected.currentSourceReferences) SyncSnapshotChangeNotifier.onSyncTasksConfigurationChanged()
        }

        private fun targetLocation(task: SyncTask) = FileSimpleInfo.pathFileSimpleInfo(task.targetPath).withCopy(
            protocol = toProtocol(task.targetType), protocolId = task.targetRef, storageReference = task.targetStorageReference,
        )

        internal suspend fun copyOnce(
            task: SyncTask,
            operationTask: Task,
            sourceDesk: DiskBase,
            targetDesk: DiskBase,
            sourceFile: FileSimpleInfo,
            destinationPath: String,
        ): Result<Boolean> {
            val source = sourceFile.withCopy(
                protocol = toProtocol(task.sourceType),
                protocolId = task.sourceRef,
            )
            val target = endpointFiles.readPathInfo(task.targetType, task.targetRef, targetDesk, destinationPath, targetLocation(task))
                .getOrElse { return Result.failure(it) }
            val destination = sourceFile.copy(
                path = destinationPath,
                protocol = toProtocol(task.targetType),
                protocolId = task.targetRef,
                storageReference = target?.storageReference ?: targetLocation(task).withCopy(path = destinationPath).storageReference,
            )
            taskState.putTransientResult(operationTask, destinationPath, AppStrings.ui_start_syncing)

            ensureOperationRunning(operationTask.key)
            beforeSyncConditionFile()
            taskState.clearRuntimeByteItem(operationTask, destinationPath)
            taskState.startRuntimeByteItem(operationTask, destinationPath, sourceFile.size.coerceAtLeast(0))
            val result = fileState.runCopyAcrossEndpoints(
                task = operationTask,
                src = source,
                dest = destination,
                sourceDesk = sourceDesk,
                targetDesk = targetDesk,
            )

            if (result.isSuccess && result.getOrDefault(false)) {
                taskState.putTransientResult(operationTask, destinationPath, AppStrings.ui_synchronization_completed)
                return Result.success(true)
            }

            val error = result.exceptionOrNull()
            if (error is CancellationException && taskState.isTaskCancelled(operationTask.key)) {
                return Result.failure(error)
            }

            return Result.failure(error ?: IllegalStateException(AppStrings.ui_copy_failed))
        }

        internal suspend fun loadSyncIgnoreMatcher(task: SyncTask, sourceDesk: DiskBase, sourceRoot: FileSimpleInfo): Result<ResolvedIgnoreMatcher?> {
            if (!task.useIgnoreFiles) return Result.success(null)
            val rootChildren = endpointFiles.listChildren(task.sourceType, task.sourceRef, sourceDesk, sourceRoot.path, sourceRoot)
                .getOrElse { return Result.failure(it) }
            return loadResolvedIgnoreMatcherStrict(IgnorePreferenceScope(
                toProtocol(task.sourceType), task.sourceRef, sourceRoot.path, task.ignoreFileNames,
                endpointFiles.endpointSeparator(task.sourceType, sourceDesk),
            )) { path ->
                val matches = rootChildren.filter { it.path == path }
                if (matches.size != 1) Result.failure(IllegalArgumentException(AppStrings.operation_ignore_file_unreadable))
                else endpointFiles.readIgnoreLines(task.sourceType, sourceDesk, matches.single())
            }
        }

        internal suspend fun collectSourceSnapshot(
            task: SyncTask,
            sourceDesk: DiskBase,
            sourceRoot: FileSimpleInfo,
            filters: List<String>,
            relativePrefix: String = "",
        ): Result<SyncSourceSnapshot> {
            if (!sourceRoot.isDirectory) {
                val relative = relativePrefix.ifEmpty { fileNameOf(sourceRoot.path, endpointFiles.endpointSeparator(task.sourceType, sourceDesk)) }
                val includes = parseSyncFilterPatterns(task.includeRaw)
                return Result.success(SyncSourceSnapshot(
                    files = if (syncReservedPath(relative) || syncFilterMatches(relative, sourceRoot.name, filters) ||
                        includes.isNotEmpty() && !syncFilterMatches(relative, sourceRoot.name, includes)) emptyList()
                    else listOf(SyncSourceFileEntry(relative, sourceRoot)), directories = emptyList(),
                ))
            }
            val matcher = loadSyncIgnoreMatcher(task, sourceDesk, sourceRoot).getOrElse { return Result.failure(it) }
            if (task.destinations.isNotEmpty() && task.sourceType == SyncEndpointType.Network && !sourceRoot.isSymbolicLinkKnown) {
                return Result.failure(IllegalStateException(AppStrings.network_untrusted_symbolic_link_metadata))
            }
            return scanSyncDirectory(sourceRoot, task.copy(filterRaw = filters.joinToString(";")),
                listChildren = { directory -> endpointFiles.listChildren(task.sourceType, task.sourceRef, sourceDesk, directory.path, directory) },
                separator = endpointFiles.endpointSeparator(task.sourceType, sourceDesk),
                allowDuplicateSourceEntries = task.mode == SyncMode.Update && task.destinations.isEmpty(),
                ignored = { path, directory -> matcher?.matchesRelativeOperationSkip(
                    if (relativePrefix.isEmpty()) path else path.removePrefix(relativePrefix).trimStart('/'), directory) == true },
                relativePrefix = relativePrefix,
                directoryAliases = task.selectedSources.filter { it.isDirectory }.mapTo(mutableSetOf()) { it.name },
            ).map { scan ->
                val all = scan.entries.map { it.key to it.value } + scan.duplicateEntries
                val files = all.filter { !it.second.isDirectory }.map { SyncSourceFileEntry(it.first, it.second) }.sortedBy { it.relativePath }
                val directories = all.filter { it.second.isDirectory }.map { it.first }.filter { directory ->
                    task.includeEmptyDirectories && task.includeRaw.isBlank() || directory in scan.nonEmptyDirectories
                }.sortedBy { it.length }
                SyncSourceSnapshot(files, directories, scan.protectedPaths, matcher)
            }
        }

        internal suspend fun collectTargetSnapshot(
            task: SyncTask,
            targetDesk: DiskBase,
            targetRootPath: String,
            matcher: ResolvedIgnoreMatcher? = null,
            ignored: (String, Boolean) -> Boolean = { path, directory -> matcher?.matchesRelativeOperationSkip(path, directory) == true },
            listedDirectory: FileSimpleInfo? = null,
        ): Result<SyncTargetSnapshot> {
            val separator = endpointFiles.endpointSeparator(task.targetType, targetDesk)
            val root = endpointFiles.readPathInfo(task.targetType, task.targetRef, targetDesk, targetRootPath, targetLocation(task))
                .getOrElse { return Result.failure(it) } ?: listedDirectory
            if (root?.isDirectory != true) {
                val siblings = if (root != null && task.conflictPolicy == SyncConflictPolicy.Rename)
                    endpointFiles.listChildren(task.targetType, task.targetRef, targetDesk, parentPathOf(targetRootPath, separator), targetLocation(task))
                        .getOrElse { return Result.failure(it) } else emptyList()
                val occupied = siblings + listOfNotNull(root)
                return Result.success(SyncTargetSnapshot(root, mutableMapOf(),
                    occupied.filter { !it.isDirectory }.associateBy { it.path }.toMutableMap(),
                    occupied.filter { it.isDirectory }.mapTo(mutableSetOf()) { it.path },
                    occupied.mapTo(mutableSetOf()) { it.path }))
            }
            return scanSyncDirectory(root, task,
                listChildren = { directory -> endpointFiles.listChildren(task.targetType, task.targetRef, targetDesk, directory.path, directory) },
                separator = separator,
                ignored = ignored,
                directoryAliases = task.selectedSources.filter { it.isDirectory }.mapTo(mutableSetOf()) { it.name },
            ).map { scan ->
                val files = scan.entries.filterValues { !it.isDirectory }
                SyncTargetSnapshot(root, files.toMutableMap(), scan.occupied.values.filter { !it.isDirectory }
                    .associateBy { normalizePathForCompare(it.path, separator) }.toMutableMap(),
                    (scan.occupied.values.filter { it.isDirectory }.map { normalizePathForCompare(it.path, separator) } + root.path).toMutableSet(),
                    (scan.occupied.values.map { normalizePathForCompare(it.path, separator) } + root.path).toMutableSet(), scan.protectedPaths)
            }
        }

        internal suspend fun executeDirectorySync(
            task: SyncTask,
            sourceDesk: DiskBase,
            targetDesk: DiskBase,
            sourceSnapshot: SyncSourceSnapshot,
            operationTask: Task,
            recoverPendingWrite: Boolean = false,
            modifiedTimeState: SyncModifiedTimeState = SyncModifiedTimeState(),
            frozen: SyncUpdateTarget? = null,
            guard: SyncUpdateGuard? = null,
            details: SyncRunDetailTarget? = null,
        ): SyncExecutionSummary {
            val executionTask = if (frozen == null) task else task.copy(targetPath = frozen.plan.root.path,
                targetStorageReference = frozen.endpoint.root.storageReference)
            val targetSeparator = endpointFiles.endpointSeparator(executionTask.targetType, targetDesk)
            var targetRootPath = normalizePathForCompare(executionTask.targetPath, targetSeparator)

            val snapshotResult = frozen?.snapshot?.let { Result.success(it.copy(filesByRelative = it.filesByRelative.toMutableMap(),
                filesByAbsolute = it.filesByAbsolute.toMutableMap(), directoryPaths = it.directoryPaths.toMutableSet(), occupiedPaths = it.occupiedPaths.toMutableSet())) }
                ?: collectTargetSnapshot(task, targetDesk, targetRootPath, sourceSnapshot.ignoreMatcher)
            if (snapshotResult.isFailure) {
                val error = snapshotResult.exceptionOrNull()?.message ?: AppStrings.ui_failed_read_target_path
                details?.let { it.run.add(SyncRunItem(targetRootPath, false, sanitizeSyncMessage(error), participant = it.participant)) }
                return SyncExecutionSummary(
                    status = SyncRunStatus.Failure,
                    successCount = 0,
                    failureCount = 1,
                    message = sanitizeSyncMessage(error),
                    items = listOf(SyncRunItem(path = targetRootPath, success = false, message = sanitizeSyncMessage(error))),
                )
            }

            val targetSnapshot = snapshotResult.getOrThrow()
            val root = frozen?.plan?.root ?: planUpdateRoot(task, targetSnapshot, targetSeparator, targetRootPath)
            targetRootPath = root.path
            val items = mutableListOf<SyncRunItem>()
            if (root.action == SyncUpdateRootAction.Skip) {
                val msg = AppStrings.ui_target_path_file_has_been_skipped_according_policy
                appendRunItem(items, targetRootPath, true, msg)
                details?.let { it.run.add(SyncRunItem(targetRootPath, true, msg, kind = SyncRunItemKind.Skip, participant = it.participant)) }
                return SyncExecutionSummary(SyncRunStatus.Success, 1, 0, msg, items)
            }
            if (frozen == null) for ((path, file) in targetSnapshot.filesByAbsolute.toMap()) {
                targetSnapshot.filesByAbsolute[path] = syncComparisonTargetFile(targetDesk, file)
            }
            val plan = frozen?.plan ?: planSync(task, sourceSnapshot, targetSnapshot, targetSeparator, targetRootPath, recoverPendingWrite, root)
            try { guard?.beforeRoot() } catch (changed: SyncPlannedStateChangedException) {
                val message = sanitizeSyncMessage(changed.message)
                val skipped = maxOf(1, plan.items.size)
                details?.let { target ->
                    target.run.add(plan.items.map { SyncRunItem(it.targetPath, true, message, kind = SyncRunItemKind.Skip, participant = target.participant) }
                        .ifEmpty { listOf(SyncRunItem(targetRootPath, true, message, kind = SyncRunItemKind.Skip, participant = target.participant)) })
                }
                return SyncExecutionSummary(SyncRunStatus.Success, skipped, 0, message,
                    listOf(SyncRunItem(targetRootPath, true, message)), skippedCount = skipped)
            }
            if (root.action == SyncUpdateRootAction.ReplaceFile) {
                val original = checkNotNull(root.expected)
                val rootAbsolute = normalizePathForCompare(original.path, targetSeparator)
                val deleted = endpointFiles.deletePath(executionTask.targetType, targetDesk, rootAbsolute, false, original)
                if (deleted.isFailure || !deleted.getOrDefault(false)) {
                    val msg = sanitizeSyncMessage(deleted.exceptionOrNull()?.message ?: AppStrings.ui_unable_delete_target_conflict_file)
                    appendRunItem(items, rootAbsolute, false, msg)
                    details?.let { it.run.add(SyncRunItem(rootAbsolute, false, msg, kind = SyncRunItemKind.Overwrite, participant = it.participant)) }
                    return SyncExecutionSummary(SyncRunStatus.Failure, 0, 1, msg, items)
                }
                targetSnapshot.filesByAbsolute.remove(rootAbsolute)
                targetSnapshot.occupiedPaths.remove(rootAbsolute)
                appendRunItem(items, rootAbsolute, true, AppStrings.ui_conflicting_files_removed)
                details?.let { it.run.add(SyncRunItem(rootAbsolute, true, AppStrings.ui_conflicting_files_removed, kind = SyncRunItemKind.Overwrite, participant = it.participant)) }
            }

            val ensureRootResult = if (!plan.ensureRoot) Result.success(true) else endpointFiles.ensureDirectoryExists(
                type = executionTask.targetType,
                ref = executionTask.targetRef,
                desk = targetDesk,
                path = targetRootPath,
                knownDirectories = targetSnapshot.directoryPaths,
                occupiedPaths = targetSnapshot.occupiedPaths,
                location = targetLocation(executionTask),
            )
            if (ensureRootResult.isFailure) {
                val msg = ensureRootResult.exceptionOrNull()?.message ?: AppStrings.ui_failed_create_target_root_directory
                appendRunItem(items, targetRootPath, false, msg)
                details?.let { it.run.add(SyncRunItem(targetRootPath, false, sanitizeSyncMessage(msg), kind = SyncRunItemKind.CreateDirectory, participant = it.participant)) }
                return SyncExecutionSummary(
                    status = SyncRunStatus.Failure,
                    successCount = 0,
                    failureCount = 1,
                    message = sanitizeSyncMessage(msg),
                    items = items,
                )
            }

            if (plan.ensureRoot) guard?.rootCreated()
            if (plan.ensureRoot && root.expected?.isDirectory != true) details?.let {
                it.run.add(SyncRunItem(targetRootPath, true, AppStrings.ui_synchronization_completed, kind = SyncRunItemKind.CreateDirectory, participant = it.participant))
            }
            return executePlan(executionTask, sourceDesk, targetDesk, operationTask, plan.items, targetSnapshot, modifiedTimeState, guard, details)
        }

        private suspend fun executePlan(
            task: SyncTask,
            sourceDesk: DiskBase,
            targetDesk: DiskBase,
            operationTask: Task,
            plan: List<SyncPlanItem>,
            targetSnapshot: SyncTargetSnapshot,
            modifiedTimeState: SyncModifiedTimeState = SyncModifiedTimeState(),
            guard: SyncUpdateGuard? = null,
            details: SyncRunDetailTarget? = null,
        ): SyncExecutionSummary {
            val resultMutex = Mutex()
            val items = mutableListOf<SyncRunItem>()
            val failures = mutableListOf<SyncFailureItem>()
            var failureCount = 0
            var copied = 0
            var renamed = 0
            var directories = 0
            var unchanged = 0
            var skipped = 0
            var bytes = 0L
            var progress = 0
            var canceled = false
            val separator = endpointFiles.endpointSeparator(task.targetType, targetDesk)
            val failedDirectories = mutableSetOf<String>()
            val skippedMessages = setOf(AppStrings.ui_conflict_exists_skipped, AppStrings.sync_protected_path_skipped, AppStrings.sync_selected_root_type_conflict)
            taskState.putValue(operationTask, "progressMax", plan.size.toString())

            suspend fun record(item: SyncPlanItem, error: Throwable? = null, skippedMessage: String? = null) = resultMutex.withLock {
                if (error != null) {
                    failureCount++
                    val message = sanitizeSyncMessage(error.message ?: AppStrings.ui_sync_failed)
                    if (failures.size < 10_000) failures.add(SyncFailureItem(item.relativePath, item.action == SyncPlanAction.CreateDirectory, message))
                } else {
                    when (item.action) {
                        SyncPlanAction.Copy -> { copied++; bytes += item.source?.size?.coerceAtLeast(0) ?: 0 }
                        SyncPlanAction.RenameCopy -> { renamed++; bytes += item.source?.size?.coerceAtLeast(0) ?: 0 }
                        SyncPlanAction.CreateDirectory -> directories++
                        SyncPlanAction.Unchanged -> unchanged++
                        SyncPlanAction.Skip -> skipped++
                    }
                    if (item.action != SyncPlanAction.Unchanged) {
                        if (items.size == RUN_ITEM_LIMIT && item.action != SyncPlanAction.Skip) {
                            val skippedIndex = items.indexOfLast { it.message in skippedMessages }
                            if (skippedIndex >= 0) items.removeAt(skippedIndex)
                        }
                        appendRunItem(items, item.targetPath, true,
                            if (item.action == SyncPlanAction.Skip) skippedMessage ?: when (item.skipReason) {
                                SyncPlanSkipReason.Conflict -> AppStrings.ui_conflict_exists_skipped
                                SyncPlanSkipReason.Protected -> AppStrings.sync_protected_path_skipped
                                SyncPlanSkipReason.SelectedTypeConflict -> AppStrings.sync_selected_root_type_conflict
                            } else AppStrings.ui_synchronization_completed,
                            item.source)
                    }
                }
                details?.record(item, error == null, if (error != null) sanitizeSyncMessage(error.message ?: AppStrings.ui_sync_failed)
                    else skippedMessage ?: if (item.action == SyncPlanAction.Skip) when (item.skipReason) {
                        SyncPlanSkipReason.Conflict -> AppStrings.ui_conflict_exists_skipped
                        SyncPlanSkipReason.Protected -> AppStrings.sync_protected_path_skipped
                        SyncPlanSkipReason.SelectedTypeConflict -> AppStrings.sync_selected_root_type_conflict
                    } else AppStrings.ui_synchronization_completed)
                progress++
                taskState.putValue(operationTask, "progressCur", progress.toString())
            }

            suspend fun preserveModifiedTime(item: SyncPlanItem) = modifiedTimeState.mutex.withLock {
                val source = item.source ?: return@withLock
                if (modifiedTimeState.disabled || source.updatedDate <= 0) return@withLock
                val applied = try {
                    endpointFiles.setModifiedTime(task.targetType, targetDesk, item.targetPath, source.updatedDate)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { LogKit.w(sanitizeSyncMessage(error.message)); return@withLock }
                if (!applied.getOrDefault(false)) {
                    applied.exceptionOrNull()?.let { LogKit.w(sanitizeSyncMessage(it.message)) }
                    return@withLock
                }
                if (!modifiedTimeState.checked) {
                    modifiedTimeState.checked = true
                    val actual = try {
                        endpointFiles.readPathInfo(task.targetType, task.targetRef, targetDesk, item.targetPath, targetLocation(task)).getOrNull()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (actual == null || actual.updatedDate <= 0 || kotlin.math.abs(actual.updatedDate - source.updatedDate) > 2_000) {
                        modifiedTimeState.disabled = true
                    }
                }
            }

            val started = nowMillis()
            try {
                for (item in plan.filter { it.action == SyncPlanAction.CreateDirectory }) withSyncConditionPath {
                    ensureOperationRunning(operationTask.key)
                    beforeSyncConditionFile()
                    try {
                        guard?.before(item)
                        endpointFiles.ensureDirectoryExists(task.targetType, task.targetRef, targetDesk, item.targetPath,
                            targetSnapshot.directoryPaths, targetSnapshot.occupiedPaths, targetLocation(task)).getOrThrow()
                        guard?.completed(item)
                        record(item)
                    } catch (changed: SyncPlannedStateChangedException) {
                        failedDirectories.add(item.targetPath); record(item.copy(action = SyncPlanAction.Skip), skippedMessage = changed.message)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failedDirectories.add(item.targetPath); record(item, error) }
                }
                val files = plan.filter { it.action != SyncPlanAction.CreateDirectory }
                val groups = files.groupBy { it.targetPath }.values.toList()
                val tuning = syncCopyOperationTuning(listOf(sourceDesk, targetDesk), task.parallelism)
                processItemsAdaptive(groups, tuning.config(),
                    ensureRunning = { ensureOperationRunning(operationTask.key) },
                    dynamicMaxParallelismProvider = tuning::currentMaxParallelism,
                    onActiveParallelismChanged = { taskState.putRuntimeParallelCount(operationTask, it) },
                ) { group ->
                    if (group.all { it.action == SyncPlanAction.Unchanged || it.action == SyncPlanAction.Skip }) reportOperationSkipped()
                    for (item in group) withSyncConditionPath {
                        ensureOperationRunning(operationTask.key)
                        beforeSyncConditionFile()
                        try {
                            if (item.action == SyncPlanAction.Unchanged || item.action == SyncPlanAction.Skip) { record(item); return@withSyncConditionPath }
                            guard?.before(item)
                            check(failedDirectories.none { item.targetPath == it || syncTargetInsideSourcePath(it, item.targetPath, separator) }) {
                                AppStrings.ui_failed_create_parent_directory
                            }
                            if (item.targetIsDirectory && guard != null) guard.removeDirectory(item)
                            else if (item.targetIsDirectory) {
                                check(endpointFiles.deletePath(task.targetType, targetDesk, item.targetPath, true, targetLocation(task)).getOrThrow()) {
                                    AppStrings.ui_failed_delete_conflicting_directory
                                }
                            }
                            val source = requireNotNull(item.source)
                            var attempted = false
                            val result = copySyncFileWithRetry(
                                scope, task.retryCount, task.timeoutSeconds * 1_000L,
                                lastProgressAt = { taskState.lastRuntimeProgressAt(operationTask.key, item.targetPath) },
                                ensureRunning = { ensureOperationRunning(operationTask.key) },
                                stopBlockedIo = { (targetDesk as? NetworkAccess)?.disconnect() },
                                cleanup = {
                                    if (guard == null && !item.targetExisted) endpointFiles.deletePath(task.targetType, targetDesk, item.targetPath, false, targetLocation(task))
                                },
                                isUncertainFailure = { task.destinations.isNotEmpty() && it.message.orEmpty().contains("uncertain", ignoreCase = true) },
                            ) {
                                try { guard?.before(item) } catch (changed: SyncPlannedStateChangedException) {
                                    if (attempted) throw IllegalStateException(changed.message, changed) else throw changed
                                }
                                attempted = true
                                copyOnce(task, operationTask, sourceDesk, targetDesk, source, item.targetPath)
                            }
                            check(result.getOrThrow()) { AppStrings.ui_copy_failed }
                            preserveModifiedTime(item)
                            guard?.completed(item)
                            record(item)
                        } catch (changed: SyncPlannedStateChangedException) {
                            reportOperationSkipped(); record(item.copy(action = SyncPlanAction.Skip), skippedMessage = changed.message)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { reportOperationFailure(); record(item, error) }
                    }
                }
            } catch (_: CancellationException) { canceled = true }
            val success = copied + renamed + directories + unchanged + skipped
            val status = when {
                canceled -> SyncRunStatus.Canceled
                failureCount == 0 -> SyncRunStatus.Success
                success > 0 -> SyncRunStatus.PartialSuccess
                else -> SyncRunStatus.Failure
            }
            val message = when {
                canceled -> AppStrings.ui_sync_canceled
                failureCount > 0 -> if (success > 0) AppStrings.ui_partially_successful else AppStrings.ui_sync_failed
                plan.any { it.skipReason == SyncPlanSkipReason.SelectedTypeConflict } -> AppStrings.sync_selected_root_type_conflict
                copied + renamed + directories == 0 -> AppStrings.ui_no_changes
                else -> AppStrings.ui_synchronization_successful
            }
            return SyncExecutionSummary(status, success, failureCount,
                if (modifiedTimeState.disabled) "$message (${AppStrings.sync_metadata_time_not_preserved})" else message,
                items.sortedBy { if (it.message in skippedMessages) 1 else 0 }.take(RUN_ITEM_LIMIT),
                copiedCount = copied, renamedCount = renamed, createdDirectoryCount = directories,
                unchangedCount = unchanged, skippedCount = skipped, transferredBytes = bytes,
                transferDurationMillis = (nowMillis() - started).coerceAtLeast(0), failedItems = failures)
        }

        internal suspend fun executeSingleFileSync(
            task: SyncTask,
            sourceDesk: DiskBase,
            targetDesk: DiskBase,
            sourceFile: FileSimpleInfo,
            operationTask: Task,
            details: SyncRunDetailTarget? = null,
        ): SyncExecutionSummary {
            val separator = endpointFiles.endpointSeparator(task.targetType, targetDesk)
            val root = normalizePathForCompare(task.targetPath, separator)
            val rootInfo = endpointFiles.readPathInfo(task.targetType, task.targetRef, targetDesk, root, targetLocation(task)).getOrThrow()
            val target = if (rootInfo?.isDirectory == true) joinPath(root, sourceFile.name, separator) else root
            val parent = parentPathOf(target, separator)
            val existing = endpointFiles.readPathInfo(task.targetType, task.targetRef, targetDesk, target, targetLocation(task)).getOrThrow()
            val siblings = endpointFiles.listChildren(task.targetType, task.targetRef, targetDesk, parent, targetLocation(task))
                .getOrElse { if (existing != null) throw it else emptyList() }
            val snapshot = SyncTargetSnapshot(null, mutableMapOf(), mutableMapOf(), mutableSetOf(), mutableSetOf())
            for (file in siblings + listOfNotNull(existing)) {
                snapshot.occupiedPaths.add(file.path)
                if (file.isDirectory) snapshot.directoryPaths.add(file.path)
                else snapshot.filesByAbsolute[file.path] = syncComparisonTargetFile(targetDesk, file)
            }
            val plan = planSync(task, sourceFile, snapshot, target, separator)
            if (plan.ensureRoot) {
                endpointFiles.ensureDirectoryExists(task.targetType, task.targetRef, targetDesk, parent,
                    snapshot.directoryPaths, snapshot.occupiedPaths, targetLocation(task)).getOrThrow()
            }
            return executePlan(task, sourceDesk, targetDesk, operationTask, plan.items, snapshot, details = details)
        }

        private suspend fun executeTask(
            task: SyncTask,
            trigger: String,
            operationTask: Task,
            retrySources: List<FileSimpleInfo>? = null,
            retryFailures: List<SyncFailureItem>? = null,
            failedItems: MutableList<SyncFailureItem> = mutableListOf(),
        ): SyncRunRecord {
            val startedAt = maxOf(nowMillis(), recordsFor(task.id).maxOfOrNull { it.runId }?.plus(1) ?: 0)
            val details = SyncRunDetails(task, startedAt, localStore)
            val record = executeTaskWithDetails(task, trigger, operationTask, retrySources, retryFailures, failedItems,
                startedAt, SyncRunDetailTarget(details, "target-0"))
            return details.finish(record.copy(policySnapshot = task))
        }

        private suspend fun executeTaskWithDetails(
            task: SyncTask,
            trigger: String,
            operationTask: Task,
            retrySources: List<FileSimpleInfo>?,
            retryFailures: List<SyncFailureItem>?,
            failedItems: MutableList<SyncFailureItem>,
            startedAt: Long,
            details: SyncRunDetailTarget,
        ): SyncRunRecord {

            val sourceDesk = resolveEndpoint(task.sourceType, task.sourceRef)
                ?: return SyncRunRecord(
                    runId = startedAt,
                    taskId = task.id,
                    trigger = trigger,
                    startedAt = startedAt,
                    endedAt = nowMillis(),
                    status = SyncRunStatus.Failure,
                    totalCount = 1,
                    successCount = 0,
                    failureCount = 1,
                    message = AppStrings.ui_source_endpoint_unavailable,
                    items = listOf(SyncRunItem(task.sourcePath, false, AppStrings.ui_source_endpoint_unavailable)),
                )

            val targetDesk = resolveEndpoint(task.targetType, task.targetRef)
                ?: return SyncRunRecord(
                    runId = startedAt,
                    taskId = task.id,
                    trigger = trigger,
                    startedAt = startedAt,
                    endedAt = nowMillis(),
                    status = SyncRunStatus.Failure,
                    totalCount = 1,
                    successCount = 0,
                    failureCount = 1,
                    message = AppStrings.ui_target_endpoint_unavailable,
                    items = listOf(SyncRunItem(task.targetPath, false, AppStrings.ui_target_endpoint_unavailable)),
                )

            return try {
                ensureOperationRunning(operationTask.key)
                for ((desk, write) in listOf(sourceDesk to false, targetDesk to true)) {
                    if (desk is Network) {
                        val permissions = desk.syncReadWrite().getOrThrow()
                        check(if (write) permissions.second else permissions.first) {
                            if (write) AppStrings.ui_target_endpoint_does_not_support_writing else AppStrings.ui_source_endpoint_does_not_support_reading
                        }
                    }
                }

                val sourceInfoResult = endpointFiles.readPathInfo(
                    type = task.sourceType,
                    ref = task.sourceRef,
                    desk = sourceDesk,
                    path = task.sourcePath,
                    location = sourceLocation(task),
                )
                if (sourceInfoResult.isFailure) {
                    val message = sourceInfoResult.exceptionOrNull()?.message ?: AppStrings.ui_failed_read_source_path
                    return SyncRunRecord(
                        runId = startedAt,
                        taskId = task.id,
                        trigger = trigger,
                        startedAt = startedAt,
                        endedAt = nowMillis(),
                        status = SyncRunStatus.Failure,
                        totalCount = 1,
                        successCount = 0,
                        failureCount = 1,
                        message = sanitizeSyncMessage(message),
                        items = listOf(
                            SyncRunItem(
                                path = task.sourcePath,
                                success = false,
                                message = sanitizeSyncMessage(message),
                            )
                        ),
                    )
                }

                if (sourceInfoResult.getOrNull() == null && !retryFailures.isNullOrEmpty()) {
                    details.run.add(retryFailures.map { SyncRunItem(it.relativePath, true, AppStrings.ui_source_path_does_not_exist,
                        kind = SyncRunItemKind.Skip, participant = details.participant) })
                    return SyncRunRecord(
                        runId = startedAt, taskId = task.id, trigger = trigger,
                        startedAt = startedAt, endedAt = nowMillis(), status = SyncRunStatus.Success,
                        totalCount = retryFailures.size, successCount = retryFailures.size, failureCount = 0,
                        skippedCount = retryFailures.size, message = AppStrings.ui_source_path_does_not_exist,
                        items = retryFailures.take(RUN_ITEM_LIMIT).map {
                            SyncRunItem(it.relativePath, true, AppStrings.ui_source_path_does_not_exist)
                        },
                    )
                }
                val sourceRoot = sourceInfoResult.getOrNull() ?: return SyncRunRecord(
                    runId = startedAt,
                    taskId = task.id,
                    trigger = trigger,
                    startedAt = startedAt,
                    endedAt = nowMillis(),
                    status = SyncRunStatus.Failure,
                    totalCount = 1,
                    successCount = 0,
                    failureCount = 1,
                    message = AppStrings.ui_source_path_does_not_exist,
                    items = listOf(SyncRunItem(path = task.sourcePath, success = false, message = AppStrings.ui_source_path_does_not_exist)),
                )
                if (sourceRoot.isDirectory && task.sourceType == task.targetType && task.sourceRef == task.targetRef) {
                    val separator = endpointFiles.endpointSeparator(task.sourceType, sourceDesk)
                    if (syncTargetInsideSourcePath(task.sourcePath, task.targetPath, separator)) {
                        return SyncRunRecord(
                            runId = startedAt,
                            taskId = task.id,
                            trigger = trigger,
                            startedAt = startedAt,
                            endedAt = nowMillis(),
                            status = SyncRunStatus.Failure,
                            totalCount = 1,
                            successCount = 0,
                            failureCount = 1,
                            message = AppStrings.ui_target_path_cannot_within_source_directory,
                            items = listOf(
                                SyncRunItem(
                                    path = task.targetPath,
                                    success = false,
                                    message = AppStrings.ui_target_path_cannot_within_source_directory,
                                )
                            ),
                        )
                    }
                }

                val filters = parseSyncFilterPatterns(task.filterRaw)
                val sourceSnapshotResult = collectSourceSnapshot(
                    task = task,
                    sourceDesk = sourceDesk,
                    sourceRoot = sourceRoot,
                    filters = filters,
                )
                if (sourceSnapshotResult.isFailure) {
                    val message = sourceSnapshotResult.exceptionOrNull()?.message ?: AppStrings.ui_failed_read_source_directory
                    return SyncRunRecord(
                        runId = startedAt,
                        taskId = task.id,
                        trigger = trigger,
                        startedAt = startedAt,
                        endedAt = nowMillis(),
                        status = SyncRunStatus.Failure,
                        totalCount = 1,
                        successCount = 0,
                        failureCount = 1,
                        message = sanitizeSyncMessage(message),
                        items = listOf(
                            SyncRunItem(
                                path = task.sourcePath,
                                success = false,
                                message = sanitizeSyncMessage(message),
                            )
                        ),
                    )
                }

                val scanned = sourceSnapshotResult.getOrThrow()
                val sourceSnapshot = when {
                    retryFailures != null -> {
                        val relativePaths = retryFailures.map { it.relativePath }.toSet()
                        scanned.copy(files = scanned.files.filter { it.relativePath in relativePaths },
                            directories = scanned.directories.filter { directory ->
                                directory in relativePaths || relativePaths.any { it.startsWith("$directory/") }
                            })
                    }
                    retrySources != null -> {
                        val keys = retrySources.map { it.fileOperationKey() }.toSet()
                        val selected = scanned.files.filter { it.file.fileOperationKey() in keys }
                        scanned.copy(files = selected, directories = scanned.directories.filter { dir -> selected.any { it.relativePath.startsWith("$dir/") } })
                    }
                    else -> scanned
                }

                val summary = if (sourceRoot.isDirectory) {
                    executeDirectorySync(
                        task = task,
                        sourceDesk = sourceDesk,
                        targetDesk = targetDesk,
                        sourceSnapshot = sourceSnapshot,
                        operationTask = operationTask,
                        details = details,
                    )
                } else if (sourceSnapshot.files.isEmpty()) {
                    SyncExecutionSummary(SyncRunStatus.Success, 0, 0, AppStrings.ui_no_changes, emptyList())
                } else {
                    val single = sourceSnapshot.files.first().file
                    executeSingleFileSync(
                        task = task,
                        sourceDesk = sourceDesk,
                        targetDesk = targetDesk,
                        sourceFile = single,
                        operationTask = operationTask,
                        details = details,
                    )
                }

                failedItems.addAll(summary.failedItems)
                val missingRetryItems = retryFailures.orEmpty().filter { failure ->
                    if (failure.isDirectory) failure.relativePath !in scanned.directories
                    else scanned.files.none { it.relativePath == failure.relativePath }
                }
                details.run.add(missingRetryItems.map { SyncRunItem(it.relativePath, true, AppStrings.ui_source_path_does_not_exist,
                    kind = SyncRunItemKind.Skip, participant = details.participant) })
                SyncRunRecord(
                    runId = startedAt,
                    taskId = task.id,
                    trigger = trigger,
                    startedAt = startedAt,
                    endedAt = nowMillis(),
                    status = summary.status,
                    totalCount = summary.successCount + summary.failureCount + missingRetryItems.size,
                    successCount = summary.successCount + missingRetryItems.size,
                    failureCount = summary.failureCount,
                    message = summary.message,
                    items = (summary.items + missingRetryItems.map {
                        SyncRunItem(it.relativePath, true, AppStrings.ui_source_path_does_not_exist)
                    }).take(RUN_ITEM_LIMIT),
                    copiedCount = summary.copiedCount, renamedCount = summary.renamedCount,
                    createdDirectoryCount = summary.createdDirectoryCount, unchangedCount = summary.unchangedCount,
                    skippedCount = summary.skippedCount + missingRetryItems.size, transferredBytes = summary.transferredBytes,
                    transferDurationMillis = summary.transferDurationMillis,
                    unsavedFailureCount = (summary.failureCount - summary.failedItems.size).coerceAtLeast(0),
                )
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    details.run.add(SyncRunItem(task.targetPath, false, AppStrings.ui_sync_canceled, participant = details.participant))
                }
                SyncRunRecord(
                    runId = startedAt,
                    taskId = task.id,
                    trigger = trigger,
                    startedAt = startedAt,
                    endedAt = nowMillis(),
                    status = SyncRunStatus.Canceled,
                    totalCount = 1,
                    successCount = 0,
                    failureCount = 1,
                    message = AppStrings.ui_sync_canceled,
                    items = retrySources?.map { SyncRunItem(it.path, false, AppStrings.ui_sync_canceled, it) }
                        ?: listOf(SyncRunItem(task.targetPath, false, AppStrings.ui_sync_canceled)),
                )
            } catch (error: Throwable) {
                val message = sanitizeSyncMessage(error.message ?: AppStrings.ui_synchronization_exception)
                details.run.add(SyncRunItem(task.targetPath, false, message, participant = details.participant))
                SyncRunRecord(
                    runId = startedAt,
                    taskId = task.id,
                    trigger = trigger,
                    startedAt = startedAt,
                    endedAt = nowMillis(),
                    status = SyncRunStatus.Failure,
                    totalCount = 1,
                    successCount = 0,
                    failureCount = 1,
                    message = message,
                    items = retrySources?.map { SyncRunItem(it.path, false, message, it) }
                        ?: listOf(SyncRunItem(task.targetPath, false, message)),
                )
            }
        }

        internal suspend fun onTaskFinished(
            task: SyncTask,
            record: SyncRunRecord,
            failedItems: List<SyncFailureItem>? = null,
            updateRuntime: Boolean = true,
        ) {
            val conditionRun = currentCoroutineContext()[SyncConditionRun]
            val conditionsLost = conditionRun?.interrupted.orEmpty()
            val classifiedRecord = if (record.resultCounts.isNotEmpty()) record else
                SyncRunDetails(task, record.runId, localStore).finish(record.copy(policySnapshot = record.policySnapshot ?: task))
            val systemStop = currentCoroutineContext()[SyncSystemRun]?.stopped() ?: SyncSystemStop.None
            val finishedRecord = when {
                systemStop == SyncSystemStop.Interrupted -> classifiedRecord.copy(status = SyncRunStatus.Interrupted, message = AppStrings.sync_interrupted)
                systemStop == SyncSystemStop.UserCanceled -> classifiedRecord.copy(status = SyncRunStatus.Canceled, message = AppStrings.ui_sync_canceled)
                conditionsLost.isNotEmpty() -> classifiedRecord.copy(status = SyncRunStatus.Canceled, message = syncConditionsLostMessage(conditionsLost))
                else -> classifiedRecord
            }
            val persisted = withContext(Dispatchers.Default) {
                taskStore.withExclusiveAccess {
                    val current = taskStore.loadTasks().firstOrNull { it.id == task.id } ?: return@withExclusiveAccess null
                    if (current.ownerDeviceId != task.ownerDeviceId || !isTaskOwnedByCurrentDevice(current)) return@withExclusiveAccess null
                    val savedRecord = if (failedItems == null) finishedRecord else finishedRecord.copy(
                        unsavedFailureCount = finishedRecord.unsavedFailureCount + taskStore.saveFailedItems(task.id, record.runId, failedItems))
                    taskStore.appendRun(savedRecord)
                    val updated = if (updateRuntime) taskStore.saveTask(current.copy(
                        lastRunAt = savedRecord.endedAt, lastStatus = savedRecord.status, lastMessage = savedRecord.message,
                        nextRunAt = if (systemStop == SyncSystemStop.Interrupted || conditionsLost.isNotEmpty() && systemStop != SyncSystemStop.UserCanceled)
                            current.nextRunAt else nextSyncRunAfterFinish(current, task, savedRecord, nowMillis()), updatedAt = nowMillis(),
                    )) else current
                    updated to savedRecord
                }
            } ?: return
            conditionRun?.published = true
            val retainedRunIds = withContext(Dispatchers.Default) { taskStore.loadRuns(task.id).take(RUN_HISTORY_LIMIT).mapTo(mutableSetOf()) { it.runId } }
            if (retainedRunIds.isNotEmpty()) try { localStore?.pruneRunDetails(task.id, task.ownerDeviceId, retainedRunIds) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { LogKit.w(sanitizeSyncMessage(error.message)) }
            withContext(Dispatchers.Main) {
                val index = tasks.indexOfFirst { it.id == task.id }
                if (index < 0 || tasks[index].ownerDeviceId != task.ownerDeviceId) return@withContext
                val (savedTask, savedRecord) = persisted
                runHistory[task.id] = (listOf(savedRecord) + runHistory[task.id].orEmpty().filter { it.runId != savedRecord.runId }).take(RUN_HISTORY_LIMIT)
                if (updateRuntime) tasks[index] = tasks[index].copy(
                    lastRunAt = savedTask.lastRunAt, lastStatus = savedTask.lastStatus, lastMessage = savedTask.lastMessage,
                    nextRunAt = savedTask.nextRunAt, updatedAt = savedTask.updatedAt,
                )
                notifyChanged()
            }
            refreshSyncAttention(task.id)
            val (savedTask, savedRecord) = persisted
            if (savedRecord.status !in listOf(SyncRunStatus.Success, SyncRunStatus.PartialSuccess, SyncRunStatus.Failure,
                    SyncRunStatus.Canceled, SyncRunStatus.Interrupted)) return
            val notificationTask = getTask(task.id)?.takeIf { it.ownerDeviceId == savedTask.ownerDeviceId && isTaskOwnedByCurrentDevice(it) } ?: return
            val pendingCount = taskAttention[task.id]?.pendingDeletionCount ?: 0
            val event = when {
                pendingCount > 0 -> SyncNotificationEvent.PendingDeletions
                savedRecord.status in listOf(SyncRunStatus.Failure, SyncRunStatus.PartialSuccess, SyncRunStatus.Interrupted) -> SyncNotificationEvent.Failed
                else -> SyncNotificationEvent.Completed
            }
            postSyncNotification(notificationTask, event, savedRecord, pendingCount)
        }

        internal fun resolveEndpoint(type: SyncEndpointType, ref: String): DiskBase? {
            return when (type) {
                SyncEndpointType.Local -> Local()
                SyncEndpointType.Device -> deviceState.devices.firstOrNull { item -> item.id == ref }
                SyncEndpointType.Network -> networkState.networks.firstOrNull { item -> item.buildProtocolId() == ref && item.supportsSync }
            }
        }

        internal fun targetEndpointMissingError(task: SyncTask): String? {
            return when (task.targetType) {
                SyncEndpointType.Local -> null
                SyncEndpointType.Device -> {
                    val exists = deviceState.devices.any { item -> item.id == task.targetRef } ||
                        deviceState.socketDevices.any { item -> item.id == task.targetRef }
                    syncTargetEndpointMissingError(task.targetType, exists)
                }

                SyncEndpointType.Network -> {
                    val exists = networkState.entries.any { item -> item.network.buildProtocolId() == task.targetRef }
                    syncTargetEndpointMissingError(task.targetType, exists)
                }
            }
        }

        internal suspend fun connectTargetEndpointIfNeeded(task: SyncTask): String? {
            return when (task.targetType) {
                SyncEndpointType.Local -> null
                SyncEndpointType.Device -> connectTargetDeviceIfNeeded(task)
                SyncEndpointType.Network -> connectTargetNetworkIfNeeded(task)
            }
        }

        private suspend fun connectTargetDeviceIfNeeded(task: SyncTask): String? {
        val result = connectSyncDeviceEndpoint(
            isConnected = { resolveEndpoint(SyncEndpointType.Device, task.targetRef) != null },
            discoveredDevice = { deviceState.socketDevices.firstOrNull { it.id == task.targetRef } },
            connect = { deviceState.connect(it) },
        )
        return if (result.getOrDefault(false)) null else syncConnectionFailureMessage(SyncEndpointType.Device, result.exceptionOrNull())
        }

        private suspend fun connectTargetNetworkIfNeeded(task: SyncTask): String? {
            if (networkState.connectedEntries.any { item -> item.network.buildProtocolId() == task.targetRef }) return null
            val entry = networkState.entries.firstOrNull { item -> item.network.buildProtocolId() == task.targetRef }
                ?: return syncTargetEndpointMissingError(SyncEndpointType.Network, exists = false)
            val rootPath = entry.network.getRootPaths().firstOrNull()?.path ?: entry.network.pathSeparator

            val listResult = try {
                entry.network.getList(rootPath)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Throwable) {
                return syncConnectionFailureMessage(SyncEndpointType.Network, error)
            }
            if (listResult.isFailure) {
                return syncConnectionFailureMessage(SyncEndpointType.Network, listResult.exceptionOrNull())
            }

            withContext(Dispatchers.Main) {
                networkState.connectEntry(entry)
            }
            return null
        }

        private fun syncConnectionFailureMessage(
            type: SyncEndpointType,
            error: Throwable?,
        ): String {
            val label = when (type) {
                SyncEndpointType.Local -> AppStrings.ui_target_endpoint
                SyncEndpointType.Device -> AppStrings.ui_target_device
                SyncEndpointType.Network -> AppStrings.ui_target_network_disk
            }
            val detail = sanitizeSyncMessage(error?.message).trim()
            return if (detail.isBlank()) {
                AppStrings.ui_arg0_connection_failed.format(arg0 = label)
            } else {
                AppStrings.ui_arg0_connection_failed_arg1.format(arg0 = label, arg1 = detail)
            }
        }

        private fun startScheduler() {
            scope.launch {
                while (true) {
                    delay(1_000.milliseconds)
                    RuntimeServiceFailures.observe("sync") { checkScheduledRuns(); eventTriggers.check(); conditionRuns.drain() }
                }
            }
        }

        internal suspend fun checkScheduledRuns(now: Long = nowMillis(), timeZone: TimeZone = currentSyncTimeZone()) {
            if (timeZone != scheduleTimeZone) {
                lifecycleMutex.withLock {
                    for (task in snapshotTasks()) {
                        if (!isTaskOwnedByCurrentDevice(task) || task.scheduleType !in listOf(SyncScheduleType.Daily, SyncScheduleType.Weekly)) continue
                        val saved = withContext(Dispatchers.Default) {
                            taskStore.updateOwnedSyncTask(task) { current ->
                                if (sameSyncSchedule(task, current) && current.nextRunAt == task.nextRunAt)
                                    current.copy(nextRunAt = computeNextRun(current, now, timeZone)) else current
                            }
                        } ?: continue
                        localStore?.saveScheduleTimeZone(saved.id, syncDefinitionFingerprint(saved), timeZone)
                        withContext(Dispatchers.Main) {
                            val current = getTask(task.id)
                            if (current != null && sameSyncSchedule(task, current) && current.nextRunAt == task.nextRunAt)
                                replaceOrAddTask(current.copy(nextRunAt = saved.nextRunAt))
                        }
                    }
                    scheduleTimeZone = timeZone
                    notifyChanged()
                }
            }
            for (dueTask in snapshotTasks()) {
                if (!isTaskOwnedByCurrentDevice(dueTask) || hasActiveSyncRun(dueTask.id) ||
                    !matchesSyncDueRun(dueTask, dueTask, dueTask.nextRunAt, now)) continue
                if (requiresSyncPreview(dueTask)) {
                    deferUnconfirmedSyncRun(dueTask)
                    continue
                }
                if (systemScheduler.managesTimedRuns) systemScheduler.refresh()
                else enqueueRun(dueTask) { RuntimeServiceFailures.observe("sync") {
                    runTaskInternal(dueTask, trigger = "schedule", scheduledAt = dueTask.nextRunAt)
                } }
            }
        }

        private fun replaceOrAddTask(task: SyncTask) {
            val index = tasks.indexOfFirst { item -> item.id == task.id }
            if (index >= 0) {
                tasks[index] = task
            } else {
                tasks.add(task)
            }
        }

        private fun saveTaskPatchAsync(expected: SyncTask, patch: (SyncTask) -> SyncTask) {
            scope.launch { taskStore.updateOwnedSyncTask(expected, patch) }
        }

        private suspend fun deleteTaskFromStore(taskId: Long): Unit = lifecycleMutex.withLock {
            if (taskId <= 0L) return
            withContext(Dispatchers.Default) {
                taskStore.deleteTask(taskId)
            }
            localStore?.deleteTaskState(taskId)
            clearSyncNotification(taskId)
            withContext(Dispatchers.Main) { tasks.removeAll { it.id == taskId } }
        }

        private fun deleteTaskFromStoreAsync(taskId: Long) {
            scope.launch {
                deleteTaskFromStore(taskId)
            }
        }

        private fun deleteRunsFromStoreAsync(taskId: Long) {
            scope.launch {
                val owner = getTask(taskId)?.ownerDeviceId ?: return@launch
                val deletedRunIds = withContext(Dispatchers.Default) {
                    taskStore.loadRuns(taskId).filter { it.status !in listOf(SyncRunStatus.Running, SyncRunStatus.Queued) }
                        .mapTo(mutableSetOf()) { it.runId }.also { taskStore.deleteRuns(taskId) }
                }
                localStore?.deleteRunDetails(taskId, owner, deletedRunIds)
            }
        }

        private suspend fun loadTasks(recoverInterrupted: Boolean = false) {
            val (reloaded, reloadedRunHistory) = withContext(Dispatchers.Default) {
              taskStore.withExclusiveAccess {
                val reloaded = taskStore.loadTasks().map { loaded ->
                    val identity = deviceIdentity()
                    val task = if (loaded.ownerDeviceId.isBlank() && identity.id.isNotBlank()) {
                        taskStore.saveTask(loaded.copy(ownerDeviceId = identity.id, ownerDeviceName = identity.name))
                    } else loaded
                    val interrupted = recoverInterrupted && task.lastStatus in listOf(SyncRunStatus.Running, SyncRunStatus.Queued)
                    val nextRunAt = nextSyncRunOnLoad(task, nowMillis(), interrupted, isTaskOwnedByCurrentDevice(task))
                    if (interrupted) {
                        taskStore.saveTask(task.copy(
                            lastStatus = if (systemScheduler.managesTimedRuns) SyncRunStatus.Interrupted else SyncRunStatus.Failure,
                            lastMessage = AppStrings.ui_task_interrupted_after_application_exits_task_can_continued,
                            nextRunAt = nextRunAt,
                        ))
                    } else if (nextRunAt != task.nextRunAt) taskStore.saveTask(task.copy(nextRunAt = nextRunAt)) else task
                }
                val reloadedRunHistory = reloaded.associate { task ->
                    task.id to taskStore.loadRuns(task.id).map { record ->
                        if (recoverInterrupted && record.targets.any { it.status == SyncRunStatus.Running || it.status == SyncRunStatus.Queued }) {
                            val targets = record.targets.map { target ->
                                if (target.status == SyncRunStatus.Running || target.status == SyncRunStatus.Queued) {
                                    target.copy(status = if (systemScheduler.managesTimedRuns) SyncRunStatus.Interrupted else SyncRunStatus.Failure,
                                        message = AppStrings.ui_task_interrupted_after_application_exits_task_can_continued)
                                } else target
                            }
                            record.copy(targets = targets, status = if (systemScheduler.managesTimedRuns) SyncRunStatus.Interrupted else storageGroupStatus(targets), endedAt = nowMillis())
                                .also(taskStore::appendRun)
                        } else record
                    }
                }
                reloaded to reloadedRunHistory
            }
        }

        localStore?.reconcileTasks(reloaded)
        val timeZone = currentSyncTimeZone()
        val scheduled = reloaded.map { task ->
            if (!isTaskOwnedByCurrentDevice(task) || task.scheduleType !in listOf(SyncScheduleType.Daily, SyncScheduleType.Weekly)) return@map task
            val previousZone = localStore?.localState(task.id)?.scheduleTimeZone.orEmpty()
            val next = nextSyncRunOnLoad(task, nowMillis(), interrupted = false, owned = true,
                previousTimeZone = previousZone, timeZone = timeZone)
            val saved = if (next == task.nextRunAt) task else withContext(Dispatchers.Default) {
                taskStore.updateOwnedSyncTask(task) { current ->
                    if (sameSyncSchedule(task, current) && current.nextRunAt == task.nextRunAt) current.copy(nextRunAt = next) else current
                }
            } ?: task
            localStore?.saveScheduleTimeZone(saved.id, syncDefinitionFingerprint(saved), timeZone)
            saved
        }
        scheduleTimeZone = timeZone
        withContext(Dispatchers.Main) {
            tasks.clear()
            tasks.addAll(scheduled)
            runHistory.clear()
            reloadedRunHistory.forEach { (taskId, records) ->
                if (records.isNotEmpty()) {
                    runHistory[taskId] = records
                }
            }
            notifyChanged()
        }
        conditionRuns.drain()
        scheduled.forEach { refreshSyncAttention(it.id) }
    }

    suspend fun reloadTasksFromStore() = lifecycleMutex.withLock {
        val before = snapshotTasks()
        loadTasks()
        for (old in before) {
            val current = getTask(old.id)
            if (current == null || current.ownerDeviceId != old.ownerDeviceId || !isTaskOwnedByCurrentDevice(current)) {
                runQueue.cancel(old.id)
                runningOperationTasks[old.id]?.let { taskState.requestCancel(it) }
                controlStorageRun(old.id, StorageGroupControl.Cancel)
            }
        }
    }

}

internal fun SyncTaskStore.updateOwnedSyncTask(expected: SyncTask, patch: (SyncTask) -> SyncTask): SyncTask? =
    withExclusiveAccess {
        val persisted = loadTasks().firstOrNull { it.id == expected.id } ?: return@withExclusiveAccess null
        if (persisted.ownerDeviceId != expected.ownerDeviceId) return@withExclusiveAccess null
        saveTask(patch(persisted))
    }

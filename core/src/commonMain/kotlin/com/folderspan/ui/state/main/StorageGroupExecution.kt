package com.folderspan.ui.state.main

import com.folderspan.service.sync.beforeSyncConditionFile
import com.folderspan.service.sync.withSyncConditionPath

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.ChunkReadableNetworkAccess
import com.folderspan.extensions.fileOperationKey
import com.folderspan.service.network.StorageContentPlatform
import com.folderspan.ignore.ResolvedIgnoreMatcher
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.SensitiveFileAccessPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import strings.AppStrings

internal suspend fun SyncState.publishStorageRun(task: SyncTask, record: SyncRunRecord) {
    if (!withContext(Dispatchers.Default) { taskStoreForStorageRun(task, record) }) return
    withContext(Dispatchers.Main) {
        val current = getTask(task.id) ?: return@withContext
        if (current.ownerDeviceId != task.ownerDeviceId || !isTaskOwnedByCurrentDevice(current)) return@withContext
        runHistory[task.id] = (listOf(record) + runHistory[task.id].orEmpty().filter { it.runId != record.runId })
            .take(RUN_HISTORY_LIMIT)
    }
}

/** One staged file at a time bounds disk usage; every destination consumes the same verified bytes. */
internal suspend fun SyncState.executeStorageGroup(
    task: SyncTask,
    trigger: String,
    retry: SyncRunRecord?,
    selectedTargetKeys: Set<Long>,
    selectedFiles: Map<Long, Set<String>> = emptyMap(),
    frozen: SyncPreparedRun? = null,
) {
    val started = maxOf(nowMillis(), recordsFor(task.id).maxOfOrNull { it.runId }?.plus(1) ?: 0L)
    val groupTask = createOperationTask(task)
    val details = SyncRunDetails(task, started, localStore)
    val digests = retry?.sourceDigests.orEmpty().toMutableMap()
    var manifest = retry?.sourceManifest.orEmpty()
    var directories = retry?.sourceDirectories.orEmpty()
    var targets = retry?.targets.orEmpty().toMutableList()
    val sourceDesk = resolveEndpoint(task.sourceType, task.sourceRef)
    val operations = mutableListOf<Task>()
    val retryItems = mutableMapOf<Long, Set<String>>()
    val modifiedTimeStates = mutableMapOf<Long, SyncModifiedTimeState>()
    val preparedTargets = frozen?.update?.targets.orEmpty().associateBy { it.destination }.toMutableMap()
    var sourceSnapshot = SyncSourceSnapshot(manifest.map { SyncSourceFileEntry(it.relativePath, it.file) }, directories)
    val guards = mutableMapOf<Long, SyncUpdateGuard>()

    fun record() = SyncRunRecord(
        runId = started, taskId = task.id, trigger = trigger, startedAt = started,
        endedAt = if (targets.any { it.status in listOf(SyncRunStatus.Queued, SyncRunStatus.Running) }) 0 else nowMillis(),
        status = storageGroupStatus(targets),
        totalCount = maxOf(targets.sumOf { it.successCount + it.failureCount },
            targets.size * (manifest.size + if (task.includeEmptyDirectories) directories.size else 0)),
        successCount = targets.sumOf { it.successCount }, failureCount = targets.sumOf { it.failureCount },
        items = targets.flatMap { it.items },
        policySnapshot = task, targets = targets.toList(), sourceManifest = manifest,
        sourceDirectories = directories, sourceDigests = digests.toMap(),
    )

    suspend fun publish() {
        val snapshot = record()
        taskState.putValues(groupTask, listOf(
            TASK_PROGRESS_MAX_VALUE_KEY to snapshot.totalCount.toString(),
            TASK_PROGRESS_CUR_VALUE_KEY to (snapshot.successCount + snapshot.failureCount).toString(),
        ))
        publishStorageRun(task, snapshot)
    }

    fun finishTargetIfComplete(index: Int) {
        val target = targets[index]
        if (target.status != SyncRunStatus.Running) return
        val completed = target.items.mapNotNull { it.source?.fileOperationKey() }.toSet()
        if (manifest.any { it.file.fileOperationKey() !in completed }) return
        val status = when {
            target.failureCount == 0 -> SyncRunStatus.Success
            target.successCount > 0 -> SyncRunStatus.PartialSuccess
            else -> SyncRunStatus.Failure
        }
        targets[index] = target.copy(status = status)
        operations.firstOrNull { it.key == target.operationTaskKey }?.let {
            finalizeOperationTask(it, record().copy(status = status, message = target.message))
        }
    }

    suspend fun failTarget(index: Int, error: Throwable, source: FileSimpleInfo? = null, stop: Boolean = true) {
        val target = targets[index]
        val message = sanitizeSyncMessage(error.message).ifBlank { AppStrings.ui_sync_failed }
        val item = SyncRunItem(source?.path ?: target.destination.path, false, message, source)
        targets[index] = target.copy(
            status = if (error is CancellationException) SyncRunStatus.Canceled else if (stop) SyncRunStatus.Failure else SyncRunStatus.Running,
            message = message, failureCount = target.failureCount + 1, items = target.items + item,
            uncertain = target.uncertain || message.contains("uncertain", ignoreCase = true),
        )
        withContext(NonCancellable) { details.add(item.copy(participant = "target-$index")) }
    }

    fun recordOutcome(index: Int, entry: StorageSourceEntry, copied: SyncExecutionSummary, separator: String) {
        val target = targets[index]
        val outcomes = copied.items.map { it.copy(source = entry.file) } + copied.failedItems.map {
            SyncRunItem(joinPath(target.destination.path, it.relativePath, separator), false, it.message, entry.file)
        } + if (copied.unchangedCount > 0) listOf(SyncRunItem(
            joinPath(target.destination.path, entry.relativePath, separator), true, AppStrings.ui_no_changes_skipped, entry.file)) else emptyList()
        targets[index] = target.copy(items = target.items + outcomes,
            successCount = target.successCount + copied.successCount, failureCount = target.failureCount + copied.failureCount,
            message = copied.message,
            uncertain = (target.items + outcomes).any { !it.success && it.message.contains("uncertain", ignoreCase = true) })
    }

    suspend fun publishProgress(index: Int) {
        finishTargetIfComplete(index)
        val progress = targets[index]
        val operation = operations.single { it.key == progress.operationTaskKey }
        taskState.putValues(operation, listOf(
            TASK_PROGRESS_MAX_VALUE_KEY to (manifest.size + if (task.includeEmptyDirectories) directories.size else 0).toString(),
            TASK_PROGRESS_CUR_VALUE_KEY to (progress.successCount + progress.failureCount).toString()))
        publish()
    }

    suspend fun checkSelectedTypes(prepared: SyncUpdateTarget, items: List<SyncPlanItem>) {
        if (frozen != null) return
        val endpoint = prepared.endpoint
        val separator = endpointFiles.endpointSeparator(endpoint.type, endpoint.desk)
        for (selected in task.selectedSources) if (items.any { item ->
            item.action in listOf(SyncPlanAction.Copy, SyncPlanAction.RenameCopy, SyncPlanAction.CreateDirectory) &&
                (item.relativePath == selected.name || item.relativePath.startsWith(selected.name + "/"))
        }) {
            val path = joinPath(prepared.plan.root.path, selected.name, separator)
            val current = endpointFiles.readPathInfo(endpoint.type, endpoint.ref, endpoint.desk, path,
                endpoint.root.withCopy(path = path)).getOrThrow()
            check(current == null || !current.isSymbolicLink && current.isDirectory == selected.isDirectory) {
                AppStrings.sync_selected_root_type_conflict
            }
        }
    }

    try {
        taskState.beginTaskOutputs(groupTask)
        taskState.addOrUpdate(groupTask)
        activeStorageGroups[task.id] = listOf(groupTask)
        check(sourceDesk != null) { AppStrings.ui_source_endpoint_unavailable }
        val root = frozen?.endpoints?.firstOrNull { it.id == "source" }?.root ?: endpointFiles.readPathInfo(
            task.sourceType, task.sourceRef, sourceDesk, task.sourcePath, sourceLocation(task)).getOrThrow()
            ?: error(AppStrings.ui_source_path_does_not_exist)
        val selections = task.resolvedSelectedSources().ifEmpty { if (root.isDirectory) emptyList() else listOf(root) }
        val sourceScope = SyncSourceScope(root, selections, endpointFiles.endpointSeparator(task.sourceType, sourceDesk))
        val matchers = mutableMapOf<String, ResolvedIgnoreMatcher?>()
        if (frozen != null) {
            check(retry == null && frozen.task == task && syncPreviewFingerprint(task) == frozen.previewFingerprint)
            val prepared = checkNotNull(frozen.update)
            sourceSnapshot = prepared.source
            manifest = prepared.source.files.map { StorageSourceEntry(it.relativePath, it.file) }
            directories = prepared.source.directories
            targets = prepared.targets.map { StorageTargetRun(it.destination, createOperationTask(task).key) }.toMutableList()
        } else if (retry == null) {
            val roots = task.resolvedSelectedSources().ifEmpty { listOf(root) }
            val scanned = roots.map { selected ->
                for (destination in task.destinations) {
                    validateTask(task.name, task.sourceType, task.sourceRef, selected.path,
                        destination.type, destination.ref, destination.path, strictEndpointAvailability = false)
                        ?.let { error(it) }
                }
                val current = endpointFiles.readPathInfo(task.sourceType, task.sourceRef, sourceDesk,
                    selected.path, selected).getOrThrow() ?: error(AppStrings.ui_source_path_does_not_exist)
                check(!current.isSymbolicLink && current.isDirectory == selected.isDirectory) { AppStrings.ui_storage_source_changed }
                if (!current.isDirectory && task.selectedSources.isNotEmpty()) {
                    check(current.size == selected.size &&
                        (selected.updatedDate <= 0 || current.updatedDate == selected.updatedDate) &&
                        current.storageReference == selected.storageReference) { AppStrings.ui_storage_source_changed }
                }
                val prefix = if (task.selectedSources.isNotEmpty()) selected.name else ""
                val snapshot = collectSourceSnapshot(task, sourceDesk, current, parseSyncFilterPatterns(task.filterRaw), prefix).getOrThrow()
                if (current.isDirectory) matchers[prefix] = snapshot.ignoreMatcher
                snapshot
            }
            manifest = scanned.flatMap { it.files }.map { StorageSourceEntry(it.relativePath, it.file) }
            directories = scanned.flatMap { it.directories }
            sourceSnapshot = SyncSourceSnapshot(scanned.flatMap { it.files }, directories, scanned.flatMapTo(mutableSetOf()) { it.protectedPaths })
            require(manifest.map { it.relativePath }.distinct().size == manifest.size &&
                directories.distinct().size == directories.size) { AppStrings.ui_storage_duplicate_source }
            requireDistinctStorageDestinations(task.destinations)
            val metadata = task.destinations.map { networkState.refreshStorageMetadata(it) }
            val recommendations = recommendStorageTargets(metadata, manifest.map { it.file.size }, task.preferredEntryIds)
            val destinations = if (task.selectRecommended) {
                recommendations.firstOrNull { it.eligible }?.let { listOf(it.metadata.destination) }.orEmpty()
            } else task.destinations
            check(destinations.isNotEmpty()) { AppStrings.ui_storage_no_candidate }
            targets = destinations.map { StorageTargetRun(it, createOperationTask(task).key) }.toMutableList()
        } else {
            require(retry.policySnapshot == task) { AppStrings.ui_storage_source_changed }
            val protection = mutableSetOf<String>()
            for (selected in selections.ifEmpty { listOf(root) }) if (selected.isDirectory) {
                val fresh = endpointFiles.readPathInfo(task.sourceType, task.sourceRef, sourceDesk, selected.path, selected).getOrThrow()
                check(fresh?.isDirectory == true && !fresh.isSymbolicLink) { AppStrings.ui_storage_source_changed }
                val prefix = if (task.selectedSources.isEmpty()) "" else selected.name
                val scan = collectSourceSnapshot(task, sourceDesk, fresh, parseSyncFilterPatterns(task.filterRaw), prefix).getOrThrow()
                matchers[prefix] = scan.ignoreMatcher
                protection.addAll(scan.protectedPaths)
            }
            sourceSnapshot = sourceSnapshot.copy(protectedPaths = protection)
            targets.forEachIndexed { index, target ->
                if (target.operationTaskKey in selectedTargetKeys && target.status != SyncRunStatus.Success) {
                    val chosenFiles = selectedFiles[target.operationTaskKey]
                    val failedItems = target.items.filter { !it.success }
                    val retryKeys = failedItems.mapNotNull { it.source?.fileOperationKey() }.toSet()
                    require(chosenFiles == null || chosenFiles.isNotEmpty() && chosenFiles.all { it in retryKeys }) {
                        AppStrings.ui_retry_failed_items
                    }
                    retryItems[index.toLong()] = chosenFiles ?: retryKeys
                    val retained = target.items.filter {
                        it.success || (chosenFiles != null && it.source?.fileOperationKey() !in chosenFiles)
                    }
                    targets[index] = target.copy(
                        status = SyncRunStatus.Queued, message = "",
                        items = retained, failureCount = retained.count { !it.success },
                    )
                }
            }
        }
        targets.forEachIndexed { index, target ->
            if (target.status != SyncRunStatus.Queued) return@forEachIndexed
            val operation = createOperationTask(task).copy(
                key = target.operationTaskKey,
                values = mapOf("storageGroupTaskId" to task.id.toString(), "storageGroupRunId" to started.toString(),
                    "sourcePath" to task.sourcePath, "targetPath" to target.destination.path),
            )
            taskState.clearSignals(operation.key)
            taskState.beginTaskOutputs(operation)
            taskState.addOrUpdate(operation)
            operations.add(operation)
            try {
                val network = networkState.validateStorageDestination(target.destination).getOrThrow()
                check(recommendStorageTargets(
                    listOf(networkState.refreshStorageMetadata(target.destination)), manifest.map { it.file.size },
                ).single().eligible) { AppStrings.ui_storage_no_candidate }
                if (target.destination !in preparedTargets) {
                    val raw = collectTargetSnapshot(target.destination.applyTo(task), network, target.destination.path,
                        ignored = { path, directory -> sourceScope.ignored(path, directory, matchers) },
                        listedDirectory = FileSimpleInfo.pathFileSimpleInfo(target.destination.path).withCopy(isDirectory = true,
                            protocol = toProtocol(target.destination.type), protocolId = target.destination.ref,
                            storageReference = target.destination.storageReference)).getOrThrow()
                    check(raw.rootInfo?.isDirectory == true) { AppStrings.ui_target_endpoint_unavailable }
                    val comparison = raw.copy(filesByAbsolute = raw.filesByAbsolute.mapValues { (_, file) ->
                        syncComparisonTargetFile(network, file)
                    }.toMutableMap())
                    val endpoint = SyncRunEndpoint("target-$index", target.destination.type, target.destination.ref, network,
                        checkNotNull(raw.rootInfo))
                    preparedTargets[target.destination] = SyncUpdateTarget(endpoint, target.destination, raw,
                        planSync(task, sourceSnapshot, comparison, network.pathSeparator, target.destination.path,
                            recoverPendingWrite = retry != null && target.uncertain), comparisonSnapshot = comparison)
                }
            } catch (error: Exception) {
                if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                failTarget(index, error)
            }
        }
        activeStorageGroups[task.id] = listOf(groupTask) + operations
        if (frozen == null) {
            val protected = sourceSnapshot.protectedPaths + preparedTargets.values.flatMap { it.snapshot.protectedPaths }
            val conflicts = preparedTargets.values.flatMapTo(mutableSetOf()) {
                syncSelectedTypeConflicts(task, it.snapshot, it.plan.root.path, endpointFiles.endpointSeparator(it.endpoint.type, it.endpoint.desk))
            }
            for ((destination, prepared) in preparedTargets.toMap()) {
                val target = targets.single { it.destination == destination }
                preparedTargets[destination] = prepared.copy(plan = planSync(task, sourceSnapshot.copy(protectedPaths = protected),
                    prepared.comparisonSnapshot, endpointFiles.endpointSeparator(prepared.endpoint.type, prepared.endpoint.desk), destination.path,
                    recoverPendingWrite = retry != null && target.uncertain, root = prepared.plan.root, selectedTypeConflicts = conflicts))
            }
        }
        targets.forEachIndexed { index, target ->
            if (target.status != SyncRunStatus.Queued) return@forEachIndexed
            val operation = operations.single { it.key == target.operationTaskKey }
            try {
                val prepared = preparedTargets.getValue(target.destination)
                val network = prepared.endpoint.desk
                val separator = endpointFiles.endpointSeparator(target.destination.type, network)
                val guard = if (frozen != null) SyncUpdateGuard(this, frozen, prepared, operation)
                    .also { guards[operation.key] = it } else null
                checkSelectedTypes(prepared, prepared.plan.items.filter { it.action == SyncPlanAction.CreateDirectory })
                val structure = executeDirectorySync(target.destination.applyTo(task), sourceDesk, network,
                    SyncSourceSnapshot(emptyList(), directories.filterNot { relative ->
                        target.items.any { it.success && it.source == null &&
                            it.path == joinPath(target.destination.path, relative, separator) }
                    }), operation, modifiedTimeState = modifiedTimeStates.getOrPut(operation.key) { SyncModifiedTimeState() },
                    frozen = prepared.copy(plan = prepared.plan.copy(items = prepared.plan.items.filter { item ->
                        (item.action == SyncPlanAction.CreateDirectory || item.action == SyncPlanAction.Skip && item.source?.isDirectory == true) &&
                            target.items.none { it.success && it.source == null && it.path == item.targetPath }
                    })),
                    guard = guard, details = SyncRunDetailTarget(details, "target-$index"))
                targets[index] = targets[index].copy(
                    status = if (structure.failureCount == 0) SyncRunStatus.Running else SyncRunStatus.Failure,
                    items = targets[index].items + structure.items + structure.failedItems.map {
                        SyncRunItem(joinPath(target.destination.path, it.relativePath, separator), false, it.message)
                    },
                    successCount = targets[index].successCount + structure.successCount,
                    failureCount = targets[index].failureCount + structure.failureCount,
                )
                finishTargetIfComplete(index)
            } catch (error: Exception) { failTarget(index, error) }
        }
        activeStorageGroups[task.id] = listOf(groupTask) + operations
        val filePlans = preparedTargets.mapValues { (_, prepared) -> prepared.plan.items
            .filter { it.source?.isDirectory != true && it.action != SyncPlanAction.CreateDirectory }.groupBy { it.relativePath } }
        publish()

        for (entry in manifest) withSyncConditionPath {
            beforeSyncConditionFile()
            currentCoroutineContext().ensureActive()
            ensureOperationRunning(groupTask.key)
            val needing = targets.indices.filter { index ->
                targets[index].status == SyncRunStatus.Running &&
                    targets[index].items.none { it.success && it.source?.fileOperationKey() == entry.file.fileOperationKey() } &&
                    (retry == null || retryItems[index.toLong()].isNullOrEmpty() ||
                        entry.file.fileOperationKey() in retryItems[index.toLong()].orEmpty())
            }
            if (needing.isEmpty()) return@withSyncConditionPath
            val copying = mutableListOf<Int>()
            for (index in needing) {
                val target = targets[index]
                val prepared = preparedTargets.getValue(target.destination)
                val items = filePlans[target.destination]?.get(entry.relativePath).orEmpty()
                if (items.any { it.action == SyncPlanAction.Copy || it.action == SyncPlanAction.RenameCopy }) copying.add(index)
                else {
                    try {
                        val operation = operations.single { it.key == target.operationTaskKey }
                        val copied = executeDirectorySync(target.destination.applyTo(task), sourceDesk, prepared.endpoint.desk,
                            SyncSourceSnapshot(listOf(SyncSourceFileEntry(entry.relativePath, entry.file)), emptyList()), operation,
                            frozen = prepared.copy(plan = prepared.plan.copy(items = items, ensureRoot = false)), guard = guards[operation.key],
                            details = SyncRunDetailTarget(details, "target-$index", entry.file))
                        recordOutcome(index, entry, copied, endpointFiles.endpointSeparator(target.destination.type, prepared.endpoint.desk))
                    } catch (error: Exception) {
                        if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                        failTarget(index, error, entry.file, stop = false)
                    }
                    publishProgress(index)
                }
            }
            if (copying.isEmpty()) return@withSyncConditionPath
            var stage: String? = null
            try {
                val fresh = endpointFiles.readPathInfo(task.sourceType, task.sourceRef, sourceDesk,
                    entry.file.path, entry.file).getOrThrow() ?: error(AppStrings.ui_source_path_does_not_exist)
                check(fresh.size == entry.file.size && fresh.updatedDate == entry.file.updatedDate &&
                    fresh.storageReference == entry.file.storageReference) { AppStrings.ui_storage_source_changed }
                require(!fresh.isDirectory && !fresh.isSymbolicLink && fresh.size >= 0) { AppStrings.ui_storage_source_changed }
                stage = StorageContentPlatform.createStage(groupTask.key, entry.file.fileOperationKey()).getOrThrow()
                val stageIdentity = if (LocalTaskArtifacts.supported) StorageContentPlatform.stageIdentity(groupTask.key, stage).getOrThrow() else ""
                val stable = stageStorageSource(task, fresh, sourceDesk, stage, groupTask)
                val digest = storageUploadSourceDigest(stable.size, { ensureOperationRunning(groupTask.key) }) { start, end ->
                    FileUtils.readFileRange(FileAccessPermission.Allowed, stable.path, start, end)
                }
                check(digests[entry.relativePath]?.let { it == digest } != false) { AppStrings.ui_storage_source_changed }
                digests[entry.relativePath] = digest
                publish()
                for (index in copying) {
                    val target = targets[index]
                    val operation = operations.single { it.key == target.operationTaskKey }
                    try {
                        ensureOperationRunning(groupTask.key)
                        ensureOperationRunning(operation.key)
                        val network = networkState.validateStorageDestination(target.destination).getOrThrow()
                        val prepared = preparedTargets.getValue(target.destination)
                        checkSelectedTypes(prepared, filePlans[target.destination]?.get(entry.relativePath).orEmpty())
                        if (retry != null && target.uncertain) {
                            val finalPath = joinPath(target.destination.path, entry.relativePath, network.pathSeparator)
                            val artifacts = taskState.loadTaskArtifacts(operation.key).orEmpty()
                                .filter { it.finalPath == finalPath && !it.completed }
                            val checkpoint = artifacts.firstNotNullOfOrNull { artifact ->
                                val destination = stable.copy(
                                    path = artifact.endpoint.path, protocol = artifact.endpoint.protocol,
                                    protocolId = artifact.endpoint.protocolId, storageReference = artifact.endpoint.storageReference,
                                )
                                val key = buildCopyRetryEntry(TaskType.Copy, entry.file, destination).entryKey
                                taskState.loadRuntimeTransferCheckpoint(operation.key, key)
                            } ?: error(AppStrings.ui_storage_uncertain)
                            network.verifyStorageUpload(operation.key, checkpoint).getOrThrow()
                        }
                        val copied = withContext(TaskUploadSourceIdentity(entry.file, stable.path, stageIdentity, groupTask.key)) {
                            executeDirectorySync(
                                task = target.destination.applyTo(task).copy(sourceType = SyncEndpointType.Local,
                                    sourceRef = "", sourceStorageReference = null),
                                sourceDesk = Local(), targetDesk = network,
                                sourceSnapshot = SyncSourceSnapshot(listOf(SyncSourceFileEntry(entry.relativePath, stable)), emptyList()),
                                operationTask = operation,
                                recoverPendingWrite = retry != null && target.uncertain,
                                modifiedTimeState = modifiedTimeStates.getOrPut(operation.key) { SyncModifiedTimeState() },
                                frozen = preparedTargets[target.destination]?.let { frozenTarget ->
                                    frozenTarget.copy(plan = frozenTarget.plan.copy(
                                        root = frozenTarget.plan.root.let { if (it.action == SyncUpdateRootAction.ReplaceFile) it.copy(action = SyncUpdateRootAction.EnsureDirectory) else it },
                                        items = filePlans[target.destination]?.get(entry.relativePath).orEmpty()
                                            .map { it.copy(source = stable) }))
                                }, guard = guards[operation.key], details = SyncRunDetailTarget(details, "target-$index", entry.file),
                            )
                        }
                        val verifiedDigest = storageUploadSourceDigest(stable.size, { ensureOperationRunning(operation.key) }) { start, end ->
                            FileUtils.readFileRange(FileAccessPermission.Allowed, stable.path, start, end)
                        }
                        check(verifiedDigest == digest) { AppStrings.ui_storage_source_changed }
                        recordOutcome(index, entry, copied, network.pathSeparator)
                    } catch (error: Exception) {
                        if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                        failTarget(index, error, entry.file, stop = false)
                    }
                    publishProgress(index)
                }
            } catch (error: Exception) {
                if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                copying.forEach { failTarget(it, error, entry.file, stop = false); finishTargetIfComplete(it) }
                publish()
            } finally {
                withContext(NonCancellable) {
                    stage?.let { FileUtils.deleteFile(FileAccessPermission.Allowed, it).getOrThrow() }
                }
            }
        }
        targets.indices.forEach { finishTargetIfComplete(it) }
    } catch (error: Exception) {
        if (targets.isEmpty()) {
            targets.add(StorageTargetRun(task.destinations.first(), groupTask.key))
        }
        targets.indices.filter { targets[it].status in listOf(SyncRunStatus.Running, SyncRunStatus.Queued) }
            .forEach { failTarget(it, error) }
    } finally {
        withContext(NonCancellable) {
            if (taskState.isTaskCanceledByUser(groupTask.key) || operations.any { taskState.isTaskCanceledByUser(it.key) }) {
                conditionRuns.cancel(task.id)
            }
            val result = details.finish(record())
            try {
                StorageContentPlatform.cleanup(groupTask.key).getOrThrow()
                onTaskFinished(task, result)
                operations.forEach { operation ->
                    val target = targets.single { it.operationTaskKey == operation.key }
                    finalizeOperationTask(operation, result.copy(status = target.status, message = target.message))
                }
                finalizeOperationTask(groupTask, result, currentCoroutineContext()[SyncConditionRun]?.interrupted.orEmpty())
            } finally {
                operations.forEach { operation ->
                    val target = targets.single { it.operationTaskKey == operation.key }
                    taskState.taskOutputsFinished(operation, interrupted = target.status != SyncRunStatus.Success)
                    taskState.clearSignals(operation.key)
                }
                taskState.taskOutputsFinished(groupTask, interrupted = result.status != SyncRunStatus.Success)
                taskState.clearSignals(groupTask.key)
                activeStorageGroups.remove(task.id)
            }
        }
    }
}

internal suspend fun SyncState.stageStorageSource(
    task: SyncTask, source: FileSimpleInfo, sourceDesk: DiskBase,
    path: String, operation: Task,
): FileSimpleInfo {
    beforeSyncConditionFile()
    if (task.sourceType == SyncEndpointType.Local) {
        SensitiveFileAccessPolicy.deniedException(source.path)?.let { throw it }
    }
    var offset = 0L
    suspend fun write(bytes: ByteArray) {
        ensureOperationRunning(operation.key)
        check(bytes.isNotEmpty() && bytes.size <= source.size - offset) { AppStrings.ui_storage_source_changed }
        check(FileUtils.writeBytes(FileAccessPermission.Allowed, path, source.size, bytes, offset).getOrThrow()) {
            AppStrings.ui_storage_stage_failed
        }
        offset += bytes.size
    }
    when (task.sourceType) {
        SyncEndpointType.Local, SyncEndpointType.Device -> {
            while (offset < source.size) {
                val end = minOf(offset + 1024 * 1024, source.size)
                val bytes = if (sourceDesk is Device) sourceDesk.files.readBytes(source.path, offset, end).getOrThrow()
                    else FileUtils.readFileRange(FileAccessPermission.Allowed, source.path, offset, end).getOrThrow()
                check(bytes.size.toLong() == end - offset) { AppStrings.ui_storage_source_changed }
                write(bytes)
            }
        }
        SyncEndpointType.Network -> {
            val reader = sourceDesk as? ChunkReadableNetworkAccess ?: error(AppStrings.ui_storage_stage_failed)
            check(reader.downloadFileByChunks(operation, source) { bytes, _ ->
                write(bytes); Result.success(Unit)
            }.getOrThrow()) { AppStrings.ui_storage_stage_failed }
        }
    }
    check(offset == source.size) { AppStrings.ui_storage_source_changed }
    val after = endpointFiles.readPathInfo(task.sourceType, task.sourceRef, sourceDesk, source.path, source).getOrThrow()
    check(after?.size == source.size && after.updatedDate == source.updatedDate &&
        after.storageReference == source.storageReference) { AppStrings.ui_storage_source_changed }
    return source.copy(path = path, protocol = FileProtocol.Local, protocolId = "", storageReference = null)
}

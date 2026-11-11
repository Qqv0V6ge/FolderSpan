package com.folderspan.ui.state.main

import com.folderspan.service.sync.beforeSyncConditionFile

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.network.Network
import com.folderspan.extensions.fileOperationKey
import com.folderspan.ignore.ResolvedIgnoreMatcher
import com.folderspan.service.network.storageConnectionBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import com.folderspan.utils.currentSyncTimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import strings.AppStrings

internal data class SyncRunEndpoint(
    val id: String,
    val type: SyncEndpointType,
    val ref: String,
    val desk: DiskBase,
    val root: FileSimpleInfo,
    val connectionBinding: String = (desk as? Network)?.let { storageConnectionBinding(it.connectionConfig()) }.orEmpty(),
) {
    val label: String get() = desk.name + " " + fileNameOf(root.path, desk.pathSeparator)
}

internal data class SyncPreparedRun(
    val task: SyncTask,
    val previewFingerprint: String,
    val endpoints: List<SyncRunEndpoint>,
    val sourceScope: SyncSourceScope,
    val preparation: SyncPreparedPlan,
    val localState: SyncTaskLocalState?,
    val update: SyncPreparedUpdate? = null,
    val participatingDestinations: List<StorageDestination> = emptyList(),
)

internal fun SyncPreparedRun.runParticipants(): List<SyncRunParticipant> = endpoints.map { endpoint ->
    SyncRunParticipant(endpoint.id, endpoint.label, endpoint.type, endpoint.ref,
        if (endpoint.id == "source" && task.selectedSources.isNotEmpty()) task.selectedSources.map { it.path } else listOf(endpoint.root.path))
}

internal fun SyncPreparedRun.recoveryLocation(id: String, relative: String, conflict: SyncPlannedOperation? = null): Pair<SyncRecoveryEndpoint, FileSimpleInfo> {
    val endpoint = endpoints.first { it.id == id }
    val root = if (id == "source") sourceScope.artifactRoot(relative, conflict) else endpoint.root
    val location = if (id == "source") sourceScope.location(relative, conflict) else
        root.withCopy(path = joinPath(root.path, relative, endpoint.desk.pathSeparator))
    return SyncRecoveryEndpoint(endpoint.type, endpoint.ref, root.path, root.storageReference, endpoint.connectionBinding) to location
}

internal suspend fun SyncState.prepareSyncRun(task: SyncTask, operation: Task): Result<SyncPreparedRun> =
    if (task.mode == SyncMode.Update) prepareUpdateSyncRun(task, operation) else try {
    check(isTaskOwnedByCurrentDevice(task)) { "Synchronization belongs to another device" }
    val previewFingerprint = syncPreviewFingerprint(task)
    check(getTask(task.id)?.let(::syncPreviewFingerprint) == previewFingerprint) { "Synchronization definition changed" }
    val store = localStore
    refreshFixedSyncTargets(task)
    val local = store?.localState(task.id)
    check(store?.recoveries(task.id).isNullOrEmpty()) { "Synchronization has pending recovery operations" }
    if (task.mode != SyncMode.Update) {
        check(local?.fingerprint == syncDefinitionFingerprint(task)) { "Synchronization local state is unavailable or changed" }
    }
    val configured = task.destinations.ifEmpty { listOf(StorageDestination(task.targetType, task.targetRef, task.targetPath, task.targetStorageReference)) }
    requireDistinctStorageDestinations(configured)
    suspend fun endpoint(id: String, type: SyncEndpointType, ref: String, location: FileSimpleInfo, source: Boolean): SyncRunEndpoint {
        ensureOperationRunning(operation.key)
        val connectionTask = task.copy(targetType = type, targetRef = ref, targetPath = location.path, targetStorageReference = location.storageReference)
        val connectionError = withTimeout(30_000) { targetEndpointMissingError(connectionTask) ?: connectTargetEndpointIfNeeded(connectionTask) }
        check(connectionError == null) { connectionError ?: "Endpoint connection failed" }
        val desk = resolveEndpoint(type, ref) ?: error("Synchronization endpoint is unavailable")
        val root = if (source && task.selectedSources.isNotEmpty()) location else
            endpointFiles.readPathInfo(type, ref, desk, location.path, location).getOrThrow() ?: error("Synchronization root disappeared")
        val permissions = endpointFiles.capabilities(desk).getOrThrow()
        syncModeEndpointError(task.mode, source, root.isDirectory || source && task.selectedSources.isNotEmpty(), permissions)?.let { error(it) }
        if (!source || task.selectedSources.isEmpty()) require(root.isDirectory && !root.isSymbolicLink) { "Synchronization root is not a directory" }
        return SyncRunEndpoint(id, type, ref, desk, root)
    }
    val source = endpoint("source", task.sourceType, task.sourceRef, sourceLocation(task), true)
    val separator = endpointFiles.endpointSeparator(source.type, source.desk)
    val parentListings = mutableMapOf<String, Pair<FileSimpleInfo, List<FileSimpleInfo>>>()
    val resolvedFiles = mutableMapOf<String, FileSimpleInfo?>()
    val selectedSources = task.selectedSources.map { selected ->
        if (source.type != SyncEndpointType.Network || selected.isDirectory || selected.storageReference?.id.isNullOrEmpty()) selected
        else {
            val previous = store?.baselineEntry(task.id, syncDefinitionFingerprint(task), selected.name)
            val (effective, fresh) = resolveSyncSelectedFile(selected, previous, separator,
                task.currentSourceReferences[syncSelectedSourceKey(selected)]) { parent ->
                parentListings.getOrPut(parent.fileOperationKey()) {
                    val current = endpointFiles.readPathInfo(source.type, source.ref, source.desk, parent.path, parent).getOrThrow()
                    check(current?.isDirectory == true && !current.isSymbolicLink) { "Selected file parent is unavailable" }
                    current to endpointFiles.listChildren(source.type, source.ref, source.desk, current.path, current).getOrThrow()
                }
            }
            resolvedFiles[effective.name] = fresh
            effective
        }
    }
    val sourceScope = SyncSourceScope(source.root, selectedSources, separator)
    val roots = selectedSources.ifEmpty { listOf(source.root) }
    val freshRoots = roots.map { selected ->
        val fresh = if (selected.name in resolvedFiles) resolvedFiles[selected.name] else
            endpointFiles.readPathInfo(source.type, source.ref, source.desk, selected.path, selected).getOrThrow()
        if (task.selectedSources.isEmpty()) check(fresh?.isDirectory == true && !fresh.isSymbolicLink) { "Synchronization root is unavailable" }
        if (selected.isDirectory) check(fresh?.isDirectory == true && !fresh.isSymbolicLink) { "Selected directory root is unavailable" }
        if (fresh != null) check(!fresh.isSymbolicLink && (task.selectedSources.isEmpty() || fresh.isDirectory == selected.isDirectory)) {
            "Selected source type changed"
        }
        selected to fresh
    }
    val matchers = mutableMapOf<String, ResolvedIgnoreMatcher?>()
    for ((selected, fresh) in freshRoots) if (fresh?.isDirectory == true) {
        val prefix = if (task.selectedSources.isEmpty()) "" else selected.name
        matchers[prefix] = loadSyncIgnoreMatcher(task, source.desk, fresh).getOrThrow()
    }
    fun ignored(path: String, directory: Boolean): Boolean {
        return sourceScope.ignored(path, directory, matchers)
    }
    val entries = linkedMapOf<String, FileSimpleInfo>()
    val occupied = linkedMapOf<String, FileSimpleInfo>()
    val protected = mutableSetOf<String>()
    val nonEmpty = mutableSetOf<String>()
    val siblingsRead = mutableSetOf<String>()
    val aliases = task.selectedSources.mapTo(mutableSetOf()) { it.name }
    val includes = parseSyncFilterPatterns(task.includeRaw)
    val excludes = parseSyncFilterPatterns(task.filterRaw)
    for ((selected, fresh) in freshRoots) {
        ensureOperationRunning(operation.key)
        val prefix = if (task.selectedSources.isEmpty()) "" else selected.name
        if (fresh?.isDirectory == true) {
            val scan = scanSyncDirectory(fresh, task, { directory ->
                ensureOperationRunning(operation.key)
                beforeSyncConditionFile()
                endpointFiles.listChildren(source.type, source.ref, source.desk, directory.path, directory)
            }, separator, ignored = ::ignored, relativePrefix = prefix, directoryAliases = sourceScope.directoryAliases).getOrThrow()
            entries.putAll(scan.entries); occupied.putAll(scan.occupied)
            protected.addAll(scan.protectedPaths); nonEmpty.addAll(scan.nonEmptyDirectories)
        } else {
            val parent = sourceScope.artifactRoot(prefix)
            if (siblingsRead.add(parent.path)) {
                val siblings = parentListings[parent.fileOperationKey()]?.second
                    ?: endpointFiles.listChildren(source.type, source.ref, source.desk, parent.path, parent).getOrThrow()
                for (sibling in siblings) {
                    require(sibling.name.isNotBlank() && '/' !in sibling.name && '\\' !in sibling.name && sibling.name !in listOf(".", ".."))
                    if (sibling.name !in aliases) occupied[sibling.name] = sibling
                }
            }
            if (fresh != null) {
                occupied[prefix] = fresh
                if (ignored(prefix, false) || syncFilterMatches(prefix, selected.name, excludes) ||
                    includes.isNotEmpty() && !syncFilterMatches(prefix, selected.name, includes)) protected.add(prefix)
                else entries[prefix] = fresh
            }
        }
    }
    val sourceScan = SyncDirectoryScan(entries, occupied, protected, nonEmptyDirectories = nonEmpty)
    val destinations = if (task.requiresFixedSyncTargets()) local?.fixedTargets?.takeIf { it.isNotEmpty() } ?: run {
        val metadata = configured.map { networkState.refreshStorageMetadata(it) }
        listOf(checkNotNull(recommendStorageTargets(metadata, entries.values.filter { !it.isDirectory }.map { it.size }, task.preferredEntryIds)
            .firstOrNull { it.eligible }) { AppStrings.ui_storage_no_candidate }.metadata.destination)
    } else configured
    val targets = destinations.mapIndexed { index, destination ->
        if (task.destinations.isNotEmpty() && destination.type == SyncEndpointType.Network) {
            networkState.validateStorageDestination(destination).getOrThrow()
        }
        for (selected in task.selectedSources.ifEmpty { listOf(source.root) }) {
            validateTask(task.name, source.type, source.ref, selected.path, destination.type, destination.ref, destination.path,
                strictEndpointAvailability = false)?.let { error(it) }
        }
        endpoint("target-$index", destination.type, destination.ref, FileSimpleInfo.pathFileSimpleInfo(destination.path).withCopy(
            protocol = toProtocol(destination.type), protocolId = destination.ref, storageReference = destination.storageReference), false)
    }
    val targetScans = targets.map { target ->
        val scan = scanSyncDirectory(target.root, task, { directory ->
            ensureOperationRunning(operation.key)
            beforeSyncConditionFile()
            endpointFiles.listChildren(target.type, target.ref, target.desk, directory.path, directory)
        }, endpointFiles.endpointSeparator(target.type, target.desk), ignored = ::ignored,
            directoryAliases = sourceScope.directoryAliases).getOrThrow()
        suspend fun logical(files: Map<String, FileSimpleInfo>) = files.mapValues { (_, file) ->
            if (file.isDirectory) file else syncComparisonTargetFile(target.desk, file)
        }
        SyncParticipantScan(target.id, target.label, scan.copy(entries = logical(scan.entries), occupied = logical(scan.occupied)))
    }
    val participants = listOf(SyncParticipantScan(source.id, source.label, sourceScan)) + targetScans
    val endpoints = listOf(source) + targets
    val byId = endpoints.associateBy { it.id }
    val now = Instant.fromEpochMilliseconds(nowMillis()).toLocalDateTime(currentSyncTimeZone())
    val stamp = "${now.date} ${now.hour.toString().padStart(2, '0')}${now.minute.toString().padStart(2, '0')}"
    val preparation = prepareSyncPlan(task, participants, stamp, { offset ->
        checkNotNull(store).baselinePage(task.id, syncDefinitionFingerprint(task), offset)
    }, { id, file, onRead ->
        beforeSyncConditionFile()
        val end = byId.getValue(id)
        readVerifiedSyncEndpointState(endpointFiles, end.type, end.ref, end.desk, file, operation, onRead) {
            ensureOperationRunning(operation.key)
        }
    }, ignored = ::ignored, conflictWord = AppStrings.sync_conflict_copy_word,
        scopeRelativePath = sourceScope::relativeWithinSelection, selectedSourceTypes = sourceScope.selectionTypes).getOrThrow()
    check(getTask(task.id)?.let(::syncPreviewFingerprint) == previewFingerprint && isTaskOwnedByCurrentDevice(task)) {
        "Synchronization definition changed during preparation"
    }
    Result.success(SyncPreparedRun(task, previewFingerprint, endpoints, sourceScope, preparation, local, participatingDestinations = destinations))
} catch (cancelled: CancellationException) { throw cancelled }
catch (error: Exception) { Result.failure(error) }

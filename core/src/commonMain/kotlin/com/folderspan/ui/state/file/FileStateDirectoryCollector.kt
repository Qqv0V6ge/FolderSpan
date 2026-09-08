package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.data.main.share.DeviceBackedShareSession
import com.folderspan.service.operation.TraversalEndpointKind
import com.folderspan.service.operation.TraversalScanProgress
import com.folderspan.service.operation.collectDirectoryEntriesAdaptive
import com.folderspan.service.operation.resolveTraversalParallelism
import com.folderspan.service.operation.resolveTraversalRuntimeMaxParallelism
import com.folderspan.ui.state.main.DeviceEndpointUnavailableException
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.NetworkState
import com.folderspan.ui.state.main.ShareSessionUnavailableException
import com.folderspan.ui.state.main.TaskEndpointUnavailableException
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.PathUtils
import com.folderspan.utils.toFileListingIssueMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class FileStateDirectoryCollector(
    private val currentDesk: () -> DiskBase,
    private val deviceState: DeviceState,
    private val networkState: NetworkState,
) {
    suspend fun collectDirectoryEntries(
        root: FileSimpleInfo,
        ensureRunning: suspend () -> Unit = {},
        requestBatchId: String? = null,
        onScanProgress: suspend (TraversalScanProgress) -> Unit = {},
        maxParallelismLimit: Int? = null,
        retainDiscoveredEntries: Boolean = true,
        deduplicateDirectories: Boolean = true,
        depthFirst: Boolean = false,
        rejectSymbolicLinkEntries: Boolean = false,
        onRejectedEntry: suspend (FileSimpleInfo, Throwable) -> Unit = { _, _ -> },
        continueOnDirectoryError: Boolean = false,
        onScanIssues: suspend (Int, List<FilePropertyScanIssue>) -> Unit = { _, _ -> },
        onEntriesDiscovered: suspend (List<FileSimpleInfo>) -> Unit = {},
    ): List<FileSimpleInfo> {
        if (!root.isDirectory) return emptyList()
        val reportDirectoryError: suspend (FileSimpleInfo, Throwable) -> Unit = { directory, error ->
            onScanIssues(
                1,
                listOf(
                    FilePropertyScanIssue(
                        path = directory.path,
                        message = error.toFileListingIssueMessage(),
                    ),
                ),
            )
        }
        return when (root.protocol) {
            FileProtocol.Local -> {
                if (PathUtils.isSymbolicLink(FileAccessPermission.Allowed, root.path)) return emptyList()
                val listingIssuesByDirectory = mutableMapOf<String, Pair<Int, List<FilePropertyScanIssue>>>()
                val listingIssuesMutex = Mutex()
                collectDirectoryEntriesAdaptive(
                    root = root,
                    config = resolveTraversalParallelism(TraversalEndpointKind.Local),
                    pathSeparator = PathUtils.getPathSeparator(),
                    ensureRunning = ensureRunning,
                    onCancel = { cancelTaskScanRequests(root, requestBatchId) },
                    onScanProgress = onScanProgress,
                    onDirectoryListed = { directory ->
                        val issueBatch = listingIssuesMutex.withLock {
                            listingIssuesByDirectory.remove(directory.path)
                        }
                        if (issueBatch != null) {
                            onScanIssues(issueBatch.first, issueBatch.second)
                        }
                    },
                    onEntriesDiscovered = onEntriesDiscovered,
                    dynamicMaxParallelismProvider = {
                        resolveTraversalRuntimeMaxParallelism(TraversalEndpointKind.Local)
                    },
                    maxParallelismLimit = maxParallelismLimit,
                    retainDiscoveredEntries = retainDiscoveredEntries,
                    deduplicateDirectories = deduplicateDirectories,
                    depthFirst = depthFirst,
                    rejectSymbolicLinkEntries = rejectSymbolicLinkEntries,
                    onRejectedEntry = onRejectedEntry,
                    continueOnDirectoryError = continueOnDirectoryError,
                    onDirectoryError = reportDirectoryError,
                ) { directory ->
                    val entriesResult: Result<List<FileSimpleInfo>> = if (continueOnDirectoryError) {
                        val listing = PathUtils.getFileAndFolderWithIssues(
                            FileAccessPermission.Allowed,
                            directory.path,
                        )
                            .getOrElse { error ->
                                return@collectDirectoryEntriesAdaptive Result.failure(error)
                            }
                        if (listing.issueCount > 0) {
                            val issues = listing.issues.map { issue ->
                                FilePropertyScanIssue(path = issue.path, message = issue.message)
                            }
                            listingIssuesMutex.withLock {
                                listingIssuesByDirectory[directory.path] = listing.issueCount to issues
                            }
                        }
                        Result.success(listing.entries)
                    } else {
                        PathUtils.getFileAndFolder(FileAccessPermission.Allowed, directory.path)
                    }
                    entriesResult.map { entries ->
                        entries
                            .asSequence()
                            .map { entry ->
                                entry.withCopy(
                                    protocol = FileProtocol.Local,
                                    protocolId = "",
                                    isSymbolicLink = entry.isSymbolicLinkKnown && entry.isSymbolicLink ||
                                        (!entry.isSymbolicLinkKnown && PathUtils.isSymbolicLink(
                                            FileAccessPermission.Allowed,
                                            entry.path,
                                        )),
                                    isSymbolicLinkKnown = true,
                                )
                            }
                            .toList()
                    }
                }
            }

            FileProtocol.Device -> {
                ensureRunning()
                val device = resolveDevice(currentDesk(), root.protocolId)
                    ?: throw DeviceEndpointUnavailableException(AppStrings.message_task_source_device_disconnected)
                fun currentTransferStatus() = device.paths
                    .transferStatus()
                    .clamped()
                    .takeIf { status -> status.sampledAtMillis > 0L }
                fun currentTransferStatusBusy(): Boolean {
                    val status = currentTransferStatus() ?: return false
                    return status.busy ||
                        (status.maxParallelRequests > 0 && status.activeRequests >= status.maxParallelRequests)
                }
                val transferStatus = currentTransferStatus()
                collectDirectoryEntriesAdaptive(
                    root = root,
                    config = resolveTraversalParallelism(
                        endpointKind = TraversalEndpointKind.Device,
                        remoteRecommendedParallelism = transferStatus?.recommendedParallelRequests,
                        remoteBusy = currentTransferStatusBusy(),
                    ),
                    pathSeparator = device.pathSeparator.ifBlank { PathUtils.getPathSeparator() },
                    ensureRunning = ensureRunning,
                    onCancel = { cancelTaskScanRequests(root, requestBatchId) },
                    onScanProgress = onScanProgress,
                    onEntriesDiscovered = onEntriesDiscovered,
                    dynamicMaxParallelismProvider = {
                        val latestStatus = currentTransferStatus()
                        resolveTraversalRuntimeMaxParallelism(
                            endpointKind = TraversalEndpointKind.Device,
                            remoteRecommendedParallelism = latestStatus?.recommendedParallelRequests,
                            remoteBusy = currentTransferStatusBusy(),
                        )
                    },
                    maxParallelismLimit = maxParallelismLimit,
                    retainDiscoveredEntries = retainDiscoveredEntries,
                    deduplicateDirectories = deduplicateDirectories,
                    depthFirst = depthFirst,
                    requireKnownSymbolicLinkMetadata = true,
                    rejectSymbolicLinkEntries = rejectSymbolicLinkEntries,
                    onRejectedEntry = onRejectedEntry,
                    continueOnDirectoryError = continueOnDirectoryError,
                    onDirectoryError = reportDirectoryError,
                ) { directory ->
                    device.paths.getList(directory.path, batchId = requestBatchId).map { entries ->
                        entries.map { entry -> entry.withCopy(protocol = FileProtocol.Device, protocolId = root.protocolId) }
                    }
                }
            }

            FileProtocol.Share -> {
                ensureRunning()
                val share = deviceState.shares.firstOrNull { item -> item.id == root.protocolId }
                    ?: throw ShareSessionUnavailableException()
                val sharePathClient = (share.session as? DeviceBackedShareSession)?.devicePathClient
                fun currentShareTransferStatus() = sharePathClient
                    ?.transferStatus()
                    ?.clamped()
                    ?.takeIf { status -> status.sampledAtMillis > 0L }
                fun currentShareTransferStatusBusy(): Boolean {
                    val status = currentShareTransferStatus() ?: return false
                    return status.busy ||
                        (status.maxParallelRequests > 0 && status.activeRequests >= status.maxParallelRequests)
                }
                val shareTransferStatus = currentShareTransferStatus()
                val shareEndpointKind = if (sharePathClient != null) {
                    TraversalEndpointKind.Device
                } else {
                    TraversalEndpointKind.Share
                }
                collectDirectoryEntriesAdaptive(
                    root = root,
                    config = resolveTraversalParallelism(
                        endpointKind = shareEndpointKind,
                        remoteRecommendedParallelism = shareTransferStatus?.recommendedParallelRequests,
                        remoteBusy = currentShareTransferStatusBusy(),
                    ),
                    pathSeparator = share.pathSeparator.ifBlank { "/" },
                    ensureRunning = ensureRunning,
                    onScanProgress = onScanProgress,
                    onEntriesDiscovered = onEntriesDiscovered,
                    dynamicMaxParallelismProvider = {
                        val latestStatus = currentShareTransferStatus()
                        resolveTraversalRuntimeMaxParallelism(
                            endpointKind = shareEndpointKind,
                            remoteRecommendedParallelism = latestStatus?.recommendedParallelRequests,
                            remoteBusy = currentShareTransferStatusBusy(),
                        )
                    },
                    maxParallelismLimit = maxParallelismLimit,
                    retainDiscoveredEntries = retainDiscoveredEntries,
                    deduplicateDirectories = deduplicateDirectories,
                    depthFirst = depthFirst,
                    requireKnownSymbolicLinkMetadata = true,
                    rejectSymbolicLinkEntries = rejectSymbolicLinkEntries,
                    onRejectedEntry = onRejectedEntry,
                    continueOnDirectoryError = continueOnDirectoryError,
                    onDirectoryError = reportDirectoryError,
                ) { directory ->
                    share.getFileList(directory.path).map { entries ->
                        entries.map { entry -> entry.withCopy(protocol = FileProtocol.Share, protocolId = root.protocolId) }
                    }
                }
            }

            FileProtocol.Network -> {
                ensureRunning()
                val networkAccess = resolveNetworkAccess(currentDesk(), root.protocolId)
                    ?: throw TaskEndpointUnavailableException(AppStrings.message_task_source_network_disconnected)
                collectDirectoryEntriesAdaptive(
                    root = root,
                    config = resolveTraversalParallelism(TraversalEndpointKind.Network),
                    pathSeparator = (networkAccess as? Network)?.pathSeparator ?: "/",
                    ensureRunning = ensureRunning,
                    onCancel = { cancelTaskScanRequests(root, requestBatchId) },
                    onScanProgress = onScanProgress,
                    onEntriesDiscovered = onEntriesDiscovered,
                    dynamicMaxParallelismProvider = {
                        resolveTraversalRuntimeMaxParallelism(TraversalEndpointKind.Network)
                    },
                    maxParallelismLimit = maxParallelismLimit,
                    retainDiscoveredEntries = retainDiscoveredEntries,
                    deduplicateDirectories = deduplicateDirectories,
                    depthFirst = depthFirst,
                    requireKnownSymbolicLinkMetadata = true,
                    rejectSymbolicLinkEntries = rejectSymbolicLinkEntries,
                    onRejectedEntry = onRejectedEntry,
                    continueOnDirectoryError = continueOnDirectoryError,
                    onDirectoryError = reportDirectoryError,
                ) { directory ->
                    networkAccess.getList(directory.path, batchId = requestBatchId).map { entries ->
                        entries.map { entry -> entry.withCopy(protocol = FileProtocol.Network, protocolId = root.protocolId) }
                    }
                }
            }
        }
    }

    private suspend fun cancelTaskScanRequests(root: FileSimpleInfo, requestBatchId: String?) {
        val batchId = requestBatchId?.takeIf { item -> item.isNotBlank() } ?: return
        when (root.protocol) {
            FileProtocol.Device -> {
                val device = resolveDevice(currentDesk(), root.protocolId) ?: return
                device.host.values.forEach { manager ->
                    runCatching { manager.cancelBatch(batchId, AppStrings.ui_cancel_task_traversal_list_request) }
                }
            }

            FileProtocol.Share -> Unit

            FileProtocol.Network -> {
                val networkAccess = resolveNetworkAccess(currentDesk(), root.protocolId) ?: return
                runCatching { networkAccess.cancelBatch(batchId, AppStrings.ui_cancel_task_traversal_list_request) }
            }

            FileProtocol.Local -> Unit
        }
    }

    private fun resolveNetworkAccess(
        preferredDesk: DiskBase?,
        protocolId: String,
    ): NetworkAccess? {
        if (preferredDesk is NetworkAccess && (protocolId.isBlank() || preferredDesk.protocolId == protocolId)) {
            return preferredDesk
        }
        return networkState.networks.firstOrNull { item -> item.protocolId == protocolId }
    }

    private fun resolveDevice(
        preferredDesk: DiskBase?,
        deviceId: String,
    ): Device? {
        return deviceState.resolveConnectedDevice(
            deviceId = deviceId,
            preferredDevice = preferredDesk as? Device,
        )
    }
}

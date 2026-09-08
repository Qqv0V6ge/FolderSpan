package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.ChunkReadableNetworkAccess
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.data.main.share.Share
import com.folderspan.exception.DeviceCopyUnsupportedException
import com.folderspan.exception.EmptyDataException
import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.extensions.formatDuration
import com.folderspan.extensions.formatPercent
import com.folderspan.extensions.formatSpeed
import com.folderspan.service.file.DeviceTransportChunk
import com.folderspan.service.file.runDeviceTransportPipeline
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_MAX_LENGTH
import com.folderspan.service.operation.TraversalEndpointKind
import com.folderspan.service.operation.delayForHttpTransferBackoff
import com.folderspan.ui.state.main.*
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

internal class FileStateCopyCoordinator(
    private val taskState: TaskState,
    private val deviceState: DeviceState,
    private val networkState: NetworkState,
    private val currentDesk: () -> DiskBase,
    private val fileRecentState: FileRecentState,
) {
    suspend fun copyTo(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
    ): Result<Boolean> {
        val cancellation = CancellationException(AppStrings.message_task_cancelled)

        suspend fun ensureRunningOrThrow() {
            if (!taskState.awaitIfPaused(task.key)) {
                throw cancellation
            }
            if (taskState.isTaskCancelled(task.key)) {
                throw cancellation
            }
        }

        return try {
            ensureRunningOrThrow()
            val result = withContext(Dispatchers.Default) {
                val copyRoute = resolveCopyRoute(
                    srcProtocol = srcFileSimpleInfo.protocol,
                    destProtocol = destFileSimpleInfo.protocol,
                    srcProtocolId = srcFileSimpleInfo.protocolId
                )
                LogKit.d(
                    AppStrings.ui_copy_start_task_arg0_route_arg1.format(arg0 = (task.key).toString(), arg1 = (copyRoute).toString()) +
                            "src=${srcFileSimpleInfo.protocol}:${srcFileSimpleInfo.protocolId}:${srcFileSimpleInfo.path}, " +
                            "dest=${destFileSimpleInfo.protocol}:${destFileSimpleInfo.protocolId}:${destFileSimpleInfo.path}"
                )
                when (copyRoute) {
                    CopyRoute.LocalToLocal,
                    CopyRoute.DirectToLocal,
                    CopyRoute.SystemShareToLocal -> {
                        srcFileSimpleInfo.copyTo(task, destFileSimpleInfo.path, taskState)
                    }

                    CopyRoute.DeviceRoute -> {
                        ensureRunningOrThrow()
                        val device = when {
                            srcFileSimpleInfo.protocol == FileProtocol.Device ->
                                resolveDevice(currentDesk(), srcFileSimpleInfo.protocolId)

                            destFileSimpleInfo.protocol == FileProtocol.Device ->
                                resolveDevice(currentDesk(), destFileSimpleInfo.protocolId)

                            else -> null
                        }
                        if (device == null) {
                            val currentDeskDeviceId = (currentDesk() as? Device)?.id.orEmpty()
                            val knownDevices =
                                deviceState.devices.joinToString(prefix = "[", postfix = "]") { item -> item.id }
                            val knownSocketDevices =
                                deviceState.socketDevices.joinToString(prefix = "[", postfix = "]") { item ->
                                    "${item.id}:${item.transportType}:${item.connectType}"
                                }
                            LogKit.w(
                                AppStrings.ui_device_copy_not_found_device_task_arg0.format(arg0 = (task.key).toString()) +
                                        "src=${srcFileSimpleInfo.protocol}:${srcFileSimpleInfo.protocolId}:${srcFileSimpleInfo.path}, " +
                                        "dest=${destFileSimpleInfo.protocol}:${destFileSimpleInfo.protocolId}:${destFileSimpleInfo.path}, " +
                                        "currentDeskDeviceId=$currentDeskDeviceId, " +
                                        "knownDevices=$knownDevices, " +
                                        "knownSocketDevices=$knownSocketDevices"
                            )
                        }
                        val copyResult = copyDeviceRouteWithUnsupportedFallback(
                            task = task,
                            source = srcFileSimpleInfo,
                            destination = destFileSimpleInfo,
                            device = device,
                        )
                        if (copyResult.isFailure) {
                            LogKit.e(
                                AppStrings.ui_device_copy_failed_task_arg0_deviceid_arg1.format(arg0 = (task.key).toString(), arg1 = device?.id ?: "") +
                                        "src=${srcFileSimpleInfo.path}, dest=${destFileSimpleInfo.path}",
                                copyResult.exceptionOrNull()
                            )
                        }
                        copyResult
                    }

                    CopyRoute.ShareToLocal -> {
                        ensureRunningOrThrow()
                        val share: Share =
                            deviceState.shares.firstOrNull { item -> item.id == srcFileSimpleInfo.protocolId }
                                ?: return@withContext Result.failure(
                                    EmptyDataException()
                                )
                        share.copyTo(
                            task = task,
                            srcFileSimpleInfo = srcFileSimpleInfo,
                            destFileSimpleInfo = destFileSimpleInfo
                        )
                    }

                    CopyRoute.ShareToNetwork -> {
                        ensureRunningOrThrow()
                        val networkAccess = resolveNetworkAccess(
                            preferredDesk = currentDesk(),
                            protocolId = destFileSimpleInfo.protocolId,
                        ) ?: return@withContext Result.failure(EmptyDataException())
                        copyShareToNetwork(
                            task = task,
                            source = srcFileSimpleInfo,
                            destination = destFileSimpleInfo,
                            networkAccess = networkAccess,
                        )
                    }

                    CopyRoute.NetworkToLocal -> {
                        ensureRunningOrThrow()
                        val networkAccess = resolveNetworkAccess(currentDesk(), srcFileSimpleInfo.protocolId)
                        networkAccess?.copyTo(task, srcFileSimpleInfo, destFileSimpleInfo)
                            ?: Result.failure(EmptyDataException())
                    }

                    CopyRoute.LocalToNetwork -> {
                        ensureRunningOrThrow()
                        val networkAccess = resolveNetworkAccess(
                            preferredDesk = currentDesk(),
                            protocolId = destFileSimpleInfo.protocolId,
                        )
                        networkAccess?.copyTo(task, srcFileSimpleInfo, destFileSimpleInfo)
                            ?: Result.failure(EmptyDataException())
                    }

                    CopyRoute.NetworkToDevice -> {
                        ensureRunningOrThrow()
                        val networkAccess = resolveNetworkAccess(
                            preferredDesk = currentDesk(),
                            protocolId = srcFileSimpleInfo.protocolId,
                        ) ?: return@withContext Result.failure(EmptyDataException())
                        val device = resolveDevice(
                            preferredDesk = currentDesk(),
                            deviceId = destFileSimpleInfo.protocolId,
                        ) ?: return@withContext Result.failure(EmptyDataException())
                        copyNetworkToDevice(
                            task = task,
                            source = srcFileSimpleInfo,
                            destination = destFileSimpleInfo,
                            networkAccess = networkAccess,
                            device = device,
                            ensureRunning = ::ensureRunningOrThrow,
                        )
                    }

                    CopyRoute.DeviceToNetwork -> {
                        ensureRunningOrThrow()
                        val device = resolveDevice(
                            preferredDesk = currentDesk(),
                            deviceId = srcFileSimpleInfo.protocolId,
                        ) ?: return@withContext Result.failure(EmptyDataException())
                        val networkAccess = resolveNetworkAccess(
                            preferredDesk = currentDesk(),
                            protocolId = destFileSimpleInfo.protocolId,
                        ) ?: return@withContext Result.failure(EmptyDataException())
                        copyDeviceToNetwork(
                            task = task,
                            source = srcFileSimpleInfo,
                            destination = destFileSimpleInfo,
                            device = device,
                            networkAccess = networkAccess,
                            ensureRunning = ::ensureRunningOrThrow,
                        )
                    }

                    CopyRoute.NetworkToNetwork -> {
                        ensureRunningOrThrow()
                        val sourceNetworkAccess = resolveNetworkAccess(
                            preferredDesk = currentDesk(),
                            protocolId = srcFileSimpleInfo.protocolId,
                        ) ?: return@withContext Result.failure(EmptyDataException())
                        val targetNetworkAccess = resolveNetworkAccess(
                            preferredDesk = currentDesk(),
                            protocolId = destFileSimpleInfo.protocolId,
                        ) ?: return@withContext Result.failure(EmptyDataException())
                        copyNetworkToNetwork(
                            task = task,
                            source = srcFileSimpleInfo,
                            destination = destFileSimpleInfo,
                            sourceNetworkAccess = sourceNetworkAccess,
                            targetNetworkAccess = targetNetworkAccess,
                        )
                    }

                    CopyRoute.Unsupported -> Result.failure(Exception(AppStrings.ui_not_supported_for_copying))
                }
            }
            if (result.isFailure && result.exceptionOrNull() is CancellationException) throw cancellation
            if (taskState.isTaskCancelled(task.key)) throw cancellation
            recordTransferDestinationIfNeeded(
                taskType = task.taskType,
                destination = destFileSimpleInfo,
                result = result,
                isTaskCancelled = false,
                record = fileRecentState::record,
            )
            result
        } catch (cancel: CancellationException) {
            val message = taskState.resolveTaskFailureMessage(
                task = task,
                preferredPath = destFileSimpleInfo.path,
                error = cancel,
                fallback = AppStrings.message_task_cancelled,
            )
            taskState.putResult(task, destFileSimpleInfo.path, message)
            taskState.updateStatus(task, StatusEnum.FAILURE)
            taskState.clearSignals(task.key)
            Result.failure(cancel)
        } catch (error: Exception) {
            LogKit.e(
                AppStrings.ui_copy_execution_exception_task_arg0.format(arg0 = (task.key).toString()) +
                    "src=${srcFileSimpleInfo.protocol}:${srcFileSimpleInfo.protocolId}:${srcFileSimpleInfo.path}, " +
                    "dest=${destFileSimpleInfo.protocol}:${destFileSimpleInfo.protocolId}:${destFileSimpleInfo.path}",
                error,
            )
            Result.failure(error)
        }
    }

    suspend fun runCopyAcrossEndpoints(
        task: Task,
        src: FileSimpleInfo,
        dest: FileSimpleInfo,
        sourceDesk: DiskBase,
        targetDesk: DiskBase,
    ): Result<Boolean> {
        val cancellation = CancellationException(AppStrings.message_task_cancelled)

        suspend fun ensureRunningOrThrow() {
            if (!taskState.awaitIfPaused(task.key)) {
                throw cancellation
            }
            if (taskState.isTaskCancelled(task.key)) {
                throw cancellation
            }
        }

        return try {
            ensureRunningOrThrow()
            val result = withContext(Dispatchers.Default) {
                when (
                    resolveCopyRoute(
                        srcProtocol = src.protocol,
                        destProtocol = dest.protocol,
                        srcProtocolId = src.protocolId
                    )
                ) {
                    CopyRoute.LocalToLocal,
                    CopyRoute.DirectToLocal,
                    CopyRoute.SystemShareToLocal -> {
                        src.copyTo(task, dest.path, taskState)
                    }

                    CopyRoute.DeviceRoute -> {
                        ensureRunningOrThrow()
                        val device: Device? = when {
                            src.protocol == FileProtocol.Device -> {
                                resolveDevice(sourceDesk, src.protocolId)
                            }

                            dest.protocol == FileProtocol.Device -> {
                                resolveDevice(targetDesk, dest.protocolId)
                            }

                            else -> null
                        }
                        copyDeviceRouteWithUnsupportedFallback(
                            task = task,
                            source = src,
                            destination = dest,
                            device = device,
                        )
                    }

                    CopyRoute.ShareToLocal -> {
                        ensureRunningOrThrow()
                        val share: Share =
                            deviceState.shares.firstOrNull { item -> item.id == src.protocolId }
                                ?: return@withContext Result.failure(EmptyDataException())
                        share.copyTo(task = task, srcFileSimpleInfo = src, destFileSimpleInfo = dest)
                    }

                    CopyRoute.ShareToNetwork -> {
                        ensureRunningOrThrow()
                        val networkAccess = resolveNetworkAccess(targetDesk, dest.protocolId)
                            ?: return@withContext Result.failure(EmptyDataException())
                        copyShareToNetwork(
                            task = task,
                            source = src,
                            destination = dest,
                            networkAccess = networkAccess,
                        )
                    }

                    CopyRoute.NetworkToLocal -> {
                        ensureRunningOrThrow()
                        val networkAccess = resolveNetworkAccess(sourceDesk, src.protocolId)
                        networkAccess?.copyTo(task, src, dest) ?: Result.failure(EmptyDataException())
                    }

                    CopyRoute.LocalToNetwork -> {
                        ensureRunningOrThrow()
                        val networkAccess = resolveNetworkAccess(targetDesk, dest.protocolId)
                        networkAccess?.copyTo(task, src, dest) ?: Result.failure(EmptyDataException())
                    }

                    CopyRoute.NetworkToDevice -> {
                        ensureRunningOrThrow()
                        val networkAccess = resolveNetworkAccess(sourceDesk, src.protocolId)
                            ?: return@withContext Result.failure(EmptyDataException())
                        val device = resolveDevice(targetDesk, dest.protocolId)
                            ?: return@withContext Result.failure(EmptyDataException())
                        copyNetworkToDevice(
                            task = task,
                            source = src,
                            destination = dest,
                            networkAccess = networkAccess,
                            device = device,
                            ensureRunning = ::ensureRunningOrThrow,
                        )
                    }

                    CopyRoute.DeviceToNetwork -> {
                        ensureRunningOrThrow()
                        val device = resolveDevice(sourceDesk, src.protocolId)
                            ?: return@withContext Result.failure(EmptyDataException())
                        val networkAccess = resolveNetworkAccess(targetDesk, dest.protocolId)
                            ?: return@withContext Result.failure(EmptyDataException())
                        copyDeviceToNetwork(
                            task = task,
                            source = src,
                            destination = dest,
                            device = device,
                            networkAccess = networkAccess,
                            ensureRunning = ::ensureRunningOrThrow,
                        )
                    }

                    CopyRoute.NetworkToNetwork -> {
                        ensureRunningOrThrow()
                        val sourceNetworkAccess = resolveNetworkAccess(sourceDesk, src.protocolId)
                            ?: return@withContext Result.failure(EmptyDataException())
                        val targetNetworkAccess = resolveNetworkAccess(targetDesk, dest.protocolId)
                            ?: return@withContext Result.failure(EmptyDataException())
                        copyNetworkToNetwork(
                            task = task,
                            source = src,
                            destination = dest,
                            sourceNetworkAccess = sourceNetworkAccess,
                            targetNetworkAccess = targetNetworkAccess,
                        )
                    }

                    CopyRoute.Unsupported -> Result.failure(Exception(AppStrings.ui_not_supported_for_copying))
                }
            }
            if (result.isFailure && result.exceptionOrNull() is CancellationException) throw cancellation
            if (taskState.isTaskCancelled(task.key)) throw cancellation
            recordTransferDestinationIfNeeded(
                taskType = task.taskType,
                destination = dest,
                result = result,
                isTaskCancelled = false,
                record = fileRecentState::record,
            )
            result
        } catch (cancel: CancellationException) {
            val message = taskState.resolveTaskFailureMessage(
                task = task,
                preferredPath = dest.path,
                error = cancel,
                fallback = AppStrings.message_task_cancelled,
            )
            taskState.putResult(task, dest.path, message)
            taskState.updateStatus(task, StatusEnum.FAILURE)
            taskState.clearSignals(task.key)
            Result.failure(cancel)
        } catch (error: Exception) {
            LogKit.e(
                AppStrings.ui_cross_end_copy_execution_exception_task_arg0.format(arg0 = (task.key).toString()) +
                    "src=${src.protocol}:${src.protocolId}:${src.path}, " +
                    "dest=${dest.protocol}:${dest.protocolId}:${dest.path}",
                error,
            )
            Result.failure(error)
        }
    }

    private suspend fun copyNetworkToDevice(
        task: Task,
        source: FileSimpleInfo,
        destination: FileSimpleInfo,
        networkAccess: NetworkAccess,
        device: Device,
        ensureRunning: suspend () -> Unit,
    ): Result<Boolean> {
        return runWithDeviceDisconnectWatcher(
            task = task,
            deviceId = device.id,
            label = AppStrings.ui_target_device,
            preferredPath = destination.path,
        ) {
            val directResult = copyNetworkFileToDeviceDirect(
                task = task,
                source = source,
                destination = destination,
                networkAccess = networkAccess,
                device = device,
                ensureRunning = ensureRunning,
            )
            if (directResult != null) {
                return@runWithDeviceDisconnectWatcher directResult
            }
            copyViaLocalTemp(
                source = source,
                copyToTemp = { tempInfo -> networkAccess.copyTo(task, source, tempInfo) },
                copyFromTemp = { tempInfo -> device.files.copyTo(task, tempInfo, destination) }
            )
        }
    }

    private suspend fun copyDeviceRouteWithUnsupportedFallback(
        task: Task,
        source: FileSimpleInfo,
        destination: FileSimpleInfo,
        device: Device?,
    ): Result<Boolean> {
        device ?: return Result.failure(EmptyDataException())
        val isSameDeviceEndpoint =
            source.protocol == FileProtocol.Device &&
                destination.protocol == FileProtocol.Device &&
                source.protocolId.isNotBlank() &&
                source.protocolId == destination.protocolId
        if (!isSameDeviceEndpoint) {
            return device.files.copyTo(task, source, destination)
        }

        return copyDeviceFileWithUnsupportedFallback(
            copyOnDevice = {
                device.files.copyTo(task, source, destination)
            },
            copyViaLocal = {
                LogKit.i(
                    AppStrings.ui_device_does_not_support_direct_copying_uses_local_temporary +
                        "${source.path} -> ${destination.path}"
                )
                taskState.putResult(task, destination.path, AppStrings.ui_device_does_not_support_direct_copying_being_transferred_through)
                copyViaLocalTemp(
                    source = source,
                    copyToTemp = { tempInfo -> device.files.copyTo(task, source, tempInfo) },
                    copyFromTemp = { tempInfo -> device.files.copyTo(task, tempInfo, destination) },
                )
            },
        )
    }

    private suspend fun copyNetworkToNetwork(
        task: Task,
        source: FileSimpleInfo,
        destination: FileSimpleInfo,
        sourceNetworkAccess: NetworkAccess,
        targetNetworkAccess: NetworkAccess,
    ): Result<Boolean> {
        val separator = (sourceNetworkAccess as? Network)?.pathSeparator ?: "/"
        val isSameEndpoint = isSameNetworkEndpoint(
            sourceProtocolId = sourceNetworkAccess.protocolId,
            targetProtocolId = targetNetworkAccess.protocolId,
        )
        if (
            source.isDirectory &&
            isSameEndpoint &&
            isParentPath(source.path, destination.path, separator)
        ) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_target_path_cannot_within_source_directory))
        }
        if (source.isDirectory && isSameEndpoint) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_same_directory_copy_must_be_executed_as_file_task))
        }

        if (isSameEndpoint) {
            return copyNetworkFileWithUnsupportedFallback(
                copyOnServer = {
                    sourceNetworkAccess.copyFileWithinEndpoint(task, source, destination)
                },
                copyViaLocal = {
                    LogKit.i(
                        AppStrings.ui_network_side_does_not_support_direct_copying_so_local +
                            "${source.path} -> ${destination.path}"
                    )
                    taskState.putResult(task, destination.path, AppStrings.ui_network_does_not_support_direct_copying_being_transferred_through)
                    copyViaLocalTemp(
                        source = source,
                        copyToTemp = { tempInfo -> sourceNetworkAccess.copyTo(task, source, tempInfo) },
                        copyFromTemp = { tempInfo -> targetNetworkAccess.copyTo(task, tempInfo, destination) }
                    )
                },
            )
        }

        return copyViaLocalTemp(
            source = source,
            copyToTemp = { tempInfo -> sourceNetworkAccess.copyTo(task, source, tempInfo) },
            copyFromTemp = { tempInfo -> targetNetworkAccess.copyTo(task, tempInfo, destination) }
        )
    }

    private suspend fun copyDeviceToNetwork(
        task: Task,
        source: FileSimpleInfo,
        destination: FileSimpleInfo,
        device: Device,
        networkAccess: NetworkAccess,
        ensureRunning: suspend () -> Unit,
    ): Result<Boolean> {
        return runWithDeviceDisconnectWatcher(
            task = task,
            deviceId = device.id,
            label = AppStrings.ui_source_device,
            preferredPath = source.path,
        ) {
            val fileClient = device.fileClient ?: device.host.values.firstOrNull()?.fileRouteClient
                ?: return@runWithDeviceDisconnectWatcher Result.failure(EmptyDataException())
            val destSeparator = (networkAccess as? Network)?.pathSeparator ?: "/"
            copySourceToNetwork(
                task = task,
                source = source,
                destination = destination,
                networkAccess = networkAccess,
                ensureRunning = ensureRunning,
                sourceSeparator = device.pathSeparator.ifBlank { "/" },
                destSeparator = destSeparator,
                traversalKind = TraversalEndpointKind.Device,
                listChildren = { directory ->
                    device.paths.getList(directory.path).map { entries ->
                        entries.map { entry ->
                            entry.withCopy(protocol = FileProtocol.Device, protocolId = device.id)
                        }
                    }
                },
                chunkSize = resolveDeviceRelayPipelineConfig(device).chunkSize,
                readRange = { path, startOffset, endOffset ->
                    ensureRunning()
                    ensureConnectedDevice(device.id, AppStrings.ui_source_device)
                    delayForHttpTransferBackoff(fileClient.transferStatus())
                    fileClient.readBytes(path, startOffset, endOffset)
                },
            )
        }
    }

    private suspend fun copyShareToNetwork(
        task: Task,
        source: FileSimpleInfo,
        destination: FileSimpleInfo,
        networkAccess: NetworkAccess,
    ): Result<Boolean> {
        val cancellation = CancellationException(AppStrings.message_task_cancelled)

        suspend fun ensureRunning() {
            if (!taskState.awaitIfPaused(task.key)) {
                throw cancellation
            }
            if (taskState.isTaskCancelled(task.key)) {
                throw cancellation
            }
        }

        val destSeparator = (networkAccess as? Network)?.pathSeparator ?: "/"
        if (!shouldResolveRemoteShareSession(source.protocolId)) {
            return copyLocalOrContentSourceToNetwork(
                task = task,
                source = source,
                destination = destination,
                networkAccess = networkAccess,
                destSeparator = destSeparator,
                ensureRunning = ::ensureRunning,
            )
        }

        val share = resolveShare(source.protocolId) ?: return Result.failure(EmptyDataException())
        return copySourceToNetwork(
            task = task,
            source = source,
            destination = destination,
            networkAccess = networkAccess,
            ensureRunning = ::ensureRunning,
            sourceSeparator = share.pathSeparator.ifBlank { "/" },
            destSeparator = destSeparator,
            traversalKind = TraversalEndpointKind.Share,
            listChildren = { directory ->
                share.getFileList(directory.path).map { entries ->
                    entries.map { entry ->
                        entry.withCopy(protocol = FileProtocol.Share, protocolId = share.id)
                    }
                }
            },
            chunkSize = DEVICE_DIRECT_MAX_LENGTH,
            readRange = { path, startOffset, endOffset ->
                ensureRunning()
                share.readBytes(path, startOffset, endOffset)
            },
        )
    }

    private suspend fun copyLocalOrContentSourceToNetwork(
        task: Task,
        source: FileSimpleInfo,
        destination: FileSimpleInfo,
        networkAccess: NetworkAccess,
        destSeparator: String,
        ensureRunning: suspend () -> Unit,
    ): Result<Boolean> {
        val localSeparator = PathUtils.getPathSeparator()
        return copySourceToNetwork(
            task = task,
            source = source,
            destination = destination,
            networkAccess = networkAccess,
            ensureRunning = ensureRunning,
            sourceSeparator = localSeparator,
            destSeparator = destSeparator,
            traversalKind = TraversalEndpointKind.Local,
            listChildren = { directory ->
                PathUtils.getFileAndFolder(FileAccessPermission.Allowed, directory.path).map { entries ->
                    entries.map { entry ->
                        entry.withCopy(protocol = FileProtocol.Share, protocolId = SYSTEM_SHARE_DESK_ID)
                    }
                }
            },
            chunkSize = STREAM_NETWORK_PROGRESS_BLOCK_SIZE.toInt(),
            readRange = { path, startOffset, endOffset ->
                ensureRunning()
                FileUtils.readFileRange(FileAccessPermission.Allowed, path, startOffset, endOffset)
            },
            localPathFallback = { file, remotePath ->
                if (isUsableLocalUploadPath(file.path) && file.size < 0L) {
                    networkAccess.uploadFileFromLocal(
                        localPath = file.path,
                        remotePath = remotePath,
                        size = file.size,
                    )
                } else {
                    null
                }
            },
        )
    }

    private suspend fun copySourceToNetwork(
        task: Task,
        source: FileSimpleInfo,
        destination: FileSimpleInfo,
        networkAccess: NetworkAccess,
        ensureRunning: suspend () -> Unit,
        sourceSeparator: String,
        destSeparator: String,
        traversalKind: TraversalEndpointKind,
        listChildren: suspend (FileSimpleInfo) -> Result<List<FileSimpleInfo>>,
        chunkSize: Int,
        readRange: suspend (path: String, startOffset: Long, endOffset: Long) -> Result<ByteArray>,
        localPathFallback: suspend (FileSimpleInfo, String) -> Result<Boolean>? = { _, _ -> null },
    ): Result<Boolean> {
        if (source.isDirectory) {
            taskState.putCopyScanProgress(task)
            return streamCopyDirectoryToNetwork(
                source = source,
                destination = destination,
                sourceSeparator = sourceSeparator,
                destSeparator = destSeparator,
                networkAccess = networkAccess,
                ensureRunning = ensureRunning,
                traversalKind = traversalKind,
                listChildren = listChildren,
                copyFile = { sourceFile, destPath ->
                    streamSingleFileToNetwork(
                        task = task,
                        source = sourceFile,
                        destPath = destPath,
                        networkAccess = networkAccess,
                        destSeparator = destSeparator,
                        chunkSize = chunkSize,
                        ensureRunning = ensureRunning,
                        reportChunkProgress = false,
                        readRange = { startOffset, endOffset ->
                            readRange(sourceFile.path, startOffset, endOffset)
                        },
                        localPathFallback = { file, remotePath ->
                            localPathFallback(file, remotePath)
                        },
                    )
                },
                onScanProgress = { discovered ->
                    taskState.putCopyScanProgress(task, discovered)
                },
                onCreateFolder = { path ->
                    taskState.putCreatingFolderProgress(task, path)
                },
                onEntryProgress = { processed, total, path ->
                    taskState.putValue(task, "path", path)
                    taskState.putValue(task, "progressMax", total.toString())
                    taskState.putValue(task, "progressCur", processed.toString())
                },
            )
        }

        return streamSingleFileToNetwork(
            task = task,
            source = source,
            destPath = destination.path,
            networkAccess = networkAccess,
            destSeparator = destSeparator,
            chunkSize = chunkSize,
            ensureRunning = ensureRunning,
            reportChunkProgress = true,
            readRange = { startOffset, endOffset ->
                readRange(source.path, startOffset, endOffset)
            },
            localPathFallback = localPathFallback,
        )
    }

    private suspend fun streamSingleFileToNetwork(
        task: Task,
        source: FileSimpleInfo,
        destPath: String,
        networkAccess: NetworkAccess,
        destSeparator: String,
        chunkSize: Int,
        ensureRunning: suspend () -> Unit,
        reportChunkProgress: Boolean,
        readRange: suspend (startOffset: Long, endOffset: Long) -> Result<ByteArray>,
        localPathFallback: suspend (FileSimpleInfo, String) -> Result<Boolean>? = { _, _ -> null },
    ): Result<Boolean> {
        ensureRunning()
        taskState.putValue(task, "path", destPath)
        val declaredSize = source.size
        if (declaredSize == 0L) {
            return createRemoteEmptyFile(networkAccess, destPath, destSeparator)
        }
        localPathFallback(source, destPath)?.let { fallback ->
            return fallback
        }
        val totalBytes = declaredSize.coerceAtLeast(0L)
        val totalBlocks = streamNetworkProgressBlocks(totalBytes)
        val startMs = Clock.System.now().toEpochMilliseconds()
        var lastLogMs = startMs
        val rateSampler = TaskProgressRateSampler(initialAt = startMs)
        if (reportChunkProgress) {
            taskState.putValue(task, "progressCur", "0")
            taskState.putValue(task, "progressMax", totalBlocks.toString())
        }
        taskState.putResult(task, destPath, AppStrings.ui_start_transfer)

        fun updateProgress(doneBytes: Long, total: Long, force: Boolean = false) {
            val resolvedTotal = if (total > 0L) total else totalBytes
            val now = Clock.System.now().toEpochMilliseconds()
            if (!force && now - lastLogMs < 1000L && (resolvedTotal <= 0L || doneBytes < resolvedTotal)) {
                return
            }
            val rateSample = rateSampler.update(
                completed = doneBytes,
                total = resolvedTotal,
                now = now,
                force = force || (resolvedTotal > 0L && doneBytes >= resolvedTotal),
            )
            val doneBlocks = streamNetworkProgressCur(doneBytes, resolvedTotal)
            val resolvedBlocks = streamNetworkProgressBlocks(resolvedTotal)
            if (reportChunkProgress) {
                taskState.putValue(task, "progressCur", doneBlocks.toString())
                taskState.putValue(task, "progressMax", resolvedBlocks.toString())
            }
            taskState.putResult(
                task,
                destPath,
                AppStrings.ui_transfer_progress_arg0_arg1_arg2_speed_arg3_remaining_arg4.format(
                    arg0 = doneBytes.formatPercent(resolvedTotal),
                    arg1 = doneBlocks.toString(),
                    arg2 = resolvedBlocks.toString(),
                    arg3 = rateSample.speedPerSecond.formatSpeed(),
                    arg4 = rateSample.etaMs.formatDuration(),
                ),
            )
            lastLogMs = now
        }

        val result = streamUploadFileToNetwork(
            networkAccess = networkAccess,
            remotePath = destPath,
            size = declaredSize,
            onProgress = { doneBytes, total ->
                updateProgress(doneBytes, total)
            },
            readChunk = sequentialRangeReadChunk(
                size = declaredSize,
                chunkSize = chunkSize,
                ensureRunning = ensureRunning,
                readRange = readRange,
            ),
        )
        if (result.isSuccess && result.getOrDefault(false)) {
            updateProgress(totalBytes, totalBytes, force = true)
            taskState.removeResult(task, destPath)
        }
        return result
    }

    private suspend fun copyNetworkFileToDeviceDirect(
        task: Task,
        source: FileSimpleInfo,
        destination: FileSimpleInfo,
        networkAccess: NetworkAccess,
        device: Device,
        ensureRunning: suspend () -> Unit,
    ): Result<Boolean>? {
        if (source.isDirectory) {
            return null
        }
        val chunkReadable = networkAccess as? ChunkReadableNetworkAccess ?: return null
        val fileClient = device.fileClient ?: device.host.values.firstOrNull()?.fileRouteClient
        ?: return Result.failure(EmptyDataException())
        val declaredSize = source.size.coerceAtLeast(0L)
        val pipelineConfig = resolveDeviceRelayPipelineConfig(device)
        taskState.putValue(task, "path", destination.path)

        fun ensureTargetDeviceConnected() {
            ensureConnectedDevice(device.id, AppStrings.ui_target_device)
        }

        if (declaredSize == 0L) {
            ensureTargetDeviceConnected()
            val createResult = fileClient.createFiles(listOf(destination.path))
            return createResult.fold(
                onSuccess = { results ->
                    results.firstOrNull() ?: Result.success(false)
                },
                onFailure = { error -> Result.failure(error) }
            )
        }

        var resolvedSize = declaredSize
        var stagedBytes = 0
        var blockIndex = 0L
        var blockStartOffset = 0L
        var writtenBytes = 0L
        val startMs = Clock.System.now().toEpochMilliseconds()
        var lastLogMs = startMs
        val rateSampler = TaskProgressRateSampler(initialAt = startMs)
        val relayBuffer = ByteArray(pipelineConfig.chunkSize)
        val writeSemaphore = Semaphore(pipelineConfig.writeParallelism)
        val writeProgressMutex = Mutex()
        val pendingWrites = mutableListOf<Job>()
        taskState.putValue(task, "progressCur", "0")
        taskState.putValue(task, "progressMax", totalRelayBlocks(resolvedSize, pipelineConfig.chunkSize).toString())
        taskState.putResult(task, destination.path, AppStrings.ui_start_transfer)

        fun updateRelayProgress(force: Boolean = false) {
            val now = Clock.System.now().toEpochMilliseconds()
            if (!force && now - lastLogMs < 1000L && writtenBytes < resolvedSize) {
                return
            }
            val rateSample = rateSampler.update(
                completed = writtenBytes,
                total = resolvedSize,
                now = now,
                force = force || writtenBytes >= resolvedSize,
            )
            val totalBlocks = totalRelayBlocks(resolvedSize, pipelineConfig.chunkSize)
            val doneBlocks = ((writtenBytes + pipelineConfig.chunkSize - 1L) / pipelineConfig.chunkSize)
                .coerceIn(0L, totalBlocks.toLong())
            val resultText =
                AppStrings.ui_transfer_progress_arg0_arg1_arg2_speed_arg3_remaining_arg4.format(arg0 = writtenBytes.formatPercent(resolvedSize), arg1 = (doneBlocks).toString(), arg2 = (totalBlocks).toString(), arg3 = rateSample.speedPerSecond.formatSpeed(), arg4 = rateSample.etaMs.formatDuration())
            taskState.putResult(task, destination.path, resultText)
            lastLogMs = now
        }

        suspend fun flushRelayBuffer(scope: CoroutineScope): Result<Unit> {
            if (stagedBytes <= 0) return Result.success(Unit)
            ensureRunning()
            ensureTargetDeviceConnected()
            val blockBytes = stagedBytes
            val writeBlockIndex = blockIndex
            val writeStartOffset = blockStartOffset
            val writeBytes = relayBuffer.copyOf(blockBytes)
            stagedBytes = 0
            blockIndex++
            blockStartOffset += blockBytes.toLong()
            writeSemaphore.acquire()
            val writeJob = scope.launch(Dispatchers.Default) {
                try {
                    ensureRunning()
                    ensureTargetDeviceConnected()
                    delayForHttpTransferBackoff(fileClient.transferStatus())
                    val writeResult = fileClient.writeBytes(
                        fileSize = resolvedSize,
                        blockIndex = writeBlockIndex,
                        blockLength = blockBytes.toLong(),
                        path = destination.path,
                        byteArray = writeBytes,
                        startOffset = writeStartOffset,
                    )
                    if (writeResult.isFailure || !writeResult.getOrDefault(false)) {
                        throw (writeResult.exceptionOrNull() ?: Exception(AppStrings.ui_write_failed))
                    }
                    writeProgressMutex.withLock {
                        writtenBytes += blockBytes
                        val completedBlocks =
                            ((writtenBytes + pipelineConfig.chunkSize - 1L) / pipelineConfig.chunkSize)
                                .coerceAtMost(totalRelayBlocks(resolvedSize, pipelineConfig.chunkSize).toLong())
                        taskState.putValue(
                            task,
                            "progressCur",
                            completedBlocks.toString()
                        )
                        updateRelayProgress()
                    }
                } finally {
                    writeSemaphore.release()
                }
            }
            pendingWrites += writeJob
            pendingWrites.removeAll { it.isCompleted }
            return Result.success(Unit)
        }

        val directResult = try {
            coroutineScope {
                chunkReadable.downloadFileByChunks(task, source) { chunk, totalBytes ->
                    ensureRunning()
                    ensureTargetDeviceConnected()
                    if (totalBytes > 0L) {
                        resolvedSize = totalBytes
                        taskState.putValue(
                            task,
                            "progressMax",
                            totalRelayBlocks(resolvedSize, pipelineConfig.chunkSize).toString()
                        )
                    }
                    var chunkOffset = 0
                    while (chunkOffset < chunk.size) {
                        val copySize = minOf(pipelineConfig.chunkSize - stagedBytes, chunk.size - chunkOffset)
                        chunk.copyInto(
                            relayBuffer,
                            destinationOffset = stagedBytes,
                            startIndex = chunkOffset,
                            endIndex = chunkOffset + copySize,
                        )
                        stagedBytes += copySize
                        chunkOffset += copySize
                        if (stagedBytes == pipelineConfig.chunkSize) {
                            val flushResult = flushRelayBuffer(this)
                            if (flushResult.isFailure) {
                                return@downloadFileByChunks Result.failure(
                                    flushResult.exceptionOrNull() ?: Exception(AppStrings.ui_write_failed)
                                )
                            }
                        }
                    }
                    Result.success(Unit)
                }
            }
        } catch (error: Exception) {
            Result.failure(error)
        }

        if (directResult.isFailure) {
            val error = directResult.exceptionOrNull()
            if (error is NetworkUnsupportedException) {
                return null
            }
            return Result.failure(error ?: Exception(AppStrings.ui_flow_relay_failed))
        }

        val finalFlush = coroutineScope { flushRelayBuffer(this) }
        if (finalFlush.isFailure) {
            return Result.failure(finalFlush.exceptionOrNull() ?: Exception(AppStrings.ui_write_failed))
        }
        pendingWrites.joinAll()
        updateRelayProgress(force = true)
        taskState.putValue(task, "progressCur", totalRelayBlocks(resolvedSize, pipelineConfig.chunkSize).toString())
        taskState.removeResult(task, destination.path)
        return Result.success(true)
    }

    private suspend fun stageDeviceFileToLocalForNetworkUpload(
        task: Task,
        source: FileSimpleInfo,
        displayPath: String,
        tempInfo: FileSimpleInfo,
        device: Device,
        ensureRunning: suspend () -> Unit,
    ): Result<Boolean> {
        val fileClient = device.fileClient ?: device.host.values.firstOrNull()?.fileRouteClient
        ?: return Result.failure(EmptyDataException())
        val declaredSize = source.size.coerceAtLeast(0L)
        val pipelineConfig = resolveDeviceRelayPipelineConfig(device)
        taskState.putValue(task, "path", displayPath)

        fun ensureSourceDeviceConnected() {
            ensureConnectedDevice(device.id, AppStrings.ui_source_device)
        }

        if (declaredSize == 0L) {
            ensureSourceDeviceConnected()
            val createResult = FileUtils.createFile(FileAccessPermission.Allowed, tempInfo.path)
            if (createResult.isFailure || !createResult.getOrDefault(false)) {
                return Result.failure(createResult.exceptionOrNull() ?: Exception(AppStrings.ui_create_temporary_files_failed))
            }
            return Result.success(true)
        }

        val totalBlocks = totalRelayBlocks(declaredSize, pipelineConfig.chunkSize)
        val startMs = Clock.System.now().toEpochMilliseconds()
        var lastLogMs = startMs
        val rateSampler = TaskProgressRateSampler(initialAt = startMs)
        taskState.putValue(task, "progressCur", "0")
        taskState.putValue(task, "progressMax", totalBlocks.toString())
        taskState.putResult(task, displayPath, AppStrings.ui_start_transfer)

        fun updateStageProgress(transferredBytes: Long, completedChunks: Int, force: Boolean = false) {
            val now = Clock.System.now().toEpochMilliseconds()
            if (!force && now - lastLogMs < 1000L && transferredBytes < declaredSize) {
                return
            }
            val rateSample = rateSampler.update(
                completed = transferredBytes,
                total = declaredSize,
                now = now,
                force = force || transferredBytes >= declaredSize,
            )
            val doneBlocks = completedChunks.toLong().coerceIn(0L, totalBlocks.toLong())
                .coerceIn(0L, totalBlocks.toLong())
            val resultText =
                AppStrings.ui_transfer_progress_arg0_arg1_arg2_speed_arg3_remaining_arg4.format(arg0 = transferredBytes.formatPercent(declaredSize), arg1 = (doneBlocks).toString(), arg2 = (totalBlocks).toString(), arg3 = rateSample.speedPerSecond.formatSpeed(), arg4 = rateSample.etaMs.formatDuration())
            taskState.putResult(task, displayPath, resultText)
            lastLogMs = now
        }

        return runDeviceTransportPipeline(
            totalBytes = declaredSize,
            config = pipelineConfig,
            readChunk = { _, startOffset, endOffset ->
                ensureRunning()
                ensureSourceDeviceConnected()
                delayForHttpTransferBackoff(fileClient.transferStatus())
                fileClient.readBytes(source.path, startOffset, endOffset).mapCatching { bytes ->
                    val expectedBytes = (endOffset - startOffset).coerceAtLeast(0L)
                    if (bytes.size.toLong() != expectedBytes) {
                        throw IllegalStateException(AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range)
                    }
                    bytes
                }
            },
            writeChunk = { chunk: DeviceTransportChunk ->
                ensureRunning()
                FileUtils.writeBytes(
                    permission = FileAccessPermission.Allowed,
                    path = tempInfo.path,
                    fileSize = declaredSize,
                    data = chunk.bytes,
                    offset = chunk.startOffset
                )
            },
            onChunkCommitted = { progress ->
                taskState.putValue(task, "progressCur", progress.completedChunks.toString())
                updateStageProgress(progress.transferredBytes, progress.completedChunks)
            }
        ).map {
            updateStageProgress(declaredSize, totalBlocks, force = true)
            true
        }
    }

    private suspend fun copyViaLocalTemp(
        source: FileSimpleInfo,
        copyToTemp: suspend (FileSimpleInfo) -> Result<Boolean>,
        copyFromTemp: suspend (FileSimpleInfo) -> Result<Boolean>,
    ): Result<Boolean> {
        val tempPath = buildSyncTempPath(source.name)
        val tempInfo = buildSyncTempInfo(source, tempPath)
        return try {
            val stageResult = copyToTemp(tempInfo)
            if (stageResult.isFailure || !stageResult.getOrDefault(false)) {
                return stageResult
            }
            copyFromTemp(tempInfo)
        } finally {
            runCatching { FileUtils.deleteFile(FileAccessPermission.Allowed, tempPath) }
        }
    }

    private suspend fun <T> runWithDeviceDisconnectWatcher(
        task: Task,
        deviceId: String,
        label: String,
        preferredPath: String,
        block: suspend () -> Result<T>,
    ): Result<T> = coroutineScope {
        ensureConnectedDevice(deviceId, label)
        val disconnectMessage = AppStrings.ui_arg0_has_been_disconnected.format(arg0 = label)
        val watcher = launch(Dispatchers.Default) {
            while (isActive && !taskState.isTaskCancelled(task.key)) {
                val isConnected = deviceState.socketDevices.any { device ->
                    device.id == deviceId && device.hasActiveConnection()
                }
                if (!isConnected) {
                    taskState.putResult(task, preferredPath, disconnectMessage)
                    taskState.updateStatus(task, StatusEnum.FAILURE)
                    taskState.requestCancel(task, disconnectMessage)
                    break
                }
                delay(300.milliseconds)
            }
        }
        try {
            block()
        } finally {
            watcher.cancel()
        }
    }

    private fun buildSyncTempInfo(source: FileSimpleInfo, tempPath: String): FileSimpleInfo {
        return FileSimpleInfo.nullFileSimpleInfo().copy(
            name = source.name,
            description = source.description,
            isDirectory = source.isDirectory,
            isHidden = source.isHidden,
            path = tempPath,
            mineType = source.mineType,
            size = source.size,
            createdDate = source.createdDate,
            updatedDate = source.updatedDate,
            protocol = FileProtocol.Local,
            protocolId = "",
        )
    }

    private fun buildSyncTempPath(name: String): String {
        val separator = PathUtils.getPathSeparator()
        val directory = PathUtils.getCachePath().trimEnd('/', '\\') + separator + "sync-stage"
        PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, directory)
        val safeName = name.ifBlank { "sync-item" }
        return directory + separator + "${Clock.System.now().toEpochMilliseconds()}-$safeName"
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

    private fun resolveShare(shareId: String): Share? {
        return deviceState.shares.firstOrNull { item -> item.id == shareId }
    }

    private fun ensureConnectedDevice(deviceId: String, label: String) {
        if (deviceId.isBlank()) return
        val isConnected = deviceState.socketDevices.any { device ->
            device.id == deviceId && device.hasActiveConnection()
        }
        if (!isConnected) {
            throw DeviceEndpointUnavailableException(
                AppStrings.ui_arg0_has_been_disconnected.format(arg0 = label)
            )
        }
    }

    private fun isParentPath(parentPath: String, childPath: String, separator: String): Boolean {
        val normalizedParent = normalizePath(parentPath, separator)
        val normalizedChild = normalizePath(childPath, separator)
        if (normalizedParent.isBlank() || normalizedChild.isBlank()) return false
        if (normalizedParent == normalizedChild) return false
        val prefix = if (normalizedParent.endsWith(separator)) normalizedParent else normalizedParent + separator
        return normalizedChild.startsWith(prefix)
    }

    private fun normalizePath(path: String, separator: String): String {
        var normalized = path.trim()
        if (separator == "/") {
            normalized = normalized.replace('\\', '/')
        } else if (separator == "\\") {
            normalized = normalized.replace('/', '\\')
        }
        while (normalized.length > separator.length && normalized.endsWith(separator)) {
            normalized = normalized.dropLast(separator.length)
        }
        return normalized
    }
}

internal suspend fun copyNetworkFileWithUnsupportedFallback(
    copyOnServer: suspend () -> Result<Boolean>,
    copyViaLocal: suspend () -> Result<Boolean>,
): Result<Boolean> {
    val serverResult = try {
        copyOnServer()
    } catch (unsupported: NetworkUnsupportedException) {
        Result.failure(unsupported)
    }
    return if (serverResult.exceptionOrNull() is NetworkUnsupportedException) {
        copyViaLocal()
    } else {
        serverResult
    }
}

internal suspend fun copyDeviceFileWithUnsupportedFallback(
    copyOnDevice: suspend () -> Result<Boolean>,
    copyViaLocal: suspend () -> Result<Boolean>,
): Result<Boolean> {
    val deviceResult = try {
        copyOnDevice()
    } catch (unsupported: DeviceCopyUnsupportedException) {
        Result.failure(unsupported)
    }
    return if (deviceResult.exceptionOrNull() is DeviceCopyUnsupportedException) {
        copyViaLocal()
    } else {
        deviceResult
    }
}

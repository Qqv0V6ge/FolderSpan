package com.folderspan.ui.state.file

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.ChunkReadableNetworkAccess
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.extensions.parentPath
import com.folderspan.ignore.ResolvedIgnoreMatcher
import com.folderspan.ignore.loadOperationIgnoreMatcher
import com.folderspan.ui.state.main.DeviceEndpointUnavailableException
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.ShareSessionUnavailableException
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskEndpointUnavailableException
import com.folderspan.ui.state.main.TaskType
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import strings.AppStrings

internal class FileOperationIgnoreResolver(
    private val database: FolderSpanDatabase,
    private val deviceState: DeviceState,
    private val currentDesk: () -> DiskBase,
    private val resolveDevice: (DiskBase?, String) -> Device?,
    private val resolveNetworkAccess: (DiskBase?, String) -> NetworkAccess?,
) {
    suspend fun resolveForSource(
        source: FileSimpleInfo,
        separator: String,
    ): ResolvedIgnoreMatcher? {
        return loadOperationIgnoreMatcher(
            database = database,
            protocol = source.protocol,
            protocolId = source.protocolId,
            path = source.path,
            separator = separator,
        ) { filePath ->
            readOperationIgnoreFileLines(
                protocol = source.protocol,
                protocolId = source.protocolId,
                filePath = filePath,
                separator = separator,
            )
        }
    }

    private suspend fun readOperationIgnoreFileLines(
        protocol: FileProtocol,
        protocolId: String,
        filePath: String,
        separator: String,
    ): Result<List<String>> {
        return when (protocol) {
            FileProtocol.Local -> withContext(Dispatchers.Default) {
                runCatching { FileUtils.readFileLines(FileAccessPermission.Allowed, filePath) }
            }

            FileProtocol.Device -> {
                val device = resolveDevice(currentDesk(), protocolId)
                    ?: return Result.failure(
                        DeviceEndpointUnavailableException(AppStrings.message_task_source_device_disconnected)
                    )
                device.files.readLines(filePath)
            }

            FileProtocol.Share -> readShareOperationIgnoreFileLines(protocolId, filePath, separator)
            FileProtocol.Network -> readNetworkOperationIgnoreFileLines(protocolId, filePath, separator)
        }
    }

    private suspend fun readShareOperationIgnoreFileLines(
        protocolId: String,
        filePath: String,
        separator: String,
    ): Result<List<String>> {
        val share = deviceState.shares.firstOrNull { item -> item.id == protocolId }
            ?: return Result.failure(
                ShareSessionUnavailableException(AppStrings.message_task_source_share_session_expired)
            )
        val file = resolveOperationIgnoreFileInfo(filePath, separator) { parent ->
            share.getFileList(parent)
        } ?: return Result.failure(IllegalStateException(AppStrings.operation_ignore_file_not_found))
        if (file.isDirectory || file.size < 0 || file.size > IGNORE_FILE_OPERATION_MAX_BYTES) {
            return Result.failure(IllegalArgumentException(AppStrings.operation_ignore_file_unreadable))
        }
        return share.readBytes(file.path, 0, file.size)
            .map { bytes -> bytes.decodeToString().lines() }
    }

    private suspend fun readNetworkOperationIgnoreFileLines(
        protocolId: String,
        filePath: String,
        separator: String,
    ): Result<List<String>> {
        val networkAccess = resolveNetworkAccess(currentDesk(), protocolId)
            ?: return Result.failure(
                TaskEndpointUnavailableException(AppStrings.message_task_source_network_disconnected)
            )
        val reader = networkAccess as? ChunkReadableNetworkAccess
            ?: return Result.failure(
                IllegalStateException(AppStrings.operation_ignore_network_read_not_supported)
            )
        val file = resolveOperationIgnoreFileInfo(filePath, separator) { parent ->
            networkAccess.getList(parent)
        } ?: return Result.failure(IllegalStateException(AppStrings.operation_ignore_file_not_found))
        if (file.isDirectory || file.size < 0 || file.size > IGNORE_FILE_OPERATION_MAX_BYTES) {
            return Result.failure(IllegalArgumentException(AppStrings.operation_ignore_file_unreadable))
        }

        val chunks = mutableListOf<ByteArray>()
        var totalBytes = 0
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
            protocol = file.protocol,
            protocolId = file.protocolId,
        )
        val result = reader.downloadFileByChunks(task, file) { chunk, _ ->
            totalBytes += chunk.size
            if (totalBytes > IGNORE_FILE_OPERATION_MAX_BYTES) {
                Result.failure(IllegalArgumentException(AppStrings.operation_ignore_file_unreadable))
            } else {
                chunks.add(chunk)
                Result.success(Unit)
            }
        }
        return result.map {
            val bytes = ByteArray(totalBytes)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(bytes, destinationOffset = offset)
                offset += chunk.size
            }
            bytes.decodeToString().lines()
        }
    }

    private suspend fun resolveOperationIgnoreFileInfo(
        filePath: String,
        separator: String,
        listParent: suspend (String) -> Result<List<FileSimpleInfo>>,
    ): FileSimpleInfo? {
        val parent = filePath.parentPath(separator)
        val name = filePath.substringAfterLast(separator)
        return listParent(parent).getOrNull()
            ?.firstOrNull { item -> item.name == name || item.path == filePath }
    }
}

package com.folderspan.ui.state.file

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.ChunkReadableNetworkAccess
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskType
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import strings.AppStrings

internal suspend fun readEndpointIgnoreFileLines(
    protocol: FileProtocol,
    file: FileSimpleInfo,
    device: Device? = null,
    networkAccess: NetworkAccess? = null,
): Result<List<String>> {
    if (file.isDirectory || file.isSymbolicLink || file.size !in 0..IGNORE_FILE_OPERATION_MAX_BYTES.toLong()) {
        return Result.failure(IllegalArgumentException(AppStrings.operation_ignore_file_unreadable))
    }
    return when (protocol) {
        FileProtocol.Local -> withContext(Dispatchers.Default) {
            try { Result.success(FileUtils.readFileLines(FileAccessPermission.Allowed, file.path)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
        }
        FileProtocol.Device -> device?.files?.readLines(file.path)
            ?: Result.failure(IllegalStateException(AppStrings.message_task_source_device_disconnected))
        FileProtocol.Share -> Result.failure(IllegalStateException(AppStrings.operation_ignore_network_read_not_supported))
        FileProtocol.Network -> {
            val reader = networkAccess as? ChunkReadableNetworkAccess
                ?: return Result.failure(IllegalStateException(AppStrings.operation_ignore_network_read_not_supported))
            val chunks = mutableListOf<ByteArray>()
            var total = 0
            val task = Task(taskType = TaskType.Copy, status = StatusEnum.LOADING, protocol = file.protocol, protocolId = file.protocolId)
            reader.downloadFileByChunks(task, file) { chunk, _ ->
                if (chunk.size > IGNORE_FILE_OPERATION_MAX_BYTES - total) {
                    Result.failure(IllegalArgumentException(AppStrings.operation_ignore_file_unreadable))
                } else {
                    chunks.add(chunk)
                    total += chunk.size
                    Result.success(Unit)
                }
            }.map {
                val bytes = ByteArray(total)
                var offset = 0
                for (chunk in chunks) { chunk.copyInto(bytes, offset); offset += chunk.size }
                bytes.decodeToString().lines()
            }
        }
    }
}

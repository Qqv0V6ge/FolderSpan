package com.folderspan.service.mcp.file

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.directoryBatches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal actual suspend fun localFileMetadata(path: String): Result<FileSimpleInfo> =
    FileUtils.getFile(FileAccessPermission.Allowed, path)

internal actual suspend fun openLocalFileDirectory(path: String, endpoint: FileEndpointRef, permissions: FileEndpointPermissions): FileEndpointDirectory {
    val channel = Channel<Result<FileEndpointEntry>>(128)
    val job = CoroutineScope(Dispatchers.Default).launch {
        try {
            directoryBatches(path, includeDirectoryEntryCount = false).collect { batch ->
                for (entry in batch.entries) channel.send(Result.success(entry.toLocalEndpointEntry(endpoint, permissions)))
                for (issue in batch.issues) channel.send(Result.failure(IllegalStateException(issue)))
            }
            channel.close()
        } catch (error: Throwable) {
            channel.close(error)
        }
    }
    return object : FileEndpointDirectory {
        override suspend fun next(): Result<FileEndpointEntry>? {
            val item = channel.receiveCatching()
            item.exceptionOrNull()?.let { throw it }
            return item.getOrNull()
        }
        override fun close() { job.cancel(); channel.cancel() }
    }
}

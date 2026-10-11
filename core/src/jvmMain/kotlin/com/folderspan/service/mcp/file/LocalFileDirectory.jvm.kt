package com.folderspan.service.mcp.file

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.extensions.toFileSimpleInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

internal actual suspend fun localFileMetadata(path: String): Result<FileSimpleInfo> =
    File(path).toFileSimpleInfo(includeDirectoryEntryCount = false).map { entry ->
        // Directory child counts are unknown until explicitly requested.
        if (entry.isDirectory) entry.withCopy(size = -1L) else entry
    }

internal actual suspend fun openLocalFileDirectory(
    path: String,
    endpoint: FileEndpointRef,
    permissions: FileEndpointPermissions,
): FileEndpointDirectory {
    val stream = Files.newDirectoryStream(File(path).toPath())
    try {
        val iterator = stream.iterator()
        return object : FileEndpointDirectory {
            override suspend fun next(): Result<FileEndpointEntry>? = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                if (!iterator.hasNext()) return@withContext null
                localFileMetadata(iterator.next().toString()).map { it.toLocalEndpointEntry(endpoint, permissions) }
            }

            override fun close() = stream.close()
        }
    } catch (error: Throwable) {
        stream.close()
        throw error
    }
}

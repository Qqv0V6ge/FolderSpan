package com.folderspan.service.mcp.file

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.extensions.getFileAndFolder
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils

internal actual suspend fun localFileMetadata(path: String): Result<FileSimpleInfo> =
    FileUtils.getFile(FileAccessPermission.Allowed, path)

internal actual suspend fun openLocalFileDirectory(
    path: String,
    endpoint: FileEndpointRef,
    permissions: FileEndpointPermissions,
): FileEndpointDirectory =
    // Web UI hosting is JVM-only. Preserve SAF/security-scope/privileged access on other platforms.
    path.getFileAndFolder(FileAccessPermission.Allowed).getOrThrow()
        .map { it.toLocalEndpointEntry(endpoint, permissions) }
        .asEndpointDirectory()

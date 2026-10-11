package com.folderspan.utils

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.service.webui.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

actual object PathUtils {
    actual suspend fun getFileAndFolder(permission: FileAccessPermission, path: String): Result<List<FileSimpleInfo>> =
        listDirectory(permission, path, includeDirectoryEntryCount = true).map { it.entries }

    actual suspend fun getFileAndFolderWithIssues(permission: FileAccessPermission, path: String): Result<FileListingResult> =
        listDirectory(permission, path, includeDirectoryEntryCount = false)

    private suspend fun listDirectory(permission: FileAccessPermission, path: String, includeDirectoryEntryCount: Boolean): Result<FileListingResult> = platformResult {
        requireFileAccess(permission)
        if (PrivateFileStorage.owns(path)) return@platformResult FileListingResult(PrivateFileStorage.getFileAndFolder(permission, path).getOrThrow())
        val entries = mutableListOf<FileSimpleInfo>()
        val issues = mutableListOf<FileListingIssue>()
        var issueCount = 0
        directoryBatches(path, includeDirectoryEntryCount).collect { batch ->
            entries += batch.entries
            issueCount += batch.issues.size
            batch.issues.take((FILE_LISTING_ISSUE_LIMIT - issues.size).coerceAtLeast(0)).forEach { issues += FileListingIssue(path, it) }
        }
        FileListingResult(entries, issueCount, issues)
    }

    actual suspend fun isSymbolicLink(permission: FileAccessPermission, path: String): Boolean {
        if (!hasFileAccess(permission)) return false
        return if (PrivateFileStorage.owns(path)) false else WebUiPlatformClient.get("/files/symlink?path=${encodeWebUiQuery(path)}")
    }

    actual suspend fun isPathWithinRoot(permission: FileAccessPermission, rootPath: String, targetPath: String, allowNonExistentLeaf: Boolean): Boolean {
        if (!hasFileAccess(permission)) return false
        if (PrivateFileStorage.owns(rootPath)) return isPathInsideRootLexically(rootPath, targetPath, "/") &&
            (PrivateFileStorage.exists(permission, targetPath) || allowNonExistentLeaf && PrivateFileStorage.exists(permission, targetPath.substringBeforeLast('/')))
        return WebUiPlatformClient.post("/files/boundary", WebUiPathBoundaryRequest(rootPath, targetPath, allowNonExistentLeaf))
    }

    actual suspend fun resolveCanonicalPath(permission: FileAccessPermission, path: String, allowNonExistentLeaf: Boolean): String? {
        if (!hasFileAccess(permission)) return null
        if (PrivateFileStorage.owns(path)) return if (PrivateFileStorage.exists(permission, path) || allowNonExistentLeaf) path else null
        return WebUiPlatformClient.get("/files/canonical?path=${encodeWebUiQuery(path)}&allowMissing=$allowNonExistentLeaf")
    }

    actual fun getAppPath(): String = checkNotNull(WebUiAttachment.hostInfo) { "Runtime login required" }.app
    actual fun getHomePath(): String = WebUiAttachment.homePath
    actual fun getCachePath(): String = WebUiAttachment.cachePath
    actual fun getPathSeparator(): String = WebUiAttachment.pathSeparator
    actual suspend fun getRootPaths(permission: FileAccessPermission): Result<List<PathInfo>> = platformResult {
        requireFileAccess(permission)
        WebUiPlatformClient.get("/files/roots")
    }

    actual fun traverse(permission: FileAccessPermission, path: String): Flow<Result<List<FileSimpleInfo>>> = flow {
        if (!hasFileAccess(permission)) {
            emit(deniedFileAccessResult())
            return@flow
        }
        val pending = ArrayDeque<String>().apply { add(path) }
        while (pending.isNotEmpty()) {
            val result = getFileAndFolder(permission, pending.removeLast())
            emit(result)
            if (result.isFailure) return@flow
            result.getOrThrow().filter { it.isDirectory && !it.isSymbolicLink }.forEach { pending.add(it.path) }
        }
    }

    actual suspend fun exists(permission: FileAccessPermission, path: String): Boolean {
        if (!hasFileAccess(permission)) return false
        return if (PrivateFileStorage.owns(path)) PrivateFileStorage.exists(permission, path) else WebUiPlatformClient.get("/files/exists?path=${encodeWebUiQuery(path)}")
    }
    actual suspend fun createDirectoryIfNotExists(permission: FileAccessPermission, path: String) {
        requireFileAccess(permission)
        if (PrivateFileStorage.owns(path)) PrivateFileStorage.createDirectoryIfNotExists(permission, path)
        else check(WebUiPlatformClient.post<WebUiPathRequest, Boolean>("/files/mkdirs", WebUiPathRequest(path)))
    }
    actual suspend fun deleteDirectory(permission: FileAccessPermission, path: String) {
        requireFileAccess(permission)
        if (PrivateFileStorage.owns(path)) PrivateFileStorage.deleteDirectory(permission, path)
        else WebUiPlatformClient.exchange("DELETE", "$WEB_UI_PLATFORM_PATH/files/tree?path=${encodeWebUiQuery(path)}")
    }
}

internal fun directoryBatches(path: String, includeDirectoryEntryCount: Boolean = true): Flow<WebUiDirectoryBatch> = flow {
    val handle = WebUiPlatformClient.post<WebUiPathRequest, WebUiHandle>("/files/directories?includeDirectoryEntryCount=$includeDirectoryEntryCount", WebUiPathRequest(path)).id
    var finished = false
    try {
        while (!finished) {
            val batch = WebUiPlatformClient.get<WebUiDirectoryBatch>("/files/directories?handle=${encodeWebUiQuery(handle)}")
            finished = batch.finished
            emit(batch)
        }
    } finally {
        if (!finished) withContext(NonCancellable) { runCatching { WebUiPlatformClient.release(handle) } }
    }
}

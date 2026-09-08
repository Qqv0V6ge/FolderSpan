package com.folderspan.utils

import android.os.Environment
import android.system.Os
import android.system.OsConstants
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.androidContext
import com.folderspan.exception.AuthorityException
import com.folderspan.extensions.toFileSimpleInfo
import com.folderspan.privileged.PrivilegedFileAccess
import com.folderspan.privileged.PrivilegedFileBackends
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.File.separator
import strings.AppStrings

actual object PathUtils {

    actual fun getFileAndFolder(permission: FileAccessPermission, path: String): Result<List<FileSimpleInfo>> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        if (isSymbolicLink(permission, path)) {
            return Result.failure(AuthorityException(AppStrings.ui_symbol_links_cannot_be_used_as_directory_access))
        }
        return withPrivilegedFallback(
            primary = { localGetFileAndFolder(path) },
            fallback = { client -> client.list(path) }
        )
    }

    actual fun getFileAndFolderWithIssues(permission: FileAccessPermission, path: String): Result<FileListingResult> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        if (isSymbolicLink(permission, path)) {
            return Result.failure(AuthorityException(AppStrings.ui_symbol_links_cannot_be_used_as_directory_access))
        }
        return withPrivilegedFallback(
            primary = { localGetFileAndFolderWithIssues(path) },
            fallback = { client ->
                client.list(path).map { entries -> FileListingResult(entries = entries) }
            }
        )
    }

    actual fun isSymbolicLink(permission: FileAccessPermission, path: String): Boolean =
        hasFileAccess(permission) && getPathMetadata(path).map { item -> item.isSymbolicLinkKnown && item.isSymbolicLink }
            .getOrDefault(false)

    actual fun isPathWithinRoot(
        permission: FileAccessPermission,
        rootPath: String,
        targetPath: String,
        allowNonExistentLeaf: Boolean,
    ): Boolean {
        if (!hasFileAccess(permission)) return false
        val root = normalizePathForSecurityBoundary(rootPath, separator) ?: return false
        val target = normalizePathForSecurityBoundary(targetPath, separator) ?: return false
        if (!isPathInsideRootLexically(root, target, separator) || !exists(permission, root)) return false
        val rootMetadata = getPathMetadata(root).getOrNull() ?: return false
        if (!rootMetadata.isSymbolicLinkKnown || rootMetadata.isSymbolicLink) return false

        val relativeComponents = target.removePrefix(root).trim('/').split('/').filter { it.isNotEmpty() }
        var current = root
        relativeComponents.forEachIndexed { index, component ->
            current = if (current == "/") "/$component" else "$current/$component"
            val metadata = getPathMetadata(current).getOrNull()
            if (metadata == null) {
                return allowNonExistentLeaf && index == relativeComponents.lastIndex && !exists(permission, current)
            }
            if (!metadata.isSymbolicLinkKnown || metadata.isSymbolicLink) return false
        }
        return true
    }

    actual fun resolveCanonicalPath(
        permission: FileAccessPermission,
        path: String,
        allowNonExistentLeaf: Boolean,
    ): String? {
        if (!hasFileAccess(permission)) return null
        return runCatching {
            val file = File(path)
            if (!file.exists()) {
                if (!allowNonExistentLeaf) return@runCatching null
                val parent = file.parentFile ?: return@runCatching null
                if (!parent.exists()) return@runCatching null
                return@runCatching File(parent.canonicalFile, file.name).path
            }
            file.canonicalPath
        }.getOrNull()
    }

    actual fun getAppPath(): String = androidContext().filesDir.absolutePath

    actual fun getHomePath(): String = Environment.getExternalStorageDirectory().absolutePath

    actual fun getCachePath(): String = androidContext().cacheDir.absolutePath

    actual fun getPathSeparator(): String = separator

    actual fun getRootPaths(permission: FileAccessPermission): Result<List<PathInfo>> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val localResult = localGetRootPaths()
        val pathInfoMap = linkedMapOf<String, PathInfo>()
        localResult.getOrDefault(emptyList()).forEach { item ->
            pathInfoMap[item.path] = item
        }

        val localRootPathInfo = resolveSystemRootPathInfo()
        pathInfoMap.putIfAbsent(SYSTEM_ROOT_PATH, localRootPathInfo)

        PrivilegedFileAccess.withFallback(
            primary = { Result.failure(AuthorityException(AppStrings.ui_no_permission_to_read_space_information)) },
            operation = { client ->
                val currentRoot = pathInfoMap[SYSTEM_ROOT_PATH] ?: localRootPathInfo
                val total = client.totalSpace(SYSTEM_ROOT_PATH).getOrDefault(currentRoot.totalSpace)
                val free = client.freeSpace(SYSTEM_ROOT_PATH).getOrDefault(currentRoot.freeSpace)
                Result.success(PathInfo(SYSTEM_ROOT_PATH, total, free))
            }
        ).getOrNull()?.let { pathInfo ->
            val currentRoot = pathInfoMap[SYSTEM_ROOT_PATH] ?: localRootPathInfo
            pathInfoMap[SYSTEM_ROOT_PATH] = PathInfo(
                SYSTEM_ROOT_PATH,
                pathInfo.totalSpace.takeIf { item -> item > 0 } ?: currentRoot.totalSpace,
                pathInfo.freeSpace.takeIf { item -> item >= 0 } ?: currentRoot.freeSpace
            )
        }

        return Result.success(pathInfoMap.values.toList())
    }

    actual fun traverse(permission: FileAccessPermission, path: String): Flow<Result<List<FileSimpleInfo>>> = flow {
        if (!hasFileAccess(permission)) {
            emit(deniedFileAccessResult())
            return@flow
        }
        PrivilegedFileBackends.beforeLocalIo()?.let { backend ->
            val client = backend.withClient { item -> Result.success(item) }.getOrNull()
            if (client == null) {
                emit(Result.failure(AuthorityException(AppStrings.ui_root_service_is_not_ready)))
                return@flow
            }
            traverseWithPrivilegedClient(path, client, this)
            return@flow
        }

        val localResult = runCatching {
            traverseLocally(permission, path, this)
        }
        val error = localResult.exceptionOrNull()
        if (error != null) {
            if (!error.isPermissionError()) {
                emit(Result.failure(error))
                return@flow
            }
            val client = PrivilegedFileBackends.preferredAuthorized()
                ?.withClient { item -> Result.success(item) }
                ?.getOrNull()
            if (client == null) {
                emit(Result.failure(error))
                return@flow
            }
            traverseWithPrivilegedClient(path, client, this)
        }
    }.flowOn(Dispatchers.IO)

    actual fun exists(permission: FileAccessPermission, path: String): Boolean {
        return hasFileAccess(permission) && PrivilegedFileAccess.withFallback(
            primary = {
                runCatching { File(path).exists() }.fold(
                    onSuccess = { exists -> Result.success(exists) },
                    onFailure = { error -> Result.failure(error) }
                )
            },
            operation = { client -> client.exists(path) }
        ).getOrElse { false }
    }

    actual fun createDirectoryIfNotExists(permission: FileAccessPermission, path: String) {
        requireFileAccess(permission)
        PrivilegedFileBackends.beforeLocalIo()?.let { backend ->
            val created = backend.withClient { client -> client.createDirectory(path) }
                .getOrElse { item -> throw item }
            if (!created) throw Exception(AppStrings.ui_failed_create_directory)
            return
        }

        val dir = File(path)
        if (dir.exists()) return
        try {
            if (dir.mkdirs() || dir.exists()) return
            val created = PrivilegedFileAccess.withFallback(
                primary = { Result.failure(AuthorityException(AppStrings.ui_no_permission_to_create_a_directory)) },
                operation = { client -> client.createDirectory(path) }
            )
                .getOrElse { item -> throw item }
            if (!created) throw Exception(AppStrings.ui_failed_create_directory)
        } catch (t: Throwable) {
            if (!t.isPermissionError()) throw t
            val created = PrivilegedFileAccess.withFallback(
                primary = { Result.failure(t) },
                operation = { client -> client.createDirectory(path) }
            )
                .getOrElse { item -> throw item }
            if (!created) throw Exception(AppStrings.ui_failed_create_directory)
        }
    }

    actual fun deleteDirectory(permission: FileAccessPermission, path: String) {
        requireFileAccess(permission)
        PrivilegedFileBackends.beforeLocalIo()?.let { backend ->
            val deleted = backend.withClient { client -> client.deleteDirectory(path) }
                .getOrElse { item -> throw item }
            if (!deleted) throw Exception(AppStrings.ui_failed_delete_directory)
            return
        }

        val dir = File(path)
        if (!dir.exists() && !isSymbolicLink(permission, path)) return
        try {
            if (deleteLocalDirectoryNoFollow(dir, permission) || (!dir.exists() && !isSymbolicLink(permission, path))) return
            val deleted = PrivilegedFileAccess.withFallback(
                primary = { Result.failure(AuthorityException(AppStrings.ui_no_permission_to_delete_directory)) },
                operation = { client -> client.deleteDirectory(path) }
            )
                .getOrElse { item -> throw item }
            if (!deleted) throw Exception(AppStrings.ui_failed_delete_directory)
        } catch (t: Throwable) {
            if (!t.isPermissionError()) throw t
            val deleted = PrivilegedFileAccess.withFallback(
                primary = { Result.failure(t) },
                operation = { client -> client.deleteDirectory(path) }
            )
                .getOrElse { item -> throw item }
            if (!deleted) throw Exception(AppStrings.ui_failed_delete_directory)
        }
    }

    private fun getPathMetadata(path: String): Result<FileSimpleInfo> = withPrivilegedFallback(
        primary = {
            runCatching {
                Os.lstat(path)
                File(path).toFileSimpleInfo().getOrThrow()
            }
        },
        fallback = { client -> client.getFile(path) },
    )

    private fun deleteLocalDirectoryNoFollow(file: File, permission: FileAccessPermission): Boolean {
        if (isSymbolicLink(permission, file.absolutePath)) return file.delete()
        if (!file.exists()) return true
        if (!file.isDirectory) return file.delete()
        val children = file.listFiles() ?: return false
        for (child in children) {
            if (!deleteLocalDirectoryNoFollow(child, permission)) return false
        }
        return file.delete()
    }
}

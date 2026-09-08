@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.folderspan.utils

import strings.AppStrings

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.exception.AuthorityException
import com.folderspan.exception.EmptyDataException
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import platform.Foundation.*

actual object PathUtils {
    private val fileManager = NSFileManager.defaultManager
    private const val OPEN_IN_PLACE_ROOT = "open://"

    actual fun getFileAndFolder(
        permission: FileAccessPermission,
        path: String,
    ): Result<List<FileSimpleInfo>> =
        getFileAndFolderWithIssues(permission, path).map { listing -> listing.entries }

    actual fun getFileAndFolderWithIssues(
        permission: FileAccessPermission,
        path: String,
    ): Result<FileListingResult> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val normalizedPath = path.trim()
        if (normalizedPath == OPEN_IN_PLACE_ROOT) {
            val registeredPaths = IosSecurityScopeStore.registeredPaths().asReversed()
            val entries = mutableListOf<FileSimpleInfo>()
            val issues = mutableListOf<FileListingIssue>()
            var issueCount = 0
            registeredPaths.forEach { registeredPath ->
                FileUtils.getFile(permission, registeredPath)
                    .onSuccess { entry -> entries += entry }
                    .onFailure { error ->
                        issueCount++
                        if (issues.size < FILE_LISTING_ISSUE_LIMIT) {
                            issues += FileListingIssue(registeredPath, error.toFileListingIssueMessage())
                        }
                    }
            }
            return if (entries.isEmpty() && issueCount == 0) {
                Result.failure(EmptyDataException())
            } else {
                Result.success(
                    FileListingResult(
                        entries = entries,
                        issueCount = issueCount,
                        issues = issues,
                    )
                )
            }
        }
        if (normalizedPath.isEmpty()) return Result.failure(AuthorityException(AppStrings.ui_directory_error))
        if (isSymbolicLink(permission, normalizedPath)) {
            return Result.failure(AuthorityException(AppStrings.ui_symbol_links_cannot_be_used_as_directory_access))
        }

        return IosSecurityScopeStore.withSecurityScopeIfNeeded(normalizedPath) {
            runCatching {
                memScoped {
                    val isDir = alloc<BooleanVar>()
                    val exists = fileManager.fileExistsAtPath(normalizedPath, isDir.ptr)
                    if (!exists || !isDir.value) {
                        throw AuthorityException(AppStrings.ui_the_directory_does_not_exist)
                    }
                    if (!fileManager.isReadableFileAtPath(normalizedPath)) throw AuthorityException(AppStrings.message_task_permission_denied)
                    val error = alloc<ObjCObjectVar<NSError?>>()
                    val contents =
                        fileManager.contentsOfDirectoryAtPath(normalizedPath, error.ptr)
                            ?: throw mapFoundationError(error.value, AppStrings.ui_failed_read_directory)
                    val entries = mutableListOf<FileSimpleInfo>()
                    val issues = mutableListOf<FileListingIssue>()
                    var issueCount = 0
                    contents.forEach { name ->
                        val fileName = name?.toString()?.trim().orEmpty()
                        if (fileName.isEmpty()) return@forEach
                        val fullPath =
                            if (normalizedPath.endsWith("/")) "$normalizedPath$fileName" else "$normalizedPath/$fileName"
                        FileUtils.getFile(permission, fullPath)
                            .onSuccess { entry -> entries += entry }
                            .onFailure { itemError ->
                                issueCount++
                                if (issues.size < FILE_LISTING_ISSUE_LIMIT) {
                                    issues += FileListingIssue(fullPath, itemError.toFileListingIssueMessage())
                                }
                            }
                    }
                    FileListingResult(
                        entries = entries,
                        issueCount = issueCount,
                        issues = issues,
                    )
                }
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.failure(mapPathError(it)) }
            )
        }
    }

    actual fun isSymbolicLink(permission: FileAccessPermission, path: String): Boolean =
        hasFileAccess(permission) && runCatching {
            fileManager.attributesOfItemAtPath(path, error = null)?.get(NSFileType) == NSFileTypeSymbolicLink
        }.getOrDefault(false)

    actual fun isPathWithinRoot(
        permission: FileAccessPermission,
        rootPath: String,
        targetPath: String,
        allowNonExistentLeaf: Boolean,
    ): Boolean {
        if (!hasFileAccess(permission)) return false
        val root = normalizePathForSecurityBoundary(rootPath, "/") ?: return false
        val target = normalizePathForSecurityBoundary(targetPath, "/") ?: return false
        if (
            !isPathInsideRootLexically(root, target, "/") ||
            !exists(permission, root) ||
            isSymbolicLink(permission, root)
        ) {
            return false
        }

        val relativeComponents = target.removePrefix(root).trim('/').split('/').filter { it.isNotEmpty() }
        var current = root
        relativeComponents.forEachIndexed { index, component ->
            current = if (current == "/") "/$component" else "$current/$component"
            if (isSymbolicLink(permission, current)) return false
            if (!exists(permission, current)) {
                return allowNonExistentLeaf && index == relativeComponents.lastIndex
            }
        }
        return true
    }

    actual fun resolveCanonicalPath(
        permission: FileAccessPermission,
        path: String,
        allowNonExistentLeaf: Boolean,
    ): String? {
        if (!hasFileAccess(permission)) return null
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == OPEN_IN_PLACE_ROOT) return null
        return IosSecurityScopeStore.withSecurityScopeIfNeeded(trimmed) {
            val nsPath = trimmed as NSString
            if (!fileManager.fileExistsAtPath(trimmed)) {
                if (!allowNonExistentLeaf) return@withSecurityScopeIfNeeded null
                val parent = nsPath.stringByDeletingLastPathComponent
                if (parent.isEmpty() || !fileManager.fileExistsAtPath(parent)) {
                    return@withSecurityScopeIfNeeded null
                }
                val realParent = (parent as NSString).stringByResolvingSymlinksInPath
                (realParent as NSString).stringByAppendingPathComponent(nsPath.lastPathComponent)
            } else {
                nsPath.stringByResolvingSymlinksInPath
            }
        }
    }

    actual fun getAppPath(): String = NSHomeDirectory()

    actual fun getHomePath(): String = NSHomeDirectory()

    actual fun getCachePath(): String = NSTemporaryDirectory()

    actual fun getPathSeparator(): String = "/"

    actual fun getRootPaths(permission: FileAccessPermission): Result<List<PathInfo>> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val home = getHomePath()
        val total = getFileSystemAttribute(home, NSFileSystemSize)
        val free = getFileSystemAttribute(home, NSFileSystemFreeSize)
        val roots = mutableListOf(PathInfo(home, total, free))
        if (IosSecurityScopeStore.hasRegisteredPaths()) {
            roots.add(PathInfo(OPEN_IN_PLACE_ROOT, 0, 0))
        }
        return Result.success(roots)
    }

    actual fun traverse(
        permission: FileAccessPermission,
        path: String,
    ): Flow<Result<List<FileSimpleInfo>>> = flow {
        if (!hasFileAccess(permission)) {
            emit(deniedFileAccessResult())
            return@flow
        }
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        pending.add(path)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val key = normalizePathForSecurityBoundary(current, "/") ?: current
            if (!visited.add(key)) continue
            val result = getFileAndFolder(permission, current)
            emit(result)
            result.getOrNull().orEmpty().forEach { item ->
                if (item.isDirectory && item.isSymbolicLinkKnown && !item.isSymbolicLink) {
                    pending.add(item.path)
                }
            }
        }
    }.flowOn(Dispatchers.Default)

    actual fun exists(permission: FileAccessPermission, path: String): Boolean {
        if (!hasFileAccess(permission)) return false
        val normalizedPath = path.trim()
        return normalizedPath == OPEN_IN_PLACE_ROOT || fileManager.fileExistsAtPath(normalizedPath)
    }

    actual fun createDirectoryIfNotExists(permission: FileAccessPermission, path: String) {
        requireFileAccess(permission)
        val normalizedPath = path.trim()
        if (normalizedPath == OPEN_IN_PLACE_ROOT) return
        if (normalizedPath.isEmpty()) throw AuthorityException(AppStrings.ui_directory_error)
        IosSecurityScopeStore.withSecurityScopeIfNeeded(normalizedPath) {
            if (fileManager.fileExistsAtPath(normalizedPath)) return@withSecurityScopeIfNeeded
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                val created = fileManager.createDirectoryAtPath(
                    normalizedPath,
                    true,
                    attributes = null,
                    error = error.ptr
                )
                if (!created) throw mapFoundationError(error.value, AppStrings.ui_failed_create_directory)
            }
        }
    }

    actual fun deleteDirectory(permission: FileAccessPermission, path: String) {
        requireFileAccess(permission)
        val normalizedPath = path.trim()
        if (normalizedPath == OPEN_IN_PLACE_ROOT) return
        if (normalizedPath.isEmpty()) throw AuthorityException(AppStrings.ui_directory_error)
        IosSecurityScopeStore.withSecurityScopeIfNeeded(normalizedPath) {
            if (!fileManager.fileExistsAtPath(normalizedPath)) return@withSecurityScopeIfNeeded
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                val removed = fileManager.removeItemAtPath(normalizedPath, error.ptr)
                if (!removed) throw mapFoundationError(error.value, AppStrings.ui_failed_delete_directory)
            }
        }
    }

    private fun mapFoundationError(error: NSError?, fallbackMessage: String): Throwable {
        val message = error?.localizedDescription?.takeIf { it.isNotBlank() } ?: fallbackMessage
        return if (
            message.contains("permission denied", ignoreCase = true) ||
            message.contains("operation not permitted", ignoreCase = true) ||
            message.contains("access denied", ignoreCase = true)
        ) {
            AuthorityException(AppStrings.message_task_permission_denied)
        } else {
            Exception(message)
        }
    }

    private fun mapPathError(throwable: Throwable): Throwable {
        if (throwable is AuthorityException || throwable is EmptyDataException) return throwable
        val message = throwable.message.orEmpty()
        return when {
            message.contains("permission denied", ignoreCase = true) ||
                message.contains("operation not permitted", ignoreCase = true) ||
                message.contains("access denied", ignoreCase = true) -> AuthorityException(AppStrings.message_task_permission_denied)

            throwable is Exception -> throwable
            else -> Exception(message.ifEmpty { AppStrings.ui_path_operation_failed })
        }
    }

    private fun getFileSystemAttribute(path: String, key: String?): Long {
        val resolvedKey = key ?: return 0L
        val attributes = fileManager.attributesOfFileSystemForPath(path, error = null) ?: return 0L
        val value = attributes[resolvedKey] as? NSNumber
        return value?.longLongValue ?: 0L
    }
}

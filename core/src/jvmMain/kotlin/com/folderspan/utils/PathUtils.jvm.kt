package com.folderspan.utils

import com.folderspan.cleanup.resolveDesktopApplicationDataDirectory
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.exception.AuthorityException
import com.folderspan.exception.EmptyDataException
import com.folderspan.extensions.toFileSimpleInfo
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.io.File.separator
import java.nio.file.FileVisitResult
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import strings.AppStrings

actual object PathUtils {
    // 获取目录下所有文件和文件夹
    actual fun getFileAndFolder(permission: FileAccessPermission, path: String): Result<List<FileSimpleInfo>> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        return listDirectory(path, includeDirectoryEntryCount = true, permission = permission)
            .map { listing -> listing.entries }
    }

    actual fun getFileAndFolderWithIssues(permission: FileAccessPermission, path: String): Result<FileListingResult> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        return listDirectory(path, includeDirectoryEntryCount = false, permission = permission)
    }

    private fun listDirectory(
        path: String,
        includeDirectoryEntryCount: Boolean,
        permission: FileAccessPermission,
    ): Result<FileListingResult> {
        if (path.isEmpty()) return Result.failure(AuthorityException(AppStrings.ui_directory_error))
        if (isSymbolicLink(permission, path)) return Result.failure(AuthorityException(AppStrings.ui_symbol_links_cannot_be_used_as_directory_access))
        val file = File(path)
        if (!file.exists()) return Result.failure(AuthorityException(AppStrings.ui_the_directory_does_not_exist))
        if (!file.canRead()) return Result.failure(AuthorityException(AppStrings.message_task_permission_denied))

        val listFiles = file.listFiles() ?: run {
            Napier.w { "getFileAndFolder listFiles=null: path=$path, isDirectory=${file.isDirectory}, exists=${file.exists()}, canRead=${file.canRead()}" }
            return Result.failure(EmptyDataException())
        }

        val entries = ArrayList<FileSimpleInfo>(listFiles.size)
        val issues = ArrayList<FileListingIssue>(minOf(listFiles.size, FILE_LISTING_ISSUE_LIMIT))
        var issueCount = 0
        listFiles.forEach { child ->
            runCatching {
                child.toFileSimpleInfo(includeDirectoryEntryCount = includeDirectoryEntryCount).getOrThrow()
            }
                .onSuccess { entry -> entries += entry }
                .onFailure { error ->
                    issueCount++
                    if (issues.size < FILE_LISTING_ISSUE_LIMIT) {
                        issues += FileListingIssue(child.absolutePath, error.toFileListingIssueMessage())
                    }
                }
        }
        return Result.success(
            FileListingResult(
                entries = entries,
                issueCount = issueCount,
                issues = issues,
            )
        )
    }

    actual fun isSymbolicLink(permission: FileAccessPermission, path: String): Boolean =
        hasFileAccess(permission) && runCatching { Files.isSymbolicLink(File(path).toPath()) }.getOrDefault(false)

    actual fun isPathWithinRoot(
        permission: FileAccessPermission,
        rootPath: String,
        targetPath: String,
        allowNonExistentLeaf: Boolean,
    ): Boolean = runCatching {
        if (!hasFileAccess(permission)) return@runCatching false
        val root = Paths.get(rootPath).toAbsolutePath().normalize()
        val target = Paths.get(targetPath).toAbsolutePath().normalize()
        if (
            !target.startsWith(root) ||
            !Files.exists(root, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(root)
        ) {
            return@runCatching false
        }
        val realRoot = root.toRealPath()
        val relativeComponents = root.relativize(target).toList()
        var current = root
        relativeComponents.forEachIndexed { index, component ->
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) return@runCatching false
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                val isAllowedMissingLeaf =
                    allowNonExistentLeaf && index == relativeComponents.lastIndex
                if (!isAllowedMissingLeaf) return@runCatching false
                val parent = current.parent ?: return@runCatching false
                return@runCatching parent.toRealPath().startsWith(realRoot)
            }
        }
        target.toRealPath().startsWith(realRoot)
    }.getOrDefault(false)

    actual fun resolveCanonicalPath(
        permission: FileAccessPermission,
        path: String,
        allowNonExistentLeaf: Boolean,
    ): String? = runCatching {
        if (!hasFileAccess(permission)) return@runCatching null
        val target = Paths.get(path).toAbsolutePath().normalize()
        val exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.exists(target)
        if (!exists) {
            if (!allowNonExistentLeaf) return@runCatching null
            val parent = target.parent ?: return@runCatching null
            if (!Files.exists(parent)) return@runCatching null
            return@runCatching parent.toRealPath().resolve(target.fileName).toString()
        }
        target.toRealPath().toString()
    }.getOrNull()

    // 获取用户目录
    actual fun getAppPath(): String = System.getProperty("user.dir")

    // 获取用户目录
    actual fun getHomePath(): String = System.getProperty("user.home")

    // 获取缓存目录
    actual fun getCachePath(): String {
        val root = resolveDesktopApplicationDataDirectory().resolve("cache")
        Files.createDirectories(root)
        restrictOwnerOnlyPath(root, directory = true)
        return root.toString()
    }

    // 获取路径分隔符
    actual fun getPathSeparator(): String = separator

    // 获取根目录
    actual fun getRootPaths(permission: FileAccessPermission): Result<List<PathInfo>> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        return try {
            val rootCandidates = linkedMapOf<String, FileStoreInfo>()

            File.listRoots()?.forEach { item ->  item.tryAddAsRoot(rootCandidates) }

            discoverMountedRoots().forEach { item ->  item.tryAddAsRoot(rootCandidates) }

            FileSystems.getDefault().rootDirectories.forEach { rootPath ->
                runCatching {
                    if (Files.isDirectory(rootPath) && Files.isReadable(rootPath)) {
                        val file = rootPath.toFile()
                        val store = runCatching { Files.getFileStore(rootPath) }.getOrNull()
                        file.tryAddAsRoot(rootCandidates, store)
                    }
                }
            }

            if (rootCandidates.isEmpty()) {
                val home = System.getProperty("user.home")
                val fallback = File(home)
                val total = fallback.totalSpace
                val free = fallback.freeSpace
                rootCandidates[home] = FileStoreInfo(home, total, free)
            }

            Result.success(rootCandidates.values.map { info ->
                PathInfo(info.path, info.totalSpace, info.freeSpace)
            })
        } catch (e: SecurityException) {
            Result.failure(AuthorityException(AppStrings.ui_no_permission_to_access_directory))
        } catch (e: Exception) {
            Result.failure(Exception(AppStrings.ui_unable_to_retrieve_the_root_directory_arg0.format(arg0 = (e.message ?: AppStrings.ui_unknown_error))))
        }
    }

    // 遍历目录
    actual fun traverse(permission: FileAccessPermission, path: String): Flow<Result<List<FileSimpleInfo>>> = flow {
        if (!hasFileAccess(permission)) {
            emit(deniedFileAccessResult())
            return@flow
        }
        if (path.isEmpty()) {
            emit(Result.failure(AuthorityException(AppStrings.ui_path_error)))
            return@flow
        }

        val pending = ArrayDeque<File>()
        val visited = mutableSetOf<String>()
        pending.add(File(path))
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            val directoryPath = directory.toPath()
            val key = directoryPath.toAbsolutePath().normalize().toString()
            if (!visited.add(key)) continue

            if (!Files.exists(directoryPath, LinkOption.NOFOLLOW_LINKS)) {
                emit(Result.failure(AuthorityException(AppStrings.ui_the_directory_does_not_exist)))
                continue
            }
            if (!directory.canRead()) {
                emit(Result.failure(AuthorityException(AppStrings.ui_no_permission_to_read_the_directory)))
                continue
            }

            try {
                if (Files.isSymbolicLink(directoryPath) || !directory.isDirectory) {
                    emit(Result.success(listOf(directory.toFileSimpleInfo().getOrThrow())))
                    continue
                }

                val files = directory.listFiles() ?: run {
                    Napier.w { "traverse listFiles=null: path=${directory.path}, isDirectory=${directory.isDirectory}, exists=${directory.exists()}, canRead=${directory.canRead()}" }
                    throw EmptyDataException()
                }
                val fileList = files.mapNotNull { file ->
                    runCatching { file.toFileSimpleInfo().getOrThrow() }
                        .onFailure { error -> Napier.e { AppStrings.ui_file_processing_failed_arg0.format(arg0 = (error.message).toString()) } }
                        .getOrNull()
                }
                emit(Result.success(fileList))
                files.forEach { file ->
                    if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
                        pending.add(file)
                    }
                }
            } catch (e: SecurityException) {
                emit(Result.failure(AuthorityException(AppStrings.ui_no_permission_to_access_directory)))
            } catch (e: Exception) {
                emit(Result.failure(Exception(AppStrings.ui_unknown_error_arg0.format(arg0 = (e.message).toString()))))
            }
        }
    }.flowOn(Dispatchers.IO)

    actual fun exists(permission: FileAccessPermission, path: String): Boolean =
        hasFileAccess(permission) && File(path).exists()

    actual fun createDirectoryIfNotExists(permission: FileAccessPermission, path: String) {
        requireFileAccess(permission)
        val dir = File(path)
        if (!dir.exists()) {
            if (!dir.mkdirs() && !dir.exists()) {
                throw Exception(AppStrings.ui_failed_create_directory)
            }
        }
        restrictOwnerOnlyPath(dir.toPath(), directory = true)
    }

    actual fun deleteDirectory(permission: FileAccessPermission, path: String) {
        requireFileAccess(permission)
        val target = File(path).toPath()
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

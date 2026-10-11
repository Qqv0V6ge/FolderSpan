package com.folderspan.utils

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.folderspan.androidContext
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.exception.AuthorityException
import com.folderspan.exception.EmptyDataException
import com.folderspan.extensions.toFileSimpleInfo
import com.folderspan.privileged.PrivilegedFileClient
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.FlowCollector
import java.io.File
import java.util.*
import kotlin.time.Clock
import strings.AppStrings

const val SYSTEM_ROOT_PATH = "/"

private fun Uri.isShareTopLevelEntry(): Boolean {
    if (scheme != "content") return true
    if (host != "bundle") return true
    val segments = pathSegments.filter { item -> item.isNotBlank() }
    return segments.size <= 1
}

fun String?.normalizeRootPath(): String {
    if (this.isNullOrBlank()) return ""
    val trimmed = trimEnd('/')
    return trimmed.ifEmpty { SYSTEM_ROOT_PATH }
}

fun File.extractVolumeRoot(): String {
    val abs = absolutePath
    val idx = abs.indexOf("/Android/")
    return if (idx > 0) abs.take(idx) else abs.normalizeRootPath()
}

fun StorageVolume.isMounted(): Boolean {
    val currentState = state
    return currentState == Environment.MEDIA_MOUNTED || currentState == Environment.MEDIA_MOUNTED_READ_ONLY
}

@SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi")
fun StorageVolume.resolveDirectoryPath(): String? {
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> directory?.absolutePath
        else -> runCatching {
            javaClass.getMethod("getPath").invoke(this) as? String
        }.getOrNull() ?: runCatching {
            javaClass.getDeclaredField("mPath").apply { isAccessible = true }.get(this) as? String
        }.getOrNull()
    }?.normalizeRootPath()
}

fun resolveSystemRootPathInfo(
    defaultTotalSpace: Long = 0L,
    defaultFreeSpace: Long = 0L
): PathInfo {
    val root = File(SYSTEM_ROOT_PATH)
    val totalSpace = runCatching { root.totalSpace }
        .getOrDefault(defaultTotalSpace)
        .coerceAtLeast(0L)
    val freeSpace = runCatching { root.freeSpace }
        .getOrDefault(defaultFreeSpace)
        .coerceAtLeast(0L)
    return PathInfo(
        path = SYSTEM_ROOT_PATH,
        totalSpace = totalSpace,
        freeSpace = freeSpace
    )
}

fun localGetFileAndFolder(path: String): Result<List<FileSimpleInfo>> =
    localListDirectory(path, includeDirectoryEntryCount = true).map { listing -> listing.entries }

fun localGetFileAndFolderWithIssues(path: String): Result<FileListingResult> =
    localListDirectory(path, includeDirectoryEntryCount = false)

private fun localListDirectory(
    path: String,
    includeDirectoryEntryCount: Boolean,
): Result<FileListingResult> {
    if (path == "content://") {
        return Result.success(
            FileListingResult(
                entries = SharedUriFileRegistry.entries()
                    .filter { (uri, _) -> uri.isShareTopLevelEntry() }
                    .map { (uri, files) ->
                        FileSimpleInfo(
                            name = uri.path?.split(PathUtils.getPathSeparator())?.last().orEmpty(),
                            description = uri.host.orEmpty(),
                            isDirectory = files.isNotEmpty(),
                            isHidden = false,
                            path = uri.toString(),
                            mineType = "file",
                            size = files.size.toLong(),
                            createdDate = Clock.System.now().toEpochMilliseconds(),
                            updatedDate = Clock.System.now().toEpochMilliseconds(),
                            protocol = FileProtocol.Share,
                            protocolId = SYSTEM_SHARE_DESK_ID,
                        )
                    }
            )
        )
    }
    SharedUriFileRegistry.findByPath(path)?.let { item ->
        return Result.success(FileListingResult(entries = item))
    }
    if (path.isEmpty()) return Result.failure(AuthorityException(AppStrings.ui_directory_error))

    val file = File(path)
    if (!file.exists()) return Result.failure(AuthorityException(AppStrings.ui_the_directory_does_not_exist))
    if (!file.canRead()) return Result.failure(AuthorityException(AppStrings.message_task_permission_denied))

    val listFiles = file.listFiles() ?: run {
        Napier.w { "localGetFileAndFolder listFiles=null: path=$path, isDirectory=${file.isDirectory}, exists=${file.exists()}, canRead=${file.canRead()}" }
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

fun localGetRootPaths(): Result<List<PathInfo>> = try {
    val ctx = androidContext()
    val discovered = linkedMapOf<String, PathInfo>()

    fun tryAddRoot(path: String?, statSource: String? = path) {
        val normalized = path.normalizeRootPath()
        if (normalized.isEmpty() || discovered.containsKey(normalized)) return
        val statTarget = statSource
            ?.takeIf { item -> item.isNotBlank() }
            ?: path?.takeIf { item -> item.isNotBlank() }
            ?: return
        runCatching {
            val stat = StatFs(statTarget)
            discovered[normalized] = PathInfo(
                path = normalized,
                totalSpace = stat.totalBytes,
                freeSpace = stat.availableBytes
            )
        }
    }

    val storageManager = ctx.getSystemService(StorageManager::class.java)
    storageManager?.storageVolumes
        ?.filter { item -> item.isMounted() }
        ?.forEach { volume ->
            val rootPath = volume.resolveDirectoryPath()
            if (!rootPath.isNullOrEmpty()) {
                @Suppress("DEPRECATION")
                val statSource = if (volume.isPrimary) {
                    Environment.getExternalStorageDirectory().absolutePath
                } else {
                    rootPath
                }
                tryAddRoot(rootPath, statSource)
            }
        }

    ctx.getExternalFilesDirs(null)?.forEach { dir ->
        dir?.let { item ->
            val rootPath = item.extractVolumeRoot()
            tryAddRoot(rootPath, item.absolutePath)
        }
    }

    if (discovered.isEmpty()) {
        @Suppress("DEPRECATION")
        val primary = Environment.getExternalStorageDirectory()
        tryAddRoot(primary.absolutePath, primary.absolutePath)
        if (discovered.isEmpty()) {
            val filesDir = ctx.filesDir
            tryAddRoot(filesDir.absolutePath, filesDir.absolutePath)
        }
    }

    Result.success(discovered.values.toList())
} catch (_: SecurityException) {
    Result.failure(AuthorityException(AppStrings.ui_no_permission_to_access_directory))
} catch (e: Exception) {
    Result.failure(Exception(e.message))
}

suspend fun traverseLocally(
    permission: FileAccessPermission,
    path: String,
    collector: FlowCollector<Result<List<FileSimpleInfo>>>
) {
    if (path.isEmpty()) {
        collector.emit(Result.failure(AuthorityException(AppStrings.ui_path_error)))
        return
    }

    val pending = ArrayDeque<File>()
    val visited = mutableSetOf<String>()
    pending.add(File(path))
    while (pending.isNotEmpty()) {
        val directory = pending.removeFirst()
        val key = directory.absoluteFile.normalize().path
        if (!visited.add(key)) continue

        val isSymbolicLink = PathUtils.isSymbolicLink(permission, directory.path)
        if (!directory.exists() && !isSymbolicLink) {
            collector.emit(Result.failure(AuthorityException(AppStrings.ui_the_directory_does_not_exist)))
            continue
        }
        if (isSymbolicLink || !directory.isDirectory) {
            val info = directory.toFileSimpleInfo().getOrElse { item -> throw item }
            collector.emit(Result.success(listOf(info)))
            continue
        }
        if (!directory.canRead()) {
            collector.emit(Result.failure(AuthorityException(AppStrings.ui_no_permission_to_read_the_directory)))
            continue
        }

        val files = directory.listFiles() ?: throw AuthorityException(AppStrings.ui_no_permission_to_access_directory)
        val fileList = files.mapNotNull { file ->
            runCatching { file.toFileSimpleInfo().getOrThrow() }
                .onFailure { error -> Napier.e(error) { AppStrings.ui_file_processing_failed_arg0.format(arg0 = (error.message).toString()) } }
                .getOrNull()
        }
        collector.emit(Result.success(fileList))
        files.forEach { file ->
            if (file.isDirectory && !PathUtils.isSymbolicLink(permission, file.path)) {
                pending.add(file)
            }
        }
    }
}

suspend fun traverseWithPrivilegedClient(
    rootPath: String,
    client: PrivilegedFileClient,
    collector: FlowCollector<Result<List<FileSimpleInfo>>>
) {
    val rootInfo = client.getFile(rootPath).getOrNull()
    if (rootInfo?.isSymbolicLink == true) {
        collector.emit(Result.success(listOf(rootInfo)))
        return
    }
    val queue = ArrayDeque<String>()
    val visited = mutableSetOf<String>()
    queue.add(rootPath)

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!visited.add(current)) continue

        val entriesResult = client.list(current)
        if (entriesResult.isFailure) {
            val singleResult = client.getFile(current)
            if (singleResult.isSuccess) {
                collector.emit(Result.success(listOf(singleResult.getOrNull()!!)))
            } else {
                collector.emit(entriesResult)
            }
            continue
        }
        val entries = entriesResult.getOrNull().orEmpty()
        if (entries.isEmpty()) {
            collector.emit(Result.success(emptyList()))
            continue
        }
        collector.emit(Result.success(entries))
        entries.filter { item ->
            item.isDirectory && item.isSymbolicLinkKnown && !item.isSymbolicLink
        }.forEach { item -> queue.add(item.path) }
    }
}

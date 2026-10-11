package com.folderspan.utils

import io.github.aakira.napier.Napier
import java.io.File
import java.nio.file.FileStore
import java.nio.file.Files
import strings.AppStrings

internal data class FileStoreInfo(
    val path: String,
    val totalSpace: Long,
    val freeSpace: Long
)

private val mountBasePaths = listOf("/Volumes", "/media", "/run/media", "/mnt")

internal fun discoverMountedRoots(): List<File> {
    val osName = System.getProperty("os.name")?.lowercase() ?: ""
    val candidates = mutableSetOf<File>()

    val pathsToCheck = when {
        osName.contains("mac") || osName.contains("darwin") -> listOf("/Volumes")
        osName.contains("linux") -> listOf("/media", "/run/media", "/mnt")
        else -> mountBasePaths
    }

    pathsToCheck.forEach { basePath ->
        val base = File(basePath)
        if (!base.isReadableDirectory()) return@forEach

        base.listFiles()
            ?.filter { item ->  item.isReadableDirectory() }
            ?.forEach { candidate ->
                gatherMountedRoots(candidate, candidates)
            }
    }

    return candidates.mapNotNull { file ->
        runCatching { file.canonicalFile }.getOrNull()
    }
}

internal fun gatherMountedRoots(directory: File, collector: MutableSet<File>, depth: Int = 0) {
    if (!directory.isReadableDirectory()) return

    if (directory.isMountPoint()) {
        collector += directory
        return
    }

    if (depth >= 1) return

    directory.listFiles()
        ?.filter { item ->  item.isReadableDirectory() }
        ?.forEach { child ->
            gatherMountedRoots(child, collector, depth + 1)
        }
}

internal fun File.isReadableDirectory(): Boolean = exists() && isDirectory && canRead()

internal fun File.isMountPoint(): Boolean {
    val parentDir = parentFile ?: return false
    return try {
        val parentStore = Files.getFileStore(parentDir.toPath())
        val currentStore = Files.getFileStore(toPath())
        parentStore != currentStore || parentStore.name() != currentStore.name()
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }
}

internal fun File.tryAddAsRoot(
    target: MutableMap<String, FileStoreInfo>,
    store: FileStore? = null
) {
    try {
        if (!isReadableDirectory()) return
        val canonicalDir = runCatching { canonicalFile }.getOrElse { this }
        if (!canonicalDir.isReadableDirectory()) return
        val normalized = canonicalDir.normalizeRoot()

        val totalFromStore = store?.let { fileStore -> runCatching { fileStore.totalSpace }.getOrNull() }
        val freeFromStore = store?.let { fileStore -> runCatching { fileStore.usableSpace }.getOrNull() }
        val info = FileStoreInfo(
            path = normalized,
            totalSpace = totalFromStore ?: canonicalDir.totalSpace,
            freeSpace = freeFromStore ?: canonicalDir.freeSpace
        )

        target.putIfAbsent(normalized, info)
    } catch (_: SecurityException) {
        // 忽略需要权限的路径
    } catch (e: Exception) {
        Napier.w(e) { AppStrings.ui_ignore_root_directory_arg0.format(arg0 = (path).toString()) }
    }
}

internal fun File.normalizeRoot(): String {
    val canonical = runCatching { canonicalPath }.getOrElse { path }
    if (canonical == File.separator) return canonical

    val trimmed = canonical.trimEnd('/', '\\')
    if (trimmed.isEmpty()) return File.separator

    return if (trimmed.length == 2 && trimmed[1] == ':') {
        "$trimmed${File.separator}"
    } else {
        trimmed
    }
}

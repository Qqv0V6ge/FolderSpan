package com.folderspan.ui.state.main

import com.folderspan.utils.iosApplicationSupportRoot
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

internal actual fun resolveSyncTaskStoreDirectory(legacyDirectory: String): String =
    IosSyncTaskStorage.prepareDirectory(legacyDirectory.toPath()).toString()

internal object IosSyncTaskStorage {
    private fun directory(home: Path): Path = iosApplicationSupportRoot(home) / SYNC_TASK_STORE_DIR_NAME

    private fun stagingDirectory(home: Path): Path =
        iosApplicationSupportRoot(home) / "$SYNC_TASK_STORE_DIR_NAME-migration"

    fun prepareDirectory(legacy: Path): Path {
        val home = requireNotNull(legacy.parent)
        val destination = directory(home)
        val files = FileSystem.SYSTEM
        if (files.exists(destination)) return destination
        val legacyMetadata = files.metadataOrNull(legacy) ?: return destination
        check(legacyMetadata.isDirectory && legacyMetadata.symlinkTarget == null) { "Invalid legacy sync task directory" }

        // 容器根目录可能无法写入，先复制旧任务和历史记录，再原子发布新目录。
        // 中断后可从旧目录重试，已启用的新目录优先，不会读回过期数据。
        val staging = stagingDirectory(home)
        files.deleteRecursively(staging)
        files.createDirectories(staging)
        files.listRecursively(legacy).forEach { entry ->
            val target = staging / entry.relativeTo(legacy)
            val metadata = files.metadata(entry)
            when {
                metadata.isDirectory -> files.createDirectories(target)
                metadata.isRegularFile -> files.copy(entry, target)
            }
        }
        files.atomicMove(staging, destination)
        return destination
    }

    fun clear(home: Path) {
        val files = FileSystem.SYSTEM
        val legacy = home / SYNC_TASK_STORE_DIR_NAME
        // 先清除旧数据，避免新目录删除后又导入旧任务。
        try {
            files.deleteRecursively(legacy)
        } catch (error: IOException) {
            // 容器根目录不可写时可以保留已清空的旧目录。
            val metadata = files.metadataOrNull(legacy)
            if (metadata?.isDirectory != true || metadata.symlinkTarget != null || files.list(legacy).isNotEmpty()) {
                throw error
            }
        }
        files.deleteRecursively(directory(home))
        files.deleteRecursively(stagingDirectory(home))
    }
}

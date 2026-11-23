package com.folderspan.utils

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** 统一 iOS 数据库的打开路径和清理范围。 */
object IosDatabaseStorage {
    const val databaseName = "folderspan.db"

    private val recoveryFiles = listOf("$databaseName-wal", "$databaseName-journal")
    private val databaseFiles = listOf(databaseName, "$databaseName-shm") + recoveryFiles

    private fun storageDirectory(home: Path): Path =
        home / "Library" / "Application Support" / "FolderSpan"

    private fun databaseDirectory(home: Path): Path = storageDirectory(home) / "database"

    private fun migrationDirectory(home: Path): Path = storageDirectory(home) / "database-migration"

    fun prepareDirectory(): String = prepareDirectory(PathUtils.getAppPath().toPath()).toString()

    internal fun prepareDirectory(home: Path): Path {
        val fileSystem = FileSystem.SYSTEM
        val destination = databaseDirectory(home)
        if (fileSystem.exists(destination / databaseName) || !fileSystem.exists(home / databaseName)) {
            fileSystem.createDirectories(destination)
            protectIosPrivatePath(destination.toString())
            return destination
        }

        // 先复制到同卷的临时目录，再整体切换。中断后可重试，旧库和恢复文件始终保留。
        val staging = migrationDirectory(home)
        fileSystem.deleteRecursively(staging)
        fileSystem.createDirectories(staging)
        protectIosPrivatePath(staging.toString())
        fileSystem.copy(home / databaseName, staging / databaseName)
        recoveryFiles.forEach { name ->
            val legacyFile = home / name
            if (fileSystem.exists(legacyFile)) fileSystem.copy(legacyFile, staging / name)
        }
        // SHM 是可重建的 WAL 索引。由 SQLite 在打开新路径时重新创建。
        fileSystem.atomicMove(staging, destination)
        return destination
    }

    fun pathsForCleanup(appPath: String): List<String> {
        // 一并清理旧库与迁移临时目录，防止重启后再次导入已清理的数据。
        val home = appPath.toPath()
        return (databaseFiles.map { home / it } + databaseDirectory(home) + migrationDirectory(home))
            .map(Path::toString)
    }

    fun protectDatabaseFiles(directory: String) {
        val root = directory.toPath()
        databaseFiles.forEach { name -> protectIosPrivatePath((root / name).toString()) }
    }
}

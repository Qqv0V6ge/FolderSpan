package com.folderspan.utils

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosDatabaseStorageTest {
    private val files = FileSystem.SYSTEM
    private val databaseName = IosDatabaseStorage.databaseName

    @Test
    fun freshInstallCreatesPrivateDatabaseDirectory() = withTemporaryHome { home ->
        val directory = IosDatabaseStorage.prepareDirectory(home)

        assertEquals(home / "Library" / "Application Support" / "FolderSpan" / "database", directory)
        assertTrue(files.metadata(directory).isDirectory)
        assertFalse(files.exists(home / databaseName))
    }

    @Test
    fun migrationRetriesAndDoesNotOverwriteAnAlreadyMigratedDatabase() = withTemporaryHome { home ->
        val legacyDatabase = home / databaseName
        val staging = home / "Library" / "Application Support" / "FolderSpan" / "database-migration"
        files.write(legacyDatabase) { writeUtf8("旧数据库") }
        files.write(home / "$databaseName-wal") { writeUtf8("未合并事务") }
        files.write(home / "$databaseName-shm") { writeUtf8("旧索引") }
        files.createDirectories(staging)
        // 模拟上一次迁移在复制完成前中断。
        files.write(staging / databaseName) { writeUtf8("不完整的旧副本") }

        val directory = IosDatabaseStorage.prepareDirectory(home)
        val database = directory / databaseName
        assertEquals("旧数据库", files.read(database) { readUtf8() })
        assertEquals("未合并事务", files.read(directory / "$databaseName-wal") { readUtf8() })
        assertFalse(files.exists(directory / "$databaseName-shm"))
        assertFalse(files.exists(staging))
        assertTrue(files.exists(legacyDatabase))
        assertTrue(
            setOf(legacyDatabase, directory, staging).all { path ->
                path.toString() in IosDatabaseStorage.pathsForCleanup(home.toString())
            }
        )

        files.write(database) { writeUtf8("迁移后的新数据") }
        assertEquals(directory, IosDatabaseStorage.prepareDirectory(home))
        assertEquals("迁移后的新数据", files.read(database) { readUtf8() })
    }

    private fun withTemporaryHome(test: (Path) -> Unit) {
        val home = NSTemporaryDirectory().toPath() / "folderspan-db-${NSUUID().UUIDString}"
        files.createDirectories(home)
        try {
            test(home)
        } finally {
            files.deleteRecursively(home)
        }
    }
}

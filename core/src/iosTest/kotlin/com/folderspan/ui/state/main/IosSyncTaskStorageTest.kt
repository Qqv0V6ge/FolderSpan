@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.folderspan.ui.state.main

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.S_IRUSR
import platform.posix.S_IRWXU
import platform.posix.S_IXUSR
import platform.posix.chmod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosSyncTaskStorageTest {
    private val files = FileSystem.SYSTEM

    @Test
    fun savesAndReloadsManualTaskWhenContainerRootIsReadOnly() = withTemporaryHome { home ->
        files.createDirectories(home / "Library")
        val legacy = home / SYNC_TASK_STORE_DIR_NAME
        // 将容器根目录设为只读，复现构建 14 的写入条件。
        assertEquals(0, chmod(home.toString(), (S_IRUSR or S_IXUSR).toUShort()))
        try {
            assertFails { TempSyncTaskStore(legacy.toString()).saveTask(manualTask()) }
            val directory = IosSyncTaskStorage.prepareDirectory(legacy)
            val saved = TempSyncTaskStore(directory.toString()).saveTask(manualTask())

            assertEquals(1L, saved.id)
            assertEquals(home / "Library" / "Application Support" / "FolderSpan" / "sync-tasks", directory)
            assertEquals(listOf(saved), TempSyncTaskStore(directory.toString()).loadTasks())
            assertFalse(files.exists(legacy))
        } finally {
            assertEquals(0, chmod(home.toString(), S_IRWXU.toUShort()))
        }
    }

    @Test
    fun migratesTasksAndRunHistoryWithoutOverwritingNewDataOnRetry() = withTemporaryHome { home ->
        val legacy = home / SYNC_TASK_STORE_DIR_NAME
        val oldStore = TempSyncTaskStore(legacy.toString())
        val task = oldStore.saveTask(manualTask())
        val run = SyncRunRecord(
            runId = 7, taskId = task.id, trigger = "manual", startedAt = 10, endedAt = 20,
            status = SyncRunStatus.Success, totalCount = 1, successCount = 1, failureCount = 0,
        )
        oldStore.appendRun(run)
        val support = home / "Library" / "Application Support" / "FolderSpan"
        val staging = support / "sync-tasks-migration"
        files.createDirectories(staging)
        // 模拟迁移在完成复制前被中断，残留文件不会进入新仓库。
        files.write(staging / "incomplete") { writeUtf8("未完成") }

        val destination = IosSyncTaskStorage.prepareDirectory(legacy)
        val store = TempSyncTaskStore(destination.toString())
        assertEquals(listOf(task), store.loadTasks())
        assertEquals(listOf(run), store.loadRuns(task.id))
        assertEquals(task.id + 1, store.saveTask(manualTask().copy(name = "新任务")).id)
        assertFalse(files.exists(destination / "incomplete"))
        assertFalse(files.exists(staging))
        assertTrue(files.exists(legacy))
        store.deleteTask(task.id)
        val reopened = TempSyncTaskStore(IosSyncTaskStorage.prepareDirectory(legacy).toString())
        assertEquals(listOf("新任务"), reopened.loadTasks().map { it.name })
        assertTrue(reopened.loadRuns(task.id).isEmpty())

        // 即使无法移除容器根目录下的旧目录，也应清除内容和新目录。
        files.createDirectories(staging)
        assertEquals(0, chmod(home.toString(), (S_IRUSR or S_IXUSR).toUShort()))
        try {
            IosSyncTaskStorage.clear(home)
            assertTrue(files.list(legacy).isEmpty())
            assertFalse(files.exists(destination))
            assertFalse(files.exists(staging))
        } finally {
            assertEquals(0, chmod(home.toString(), S_IRWXU.toUShort()))
        }
    }

    private fun manualTask() = SyncTask(
        id = 0, name = "Review Manual Sync",
        sourceType = SyncEndpointType.Local, sourcePath = "/Review Demo",
        targetType = SyncEndpointType.Local, targetPath = "/Review Backup",
        scheduleType = SyncScheduleType.Manual, mode = SyncMode.Update,
    )

    private fun withTemporaryHome(test: (Path) -> Unit) {
        val home = NSTemporaryDirectory().toPath() / "folderspan-sync-${NSUUID().UUIDString}"
        files.createDirectories(home)
        try {
            test(home)
        } finally {
            files.deleteRecursively(home)
        }
    }
}

package com.folderspan.ui.state.main

import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.service.webui.WebUiMode
import com.folderspan.utils.SyncSnapshotChangeNotifier
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SyncStateLifecycleJvmTest {
    @Test
    fun startupNormalizationAndSnapshotMutationUseOneStoreTransaction() = runBlocking {
        val lock = ReentrantLock()
        val loaded = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var firstRead = true
        var tasks = listOf(SyncTask(1, "old", sourceType = SyncEndpointType.Local,
            sourcePath = "/source", targetType = SyncEndpointType.Local, targetPath = "/target"))
        val store = object : SyncTaskStore {
            override fun <T> withExclusiveAccess(block: () -> T): T = lock.withLock(block)
            override fun loadTasks(): List<SyncTask> {
                val snapshot = lock.withLock { tasks }
                if (firstRead) {
                    firstRead = false
                    loaded.countDown()
                    check(resume.await(2, TimeUnit.SECONDS))
                }
                return snapshot
            }
            override fun saveTask(task: SyncTask): SyncTask = lock.withLock { tasks = listOf(task); task }
            override fun deleteTask(taskId: Long) { lock.withLock { tasks = emptyList() } }
            override fun appendRun(record: SyncRunRecord) = Unit
            override fun loadRuns(taskId: Long): List<SyncRunRecord> = emptyList()
            override fun deleteRuns(taskId: Long) = Unit
        }
        val state = SyncState(store) { SyncDeviceIdentity("device", "Device") }
        try {
            val start = async(Dispatchers.Default) { state.start() }
            assertTrue(loaded.await(2, TimeUnit.SECONDS))
            val snapshotApply = async(Dispatchers.Default) { store.withExclusiveAccess { store.deleteTask(1) } }
            delay(25)
            assertFalse(snapshotApply.isCompleted)
            resume.countDown()
            start.await()
            snapshotApply.await()
            state.reloadTasksFromStore()
            assertTrue(store.loadTasks().isEmpty())
            assertTrue(state.tasks.isEmpty())
        } finally { resume.countDown(); state.stop() }
    }

    @Test
    fun initializationIsExplicitAndIdempotentForAllClients(): Unit = runBlocking {
        var loads = 0
        val saved = SyncTask(
            id = 1, name = "Saved plan", sourceType = SyncEndpointType.Local,
            sourcePath = "/source", targetType = SyncEndpointType.Local, targetPath = "/target",
        )
        val store = object : SyncTaskStore {
            override fun loadTasks(): List<SyncTask> { loads++; return listOf(saved) }
            override fun saveTask(task: SyncTask): SyncTask = task
            override fun deleteTask(taskId: Long) = Unit
            override fun appendRun(record: SyncRunRecord) = Unit
            override fun loadRuns(taskId: Long): List<SyncRunRecord> = emptyList()
            override fun deleteRuns(taskId: Long) = Unit
        }
        val originalMode = WebUiAttachment.mode
        var uploads = 0
        SyncSnapshotChangeNotifier.setSyncTasksConfigurationHandler { uploads++ }
        val state = SyncState(store) { SyncDeviceIdentity("test-device", "Test device") }
        try {
            assertEquals(0, loads)
            assertTrue(state.tasks.isEmpty())
            WebUiAttachment.mode = WebUiMode.Attached
            state.start()
            state.start()
            assertEquals(1, loads)
            assertEquals(listOf(saved.copy(ownerDeviceId = "test-device", ownerDeviceName = "Test device")), state.snapshotTasks())
            assertEquals(0, uploads)
            state.stop()
            assertFailsWith<IllegalStateException> { state.start() }
        } finally {
            state.stop()
            WebUiAttachment.mode = originalMode
            SyncSnapshotChangeNotifier.setSyncTasksConfigurationHandler(null)
        }
    }
}

package com.folderspan.ui.state.main

import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.service.webui.WebUiMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncStateLifecycleJvmTest {
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
        val state = SyncState(store)
        try {
            assertEquals(0, loads)
            assertTrue(state.tasks.isEmpty())
            WebUiAttachment.mode = WebUiMode.Attached
            state.start()
            state.start()
            assertEquals(1, loads)
            assertEquals(listOf(saved), state.snapshotTasks())
            state.stop()
            assertFailsWith<IllegalStateException> { state.start() }
        } finally {
            state.stop()
            WebUiAttachment.mode = originalMode
        }
    }
}

package com.folderspan.ui.state.main

import com.folderspan.data.StatusEnum
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CopyValidationPersistenceJvmTest {
    @Test fun copySidecarsSurviveStoreRecreationAndQueueResetWithoutChangingLegacySnapshots() = runBlocking {
        val first = TempTaskRuntimePersistenceStore()
        val task = Task(TaskType.Copy, status = StatusEnum.FAILURE)
        first.saveTaskSnapshot(task)
        val state = TaskState(runtimeStore = first)
        try {
            assertNull(first.loadCopyValidation(task.key))
            val bytes = "versioned-source-manifest".encodeToByteArray()
            first.saveCopyValidation(task.key, bytes)
            val reopened = TempTaskRuntimePersistenceStore()
            assertEquals(task, reopened.loadTaskSnapshot(task.key))
            assertContentEquals(bytes, reopened.loadCopyValidation(task.key))
            state.clearRuntime(task.key)
            assertContentEquals(bytes, reopened.loadCopyValidation(task.key))
            reopened.clearTaskRuntime(task.key)
            assertNull(reopened.loadCopyValidation(task.key))
        } finally { state.stop(); first.clearTaskRuntime(task.key) }
    }
    @Test fun cancelledTasksCannotRecreateTheirValidationSidecar() = runBlocking {
        val store = TempTaskRuntimePersistenceStore()
        val state = TaskState(runtimeStore = store)
        val task = Task(TaskType.Copy, status = StatusEnum.LOADING)
        try {
            state.addOrUpdate(task)
            state.saveCopyValidation(task.key, "before-cancel".encodeToByteArray())
            state.requestCancel(task)
            state.saveCopyValidation(task.key, "late-write".encodeToByteArray())
            state.clearRuntime(task.key)
            assertNull(store.loadCopyValidation(task.key))
        } finally { state.stop(); store.clearTaskRuntime(task.key) }
    }
}

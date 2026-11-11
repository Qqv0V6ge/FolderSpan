package com.folderspan.ui.state.main

import com.folderspan.service.operation.TransferWaitReason
import com.folderspan.data.StatusEnum
import com.folderspan.service.operation.observeOperationTransferWait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import com.folderspan.service.operation.OperationTransferWaitObserver
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TaskTransferWaitingStateTest {
    @Test fun parallelWaitsClearIndependentlyAndStayOutOfTheTaskModel() = runTest {
        val state = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        val task = Task(taskType = TaskType.Copy, status = StatusEnum.LOADING)
        val secondEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            state.withTransferWaitObservation(task) { coroutineScope {
                val close = observeOperationTransferWait(TransferWaitReason.Receiver)
                val second = launch {
                    assertNotNull(currentCoroutineContext()[OperationTransferWaitObserver])
                    val end = observeOperationTransferWait(TransferWaitReason.Receiver)
                    try { secondEntered.complete(Unit); release.await() } finally { end() }
                }
                secondEntered.await()
                close()
                assertEquals(setOf(TransferWaitReason.Receiver), state.transferWaitReasons(task.key))
                assertTrue(task.values.isEmpty())
                release.complete(Unit)
                second.join()
                assertTrue(state.transferWaitReasons(task.key).isEmpty())
            } }
            val waiter = launch {
                state.withTransferWaitObservation(task) {
                    val close = observeOperationTransferWait(TransferWaitReason.DeviceBusy)
                    try { CompletableDeferred<Unit>().await() } finally { close() }
                }
            }
            runCurrent()
            assertEquals(setOf(TransferWaitReason.DeviceBusy), state.transferWaitReasons(task.key))
            waiter.cancelAndJoin()
            assertTrue(state.transferWaitReasons(task.key).isEmpty())
        } finally { state.stop() }
    }

    @Test fun byteSpeedUsesTheExistingByteMetricAndRejectsOtherOrInvalidSamples() {
        val base = Task(taskType = TaskType.Copy, status = StatusEnum.LOADING, values = mapOf(TASK_RUNTIME_METRIC_KIND_VALUE_KEY to TASK_RUNTIME_METRIC_KIND_BYTES,
            TASK_RUNTIME_METRIC_SPEED_VALUE_KEY to "1024.5", TASK_RUNTIME_METRIC_UPDATED_AT_VALUE_KEY to "1000"))
        assertEquals(1024.5, base.runtimeByteSpeedPerSecond(1_500))
        assertEquals(0.0, base.runtimeByteSpeedPerSecond(4_000))
        assertNull(base.runtimeByteSpeedPerSecond(500))
        assertNull(base.copy(values = base.values - TASK_RUNTIME_METRIC_UPDATED_AT_VALUE_KEY).runtimeByteSpeedPerSecond(1_500))
        for (value in listOf("-1", "NaN", "Infinity", "invalid")) {
            assertNull(base.copy(values = base.values + (TASK_RUNTIME_METRIC_SPEED_VALUE_KEY to value)).runtimeByteSpeedPerSecond(1_500))
        }
        assertNull(base.copy(values = base.values + (TASK_RUNTIME_METRIC_KIND_VALUE_KEY to TASK_RUNTIME_METRIC_KIND_ITEMS)).runtimeByteSpeedPerSecond(1_500))
    }
}

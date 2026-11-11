package com.folderspan.ui.state.main

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncSystemSchedulerTest {
    private val task = SyncTask(1, "timed", sourceType = SyncEndpointType.Local, sourcePath = "/source",
        targetType = SyncEndpointType.Local, targetPath = "/target", scheduleType = SyncScheduleType.Interval,
        intervalMinutes = 60, nextRunAt = 1000, updatedAt = 10)

    @Test fun onlyOwnedEnabledTasksWithAPersistedDueTimeAreSystemScheduled() {
        assertTrue(syncSystemScheduleEligible(task, true))
        assertFalse(syncSystemScheduleEligible(task, false))
        assertFalse(syncSystemScheduleEligible(task.copy(enabled = false), true))
        assertFalse(syncSystemScheduleEligible(task.copy(nextRunAt = 0), true))
        assertFalse(syncSystemScheduleEligible(task.copy(scheduleType = SyncScheduleType.Manual), true))
    }

    @Test fun dueConfigurationAndConfirmationChangesCreateANewSignatureButProgressDoesNot() {
        val signature = syncSystemScheduleSignature(task, false)
        assertEquals(signature, syncSystemScheduleSignature(task.copy(lastRunAt = 40, lastStatus = SyncRunStatus.Running, lastMessage = "copying", updatedAt = 20), false))
        for (changed in listOf(task.copy(nextRunAt = 2000), task.copy(requireCharging = true),
            task.copy(sourcePath = "/new"), task.copy(ownerDeviceId = "another"))) {
            assertNotEquals(signature, syncSystemScheduleSignature(changed, false))
        }
        assertNotEquals(signature, syncSystemScheduleSignature(task, true))
    }
}

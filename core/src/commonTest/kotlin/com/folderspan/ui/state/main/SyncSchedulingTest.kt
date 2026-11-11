package com.folderspan.ui.state.main

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class SyncSchedulingTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z").toEpochMilliseconds()
    private val zone = TimeZone.UTC
    private fun task(type: SyncScheduleType = SyncScheduleType.Interval) = SyncTask(1, "sync",
        sourceType = SyncEndpointType.Local, sourcePath = "/source", targetType = SyncEndpointType.Local,
        targetPath = "/target", ownerDeviceId = "device", scheduleType = type, intervalMinutes = 30,
        scheduleTimeMinutes = 120, scheduleWeekdays = 1 or 8, nextRunAt = now - 5 * 60 * 60_000)
    private fun run(task: SyncTask, trigger: String, start: Long = now, end: Long = now) =
        SyncRunRecord(1, task.id, trigger, start, end, SyncRunStatus.Success, 0, 0, 0)

    @Test fun overdueSchedulesSurviveRestartAndFinishOnlyOnceFromTheEnd() {
        for (type in listOf(SyncScheduleType.Interval, SyncScheduleType.Daily, SyncScheduleType.Weekly)) {
            val task = task(type)
            assertEquals(task.nextRunAt, nextSyncRunOnLoad(task, now, interrupted = false, owned = true))
            assertTrue(matchesSyncDueRun(task, task, task.nextRunAt, now))
            val end = now + 3 * 24 * 60 * 60_000
            val next = nextSyncRunAfterFinish(task, task, run(task, "schedule", end = end), end, zone)
            assertEquals(computeNextRun(task, end, zone), next)
            assertTrue(next > end)
            assertFalse(matchesSyncDueRun(task, task.copy(nextRunAt = next), task.nextRunAt, end))
        }
    }

    @Test fun pausedForeignAndManualTasksCannotCatchUpAndResumeStartsInTheFuture() {
        val task = task()
        assertEquals(0L, nextSyncRunOnLoad(task.copy(enabled = false), now, true, true))
        assertEquals(0L, nextSyncRunOnLoad(task, now, true, false))
        assertEquals(0L, nextSyncRunOnLoad(task.copy(scheduleType = SyncScheduleType.Manual), now, true, true))
        assertEquals(now, nextSyncRunOnLoad(task.copy(nextRunAt = 0), now, true, true))
        assertEquals(now + 30 * 60_000, computeNextRun(task.copy(enabled = true), now, zone))
        for (changed in listOf(task.copy(enabled = false), task.copy(nextRunAt = now + 1),
            task.copy(ownerDeviceId = "another"), task.copy(sourcePath = "/new"), task.copy(intervalMinutes = 60))) {
            assertFalse(matchesSyncDueRun(task, changed, task.nextRunAt, now))
        }
        assertFalse(matchesSyncDueRun(task, null, task.nextRunAt, now))
        assertFalse(matchesSyncDueRun(task, task, now + 1, now))
    }

    @Test fun eventsPreserveFutureSchedulesAndFoldDueTimesPassedByTheSameRun() {
        val task = task().copy(nextRunAt = now + 60_000)
        assertEquals(task.nextRunAt, nextSyncRunAfterFinish(task, task, run(task, "event"), now, zone))
        val end = now + 5 * 60 * 60_000
        assertEquals(computeNextRun(task, end, zone), nextSyncRunAfterFinish(task, task, run(task, "event", end = end), end, zone))
        val edited = task.copy(intervalMinutes = 60, nextRunAt = now + 3_600_000)
        assertEquals(edited.nextRunAt, nextSyncRunAfterFinish(edited, task, run(task, "schedule"), now, zone))
        assertEquals(0L, nextSyncRunAfterFinish(task.copy(enabled = false), task, run(task, "schedule"), now, zone))
        val merged = mergeSyncTaskSnapshot(listOf(task), listOf(task.copy(name = "snapshot")), "device", now).tasks.single()
        assertEquals(task.nextRunAt, merged.nextRunAt)
        val changed = mergeSyncTaskSnapshot(listOf(task), listOf(task.copy(scheduleType = SyncScheduleType.Daily)), "device", now).tasks.single()
        assertEquals(computeNextRun(changed, now), changed.nextRunAt)
    }

    @Test fun reopeningInAnotherTimeZoneRecalculatesCalendarSchedulesAndPreservesUnknownOrIntervalHistory() {
        val previous = TimeZone.of("Asia/Shanghai")
        val current = TimeZone.of("Asia/Tokyo")
        for (type in listOf(SyncScheduleType.Daily, SyncScheduleType.Weekly)) {
            val task = task(type)
            assertEquals(computeNextRun(task, now, current), nextSyncRunOnLoad(task, now, false, true, previous.id, current))
            assertEquals(task.nextRunAt, nextSyncRunOnLoad(task, now, false, true, current.id, current))
            assertEquals(task.nextRunAt, nextSyncRunOnLoad(task, now, false, true, "", current))
            assertEquals(0L, nextSyncRunOnLoad(task.copy(enabled = false), now, false, true, previous.id, current))
        }
        assertEquals(task().nextRunAt, nextSyncRunOnLoad(task(), now, false, true, previous.id, current))
    }
}

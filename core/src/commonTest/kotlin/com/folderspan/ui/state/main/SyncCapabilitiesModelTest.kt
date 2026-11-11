package com.folderspan.ui.state.main

import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.utils.ProtoBufCodec
import com.folderspan.service.network.StorageCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json

class SyncCapabilitiesModelTest : ChineseLocalizationTest() {
    private fun task() = SyncTask(1, "sync", sourceType = SyncEndpointType.Local, sourcePath = "/source",
        targetType = SyncEndpointType.Local, targetPath = "/target")

    @Test fun publishedTaskKeepsOriginalModeAndSchedule() {
        val old = ProtoBufCodec.decode<SyncTask>(Base64.decode("CAcSBmxlZ2FjeRgBIAAqADIHL3NvdXJjZTgAQgBKBy90YXJnZXRQAVgBYgBoAHgAgAEBiAE8kAEAmAHSCaABAKoBALABCrgBFA=="))
        assertEquals(SyncMode.Update, old.mode)
        assertEquals(SyncScheduleType.Interval, old.scheduleType)
        assertEquals(60L, old.intervalMinutes)
        assertEquals(30, old.recycleRetentionDays)
        assertEquals(100, old.deleteThreshold)
        assertEquals(SyncNotifyLevel.FailuresAndConfirmations, old.notifyLevel)
    }

    @Test fun legacyGroupSnapshotKeepsItsOrderedTargetsAndUsesSafeModeDefaults() {
        val group = Json.decodeFromString<SyncTask>("""{"id":7,"name":"Old group","sourceType":"Local","sourcePath":"/source","targetType":"Network","targetRef":"first","targetPath":"/one","destinations":[{"type":"Network","ref":"first","path":"/one"},{"type":"Network","ref":"second","path":"/two"}],"selectRecommended":true}""")
        assertEquals(SyncMode.Update, group.mode)
        assertEquals(listOf("first", "second"), group.destinations.map { it.ref })
        assertEquals(true, group.selectRecommended)
        assertEquals(emptyMap(), group.currentSourceReferences)
        assertEquals(30, group.recycleRetentionDays)
    }

    @Test fun allNewDefinitionFieldsSurviveRoundTrip() {
        val value = task().copy(mode = SyncMode.Bidirectional, bidiConflictPolicy = SyncBidiConflictPolicy.Target,
            recycleEnabled = false, recycleRetentionDays = 365, deleteThreshold = 100_000, includeRaw = "*.jpg;*.mp4",
            scheduleType = SyncScheduleType.Weekly, scheduleTimeMinutes = 1439, scheduleWeekdays = 65,
            triggerOnDeviceConnect = true, triggerOnNetworkAvailable = true, requireWifi = true,
            requireCharging = true, notifyLevel = SyncNotifyLevel.All)
        assertEquals(value, ProtoBufCodec.decode<SyncTask>(ProtoBufCodec.encode(value)))
    }

    @Test fun settingBoundsAndBackupPolicyAreValidated() {
        assertNull(syncCapabilitySettingsError(task().copy(recycleRetentionDays = 1, deleteThreshold = 1)))
        assertNull(syncCapabilitySettingsError(task().copy(recycleRetentionDays = 365, deleteThreshold = 100_000)))
        for (days in listOf(0, 366)) assertNotNull(syncCapabilitySettingsError(task().copy(recycleRetentionDays = days)))
        for (count in listOf(0, 100_001)) assertNotNull(syncCapabilitySettingsError(task().copy(deleteThreshold = count)))
        assertNotNull(syncCapabilitySettingsError(task().copy(mode = SyncMode.BackupThenClean)))
        assertEquals(SyncConflictPolicy.Rename, task().withMode(SyncMode.BackupThenClean).conflictPolicy)
        assertNull(syncCapabilitySettingsError(task().withMode(SyncMode.BackupThenClean)))
        assertNotNull(syncCapabilitySettingsError(task().copy(scheduleType = SyncScheduleType.Weekly, scheduleWeekdays = 0)))
        assertNotNull(syncCapabilitySettingsError(task().copy(scheduleType = SyncScheduleType.Daily, scheduleTimeMinutes = 1440)))
    }

    @Test fun modesRequireDirectoriesAndTheCapabilitiesOfEveryParticipatingSide() {
        val full = StorageCapabilities.ReadWrite
        for (mode in SyncMode.entries) for (source in listOf(true, false)) {
            assertNull(syncModeEndpointError(mode, source, true, full))
            if (mode != SyncMode.Update) assertNotNull(syncModeEndpointError(mode, source, false, full))
        }
        assertNull(syncModeEndpointError(SyncMode.Mirror, true, true, StorageCapabilities.ReadOnly))
        assertNotNull(syncModeEndpointError(SyncMode.Mirror, false, true, full.copy(delete = false)))
        assertNotNull(syncModeEndpointError(SyncMode.BackupThenClean, true, true, full.copy(delete = false)))
        assertNull(syncModeEndpointError(SyncMode.BackupThenClean, false, true, full.copy(delete = false)))
        for (source in listOf(true, false)) {
            assertNotNull(syncModeEndpointError(SyncMode.Bidirectional, source, true, full.copy(read = false)))
            assertNotNull(syncModeEndpointError(SyncMode.Bidirectional, source, true, full.copy(write = false)))
            assertNotNull(syncModeEndpointError(SyncMode.Bidirectional, source, true, full.copy(delete = false)))
        }
    }

    @Test fun dailyWeeklyAndIntervalUseTheCorrectClock() {
        val now = Instant.parse("2026-11-02T03:00:00Z").toEpochMilliseconds()
        val daily = task().copy(scheduleType = SyncScheduleType.Daily)
        assertEquals(Instant.parse("2026-11-02T18:00:00Z").toEpochMilliseconds(), computeNextRun(daily, now, TimeZone.of("Asia/Shanghai")))
        assertEquals(Instant.parse("2026-11-02T17:00:00Z").toEpochMilliseconds(), computeNextRun(daily, now, TimeZone.of("Asia/Tokyo")))
        val weekly = daily.copy(scheduleType = SyncScheduleType.Weekly, scheduleTimeMinutes = 20 * 60, scheduleWeekdays = 1 or 8)
        assertEquals(Instant.parse("2026-11-02T12:00:00Z").toEpochMilliseconds(), computeNextRun(weekly, now, TimeZone.of("Asia/Shanghai")))
        assertEquals(Instant.parse("2026-11-05T12:00:00Z").toEpochMilliseconds(), computeNextRun(weekly, Instant.parse("2026-11-02T13:00:00Z").toEpochMilliseconds(), TimeZone.of("Asia/Shanghai")))
        assertEquals(now + 60 * 60_000, computeNextRun(task().copy(scheduleType = SyncScheduleType.Interval, intervalMinutes = 60), now))
    }

    @Test fun daylightSavingGapMovesForwardAndOverlapOnlyUsesFirstOccurrence() {
        val zone = TimeZone.of("America/New_York")
        val daily = task().copy(scheduleType = SyncScheduleType.Daily, scheduleTimeMinutes = 150)
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilliseconds(), computeNextRun(daily, Instant.parse("2026-03-08T05:00:00Z").toEpochMilliseconds(), zone))
        val overlap = daily.copy(scheduleTimeMinutes = 90)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilliseconds(), computeNextRun(overlap, Instant.parse("2026-11-01T04:00:00Z").toEpochMilliseconds(), zone))
        assertEquals(Instant.parse("2026-11-02T06:30:00Z").toEpochMilliseconds(), computeNextRun(overlap, Instant.parse("2026-11-01T05:31:00Z").toEpochMilliseconds(), zone))
    }
}

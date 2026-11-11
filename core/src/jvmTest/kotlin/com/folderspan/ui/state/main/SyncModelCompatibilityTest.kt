package com.folderspan.ui.state.main

import com.folderspan.utils.ProtoBufCodec
import com.folderspan.service.network.StorageReference
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncModelCompatibilityTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class PublishedRunHeader(
        @ProtoNumber(1) val runId: Long,
        @ProtoNumber(6) val status: SyncRunStatus,
        @ProtoNumber(8) val successCount: Int,
    )

    @Test
    fun publishedV101WireSamplesKeepOldFieldsAndUseNewDefaults() {
        // 固定为 v1.0.1 的字段编号，不使用新模型重新生成兼容样本。
        val task = ProtoBufCodec.decode<SyncTask>(Base64.decode("CAcSBmxlZ2FjeRgBIAAqADIHL3NvdXJjZTgAQgBKBy90YXJnZXRQAVgBYgBoAHgAgAEBiAE8kAEAmAHSCaABAKoBALABCrgBFA=="))
        assertEquals(7L, task.id)
        assertEquals("legacy", task.name)
        assertEquals(SyncScheduleType.Interval, task.scheduleType)
        assertEquals(60L, task.intervalMinutes)
        assertEquals(1234L, task.nextRunAt)
        assertEquals(1, task.retryCount)
        assertEquals(120, task.timeoutSeconds)
        assertEquals(1, task.parallelism)
        assertEquals("", task.ownerDeviceId)
        val record = ProtoBufCodec.decode<SyncRunRecord>(Base64.decode("CAsQBxoGbWFudWFsIGQoyAEwAzgBQAFIAFIEZG9uZQ=="))
        assertEquals(SyncRunStatus.Success, record.status)
        assertEquals(1, record.successCount)
        assertEquals(0, record.copiedCount)
        assertEquals(0L, record.retryOfRunId)
        assertEquals(0, record.unsavedFailureCount)
        assertEquals(emptyList(), record.participants)
        assertEquals(emptyList(), record.resultCounts)
        assertEquals(0L, record.detailCount)
        assertEquals(0L, record.unsavedDetailCount)
        val item = ProtoBufCodec.decode<SyncRunItem>(Base64.decode("CgpsZWdhY3kudHh0EAEaBGRvbmU="))
        assertEquals(SyncRunItem("legacy.txt", true, "done"), item)
        assertEquals(SyncRunItemKind.Other, item.kind)
        assertEquals(emptyList(), item.recoveryLocations)
    }

    @Test
    fun newFieldsSurviveProtobufRoundTrip() {
        val task = SyncTask(7, "Docs", sourceType = SyncEndpointType.Local, sourcePath = "/source",
            targetType = SyncEndpointType.Local, targetPath = "/target", retryCount = 5, timeoutSeconds = 3600,
            parallelism = 8, ownerDeviceId = "device-a", ownerDeviceName = "Computer",
            currentSourceReferences = mapOf("selection" to StorageReference("current", "parent")))
        assertEquals(task, ProtoBufCodec.decode<SyncTask>(ProtoBufCodec.encode(task)))
        val record = SyncRunRecord(11, 7, "retry", 100, 200, SyncRunStatus.PartialSuccess, 10, 9, 1,
            copiedCount = 2, renamedCount = 3, createdDirectoryCount = 1, unchangedCount = 2, skippedCount = 1,
            transferredBytes = 2048, transferDurationMillis = 20, unsavedFailureCount = 2, retryOfRunId = 10,
            policySnapshot = task.withMode(SyncMode.Bidirectional),
            items = listOf(SyncRunItem("file", true, "recycled", kind = SyncRunItemKind.Recycle, participant = "target-0", recoveryGroup = "group",
                recoveryLocations = listOf(SyncRecoveryLocation(SyncRecoveryEndpoint(SyncEndpointType.Local, "", "/target"), "/target/recycle/file")))),
            resultCounts = listOf(SyncRunResultCount(SyncRunItemKind.Recycle, 1, 0)), detailCount = 1, unsavedDetailCount = 2,
            participants = listOf(SyncRunParticipant("target-0", "Actual archive", SyncEndpointType.Network, "chosen", listOf("/archive"))))
        assertEquals(record, ProtoBufCodec.decode<SyncRunRecord>(ProtoBufCodec.encode(record)))
        assertEquals(PublishedRunHeader(11, SyncRunStatus.PartialSuccess, 9),
            ProtoBufCodec.decode<PublishedRunHeader>(ProtoBufCodec.encode(record)))
    }

    @Test
    fun identityReadsPersistedDeviceIdWithoutGeneratingOne() {
        val settings = MapSettings()
        assertEquals("", SyncDeviceIdentity.fromSettings(settings, "Computer").id)
        settings.putString(SettingsUtils.KEY_DEVICE_ID, "persisted-device")
        assertEquals(SyncDeviceIdentity("persisted-device", "Computer"), SyncDeviceIdentity.fromSettings(settings, "Computer"))
    }
}

package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.ui.state.main.TaskRuntimeEndpointRef
import com.folderspan.ui.state.main.TaskRuntimeEntryKind
import com.folderspan.ui.state.main.TaskRuntimeQueueEntry
import com.folderspan.ui.state.main.TaskRuntimeStage
import com.folderspan.ui.state.main.TaskType
import com.folderspan.ui.state.main.taskFileRecoveryMinBytes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileRuntimeTaskExecutorRecoveryRouteTest {
    @Test
    fun largeFileOnSameDeviceBypassesChunkRecovery() {
        val entry = largeFileEntry(
            srcProtocol = FileProtocol.Device,
            srcProtocolId = "device-1",
            destProtocol = FileProtocol.Device,
            destProtocolId = "device-1",
        )

        assertFalse(entry.shouldUseTransferRecoveryForCopyQueue())
    }

    @Test
    fun largeFileAcrossDevicesStillUsesChunkRecovery() {
        val entry = largeFileEntry(
            srcProtocol = FileProtocol.Device,
            srcProtocolId = "device-1",
            destProtocol = FileProtocol.Device,
            destProtocolId = "device-2",
        )

        assertTrue(entry.shouldUseTransferRecoveryForCopyQueue())
    }

    @Test
    fun largeFileFromShareToLocalUsesChunkRecovery() {
        val entry = largeFileEntry(
            srcProtocol = FileProtocol.Share,
            srcProtocolId = "share-1",
            destProtocol = FileProtocol.Local,
            destProtocolId = "",
        )

        assertTrue(entry.shouldUseTransferRecoveryForCopyQueue())
    }

    @Test
    fun systemShareAndShareToDeviceBypassChunkRecovery() {
        assertFalse(
            largeFileEntry(
                srcProtocol = FileProtocol.Share,
                srcProtocolId = SYSTEM_SHARE_DESK_ID,
                destProtocol = FileProtocol.Local,
                destProtocolId = "",
            ).shouldUseTransferRecoveryForCopyQueue()
        )
        assertFalse(
            largeFileEntry(
                srcProtocol = FileProtocol.Share,
                srcProtocolId = "share-1",
                destProtocol = FileProtocol.Device,
                destProtocolId = "device-1",
            ).shouldUseTransferRecoveryForCopyQueue()
        )
        assertFalse(
            largeFileEntry(
                srcProtocol = FileProtocol.Share,
                srcProtocolId = "share-1",
                destProtocol = FileProtocol.Network,
                destProtocolId = "network-1",
            ).shouldUseTransferRecoveryForCopyQueue()
        )
        assertFalse(
            largeFileEntry(
                srcProtocol = FileProtocol.Device,
                srcProtocolId = "device-1",
                destProtocol = FileProtocol.Network,
                destProtocolId = "network-1",
            ).shouldUseTransferRecoveryForCopyQueue()
        )
    }

    @Test
    fun blankDeviceIdsDoNotEnableSameDeviceFastPath() {
        val entry = largeFileEntry(
            srcProtocol = FileProtocol.Device,
            srcProtocolId = "",
            destProtocol = FileProtocol.Device,
            destProtocolId = "",
        )

        assertTrue(entry.shouldUseTransferRecoveryForCopyQueue())
    }

    @Test
    fun moveCopyFailureStopsBeforeDeleteSourceStage() {
        assertFalse(
            shouldContinueRuntimeQueueAfterFailure(
                taskType = TaskType.Move,
                stage = TaskRuntimeStage.COPY,
                failure = Exception(AppStrings.ui_test_file_runtime_task_executor_recovery_route_server_replication_failed),
            )
        )
    }

    @Test
    fun copyTaskCanContinueCollectingIndependentEntryFailures() {
        assertTrue(
            shouldContinueRuntimeQueueAfterFailure(
                taskType = TaskType.Copy,
                stage = TaskRuntimeStage.COPY,
                failure = Exception(AppStrings.ui_test_file_runtime_task_executor_recovery_route_file_copy_failed),
            )
        )
    }

    @Test
    fun moveDeleteFailureCanStillBeRecordedPerEntry() {
        assertTrue(
            shouldContinueRuntimeQueueAfterFailure(
                taskType = TaskType.Move,
                stage = TaskRuntimeStage.DELETE_SOURCE,
                failure = Exception(AppStrings.ui_test_file_runtime_task_executor_recovery_route_file_deletion_failed),
            )
        )
    }

    private fun largeFileEntry(
        srcProtocol: FileProtocol,
        srcProtocolId: String,
        destProtocol: FileProtocol,
        destProtocolId: String,
    ): TaskRuntimeQueueEntry = TaskRuntimeQueueEntry(
        entryId = "large-file",
        stage = TaskRuntimeStage.COPY,
        kind = TaskRuntimeEntryKind.FILE_COPY,
        src = TaskRuntimeEndpointRef(
            protocol = srcProtocol,
            protocolId = srcProtocolId,
            path = "/source.bin",
        ),
        dest = TaskRuntimeEndpointRef(
            protocol = destProtocol,
            protocolId = destProtocolId,
            path = "/target.bin",
        ),
        size = taskFileRecoveryMinBytes(),
    )
}

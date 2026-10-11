package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ui.state.main.TaskType
import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileStateRecentTrackingTest {

    @Test
    fun successfulFileTransferRecordsDestination() = runSuspendTest {
        val recorded = mutableListOf<FileSimpleInfo>()
        val destination = buildDestination(isDirectory = false)

        recordTransferDestinationIfNeeded(
            taskType = TaskType.Copy,
            destination = destination,
            result = Result.success(true),
            isTaskCancelled = false,
        ) { file ->
            recorded += file
        }

        assertEquals(listOf(destination), recorded)
    }

    @Test
    fun successfulFolderTransferRecordsDestination() = runSuspendTest {
        val recorded = mutableListOf<FileSimpleInfo>()
        val destination = buildDestination(isDirectory = true)

        recordTransferDestinationIfNeeded(
            taskType = TaskType.Copy,
            destination = destination,
            result = Result.success(true),
            isTaskCancelled = false,
        ) { file ->
            recorded += file
        }

        assertEquals(listOf(destination), recorded)
        assertTrue(recorded.single().isDirectory)
    }

    @Test
    fun failedCancelledOrMoveTransferDoesNotRecordDestination() = runSuspendTest {
        val recorded = mutableListOf<FileSimpleInfo>()
        val destination = buildDestination(isDirectory = false)

        recordTransferDestinationIfNeeded(
            taskType = TaskType.Copy,
            destination = destination,
            result = Result.failure(IllegalStateException("boom")),
            isTaskCancelled = false,
        ) { file ->
            recorded += file
        }
        recordTransferDestinationIfNeeded(
            taskType = TaskType.Copy,
            destination = destination,
            result = Result.success(true),
            isTaskCancelled = true,
        ) { file ->
            recorded += file
        }
        recordTransferDestinationIfNeeded(
            taskType = TaskType.Move,
            destination = destination,
            result = Result.success(true),
            isTaskCancelled = false,
        ) { file ->
            recorded += file
        }
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun successfulCopyTracksEverySupportedDataSource() = runSuspendTest {
        val recorded = mutableListOf<FileSimpleInfo>()

        FileProtocol.entries.forEach { protocol ->
            val destination = buildDestination(
                isDirectory = false,
                protocol = protocol,
                protocolId = if (protocol == FileProtocol.Local) "" else "${protocol.name.lowercase()}-id",
            )
            recordTransferDestinationIfNeeded(
                taskType = TaskType.Copy,
                destination = destination,
                result = Result.success(true),
                isTaskCancelled = false,
            ) { file ->
                recorded += file
            }
        }

        assertEquals(FileProtocol.entries, recorded.map { file -> file.protocol })
    }

    private fun buildDestination(
        isDirectory: Boolean,
        protocol: FileProtocol = FileProtocol.Local,
        protocolId: String = "",
    ): FileSimpleInfo {
        val name = if (isDirectory) "target-dir" else "target.txt"
        val path = if (protocol == FileProtocol.Local) "/tmp/$name" else "$protocolId/$name"
        return FileSimpleInfo(
            name = name,
            isDirectory = isDirectory,
            isHidden = false,
            path = path,
            mineType = if (isDirectory) "" else "text/plain",
            size = if (isDirectory) 0L else 12L,
            createdDate = 1L,
            updatedDate = 2L,
            protocol = protocol,
            protocolId = protocolId,
        )
    }
}

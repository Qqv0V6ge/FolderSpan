package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilePasteOperationPlannerTest {
    private val planner = FilePasteOperationPlanner { _, _ -> "/" }

    @Test
    fun conflictChoicesPreserveReplaceSkipAndReserveSemanticsForExternalSources() {
        val source = file("report.txt", "/external/report.txt")
        val destination = file("target", "/target", isDirectory = true, protocolId = "network-1")
        val existing = file("report.txt", "/target/report.txt", protocolId = "network-1")
        val operationState = FileOperationState()

        planner.updateConflictOperations(listOf(source), destination, listOf(existing), operationState)
        val conflict = operationState.files.single()

        val replace = planner.buildPendingOperations(
            listOf(conflict.copy(type = FileOperationType.Replace)),
            destination,
            listOf(existing),
        ).single()
        assertEquals("/target/report.txt", replace.dest.path)
        assertEquals("network-1", replace.dest.protocolId)
        assertNull(replace.replaceTarget)

        val skipped = planner.buildPendingOperations(
            listOf(conflict.copy(type = FileOperationType.Jump)),
            destination,
            listOf(existing),
        )
        assertEquals(emptyList(), skipped)

        val reserved = planner.buildPendingOperations(
            listOf(conflict.copy(type = FileOperationType.Reserve)),
            destination,
            listOf(existing, file("report(2).txt", "/target/report(2).txt")),
        ).single()
        assertEquals("report(3).txt", reserved.dest.name)
        assertEquals("/target/report(3).txt", reserved.dest.path)
    }

    @Test
    fun directorySourceRemainsADirectoryWhenPlannedForCopy() {
        val source = file("empty-folder", "/external/empty-folder", isDirectory = true)
        val destination = file("target", "/target", isDirectory = true)
        val operationState = FileOperationState()

        planner.updateConflictOperations(listOf(source), destination, emptyList(), operationState)
        val pending = planner.buildPendingOperations(operationState.files, destination, emptyList()).single()

        assertEquals(true, pending.src.isDirectory)
        assertEquals(true, pending.dest.isDirectory)
        assertEquals("/target/empty-folder", pending.dest.path)
    }

    @Test
    fun replacePlansAllFilesAndFoldersTogether() {
        val sources = listOf(
            file("report.txt", "/share/report.txt"),
            file("photos", "/share/photos", isDirectory = true),
        )
        val destination = file("downloads", "/downloads", isDirectory = true)
        val existing = file("report.txt", "/downloads/report.txt")
        val operationState = FileOperationState()

        planner.updateConflictOperations(sources, destination, listOf(existing), operationState)
        val pending = planner.buildPendingOperations(
            operationState.files.map { operation -> operation.copy(type = FileOperationType.Replace) },
            destination,
            listOf(existing),
        )

        assertEquals(listOf("/downloads/report.txt", "/downloads/photos"), pending.map { item -> item.dest.path })
        assertEquals(listOf(false, true), pending.map { item -> item.dest.isDirectory })
    }

    private fun file(
        name: String,
        path: String,
        isDirectory: Boolean = false,
        protocolId: String = "",
    ) = FileSimpleInfo(
        name = name,
        isDirectory = isDirectory,
        isHidden = false,
        path = path,
        mineType = if (isDirectory) "" else "text/plain",
        size = 1,
        createdDate = 1,
        updatedDate = 1,
        protocol = if (protocolId.isBlank()) FileProtocol.Local else FileProtocol.Network,
        protocolId = protocolId,
    )
}

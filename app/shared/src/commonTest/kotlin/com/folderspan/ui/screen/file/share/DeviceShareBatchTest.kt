package com.folderspan.ui.screen.file.share

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.SocketDevice
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.FileShareStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeviceShareBatchTest {
    @Test
    fun newTargetsReceiveOneSnapshotBeforeStartingAndDuplicatesAreIgnored() {
        val state = FileShareState()
        val originalFiles = mutableListOf(file())
        val first = device("a")
        val started = mutableListOf<SocketDevice>()

        val result = submitDeviceShareBatch(
            setOf("a", "b"), listOf(first, device("a"), device("b")), originalFiles, true, state,
        ) { target ->
            assertEquals(null, state.sendFile[target.id])
            assertEquals(true to listOf(file()), state.shareToDevices[target.id])
            started += target
        }
        originalFiles.clear()

        assertEquals(DeviceShareBatchResult(2, 0, 0), result)
        assertEquals(listOf("a", "b"), started.map { it.id })
        assertSame(first, started.first())
        assertEquals(listOf(file()), state.shareToDevices.getValue("a").second)
        assertEquals(state.shareToDevices["a"], state.shareToDevices["b"])
    }

    @Test
    fun revalidationSkipsMissingAndWaitingTargetsAndUsesSingleDeviceRules() {
        val state = FileShareState()
        state.sendFile["waiting"] = FileShareStatus.WAITING
        state.sendFile["sending"] = FileShareStatus.SENDING
        state.sendFile["approved"] = FileShareStatus.COMPLETED
        state.sendFile["failed"] = FileShareStatus.ERROR
        state.sendFile["rejected"] = FileShareStatus.REJECTED
        state.shareToDevices["approved"] = true to emptyList()
        val ids = setOf("waiting", "sending", "approved", "failed", "rejected", "missing")
        val started = mutableListOf<String>()

        val result = submitDeviceShareBatch(
            ids, ids.filterNot { it == "missing" }.map(::device), listOf(file()), false, state,
        ) { started += it.id }

        assertEquals(DeviceShareBatchResult(3, 1, 2), result)
        assertEquals(listOf("sending", "failed", "rejected"), started)
        assertEquals(FileShareStatus.COMPLETED, state.sendFile["approved"])
        assertEquals(false to listOf(file()), state.shareToDevices["approved"])
        assertFalse(state.shareToDevices.containsKey("waiting"))
        assertEquals(false to listOf(file()), state.shareToDevices["sending"])
        assertEquals(FileShareStatus.SENDING, state.sendFile["sending"])
    }

    @Test
    fun emptyFilesOrTargetsDoNotChangeExistingAccess() {
        val state = FileShareState()
        state.shareToDevices["a"] = true to listOf(file())
        val before = state.shareToDevices.toMap()

        assertEquals(
            DeviceShareBatchResult(0, 0, 0),
            submitDeviceShareBatch(setOf("a"), listOf(device("a")), emptyList(), false, state) {
                error("Empty files must not start a share")
            },
        )
        assertEquals(
            DeviceShareBatchResult(0, 0, 0),
            submitDeviceShareBatch(emptySet(), listOf(device("a")), listOf(file()), false, state) {
                error("Empty selection must not start a share")
            },
        )
        assertEquals(before, state.shareToDevices.toMap())
        assertTrue(state.sendFile.isEmpty())
    }

    @Test
    fun repeatedSubmissionDoesNotRestartPendingSharesAndResultsStayIndependent() {
        val state = FileShareState()
        val devices = listOf(device("a"), device("b"))
        val started = mutableListOf<String>()
        val start: (SocketDevice) -> Unit = {
            started += it.id
            state.sendFile[it.id] = FileShareStatus.WAITING
        }

        submitDeviceShareBatch(setOf("a", "b"), devices, listOf(file()), false, state, start)
        val repeated = submitDeviceShareBatch(setOf("a", "b"), devices, listOf(file()), false, state, start)

        assertEquals(DeviceShareBatchResult(0, 0, 2), repeated)
        assertEquals(listOf("a", "b"), started)
        state.sendFile["a"] = FileShareStatus.REJECTED
        state.sendFile["b"] = FileShareStatus.COMPLETED
        state.clearShareToDevice("a")
        assertEquals(FileShareStatus.COMPLETED, state.sendFile["b"])
        assertEquals(false to listOf(file()), state.shareToDevices["b"])
    }

    @Test
    fun onlyWaitingStatusPreventsSelectionAsWithSingleDeviceClicks() {
        assertTrue(canSelectDeviceShare(null))
        FileShareStatus.entries.forEach { status ->
            assertEquals(status != FileShareStatus.WAITING, canSelectDeviceShare(status))
        }
    }

    @Test
    fun singleAndBatchShareTheOriginalSubmissionAndStatusRules() {
        for (status in listOf(null) + FileShareStatus.entries) {
            val singleState = FileShareState()
            val batchState = FileShareState()
            if (status != null) {
                singleState.sendFile["a"] = status
                batchState.sendFile["a"] = status
            }
            val singleStarted = mutableListOf<String>()
            val batchStarted = mutableListOf<String>()
            val requested = submitDeviceShare(device("a"), listOf(file()), true, singleState) {
                singleStarted += it.id
            }
            assertEquals(status != FileShareStatus.COMPLETED && status != FileShareStatus.WAITING, requested)
            assertEquals(status, singleState.sendFile["a"], "Submission must preserve the existing status timing")

            if (status != FileShareStatus.WAITING) {
                val result = submitDeviceShareBatch(setOf("a"), listOf(device("a")), listOf(file()), true, batchState) {
                    batchStarted += it.id
                }
                assertEquals(singleStarted, batchStarted, "status=$status")
                assertEquals(singleState.shareToDevices.toMap(), batchState.shareToDevices.toMap())
                assertEquals(singleState.sendFile.toMap(), batchState.sendFile.toMap())
                assertEquals(if (requested) 1 else 0, result.requested)
            }
        }
    }

    private fun device(id: String) = SocketDevice(id, "Device $id", "/", type = DeviceType.JVM)

    private fun file() = FileSimpleInfo(
        name = "shared.txt", path = "/tmp/shared.txt", isDirectory = false, isHidden = false,
        mineType = "text/plain", size = 1, createdDate = 1, updatedDate = 1, protocol = FileProtocol.Local,
    )
}

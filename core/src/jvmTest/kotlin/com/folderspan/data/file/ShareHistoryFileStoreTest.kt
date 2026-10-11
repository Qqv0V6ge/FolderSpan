package com.folderspan.data.file

import strings.AppStrings

import com.folderspan.data.main.device.DeviceType
import com.folderspan.ui.state.file.FileShareStatus
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareHistoryFileStoreTest {
    @Test
    fun addAndQueryHistoryByDirectionAndStatus() {
        val store = TempShareHistoryStore(Files.createTempDirectory("share-history-store").absolutePathString())

        val outgoingWaiting = store.add(historyInput("out-wait", true, FileShareStatus.WAITING, timestamp = 100))
        val incomingCompleted = store.add(historyInput("in-done", false, FileShareStatus.COMPLETED, timestamp = 200))
        val outgoingCompleted = store.add(historyInput("out-done", true, FileShareStatus.COMPLETED, timestamp = 300))

        assertEquals(listOf(outgoingCompleted.id, incomingCompleted.id, outgoingWaiting.id), store.query().map { it.id })
        assertEquals(
            listOf(outgoingCompleted.id, outgoingWaiting.id),
            store.query(direction = ShareHistoryDirection.OUTGOING).map { it.id },
        )
        assertEquals(
            listOf(incomingCompleted.id, outgoingCompleted.id),
            store.query(status = FileShareStatus.COMPLETED).map { it.id }.sorted(),
        )
    }

    @Test
    fun deleteAndClearRewriteHistoryFile() {
        val store = TempShareHistoryStore(Files.createTempDirectory("share-history-store-delete").absolutePathString())
        val first = store.add(historyInput("first", true, FileShareStatus.COMPLETED, timestamp = 100))
        val second = store.add(historyInput("second", false, FileShareStatus.ERROR, timestamp = 200))

        store.delete(first.id)

        assertEquals(listOf(second.id), store.query().map { it.id })

        store.clear()

        assertTrue(store.query().isEmpty())
    }

    @Test
    fun addPersistsProtobufPayloadLinesInsteadOfJsonLines() {
        val root = Files.createTempDirectory("share-history-store-protobuf")
        val store = TempShareHistoryStore(root.absolutePathString())

        store.add(historyInput("protobuf", true, FileShareStatus.COMPLETED, timestamp = 100))

        assertFalse(root.resolve("history.jsonl").exists())
        val payload = root.resolve("history.pb64l").readText().trim()
        assertFalse(payload.startsWith("{"))
        assertEquals(listOf("protobuf.txt"), store.query().map { history -> history.fileName })
    }

    private fun historyInput(
        name: String,
        isOutgoing: Boolean,
        status: FileShareStatus,
        timestamp: Long,
    ): ShareHistoryInput {
        return ShareHistoryInput(
            fileName = "$name.txt",
            filePath = "/source/$name.txt",
            fileSize = 12L,
            isDirectory = false,
            sourceDeviceId = "source-device",
            sourceDeviceName = if (isOutgoing) AppStrings.ui_me else "Source",
            sourceDeviceType = DeviceType.JS,
            targetDeviceId = "target-device",
            targetDeviceName = if (isOutgoing) "Target" else AppStrings.ui_me,
            targetDeviceType = DeviceType.Android,
            isOutgoing = isOutgoing,
            timestamp = timestamp,
            status = status,
            errorMessage = if (status == FileShareStatus.ERROR) "failed" else "",
            savePath = "/downloads/$name.txt",
        )
    }
}

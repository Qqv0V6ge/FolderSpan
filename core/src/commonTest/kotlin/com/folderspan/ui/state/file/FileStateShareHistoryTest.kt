package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileStateShareHistoryTest {

    @Test
    fun incomingShareCopiesBuildHistoryAfterCompletion() {
        val source = file("report.txt", "/shared/report.txt", FileProtocol.Share, "sender")
        val destination = file("report.txt", "/downloads/report.txt", FileProtocol.Local, "")

        val completed = buildIncomingShareHistoryInput(source, destination, null, Result.success(true))
        val failed = buildIncomingShareHistoryInput(
            source,
            destination,
            null,
            Result.failure(IllegalStateException("network error")),
        )

        assertEquals(FileShareStatus.COMPLETED, completed?.status)
        assertEquals(false, completed?.isOutgoing)
        assertEquals("sender", completed?.sourceDeviceName)
        assertEquals(destination.path, completed?.savePath)
        assertEquals(FileShareStatus.ERROR, failed?.status)
        assertEquals("network error", failed?.errorMessage)
        assertNull(
            buildIncomingShareHistoryInput(
                source.copy(protocolId = SYSTEM_SHARE_DESK_ID),
                destination,
                null,
                Result.success(true),
            )
        )
        assertNull(
            buildIncomingShareHistoryInput(
                source,
                destination,
                null,
                Result.failure(CancellationException("cancelled")),
            )
        )
    }

    private fun file(
        name: String,
        path: String,
        protocol: FileProtocol,
        protocolId: String,
    ): FileSimpleInfo = FileSimpleInfo(
        name = name,
        isDirectory = false,
        isHidden = false,
        path = path,
        mineType = "text/plain",
        size = 12L,
        createdDate = 1L,
        updatedDate = 2L,
        protocol = protocol,
        protocolId = protocolId,
    )
}

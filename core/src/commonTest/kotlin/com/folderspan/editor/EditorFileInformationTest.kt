package com.folderspan.editor

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorFileInformationTest {
    @Test
    fun buildsProtocolQualifiedPathAndLiveEditorLocation() {
        val file = FileSimpleInfo(
            name = "data.bin",
            isDirectory = false,
            isHidden = false,
            path = "/logs/data.bin",
            mineType = "application/octet-stream",
            size = 99L,
            createdDate = 10L,
            updatedDate = 20L,
            protocol = FileProtocol.Device,
            protocolId = "device-1",
        )

        val information = editorFileInformation(
            file,
            FileEditorDocumentState(
                fileSize = 100L,
                encoding = EditorTextEncoding.UTF16_LE,
                newlineKind = EditorNewlineKind.CRLF,
                selectionStartOffset = 8L,
                selectionEndOffsetExclusive = 12L,
                currentLineNumber = 3L,
            ),
        )

        assertEquals("Device[device-1]:/logs/data.bin", information.protocolQualifiedPath)
        assertEquals(100L, information.size)
        assertEquals(8L, information.currentOffset)
        assertEquals(3L, information.currentLine)
        assertEquals(4L, information.selectionSize)
    }
}

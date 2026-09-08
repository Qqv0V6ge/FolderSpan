package com.folderspan.ui.screen.file.share

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileShareMergePolicyTest {
    @Test
    fun emptyCurrentListAcceptsIncomingFilesWithoutMergeDialog() {
        assertFalse(shouldShowFileShareMergeDialog(emptyList(), listOf(file("incoming.txt"))))
    }

    @Test
    fun differentNonEmptyListsRequireMergeDialog() {
        assertTrue(
            shouldShowFileShareMergeDialog(
                existingFiles = listOf(file("existing.txt")),
                incomingFiles = listOf(file("incoming.txt")),
            )
        )
    }

    @Test
    fun identicalListsDoNotRequireMergeDialog() {
        val sharedFile = file("shared.txt")

        assertFalse(shouldShowFileShareMergeDialog(listOf(sharedFile), listOf(sharedFile)))
    }

    private fun file(name: String) = FileSimpleInfo(
        name = name,
        isDirectory = false,
        isHidden = false,
        path = "/tmp/$name",
        mineType = "text/plain",
        size = 1,
        createdDate = 1,
        updatedDate = 1,
        protocol = FileProtocol.Local,
    )
}

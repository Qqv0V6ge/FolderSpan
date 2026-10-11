package com.folderspan.ui.components.buttons

import strings.AppStrings

import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.displayName
import com.folderspan.db.FileFilter
import kotlin.test.Test
import kotlin.test.assertEquals

class FileFilterButtonGroupUiStateTest {
    @Test
    fun builtInFilterChipUsesLocalizedTypeName() {
        val uiState = buildFileFilterButtonGroupUiState(
            fileAndFolder = listOf(
                FileSimpleInfo(
                    name = "photo.png",
                    isDirectory = false,
                    isHidden = false,
                    path = "/photo.png",
                    mineType = ".png",
                    size = 1,
                    createdDate = 0,
                    updatedDate = 0,
                )
            ),
            filterFileTypes = listOf(
                FileFilter(
                    id = 1L,
                    name = AppStrings.ui_pictures,
                    type = FileFilterType.Image,
                    extensions = listOf(".png"),
                    icon = null,
                    sort = 1L,
                )
            ),
            filterFileExtensions = emptyList(),
            isHide = false,
        )

        assertEquals(FileFilterType.Image.displayName(), uiState.chips.single().name)
    }
}

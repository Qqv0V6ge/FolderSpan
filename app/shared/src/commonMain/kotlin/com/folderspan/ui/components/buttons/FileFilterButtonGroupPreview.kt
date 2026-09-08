package com.folderspan.ui.components.buttons

import strings.AppStrings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.db.FileFilter

@Preview
@Composable
private fun FileFilterButtonGroupPreview() {
    FileFilterButtonGroup(
        uiState = buildFileFilterButtonGroupUiState(
            fileAndFolder = listOf(
                FileSimpleInfo(
                    name = "Pictures",
                    isDirectory = true,
                    isHidden = false,
                    path = "/Pictures",
                    mineType = "",
                    size = 0,
                    createdDate = 0,
                    updatedDate = 0
                ),
                FileSimpleInfo(
                    name = "photo.png",
                    isDirectory = false,
                    isHidden = false,
                    path = "/Pictures/photo.png",
                    mineType = ".png",
                    size = 2_048,
                    createdDate = 0,
                    updatedDate = 0
                ),
                FileSimpleInfo(
                    name = ".hidden-photo.png",
                    isDirectory = false,
                    isHidden = true,
                    path = "/Pictures/.hidden-photo.png",
                    mineType = ".png",
                    size = 1_024,
                    createdDate = 0,
                    updatedDate = 0
                )
            ),
            filterFileTypes = listOf(
                FileFilter(
                    id = 1L,
                    name = AppStrings.ui_pictures,
                    type = FileFilterType.Image,
                    extensions = listOf(".png", ".jpg"),
                    icon = null,
                    sort = 1L
                )
            ),
            filterFileExtensions = listOf(FileFilterType.File, FileFilterType.Hidden),
            isHide = true,
        ),
        onCheckedFileFilterTypeChange = { _, _ -> }
    )
}

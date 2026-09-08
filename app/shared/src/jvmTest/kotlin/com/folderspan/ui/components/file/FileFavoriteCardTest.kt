package com.folderspan.ui.components.file

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.data.file.FileProtocol
import com.folderspan.db.FileFavorite
import kotlin.test.Test
import strings.AppStrings

@OptIn(ExperimentalTestApi::class)
class FileFavoriteCardTest {
    @Test
    fun normalModeShowsTrailingMenu() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FileFavoriteCard(
                    favorite = favorite(),
                    onClick = {},
                    onFixed = {},
                    onRemove = {},
                )
            }
        }

        onNodeWithContentDescription(AppStrings.ui_more).assertIsDisplayed()
    }

    @Test
    fun selectionModeHidesTrailingMenu() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FileFavoriteCard(
                    favorite = favorite(),
                    onClick = {},
                    onFixed = {},
                    onRemove = {},
                    isSelectionMode = true,
                )
            }
        }

        onNodeWithContentDescription(AppStrings.ui_more).assertDoesNotExist()
    }

    private fun favorite() = FileFavorite(
        id = 1L,
        name = "Documents",
        isDirectory = true,
        isFixed = false,
        path = "/Documents",
        mineType = "",
        size = 0L,
        createdDate = 0L,
        updatedDate = 0L,
        protocol = FileProtocol.Local,
        protocolId = "",
    )
}

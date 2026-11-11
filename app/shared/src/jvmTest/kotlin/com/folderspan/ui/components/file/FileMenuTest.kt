package com.folderspan.ui.components.file

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.data.main.DiskMenuPermission
import com.folderspan.test.ChineseLocalizationTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import strings.AppStrings

@OptIn(ExperimentalTestApi::class)
class FileMenuTest : ChineseLocalizationTest() {
    @Test
    fun copyAndStorageArrowRunSeparateActionsAndDismissMenu() = runSkikoComposeUiTest(size = Size(240f, 230f)) {
        val actions = mutableListOf<String>()
        setContent {
            MaterialTheme {
                FileMenu(
                    permission = DiskMenuPermission(copy = true, move = true, delete = true),
                    onCopy = { actions += "copy" },
                    onStorageCopy = { actions += "storage" },
                )
            }
        }

        onNodeWithContentDescription(AppStrings.ui_more_actions).performClick()
        onNodeWithText(AppStrings.ui_storage_multi_copy).assertDoesNotExist()
        onNodeWithContentDescription(AppStrings.ui_storage_multi_copy).assertIsDisplayed()
        val screenshot = File("build/reports/file-menu/copy-actions.png")
        screenshot.parentFile.mkdirs()
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", screenshot)

        onNodeWithText(AppStrings.ui_copy).performTouchInput { click() }
        runOnIdle { assertEquals(listOf("copy"), actions) }
        onNodeWithText(AppStrings.ui_copy).assertDoesNotExist()

        onNodeWithContentDescription(AppStrings.ui_more_actions).performClick()
        onNodeWithContentDescription(AppStrings.ui_storage_multi_copy).performTouchInput { click() }
        runOnIdle { assertEquals(listOf("copy", "storage"), actions) }
        onNodeWithText(AppStrings.ui_copy).assertDoesNotExist()
    }

    @Test
    fun storageArrowIsHiddenWithoutStorageAction() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FileMenuItems(permission = DiskMenuPermission(copy = true))
            }
        }

        onNodeWithText(AppStrings.ui_copy).assertIsDisplayed()
        onNodeWithContentDescription(AppStrings.ui_storage_multi_copy).assertDoesNotExist()
    }

    @Test
    fun storageArrowIsHiddenWithoutCopyPermission() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FileMenuItems(permission = DiskMenuPermission(info = true), onStorageCopy = {})
            }
        }

        onNodeWithText(AppStrings.ui_copy).assertDoesNotExist()
        onNodeWithContentDescription(AppStrings.ui_storage_multi_copy).assertDoesNotExist()
    }
}

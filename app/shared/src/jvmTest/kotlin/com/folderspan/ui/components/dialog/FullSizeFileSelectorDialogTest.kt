package com.folderspan.ui.components.dialog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ui.components.file.FILE_SELECTOR_GRID_TEST_TAG
import com.folderspan.ui.components.file.FILE_SELECTOR_LIST_TEST_TAG
import com.folderspan.ui.components.file.FileSelectorEntriesRegion
import com.folderspan.ui.components.file.FileSelectorEntryUiModel
import kotlin.test.Test
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class FullSizeFileSelectorDialogTest {
    @Test
    fun fullSizeDialogDisablesPlatformWidthAndOutsideDismiss() {
        val properties = fullSizeFileSelectorDialogProperties()

        assertFalse(properties.usePlatformDefaultWidth)
        assertFalse(properties.dismissOnClickOutside)
    }

    @Test
    fun compactLayoutFillsBoundsAndKeepsHeaderAndActionsVisibleDuringEntryScroll() =
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        FullSizeFileSelectorDialogLayout(
                            title = { Text("selector-title") },
                            dismissButton = { Text("selector-cancel") },
                            confirmButton = { Text("selector-confirm") },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            TestSelectorEntries(count = 100)
                        }
                    }
                }
            }

            onNodeWithTag(FULL_SIZE_FILE_SELECTOR_DIALOG_LAYOUT_TEST_TAG)
                .assertWidthIsEqualTo(360.dp)
                .assertHeightIsEqualTo(640.dp)
            onNodeWithTag(FILE_SELECTOR_LIST_TEST_TAG).performScrollToIndex(90)
            onNodeWithText("selector-title").assertIsDisplayed()
            onNodeWithText("selector-cancel").assertIsDisplayed()
            onNodeWithText("selector-confirm").assertIsDisplayed()
        }

    @Test
    fun expandedLayoutFillsBoundsAndLetsSelectorActivateGrid() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 700.dp)) {
                    FullSizeFileSelectorDialogLayout(
                        title = { Text("selector-title") },
                        dismissButton = { Text("selector-cancel") },
                        confirmButton = { Text("selector-confirm") },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        TestSelectorEntries(count = 20)
                    }
                }
            }
        }

        onNodeWithTag(FULL_SIZE_FILE_SELECTOR_DIALOG_LAYOUT_TEST_TAG)
            .assertWidthIsEqualTo(900.dp)
            .assertHeightIsEqualTo(700.dp)
        onNodeWithTag(FILE_SELECTOR_GRID_TEST_TAG).assertIsDisplayed()
        onNodeWithText("selector-title").assertIsDisplayed()
        onNodeWithText("selector-cancel").assertIsDisplayed()
        onNodeWithText("selector-confirm").assertIsDisplayed()
    }

    @Composable
    private fun TestSelectorEntries(count: Int) {
        FileSelectorEntriesRegion(
            entries = (0 until count).map { index ->
                val file = FileSimpleInfo(
                    name = "item-$index.txt",
                    isDirectory = false,
                    isHidden = false,
                    path = "/item-$index.txt",
                    mineType = "txt",
                    size = index.toLong(),
                    createdDate = 0,
                    updatedDate = 0,
                )
                FileSelectorEntryUiModel(
                    file = file,
                    isSelected = false,
                    selectionRejection = null,
                )
            },
            path = "/",
            onActivate = {},
            onToggleSelection = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

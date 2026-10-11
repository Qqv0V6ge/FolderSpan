package com.folderspan.ui.components.file

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileSimpleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FileSelectorEntriesRegionTest {
    @Test
    fun compactWidthUsesInformationDenseList() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(599.dp, 320.dp)) {
                    TestEntryRegion(entries = sampleEntries())
                }
            }
        }

        onNodeWithTag(FILE_SELECTOR_LIST_TEST_TAG).assertIsDisplayed()
        onAllNodesWithTag(FILE_SELECTOR_GRID_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun expandedWidthUsesMultipleAdaptiveGridColumns() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(600.dp, 320.dp)) {
                    TestEntryRegion(entries = sampleEntries())
                }
            }
        }

        onNodeWithTag(FILE_SELECTOR_GRID_TEST_TAG).assertIsDisplayed()
        onAllNodesWithTag(FILE_SELECTOR_LIST_TEST_TAG).assertCountEquals(0)
        lateinit var firstBounds: DpRect
        lateinit var secondBounds: DpRect
        runOnIdle {
            firstBounds = onNodeWithTag(fileSelectorEntryTestTag("/first.txt"))
                .getUnclippedBoundsInRoot()
            secondBounds = onNodeWithTag(fileSelectorEntryTestTag("/second.txt"))
                .getUnclippedBoundsInRoot()
        }
        assertEquals(firstBounds.top, secondBounds.top)
        assertTrue(secondBounds.left > firstBounds.left)
    }

    @Test
    fun wideGridKeepsTilesLargeEnoughToReadComfortably() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(1200.dp, 400.dp)) {
                    TestEntryRegion(entries = sampleEntries())
                }
            }
        }

        lateinit var firstBounds: DpRect
        runOnIdle {
            firstBounds = onNodeWithTag(fileSelectorEntryTestTag("/first.txt"))
                .getUnclippedBoundsInRoot()
        }

        assertTrue(firstBounds.right - firstBounds.left >= 280.dp)
        assertTrue(firstBounds.bottom - firstBounds.top >= 168.dp)
    }

    @Test
    fun runtimeResizeRetainsSelectionAndRestoresNearestVisibleEntry() = runComposeUiTest {
        var width by mutableStateOf(599.dp)
        var selected by mutableStateOf(false)
        val files = (0 until 80).map { index -> file("item-$index.txt") }

        setContent {
            MaterialTheme {
                Box(Modifier.width(width).height(240.dp)) {
                    FileSelectorEntriesRegion(
                        entries = files.map { item ->
                            FileSelectorEntryUiModel(
                                file = item,
                                isSelected = selected && item.name == "item-40.txt",
                                selectionRejection = null,
                            )
                        },
                        path = "/",
                        onActivate = { entry ->
                            if (entry.file.name == "item-40.txt") selected = true
                        },
                        onToggleSelection = {},
                        modifier = Modifier.size(width, 240.dp),
                    )
                }
            }
        }

        onNodeWithTag(FILE_SELECTOR_LIST_TEST_TAG).performScrollToIndex(40)
        onNodeWithTag(fileSelectorEntryTestTag("/item-40.txt")).performClick()
        onNodeWithTag(fileSelectorEntryTestTag("/item-40.txt")).assertIsSelected()

        runOnIdle { width = 800.dp }
        waitForIdle()

        onNodeWithTag(FILE_SELECTOR_GRID_TEST_TAG).assertIsDisplayed()
        onNodeWithTag(fileSelectorEntryTestTag("/item-40.txt"))
            .assertIsDisplayed()
            .assertIsSelected()
    }

    @Test
    fun rejectedEntryExposesLocalizedReasonAndStillReportsActivation() = runComposeUiTest {
        val violation = FileSelectorConstraintViolation.FileTooLarge(
            actualSizeBytes = 30,
            maxSizeBytes = 20,
        )
        val expectedMessage = violation.localizedMessage()
        var activatedPath: String? = null

        setContent {
            MaterialTheme {
                Box(Modifier.size(599.dp, 240.dp)) {
                    FileSelectorEntriesRegion(
                        entries = listOf(
                            FileSelectorEntryUiModel(
                                file = file("large.log", size = 30),
                                isSelected = false,
                                selectionRejection = FileSelectorSelectionRejection.Constraint(violation),
                            ),
                        ),
                        path = "/",
                        onActivate = { entry -> activatedPath = entry.file.path },
                        onToggleSelection = {},
                        modifier = Modifier.size(599.dp, 240.dp),
                    )
                }
            }
        }

        onNodeWithTag(fileSelectorEntryTestTag("/large.log"))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    expectedMessage,
                ),
            )
            .performClick()
        assertEquals("/large.log", activatedPath)
    }

    @Test
    fun gridRejectedEntryExposesTheSameLocalizedReason() = runComposeUiTest {
        val violation = FileSelectorConstraintViolation.FileTooLarge(
            actualSizeBytes = 30,
            maxSizeBytes = 20,
        )

        setContent {
            MaterialTheme {
                Box(Modifier.size(800.dp, 240.dp)) {
                    FileSelectorEntriesRegion(
                        entries = listOf(
                            FileSelectorEntryUiModel(
                                file = file("large.log", size = 30),
                                isSelected = false,
                                selectionRejection = FileSelectorSelectionRejection.Constraint(violation),
                            ),
                        ),
                        path = "/",
                        onActivate = {},
                        onToggleSelection = {},
                        modifier = Modifier.size(800.dp, 240.dp),
                    )
                }
            }
        }

        onNodeWithTag(fileSelectorEntryTestTag("/large.log"))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    violation.localizedMessage(),
                ),
            )
    }

    @Test
    fun gridDirectoryUsesTheSameActivationCallback() = runComposeUiTest {
        var activatedPath: String? = null
        val directory = file("nested", isDirectory = true)

        setContent {
            MaterialTheme {
                Box(Modifier.size(800.dp, 240.dp)) {
                    FileSelectorEntriesRegion(
                        entries = listOf(
                            FileSelectorEntryUiModel(
                                file = directory,
                                isSelected = false,
                                selectionRejection = null,
                            ),
                        ),
                        path = "/",
                        onActivate = { entry -> activatedPath = entry.file.path },
                        onToggleSelection = {},
                        modifier = Modifier.size(800.dp, 240.dp),
                    )
                }
            }
        }

        onNodeWithTag(fileSelectorEntryTestTag(directory.path)).performClick()
        assertEquals(directory.path, activatedPath)
    }

    @androidx.compose.runtime.Composable
    private fun TestEntryRegion(entries: List<FileSelectorEntryUiModel>) {
        FileSelectorEntriesRegion(
            entries = entries,
            path = "/",
            onActivate = {},
            onToggleSelection = {},
            modifier = Modifier.fillMaxSize(),
        )
    }

    private fun sampleEntries(): List<FileSelectorEntryUiModel> = listOf(
        file("first.txt"),
        file("second.txt"),
        file("third.txt"),
    ).map { item ->
        FileSelectorEntryUiModel(
            file = item,
            isSelected = false,
            selectionRejection = null,
        )
    }

    private fun file(
        name: String,
        size: Long = 1,
        isDirectory: Boolean = false,
    ) = FileSimpleInfo(
        name = name,
        isDirectory = isDirectory,
        isHidden = false,
        path = "/$name",
        mineType = name.substringAfterLast('.', ""),
        size = size,
        createdDate = 0,
        updatedDate = 0,
    )
}

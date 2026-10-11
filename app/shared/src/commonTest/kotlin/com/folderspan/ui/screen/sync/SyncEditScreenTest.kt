package com.folderspan.ui.screen.sync

import strings.AppStrings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.ui.state.main.SyncEndpointType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SyncEditScreenTest {
    @Test
    fun advancedIgnoreOnlySwitchTogglesAndPreservesSelectionWhenDisabled() = runComposeUiTest {
        var enabled by mutableStateOf(false)
        var selectionRequests = 0
        val selectedFileNames = listOf(".gitignore")

        setContent {
            MaterialTheme {
                AdvancedIgnoreConfiguration(
                    enabled = enabled,
                    selectedFileNames = selectedFileNames,
                    availableFileNames = listOf(".gitignore"),
                    onEnabledChange = { value -> enabled = value },
                    onSelectFiles = { selectionRequests++ },
                )
            }
        }

        onNodeWithText(AppStrings.ui_advanced_ignore).assert(hasClickAction().not())
        onNodeWithText(AppStrings.ui_advanced_ignore_description).assert(hasClickAction().not())
        onNodeWithTag("sync-advanced-ignore-switch")
            .assertIsOff()
            .performClick()
            .assertIsOn()
        assertTrue(enabled)
        onNodeWithTag("sync-ignore-file-selector").performClick()
        assertEquals(1, selectionRequests)

        onNodeWithTag("sync-advanced-ignore-switch")
            .performClick()
            .assertIsOff()
        assertFalse(enabled)
        assertEquals(listOf(".gitignore"), selectedFileNames)
    }

    @Test
    fun ignoreFileDialogSupportsAccessibleMultipleSelection() = runComposeUiTest {
        var selectedFileNames by mutableStateOf(emptyList<String>())

        setContent {
            MaterialTheme {
                IgnoreFileSelectionDialog(
                    availableFileNames = listOf(".gitignore", ".dockerignore"),
                    selectedFileNames = selectedFileNames,
                    onSelectionChange = { fileNames -> selectedFileNames = fileNames },
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("sync-ignore-file-.gitignore")
            .assertIsOff()
            .performClick()
            .assertIsOn()
        onNodeWithTag("sync-ignore-file-.dockerignore")
            .assertIsOff()
            .performClick()
            .assertIsOn()

        assertEquals(listOf(".gitignore", ".dockerignore"), selectedFileNames)
        onNodeWithTag("sync-ignore-file-.gitignore").performClick().assertIsOff()
        assertEquals(listOf(".dockerignore"), selectedFileNames)
        onAllNodesWithTag("sync-ignore-file-.npmignore").assertCountEquals(0)
    }

    @Test
    fun advancedIgnoreHidesSelectorWhenSourceHasNoIgnoreFiles() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AdvancedIgnoreConfiguration(
                    enabled = true,
                    selectedFileNames = emptyList(),
                    availableFileNames = emptyList(),
                    onEnabledChange = {},
                    onSelectFiles = {},
                )
            }
        }

        onAllNodesWithTag("sync-ignore-file-selector").assertCountEquals(0)
        onAllNodesWithContentDescription(AppStrings.ui_select_ignore_files).assertCountEquals(0)
    }

    @Test
    fun ignoreFileSelectionEncodingNormalizesOrderAndDuplicates() {
        val encoded = encodeIgnoreFileSelection(
            listOf(".dockerignore", ".gitignore", ".dockerignore", "unsupported"),
        )

        assertEquals(listOf(".gitignore", ".dockerignore"), decodeIgnoreFileSelection(encoded))
    }

    @Test
    fun retainAvailableIgnoreFileSelectionRemovesMissingAndUnsupportedNames() {
        assertEquals(
            listOf(".gitignore"),
            retainAvailableIgnoreFileSelection(
                selectedFileNames = listOf(".dockerignore", ".gitignore", "unsupported"),
                availableFileNames = listOf(".gitignore", ".npmignore"),
            ),
        )
    }

    @Test
    fun syncSourceChangedDetectsTypeEndpointAndPathChangesOnly() {
        assertFalse(
            syncSourceChanged(
                previousType = SyncEndpointType.Local,
                previousRef = "",
                previousPath = "/source",
                currentType = SyncEndpointType.Local,
                currentRef = "",
                currentPath = "/source",
            )
        )
        assertTrue(
            syncSourceChanged(
                previousType = SyncEndpointType.Local,
                previousRef = "",
                previousPath = "/source",
                currentType = SyncEndpointType.Device,
                currentRef = "device-1",
                currentPath = "/source",
            )
        )
        assertTrue(
            syncSourceChanged(
                previousType = SyncEndpointType.Device,
                previousRef = "device-1",
                previousPath = "/source",
                currentType = SyncEndpointType.Device,
                currentRef = "device-2",
                currentPath = "/source",
            )
        )
        assertTrue(
            syncSourceChanged(
                previousType = SyncEndpointType.Local,
                previousRef = "",
                previousPath = "/source",
                currentType = SyncEndpointType.Local,
                currentRef = "",
                currentPath = "/other",
            )
        )
    }
}

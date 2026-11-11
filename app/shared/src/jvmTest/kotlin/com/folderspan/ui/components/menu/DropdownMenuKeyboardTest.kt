package com.folderspan.ui.components.menu

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.ui.components.model.StringListUiState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DropdownMenuKeyboardTest {
    @Test
    fun readOnlyOptionsSupportKeyboardSelection() = runComposeUiTest {
        val selected = mutableStateOf("FTP")
        setContent {
            MaterialTheme {
                EditableExposedDropdownMenu(
                    optionsUiState = StringListUiState(listOf("FTP", "Nextcloud", "Seafile")),
                    value = selected.value,
                    onValueChange = { selected.value = it },
                    readOnly = true,
                    modifier = Modifier.testTag("protocol"),
                )
            }
        }

        onNodeWithTag("protocol").performClick()
        onNodeWithText("Nextcloud").assertIsDisplayed()
        onAllNodes(isRoot()).onLast().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        runOnIdle { assertEquals("Nextcloud", selected.value) }
    }
}

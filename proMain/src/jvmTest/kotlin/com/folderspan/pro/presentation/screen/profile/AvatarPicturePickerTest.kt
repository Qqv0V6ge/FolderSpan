package com.folderspan.pro.presentation.screen.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AvatarPicturePickerTest {
    @Test
    fun absentProviderLeavesPictureActionUnavailableAndSafeToActivate() = runComposeUiTest {
        var outcomeCount = 0
        setContent {
            MaterialTheme {
                AvatarPictureAction(
                    maxOutputBytes = 1_000,
                    onOutcome = { outcomeCount++ },
                ) {
                    Text("Choose picture")
                }
            }
        }

        onNodeWithText("Choose picture")
            .assertIsNotEnabled()
            .performClick()
        assertEquals(0, outcomeCount)
    }
}

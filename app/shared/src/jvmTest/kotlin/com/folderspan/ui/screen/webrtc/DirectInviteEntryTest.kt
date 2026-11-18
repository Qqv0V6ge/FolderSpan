package com.folderspan.ui.screen.webrtc

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.data.main.webrtc.WebRtcShareCode
import kotlin.test.Test
import kotlin.test.assertFalse
import strings.AppStrings

@OptIn(ExperimentalTestApi::class)
class DirectInviteEntryTest {
    @Test
    fun invalidCodeCanBeReplacedWithoutRoomDetailsOrOfficialLogin() = runComposeUiTest {
        setContent {
            MaterialTheme {
                var code by remember { mutableStateOf("") }
                var error by remember { mutableStateOf("") }
                Column {
                    DirectInviteEntry(code, working = false, onChange = { code = it; error = "" }) {
                        try {
                            WebRtcShareCode.decodeDirectInvite(code)
                        } catch (failure: Exception) {
                            error = directInviteError(failure)
                            assertFalse(error.contains(code))
                        }
                    }
                    if (error.isNotEmpty()) Text(error)
                }
            }
        }
        onNodeWithText(AppStrings.direct_invite_join).assertIsNotEnabled()
        onNodeWithText(AppStrings.direct_invite_code).performTextReplacement("invalid-private-input")
        onNodeWithText(AppStrings.direct_invite_join).assertIsEnabled().performClick()
        onNodeWithText(AppStrings.direct_invite_invalid).assertExists()
        onNodeWithText(AppStrings.direct_invite_code).performTextReplacement("different-code")
        onNodeWithText(AppStrings.direct_invite_invalid).assertDoesNotExist()
        onNodeWithText(AppStrings.direct_invite_join).assertIsEnabled()
    }
}

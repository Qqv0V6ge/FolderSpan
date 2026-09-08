package com.folderspan.ui.screen.crash

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.ui.state.main.CrashActionAvailability
import com.folderspan.ui.state.main.CrashInfo
import com.folderspan.ui.state.main.toScreenState
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class CrashScreenFeedbackTest {
    @Test
    fun feedbackButtonVisibleOnlyWhenCaptureIsOn() = runComposeUiTest {
        val state = CrashInfo("Boom", "failed", "stack").toScreenState(
            CrashActionAvailability(canRestart = true, canExit = true, canCopy = true),
        )
        setContent {
            MaterialTheme {
                CrashScreen(state = state, onRestart = {}, showFeedback = false)
            }
        }
        onAllNodesWithTag("crash-feedback").assertCountEquals(0)
    }

    @Test
    fun feedbackButtonShownWhenCaptureIsOn() = runComposeUiTest {
        val state = CrashInfo("Boom", "failed", "stack").toScreenState(
            CrashActionAvailability(canRestart = true, canExit = true, canCopy = true),
        )
        setContent {
            MaterialTheme {
                CrashScreen(state = state, onRestart = {}, showFeedback = true)
            }
        }
        onNodeWithTag("crash-feedback").assertIsDisplayed()
    }
}

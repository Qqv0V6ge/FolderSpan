package com.folderspan

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AppStartupTest {
    @Test
    fun deferredRuntimeIsNotComposedUntilTheFirstFrame() = runComposeUiTest {
        var initialUiDrawn = false
        var firstFrameReported = false
        var callbackObservedInitialDraw = false
        var runtimeComposed = false

        setContent {
            Box(
                Modifier
                    .size(1.dp)
                    .drawBehind { initialUiDrawn = true },
            )
            val runtimeReady = rememberAppRuntimeReady(
                onFirstFrameRendered = {
                    callbackObservedInitialDraw = initialUiDrawn
                    firstFrameReported = true
                },
            )
            if (runtimeReady) {
                SideEffect { runtimeComposed = true }
            }
        }

        waitForIdle()

        assertTrue(initialUiDrawn)
        assertTrue(firstFrameReported)
        assertTrue(callbackObservedInitialDraw)
        assertTrue(runtimeComposed)
    }
}

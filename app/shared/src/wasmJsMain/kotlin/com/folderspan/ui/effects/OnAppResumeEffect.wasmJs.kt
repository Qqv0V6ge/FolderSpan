package com.folderspan.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import org.w3c.dom.events.Event
import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalWasmJsInterop::class)
private fun isDocumentVisible(): Boolean = js("document.hidden !== true")

@Composable
actual fun OnAppResumeEffect(onResume: () -> Unit) {
    val latestOnResume by rememberUpdatedState(onResume)

    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = {
            if (isDocumentVisible()) {
                latestOnResume()
            }
        }

        document.addEventListener("visibilitychange", listener)
        onDispose { document.removeEventListener("visibilitychange", listener) }
    }
}

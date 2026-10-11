package com.folderspan.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import org.w3c.dom.events.Event
import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalWasmJsInterop::class)
private fun isDocumentVisibleForNotifications(): Boolean = js("document.hidden !== true")

@Composable
actual fun UserNotificationLifecycleEffect(onForegroundChanged: (Boolean) -> Unit) {
    val currentOnForegroundChanged by rememberUpdatedState(onForegroundChanged)
    DisposableEffect(Unit) {
        fun updateVisibility() {
            if (isDocumentVisibleForNotifications()) currentOnForegroundChanged(true)
            else currentOnForegroundChanged(false)
        }
        val listener: (Event) -> Unit = { updateVisibility() }
        updateVisibility()
        document.addEventListener("visibilitychange", listener)
        onDispose {
            document.removeEventListener("visibilitychange", listener)
            currentOnForegroundChanged(false)
        }
    }
}

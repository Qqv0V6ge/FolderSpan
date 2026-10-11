package com.folderspan.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import org.w3c.dom.events.Event

@Composable
actual fun UserNotificationLifecycleEffect(onForegroundChanged: (Boolean) -> Unit) {
    val currentOnForegroundChanged by rememberUpdatedState(onForegroundChanged)
    DisposableEffect(Unit) {
        fun updateVisibility() {
            if (document.asDynamic().hidden == true) currentOnForegroundChanged(false)
            else currentOnForegroundChanged(true)
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

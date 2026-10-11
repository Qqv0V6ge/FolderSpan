package com.folderspan.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import org.w3c.dom.events.Event

@Composable
actual fun OnAppResumeEffect(onResume: () -> Unit) {
    val latestOnResume by rememberUpdatedState(onResume)

    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = {
            val hidden = document.asDynamic().hidden as Boolean?
            if (hidden != true) {
                latestOnResume()
            }
        }

        document.addEventListener("visibilitychange", listener)
        onDispose { document.removeEventListener("visibilitychange", listener) }
    }
}

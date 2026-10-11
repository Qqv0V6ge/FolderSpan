package com.folderspan.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener

@Composable
actual fun OnAppResumeEffect(onResume: () -> Unit) {
    val latestOnResume by rememberUpdatedState(onResume)

    DisposableEffect(Unit) {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val listener = PropertyChangeListener { event ->
            if (event.propertyName != "activeWindow") return@PropertyChangeListener
            if (event.newValue != null && event.oldValue == null) {
                latestOnResume()
            }
        }
        focusManager.addPropertyChangeListener(listener)
        onDispose { focusManager.removePropertyChangeListener(listener) }
    }
}


package com.folderspan.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
actual fun UserNotificationLifecycleEffect(onForegroundChanged: (Boolean) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnForegroundChanged by rememberUpdatedState(onForegroundChanged)
    DisposableEffect(lifecycle) {
        currentOnForegroundChanged(true)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> currentOnForegroundChanged(true)
                Lifecycle.Event.ON_STOP -> currentOnForegroundChanged(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            currentOnForegroundChanged(false)
        }
    }
}

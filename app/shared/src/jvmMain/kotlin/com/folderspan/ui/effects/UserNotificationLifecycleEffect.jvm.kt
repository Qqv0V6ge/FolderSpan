package com.folderspan.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

@Composable
actual fun UserNotificationLifecycleEffect(onForegroundChanged: (Boolean) -> Unit) {
    DisposableEffect(onForegroundChanged) {
        onForegroundChanged(true)
        onDispose {
            onForegroundChanged(false)
        }
    }
}

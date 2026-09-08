package com.folderspan.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

@Composable
actual fun UserNotificationLifecycleEffect(onForegroundChanged: (Boolean) -> Unit) {
    val currentOnForegroundChanged by rememberUpdatedState(onForegroundChanged)
    DisposableEffect(Unit) {
        currentOnForegroundChanged(true)
        val center = NSNotificationCenter.defaultCenter
        val background = center.addObserverForName(
            UIApplicationDidEnterBackgroundNotification,
            null,
            null,
        ) { currentOnForegroundChanged(false) }
        val foreground = center.addObserverForName(
            UIApplicationWillEnterForegroundNotification,
            null,
            null,
        ) { currentOnForegroundChanged(true) }
        onDispose {
            center.removeObserver(background)
            center.removeObserver(foreground)
            currentOnForegroundChanged(false)
        }
    }
}

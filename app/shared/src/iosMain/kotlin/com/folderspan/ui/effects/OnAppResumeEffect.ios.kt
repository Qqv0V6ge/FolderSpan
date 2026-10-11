package com.folderspan.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidBecomeActiveNotification

@Composable
actual fun OnAppResumeEffect(onResume: () -> Unit) {
    val latestOnResume by rememberUpdatedState(onResume)

    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = null
        ) { _ ->
            latestOnResume()
        }

        onDispose { center.removeObserver(observer) }
    }
}


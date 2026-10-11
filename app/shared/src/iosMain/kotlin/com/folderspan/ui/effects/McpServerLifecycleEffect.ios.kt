package com.folderspan.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.folderspan.service.mcp.http.McpHttpServiceLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

@Composable
actual fun McpServerLifecycleEffect(lifecycle: McpHttpServiceLifecycle) {
    val currentLifecycle by rememberUpdatedState(lifecycle)
    val worker = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    LaunchedEffect(lifecycle) {
        lifecycle.enterForeground()
    }
    DisposableEffect(worker) {
        val center = NSNotificationCenter.defaultCenter
        val backgroundObserver = center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = null,
        ) { _ -> worker.launch { currentLifecycle.enterBackground() } }
        val foregroundObserver = center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = null,
        ) { _ -> worker.launch { currentLifecycle.enterForeground() } }

        onDispose {
            center.removeObserver(backgroundObserver)
            center.removeObserver(foregroundObserver)
            worker.launch { currentLifecycle.enterBackground() }
                .invokeOnCompletion { worker.cancel() }
        }
    }
}

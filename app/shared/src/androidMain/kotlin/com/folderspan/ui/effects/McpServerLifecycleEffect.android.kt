package com.folderspan.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.folderspan.service.mcp.http.McpHttpServiceLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@Composable
actual fun McpServerLifecycleEffect(lifecycle: McpHttpServiceLifecycle) {
    val appLifecycle = LocalLifecycleOwner.current.lifecycle
    val currentLifecycle by rememberUpdatedState(lifecycle)
    val worker = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    LaunchedEffect(lifecycle) {
        lifecycle.enterForeground()
    }
    DisposableEffect(appLifecycle, worker) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> worker.launch { currentLifecycle.enterForeground() }
                Lifecycle.Event.ON_STOP -> worker.launch { currentLifecycle.enterBackground() }
                else -> Unit
            }
        }
        appLifecycle.addObserver(observer)
        onDispose {
            appLifecycle.removeObserver(observer)
            worker.launch { currentLifecycle.enterBackground() }
                .invokeOnCompletion { worker.cancel() }
        }
    }
}

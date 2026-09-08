package com.folderspan.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.folderspan.service.mcp.http.McpHttpServiceLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@Composable
actual fun McpServerLifecycleEffect(lifecycle: McpHttpServiceLifecycle) {
    val worker = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    LaunchedEffect(lifecycle) { lifecycle.enterForeground() }
    DisposableEffect(worker) {
        onDispose {
            worker.launch { lifecycle.enterBackground() }
                .invokeOnCompletion { worker.cancel() }
        }
    }
}

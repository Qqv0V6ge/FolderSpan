package com.folderspan.ui.effects

import androidx.compose.runtime.Composable
import com.folderspan.service.mcp.http.McpHttpServiceLifecycle

@Composable
expect fun McpServerLifecycleEffect(lifecycle: McpHttpServiceLifecycle)

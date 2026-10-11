package com.folderspan.ui.components.crash

import androidx.compose.runtime.Composable

@Composable
fun CrashBoundary(
    content: @Composable () -> Unit,
) {
    content()
}

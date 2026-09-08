package com.folderspan.ui.effects

import androidx.compose.runtime.Composable

@Composable
expect fun OnAppResumeEffect(
    onResume: () -> Unit
)


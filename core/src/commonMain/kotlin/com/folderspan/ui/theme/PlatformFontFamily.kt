package com.folderspan.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontFamily

@Immutable
data class PlatformFontState(
    val fontFamily: FontFamily? = null,
    val isLoading: Boolean = false,
)

@Composable
expect fun platformFontState(): PlatformFontState

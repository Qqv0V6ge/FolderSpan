package com.folderspan.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontFamily
import com.folderspan.shared.generated.resources.Res
import com.folderspan.shared.generated.resources.noto_sans_sc_regular
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont

@OptIn(ExperimentalResourceApi::class)
@Composable
actual fun platformFontState(): PlatformFontState {
    val font by preloadFont(Res.font.noto_sans_sc_regular)
    return PlatformFontState(
        fontFamily = font?.let { FontFamily(it) },
        isLoading = font == null,
    )
}

package com.folderspan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
actual fun isPlatformInDarkTheme(): Boolean = isSystemInDarkTheme()

@Composable
actual fun SystemAppearance(isDark: Boolean, colorScheme: ColorScheme) {
}

@Composable
actual fun dynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

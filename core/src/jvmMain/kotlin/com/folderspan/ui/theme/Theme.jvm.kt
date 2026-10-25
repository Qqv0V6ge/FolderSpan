package com.folderspan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
actual fun isPlatformInDarkTheme(): Boolean {
    val isDark by DesktopSystemTheme.isDark.collectAsState()
    return isDark ?: isSystemInDarkTheme()
}

@Composable
actual fun SystemAppearance(isDark: Boolean, colorScheme: ColorScheme) {
}

@Composable
actual fun dynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

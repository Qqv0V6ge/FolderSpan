package com.folderspan.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun SystemAppearance(isDark: Boolean, colorScheme: ColorScheme) {
    val view = LocalView.current
    val systemBarColor = colorScheme.background.toArgb()
    LaunchedEffect(isDark, systemBarColor) {
        val window = (view.context as Activity).window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = systemBarColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = systemBarColor
        WindowCompat.getInsetsController(window, window.decorView).apply {
            val lightAppearance = !isDark
            isAppearanceLightStatusBars = lightAppearance
            isAppearanceLightNavigationBars = lightAppearance
        }
    }
}

@Composable
actual fun dynamicColorScheme(darkTheme: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
}

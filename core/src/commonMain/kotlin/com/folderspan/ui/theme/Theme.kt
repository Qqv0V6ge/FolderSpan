package com.folderspan.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.folderspan.service.data.SerializableColorScheme

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)

@Composable
fun FolderSpanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    overrideColorScheme: ColorScheme? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = overrideColorScheme ?: when {
        dynamicColor -> dynamicColorScheme(darkTheme)
        else -> null
    } ?: when {
        darkTheme -> darkScheme
        else -> lightScheme
    }

    SystemAppearance(darkTheme, colorScheme)

    val platformFontState = platformFontState()
    val typography = platformFontState.fontFamily
        ?.let { Typography.withFontFamily(it) }
        ?: Typography

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
    ) {
        if (platformFontState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            content()
        }
    }
}

@Composable
expect fun SystemAppearance(isDark: Boolean, colorScheme: ColorScheme)

@Composable
expect fun dynamicColorScheme(darkTheme: Boolean): ColorScheme?

// ColorScheme 转换函数
fun ColorScheme.toSerializable(): SerializableColorScheme {
    return SerializableColorScheme(
        primary = primary.toArgb(),
        onPrimary = onPrimary.toArgb(),
        primaryContainer = primaryContainer.toArgb(),
        onPrimaryContainer = onPrimaryContainer.toArgb(),
        secondary = secondary.toArgb(),
        onSecondary = onSecondary.toArgb(),
        secondaryContainer = secondaryContainer.toArgb(),
        onSecondaryContainer = onSecondaryContainer.toArgb(),
        tertiary = tertiary.toArgb(),
        onTertiary = onTertiary.toArgb(),
        tertiaryContainer = tertiaryContainer.toArgb(),
        onTertiaryContainer = onTertiaryContainer.toArgb(),
        error = error.toArgb(),
        onError = onError.toArgb(),
        errorContainer = errorContainer.toArgb(),
        onErrorContainer = onErrorContainer.toArgb(),
        background = background.toArgb(),
        onBackground = onBackground.toArgb(),
        surface = surface.toArgb(),
        onSurface = onSurface.toArgb(),
        surfaceVariant = surfaceVariant.toArgb(),
        onSurfaceVariant = onSurfaceVariant.toArgb(),
        outline = outline.toArgb(),
        outlineVariant = outlineVariant.toArgb(),
        scrim = scrim.toArgb(),
        inverseSurface = inverseSurface.toArgb(),
        inverseOnSurface = inverseOnSurface.toArgb(),
        inversePrimary = inversePrimary.toArgb(),
        surfaceDim = surfaceDim.toArgb(),
        surfaceBright = surfaceBright.toArgb(),
        surfaceContainerLowest = surfaceContainerLowest.toArgb(),
        surfaceContainerLow = surfaceContainerLow.toArgb(),
        surfaceContainer = surfaceContainer.toArgb(),
        surfaceContainerHigh = surfaceContainerHigh.toArgb(),
        surfaceContainerHighest = surfaceContainerHighest.toArgb()
    )
}

private fun argbColor(color: Int): Color = Color(color = color)

fun SerializableColorScheme.toColorScheme(): ColorScheme {
    return lightColorScheme(
        primary = argbColor(primary),
        onPrimary = argbColor(onPrimary),
        primaryContainer = argbColor(primaryContainer),
        onPrimaryContainer = argbColor(onPrimaryContainer),
        secondary = argbColor(secondary),
        onSecondary = argbColor(onSecondary),
        secondaryContainer = argbColor(secondaryContainer),
        onSecondaryContainer = argbColor(onSecondaryContainer),
        tertiary = argbColor(tertiary),
        onTertiary = argbColor(onTertiary),
        tertiaryContainer = argbColor(tertiaryContainer),
        onTertiaryContainer = argbColor(onTertiaryContainer),
        error = argbColor(error),
        onError = argbColor(onError),
        errorContainer = argbColor(errorContainer),
        onErrorContainer = argbColor(onErrorContainer),
        background = argbColor(background),
        onBackground = argbColor(onBackground),
        surface = argbColor(surface),
        onSurface = argbColor(onSurface),
        surfaceVariant = argbColor(surfaceVariant),
        onSurfaceVariant = argbColor(onSurfaceVariant),
        outline = argbColor(outline),
        outlineVariant = argbColor(outlineVariant),
        scrim = argbColor(scrim),
        inverseSurface = argbColor(inverseSurface),
        inverseOnSurface = argbColor(inverseOnSurface),
        inversePrimary = argbColor(inversePrimary),
        surfaceDim = argbColor(surfaceDim),
        surfaceBright = argbColor(surfaceBright),
        surfaceContainerLowest = argbColor(surfaceContainerLowest),
        surfaceContainerLow = argbColor(surfaceContainerLow),
        surfaceContainer = argbColor(surfaceContainer),
        surfaceContainerHigh = argbColor(surfaceContainerHigh),
        surfaceContainerHighest = argbColor(surfaceContainerHighest)
    )
}

// 获取默认的 ColorScheme（用于服务端）
fun getDefaultColorScheme(isDark: Boolean): ColorScheme {
    return if (isDark) darkScheme else lightScheme
}

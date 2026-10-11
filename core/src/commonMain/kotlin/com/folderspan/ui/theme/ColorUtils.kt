package com.folderspan.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeTonalSpot

private const val HEX_ALPHA_LENGTH = 8
private const val HEX_RGB_LENGTH = 6

private fun argbColor(color: Int): Color = Color(color = color)

/**
 * 将十六进制颜色字符串解析为 [Color]。支持 #RRGGBB 与 #AARRGGBB。
 */
fun parseHexColor(value: String?): Color? {
    if (value.isNullOrBlank()) return null
    val normalized = value.trim().removePrefix("#")
    val hexLength = normalized.length
    val longValue = normalized.toLongOrNull(16) ?: return null
    val argb = when (hexLength) {
        HEX_RGB_LENGTH -> 0xFF000000 or longValue
        HEX_ALPHA_LENGTH -> longValue
        else -> return null
    }
    return argbColor(argb.toInt())
}

/**
 * 自定义配色设置，使用单一种子颜色生成完整的 Material 3 调色板。
 *
 * @param seed 主题种子色
 * @param contentBased 是否基于内容生成（对应 Material You Content 方案）
 */
data class CustomColorScheme(
    val seed: Color,
    val contentBased: Boolean = false
)

/**
 * 使用 Material 3 Tonal Palette 算法生成符合规范的配色。
 */
fun ColorScheme.withCustomColors(
    customColors: CustomColorScheme,
    darkTheme: Boolean
): ColorScheme {
    val argb = customColors.seed.toArgb()
    val seedHct = Hct.fromInt(argb)
    val scheme: DynamicScheme = if (customColors.contentBased) {
        SchemeContent(seedHct, darkTheme, contrastLevel = 0.0)
    } else {
        SchemeTonalSpot(seedHct, darkTheme, contrastLevel = 0.0)
    }
    val tokens = MaterialDynamicColors()

    fun toColor(role: (MaterialDynamicColors) -> DynamicColor): Color {
        return argbColor(role(tokens).getArgb(scheme))
    }

    return copy(
        primary = toColor { item ->  item.primary() },
        onPrimary = toColor { item ->  item.onPrimary() },
        primaryContainer = toColor { item ->  item.primaryContainer() },
        onPrimaryContainer = toColor { item ->  item.onPrimaryContainer() },
        inversePrimary = toColor { item ->  item.inversePrimary() },
        secondary = toColor { item ->  item.secondary() },
        onSecondary = toColor { item ->  item.onSecondary() },
        secondaryContainer = toColor { item ->  item.secondaryContainer() },
        onSecondaryContainer = toColor { item ->  item.onSecondaryContainer() },
        tertiary = toColor { item ->  item.tertiary() },
        onTertiary = toColor { item ->  item.onTertiary() },
        tertiaryContainer = toColor { item ->  item.tertiaryContainer() },
        onTertiaryContainer = toColor { item ->  item.onTertiaryContainer() },
        background = toColor { item ->  item.background() },
        onBackground = toColor { item ->  item.onBackground() },
        surface = toColor { item ->  item.surface() },
        onSurface = toColor { item ->  item.onSurface() },
        surfaceVariant = toColor { item ->  item.surfaceVariant() },
        onSurfaceVariant = toColor { item ->  item.onSurfaceVariant() },
        surfaceTint = toColor { item ->  item.surfaceTint() },
        inverseSurface = toColor { item ->  item.inverseSurface() },
        inverseOnSurface = toColor { item ->  item.inverseOnSurface() },
        error = toColor { item ->  item.error() },
        onError = toColor { item ->  item.onError() },
        errorContainer = toColor { item ->  item.errorContainer() },
        onErrorContainer = toColor { item ->  item.onErrorContainer() },
        outline = toColor { item ->  item.outline() },
        outlineVariant = toColor { item ->  item.outlineVariant() },
        scrim = toColor { item ->  item.scrim() },
        surfaceBright = toColor { item ->  item.surfaceBright() },
        surfaceDim = toColor { item ->  item.surfaceDim() },
        surfaceContainerLowest = toColor { item ->  item.surfaceContainerLowest() },
        surfaceContainerLow = toColor { item ->  item.surfaceContainerLow() },
        surfaceContainer = toColor { item ->  item.surfaceContainer() },
        surfaceContainerHigh = toColor { item ->  item.surfaceContainerHigh() },
        surfaceContainerHighest = toColor { item ->  item.surfaceContainerHighest() },
        primaryFixed = toColor { item ->  item.primaryFixed() },
        primaryFixedDim = toColor { item ->  item.primaryFixedDim() },
        onPrimaryFixed = toColor { item ->  item.onPrimaryFixed() },
        onPrimaryFixedVariant = toColor { item ->  item.onPrimaryFixedVariant() },
        secondaryFixed = toColor { item ->  item.secondaryFixed() },
        secondaryFixedDim = toColor { item ->  item.secondaryFixedDim() },
        onSecondaryFixed = toColor { item ->  item.onSecondaryFixed() },
        onSecondaryFixedVariant = toColor { item ->  item.onSecondaryFixedVariant() },
        tertiaryFixed = toColor { item ->  item.tertiaryFixed() },
        tertiaryFixedDim = toColor { item ->  item.tertiaryFixedDim() },
        onTertiaryFixed = toColor { item ->  item.onTertiaryFixed() },
        onTertiaryFixedVariant = toColor { item ->  item.onTertiaryFixedVariant() },
    )
}

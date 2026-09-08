package com.folderspan.pro.presentation.screen.marketplace

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

enum class MarketplaceLayoutMode {
    Compact,
    Medium,
    Expanded,
}

fun resolveMarketplaceLayoutMode(windowWidthSizeClass: WindowWidthSizeClass): MarketplaceLayoutMode =
    when (windowWidthSizeClass) {
        WindowWidthSizeClass.Compact -> MarketplaceLayoutMode.Compact
        WindowWidthSizeClass.Medium -> MarketplaceLayoutMode.Medium
        else -> MarketplaceLayoutMode.Expanded
    }

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
fun resolveMarketplaceLayoutMode(windowSize: DpSize): MarketplaceLayoutMode =
    resolveMarketplaceLayoutMode(WindowSizeClass.calculateFromSize(windowSize).widthSizeClass)

fun resolveMarketplaceSectionColumns(
    availableWidth: Dp,
    minCardWidth: Dp,
    maxColumns: Int,
    gap: Dp = 12.dp,
): Int {
    val raw = ((availableWidth.value + gap.value) / (minCardWidth.value + gap.value)).toInt()
    return raw.coerceIn(1, maxColumns)
}

fun resolveBannerWidth(
    availableWidth: Dp,
    layoutMode: MarketplaceLayoutMode,
): Dp = when (layoutMode) {
    MarketplaceLayoutMode.Compact -> availableWidth.coerceAtMost(360.dp)
    MarketplaceLayoutMode.Medium -> (availableWidth * 0.62f).coerceIn(360.dp, 520.dp)
    MarketplaceLayoutMode.Expanded -> (availableWidth * 0.42f).coerceIn(380.dp, 520.dp)
}

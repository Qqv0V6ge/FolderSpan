package com.folderspan.ui.screen.settings

import strings.AppStrings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.folderspan.PlatformType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.ui.components.dialog.SeedColorPickerDialog
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.state.settings.SettingsState
import com.folderspan.ui.state.settings.ThemeMode
import com.folderspan.ui.theme.parseHexColor
import org.koin.compose.koinInject
import kotlin.math.roundToInt

class AppearanceSettingsScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val settingsState = koinInject<SettingsState>()

        val themeMode by settingsState.themeMode.collectAsState()
        val dynamicColorEnabled by settingsState.dynamicColorEnabled.collectAsState()
        val customColorEnabled by settingsState.customColorEnabled.collectAsState()
        val customSeedColor by settingsState.customSeedColor.collectAsState()
        val selectedLanguageMode by settingsState.appLanguageMode.collectAsState()
        val isAndroid = remember { PlatformType == DeviceType.Android }

        LaunchedEffect(isAndroid, dynamicColorEnabled) {
            if (!isAndroid && dynamicColorEnabled) {
                settingsState.setDynamicColorEnabled(false, sync = false)
            }
        }

        var showColorPicker by remember { mutableStateOf(false) }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.settings_appearance_language_title) },
                    navigationIcon = {
                        IconButton({ navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                )
            }
        ) { padding ->
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                item(
                    key = "appearance-section-header",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Text(
                        text = AppStrings.settings_appearance_title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item(
                    key = "appearance-theme-mode",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = AppStrings.ui_theme_mode,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                        ) {
                            ThemeMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = mode == themeMode,
                                    onClick = { settingsState.setThemeMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)
                                ) {
                                    Text(mode.title)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = themeMode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item(
                    key = "appearance-dynamic-color",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_dynamic_color_selection) },
                        supportingContent = {
                            Text(
                                if (isAndroid) AppStrings.ui_use_system_color_picker_generate_color_schemes_supported_platforms
                                else AppStrings.ui_android_platform_support_only
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = dynamicColorEnabled && isAndroid,
                                onCheckedChange = { item ->
                                    if (isAndroid) settingsState.setDynamicColorEnabled(item)
                                },
                                enabled = isAndroid && !customColorEnabled
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                item(
                    key = "appearance-custom-color-toggle",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_custom_color) },
                        supportingContent = { Text(AppStrings.ui_automatically_generate_complete_material_3_compliant_color_palette_using) },
                        trailingContent = {
                            Switch(
                                checked = customColorEnabled,
                                onCheckedChange = settingsState::setCustomColorEnabled
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                item(
                    key = "appearance-custom-color-controls",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    CollapsibleCustomColorControls(visible = customColorEnabled) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            PresetSeedPalette(
                                currentHex = customSeedColor
                            ) { hex ->
                                settingsState.setCustomSeedColor(hex)
                            }
                            SeedColorRow(
                                colorHex = customSeedColor,
                                onEdit = { showColorPicker = true }
                            )
                        }
                    }
                }

                item(
                    key = "language-section-header",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Text(
                        text = AppStrings.settings_language_title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                languageOptions(
                    selectedMode = selectedLanguageMode,
                    onModeSelected = settingsState::setAppLanguageMode,
                )
            }
        }

        if (showColorPicker) {
            SeedColorPickerDialog(
                currentHex = customSeedColor,
                onDismiss = { showColorPicker = false },
                onConfirm = { hex ->
                    settingsState.setCustomSeedColor(hex)
                    showColorPicker = false
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CollapsibleCustomColorControls(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "CustomColorControlsVisibility",
    )
    val slideDistance = with(LocalDensity.current) { 12.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                val animatedHeight = (placeable.height * progress.coerceIn(0f, 1f)).roundToInt()
                layout(
                    width = placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth),
                    height = animatedHeight.coerceIn(constraints.minHeight, constraints.maxHeight),
                ) {
                    placeable.placeRelative(0, 0)
                }
            }
            .semantics {
                if (!visible) hideFromAccessibility()
            },
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                val animatedProgress = progress.coerceIn(0f, 1f)
                alpha = animatedProgress
                translationY = (1f - animatedProgress) * -slideDistance
            },
        ) {
            content()
        }
    }
}

private data class SeedPreset(val name: String, val hex: String)

private val presetSeedColors = listOf(
    SeedPreset(AppStrings.ui_mu_zi, "#6750A4"),
    SeedPreset(AppStrings.ui_lake_blue, "#1E88E5"),
    SeedPreset(AppStrings.ui_fresh_orange, "#FF6F00"),
    SeedPreset(AppStrings.ui_verdant, "#2E7D32"),
    SeedPreset(AppStrings.ui_coral, "#E64A19"),
    SeedPreset(AppStrings.ui_qinghe, "#00897B"),
    SeedPreset(AppStrings.ui_white_moon, "#607D8B")
)

@Composable
private fun PresetSeedPalette(
    currentHex: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = AppStrings.ui_quick_selection,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            presetSeedColors.forEach { preset ->
                val color = parseHexColor(preset.hex) ?: MaterialTheme.colorScheme.primary
                val isSelected = currentHex.equals(preset.hex, ignoreCase = true)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelect(preset.hex) }
                ) {
                    Surface(
                        color = color,
                        shape = CircleShape,
                        border = if (isSelected) {
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        },
                        modifier = Modifier.size(44.dp)
                    ) {}
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun SeedColorRow(
    colorHex: String,
    onEdit: () -> Unit
) {
    val previewColor = parseHexColor(colorHex) ?: MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(AppStrings.ui_manual_color_correction, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = colorHex,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = previewColor,
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(36.dp)
            ) {}
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = onEdit) {
                Icon(Icons.Filled.ColorLens, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(AppStrings.ui_adjust)
            }
        }
    }

}

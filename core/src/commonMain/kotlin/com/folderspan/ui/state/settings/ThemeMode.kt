package com.folderspan.ui.state.settings

import strings.AppStrings

/**
 * 主题模式设置
 */
enum class ThemeMode(
    val title: String,
    val description: String
) {
    System(AppStrings.language_system_title, AppStrings.ui_automatically_switch_light_dark_themes_based_system_appearance),
    Light(AppStrings.ui_light_mode, AppStrings.ui_always_use_light_theme),
    Dark(AppStrings.ui_dark_mode, AppStrings.ui_always_use_dark_theme)
}

/**
 * 安全解析字符串到 [ThemeMode]
 */
fun String?.toThemeMode(default: ThemeMode = ThemeMode.System): ThemeMode {
    return runCatching { ThemeMode.valueOf(this ?: default.name) }.getOrElse { default }
}

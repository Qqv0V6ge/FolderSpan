package com.folderspan.localization

import strings.AppStrings

object AppText {
    fun languageTitle(mode: AppLanguageMode): String = when (mode) {
        AppLanguageMode.System -> AppStrings.language_system_title
        AppLanguageMode.English -> AppStrings.language_english_title
        AppLanguageMode.SimplifiedChinese -> AppStrings.language_simplified_chinese_title
    }

    fun languageDescription(mode: AppLanguageMode): String = when (mode) {
        AppLanguageMode.System -> AppStrings.language_system_description
        AppLanguageMode.English -> AppStrings.language_english_description
        AppLanguageMode.SimplifiedChinese -> AppStrings.language_simplified_chinese_description
    }
}

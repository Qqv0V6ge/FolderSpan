package com.folderspan.localization

import com.folderspan.utils.SettingsUtils.KEY_APPEARANCE_LANGUAGE
import com.russhwolf.settings.Settings
import io.github.skeptick.libres.LibresSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private val SIMPLIFIED_CHINESE_REGIONS = setOf("cn", "sg")
private val TRADITIONAL_CHINESE_REGIONS = setOf("tw", "hk", "mo")

enum class AppLanguageMode {
    System,
    English,
    SimplifiedChinese,
}

enum class ResolvedAppLanguage(
    val languageTag: String,
    val libresLanguageCode: String,
) {
    English(languageTag = "en", libresLanguageCode = "en"),
    SimplifiedChinese(languageTag = "zh-Hans", libresLanguageCode = "zhHans"),
}

interface AppLanguagePlatform {
    fun preferredLanguageTags(): List<String>

    fun currentApplicationMode(): AppLanguageMode? = null

    fun applyApplicationLanguage(mode: AppLanguageMode, resolvedLanguage: ResolvedAppLanguage) = Unit
}

expect object DefaultAppLanguagePlatform : AppLanguagePlatform {
    override fun preferredLanguageTags(): List<String>
}

fun String.toAppLanguageMode(): AppLanguageMode =
    AppLanguageMode.entries.firstOrNull { mode -> mode.name.equals(this, ignoreCase = true) }
        ?: AppLanguageMode.System

fun resolveAppLanguage(
    mode: AppLanguageMode,
    preferredLanguageTags: List<String>,
): ResolvedAppLanguage = when (mode) {
    AppLanguageMode.English -> ResolvedAppLanguage.English
    AppLanguageMode.SimplifiedChinese -> ResolvedAppLanguage.SimplifiedChinese
    AppLanguageMode.System -> resolveSystemLanguage(preferredLanguageTags.firstOrNull())
}

fun resolveSystemLanguage(languageTag: String?): ResolvedAppLanguage {
    val normalized = languageTag
        ?.trim()
        ?.replace('_', '-')
        ?.lowercase()
        .orEmpty()
    if (normalized.isEmpty()) return ResolvedAppLanguage.English

    val subtags = normalized.split('-').filter(String::isNotBlank)
    if (subtags.firstOrNull() != "zh") return ResolvedAppLanguage.English
    if ("hant" in subtags || subtags.any { item -> item in TRADITIONAL_CHINESE_REGIONS }) {
        return ResolvedAppLanguage.English
    }
    if (
        subtags.size == 1 ||
        "hans" in subtags ||
        subtags.any { item -> item in SIMPLIFIED_CHINESE_REGIONS }
    ) {
        return ResolvedAppLanguage.SimplifiedChinese
    }
    return ResolvedAppLanguage.English
}

class AppLanguageController(
    private val settings: Settings,
    private val platform: AppLanguagePlatform = DefaultAppLanguagePlatform,
) {
    private val storedMode =
        settings.getString(KEY_APPEARANCE_LANGUAGE, AppLanguageMode.System.name).toAppLanguageMode()
    private val initialMode = platform.currentApplicationMode() ?: storedMode

    private val _mode = MutableStateFlow(initialMode)
    val mode = _mode.asStateFlow()

    private val _resolvedLanguage = MutableStateFlow(
        resolveAppLanguage(initialMode, platform.preferredLanguageTags())
    )
    val resolvedLanguage = _resolvedLanguage.asStateFlow()

    init {
        if (initialMode != storedMode) {
            settings.putString(KEY_APPEARANCE_LANGUAGE, initialMode.name)
        }
        apply(initialMode)
    }

    fun setMode(mode: AppLanguageMode) {
        settings.putString(KEY_APPEARANCE_LANGUAGE, mode.name)
        apply(mode)
    }

    fun reloadFromSettings() {
        val mode = settings
            .getString(KEY_APPEARANCE_LANGUAGE, AppLanguageMode.System.name)
            .toAppLanguageMode()
        apply(mode)
    }

    fun refreshSystemLanguage() {
        apply(_mode.value)
    }

    fun synchronizePlatformMode(mode: AppLanguageMode) {
        if (mode == _mode.value) return
        settings.putString(KEY_APPEARANCE_LANGUAGE, mode.name)
        apply(mode, updatePlatform = false)
    }

    private fun apply(mode: AppLanguageMode, updatePlatform: Boolean = true) {
        val resolved = resolveAppLanguage(mode, platform.preferredLanguageTags())
        LibresSettings.languageCode = resolved.libresLanguageCode
        _mode.value = mode
        _resolvedLanguage.value = resolved
        if (updatePlatform) {
            platform.applyApplicationLanguage(mode, resolved)
        }
    }
}

package com.folderspan.localization

import com.folderspan.test.createInMemorySettings
import com.folderspan.utils.SettingsUtils.KEY_APPEARANCE_LANGUAGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppLanguageTest {
    @Test
    fun simplifiedChineseLocalesResolveToSimplifiedChinese() {
        listOf("zh", "zh-Hans", "zh-CN", "zh-SG", "ZH_hANS_cn").forEach { languageTag ->
            assertEquals(
                ResolvedAppLanguage.SimplifiedChinese,
                resolveSystemLanguage(languageTag),
                languageTag,
            )
        }
    }

    @Test
    fun traditionalAndUnmatchedLocalesResolveToEnglish() {
        listOf("zh-Hant", "zh-TW", "zh-HK", "zh-MO", "fr-FR", "", "zh-US").forEach { languageTag ->
            assertEquals(
                ResolvedAppLanguage.English,
                resolveSystemLanguage(languageTag),
                languageTag,
            )
        }
    }

    @Test
    fun explicitLanguageTakesPrecedence() {
        assertEquals(
            ResolvedAppLanguage.English,
            resolveAppLanguage(AppLanguageMode.English, listOf("zh-CN")),
        )
        assertEquals(
            ResolvedAppLanguage.SimplifiedChinese,
            resolveAppLanguage(AppLanguageMode.SimplifiedChinese, listOf("en-US")),
        )
    }

    @Test
    fun returningToSystemUsesCurrentSystemLocale() {
        val platform = FakeLanguagePlatform("en-US")
        val controller = AppLanguageController(createInMemorySettings(), platform)

        controller.setMode(AppLanguageMode.English)
        platform.setPreferredLanguageTag("zh-CN")
        controller.setMode(AppLanguageMode.System)

        assertEquals(AppLanguageMode.System, controller.mode.value)
        assertEquals(
            ResolvedAppLanguage.SimplifiedChinese,
            controller.resolvedLanguage.value,
        )
    }

    @Test
    fun browserLanguageResolutionUsesPreferenceOrderAndEnglishFallback() {
        assertEquals(
            ResolvedAppLanguage.SimplifiedChinese,
            resolveBrowserLanguage("zh-CN,zh;q=0.9,en;q=0.8"),
        )
        assertEquals(
            ResolvedAppLanguage.English,
            resolveBrowserLanguage("zh-TW,zh;q=0.9,en;q=0.8"),
        )
        assertEquals(
            ResolvedAppLanguage.English,
            resolveBrowserLanguage("fr-FR,zh-CN;q=0.5"),
        )
        assertEquals(
            listOf("en-US", "zh-CN"),
            parseAcceptLanguageHeader("zh-CN;q=0.5,en-US;q=0.9"),
        )
    }

    @Test
    fun controllerPersistsSelectionAndDefaultsInvalidValueToSystem() {
        val settings = createInMemorySettings(KEY_APPEARANCE_LANGUAGE to "invalid")
        val platform = FakeLanguagePlatform("en-US")
        val controller = AppLanguageController(settings, platform)

        assertEquals(AppLanguageMode.System, controller.mode.value)
        controller.setMode(AppLanguageMode.SimplifiedChinese)

        assertEquals(AppLanguageMode.SimplifiedChinese, controller.mode.value)
        assertEquals(
            AppLanguageMode.SimplifiedChinese.name,
            settings.getString(KEY_APPEARANCE_LANGUAGE, ""),
        )
        assertEquals(ResolvedAppLanguage.SimplifiedChinese, platform.lastResolvedLanguage)

        val restartedController = AppLanguageController(
            settings = settings,
            platform = FakeLanguagePlatform("en-US"),
        )
        assertEquals(AppLanguageMode.SimplifiedChinese, restartedController.mode.value)
        assertEquals(
            ResolvedAppLanguage.SimplifiedChinese,
            restartedController.resolvedLanguage.value,
        )
    }

    @Test
    fun languagePreferenceIsNotSyncable() {
        assertFalse(
            com.folderspan.utils.SettingsUtils.syncableSettings.any { setting ->
                setting.key == KEY_APPEARANCE_LANGUAGE
            }
        )
    }

    private class FakeLanguagePlatform(
        private var languageTag: String,
    ) : AppLanguagePlatform {
        var lastResolvedLanguage: ResolvedAppLanguage? = null

        override fun preferredLanguageTags(): List<String> = listOf(languageTag)

        fun setPreferredLanguageTag(languageTag: String) {
            this.languageTag = languageTag
        }

        override fun applyApplicationLanguage(
            mode: AppLanguageMode,
            resolvedLanguage: ResolvedAppLanguage,
        ) {
            lastResolvedLanguage = resolvedLanguage
        }
    }
}

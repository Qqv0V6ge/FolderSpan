package com.folderspan.localization

import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

private const val APP_GROUP_IDENTIFIER = "group.com.folderspan.FolderSpan"
private const val APP_GROUP_LANGUAGE_KEY = "settings.appearance.language"

actual object DefaultAppLanguagePlatform : AppLanguagePlatform {
    actual override fun preferredLanguageTags(): List<String> =
        listOf(NSLocale.currentLocale.localeIdentifier)

    override fun currentApplicationMode(): AppLanguageMode? {
        val stored = NSUserDefaults(suiteName = APP_GROUP_IDENTIFIER)
            .stringForKey(APP_GROUP_LANGUAGE_KEY)
            ?: return null
        return stored.toAppLanguageMode()
    }

    override fun applyApplicationLanguage(
        mode: AppLanguageMode,
        resolvedLanguage: ResolvedAppLanguage,
    ) {
        NSUserDefaults(suiteName = APP_GROUP_IDENTIFIER).apply {
            setObject(mode.name, forKey = APP_GROUP_LANGUAGE_KEY)
            synchronize()
        }
    }
}

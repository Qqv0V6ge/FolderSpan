package com.folderspan.localization

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.core.app.LocaleManagerCompat
import com.folderspan.androidContext

actual object DefaultAppLanguagePlatform : AppLanguagePlatform {
    actual override fun preferredLanguageTags(): List<String> =
        LocaleManagerCompat.getSystemLocales(androidContext())
            .toLanguageTags()
            .split(',')
            .filter(String::isNotBlank)

    override fun currentApplicationMode(): AppLanguageMode? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val applicationLocales = LocaleManagerCompat.getApplicationLocales(androidContext())
        if (applicationLocales.isEmpty) return AppLanguageMode.System
        return when (
            resolveSystemLanguage(applicationLocales[0]?.toLanguageTag())
        ) {
            ResolvedAppLanguage.English -> AppLanguageMode.English
            ResolvedAppLanguage.SimplifiedChinese -> AppLanguageMode.SimplifiedChinese
        }
    }

    override fun applyApplicationLanguage(
        mode: AppLanguageMode,
        resolvedLanguage: ResolvedAppLanguage,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val localeManager = checkNotNull(
            androidContext().getSystemService(LocaleManager::class.java)
        )
        val requestedLocales = when (mode) {
            AppLanguageMode.System -> LocaleList.getEmptyLocaleList()
            else -> LocaleList.forLanguageTags(resolvedLanguage.languageTag)
        }
        if (localeManager.applicationLocales.toLanguageTags() != requestedLocales.toLanguageTags()) {
            localeManager.applicationLocales = requestedLocales
        }
    }
}

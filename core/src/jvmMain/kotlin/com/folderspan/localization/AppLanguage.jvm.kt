package com.folderspan.localization

import java.util.Locale

actual object DefaultAppLanguagePlatform : AppLanguagePlatform {
    actual override fun preferredLanguageTags(): List<String> =
        listOf(Locale.getDefault().toLanguageTag())
}

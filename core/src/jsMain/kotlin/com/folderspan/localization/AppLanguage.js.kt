package com.folderspan.localization

import kotlinx.browser.window

actual object DefaultAppLanguagePlatform : AppLanguagePlatform {
    actual override fun preferredLanguageTags(): List<String> =
        listOf(window.navigator.language)
}

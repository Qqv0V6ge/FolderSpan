package com.folderspan.test

import io.github.skeptick.libres.LibresSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

abstract class ChineseLocalizationTest {
    @BeforeTest
    fun useSimplifiedChineseResources() {
        LibresSettings.languageCode = "zhHans"
    }

    @AfterTest
    fun resetEnglishResources() {
        LibresSettings.languageCode = "en"
    }
}

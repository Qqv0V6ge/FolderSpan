package com.folderspan.ui.screen.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.localization.AppLanguageMode
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LanguageSettingsScreenTest {
    @Test
    fun languageOptionsReflectSelectionAndInvokePersistenceCallback() = runComposeUiTest {
        var selectedMode by mutableStateOf(AppLanguageMode.System)
        val callbacks = mutableListOf<AppLanguageMode>()

        setContent {
            MaterialTheme {
                LanguageOptions(
                    selectedMode = selectedMode,
                    onModeSelected = { mode ->
                        callbacks += mode
                        selectedMode = mode
                    },
                )
            }
        }

        onNodeWithTag("language-option-System").assertIsSelected()
        onNodeWithTag("language-option-English")
            .assertIsNotSelected()
            .performClick()

        onNodeWithTag("language-option-English").assertIsSelected()
        assertEquals(listOf(AppLanguageMode.English), callbacks)

        onNodeWithTag("language-option-SimplifiedChinese").performClick()
        onNodeWithTag("language-option-SimplifiedChinese").assertIsSelected()
        onNodeWithTag("language-option-System").performClick()
        onNodeWithTag("language-option-System").assertIsSelected()
        assertEquals(
            listOf(
                AppLanguageMode.English,
                AppLanguageMode.SimplifiedChinese,
                AppLanguageMode.System,
            ),
            callbacks,
        )
    }
}

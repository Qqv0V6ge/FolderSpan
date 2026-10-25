package com.folderspan

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.test.createInMemorySettings
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.state.settings.SettingsState
import com.folderspan.ui.state.settings.ThemeMode
import com.folderspan.ui.theme.getDefaultColorScheme
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FolderSpanAppThemeTest {
    @Test
    fun switchingAppearanceDiscardsOldRemoteColorsUntilMatchingColorsArrive() {
        val mainState = MainState()
        val settingsState = SettingsState(createInMemorySettings()).apply {
            setThemeMode(ThemeMode.Light)
        }
        val application = koinApplication {
            modules(module {
                single { mainState }
                single { settingsState }
            })
        }
        var background = Color.Unspecified
        try {
            runComposeUiTest {
                setContent {
                    KoinIsolatedContext(application) {
                        FolderSpanAppTheme {
                            val colors = MaterialTheme.colorScheme
                            SideEffect { background = colors.background }
                        }
                    }
                }
                runOnIdle {
                    mainState.setRemoteColorScheme(lightColorScheme(background = Color.Red), isDark = false)
                }
                runOnIdle {
                    assertEquals(Color.Red, background)
                    settingsState.setThemeMode(ThemeMode.Dark)
                }
                runOnIdle {
                    assertEquals(getDefaultColorScheme(true).background, background)
                    mainState.setRemoteColorScheme(darkColorScheme(background = Color.Blue), isDark = true)
                }
                runOnIdle {
                    assertEquals(Color.Blue, background)
                    settingsState.setThemeMode(ThemeMode.Light)
                }
                runOnIdle {
                    assertEquals(getDefaultColorScheme(false).background, background)
                }
            }
        } finally {
            application.close()
        }
    }
}

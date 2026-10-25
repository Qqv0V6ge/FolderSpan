package com.folderspan.ui.state.main

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class MainStateThemeTest {
    @Test
    fun remoteColorsAreOnlyVisibleForTheirAppearance() {
        val state = MainState()
        val colors = lightColorScheme()
        state.setRemoteColorScheme(colors, isDark = false)

        assertSame(colors, state.remoteColorScheme.value)
        assertSame(colors, state.remoteColorSchemeFor(false))
        assertNull(state.remoteColorSchemeFor(true))

        state.setCurrentDarkTheme(true)
        assertNull(state.remoteColorScheme.value)
        state.setCurrentDarkTheme(false)
        assertSame(colors, state.remoteColorScheme.value)
    }

    @Test
    fun clearingRemoteColorsAlsoDiscardsTheirCachedAppearance() {
        val state = MainState()
        state.setCurrentDarkTheme(true)
        state.setRemoteColorScheme(darkColorScheme(), isDark = true)
        state.setRemoteColorScheme(null)

        state.setCurrentDarkTheme(false)
        state.setCurrentDarkTheme(true)
        assertNull(state.remoteColorScheme.value)
        assertNull(state.remoteColorSchemeFor(true))
    }
}

package com.folderspan.ui.screen.main

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainScreenTest {

    @Test
    fun mainScreenUsesModalDrawerWhenScreenWidthIsLessThanDoubleDrawerWidth() {
        assertTrue(shouldUseModalNavigationDrawer(screenWidth = 719.dp, drawerWidth = 360.dp))
    }

    @Test
    fun mainScreenUsesInlineDrawerWhenScreenWidthIsAtLeastDoubleDrawerWidth() {
        assertFalse(shouldUseModalNavigationDrawer(screenWidth = 720.dp, drawerWidth = 360.dp))
        assertFalse(shouldUseModalNavigationDrawer(screenWidth = 721.dp, drawerWidth = 360.dp))
    }
}

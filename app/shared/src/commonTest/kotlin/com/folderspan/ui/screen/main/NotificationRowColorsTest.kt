package com.folderspan.ui.screen.main

import androidx.compose.material3.lightColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationRowColorsTest {
    private val colorScheme = lightColorScheme()

    @Test
    fun selectedRowUsesPrimaryContainerColorPair() {
        val colors = notificationRowColors(
            colorScheme = colorScheme,
            selected = true,
            detailSelected = false,
            isRead = false,
        )

        assertEquals(colorScheme.primaryContainer, colors.container)
        assertEquals(colorScheme.onPrimaryContainer, colors.content)
        assertEquals(colorScheme.onPrimaryContainer, colors.supportingContent)
        assertEquals(colorScheme.onPrimaryContainer, colors.accent)
    }

    @Test
    fun detailSelectedRowUsesSecondaryContainerColorPair() {
        val colors = notificationRowColors(
            colorScheme = colorScheme,
            selected = false,
            detailSelected = true,
            isRead = true,
        )

        assertEquals(colorScheme.secondaryContainer, colors.container)
        assertEquals(colorScheme.onSecondaryContainer, colors.content)
        assertEquals(colorScheme.onSecondaryContainer, colors.supportingContent)
        assertEquals(colorScheme.onSecondaryContainer, colors.accent)
    }
}

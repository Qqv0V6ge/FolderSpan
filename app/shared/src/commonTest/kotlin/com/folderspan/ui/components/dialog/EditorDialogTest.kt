package com.folderspan.ui.components.dialog

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorDialogTest {
    @Test
    fun compactWindowUsesFullScreenDialogOnly() {
        assertTrue(editorDialogUsesFullScreen(0.dp))
        assertTrue(editorDialogUsesFullScreen(599.dp))
        assertFalse(editorDialogUsesFullScreen(600.dp))
        assertFalse(editorDialogUsesFullScreen(840.dp))
    }

    @Test
    fun fullScreenDialogRemovesPlatformWidthAndOutsideDismiss() {
        val properties = editorDialogProperties(
            fillScreenWidth = true,
            dismissOnClickOutside = false,
        )

        assertFalse(properties.usePlatformDefaultWidth)
        assertFalse(properties.dismissOnClickOutside)
    }

    @Test
    fun fullWidthDialogRemovesPlatformWidthLimit() {
        val defaultProperties = editorDialogProperties(fillScreenWidth = false)
        val fullWidthProperties = editorDialogProperties(fillScreenWidth = true)

        assertTrue(defaultProperties.usePlatformDefaultWidth)
        assertFalse(fullWidthProperties.usePlatformDefaultWidth)
        assertTrue(fullWidthProperties.dismissOnClickOutside)
    }
}

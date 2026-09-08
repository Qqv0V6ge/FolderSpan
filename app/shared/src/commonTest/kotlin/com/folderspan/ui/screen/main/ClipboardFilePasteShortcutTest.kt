package com.folderspan.ui.screen.main

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardFilePasteShortcutTest {
    @Test
    fun shortcutAcceptsCtrlOrCommandVOnlyInNonEditableFileBrowserContext() {
        assertTrue(shortcut(ctrl = true))
        assertTrue(shortcut(meta = true))
        assertFalse(shortcut(ctrl = true, editable = true))
        assertFalse(shortcut(ctrl = true, browserActive = false))
        assertFalse(shortcut(ctrl = true, vKey = false))
        assertFalse(shortcut(ctrl = true, alt = true))
        assertFalse(shortcut(ctrl = true, shift = true))
        assertFalse(shortcut(ctrl = true, keyDown = false))
        assertFalse(shortcut())
    }

    private fun shortcut(
        ctrl: Boolean = false,
        meta: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        editable: Boolean = false,
        browserActive: Boolean = true,
        vKey: Boolean = true,
        keyDown: Boolean = true,
    ): Boolean = shouldHandleClipboardFilePasteShortcut(
        isFileBrowserActive = browserActive,
        isEditableContext = editable,
        isVKey = vKey,
        isCtrlPressed = ctrl,
        isMetaPressed = meta,
        isAltPressed = alt,
        isShiftPressed = shift,
        isKeyDown = keyDown,
    )
}

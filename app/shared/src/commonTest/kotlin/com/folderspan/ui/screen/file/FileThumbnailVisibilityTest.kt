package com.folderspan.ui.screen.file

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileThumbnailVisibilityTest {
    @Test
    fun loadsVisibleItemsAndOneListItemAhead() {
        val visible = listOf(10, 11, 12)
        assertTrue(shouldLoadFileThumbnail(10, visible, isGrid = false))
        assertTrue(shouldLoadFileThumbnail(13, visible, isGrid = false))
        assertFalse(shouldLoadFileThumbnail(14, visible, isGrid = false))
        assertFalse(shouldLoadFileThumbnail(9, visible, isGrid = false))
    }

    @Test
    fun gridPrefetchScalesWithVisibleItemCount() {
        val visible = (20..31).toList()
        assertTrue(shouldLoadFileThumbnail(34, visible, isGrid = true))
        assertFalse(shouldLoadFileThumbnail(35, visible, isGrid = true))
    }

    @Test
    fun doesNotLoadBeforeLayoutHasVisibleItems() {
        assertFalse(shouldLoadFileThumbnail(0, emptyList(), isGrid = false))
    }
}

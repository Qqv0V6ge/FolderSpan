package com.folderspan.ui.screen.file

import com.folderspan.data.file.FileFilterSort
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileFilterButtonsTest {
    @Test
    fun sortButtonReturnsToInactiveWhenSortReturnsToDefault() {
        assertTrue(
            isFileSortButtonActive(
                sortType = FileFilterSort.SizeAsc,
                isHideFile = false,
                hasActiveIgnoreFile = false,
            )
        )

        assertFalse(
            isFileSortButtonActive(
                sortType = FileFilterSort.NameAsc,
                isHideFile = false,
                hasActiveIgnoreFile = false,
            )
        )
    }

    @Test
    fun sortButtonStaysActiveForHiddenOrIgnoreFilters() {
        assertTrue(
            isFileSortButtonActive(
                sortType = FileFilterSort.NameAsc,
                isHideFile = true,
                hasActiveIgnoreFile = false,
            )
        )
        assertTrue(
            isFileSortButtonActive(
                sortType = FileFilterSort.NameAsc,
                isHideFile = false,
                hasActiveIgnoreFile = true,
            )
        )
    }
}

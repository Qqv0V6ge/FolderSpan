package com.folderspan.data.file

import strings.AppStrings

import com.folderspan.db.FileFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileFilterDisplayNameTest {
    @Test
    fun builtInFilterUsesLocalizedTypeNameInsteadOfStoredName() {
        val filter = FileFilter(
            id = 1L,
            name = AppStrings.ui_pictures,
            type = FileFilterType.Image,
            extensions = listOf(".png"),
            icon = null,
            sort = 1L,
        )

        assertEquals(FileFilterType.Image.displayName(), filter.displayName())
        assertTrue(filter.displayName().isNotBlank())
    }

    @Test
    fun customFilterKeepsUserProvidedName() {
        val filter = FileFilter(
            id = 99L,
            name = "My types",
            type = FileFilterType.Custom,
            extensions = emptyList(),
            icon = null,
            sort = 0L,
        )

        assertEquals("My types", filter.displayName())
    }
}

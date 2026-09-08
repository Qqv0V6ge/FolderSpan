package com.folderspan.ui.screen.task

import strings.AppStrings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TaskResultScreenTest {
    @Test
    fun buildTaskResultListItems_assignsDistinctKeysToDuplicatePaths() {
        val items = buildTaskResultListItems(
            listOf(
                "/duplicate" to AppStrings.ui_test_task_result_screen_first,
                "/duplicate" to AppStrings.ui_test_task_result_screen_second_line,
            )
        )

        assertNotEquals(items[0].stableKey, items[1].stableKey)
    }

    @Test
    fun filterTaskResultListItems_preservesStableKeyAcrossFilters() {
        val items = buildTaskResultListItems(
            listOf(
                "/duplicate" to AppStrings.ui_success,
                "/duplicate" to AppStrings.ui_failed,
            )
        )

        val filtered = filterTaskResultListItems(
            items = items,
            searchText = "duplicate",
            useRegex = false,
            searchRegex = null,
            selectedMessageTypes = setOf(AppStrings.ui_failed),
        )

        assertEquals(listOf(items[1].stableKey), filtered.map { item -> item.stableKey })
    }

    @Test
    fun filterTaskResultListItems_appliesRegexAndMessageTypeTogether() {
        val items = buildTaskResultListItems(
            listOf(
                "/photos/2026.jpg" to AppStrings.ui_failed,
                "/photos/readme.txt" to AppStrings.ui_failed,
                "/photos/2025.jpg" to AppStrings.ui_success,
            )
        )

        val filtered = filterTaskResultListItems(
            items = items,
            searchText = "2026\\.jpg$",
            useRegex = true,
            searchRegex = Regex("2026\\.jpg$", RegexOption.IGNORE_CASE),
            selectedMessageTypes = setOf(AppStrings.ui_failed),
        )

        assertEquals(listOf("/photos/2026.jpg"), filtered.map { item -> item.path })
    }
}

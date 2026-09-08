package com.folderspan.ui.screen.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.cleanup.ApplicationDataCleanupCategory
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ApplicationDataCleanupScreenTest {
    @Test
    fun cleanupOptionsSupportIndividualAndSelectAllChanges() = runComposeUiTest {
        var selectedCategories by mutableStateOf(emptySet<ApplicationDataCleanupCategory>())

        setContent {
            MaterialTheme {
                ApplicationDataCleanupOptions(
                    availableCategories = ApplicationDataCleanupCategory.all,
                    selectedCategories = selectedCategories,
                    onCategorySelected = { category, selected ->
                        selectedCategories = if (selected) {
                            selectedCategories + category
                        } else {
                            selectedCategories - category
                        }
                    },
                    onSelectAll = { selected ->
                        selectedCategories = if (selected) {
                            ApplicationDataCleanupCategory.all
                        } else {
                            emptySet()
                        }
                    },
                )
            }
        }

        onNodeWithTag("cleanup-select-all").assertIsOff()
        onNodeWithTag("cleanup-category-ApplicationData")
            .assertIsOff()
            .performClick()
            .assertIsOn()
        assertEquals(
            setOf(ApplicationDataCleanupCategory.ApplicationData),
            selectedCategories,
        )

        onNodeWithTag("cleanup-select-all").performClick().assertIsOn()
        assertEquals(ApplicationDataCleanupCategory.all, selectedCategories)

        onNodeWithTag("cleanup-select-all").performClick().assertIsOff()
        assertEquals(emptySet(), selectedCategories)
    }

    @Test
    fun cleanupOptionsOnlyShowCategoriesAvailableOnCurrentPlatform() = runComposeUiTest {
        val availableCategories = setOf(
            ApplicationDataCleanupCategory.ApplicationData,
            ApplicationDataCleanupCategory.PreferencesAndAccount,
        )
        var selectedCategories by mutableStateOf(emptySet<ApplicationDataCleanupCategory>())

        setContent {
            MaterialTheme {
                ApplicationDataCleanupOptions(
                    availableCategories = availableCategories,
                    selectedCategories = selectedCategories,
                    onCategorySelected = { category, selected ->
                        selectedCategories = if (selected) {
                            selectedCategories + category
                        } else {
                            selectedCategories - category
                        }
                    },
                    onSelectAll = { selected ->
                        selectedCategories = if (selected) availableCategories else emptySet()
                    },
                )
            }
        }

        onNodeWithTag("cleanup-category-ApplicationData").assertIsOff()
        onAllNodesWithTag("cleanup-category-LoginStartup").assertCountEquals(0)
        onNodeWithTag("cleanup-select-all").performClick().assertIsOn()
        assertEquals(availableCategories, selectedCategories)
    }
}

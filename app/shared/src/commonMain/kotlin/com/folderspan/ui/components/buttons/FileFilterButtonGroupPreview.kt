package com.folderspan.ui.components.buttons

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.ui.screen.file.FileFilterButtons
import com.folderspan.ui.screen.file.FileFilterButtonsUiState
import strings.AppStrings

@Preview(name = "File actions - Compact", widthDp = 360, heightDp = 100, showBackground = true)
@Preview(name = "File actions - Medium", widthDp = 720, heightDp = 100, showBackground = true)
@Preview(name = "File actions - Expanded", widthDp = 1024, heightDp = 100, showBackground = true)
@Preview(name = "File actions - Large", widthDp = 1366, heightDp = 100, showBackground = true)
@Preview(name = "File actions - Extra-large", widthDp = 1600, heightDp = 100, showBackground = true)
@Preview(name = "File actions - Short landscape", widthDp = 640, heightDp = 100, showBackground = true)
@Composable
private fun FileFilterButtonGroupPreview() {
    val uiState = FileFilterButtonsUiState(
        filterGroup = FileFilterButtonGroupUiState(
            folderCount = 12,
            folderSelected = true,
            fileCount = 8,
            fileSelected = false,
            hiddenCount = 2,
            hiddenSelected = true,
            hiddenFilterAvailable = true,
            chips = listOf(
                FileFilterChipUiState(
                    type = FileFilterType.Image,
                    name = AppStrings.ui_pictures,
                    count = 5,
                    selected = false,
                ),
            ),
        ),
        sortType = FileFilterSort.SizeDesc,
        isHideFile = true,
        searchText = "photo",
        isSearchActive = true,
        ignoreFileMenuItems = emptyList(),
    )
    MaterialExpressiveTheme {
        Surface {
            FileFilterButtons(
                uiState = uiState,
                onFilterTypeChange = { _, _ -> },
                onSortChange = {},
                onHideFileChange = {},
                onIgnoreFileToggle = {},
                onSearch = {},
            )
        }
    }
}

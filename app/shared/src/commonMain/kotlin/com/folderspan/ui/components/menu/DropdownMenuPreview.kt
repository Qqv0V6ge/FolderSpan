package com.folderspan.ui.components.menu

import strings.AppStrings

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import com.folderspan.ui.components.model.StringListUiState

@Preview
@Composable
private fun EditableExposedDropdownMenuPreview() {
    var value by remember { mutableStateOf(AppStrings.ui_option_1) }
    EditableExposedDropdownMenu(
        optionsUiState = StringListUiState(listOf(AppStrings.ui_option_1, AppStrings.ui_option_2, AppStrings.ui_option_3)),
        value = value,
        onValueChange = { item -> value = item },
        label = { Text(AppStrings.ui_choose) }
    )
}

@Preview
@Composable
private fun EditableExposedOutlinedDropdownMenuPreview() {
    var value by remember { mutableStateOf(AppStrings.ui_option_1) }
    EditableExposedOutlinedDropdownMenu(
        optionsUiState = StringListUiState(listOf(AppStrings.ui_option_1, AppStrings.ui_option_2, AppStrings.ui_option_3)),
        value = value,
        onValueChange = { item -> value = item },
        label = { Text(AppStrings.ui_choose) }
    )
}

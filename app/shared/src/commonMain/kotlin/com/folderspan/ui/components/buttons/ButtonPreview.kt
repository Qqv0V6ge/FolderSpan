package com.folderspan.ui.components.buttons

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.folderspan.data.file.FileFilterSort

@Preview
@Composable
private fun SortButtonPreview() {
    SortButton(sortType = FileFilterSort.NameAsc, onUpdateSort = { _ -> })
}

package com.folderspan.ui.components.bookmark

import strings.AppStrings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.ui.state.file.DrawerBookmarkType

@Preview
@Composable
private fun BookmarkListItemPreview() {
    BookmarkListItem(
        bookmark = DrawerBookmark(
            id = 1L,
            name = AppStrings.ui_download,
            type = DrawerBookmarkType.Download,
            path = "/Downloads"
        ),
        onToggleSelect = {},
        onClick = {}
    )
}

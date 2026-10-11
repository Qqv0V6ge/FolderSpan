package com.folderspan.ui.components.drawer

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.ui.navigation.matchesScreen
import com.folderspan.ui.navigation.popUntilRoot
import com.folderspan.ui.screen.bookmark.BookmarkManageScreen
import com.folderspan.ui.screen.file.FavoriteScreen
import com.folderspan.ui.screen.file.RecentScreen
import com.folderspan.ui.state.file.FileBookmarkState
import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.DrawerState
import com.folderspan.ui.state.main.MainState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal data class AppDrawerBookmarkUiState(
    val path: String,
    val deskType: DiskBase,
    val isExpanded: Boolean,
    val isFavorite: Boolean,
    val isRecent: Boolean,
    val bookmarks: List<DrawerBookmark>,
    val onBookmarkClick: (DrawerBookmark) -> Unit,
    val onOpenRecent: () -> Unit,
    val onOpenFavorites: () -> Unit,
    val onManageBookmarks: () -> Unit,
    val onToggleExpanded: () -> Unit,
)

@Composable
internal fun rememberAppDrawerBookmarkUiState(): AppDrawerBookmarkUiState {
    val scope = rememberCoroutineScope()
    val mainState = koinInject<MainState>()
    val currentRoute = mainState.currentRoute
    val isFavorite = currentRoute.matchesScreen(FavoriteScreen())
    val isRecent = currentRoute.matchesScreen(RecentScreen())

    val fileState = koinInject<FileState>()
    val path by fileState.path.collectAsState()
    val deskType by fileState.deskType.collectAsState()

    val drawerState = koinInject<DrawerState>()
    val isExpandBookmark by drawerState.isExpandBookmark.collectAsState()

    val fileBookmarkState = koinInject<FileBookmarkState>()

    LaunchedEffect(fileBookmarkState) {
        fileBookmarkState.load()
    }
    val bookmarkSnapshot = fileBookmarkState.bookmarks.toList()
    val bookmarks = remember(bookmarkSnapshot) { bookmarkSnapshot }

    return AppDrawerBookmarkUiState(
        path = path,
        deskType = deskType,
        isExpanded = isExpandBookmark,
        isFavorite = isFavorite,
        isRecent = isRecent,
        bookmarks = bookmarks,
        onBookmarkClick = { bookmark ->
            scope.launch(Dispatchers.Default) {
                fileState.updatePath(bookmark.path)
            }
            if (mainState.navigator?.canPop == true) {
                mainState.navigator?.popUntilRoot()
            }
            mainState.collapseDrawerAfterNavigation()
        },
        onOpenRecent = { mainState.pushScreen(RecentScreen()) },
        onOpenFavorites = { mainState.pushScreen(FavoriteScreen()) },
        onManageBookmarks = { mainState.pushScreen(BookmarkManageScreen()) },
        onToggleExpanded = { drawerState.updateExpandBookmark(!isExpandBookmark) },
    )
}

internal fun LazyListScope.appDrawerBookmark(uiState: AppDrawerBookmarkUiState) {
    item(
        key = "drawer_bookmark_header",
        contentType = "drawer_group_header",
    ) {
        AppDrawerHeader(
            title = if (uiState.deskType is Device) {
                AppStrings.drawer_device_bookmarks.format(
                    deviceName = uiState.deskType.name.split("-").firstOrNull().orEmpty(),
                )
            } else {
                AppStrings.ui_bookmark_label
            },
            actions = { AppDrawerBookmarkActions(uiState) },
        )
    }

    if (uiState.isExpanded) {
        items(
            items = uiState.bookmarks,
            key = { bookmark -> "drawer_bookmark_${bookmark.id}" },
            contentType = { "drawer_bookmark_row" },
        ) { bookmark ->
            NavigationDrawerItem(
                icon = { Icon(bookmark.icon(), null) },
                label = { Text(bookmark.name) },
                selected = !uiState.isFavorite && !uiState.isRecent && uiState.path == bookmark.path,
                onClick = { uiState.onBookmarkClick(bookmark) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
        }
    }

    item(
        key = "drawer_bookmark_footer",
        contentType = "drawer_group_spacing",
    ) {
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AppDrawerBookmarkActions(uiState: AppDrawerBookmarkUiState) {
    Row {
        Icon(
            Icons.Default.History,
            null,
            Modifier.clip(RoundedCornerShape(25.dp))
                .clickable(onClick = uiState.onOpenRecent),
            tint = if (uiState.isRecent) MaterialTheme.colorScheme.primary else LocalContentColor.current
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.Favorite,
            null,
            Modifier.clip(RoundedCornerShape(25.dp))
                .clickable(onClick = uiState.onOpenFavorites),
            tint = if (uiState.isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.BookmarkAdd,
            null,
            Modifier.clip(RoundedCornerShape(25.dp))
                .clickable(onClick = uiState.onManageBookmarks),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            if (uiState.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            null,
            Modifier.clip(RoundedCornerShape(25.dp))
                .clickable(onClick = uiState.onToggleExpanded)
        )
    }
}

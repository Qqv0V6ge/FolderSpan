package com.folderspan.ui.components.drawer

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareProtocol
import com.folderspan.extensions.DeviceIcon
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.DrawerState
import org.koin.compose.koinInject

internal data class AppDrawerShareUiState(
    val currentDesk: DiskBase,
    val isExpanded: Boolean,
    val rows: List<AppDrawerShareRow>,
    val onToggleExpanded: () -> Unit,
    val onShareClick: (Share) -> Unit,
    val onDisconnectShare: (Share) -> Unit,
)

internal data class AppDrawerShareRow(
    val lazyKey: String,
    val share: Share,
)

private class AppDrawerShareKeyRegistry {
    private data class Entry(
        val lazyKey: String,
        val share: Share,
    )

    private var nextSessionId = 0L
    private var previousEntries = emptyList<Entry>()

    fun rows(shares: List<Share>): List<AppDrawerShareRow> {
        val unusedEntries = previousEntries.toMutableList()
        val rows = shares.map { share ->
            val previousIndex = unusedEntries.indexOfFirst { entry -> entry.share === share }
            val lazyKey = if (previousIndex >= 0) {
                unusedEntries.removeAt(previousIndex).lazyKey
            } else {
                "drawer_share_session_${nextSessionId++}"
            }
            AppDrawerShareRow(lazyKey = lazyKey, share = share)
        }
        previousEntries = rows.map { row -> Entry(lazyKey = row.lazyKey, share = row.share) }
        return rows
    }
}

@Composable
internal fun rememberAppDrawerShareUiState(): AppDrawerShareUiState {
    val deviceState = koinInject<DeviceState>()
    val fileState = koinInject<FileState>()
    val currentDesk by fileState.deskType.collectAsState()
    val drawerState = koinInject<DrawerState>()
    val isExpandShare by drawerState.isExpandShare.collectAsState()
    val shareSnapshot = deviceState.shares.toList()
    val keyRegistry = remember { AppDrawerShareKeyRegistry() }
    val rows = remember(shareSnapshot) { keyRegistry.rows(shareSnapshot) }

    return AppDrawerShareUiState(
        currentDesk = currentDesk,
        isExpanded = isExpandShare,
        rows = rows,
        onToggleExpanded = { drawerState.updateExpandShare(!isExpandShare) },
        onShareClick = { share ->
            deviceState.shares.firstOrNull { item -> item === share }?.let { activeShare ->
                fileState.updateDesk(FileProtocol.Share, activeShare)
            }
        },
        onDisconnectShare = { share ->
            if (share.disconnect()) {
                deviceState.shares.remove(share)
                fileState.updateDesk(FileProtocol.Local, Local())
            }
        },
    )
}

internal fun LazyListScope.appDrawerShare(uiState: AppDrawerShareUiState) {
    if (uiState.rows.isEmpty()) return

    item(
        key = "drawer_share_header",
        contentType = "drawer_group_header",
    ) {
        AppDrawerHeader(
            title = AppStrings.ui_share,
            actions = {
                Icon(
                    if (uiState.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    Modifier.clip(RoundedCornerShape(25.dp))
                        .clickable(onClick = uiState.onToggleExpanded)
                )
            },
        )
    }

    if (uiState.isExpanded) {
        items(
            items = uiState.rows,
            key = { row -> row.lazyKey },
            contentType = { "drawer_share_row" },
        ) { row ->
            AppDrawerShareItem(
                share = row.share,
                uiState = uiState,
            )
        }
    }

    item(
        key = "drawer_share_footer",
        contentType = "drawer_group_spacing",
    ) {
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AppDrawerShareItem(
    share: Share,
    uiState: AppDrawerShareUiState,
) {
    NavigationDrawerItem(
        icon = {
            if (share.protocol == ShareProtocol.System) {
                Icon(Icons.Default.Share, contentDescription = AppStrings.ui_other_apps)
            } else {
                share.type.DeviceIcon()
            }
        },
        label = { Text(share.name) },
        selected = isSelectedShare(uiState.currentDesk, share),
        badge = {
            Icon(
                Icons.Default.Close,
                AppStrings.ui_cancel_connection,
                Modifier
                    .clip(RoundedCornerShape(25.dp))
                    .clickable { uiState.onDisconnectShare(share) }
            )
        },
        onClick = { uiState.onShareClick(share) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}

internal fun isSelectedShare(
    currentDesk: DiskBase,
    share: Share,
): Boolean = currentDesk === share

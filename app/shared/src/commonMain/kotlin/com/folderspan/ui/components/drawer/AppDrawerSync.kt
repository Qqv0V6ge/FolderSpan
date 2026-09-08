package com.folderspan.ui.components.drawer

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.ui.screen.sync.SyncEditScreen
import com.folderspan.ui.screen.sync.SyncManageScreen
import com.folderspan.ui.screen.sync.SyncRunDetailScreen
import com.folderspan.ui.state.main.DrawerState
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.state.main.SyncRunStatus
import com.folderspan.ui.state.main.SyncState
import com.folderspan.ui.state.main.SyncTask
import org.koin.compose.koinInject

internal data class AppDrawerSyncUiState(
    val isExpanded: Boolean,
    val tasks: List<SyncTask>,
    val onAddSync: () -> Unit,
    val onManageSync: () -> Unit,
    val onToggleExpanded: () -> Unit,
    val onTaskClick: (SyncTask) -> Unit,
    val onTaskAction: (SyncTask) -> Unit,
)

@Composable
internal fun rememberAppDrawerSyncUiState(): AppDrawerSyncUiState {
    val mainState = koinInject<MainState>()
    val syncState = koinInject<SyncState>()
    val drawerState = koinInject<DrawerState>()
    val isExpandSync by drawerState.isExpandSync.collectAsState()
    val taskSnapshot = syncState.tasks.toList()
    val tasks = remember(taskSnapshot) {
        taskSnapshot.sortedByDescending { item -> item.updatedAt }
    }

    return AppDrawerSyncUiState(
        isExpanded = isExpandSync,
        tasks = tasks,
        onAddSync = { mainState.pushScreen(SyncEditScreen()) },
        onManageSync = { mainState.pushScreen(SyncManageScreen) },
        onToggleExpanded = { drawerState.updateExpandSync(!isExpandSync) },
        onTaskClick = { task -> mainState.pushScreen(SyncRunDetailScreen(task.id)) },
        onTaskAction = { task ->
            if (task.lastStatus == SyncRunStatus.Running) {
                syncState.cancelActiveRun(task.id)
            } else {
                syncState.runNow(task.id)
            }
        },
    )
}

internal fun LazyListScope.appDrawerSync(uiState: AppDrawerSyncUiState) {
    item(
        key = "drawer_sync_header",
        contentType = "drawer_group_header",
    ) {
        AppDrawerHeader(
            title = AppStrings.ui_sync,
            actions = { AppDrawerSyncActions(uiState) },
        )
    }

    if (uiState.isExpanded || uiState.tasks.isNotEmpty()) {
        items(
            items = uiState.tasks,
            key = { task -> "drawer_sync_${task.id}" },
            contentType = { "drawer_sync_row" },
        ) { task ->
            AppDrawerSyncTaskItem(
                task = task,
                uiState = uiState,
            )
        }
    }

    item(
        key = "drawer_sync_footer",
        contentType = "drawer_group_spacing",
    ) {
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AppDrawerSyncActions(uiState: AppDrawerSyncUiState) {
    Row {
        Icon(
            Icons.Default.Add,
            null,
            Modifier.clip(RoundedCornerShape(25.dp))
                .clickable(onClick = uiState.onAddSync)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.Settings,
            null,
            Modifier.clip(RoundedCornerShape(25.dp))
                .clickable(onClick = uiState.onManageSync)
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

@Composable
private fun AppDrawerSyncTaskItem(
    task: SyncTask,
    uiState: AppDrawerSyncUiState,
) {
    NavigationDrawerItem(
        icon = { SyncStatusIcon(task.lastStatus) },
        label = {
            Column {
                Text(
                    text = task.name.ifBlank { AppStrings.ui_unnamed_sync },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = syncStatusLabel(task.lastStatus),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        selected = false,
        badge = {
            Icon(
                if (task.lastStatus == SyncRunStatus.Running) Icons.Default.Stop else Icons.Default.PlayArrow,
                if (task.lastStatus == SyncRunStatus.Running) {
                    AppStrings.ui_stop_sync
                } else {
                    AppStrings.ui_sync_now
                },
                Modifier
                    .clip(RoundedCornerShape(25.dp))
                    .clickable { uiState.onTaskAction(task) }
            )
        },
        onClick = { uiState.onTaskClick(task) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}

@Composable
private fun SyncStatusIcon(status: SyncRunStatus) {
    when (status) {
        SyncRunStatus.Running -> CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 3.dp
        )

        SyncRunStatus.Success -> Icon(Icons.Default.CheckCircle, contentDescription = null)
        SyncRunStatus.Queued -> Icon(Icons.Default.Schedule, contentDescription = null)
        SyncRunStatus.PartialSuccess -> Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
        SyncRunStatus.Failure -> Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error
        )

        SyncRunStatus.Canceled -> Icon(Icons.Default.Stop, contentDescription = null)
        SyncRunStatus.Idle -> Icon(Icons.Default.Sync, contentDescription = null)
    }
}

private fun syncStatusLabel(status: SyncRunStatus): String {
    return when (status) {
        SyncRunStatus.Idle -> AppStrings.sync_status_idle
        SyncRunStatus.Queued -> AppStrings.ui_queuing
        SyncRunStatus.Running -> AppStrings.ui_executing
        SyncRunStatus.Success -> AppStrings.ui_success
        SyncRunStatus.PartialSuccess -> AppStrings.ui_partially_successful
        SyncRunStatus.Failure -> AppStrings.ui_failed
        SyncRunStatus.Canceled -> AppStrings.ui_canceled
    }
}

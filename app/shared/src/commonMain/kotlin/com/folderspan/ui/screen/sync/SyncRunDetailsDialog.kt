package com.folderspan.ui.screen.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.folderspan.ui.components.pagestate.PageErrorState
import com.folderspan.ui.components.pagestate.PageErrorType
import com.folderspan.ui.components.pagestate.PageStateLayout
import com.folderspan.ui.components.pagestate.PageViewState
import com.folderspan.ui.state.main.*
import kotlinx.coroutines.CancellationException
import strings.AppStrings

internal fun syncResultLabel(kind: SyncRunItemKind): String = when (kind) {
    SyncRunItemKind.Other -> AppStrings.sync_result_other
    SyncRunItemKind.Copy -> AppStrings.sync_preview_copy
    SyncRunItemKind.Rename -> AppStrings.sync_preview_conflict_copy
    SyncRunItemKind.CreateDirectory -> AppStrings.sync_preview_create_directory
    SyncRunItemKind.Unchanged -> AppStrings.sync_preview_unchanged
    SyncRunItemKind.Skip -> AppStrings.sync_preview_skipped
    SyncRunItemKind.Delete -> AppStrings.sync_preview_delete
    SyncRunItemKind.Recycle -> AppStrings.sync_preview_recycle
    SyncRunItemKind.Overwrite -> AppStrings.sync_preview_overwrite
    SyncRunItemKind.Conflict -> AppStrings.sync_preview_conflicts
    SyncRunItemKind.CleanSource -> AppStrings.sync_preview_clean_source
    SyncRunItemKind.Recovery -> AppStrings.sync_result_recovery
}

@Composable
internal fun SyncRunDetailsDialog(
    title: String,
    participants: Map<String, String>,
    loadPage: suspend (Long, Int) -> List<SyncRunDetailRow>,
    onDismiss: () -> Unit,
    onRetryItem: ((SyncRunItem) -> Unit)? = null,
) {
    var rows by remember { mutableStateOf(emptyList<SyncRunDetailRow>()) }
    var request by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(true) }
    LaunchedEffect(request) {
        loading = true
        error = null
        try {
            val page = loadPage(rows.size.toLong(), 100)
            rows = rows + page
            more = page.size == 100
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: AppStrings.ui_operation_failed }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        PageStateLayout(
            state = when {
                rows.isNotEmpty() -> PageViewState.Content
                loading -> PageViewState.Loading
                error != null -> PageViewState.Error(PageErrorState(PageErrorType.General, error))
                else -> PageViewState.Empty(AppStrings.ui_no_running_records_yet)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 440.dp),
            fillMaxSize = false,
            onRetry = { request++ },
        ) {
            LazyColumn(Modifier.testTag("sync-result-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rows, key = { it.ordinal }) { row ->
                    SelectionContainer {
                        Column(Modifier.fillMaxWidth().testTag("sync-result-row"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.item.path, style = MaterialTheme.typography.bodyMedium)
                            Text(listOfNotNull(syncResultLabel(row.item.kind), participants[row.item.participant],
                                if (row.item.success) AppStrings.ui_success else AppStrings.ui_failed).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (row.item.success) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                            Text(row.item.message, style = MaterialTheme.typography.bodySmall)
                            if (row.item.recoveryGroup.isNotEmpty()) Text(AppStrings.sync_result_group.format(arg0 = row.item.recoveryGroup), style = MaterialTheme.typography.bodySmall)
                            row.item.recoveryLocations.forEach { location ->
                                Text(location.path, style = MaterialTheme.typography.bodySmall)
                            }
                            if (!row.item.success && row.item.source != null && onRetryItem != null) {
                                TextButton(onClick = { onRetryItem(row.item) }) { Text(AppStrings.ui_retry_failed_items) }
                            }
                        }
                    }
                    HorizontalDivider()
                }
                item("footer") {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!loading && (more || error != null)) TextButton(onClick = { request++ }, modifier = Modifier.testTag("sync-result-more")) {
                        Text(if (error != null) AppStrings.ui_notification_retry else AppStrings.ui_load_more)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(AppStrings.ui_confirm) } })
}

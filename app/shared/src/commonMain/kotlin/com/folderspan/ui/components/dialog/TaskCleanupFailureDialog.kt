package com.folderspan.ui.components.dialog

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.folderspan.ui.state.main.TaskCleanupFailure
import com.folderspan.ui.state.main.TaskState
import org.koin.compose.koinInject
import strings.AppStrings

@Composable
fun TaskCleanupFailureDialogContainer() {
    val tasks = koinInject<TaskState>()
    val failures by tasks.cleanupFailures.collectAsState()
    val busy by tasks.deletionBusy.collectAsState()
    if (failures.isNotEmpty()) TaskCleanupFailureDialog(failures, busy,
        tasks::dismissTaskCleanupFailure, tasks::retryTaskCleanup, tasks::directlyDeleteFailedTaskRecords)
    else if (busy) AlertDialog(
        onDismissRequest = {},
        title = { Text(AppStrings.ui_task_cleanup_busy) },
        text = { LinearProgressIndicator() },
        confirmButton = {},
    )
}

@Composable
fun TaskCleanupFailureDialog(
    failures: List<TaskCleanupFailure>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onDeleteRecord: () -> Unit,
) {
    val keyboardFocus = remember { FocusRequester() }
    LaunchedEffect(keyboardFocus) { keyboardFocus.requestFocus() }
    AlertDialog(
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                if (!busy) onDismiss()
                true
            } else false
        }.focusRequester(keyboardFocus).focusable(),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(AppStrings.ui_task_cleanup_failed) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (failures.any { it.path == null }) AppStrings.ui_task_cleanup_unknown else
                    AppStrings.ui_task_cleanup_count.format(arg0 = failures.size.toString()))
                Text(AppStrings.ui_task_cleanup_notice)
                if (busy) {
                    LinearProgressIndicator()
                    Text(AppStrings.ui_task_cleanup_busy)
                }
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        failures.forEach { failure ->
                            Column {
                                Text("#${failure.taskKey}", style = MaterialTheme.typography.labelMedium)
                                failure.path?.let { Text(it) }
                                Text(cleanupFailureReason(failure.reason), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onRetry, enabled = !busy) { Text(AppStrings.ui_task_cleanup_retry) } },
        dismissButton = { TextButton(onDeleteRecord, enabled = !busy) { Text(AppStrings.ui_task_cleanup_delete_record) } },
    )
}

private fun cleanupFailureReason(reason: String): String = when (reason) {
    "journal_unreadable", "journal_version_unsupported" -> AppStrings.ui_task_cleanup_journal
    "writer_not_stopped" -> AppStrings.ui_task_cleanup_stopping
    "record_remove_failed" -> AppStrings.ui_task_cleanup_record
    "ownership_unverified", "archive_ownership_unverified", "creation_unconfirmed" -> AppStrings.ui_task_cleanup_ownership
    else -> AppStrings.ui_task_cleanup_io
}

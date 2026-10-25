package com.folderspan.ui.components.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folderspan.extensions.formatFileSize
import com.folderspan.ui.state.main.TaskResidualFile
import com.folderspan.ui.state.main.TaskResidualSummary
import strings.AppStrings

internal enum class TaskCleanupDialogKind { Failure, Busy, Residual, None }

internal fun taskCleanupDialogKind(
    hasFailures: Boolean,
    busy: Boolean,
    hasResidualNotice: Boolean,
): TaskCleanupDialogKind = when {
    hasFailures -> TaskCleanupDialogKind.Failure
    busy -> TaskCleanupDialogKind.Busy
    hasResidualNotice -> TaskCleanupDialogKind.Residual
    else -> TaskCleanupDialogKind.None
}

@Composable
fun TaskResidualNoticeDialog(
    summary: TaskResidualSummary,
    onKeep: () -> Unit,
    onCleanup: () -> Unit,
    modifier: Modifier = Modifier,
    canContinueTask: Boolean = false,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onKeep,
        title = { Text(AppStrings.ui_task_residual_title) },
        text = {
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp).testTag("task_residual_notice_content"),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "notice") {
                        Text(
                            when {
                                summary.recovered && summary.unknown -> AppStrings.ui_task_residual_recovered_unknown
                                summary.recovered -> AppStrings.ui_task_residual_recovered
                                summary.unknown -> AppStrings.ui_task_residual_notice_unknown
                                else -> AppStrings.ui_task_residual_notice
                            },
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    item(key = "summary") { TaskResidualHeader(summary) }
                    items(summary.files, key = { "path:${it.path}" }) { file -> TaskResidualFileRow(file) }
                    item(key = "guidance") {
                        Text(
                            if (canContinueTask) AppStrings.ui_task_residual_continue_notice
                            else AppStrings.ui_task_residual_keep_notice,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    item(key = "cleanup-guidance") {
                        Text(AppStrings.ui_task_residual_cleanup_notice, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onCleanup) { Text(AppStrings.ui_task_residual_delete) }
                TextButton(onClick = onKeep) { Text(AppStrings.ui_task_residual_keep) }
            }
        },
    )
}

@Composable
internal fun TaskResidualFiles(
    summary: TaskResidualSummary,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TaskResidualHeader(summary)
        SelectionContainer {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).testTag("task_residual_paths"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(summary.files, key = { it.path }) { file -> TaskResidualFileRow(file) }
            }
        }
    }
}

@Composable
private fun TaskResidualHeader(summary: TaskResidualSummary, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (summary.unknown) AppStrings.ui_task_residual_section_unknown else AppStrings.ui_task_residual_section,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        if (summary.files.isNotEmpty()) {
            Text(AppStrings.ui_task_residual_count.format(arg0 = summary.files.size.toString()))
        }
        if (summary.unknown) {
            Text(AppStrings.ui_task_residual_unknown, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TaskResidualFileRow(file: TaskResidualFile, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(file.path, style = MaterialTheme.typography.bodyMedium)
        Text(
            file.sizeBytes?.formatFileSize() ?: AppStrings.ui_task_residual_size_unknown,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

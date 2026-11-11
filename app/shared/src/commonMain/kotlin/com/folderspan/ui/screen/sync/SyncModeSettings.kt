package com.folderspan.ui.screen.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import com.folderspan.ui.components.menu.EditableExposedOutlinedDropdownMenu
import com.folderspan.ui.components.model.StringListUiState
import com.folderspan.ui.state.main.*
import strings.AppStrings

internal fun validSyncProtectionSettings(retention: String, threshold: String, mode: SyncMode = SyncMode.Mirror, recycle: Boolean = true): Boolean =
    mode !in listOf(SyncMode.Mirror, SyncMode.Bidirectional) ||
        (!recycle || retention.toIntOrNull() in 1..365) && threshold.toIntOrNull() in 1..100_000

internal fun syncConflictPolicyForMode(policy: SyncConflictPolicy, mode: SyncMode): SyncConflictPolicy = when {
    mode == SyncMode.Mirror -> SyncConflictPolicy.Replace
    mode == SyncMode.BackupThenClean && policy == SyncConflictPolicy.Replace -> SyncConflictPolicy.Rename
    else -> policy
}

private fun bidiPolicyLabel(policy: SyncBidiConflictPolicy): String = when (policy) {
    SyncBidiConflictPolicy.KeepBoth -> AppStrings.sync_bidi_keep_both
    SyncBidiConflictPolicy.Newer -> AppStrings.sync_bidi_newer
    SyncBidiConflictPolicy.Source -> AppStrings.sync_bidi_source
    SyncBidiConflictPolicy.Target -> AppStrings.sync_bidi_target
}

internal fun LazyGridScope.syncModeSettings(
    mode: SyncMode, bidiPolicy: SyncBidiConflictPolicy, recycle: Boolean, retention: String, threshold: String,
    onModeChange: (SyncMode) -> Unit, onBidiPolicyChange: (SyncBidiConflictPolicy) -> Unit, onRecycleChange: (Boolean) -> Unit,
    onRetentionChange: (String) -> Unit, onThresholdChange: (String) -> Unit,
) {
    item(key = "sync-mode") {
        EditableExposedOutlinedDropdownMenu(
            optionsUiState = StringListUiState(SyncMode.entries.map(::syncModeLabel)), value = syncModeLabel(mode),
            onValueChange = { value -> SyncMode.entries.firstOrNull { syncModeLabel(it) == value }?.let(onModeChange) },
            label = { Text(AppStrings.sync_mode_label) }, modifier = Modifier.fillMaxWidth().testTag("sync-mode"), readOnly = true,
        )
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(when (mode) {
            SyncMode.Update -> AppStrings.sync_mode_update_hint
            SyncMode.Mirror -> AppStrings.sync_mode_mirror_hint
            SyncMode.BackupThenClean -> AppStrings.sync_mode_backup_clean_hint
            SyncMode.Bidirectional -> AppStrings.sync_mode_bidirectional_hint
        })
    }
    if (mode == SyncMode.Bidirectional) {
        item(key = "sync-bidi-policy") {
            EditableExposedOutlinedDropdownMenu(
                optionsUiState = StringListUiState(SyncBidiConflictPolicy.entries.map(::bidiPolicyLabel)), value = bidiPolicyLabel(bidiPolicy),
                onValueChange = { value -> SyncBidiConflictPolicy.entries.firstOrNull { bidiPolicyLabel(it) == value }?.let(onBidiPolicyChange) },
                label = { Text(AppStrings.sync_bidi_policy) }, modifier = Modifier.fillMaxWidth().testTag("sync-bidi-policy"), readOnly = true,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(AppStrings.sync_bidi_policy_hint)
        }
    }
    if (mode in listOf(SyncMode.Mirror, SyncMode.Bidirectional)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SyncSwitchRow(AppStrings.sync_recycle_label, recycle, onRecycleChange,
                supportingText = if (recycle) AppStrings.sync_recycle_hint else AppStrings.sync_preview_no_recycle,
                switchModifier = Modifier.testTag("sync-recycle"))
        }
        if (recycle) {
            item(key = "sync-retention") {
                OutlinedTextField(retention, onRetentionChange,
                    label = { Text(AppStrings.sync_retention_days) }, singleLine = true,
                    isError = retention.toIntOrNull() !in 1..365,
                    supportingText = { Text(AppStrings.sync_invalid_retention) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("sync-retention"))
            }
        }
        item(key = "sync-delete-threshold") {
            OutlinedTextField(threshold, onThresholdChange, label = { Text(AppStrings.sync_delete_threshold) }, singleLine = true,
                isError = threshold.toIntOrNull() !in 1..100_000, supportingText = { Text(AppStrings.sync_delete_threshold_hint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("sync-delete-threshold"))
        }
    }
}

@Composable
internal fun SyncNotificationSettings(level: SyncNotifyLevel, onChange: (SyncNotifyLevel) -> Unit) {
    fun label(value: SyncNotifyLevel): String = when (value) {
        SyncNotifyLevel.All -> AppStrings.sync_notify_all
        SyncNotifyLevel.FailuresAndConfirmations -> AppStrings.sync_notify_failures_confirmations
        SyncNotifyLevel.Off -> AppStrings.sync_notify_off
    }
    EditableExposedOutlinedDropdownMenu(StringListUiState(SyncNotifyLevel.entries.map(::label)), label(level),
        onValueChange = { value -> SyncNotifyLevel.entries.firstOrNull { label(it) == value }?.let(onChange) },
        modifier = Modifier.fillMaxWidth().testTag("sync-notify-level"), readOnly = true,
        label = { Text(AppStrings.sync_notification_title) })
}

@Composable
internal fun SyncDefinitionChangeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppStrings.sync_definition_change_title) },
        text = { Text(AppStrings.sync_definition_change_hint) },
        confirmButton = { TextButton(onConfirm) { Text(AppStrings.ui_save) } },
        dismissButton = { TextButton(onDismiss) { Text(AppStrings.ui_cancel) } })
}

@Composable
internal fun SyncDisableRecycleDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppStrings.sync_disable_recycle_title) },
        text = { Text(AppStrings.sync_preview_no_recycle) },
        confirmButton = { TextButton(onConfirm) { Text(AppStrings.ui_confirm) } },
        dismissButton = { TextButton(onDismiss) { Text(AppStrings.ui_cancel) } })
}

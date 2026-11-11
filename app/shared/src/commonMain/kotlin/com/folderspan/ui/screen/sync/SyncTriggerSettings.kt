package com.folderspan.ui.screen.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folderspan.PlatformType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.sync.SyncRunCondition
import strings.AppStrings
import com.folderspan.ui.state.main.SyncTask

internal data class SyncConditionConfirmation(val conditions: Set<SyncRunCondition>, val run: () -> Unit)

internal fun sameSyncConditionSettings(expected: SyncTask, current: SyncTask?): Boolean = current != null &&
    expected.ownerDeviceId == current.ownerDeviceId && expected.requireWifi == current.requireWifi &&
    expected.requireCharging == current.requireCharging

internal fun syncWaitingConditionsLabel(conditions: Set<SyncRunCondition>): String =
    if (conditions.isEmpty()) AppStrings.ui_queuing else AppStrings.sync_waiting_conditions.format(arg0 = syncConditionsLabel(conditions))

internal fun supportsSyncRunConditions(platform: DeviceType = PlatformType): Boolean =
    platform == DeviceType.Android || platform == DeviceType.IOS

internal fun syncConditionsLabel(conditions: Set<SyncRunCondition>): String = conditions.joinToString(", ") {
    when (it) {
        SyncRunCondition.Wifi -> AppStrings.sync_condition_wifi
        SyncRunCondition.Charging -> AppStrings.sync_condition_charging
    }
}

@Composable
internal fun SyncTriggerSettings(
    deviceConnect: Boolean, networkAvailable: Boolean, requireWifi: Boolean, requireCharging: Boolean,
    onDeviceConnect: (Boolean) -> Unit, onNetworkAvailable: (Boolean) -> Unit,
    onRequireWifi: (Boolean) -> Unit, onRequireCharging: (Boolean) -> Unit,
    modifier: Modifier = Modifier, supportsConditions: Boolean = supportsSyncRunConditions(),
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(AppStrings.sync_events_title, Modifier.semantics { heading() })
        SyncSwitchRow(AppStrings.sync_event_device, deviceConnect, onDeviceConnect,
            switchModifier = Modifier.testTag("sync-event-device"))
        SyncSwitchRow(AppStrings.sync_event_network, networkAvailable, onNetworkAvailable,
            switchModifier = Modifier.testTag("sync-event-network"))
        Text(AppStrings.sync_events_hint)
        Text(AppStrings.sync_conditions_title, Modifier.semantics { heading() })
        if (supportsConditions) {
            SyncSwitchRow(AppStrings.sync_require_wifi, requireWifi, onRequireWifi,
                switchModifier = Modifier.testTag("sync-require-wifi"))
            SyncSwitchRow(AppStrings.sync_require_charging, requireCharging, onRequireCharging,
                switchModifier = Modifier.testTag("sync-require-charging"))
            Text(AppStrings.sync_conditions_hint)
        } else Text(AppStrings.sync_conditions_unsupported)
    }
}

@Composable
internal fun SyncSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    switchModifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (supportingText != null) {
                Text(supportingText)
            }
        }
        Switch(checked, onCheckedChange, switchModifier.semantics { contentDescription = label })
    }
}

@Composable
internal fun SyncConditionOverrideDialog(
    conditions: Set<SyncRunCondition>, onConfirm: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier,
) {
    AlertDialog(onDismissRequest = onDismiss, modifier = modifier.testTag("sync-condition-confirmation"),
        title = { Text(AppStrings.sync_ignore_conditions_title) },
        text = { Text(AppStrings.sync_ignore_conditions_hint.format(arg0 = syncConditionsLabel(conditions))) },
        confirmButton = { TextButton(onConfirm, Modifier.testTag("sync-ignore-conditions")) { Text(AppStrings.sync_ignore_conditions_once) } },
        dismissButton = { TextButton(onDismiss) { Text(AppStrings.ui_cancel) } })
}

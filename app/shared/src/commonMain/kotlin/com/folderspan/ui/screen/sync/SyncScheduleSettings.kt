package com.folderspan.ui.screen.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.folderspan.PlatformType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.ui.state.main.SyncScheduleType
import com.folderspan.ui.state.main.SyncTask
import strings.AppStrings

internal fun syncScheduleTimeText(minutes: Int): String =
    "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

internal fun parseSyncScheduleTime(value: String): Int? {
    val parts = value.trim().split(':')
    if (parts.size != 2 || parts[0].length !in 1..2 || parts[1].length != 2 ||
        parts.any { part -> part.any { it !in '0'..'9' } }) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}

internal fun syncScheduleInputError(type: SyncScheduleType, time: String, weekdays: Int): String? = when {
    type in listOf(SyncScheduleType.Daily, SyncScheduleType.Weekly) && parseSyncScheduleTime(time) == null -> AppStrings.sync_invalid_schedule_time
    type == SyncScheduleType.Weekly && weekdays !in 1..127 -> AppStrings.sync_weekday_required
    else -> null
}

internal fun syncWeekdayLabels(): List<String> = listOf(AppStrings.sync_weekday_monday, AppStrings.sync_weekday_tuesday,
    AppStrings.sync_weekday_wednesday, AppStrings.sync_weekday_thursday, AppStrings.sync_weekday_friday,
    AppStrings.sync_weekday_saturday, AppStrings.sync_weekday_sunday)

internal fun syncScheduleLabel(task: SyncTask): String = when (task.scheduleType) {
    SyncScheduleType.Manual -> AppStrings.ui_manual_execution
    SyncScheduleType.Interval -> AppStrings.ui_every_arg0_minutes.format(arg0 = task.intervalMinutes.toString())
    SyncScheduleType.Daily -> AppStrings.sync_schedule_daily + " " + syncScheduleTimeText(task.scheduleTimeMinutes)
    SyncScheduleType.Weekly -> AppStrings.sync_schedule_weekly + " " + syncWeekdayLabels().filterIndexed { index, _ ->
        task.scheduleWeekdays and (1 shl index) != 0
    }.joinToString(", ") + " " + syncScheduleTimeText(task.scheduleTimeMinutes)
}

internal fun syncSchedulePlatformHint(platform: DeviceType = PlatformType): String = when (platform) {
    DeviceType.Android -> AppStrings.sync_schedule_scope_android
    DeviceType.IOS -> AppStrings.sync_schedule_scope_ios
    DeviceType.JVM -> AppStrings.sync_schedule_scope_desktop
    DeviceType.JS -> AppStrings.sync_schedule_scope_web
}

@Composable
internal fun SyncScheduleSettings(
    type: SyncScheduleType, enabled: Boolean, interval: String, time: String, weekdays: Int,
    onTypeChange: (SyncScheduleType) -> Unit, onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (String) -> Unit, onTimeChange: (String) -> Unit, onWeekdaysChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SyncScheduleType.entries.forEachIndexed { index, value ->
                SegmentedButton(selected = type == value, onClick = { onTypeChange(value) },
                    modifier = Modifier.testTag("sync-schedule-${value.name}"),
                    shape = SegmentedButtonDefaults.itemShape(index, SyncScheduleType.entries.size)) {
                    Text(when (value) {
                        SyncScheduleType.Manual -> AppStrings.ui_manual
                        SyncScheduleType.Interval -> AppStrings.ui_interval
                        SyncScheduleType.Daily -> AppStrings.sync_schedule_daily
                        SyncScheduleType.Weekly -> AppStrings.sync_schedule_weekly
                    })
                }
            }
        }
        if (type != SyncScheduleType.Manual) {
            SyncSwitchRow(AppStrings.ui_enable_scheduled_execution, enabled, onEnabledChange,
                switchModifier = Modifier.testTag("sync-schedule-enabled"))
        }
        if (type == SyncScheduleType.Interval) {
            OutlinedTextField(interval, onIntervalChange, label = { Text(AppStrings.ui_interval_minutes) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("sync-schedule-interval"))
        }
        if (type == SyncScheduleType.Daily || type == SyncScheduleType.Weekly) {
            OutlinedTextField(time, onTimeChange, label = { Text(AppStrings.sync_schedule_time) }, singleLine = true,
                isError = parseSyncScheduleTime(time) == null, supportingText = { Text(AppStrings.sync_schedule_time_hint) },
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("sync-schedule-time"))
        }
        if (type == SyncScheduleType.Weekly) {
            Text(AppStrings.sync_schedule_weekdays)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                syncWeekdayLabels().forEachIndexed { index, label ->
                    val bit = 1 shl index
                    FilterChip(selected = weekdays and bit != 0, onClick = { onWeekdaysChange(weekdays xor bit) },
                        label = { Text(label) }, modifier = Modifier.testTag("sync-schedule-weekday-$index"))
                }
            }
            if (weekdays !in 1..127) Text(AppStrings.sync_weekday_required)
        }
        Text(syncSchedulePlatformHint())
        if (type != SyncScheduleType.Manual) Text(AppStrings.sync_schedule_catch_up_hint)
    }
}

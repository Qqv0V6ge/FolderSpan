package com.folderspan.ui.screen.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.folderspan.extensions.formatSpeed
import com.folderspan.data.StatusEnum
import com.folderspan.service.operation.TransferWaitReason
import com.folderspan.ui.state.main.Task
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import strings.AppStrings

internal data class SyncRuntimeStatistics(val speed: String?, val parallelism: Int, val waiting: Set<TransferWaitReason>)

internal fun syncRuntimeStatistics(tasks: List<Task>, waiting: Set<TransferWaitReason>): SyncRuntimeStatistics {
    val running = tasks.filter { it.status == StatusEnum.LOADING }
    val speeds = running.mapNotNull { it.runtimeByteSpeedPerSecond() }
    return SyncRuntimeStatistics(speeds.takeIf { it.isNotEmpty() }?.sum()?.takeIf { it.isFinite() }?.formatSpeed(),
        running.sumOf { it.activeParallelCount() }, waiting)
}

/** Sampling follows the visible running page; the transfer itself owns the observations. */
@Composable
internal fun rememberSyncRuntimeStatistics(taskId: Long, running: Boolean, sample: () -> SyncRuntimeStatistics): SyncRuntimeStatistics? {
    var statistics by remember(taskId) { mutableStateOf<SyncRuntimeStatistics?>(null) }
    val currentSample by rememberUpdatedState(sample)
    LaunchedEffect(taskId, running) {
        statistics = null
        if (running) while (isActive) {
            statistics = currentSample()
            delay(500)
        }
    }
    return statistics.takeIf { running }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SyncRuntimeStatisticsView(taskId: Long, statistics: SyncRuntimeStatistics, modifier: Modifier = Modifier) {
    Column(modifier.testTag("sync-runtime-$taskId"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(AppStrings.ui_speed_arg0.format(arg0 = statistics.speed ?: "--"), style = MaterialTheme.typography.bodySmall)
            Text(AppStrings.sync_current_file_parallelism.format(arg0 = statistics.parallelism.toString()), style = MaterialTheme.typography.bodySmall)
        }
        if (statistics.waiting.isNotEmpty()) Text(TransferWaitReason.entries.filter { it in statistics.waiting }.joinToString(" · ") {
            when (it) {
                TransferWaitReason.DeviceBusy -> AppStrings.sync_wait_device_busy
                TransferWaitReason.Receiver -> AppStrings.sync_wait_receiver
                TransferWaitReason.Backoff -> AppStrings.sync_wait_backoff
            }
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

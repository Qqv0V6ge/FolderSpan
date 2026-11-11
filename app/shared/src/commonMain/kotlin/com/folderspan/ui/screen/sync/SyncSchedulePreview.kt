package com.folderspan.ui.screen.sync

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.state.main.SyncScheduleType

@Composable
private fun ScheduleSample() = MaterialTheme {
    GridList(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalSpacing = 16.dp,
        horizontalSpacing = 16.dp,
    ) {
        syncExecutionSettings("1", "120", "1", {}, {}, {})
        item(span = { GridItemSpan(maxLineSpan) }) {
            SyncScheduleSettings(SyncScheduleType.Weekly, true, "15", "02:30", 1 or 8, {}, {}, {}, {}, {})
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SyncTriggerSettings(true, true, true, true, {}, {}, {}, {}, supportsConditions = true)
        }
    }
}

@Preview(name = "Schedule compact", widthDp = 390, heightDp = 800)
@Composable private fun ScheduleCompact() = ScheduleSample()

@Preview(name = "Schedule medium", widthDp = 720, heightDp = 900)
@Composable private fun ScheduleMedium() = ScheduleSample()

@Preview(name = "Schedule expanded", widthDp = 1024, heightDp = 768)
@Composable private fun ScheduleExpanded() = ScheduleSample()

@Preview(name = "Schedule large", widthDp = 1366, heightDp = 900)
@Composable private fun ScheduleLarge() = ScheduleSample()

@Preview(name = "Schedule extra large", widthDp = 1700, heightDp = 900)
@Composable private fun ScheduleExtraLarge() = ScheduleSample()

@Preview(name = "Schedule short", widthDp = 680, heightDp = 360)
@Composable private fun ScheduleShort() = ScheduleSample()

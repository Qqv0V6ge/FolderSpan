package com.folderspan.ui.components.filter

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.pro.presentation.screen.feedback.FeedbackTicketFilterSelection
import com.folderspan.pro.presentation.screen.feedback.FeedbackTicketFilterSheet
import com.folderspan.pro.presentation.screen.feedback.LocalFeedbackTicketFilterSheet
import kotlin.math.absoluteValue
import kotlin.time.Clock

private val defaultFeedbackTicketFilterSheet = object : FeedbackTicketFilterSheet {
    @Composable
    override fun Content(
        selection: FeedbackTicketFilterSelection,
        onApply: (FeedbackTicketFilterSelection) -> Unit,
        onDismiss: () -> Unit,
    ) {
        FeedbackTicketFilterSheetContent(
            selection = selection,
            onApply = onApply,
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun FeedbackTicketFilterSheetProvider(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalFeedbackTicketFilterSheet provides defaultFeedbackTicketFilterSheet,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedbackTicketFilterSheetContent(
    selection: FeedbackTicketFilterSelection,
    onApply: (FeedbackTicketFilterSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(selection) { mutableStateOf(selection) }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val now = remember { Clock.System.now().epochSeconds }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        FilterSheetFrame(
            activeFilterCount = draft.activeCount,
            searchQuery = "",
            onSearchQueryChange = {},
            searchPlaceholder = "",
            onReset = { draft = FeedbackTicketFilterSelection() },
            onApply = {
                onApply(draft)
                onDismiss()
            },
            showSearch = false,
        ) {
            FilterSectionCard(
                title = AppStrings.ui_feedback_filter_type,
                icon = Icons.Default.Tune,
            ) {
                FilterOptions {
                    listOf(
                        null to AppStrings.ui_feedback_filter_all,
                        "feedback" to AppStrings.ui_feedback,
                        "suggestion" to AppStrings.ui_suggestion,
                    ).forEach { (value, label) ->
                        FilterOptionChip(
                            selected = draft.type == value,
                            label = label,
                            onClick = { draft = draft.copy(type = value) },
                        )
                    }
                }
            }

            FilterSectionCard(
                title = AppStrings.ui_feedback_filter_platform,
                icon = Icons.Default.Devices,
            ) {
                FilterOptions {
                    listOf<String?>(null, "android", "ios", "windows", "macos", "linux", "js", "other")
                        .forEach { value ->
                            FilterOptionChip(
                                selected = draft.platform == value,
                                label = value ?: AppStrings.ui_feedback_filter_all,
                                onClick = { draft = draft.copy(platform = value) },
                            )
                        }
                }
            }

            FilterSectionCard(
                title = AppStrings.ui_time_range,
                icon = Icons.Default.Schedule,
            ) {
                FilterOptions {
                    listOf(
                        0 to AppStrings.ui_feedback_filter_all,
                        7 to AppStrings.ui_feedback_last_7_days,
                        30 to AppStrings.ui_feedback_last_30_days,
                    ).forEach { (days, label) ->
                        val startTime = if (days == 0) null else now - days * SECONDS_PER_DAY
                        FilterOptionChip(
                            selected = if (startTime == null) {
                                draft.startTimeEpochSeconds == null && draft.endTimeEpochSeconds == null
                            } else {
                                startTime.matches(draft.startTimeEpochSeconds)
                            },
                            label = label,
                            onClick = {
                                draft = draft.copy(
                                    startTimeEpochSeconds = startTime,
                                    endTimeEpochSeconds = null,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterOptions(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

private fun Long?.matches(current: Long?): Boolean = when {
    this == null -> current == null
    current == null -> false
    else -> (current - this).absoluteValue < FILTER_TIME_TOLERANCE_SECONDS
}

private const val SECONDS_PER_DAY = 24 * 60 * 60L
private const val FILTER_TIME_TOLERANCE_SECONDS = 5 * 60L

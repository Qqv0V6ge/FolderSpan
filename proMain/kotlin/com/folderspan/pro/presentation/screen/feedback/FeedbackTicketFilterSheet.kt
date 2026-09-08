package com.folderspan.pro.presentation.screen.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

data class FeedbackTicketFilterSelection(
    val type: String? = null,
    val platform: String? = null,
    val startTimeEpochSeconds: Long? = null,
    val endTimeEpochSeconds: Long? = null,
) {
    val activeCount: Int
        get() = listOfNotNull(
            type,
            platform,
            if (startTimeEpochSeconds != null || endTimeEpochSeconds != null) "time" else null,
        ).size
}

interface FeedbackTicketFilterSheet {
    @Composable
    fun Content(
        selection: FeedbackTicketFilterSelection,
        onApply: (FeedbackTicketFilterSelection) -> Unit,
        onDismiss: () -> Unit,
    )
}

val LocalFeedbackTicketFilterSheet = staticCompositionLocalOf<FeedbackTicketFilterSheet?> { null }

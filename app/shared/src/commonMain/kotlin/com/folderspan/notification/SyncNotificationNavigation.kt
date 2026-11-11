package com.folderspan.notification

import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.screen.sync.SyncRunDetailScreen
import com.folderspan.ui.screen.sync.SyncRunPreviewScreen
import strings.AppStrings

internal fun SyncNotificationTarget.screen(): AppScreenRoute = if (opensPreview) {
    SyncRunPreviewScreen(taskId, pendingDeletionsOnly = event == SyncNotificationEvent.PendingDeletions)
} else SyncRunDetailScreen(taskId)

@Composable
internal fun SyncNotificationActionRow(metadata: Map<String, String>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (syncNotificationTarget(metadata) == null) return
    FilledTonalButton(onOpen, modifier.testTag("sync-notification-view")) { Text(AppStrings.android_action_view) }
}

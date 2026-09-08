package com.folderspan.ui.screen.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.ui.screen.network.NetworkItemListContent

@Composable
internal fun CloudDrivePageWidgetPreview(
    selectedStepIndex: Int,
    modifier: Modifier = Modifier,
) {
    if (shouldShowCloudDrivePreviewNetworkSelector(selectedStepIndex)) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CloudDriveNetworkSelectorPreview()
        }
    } else {
        RemoteFilesPageWidgetPreview(
            selectedStepIndex = selectedStepIndex,
            modifier = modifier
        )
    }
}

@Composable
private fun CloudDriveNetworkSelectorPreview() {
    NetworkItemListContent(
        entry = onboardingPreviewNetworkEntry(),
        isSelected = false,
        selectionMode = false,
        onSelectionToggle = {},
        isConnected = true,
        onEdit = {},
        onDuplicate = {},
        onDelete = {},
        onClick = {},
        onDisconnect = {},
        onTogglePinned = {},
        onPersist = {}
    )
}

internal fun shouldShowCloudDrivePreviewNetworkSelector(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    selectedStepIndex == 0

internal fun shouldShowCloudDrivePreviewRemoteFolder(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    !shouldShowCloudDrivePreviewNetworkSelector(selectedStepIndex) &&
        shouldShowRemoteFilesPreviewRemoteFolder(selectedStepIndex)

internal fun shouldShowCloudDrivePreviewRemoteFolderMenuButton(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    shouldShowRemoteFilesPreviewRemoteFolderMenuButton(selectedStepIndex)

internal fun shouldShowCloudDrivePreviewCopyMenu(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    !shouldShowCloudDrivePreviewNetworkSelector(selectedStepIndex) &&
        shouldShowRemoteFilesPreviewCopyMenu(selectedStepIndex)

internal fun shouldShowCloudDrivePreviewLocalFolder(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    !shouldShowCloudDrivePreviewNetworkSelector(selectedStepIndex) &&
        shouldShowRemoteFilesPreviewLocalFolder(selectedStepIndex)

package com.folderspan.ui.screen.onboarding

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.device.DeviceType
import com.folderspan.notification.RequestNotificationFactory
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.SocketDevice
import com.folderspan.ui.components.banner.MaterialBanner
import com.folderspan.ui.components.file.FileCard
import com.folderspan.ui.components.file.FileMenu
import com.folderspan.ui.screen.file.share.ShareToDeviceListItem

@Composable
internal fun DeviceSharePageWidgetPreview(
    selectedStepIndex: Int,
    modifier: Modifier = Modifier,
) {
    var fileItemMenuExpanded by remember(selectedStepIndex) {
        mutableStateOf(shouldExpandDeviceSharePreviewFileItemMenu(selectedStepIndex))
    }
    val previewFile = remember { linkSharePreviewCardFileItem() }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (shouldShowDeviceSharePreviewCardFileItem(selectedStepIndex)) {
            FileCard(
                file = previewFile,
                isSelected = shouldSelectLinkSharePreviewFileItem(selectedStepIndex),
                isSelectionMode = false,
                modifier = Modifier.fillMaxWidth(),
                onToggleSelect = {},
                onClick = {},
                trailingContent = {
                    if (shouldUseLinkSharePreviewFileItemMenu() &&
                        shouldShowDeviceSharePreviewFileItemMenuButton(selectedStepIndex)
                    ) {
                        FileMenu(
                            permission = linkSharePreviewFileItemMenuPermission(),
                            expanded = fileItemMenuExpanded,
                            onExpandedChange = { requestedExpanded ->
                                fileItemMenuExpanded = resolveDeviceSharePreviewFileItemMenuExpanded(
                                    requestedExpanded = requestedExpanded
                                )
                            },
                            onShare = {},
                            onInfo = {}
                        )
                    }
                }
            )
        }
        if (shouldShowDeviceSharePreviewInlineFileMenu(selectedStepIndex)) {
            LinkSharePreviewInlineFileMenu()
        }
        if (shouldShowDeviceShareToDeviceListItem(selectedStepIndex)) {
            DeviceShareToDeviceListPreview(modifier = Modifier.fillMaxWidth())
        }
        if (shouldShowDeviceShareReceiverBanner(selectedStepIndex)) {
            DeviceShareReceiverBannerPreview(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DeviceShareToDeviceListPreview(modifier: Modifier = Modifier) {
    ShareToDeviceListItem(
        device = deviceSharePreviewSocketDevice(),
        sendStatus = null,
        sendMessage = null,
        onCancel = {},
        onClick = {},
        modifier = modifier
    )
}

@Composable
private fun DeviceShareReceiverBannerPreview(modifier: Modifier = Modifier) {
    MaterialBanner(
        title = deviceShareReceiverBannerTitle(),
        message = deviceShareReceiverBannerMessage(),
        onActionClick = {},
        onDismiss = {},
        modifier = modifier,
        icon = Icons.Default.Info,
        actionLabel = deviceShareReceiverBannerActionLabel(),
        dismissLabel = deviceShareReceiverBannerDismissLabel()
    )
}

internal fun shouldShowDeviceSharePreviewCardFileItem(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    shouldShowLinkSharePreviewCardFileItem(selectedStepIndex)

internal fun shouldShowDeviceSharePreviewInlineFileMenu(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    shouldShowLinkSharePreviewInlineFileMenu(selectedStepIndex)

internal fun shouldShowDeviceSharePreviewFileItemMenuButton(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    shouldShowLinkSharePreviewFileItemMenuButton(selectedStepIndex)

internal fun shouldExpandDeviceSharePreviewFileItemMenu(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    shouldExpandLinkSharePreviewFileItemMenu(selectedStepIndex)

internal fun resolveDeviceSharePreviewFileItemMenuExpanded(
    requestedExpanded: Boolean,
): Boolean =
    resolveLinkSharePreviewFileItemMenuExpanded(requestedExpanded)

internal fun shouldShowDeviceShareToDeviceListItem(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    selectedStepIndex == 2

internal fun shouldShowDeviceShareToDeviceHeader(): Boolean = false

internal fun shouldShowDeviceShareReceiverBanner(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    selectedStepIndex == 3

internal fun deviceSharePreviewSocketDevice(): SocketDevice =
    SocketDevice(
        id = "pixel-8",
        name = "Pixel 8",
        pathSeparator = "/",
        host = "192.168.1.24",
        type = DeviceType.Android,
        connectType = ConnectType.New
    )

internal fun deviceShareReceiverBannerTitle(): String =
    deviceShareReceiverNotification().title

internal fun deviceShareReceiverBannerMessage(): String =
    deviceShareReceiverNotification().message

internal fun deviceShareReceiverBannerActionLabel(): String = AppStrings.ui_agree

internal fun deviceShareReceiverBannerDismissLabel(): String = AppStrings.ui_reject

private fun deviceShareReceiverNotification() =
    RequestNotificationFactory.buildDeviceShareNotification(
        deviceId = deviceSharePreviewSocketDevice().id,
        deviceName = deviceSharePreviewSocketDevice().name
    ).notification

package com.folderspan.ui.screen.onboarding

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskMenuPermission
import com.folderspan.service.http.server.HttpShareFileServer
import com.folderspan.ui.components.drawer.AppDrawerHeader
import com.folderspan.ui.components.file.FileCard
import com.folderspan.ui.components.file.FileMenu
import com.folderspan.ui.components.file.FileMenuItems
import com.folderspan.ui.components.fileshare.FileShareLinkCardContainer
import com.folderspan.ui.screen.file.share.LinkShareDeviceStatusListItem
import com.folderspan.ui.state.file.FileShareLikeCategory.WAITING
import com.folderspan.ui.state.file.FileShareState
import org.koin.compose.koinInject
import kotlin.time.Clock

@Composable
internal fun LinkSharePageWidgetPreview(
    selectedStepIndex: Int,
    modifier: Modifier = Modifier,
) {
    val fileShareState = koinInject<FileShareState>()
    val httpShareFileServer = remember(fileShareState) {
        HttpShareFileServer.getInstance(fileShareState)
    }
    var fileItemMenuExpanded by remember(selectedStepIndex) {
        mutableStateOf(shouldExpandLinkSharePreviewFileItemMenu(selectedStepIndex))
    }
    val previewFile = remember { linkSharePreviewCardFileItem() }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (shouldShowLinkSharePreviewCardFileItem(selectedStepIndex)) {
            FileCard(
                file = previewFile,
                isSelected = shouldSelectLinkSharePreviewFileItem(selectedStepIndex),
                isSelectionMode = false,
                modifier = Modifier.fillMaxWidth(),
                onToggleSelect = {},
                onClick = {},
                trailingContent = {
                    if (shouldUseLinkSharePreviewFileItemMenu() &&
                        shouldShowLinkSharePreviewFileItemMenuButton(selectedStepIndex)
                    ) {
                        FileMenu(
                            permission = linkSharePreviewFileItemMenuPermission(),
                            expanded = fileItemMenuExpanded,
                            onExpandedChange = { requestedExpanded ->
                                fileItemMenuExpanded = resolveLinkSharePreviewFileItemMenuExpanded(
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
        if (shouldShowLinkSharePreviewInlineFileMenu(selectedStepIndex)) {
            LinkSharePreviewInlineFileMenu()
        }
        if (shouldShowLinkSharePreviewCard(selectedStepIndex)) {
            FileShareLinkCardContainer(
                fileShareState = fileShareState,
                httpShareFileServer = httpShareFileServer,
                ipAddresses = listOf("192.168.1.100"),
                onClickOpenQRCode = {},
                runtimeSideEffectsEnabled = false,
                serverControlsEnabled = false,
                serverRunningOverride = linkSharePreviewCardServerRunningOverride()
            )
        }
        if (shouldShowLinkSharePreviewStatusTabs()) {
            LinkShareStatusTabs(modifier = Modifier.fillMaxWidth())
        }
        if (shouldShowLinkSharePreviewDeviceList()) {
            LinkShareDeviceListPreview()
        }
    }
}

@Composable
internal fun LinkSharePreviewInlineFileMenu(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(min = 176.dp, max = 240.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Column {
            FileMenuItems(
                permission = linkSharePreviewFileItemMenuPermission(),
                onShare = {}
            )
        }
    }
}

@Composable
private fun LinkShareDeviceListPreview(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        AppDrawerHeader(
            title = linkSharePreviewDeviceListHeaderTitle(),
            actions = {}
        )
        LinkShareDeviceStatusListItem(
            category = WAITING,
            deviceName = linkSharePreviewDeviceName(),
            uploadStatus = null,
            onShowDeviceLog = {},
            onApprove = {},
            onRejectOrRemove = {},
            onClick = {}
        )
    }
}

@Composable
private fun LinkShareStatusTabs(modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        val options = linkSharePreviewStatusOptions()
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedLinkSharePreviewStatusIndex(),
                onClick = {},
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label)
            }
        }
    }
}

internal fun shouldShowLinkSharePreviewCard(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    isOnboardingPreviewNodeUnlocked(
        nodeIndex = 2,
        selectedStepIndex = selectedStepIndex
    )

internal fun shouldUseRealLinkShareCardWidget(): Boolean = true

internal fun shouldUseExtractedLinkShareCardContent(): Boolean = false

internal fun shouldShowLinkSharePreviewCardFileItem(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    selectedStepIndex == 0

internal fun shouldShowLinkSharePreviewInlineFileMenu(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    selectedStepIndex == 1

internal fun shouldShowLinkSharePreviewFileItemOutsideCard(): Boolean = true

internal fun shouldShowLinkSharePreviewFileItemInsideCard(): Boolean = false

internal fun shouldSelectLinkSharePreviewFileItem(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    false

internal fun shouldUseLinkSharePreviewFileItemMenu(): Boolean = true

internal fun shouldShowLinkSharePreviewFileItemMenuButton(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    shouldShowLinkSharePreviewCardFileItem(selectedStepIndex)

internal fun shouldExpandLinkSharePreviewFileItemMenu(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    false

internal fun resolveLinkSharePreviewFileItemMenuExpanded(
    requestedExpanded: Boolean,
): Boolean =
    requestedExpanded

internal fun shouldUseRealLinkSharePreviewFileItemDropdownMenu(): Boolean = true

internal fun shouldUseNavigatorBackedLinkSharePreviewFileItemMenu(): Boolean = false

internal fun linkSharePreviewFileItemMenuPermission(): DiskMenuPermission =
    DiskMenuPermission(
        share = true
    )

internal fun linkSharePreviewCardFileItem(
    timestamp: Long = Clock.System.now().toEpochMilliseconds(),
): FileSimpleInfo =
    onboardingPreviewFile(
        name = "report.pdf",
        path = "/Documents/report.pdf",
        isDirectory = false,
        mineType = "application/pdf",
        size = 2_457_600L,
        createdDate = timestamp,
        updatedDate = timestamp,
    )

internal fun linkSharePreviewCardServerRunningOverride(): Boolean = true

internal fun shouldUseRealLinkShareDeviceWidget(): Boolean = false

internal fun shouldUseRealShareToDeviceListWidget(): Boolean = false

internal fun shouldShowLinkSharePreviewStatusTabs(): Boolean = false

internal fun linkSharePreviewStatusOptions(): List<String> =
    listOf(AppStrings.ui_wait_1, AppStrings.ui_allow_0, AppStrings.ui_reject_0)

internal fun selectedLinkSharePreviewStatusIndex(): Int = 0

internal fun shouldShowLinkSharePreviewDeviceList(): Boolean = false

internal fun linkSharePreviewDeviceListHeaderTitle(): String = AppStrings.ui_share_link

internal fun linkSharePreviewDeviceName(): String = "Pixel 8"

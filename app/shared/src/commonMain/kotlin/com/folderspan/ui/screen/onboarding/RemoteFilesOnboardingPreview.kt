package com.folderspan.ui.screen.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskMenuPermission
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.SocketDevice
import com.folderspan.ui.components.drawer.DeviceDrawerListItem
import com.folderspan.ui.components.file.FileCard
import com.folderspan.ui.components.file.FileItemOperationStatus
import com.folderspan.ui.components.file.FileMenu
import com.folderspan.ui.components.file.FileMenuItems
import com.folderspan.ui.screen.main.HomeBottomBarPreviewState
import com.folderspan.ui.screen.main.HomeScreen

@Composable
internal fun RemoteFilesPageWidgetPreview(
    selectedStepIndex: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (shouldShowRemoteFilesPreviewDeviceSelector(selectedStepIndex)) {
            DeviceDrawerListItem(
                device = remoteFilesPreviewSocketDevice(),
                selected = true,
                onClick = {},
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (shouldShowRemoteFilesPreviewRemoteFolder(selectedStepIndex)) {
            RemoteFilesPreviewFolderCard(
                file = remoteFilesPreviewRemoteFolder(),
                isSelected = true,
                showMenuButton = shouldShowRemoteFilesPreviewRemoteFolderMenuButton(selectedStepIndex),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (shouldShowRemoteFilesPreviewCopyMenu(selectedStepIndex)) {
            RemoteFilesCopyMenuPreview(modifier = Modifier.fillMaxWidth())
        }
        if (shouldShowRemoteFilesPreviewLocalFolder(selectedStepIndex)) {
            RemoteFilesLocalPastePreview(
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun RemoteFilesCopyMenuPreview(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (shouldShowRemoteFilesPreviewCopyMenuFolderCard()) {
            RemoteFilesPreviewFolderCard(
                file = remoteFilesPreviewRemoteFolder(),
                isSelected = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        RemoteFilesPreviewInlineDeviceMenu()
    }
}

@Composable
private fun RemoteFilesLocalPastePreview(
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    Column(modifier = modifier) {
        HomeScreen.HomeBottomBarContainer(
            snackbarHostState = snackbarHostState,
            previewState = HomeBottomBarPreviewState(
                selectedFiles = listOf(remoteFilesPreviewRemoteFolder()),
                isPasteCopyFile = true,
                canPasteIntoCurrent = true,
                hasMenuPermission = true,
                canCreateInCurrentDesk = true
            )
        )
    }
}

internal fun remoteFilesPreviewHomeBottomBarPreviewState(): HomeBottomBarPreviewState =
    HomeBottomBarPreviewState(
        selectedFiles = listOf(remoteFilesPreviewRemoteFolder()),
        isPasteCopyFile = true,
        canPasteIntoCurrent = true,
        hasMenuPermission = true,
        canCreateInCurrentDesk = true
    )

@Composable
private fun RemoteFilesPreviewFolderCard(
    file: FileSimpleInfo,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    operationStatus: FileItemOperationStatus? = null,
    showMenuButton: Boolean = false,
) {
    FileCard(
        file = file,
        isSelected = isSelected,
        isSelectionMode = false,
        operationStatus = operationStatus,
        modifier = modifier,
        onToggleSelect = {},
        onClick = {},
        trailingContent = {
            if (showMenuButton) {
                FileMenu(
                    permission = remoteFilesPreviewDeviceMenuPermission(),
                    expanded = false,
                    onExpandedChange = {},
                    onCopy = {},
                    onMove = {},
                    onDelete = {},
                    onRename = {},
                    onFavorite = {},
                    onShare = {},
                    onInfo = {}
                )
            }
        }
    )
}

@Composable
private fun RemoteFilesPreviewInlineDeviceMenu(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(min = 176.dp, max = 240.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Column {
            FileMenuItems(
                permission = remoteFilesPreviewDeviceMenuPermission(),
                onCopy = {},
                onMove = {},
                onDelete = {},
                onRename = {},
                onFavorite = {},
                onShare = {},
                onInfo = {}
            )
        }
    }
}

internal fun shouldShowRemoteFilesPreviewDeviceSelector(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    selectedStepIndex == 0

internal fun shouldShowRemoteFilesPreviewRemoteFolder(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    selectedStepIndex == 1

internal fun shouldShowRemoteFilesPreviewRemoteFolderMenuButton(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    selectedStepIndex == 1

internal fun shouldShowRemoteFilesPreviewCopyMenu(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    selectedStepIndex == 2

internal fun shouldShowRemoteFilesPreviewCopyMenuFolderCard(): Boolean = false

internal fun shouldShowRemoteFilesPreviewLocalFolder(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    selectedStepIndex == 3

internal fun shouldShowRemoteFilesPreviewHomePasteBar(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    selectedStepIndex == 3

internal fun shouldShowRemoteFilesPreviewLocalPath(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    false

internal fun shouldShowRemoteFilesPreviewLocalPasteFolderCard(): Boolean = false

internal fun shouldShowRemoteFilesPreviewTransferProgress(
    selectedStepIndex: Int = initialOnboardingStepIndex(),
): Boolean =
    false

internal fun remoteFilesPreviewRemoteFolder(): FileSimpleInfo =
    onboardingPreviewFile(
        name = "Camera",
        path = "/DCIM/Camera",
        isDirectory = true,
        size = 128L,
        protocol = FileProtocol.Device
    )

internal fun remoteFilesPreviewLocalFolder(): FileSimpleInfo =
    onboardingPreviewFile(
        name = "Downloads",
        path = "/Downloads",
        isDirectory = true,
        size = 12L,
        protocol = FileProtocol.Local
    )

internal fun remoteFilesPreviewSocketDevice(): SocketDevice =
    deviceSharePreviewSocketDevice().withCopy(connectType = ConnectType.Connect)

internal fun remoteFilesPreviewDeviceMenuPermission(): DiskMenuPermission {
    val devicePermission = remoteFilesPreviewSocketDevice().toDevice(includeHost = false).menuPermission

    return DiskMenuPermission(
        paste = false,
        copy = devicePermission.copy,
        move = devicePermission.move,
        delete = devicePermission.delete,
        rename = devicePermission.rename,
        setting = devicePermission.setting,
        favorite = devicePermission.favorite,
        share = devicePermission.share,
        info = devicePermission.info
    )
}

internal fun shouldUseRealRemoteFilesPreviewDeviceDrawerListItem(): Boolean = true

internal fun shouldUseRemoteFilesPreviewDeviceAccessBlock(): Boolean = false

internal fun shouldUseRealRemoteFilesPreviewFileCard(): Boolean = true

internal fun shouldUseRealRemoteFilesPreviewFileCardMenu(): Boolean = true

internal fun shouldUseRemoteFilesPreviewCopyOnlyMenu(): Boolean = false

internal fun shouldUseActualRemoteFilesPreviewHomeBottomBar(): Boolean = true

internal fun shouldUseRealRemoteFilesPreviewHomeBottomBarContent(): Boolean = false

internal fun shouldSeedRemoteFilesPreviewHomeBottomBarState(): Boolean = true

internal fun shouldUseRealRemoteFilesPreviewPathSwitch(): Boolean = false

internal fun shouldUseRemoteFilesPreviewInlineFileMenuItems(): Boolean = true

internal fun shouldUseRemoteFilesPreviewBlockingPopupMenu(): Boolean = false

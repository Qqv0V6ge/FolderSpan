package com.folderspan.ui.screen.onboarding

import strings.AppStrings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkEntry
import com.folderspan.data.main.network.NetworkProtocol

@Composable
internal fun OnboardingTargetWidget(
    widgetType: OnboardingWidgetType,
    selectedStepIndex: Int,
    modifier: Modifier = Modifier,
) {
    when (widgetType) {
        OnboardingWidgetType.LinkShare -> LinkSharePageWidgetPreview(selectedStepIndex, modifier)
        OnboardingWidgetType.DeviceShare -> DeviceSharePageWidgetPreview(selectedStepIndex, modifier)
        OnboardingWidgetType.RemoteFiles -> RemoteFilesPageWidgetPreview(selectedStepIndex, modifier)
        OnboardingWidgetType.CloudDrive -> CloudDrivePageWidgetPreview(selectedStepIndex, modifier)
    }
}

internal fun onboardingPreviewFile(
    name: String,
    path: String,
    isDirectory: Boolean = false,
    mineType: String = "",
    size: Long = 0L,
    createdDate: Long = 0L,
    updatedDate: Long = createdDate,
    protocol: FileProtocol = FileProtocol.Local,
): FileSimpleInfo =
    FileSimpleInfo(
        name = name,
        description = "",
        isDirectory = isDirectory,
        isHidden = false,
        path = path,
        mineType = mineType,
        size = size,
        createdDate = createdDate,
        updatedDate = updatedDate,
        protocol = protocol,
        protocolId = ""
    )

internal fun onboardingPreviewNetworkEntry(): NetworkEntry =
    NetworkEntry(
        id = 1,
        network = Network(
            name = AppStrings.ui_my_network_disk,
            pathSeparator = "/",
            protocol = NetworkProtocol.WebDav.name,
            host = "drive.example.com",
            username = "webb",
            password = "",
            pinned = true
        ),
        isPersisted = true
    )

package com.folderspan.ui.screen.file.share

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.data.SocketDevice
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.FileShareStatus

internal data class DeviceShareBatchResult(
    val requested: Int,
    val updated: Int,
    val skipped: Int,
)

internal fun canSelectDeviceShare(status: FileShareStatus?): Boolean =
    status != FileShareStatus.WAITING

internal fun submitDeviceShare(
    device: SocketDevice,
    files: List<FileSimpleInfo>,
    allowHidden: Boolean,
    shareState: FileShareState,
    onShare: (SocketDevice) -> Unit,
): Boolean {
    shareState.shareToDevices[device.id] = allowHidden to files.toList()
    val status = shareState.sendFile[device.id]
    if (status == FileShareStatus.COMPLETED || status == FileShareStatus.WAITING) return false
    onShare(device)
    return true
}

internal fun submitDeviceShareBatch(
    selectedDeviceIds: Set<String>,
    devices: List<SocketDevice>,
    files: List<FileSimpleInfo>,
    allowHidden: Boolean,
    shareState: FileShareState,
    onShare: (SocketDevice) -> Unit,
): DeviceShareBatchResult {
    if (files.isEmpty()) return DeviceShareBatchResult(0, 0, 0)

    val fileSnapshot = files.toList()
    val targets = devices.distinctBy { it.id }.filter { it.id in selectedDeviceIds }
    var requested = 0
    var updated = 0
    var skipped = selectedDeviceIds.size - targets.size

    targets.forEach { device ->
        val status = shareState.sendFile[device.id]
        if (!canSelectDeviceShare(status)) {
            skipped++
            return@forEach
        }
        if (submitDeviceShare(device, fileSnapshot, allowHidden, shareState, onShare)) {
            requested++
        } else {
            updated++
        }
    }
    return DeviceShareBatchResult(requested, updated, skipped)
}

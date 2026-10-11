package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.share.Share
import com.folderspan.notification.DeviceShareRequestAction
import com.folderspan.service.data.SocketDevice
import kotlinx.coroutines.CancellationException

internal class DeviceShareTransferCoordinator(
    private val connectShare: suspend (SocketDevice) -> Share,
    private val loadSharedEntries: suspend (Share) -> List<FileSimpleInfo>,
    private val copyEntriesToLocal: suspend (Share, List<FileSimpleInfo>, String) -> Unit,
    private val openShareDesk: (Share, List<FileSimpleInfo>) -> Unit,
    private val disconnectShare: (Share) -> Unit,
) {
    suspend fun execute(
        device: SocketDevice,
        action: DeviceShareRequestAction,
        savePath: String,
    ) {
        require(action != DeviceShareRequestAction.Reject && action != DeviceShareRequestAction.AutoReject)
        val connectedShare = connectShare(device)
        if (action == DeviceShareRequestAction.View) {
            openShareDesk(connectedShare, loadSharedEntries(connectedShare))
            return
        }

        try {
            copyEntriesToLocal(connectedShare, loadSharedEntries(connectedShare), savePath)
        } finally {
            disconnectShare(connectedShare)
        }
    }
}

internal suspend fun runDeviceShareConnectionAttempt(
    block: suspend () -> Unit,
    onFailure: (Exception) -> Unit,
    onCancelled: () -> Unit,
) {
    try {
        block()
    } catch (error: CancellationException) {
        onCancelled()
        throw error
    } catch (error: Exception) {
        onFailure(error)
    }
}

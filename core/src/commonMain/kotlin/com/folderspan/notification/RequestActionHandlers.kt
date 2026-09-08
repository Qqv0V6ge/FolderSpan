package com.folderspan.notification

import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.service.data.SocketDevice
import com.folderspan.ui.state.file.FileShareStatus
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.utils.PathUtils
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.utils.executeAsOneOrNullAwait
import com.folderspan.utils.SyncSnapshotChangeNotifier
import strings.AppStrings
import kotlin.time.Clock

sealed class DeviceConnectActionResult {
    object Completed : DeviceConnectActionResult()
    data class RequiresRoleSelection(val pendingType: DeviceConnectType) : DeviceConnectActionResult()
}

suspend fun handleDeviceConnectRequest(
    socketDevice: SocketDevice,
    connectionType: DeviceConnectType,
    database: FolderSpanDatabase,
    deviceState: DeviceState,
    roleId: Long? = null
): DeviceConnectActionResult {
    val deviceConnect = database.deviceConnectQueries.queryByIdAndCategory(
        socketDevice.id,
        DeviceCategory.SERVER
    ).executeAsOneOrNullAwait()
    val requestedAt = deviceState.connectionRequest[socketDevice.id]?.second
        ?: Clock.System.now().toEpochMilliseconds()

    if (deviceConnect == null || deviceConnect.roleId == -1L) {
        if (connectionType == DeviceConnectType.PERMANENTLY_BANNED) {
            database.deviceConnectQueries.upsert(
                id = socketDevice.id,
                connectionType = connectionType,
                category = DeviceCategory.SERVER,
                roleId = -1L
            ).awaitDatabaseReady()
            SyncSnapshotChangeNotifier.onDeviceConfigurationChanged()
            deviceState.updateConnectionRequest(
                deviceId = socketDevice.id,
                connectionType = connectionType,
                requestedAt = requestedAt,
                deviceName = socketDevice.name
            )
            return DeviceConnectActionResult.Completed
        }
        if (roleId == null) {
            return DeviceConnectActionResult.RequiresRoleSelection(connectionType)
        }
        database.deviceConnectQueries.upsert(
            id = socketDevice.id,
            connectionType = connectionType,
            category = DeviceCategory.SERVER,
            roleId = roleId
        ).awaitDatabaseReady()
        SyncSnapshotChangeNotifier.onDeviceConfigurationChanged()
        deviceState.updateConnectionRequest(
            deviceId = socketDevice.id,
            connectionType = connectionType,
            requestedAt = requestedAt,
            deviceName = socketDevice.name
        )
        return DeviceConnectActionResult.Completed
    }

    deviceState.updateConnectionRequest(
        deviceId = socketDevice.id,
        connectionType = connectionType,
        requestedAt = requestedAt,
        deviceName = socketDevice.name
    )
    if (connectionType == DeviceConnectType.AUTO_CONNECT || connectionType == DeviceConnectType.PERMANENTLY_BANNED) {
        database.deviceConnectQueries.updateNameConnectTypeRoleIdByIdAndCategory(
            connectionType,
            deviceConnect.roleId,
            socketDevice.id,
            DeviceCategory.SERVER
        ).awaitDatabaseReady()
        SyncSnapshotChangeNotifier.onDeviceConfigurationChanged()
    }

    return DeviceConnectActionResult.Completed
}

suspend fun handleDeviceShareRequest(
    socketDevice: SocketDevice,
    action: DeviceShareRequestAction,
    database: FolderSpanDatabase,
    deviceState: DeviceState,
    savePath: String? = null
) {
    when (action) {
        DeviceShareRequestAction.Save -> {
            val resolvedPath = resolveShareSavePath(database, socketDevice.id, savePath)
            upsertDeviceReceiveShare(
                database = database,
                deviceId = socketDevice.id,
                connectionType = DeviceConnectType.WAITING,
                path = resolvedPath
            )
            deviceState.connectShare(socketDevice, action, resolvedPath)
            deviceState.publishShareConnectionResult(socketDevice.id, FileShareStatus.COMPLETED)
        }

        DeviceShareRequestAction.View -> {
            deviceState.connectShare(socketDevice, action)
            deviceState.publishShareConnectionResult(socketDevice.id, FileShareStatus.COMPLETED)
        }

        DeviceShareRequestAction.AutoSave -> {
            val resolvedPath = resolveShareSavePath(database, socketDevice.id, savePath)
            upsertDeviceReceiveShare(
                database = database,
                deviceId = socketDevice.id,
                connectionType = DeviceConnectType.AUTO_CONNECT,
                path = resolvedPath
            )
            deviceState.connectShare(socketDevice, action, resolvedPath)
            deviceState.publishShareConnectionResult(socketDevice.id, FileShareStatus.COMPLETED)
        }

        DeviceShareRequestAction.Reject -> {
            deviceState.publishShareConnectionResult(
                deviceId = socketDevice.id,
                status = FileShareStatus.REJECTED,
                message = AppStrings.ui_other_party_refused_receive
            )
        }

        DeviceShareRequestAction.AutoReject -> {
            upsertDeviceReceiveShare(
                database = database,
                deviceId = socketDevice.id,
                connectionType = DeviceConnectType.PERMANENTLY_BANNED,
                path = ""
            )
            deviceState.publishShareConnectionResult(
                deviceId = socketDevice.id,
                status = FileShareStatus.REJECTED,
                message = AppStrings.ui_other_party_refused_receive
            )
        }
    }
    deviceState.removeShareRequest(socketDevice.id)
}

private suspend fun resolveShareSavePath(
    database: FolderSpanDatabase,
    deviceId: String,
    preferredPath: String?
): String {
    val trimmedPreferred = preferredPath?.trim().orEmpty()
    if (trimmedPreferred.isNotEmpty()) {
        return trimmedPreferred
    }
    val cachedPath = database.deviceReceiveShareQueries.selectById(deviceId)
        .executeAsOneOrNullAwait()
        ?.path
        .orEmpty()
    return cachedPath.ifBlank { PathUtils.getHomePath() }
}

private suspend fun upsertDeviceReceiveShare(
    database: FolderSpanDatabase,
    deviceId: String,
    connectionType: DeviceConnectType,
    path: String
) {
    val existing = database.deviceReceiveShareQueries.selectById(deviceId).executeAsOneOrNullAwait()
    if (existing == null) {
        database.deviceReceiveShareQueries.insert(deviceId, connectionType, path).awaitDatabaseReady()
        SyncSnapshotChangeNotifier.onDeviceConfigurationChanged()
        return
    }
    database.deviceReceiveShareQueries.updateConnectionTypeAndPathById(
        connectionType = connectionType,
        path = path,
        id = deviceId
    ).awaitDatabaseReady()
    SyncSnapshotChangeNotifier.onDeviceConfigurationChanged()
}

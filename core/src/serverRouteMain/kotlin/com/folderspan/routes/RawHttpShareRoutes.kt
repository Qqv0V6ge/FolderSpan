package com.folderspan.routes

import com.folderspan.data.main.device.DeviceConnectType.AUTO_CONNECT
import com.folderspan.data.main.device.DeviceConnectType.PERMANENTLY_BANNED
import com.folderspan.data.main.device.DeviceConnectType.WAITING
import com.folderspan.exception.EmptyDataException
import com.folderspan.notification.DeviceShareRequestAction
import com.folderspan.service.data.ShareRequestPollRequest
import com.folderspan.service.data.ShareRequestPollResponse
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.client.httpsPortOrFallback
import com.folderspan.service.http.server.getLocalIpv4Set
import com.folderspan.ui.state.file.FileShareStatus
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.executeAsOneOrNullAwait
import strings.AppStrings
import kotlin.time.Clock

/**
 * 设备分享的 HTTP 接口只负责请求审批轮询；文件访问统一由只读 Session 承载。
 */
internal suspend fun RawHttpApiDispatcher.handleShare(request: RawHttpRequest): RawHttpResponse {
    requireProtobuf(request)?.let { return it }
    return when (request.path) {
        "/api/share/heartbeat" -> handleShareHeartbeat(request)
        else -> protobufFailure(404, EmptyDataException())
    }
}

internal suspend fun RawHttpApiDispatcher.handleShareHeartbeat(request: RawHttpRequest): RawHttpResponse {
    val body = request.decodeProtobuf<ShareRequestPollRequest>()
    val socketDevice = resolveShareCallbackDevice(
        advertisedDevice = body.device,
        remoteHost = request.remoteHost,
        localHosts = getLocalIpv4Set(),
    ).withCopy(shareConnectNonce = body.connectNonce)
    deviceState.rememberShareRequestDevice(socketDevice, body.connectNonce)
    if (body.checkOnly) {
        val connectionResult = deviceState.consumeShareConnectionResult(socketDevice.id)
        if (connectionResult != null) {
            return protobuf(ShareRequestPollResponse(connectionResult.status, connectionResult.message))
        }
        val isConnected = deviceState.hasActiveShareConnection(socketDevice.id)
        return protobuf(
            ShareRequestPollResponse(
                status = if (isConnected) FileShareStatus.COMPLETED else FileShareStatus.ERROR,
                message = if (isConnected) AppStrings.ui_connected else AppStrings.ui_not_connected,
            )
        )
    }
    val deviceReceiveShare = database.deviceReceiveShareQueries.selectById(socketDevice.id)
        .executeAsOneOrNullAwait()
    val allowUnknownShare = settings.getBoolean(SettingsUtils.KEY_EASY_FILE_SHARE_ALLOW_DEVICE_SHARE, true)
    if (!allowUnknownShare && deviceReceiveShare == null) {
        deviceState.removeShareRequest(socketDevice.id)
        return protobuf(
            ShareRequestPollResponse(
                FileShareStatus.REJECTED,
                AppStrings.ui_the_device_has_been_disconnected_from_the_unknown_device,
            )
        )
    }
    when (deviceReceiveShare?.connectionType ?: WAITING) {
        AUTO_CONNECT -> {
            deviceState.connectShare(socketDevice, DeviceShareRequestAction.AutoSave)
            return protobuf(ShareRequestPollResponse(FileShareStatus.COMPLETED, ""))
        }

        PERMANENTLY_BANNED -> {
            return protobuf(
                ShareRequestPollResponse(
                    FileShareStatus.REJECTED,
                    AppStrings.ui_other_party_refused_receive,
                )
            )
        }

        else -> Unit
    }
    val connectionResult = deviceState.consumeShareConnectionResult(socketDevice.id)
    if (connectionResult != null) {
        return protobuf(ShareRequestPollResponse(connectionResult.status, connectionResult.message))
    }
    if (deviceState.shares.any { item -> item.id == socketDevice.id }) {
        return protobuf(ShareRequestPollResponse(FileShareStatus.COMPLETED, AppStrings.ui_connected))
    }
    if (!deviceState.shareRequest.containsKey(socketDevice.id)) {
        deviceState.updateShareRequest(
            deviceId = socketDevice.id,
            connectionType = WAITING,
            requestedAt = Clock.System.now().toEpochMilliseconds(),
            deviceName = socketDevice.name,
        )
    }
    return protobuf(
        ShareRequestPollResponse(
            FileShareStatus.WAITING,
            AppStrings.ui_wait_for_the_other_partys_agreement,
        )
    )
}

internal fun resolveShareCallbackDevice(
    advertisedDevice: SocketDevice,
    remoteHost: String?,
    localHosts: Set<String>,
): SocketDevice {
    return advertisedDevice.withCopy(
        host = resolveShareCallbackHost(
            advertisedHost = advertisedDevice.host,
            remoteHost = remoteHost,
            localHosts = localHosts,
        ),
        httpsPort = advertisedDevice.httpsPortOrFallback(),
    )
}

internal fun resolveShareCallbackHost(
    advertisedHost: String,
    remoteHost: String?,
    localHosts: Set<String>,
): String {
    val advertised = advertisedHost.trim()
    val remote = remoteHost?.trim().orEmpty()
    val normalizedLocalHosts = localHosts
        .mapTo(mutableSetOf()) { host -> host.normalizedShareHost() }
    val advertisedPointsToReceiver = advertised.isLocalShareHost(normalizedLocalHosts)
    val remotePointsToReceiver = remote.isLocalShareHost(normalizedLocalHosts)
    return if (advertisedPointsToReceiver && remote.isNotBlank() && !remotePointsToReceiver) {
        remote
    } else {
        advertised
    }
}

private fun String.isLocalShareHost(normalizedLocalHosts: Set<String>): Boolean {
    val normalized = normalizedShareHost()
    return normalized in normalizedLocalHosts ||
        normalized == "localhost" ||
        normalized.endsWith(".localhost") ||
        normalized == "0.0.0.0" ||
        normalized == "::" ||
        normalized == "::1" ||
        normalized == "0:0:0:0:0:0:0:0" ||
        normalized == "0:0:0:0:0:0:0:1" ||
        normalized.startsWith("127.")
}

private fun String.normalizedShareHost(): String {
    return trim()
        .removePrefix("[")
        .removeSuffix("]")
        .lowercase()
}

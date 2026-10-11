package com.folderspan.notification

import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.handleProNotificationClick
import com.folderspan.proFeedbackScreen
import com.folderspan.openUrl
import com.folderspan.ui.screen.main.NotificationScreen
import com.folderspan.ui.screen.settings.PermissionSettingsScreen
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.state.main.NotificationState
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import strings.AppStrings

object NotificationDeepLinkHandler : KoinComponent {
    @Volatile
    private var registered = false
    private val handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database by inject<FolderSpanDatabase>()

    fun register(
        mainState: MainState,
        notificationState: NotificationState,
        deviceState: DeviceState,
        fileShareState: FileShareState
    ) {
        if (registered) return
        registered = true
        LocalNotifier.addClickListener { data: NotificationPayload ->
            if (handleProNotificationClick(data, mainState)) return@addClickListener
            val actionId = data[NotificationActionKeys.ACTION_ID]?.trim()
                ?: NotificationActionKeys.ACTION_DEFAULT
            val requestId = data[RequestNotificationFactory.META_REQUEST_ID]?.trim().orEmpty()
            if (RequestNotificationFactory.consumeAppUpdateNotificationAction(data, actionId, ::openUrl)) {
                notificationState.markAsRead(
                    RequestNotificationFactory.notificationIdFor(
                        requestId.ifEmpty { RequestNotificationFactory.APP_UPDATE_REQUEST_ID },
                    ),
                )
                return@addClickListener
            }
            if (RequestNotificationFactory.isErrorLogMetadata(data) &&
                (
                    actionId == NotificationActionKeys.ACTION_OPEN ||
                        actionId == NotificationActionKeys.ACTION_DEFAULT
                    )
            ) {
                openErrorLogFeedback(data, requestId, notificationState, mainState)
                return@addClickListener
            }
            if (requestId.isEmpty()) return@addClickListener

            when (actionId) {
                NotificationActionKeys.ACTION_SAVE -> {
                    handleShareAction(
                        action = DeviceShareRequestAction.Save,
                        data = data,
                        requestId = requestId,
                        notificationState = notificationState,
                        deviceState = deviceState,
                        mainState = mainState
                    )
                }

                NotificationActionKeys.ACTION_VIEW -> {
                    handleShareAction(
                        action = DeviceShareRequestAction.View,
                        data = data,
                        requestId = requestId,
                        notificationState = notificationState,
                        deviceState = deviceState,
                        mainState = mainState
                    )
                }

                NotificationActionKeys.ACTION_APPROVE -> {
                    handleApprove(
                        data = data,
                        requestId = requestId,
                        notificationState = notificationState,
                        deviceState = deviceState,
                        fileShareState = fileShareState,
                        mainState = mainState
                    )
                }

                NotificationActionKeys.ACTION_REJECT -> {
                    handleReject(
                        data = data,
                        requestId = requestId,
                        notificationState = notificationState,
                        deviceState = deviceState,
                        fileShareState = fileShareState
                    )
                }

                NotificationActionKeys.ACTION_OPEN -> {
                    openNotificationFromPayload(data, requestId, notificationState, mainState)
                }

                NotificationActionKeys.ACTION_DELETE -> {
                    handleDeleteFromPayload(data, requestId, notificationState)
                }

                else -> {
                    openNotificationFromPayload(data, requestId, notificationState, mainState)
                }
            }
        }
    }

    private fun handleApprove(
        data: NotificationPayload,
        requestId: String,
        notificationState: NotificationState,
        deviceState: DeviceState,
        fileShareState: FileShareState,
        mainState: MainState
    ) {
        val requestInfo = parseRequestInfo(data) ?: run {
            openNotification(requestId, notificationState, mainState)
            return
        }
        notificationState.markAsRead(RequestNotificationFactory.notificationIdFor(requestId))
        when (requestInfo.kind) {
            RequestNotificationKind.DeviceConnect -> {
                val socketDevice = deviceState.socketDevices.firstOrNull { item ->  item.id == requestInfo.deviceId }
                if (socketDevice == null) {
                    openNotification(requestId, notificationState, mainState)
                    return
                }
                handlerScope.launch {
                    when (
                        handleDeviceConnectRequest(
                            socketDevice = socketDevice,
                            connectionType = DeviceConnectType.APPROVED,
                            database = database,
                            deviceState = deviceState
                        )
                    ) {
                        is DeviceConnectActionResult.RequiresRoleSelection -> {
                            openNotification(requestId, notificationState, mainState)
                        }

                        DeviceConnectActionResult.Completed -> Unit
                    }
                }
            }

            RequestNotificationKind.DeviceShare -> {
                handleShareAction(
                    action = DeviceShareRequestAction.View,
                    data = data,
                    requestId = requestId,
                    notificationState = notificationState,
                    deviceState = deviceState,
                    mainState = mainState
                )
            }

            RequestNotificationKind.LinkShare -> {
                fileShareState.approveLinkShareDevice(requestInfo.deviceId)
            }

            RequestNotificationKind.LinkShareUpload -> {
                fileShareState.approveLinkShareUploadDevice(requestInfo.deviceId)
            }
        }
    }

    private fun handleReject(
        data: NotificationPayload,
        requestId: String,
        notificationState: NotificationState,
        deviceState: DeviceState,
        fileShareState: FileShareState
    ) {
        val requestInfo = parseRequestInfo(data) ?: return
        notificationState.markAsRead(RequestNotificationFactory.notificationIdFor(requestId))
        when (requestInfo.kind) {
            RequestNotificationKind.DeviceConnect -> {
                val requestedAt = deviceState.connectionRequest[requestInfo.deviceId]?.second
                    ?: Clock.System.now().toEpochMilliseconds()
                deviceState.updateConnectionRequest(
                    deviceId = requestInfo.deviceId,
                    connectionType = DeviceConnectType.REJECTED,
                    requestedAt = requestedAt,
                    deviceName = requestInfo.deviceName
                )
            }

            RequestNotificationKind.DeviceShare -> {
                val socketDevice = deviceState.resolveShareRequestDevice(requestInfo.deviceId)
                if (socketDevice == null) {
                    LogKit.w(AppStrings.ui_not_found_to_share_device_arg0.format(arg0 = requestInfo.deviceId))
                    return
                }
                handlerScope.launch {
                    handleDeviceShareRequest(
                        socketDevice = socketDevice,
                        action = DeviceShareRequestAction.Reject,
                        database = database,
                        deviceState = deviceState
                    )
                }
            }

            RequestNotificationKind.LinkShare -> {
                fileShareState.rejectLinkShareDevice(requestInfo.deviceId)
            }

            RequestNotificationKind.LinkShareUpload -> {
                fileShareState.rejectLinkShareUploadDevice(requestInfo.deviceId)
            }
        }
    }

    private fun openErrorLogFeedback(
        data: NotificationPayload,
        requestId: String,
        notificationState: NotificationState,
        mainState: MainState,
    ) {
        val resolvedRequestId = requestId.ifEmpty { RequestNotificationFactory.ERROR_LOG_REQUEST_ID }
        notificationState.markAsRead(RequestNotificationFactory.notificationIdFor(resolvedRequestId))
        val screen = proFeedbackScreen(RequestNotificationFactory.errorLogSummary(data))
        if (screen != null) mainState.requestOpenScreen(screen)
        else openNotification(resolvedRequestId, notificationState, mainState)
    }

    private fun openNotification(
        requestId: String,
        notificationState: NotificationState,
        mainState: MainState
    ) {
        notificationState.requestOpenRequestId(requestId)
        mainState.requestOpenScreen(NotificationScreen())
    }

    private fun openNotificationFromPayload(
        data: NotificationPayload,
        requestId: String,
        notificationState: NotificationState,
        mainState: MainState
    ) {
        if (RequestNotificationFactory.isPermissionReminderMetadata(data)) {
            StartupPermissionReminderCoordinator.markReminderHandled()
            notificationState.markAsRead(RequestNotificationFactory.notificationIdFor(requestId))
            mainState.requestOpenScreen(PermissionSettingsScreen())
            return
        }
        openNotification(requestId, notificationState, mainState)
    }

    private fun handleDeleteFromPayload(
        data: NotificationPayload,
        requestId: String,
        notificationState: NotificationState
    ) {
        if (RequestNotificationFactory.isPermissionReminderMetadata(data)) {
            StartupPermissionReminderCoordinator.markReminderHandled()
        }
        RequestNotificationDispatcher.remove(notificationState, requestId)
    }

    private fun parseRequestInfo(data: NotificationPayload): RequestInfo? {
        val requestId = data[RequestNotificationFactory.META_REQUEST_ID]?.trim().orEmpty()
        val kindName = data[RequestNotificationFactory.META_REQUEST_KIND]?.trim().orEmpty()
        val deviceId = data[RequestNotificationFactory.META_DEVICE_ID]?.trim().orEmpty()
        val deviceName = data[RequestNotificationFactory.META_DEVICE_NAME]?.trim().orEmpty()
        if (requestId.isEmpty() || kindName.isEmpty() || deviceId.isEmpty()) return null
        val kind = runCatching { RequestNotificationKind.valueOf(kindName) }.getOrNull() ?: return null
        return RequestInfo(requestId, kind, deviceId, deviceName.ifEmpty { deviceId })
    }

    private fun handleShareAction(
        action: DeviceShareRequestAction,
        data: NotificationPayload,
        requestId: String,
        notificationState: NotificationState,
        deviceState: DeviceState,
        mainState: MainState
    ) {
        val requestInfo = parseRequestInfo(data) ?: run {
            openNotification(requestId, notificationState, mainState)
            return
        }
        if (requestInfo.kind != RequestNotificationKind.DeviceShare) {
            openNotification(requestId, notificationState, mainState)
            return
        }
        val socketDevice = deviceState.resolveShareRequestDevice(requestInfo.deviceId)
        if (socketDevice == null) {
            openNotification(requestId, notificationState, mainState)
            return
        }
        notificationState.markAsRead(RequestNotificationFactory.notificationIdFor(requestId))
        handlerScope.launch {
            handleDeviceShareRequest(
                socketDevice = socketDevice,
                action = action,
                database = database,
                deviceState = deviceState
            )
        }
    }

    private data class RequestInfo(
        val requestId: String,
        val kind: RequestNotificationKind,
        val deviceId: String,
        val deviceName: String
    )
}

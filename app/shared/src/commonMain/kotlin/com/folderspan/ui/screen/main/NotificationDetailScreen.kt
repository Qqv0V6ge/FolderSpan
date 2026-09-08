package com.folderspan.ui.screen.main

import strings.AppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Markunread
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.extensions.timestampToSyncDate
import com.folderspan.notification.*
import com.folderspan.service.data.SocketDevice
import com.folderspan.ui.components.dialog.DeviceRoleSelectionDialog
import com.folderspan.ui.components.dialog.DeviceShareSavePathDialog
import com.folderspan.ui.components.model.buildDeviceRoleOptionsUiState
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.notification.RequestNotificationInfo
import com.folderspan.ui.notification.toRequestInfo
import com.folderspan.ui.state.device.DeviceRoleState
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.Notification
import com.folderspan.ui.state.main.NotificationState
import com.folderspan.ui.state.main.NotificationType
import com.folderspan.utils.PathUtils
import com.folderspan.utils.executeAsOneOrNullAwait
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceNotificationDetailContent(notification: Notification) {
        val navigator = LocalAppNavigator.currentOrThrow
        val notificationState = koinInject<NotificationState>()
        val deviceState = koinInject<DeviceState>()
        val fileShareState = koinInject<FileShareState>()
        val database = koinInject<FolderSpanDatabase>()
        val roleState = koinInject<DeviceRoleState>()
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        var pendingRoleRequest by remember { mutableStateOf<PendingRoleRequest?>(null) }
        var pendingShareAutoApprove by remember { mutableStateOf<PendingShareAutoApprove?>(null) }
        val requestInfo = notification.toRequestInfo()
        var shareSavePath by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(requestInfo?.deviceId, requestInfo?.kind) {
            if (requestInfo?.kind == RequestNotificationKind.DeviceShare) {
                val cachedPath = database.deviceReceiveShareQueries.selectById(requestInfo.deviceId)
                    .executeAsOneOrNullAwait()
                    ?.path
                    ?.takeIf { item ->  item.isNotBlank() }
                shareSavePath = cachedPath ?: PathUtils.getHomePath()
            } else {
                shareSavePath = null
            }
        }

        fun resolveSocketDevice(deviceId: String): SocketDevice? {
            return deviceState.socketDevices.firstOrNull { item ->  item.id == deviceId }
        }

        fun handleRequestAction(info: RequestNotificationInfo, action: RequestAction) {
            when (info.kind) {
                RequestNotificationKind.DeviceConnect -> {
                    if (action == RequestAction.Reject) {
                        val requestedAt = deviceState.connectionRequest[info.deviceId]?.second
                            ?: Clock.System.now().toEpochMilliseconds()
                        deviceState.updateConnectionRequest(
                            deviceId = info.deviceId,
                            connectionType = DeviceConnectType.REJECTED,
                            requestedAt = requestedAt,
                            deviceName = info.deviceName
                        )
                        navigator.pop()
                        return
                    }
                    val socketDevice = resolveSocketDevice(info.deviceId) ?: return
                    val connectType = action.toConnectType() ?: return
                    scope.launch {
                        when (
                            val result = handleDeviceConnectRequest(
                                socketDevice = socketDevice,
                                connectionType = connectType,
                                database = database,
                                deviceState = deviceState
                            )
                        ) {
                            is DeviceConnectActionResult.RequiresRoleSelection -> {
                                pendingRoleRequest = PendingRoleRequest(socketDevice, result.pendingType)
                            }

                            DeviceConnectActionResult.Completed -> navigator.pop()
                        }
                    }
                }

                RequestNotificationKind.DeviceShare -> {
                    val socketDevice = deviceState.resolveShareRequestDevice(info.deviceId) ?: return
                    if (action == RequestAction.AutoApprove) {
                        pendingShareAutoApprove = PendingShareAutoApprove(socketDevice)
                        return
                    }
                    val shareAction = action.toShareRequestAction() ?: return
                    scope.launch {
                        handleDeviceShareRequest(
                            socketDevice = socketDevice,
                            action = shareAction,
                            database = database,
                            deviceState = deviceState
                        )
                        navigator.pop()
                    }
                }

                RequestNotificationKind.LinkShare -> {
                    when (action) {
                        RequestAction.Approve -> {
                            fileShareState.approveLinkShareDevice(info.deviceId)
                            navigator.pop()
                        }

                        RequestAction.Reject -> {
                            fileShareState.rejectLinkShareDevice(info.deviceId)
                            navigator.pop()
                        }

                        else -> Unit
                    }
                }

                RequestNotificationKind.LinkShareUpload -> {
                    when (action) {
                        RequestAction.Approve -> {
                            fileShareState.approveLinkShareUploadDevice(info.deviceId)
                            navigator.pop()
                        }

                        RequestAction.Reject -> {
                            fileShareState.rejectLinkShareUploadDevice(info.deviceId)
                            navigator.pop()
                        }

                        else -> Unit
                    }
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_notification_details) },
                    navigationIcon = {
                        IconButton(onClick = { navigateBackToNotificationList(navigator) }) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        if (requestInfo == null) {
                            IconButton(onClick = {
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = AppStrings.ui_notification_delete_selected_arg0.format(arg0 = "1"),
                                        actionLabel = AppStrings.ui_delete,
                                        withDismissAction = true,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        notificationState.delete(notification.id)
                                        navigator.pop()
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = AppStrings.ui_delete)
                            }
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            NotificationDetailLayout(
                icon = notification.getIcon(),
                iconColor = notification.detailIconColor(),
                category = notification.displayCategory(),
                timestampText = notification.timestamp.timestampToSyncDate(),
                title = notification.displayTitle(),
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
                footer = {
                    if (requestInfo != null) {
                        RequestActionRow(
                            requestInfo = requestInfo,
                            onAction = { info, action -> handleRequestAction(info, action) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 24.dp),
                        )
                    } else if (
                        RequestNotificationFactory.isAppUpdateMetadata(notification.metadata) &&
                        RequestNotificationFactory.appUpdateHttpLink(notification.metadata) != null
                    ) {
                        AppUpdateActionRow(
                            metadata = notification.metadata,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 24.dp),
                        )
                    } else {
                        FilledTonalButton(
                            onClick = {
                                val markRead = !notification.isRead
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = if (markRead) {
                                            AppStrings.ui_notification_mark_selected_read_arg0.format(arg0 = "1")
                                        } else {
                                            AppStrings.ui_notification_mark_selected_unread_arg0.format(arg0 = "1")
                                        },
                                        actionLabel = if (markRead) {
                                            AppStrings.ui_mark_read
                                        } else {
                                            AppStrings.ui_mark_unread
                                        },
                                        withDismissAction = true,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        if (markRead) {
                                            notificationState.markAsRead(notification.id)
                                        } else {
                                            notificationState.markAsUnread(notification.id)
                                        }
                                        navigator.pop()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 24.dp),
                        ) {
                            Icon(
                                if (notification.isRead) Icons.Default.Markunread else Icons.Default.Done,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (notification.isRead) AppStrings.ui_mark_unread else AppStrings.ui_mark_read)
                        }
                    }
                },
            ) {
                Text(
                    text = buildNotificationMessage(notification, requestInfo, shareSavePath),
                    style = typography.bodyLarge,
                )
            }
        }

        if (pendingShareAutoApprove != null) {
            val targetDevice = pendingShareAutoApprove?.socketDevice
            DeviceShareSavePathDialog(
                onConfirm = { savePath ->
                    val device = targetDevice ?: return@DeviceShareSavePathDialog
                    scope.launch {
                        handleDeviceShareRequest(
                            socketDevice = device,
                            action = DeviceShareRequestAction.AutoSave,
                            database = database,
                            deviceState = deviceState,
                            savePath = savePath
                        )
                        pendingShareAutoApprove = null
                        navigator.pop()
                    }
                },
                onCancel = { pendingShareAutoApprove = null },
                onDismissRequest = {}
            )
        }

        if (pendingRoleRequest != null) {
            val request = pendingRoleRequest!!
            val roleOptions = buildDeviceRoleOptionsUiState(roleState.roles)
            var selectedRoleId by remember(request.socketDevice.id) { mutableStateOf<Long?>(null) }

            DeviceRoleSelectionDialog(
                prompt = AppStrings.dialog_choose_device_role.format(
                    deviceName = request.socketDevice.name,
                ),
                roleOptions = roleOptions,
                selectedRoleId = selectedRoleId,
                onRoleSelect = { roleId -> selectedRoleId = roleId },
                onConfirm = {
                    val roleId = selectedRoleId ?: return@DeviceRoleSelectionDialog
                    scope.launch {
                        handleDeviceConnectRequest(
                            socketDevice = request.socketDevice,
                            connectionType = request.connectionType,
                            database = database,
                            deviceState = deviceState,
                            roleId = roleId
                        )
                        pendingRoleRequest = null
                        navigator.pop()
                    }
                },
                onCancel = {
                    deviceState.updateConnectionRequest(
                        deviceId = request.socketDevice.id,
                        connectionType = DeviceConnectType.REJECTED,
                        requestedAt = deviceState.connectionRequest[request.socketDevice.id]?.second
                            ?: Clock.System.now().toEpochMilliseconds(),
                        deviceName = request.socketDevice.name
                    )
                    pendingRoleRequest = null
                    navigator.pop()
                },
                onDismissRequest = {}
            )
        }
}

@Composable
internal fun NotificationDetailLayout(
    icon: ImageVector,
    iconColor: Color,
    category: String,
    timestampText: String,
    title: String,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    message: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    color = iconColor.copy(alpha = 0.15f),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }

                Column {
                    if (category.isNotEmpty()) {
                        Text(
                            text = category,
                            style = typography.labelLarge,
                            color = colorScheme.primary,
                        )
                    }
                    Text(
                        text = timestampText,
                        style = typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = title,
                style = typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            Surface(
                color = colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Box(Modifier.padding(16.dp)) {
                        message()
                    }
                }
            }
        }

        footer?.invoke()
    }
}

@Composable
internal fun Notification.detailIconColor(): Color = when (type) {
    NotificationType.Info, NotificationType.Success -> colorScheme.primary
    NotificationType.Warning -> colorScheme.tertiary
    NotificationType.Error -> colorScheme.error
    NotificationType.Promotion -> colorScheme.secondary
}

private fun buildNotificationMessage(
    notification: Notification,
    requestInfo: RequestNotificationInfo?,
    shareSavePath: String?,
): String {
    val lines = mutableListOf(notification.displayMessage())
    if (requestInfo?.kind == RequestNotificationKind.DeviceShare) {
        val resolvedPath = shareSavePath?.takeIf { item ->  item.isNotBlank() } ?: PathUtils.getHomePath()
        lines.add(AppStrings.ui_save_use_save_path_arg0.format(arg0 = resolvedPath))
        lines.add(AppStrings.ui_view_only_open_sharing_no_automatic_saving)
    }
    val autoRejectHint = requestInfo?.let { item ->  buildAutoRejectHint(item, notification.timestamp) }
    if (!autoRejectHint.isNullOrBlank()) {
        lines.add(autoRejectHint)
    }
    return lines.joinToString("\n")
}

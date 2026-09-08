package com.folderspan.ui.screen.main

import strings.AppStrings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.PRO_AVAILABLE
import com.folderspan.proFeedbackScreen
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.extensions.timestampToAdaptiveDateTime
import com.folderspan.extensions.timestampToLocalDateTime
import com.folderspan.notification.*
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.CONNECT_TIMEOUT
import com.folderspan.ui.components.combinedClickableWithContextClick
import com.folderspan.ui.components.dialog.DeviceRoleSelectionDialog
import com.folderspan.ui.components.dialog.DeviceShareSavePathDialog
import com.folderspan.ui.components.model.buildDeviceRoleOptionsUiState
import com.folderspan.ui.notification.RequestNotificationInfo
import com.folderspan.ui.notification.toRequestInfo
import com.folderspan.ui.navigation.AppNavigator
import com.folderspan.ui.navigation.AppRoute
import com.folderspan.ui.navigation.AppRoutePayloadStore
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.navigation.pushSafe
import com.folderspan.ui.navigation.toAppRoute
import com.folderspan.ui.screen.settings.PermissionSettingsScreen
import com.folderspan.ui.state.device.DeviceRoleState
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.Notification
import com.folderspan.ui.state.main.NotificationState
import com.folderspan.ui.state.main.NotificationType
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal class LocalNotificationScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val notificationState = koinInject<NotificationState>()
        val deviceState = koinInject<DeviceState>()
        val fileShareState = koinInject<FileShareState>()
        val database = koinInject<FolderSpanDatabase>()
        val roleState = koinInject<DeviceRoleState>()
        val scope = rememberCoroutineScope()

        var selectedFilter by remember { mutableStateOf<Boolean?>(null) } // null=全部, false=未读, true=已读
        var selectionMode by remember { mutableStateOf(false) }
        var selectedNotifications by remember { mutableStateOf(setOf<Long>()) }
        var pendingRoleRequest by remember { mutableStateOf<PendingRoleRequest?>(null) }
        var pendingShareAutoApprove by remember { mutableStateOf<PendingShareAutoApprove?>(null) }
        val pendingOpenRequestId = notificationState.pendingOpenRequestId

        val revision = notificationState.revision
        val allNotifications = remember(revision) { notificationState.notifications.toList() }
        val notifications = remember(revision) { allNotifications }
        val hasSelectableNotifications = notifications.any { item ->  item.toRequestInfo() == null }

        LaunchedEffect(pendingOpenRequestId, revision) {
            val requestId = pendingOpenRequestId ?: return@LaunchedEffect
            val target = notificationState.findByRequestId(requestId) ?: return@LaunchedEffect
            if (!target.isRead) {
                notificationState.markAsRead(target.id)
            }
            notificationState.consumePendingOpenRequestId(requestId)
            openLocalNotification(navigator, target)
        }

        val filteredNotifications = when (selectedFilter) {
            null -> notifications // 全部
            false -> notifications.filter { item ->  !item.isRead } // 未读
            true -> notifications.filter { item ->  item.isRead } // 已读
        }
        val selectableNotifications = filteredNotifications.filter { item ->  item.toRequestInfo() == null }

        val unreadCount = notifications.count { item ->  !item.isRead }
        val readCount = notifications.count { item ->  item.isRead }

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

        // 当进入选择模式但过滤后没有通知时，退出选择模式
        LaunchedEffect(filteredNotifications.size, selectionMode) {
            if (selectionMode && filteredNotifications.isEmpty()) {
                selectionMode = false
                selectedNotifications = emptySet()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (selectionMode) {
                            Text(AppStrings.ui_arg0_items_selected.format(arg0 = (selectedNotifications.size).toString()))
                        } else {
                            Text(AppStrings.ui_notification)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectionMode) {
                                selectionMode = false
                                selectedNotifications = emptySet()
                            } else {
                                navigator.pop()
                            }
                        }) {
                            Icon(
                                if (selectionMode) Icons.Default.Close else Icons.AutoMirrored.Default.ArrowBack,
                                contentDescription = null
                            )
                        }
                    },
                    actions = {
                        if (!selectionMode && hasSelectableNotifications) {
                            // 全部标记为已读
                            if (unreadCount > 0) {
                                IconButton(onClick = { notificationState.markAllAsRead() }) {
                                    Icon(Icons.Default.DoneAll, contentDescription = AppStrings.ui_all_read)
                                }
                            }
                            // 批量选择
                            IconButton(onClick = { selectionMode = true }) {
                                Icon(Icons.Default.Checklist, contentDescription = AppStrings.ui_batch_operation)
                            }
                        }
                        if (selectionMode) {
                            // 全选/取消全选
                            TextButton(onClick = {
                                selectedNotifications =
                                    if (selectedNotifications.size == selectableNotifications.size) {
                                        emptySet()
                                    } else {
                                        selectableNotifications.map { item ->  item.id }.toSet()
                                    }
                            }) {
                                Text(
                                    if (selectedNotifications.size == selectableNotifications.size) {
                                        AppStrings.ui_deselect_all
                                    } else {
                                        AppStrings.ui_select_all
                                    },
                                )
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                if (selectionMode && selectedNotifications.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        // 标记为已读按钮
                        val hasUnread = selectedNotifications.any { id ->
                            notifications.find { item ->  item.id == id }?.isRead == false
                        }
                        if (hasUnread) {
                            FloatingActionButton(
                                onClick = {
                                    notificationState.markMultipleAsRead(selectedNotifications)
                                },
                                containerColor = colorScheme.primary
                            ) {
                                Icon(Icons.Default.Done, AppStrings.ui_mark_read)
                            }
                        }

                        // 标记为未读按钮
                        val hasRead = selectedNotifications.any { id ->
                            notifications.find { item ->  item.id == id }?.isRead == true
                        }
                        if (hasRead) {
                            FloatingActionButton(
                                onClick = {
                                    notificationState.markMultipleAsUnread(selectedNotifications)
                                },
                                containerColor = colorScheme.secondary
                            ) {
                                Icon(Icons.Default.Markunread, AppStrings.ui_mark_unread)
                            }
                        }

                        // 删除按钮
                        FloatingActionButton(
                            onClick = {
                                val hasPermissionReminder = selectedNotifications.any { id ->
                                    notifications.any { item ->
                                        item.id == id &&
                                            RequestNotificationFactory.isPermissionReminderMetadata(item.metadata)
                                    }
                                }
                                if (hasPermissionReminder) {
                                    StartupPermissionReminderCoordinator.markReminderHandled()
                                }
                                notificationState.deleteMultiple(selectedNotifications)
                                selectedNotifications = emptySet()
                                selectionMode = false
                            },
                            containerColor = colorScheme.error
                        ) {
                            Icon(Icons.Default.Delete, AppStrings.ui_delete)
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
            ) {
                // 分组过滤按钮
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                ) {
                    SegmentedButton(
                        selected = selectedFilter == null,
                        onClick = { selectedFilter = null },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                    ) {
                        Text(AppStrings.ui_all_arg0.format(arg0 = (notifications.size).toString()))
                    }
                    SegmentedButton(
                        selected = selectedFilter == false,
                        onClick = {
                            selectedFilter = if (selectedFilter == false) null else false
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                    ) {
                        Text(AppStrings.ui_unread_arg0.format(arg0 = (unreadCount).toString()))
                    }
                    SegmentedButton(
                        selected = selectedFilter == true,
                        onClick = {
                            selectedFilter = if (selectedFilter == true) null else true
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                    ) {
                        Text(AppStrings.ui_read_arg0.format(arg0 = (readCount).toString()))
                    }
                }

                // 通知列表
                if (filteredNotifications.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = colorScheme.outline
                            )
                            Text(
                                text = AppStrings.ui_no_notification_yet,
                                style = typography.bodyLarge,
                                color = colorScheme.outline
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredNotifications, key = { item ->  item.id }) { notification ->
                            val requestInfo = notification.toRequestInfo()
                            val isSelectable = requestInfo == null
                            NotificationItem(
                                notification = notification,
                                requestInfo = requestInfo,
                                selected = notification.id in selectedNotifications,
                                selectionMode = selectionMode,
                                onClick = {
                                    if (selectionMode) {
                                        if (isSelectable) {
                                            selectedNotifications = if (notification.id in selectedNotifications) {
                                                selectedNotifications - notification.id
                                            } else {
                                                selectedNotifications + notification.id
                                            }
                                        }
                                    } else {
                                        // 点击查看详情
                                        if (!notification.isRead) {
                                            notificationState.markAsRead(notification.id)
                                        }
                                        if (RequestNotificationFactory.isPermissionReminderMetadata(notification.metadata)) {
                                            StartupPermissionReminderCoordinator.markReminderHandled()
                                            navigator.pushSafe(PermissionSettingsScreen())
                                        } else {
                                            openLocalNotification(navigator, notification)
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode && isSelectable) {
                                        selectionMode = true
                                        selectedNotifications = setOf(notification.id)
                                    }
                                },
                                onMarkRead = {
                                    notificationState.markAsRead(notification.id)
                                },
                                onMarkUnread = {
                                    notificationState.markAsUnread(notification.id)
                                },
                                onDelete = {
                                    var handledByRequestId = false
                                    if (RequestNotificationFactory.isPermissionReminderMetadata(notification.metadata)) {
                                        StartupPermissionReminderCoordinator.markReminderHandled()
                                        val requestId = notification.metadata[RequestNotificationFactory.META_REQUEST_ID]
                                            ?.trim()
                                            .orEmpty()
                                        if (requestId.isNotEmpty()) {
                                            RequestNotificationDispatcher.remove(notificationState, requestId)
                                            handledByRequestId = true
                                        }
                                    }
                                    if (!handledByRequestId) {
                                        notificationState.delete(notification.id)
                                    }
                                },
                                onOpenPermissionReminder = {
                                    StartupPermissionReminderCoordinator.markReminderHandled()
                                    if (!notification.isRead) {
                                        notificationState.markAsRead(notification.id)
                                    }
                                    navigator.pushSafe(PermissionSettingsScreen())
                                },
                                onOpenAppUpdate = {
                                    if (!notification.isRead) {
                                        notificationState.markAsRead(notification.id)
                                    }
                                    RequestNotificationFactory.appUpdateHttpLink(notification.metadata)
                                        ?.let { url -> com.folderspan.openUrl(url) }
                                },
                                onRequestAction = { info, action ->
                                    handleRequestAction(info, action)
                                }
                            )
                        }
                    }
                }
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
}

internal fun openNotificationDetail(
    navigator: AppNavigator,
    notification: Notification,
) {
    navigator.pushSafe(NotificationDetailScreen(notification))
}

internal fun openLocalNotification(
    navigator: AppNavigator,
    notification: Notification,
) {
    val feedbackScreen = if (shouldOpenLocalNotificationDirectly(notification)) {
        proFeedbackScreen(RequestNotificationFactory.errorLogSummary(notification.metadata))
    } else null
    if (feedbackScreen != null) navigator.pushSafe(feedbackScreen)
    else openNotificationDetail(navigator, notification)
}

internal fun shouldOpenLocalNotificationDirectly(notification: Notification): Boolean =
    PRO_AVAILABLE && RequestNotificationFactory.isErrorLogMetadata(notification.metadata)

internal enum class NotificationTab { Local, Account, Announcement }

internal fun notificationBellUnreadCount(localUnread: Int, accountUnread: Int): Int =
    localUnread + accountUnread

internal fun navigateBackToNotificationList(
    navigator: AppNavigator,
    targetTab: NotificationTab = NotificationTab.Local,
) {
    if (!navigator.pop()) {
        navigator.replaceRoot(NotificationScreen(targetTab).toAppRoute())
        return
    }

    val currentScreen = (navigator.currentRoute as? AppRoute.Payload)
        ?.let(AppRoutePayloadStore::resolve)
    if (currentScreen is NotificationScreen) {
        currentScreen.selectedTab = targetTab
    } else {
        navigator.pushSafe(NotificationScreen(targetTab))
    }
}

internal data class PendingRoleRequest(
    val socketDevice: SocketDevice,
    val connectionType: DeviceConnectType
)

internal data class PendingShareAutoApprove(
    val socketDevice: SocketDevice
)

internal enum class RequestAction {
    Approve,
    Save,
    View,
    Reject,
    AutoApprove,
    AutoReject
}

internal fun RequestAction.toConnectType(): DeviceConnectType? {
    return when (this) {
        RequestAction.Approve -> DeviceConnectType.APPROVED
        RequestAction.Save -> null
        RequestAction.View -> null
        RequestAction.Reject -> DeviceConnectType.REJECTED
        RequestAction.AutoApprove -> DeviceConnectType.AUTO_CONNECT
        RequestAction.AutoReject -> DeviceConnectType.PERMANENTLY_BANNED
    }
}

internal fun RequestAction.toShareRequestAction(): DeviceShareRequestAction? {
    return when (this) {
        RequestAction.Save -> DeviceShareRequestAction.Save
        RequestAction.View,
        RequestAction.Approve -> DeviceShareRequestAction.View

        RequestAction.Reject -> DeviceShareRequestAction.Reject
        RequestAction.AutoApprove -> DeviceShareRequestAction.AutoSave
        RequestAction.AutoReject -> DeviceShareRequestAction.AutoReject
    }
}

@Composable
private fun NotificationItem(
    notification: Notification,
    requestInfo: RequestNotificationInfo?,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onDelete: () -> Unit,
    onOpenPermissionReminder: () -> Unit,
    onOpenAppUpdate: () -> Unit,
    onRequestAction: (RequestNotificationInfo, RequestAction) -> Unit
) {
    val containerColor = when {
        selected -> colorScheme.primaryContainer
        !notification.isRead -> colorScheme.surfaceContainerHighest
        else -> colorScheme.surface
    }

    val iconColor = when (notification.type) {
        NotificationType.Info -> colorScheme.primary
        NotificationType.Warning -> colorScheme.tertiary
        NotificationType.Error -> colorScheme.error
        NotificationType.Success -> colorScheme.primary
        NotificationType.Promotion -> colorScheme.secondary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickableWithContextClick(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        border = if (selected) {
            BorderStroke(2.dp, colorScheme.primary)
        } else null,
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (selected) 4.dp else 1.dp
        )
    ) {
        Column(
            Modifier
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 左侧：选择框或图标
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier.align(Alignment.Top)
                    )
                } else {
                    Surface(
                        modifier = Modifier
                            .size(48.dp)
                            .align(Alignment.Top),
                        shape = CircleShape,
                        color = iconColor.copy(alpha = 0.15f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = notification.getIcon(),
                                contentDescription = null,
                                tint = iconColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // 中间：内容
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 分类和时间
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (notification.displayCategory().isNotEmpty()) {
                            Text(
                                text = notification.displayCategory(),
                                style = typography.labelMedium,
                                color = colorScheme.primary
                            )
                        }
                        Text(
                            text = notification.timestamp.timestampToAdaptiveDateTime(),
                            style = typography.labelSmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }

                    // 标题
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = notification.displayTitle(),
                            style = typography.titleMedium,
                            fontWeight = if (!notification.isRead) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            modifier = Modifier.weight(1f)
                        )
                        // 未读标记
                        if (!notification.isRead && !selectionMode) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(colorScheme.primary)
                            )
                        }
                    }

                    // 消息内容
                    val autoRejectHint = requestInfo?.let { item ->  buildAutoRejectHint(item, notification.timestamp) }
                    Text(
                        text = notification.displayMessage(),
                        style = typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (autoRejectHint != null) {
                        Text(
                            text = autoRejectHint,
                            style = typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (!selectionMode && requestInfo != null) {
                RequestActionRow(
                    requestInfo = requestInfo,
                    onAction = onRequestAction,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 操作按钮（非选择模式时显示）
            if (!selectionMode && requestInfo == null) {
                val isPermissionReminder = RequestNotificationFactory.isPermissionReminderMetadata(notification.metadata)
                val canOpenAppUpdate = RequestNotificationFactory.isAppUpdateMetadata(notification.metadata) &&
                    RequestNotificationFactory.appUpdateHttpLink(notification.metadata) != null
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isPermissionReminder) {
                        FilledTonalButton(
                            onClick = onOpenPermissionReminder,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(AppStrings.ui_go)
                        }
                    } else if (canOpenAppUpdate) {
                        Button(
                            onClick = onOpenAppUpdate,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("notification-app-update-open-link"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(AppStrings.settings_about_software_open_link)
                        }
                    } else {
                        // 已读/未读按钮
                        FilledTonalButton(
                            onClick = if (notification.isRead) onMarkUnread else onMarkRead,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (notification.isRead) {
                                    colorScheme.secondaryContainer
                                } else {
                                    colorScheme.primaryContainer
                                },
                                contentColor = if (notification.isRead) {
                                    colorScheme.onSecondaryContainer
                                } else {
                                    colorScheme.onPrimaryContainer
                                }
                            )
                        ) {
                            Icon(
                                imageVector = if (notification.isRead) {
                                    Icons.Default.Markunread
                                } else {
                                    Icons.Default.Done
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (notification.isRead) AppStrings.ui_mark_unread else AppStrings.ui_mark_read)
                        }
                    }

                    // 删除按钮
                    FilledTonalButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = colorScheme.errorContainer,
                            contentColor = colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(AppStrings.ui_delete)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppUpdateActionRow(
    metadata: Map<String, String>,
    modifier: Modifier = Modifier,
    openUrl: (String) -> Unit = { url -> com.folderspan.openUrl(url) },
) {
    if (!RequestNotificationFactory.isAppUpdateMetadata(metadata)) return
    val link = RequestNotificationFactory.appUpdateHttpLink(metadata) ?: return
    Button(
        onClick = { openUrl(link) },
        modifier = modifier.testTag("notification-app-update-open-link"),
    ) {
        Text(AppStrings.settings_about_software_open_link)
    }
}

@Composable
internal fun RequestActionRow(
    requestInfo: RequestNotificationInfo,
    onAction: (RequestNotificationInfo, RequestAction) -> Unit,
    modifier: Modifier = Modifier
) {
    when (requestInfo.kind) {
        RequestNotificationKind.DeviceConnect -> {
            var expanded by remember { mutableStateOf(false) }
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onAction(requestInfo, RequestAction.Approve) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(AppStrings.ui_agree)
                }

                FilledTonalButton(
                    onClick = { onAction(requestInfo, RequestAction.Reject) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colorScheme.errorContainer,
                        contentColor = colorScheme.onErrorContainer
                    )
                ) {
                    Text(AppStrings.ui_reject)
                }

                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = AppStrings.ui_more_actions)
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(AppStrings.ui_automatic_consent) },
                            onClick = {
                                expanded = false
                                onAction(requestInfo, RequestAction.AutoApprove)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(AppStrings.ui_always_refuse) },
                            onClick = {
                                expanded = false
                                onAction(requestInfo, RequestAction.AutoReject)
                            }
                        )
                    }
                }
            }
        }

        RequestNotificationKind.DeviceShare -> {
            var expanded by remember { mutableStateOf(false) }
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onAction(requestInfo, RequestAction.Save) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(AppStrings.ui_save)
                }

                FilledTonalButton(
                    onClick = { onAction(requestInfo, RequestAction.View) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(AppStrings.ui_view)
                }

                FilledTonalButton(
                    onClick = { onAction(requestInfo, RequestAction.Reject) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colorScheme.errorContainer,
                        contentColor = colorScheme.onErrorContainer
                    )
                ) {
                    Text(AppStrings.ui_reject)
                }

                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = AppStrings.ui_more_actions)
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(AppStrings.ui_auto_save) },
                            onClick = {
                                expanded = false
                                onAction(requestInfo, RequestAction.AutoApprove)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(AppStrings.ui_always_refuse) },
                            onClick = {
                                expanded = false
                                onAction(requestInfo, RequestAction.AutoReject)
                            }
                        )
                    }
                }
            }
        }

        RequestNotificationKind.LinkShare,
        RequestNotificationKind.LinkShareUpload -> {
            val approveText = if (requestInfo.kind == RequestNotificationKind.LinkShareUpload) {
                AppStrings.ui_agree
            } else {
                AppStrings.ui_allow
            }
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onAction(requestInfo, RequestAction.Approve) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(approveText)
                }
                FilledTonalButton(
                    onClick = { onAction(requestInfo, RequestAction.Reject) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colorScheme.errorContainer,
                        contentColor = colorScheme.onErrorContainer
                    )
                ) {
                    Text(AppStrings.ui_reject)
                }
            }
        }
    }
}

internal fun buildAutoRejectHint(
    requestInfo: RequestNotificationInfo,
    requestTimestamp: Long
): String? {
    if (requestInfo.kind != RequestNotificationKind.DeviceConnect &&
        requestInfo.kind != RequestNotificationKind.DeviceShare
    ) {
        return null
    }
    val deadline = requestTimestamp + CONNECT_TIMEOUT * 1000L
    val localDateTime = deadline.timestampToLocalDateTime()
    val nowDateTime = Clock.System.now().toEpochMilliseconds().timestampToLocalDateTime()
    val isSameDay = localDateTime.year == nowDateTime.year &&
            localDateTime.month == nowDateTime.month &&
            localDateTime.day == nowDateTime.day
    val dateText = if (isSameDay) AppStrings.notification_today else AppStrings.ui_tomorrow
    val timeText = "${localDateTime.hour.toString().padStart(2, '0')}:" +
            localDateTime.minute.toString().padStart(2, '0')
    return AppStrings.ui_unoperated_items_will_automatically_rejected_after_arg0_arg1.format(arg0 = dateText, arg1 = timeText)
}

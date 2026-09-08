package com.folderspan.ui.screen.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Markunread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.extensions.timestampToAdaptiveDateTime
import com.folderspan.extensions.timestampToSyncDate
import com.folderspan.notification.DeviceConnectActionResult
import com.folderspan.notification.DeviceShareRequestAction
import com.folderspan.notification.RequestNotificationFactory
import com.folderspan.notification.RequestNotificationKind
import com.folderspan.notification.StartupPermissionReminderCoordinator
import com.folderspan.notification.handleDeviceConnectRequest
import com.folderspan.notification.handleDeviceShareRequest
import com.folderspan.notification.accountNotificationListPreview
import com.folderspan.notification.isAccountNotificationActionAvailable
import com.folderspan.notification.performAccountNotificationAction
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.di.AnnouncementRuntime
import com.folderspan.pro.di.UserNotificationRuntime
import com.folderspan.pro.domain.model.AccountNotification
import com.folderspan.pro.domain.model.Announcement as AnnouncementMessage
import com.folderspan.pro.domain.model.AnnouncementSnapshot
import com.folderspan.pro.domain.model.NotificationAction
import com.folderspan.pro.domain.model.NotificationActionStyle
import com.folderspan.pro.domain.model.UnifiedNotificationKey
import com.folderspan.pro.domain.model.UserNotificationSnapshot
import com.folderspan.pro.domain.model.UserNotificationStatus
import com.folderspan.pro.domain.model.isRead
import com.folderspan.service.data.SocketDevice
import com.folderspan.ui.components.combinedClickableWithContextClick
import com.folderspan.ui.components.dialog.DeviceRoleSelectionDialog
import com.folderspan.ui.components.dialog.DeviceShareSavePathDialog
import com.folderspan.ui.components.error.ErrorConnection
import com.folderspan.ui.components.model.buildDeviceRoleOptionsUiState
import com.folderspan.ui.components.notification.NotificationMarkdownContent
import com.folderspan.ui.components.pagestate.LoadMoreOnNearEnd
import com.folderspan.ui.components.pagestate.PageAppendState
import com.folderspan.ui.components.pagestate.PageErrorState
import com.folderspan.ui.components.pagestate.PageErrorType
import com.folderspan.ui.components.pagestate.PageRefreshState
import com.folderspan.ui.components.pagestate.PageStateLayout
import com.folderspan.ui.components.pagestate.PageViewState
import com.folderspan.ui.components.pagestate.pageAppendFooter
import com.folderspan.ui.components.pagestate.resolvePageViewState
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.AppNavigator
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.navigation.pushSafe
import com.folderspan.ui.notification.RequestNotificationInfo
import com.folderspan.ui.notification.toRequestInfo
import com.folderspan.ui.screen.settings.PermissionSettingsScreen
import com.folderspan.ui.state.device.DeviceRoleState
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.state.main.Notification
import com.folderspan.ui.state.main.NotificationState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import strings.AppStrings
import kotlin.time.Clock

internal enum class NotificationReadFilter { All, Unread, Read }

internal fun NotificationTab.showsLocalNotifications(): Boolean =
    this == NotificationTab.Local

internal fun NotificationTab.showsAccountNotifications(loggedIn: Boolean): Boolean =
    this == NotificationTab.Account && loggedIn

internal fun NotificationTab.showsAnnouncements(): Boolean =
    this == NotificationTab.Announcement

internal fun visibleNotificationTabs(loggedIn: Boolean): List<NotificationTab> =
    if (loggedIn) {
        listOf(NotificationTab.Local, NotificationTab.Account, NotificationTab.Announcement)
    } else {
        listOf(NotificationTab.Local, NotificationTab.Announcement)
    }

internal fun shouldShowNotificationTabs(loggedIn: Boolean): Boolean =
    visibleNotificationTabs(loggedIn).size > 1

internal fun effectiveNotificationTab(
    selectedTab: NotificationTab,
    loggedIn: Boolean,
): NotificationTab {
    val visible = visibleNotificationTabs(loggedIn)
    return if (selectedTab in visible) selectedTab else NotificationTab.Local
}

internal fun selectedNotificationTabIndex(
    selected: NotificationTab,
    loggedIn: Boolean,
): Int = visibleNotificationTabs(loggedIn)
    .indexOf(effectiveNotificationTab(selected, loggedIn))
    .coerceAtLeast(0)

private val NotificationSplitPaneMinWidth = 840.dp
private val NotificationListPaneMinWidth = 360.dp
private val NotificationListPaneMaxWidth = 440.dp

internal fun shouldUseNotificationSplitPane(availableWidth: Dp): Boolean =
    availableWidth >= NotificationSplitPaneMinWidth

internal data class NotificationPageLoadUi(
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val errorMessage: String? = null,
    val sourceItemCount: Int = 0,
    val emptyMessage: String,
    val unavailableFallback: String,
)

internal fun notificationPageLoadUi(
    accountVisible: Boolean,
    announcementVisible: Boolean,
    accountState: UserNotificationSnapshot,
    announcementState: AnnouncementSnapshot,
    visibleItemCount: Int,
): NotificationPageLoadUi = when {
    accountVisible -> NotificationPageLoadUi(
        isRefreshing = accountState.isRefreshing,
        isLoadingMore = accountState.isLoadingMore,
        hasMore = accountState.hasMore,
        errorMessage = accountState.errorMessage,
        sourceItemCount = accountState.items.size,
        emptyMessage = AppStrings.ui_no_notification_yet,
        unavailableFallback = AppStrings.ui_notification_account_unavailable,
    )

    announcementVisible -> NotificationPageLoadUi(
        isRefreshing = announcementState.isRefreshing,
        isLoadingMore = announcementState.isLoadingMore,
        hasMore = announcementState.hasMore,
        errorMessage = announcementState.errorMessage,
        sourceItemCount = announcementState.items.size,
        emptyMessage = AppStrings.ui_notification_announcement_empty,
        unavailableFallback = AppStrings.ui_notification_announcement_unavailable,
    )

    else -> NotificationPageLoadUi(
        sourceItemCount = visibleItemCount,
        emptyMessage = AppStrings.ui_no_notification_yet,
        unavailableFallback = AppStrings.ui_no_notification_yet,
    )
}

internal fun NotificationPageLoadUi.resolvedErrorMessage(): String? {
    val raw = errorMessage ?: return null
    return raw.ifBlank { unavailableFallback }
}

internal fun NotificationPageLoadUi.toPageViewState(visibleItemCount: Int): PageViewState =
    resolvePageViewState(
        isLoading = (isRefreshing || isLoadingMore) && sourceItemCount == 0 && errorMessage == null,
        errorState = resolvedErrorMessage()?.takeIf { sourceItemCount == 0 }?.let { message ->
            PageErrorState(PageErrorType.Connection, message)
        },
        isEmpty = visibleItemCount == 0,
        emptyMessage = emptyMessage,
    )

internal fun NotificationPageLoadUi.toPageRefreshState(visibleItemCount: Int): PageRefreshState {
    val refreshError = resolvedErrorMessage()
    return when {
        isRefreshing && visibleItemCount > 0 -> PageRefreshState.Refreshing
        refreshError != null && sourceItemCount > 0 && !isLoadingMore -> {
            PageRefreshState.Error(refreshError)
        }
        else -> PageRefreshState.Idle
    }
}

internal fun NotificationPageLoadUi.toPageAppendState(visibleItemCount: Int): PageAppendState = when {
    visibleItemCount == 0 -> PageAppendState.Idle
    isLoadingMore -> PageAppendState.Loading
    else -> PageAppendState.Idle
}

internal sealed interface TimelineNotification {
    val key: UnifiedNotificationKey
    val timestamp: Long
    val title: String
    val body: String
    val isRead: Boolean

    data class Local(val notification: Notification) : TimelineNotification {
        override val key = UnifiedNotificationKey.Local(notification.id)
        override val timestamp = notification.timestamp
        override val title = notification.displayTitle()
        override val body = notification.displayMessage()
        override val isRead = notification.isRead
    }

    data class Account(val notification: AccountNotification) : TimelineNotification {
        override val key = UnifiedNotificationKey.Account(notification.id)
        override val timestamp = notification.createdAtEpochMillis
        override val title = notification.title
        override val body = notification.content
        override val isRead = notification.status == UserNotificationStatus.Read
    }

    data class Announcement(
        val notification: AnnouncementMessage,
        val cutoffEpochMillis: Long,
    ) : TimelineNotification {
        override val key = UnifiedNotificationKey.Announcement(notification.id)
        override val timestamp = notification.publishedAtEpochMillis
        override val title = notification.title
        override val body = notification.content
        override val isRead = notification.isRead(cutoffEpochMillis)
    }
}

class NotificationScreen internal constructor(
    initialTab: NotificationTab = NotificationTab.Local,
) : AppScreenRoute {
    internal var selectedTab by mutableStateOf(initialTab)
    internal var readFilter by mutableStateOf(NotificationReadFilter.All)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val notificationState = koinInject<NotificationState>()
        val accountRuntime = koinInject<UserNotificationRuntime>()
        val announcementRuntime = koinInject<AnnouncementRuntime>()
        val deviceState = koinInject<DeviceState>()
        val fileShareState = koinInject<FileShareState>()
        val database = koinInject<FolderSpanDatabase>()
        val roleState = koinInject<DeviceRoleState>()
        val mainState = koinInject<MainState>()
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val accountState by accountRuntime.state.collectAsState()
        val announcementState by announcementRuntime.state.collectAsState()
        val session by SessionManager.session.collectAsState()
        val loggedIn = session != null
        val effectiveTab = effectiveNotificationTab(selectedTab, loggedIn)
        val revision = notificationState.revision
        val localNotifications = remember(revision) { notificationState.notifications.toList() }

        var selectionMode by remember { mutableStateOf(false) }
        var selectedKeys by remember { mutableStateOf<Set<UnifiedNotificationKey>>(emptySet()) }
        var detailKey by remember { mutableStateOf<UnifiedNotificationKey?>(null) }
        var splitPaneActive by remember { mutableStateOf(false) }
        var notificationLayoutResolved by remember { mutableStateOf(false) }
        var pendingRoleRequest by remember { mutableStateOf<PendingRoleRequest?>(null) }
        var pendingShareAutoApprove by remember { mutableStateOf<PendingShareAutoApprove?>(null) }

        LaunchedEffect(loggedIn) {
            if (!loggedIn && selectedTab == NotificationTab.Account) {
                selectedTab = NotificationTab.Local
            }
        }
        LaunchedEffect(readFilter, loggedIn, effectiveTab) {
            if (loggedIn && effectiveTab == NotificationTab.Account) {
                accountRuntime.setStatusFilter(readFilter.toAccountStatus())
            }
        }
        LaunchedEffect(effectiveTab) {
            if (effectiveTab == NotificationTab.Announcement) {
                announcementRuntime.refresh(reset = true)
            }
        }

        val localNotificationsVisible = effectiveTab.showsLocalNotifications()
        val accountNotificationsVisible = effectiveTab.showsAccountNotifications(loggedIn)
        val announcementNotificationsVisible = effectiveTab.showsAnnouncements()
        val timelineItems by remember(
            localNotifications,
            accountState.items,
            announcementState.items,
            announcementState.cutoffEpochMillis,
            localNotificationsVisible,
            accountNotificationsVisible,
            announcementNotificationsVisible,
            readFilter,
        ) {
            derivedStateOf {
                buildList {
                    if (localNotificationsVisible) {
                        addAll(localNotifications.map(TimelineNotification::Local))
                    }
                    if (accountNotificationsVisible) {
                        addAll(accountState.items.map(TimelineNotification::Account))
                    }
                    if (announcementNotificationsVisible) {
                        addAll(
                            announcementState.items.map { item ->
                                TimelineNotification.Announcement(
                                    notification = item,
                                    cutoffEpochMillis = announcementState.cutoffEpochMillis,
                                )
                            },
                        )
                    }
                }.filter { readFilter.matches(it.isRead) }
                    .sortedByDescending(TimelineNotification::timestamp)
            }
        }
        val selectableItems = timelineItems.filter(TimelineNotification::isSelectable)
        val selectedItems = timelineItems.filter { it.key in selectedKeys }
        val detailItem = timelineItems.firstOrNull { it.key == detailKey }
        val localUnread = localNotifications.count { !it.isRead }
        val accountTabSelected = effectiveTab == NotificationTab.Account
        val announcementTabSelected = effectiveTab == NotificationTab.Announcement
        val unreadTotal = when (effectiveTab) {
            NotificationTab.Local -> localUnread
            NotificationTab.Account -> if (loggedIn) accountState.unreadTotal else 0
            NotificationTab.Announcement -> announcementState.items.count { item ->
                !item.isRead(announcementState.cutoffEpochMillis)
            }
        }
        val pendingOpenRequestId = notificationState.pendingOpenRequestId

        LaunchedEffect(pendingOpenRequestId, revision, notificationLayoutResolved) {
            if (!notificationLayoutResolved) return@LaunchedEffect
            val requestId = pendingOpenRequestId ?: return@LaunchedEffect
            val target = notificationState.findByRequestId(requestId) ?: return@LaunchedEffect
            if (!target.isRead) notificationState.markAsRead(target.id)
            notificationState.consumePendingOpenRequestId(requestId)
            if (shouldOpenLocalNotificationDirectly(target)) {
                openLocalNotification(navigator, target)
            } else if (splitPaneActive) {
                selectedTab = NotificationTab.Local
                detailKey = UnifiedNotificationKey.Local(target.id)
            } else {
                openLocalNotification(navigator, target)
            }
        }

        LaunchedEffect(timelineItems.map(TimelineNotification::key), selectionMode) {
            selectedKeys = selectedKeys.intersect(selectableItems.map(TimelineNotification::key).toSet())
            if (selectionMode && selectableItems.isEmpty()) selectionMode = false
            if (detailKey != null && detailItem == null) detailKey = null
        }

        fun resolveSocketDevice(deviceId: String): SocketDevice? =
            deviceState.socketDevices.firstOrNull { it.id == deviceId }

        fun handleRequestAction(info: RequestNotificationInfo, action: RequestAction) {
            when (info.kind) {
                RequestNotificationKind.DeviceConnect -> {
                    if (action == RequestAction.Reject) {
                        deviceState.updateConnectionRequest(
                            deviceId = info.deviceId,
                            connectionType = DeviceConnectType.REJECTED,
                            requestedAt = deviceState.connectionRequest[info.deviceId]?.second
                                ?: Clock.System.now().toEpochMilliseconds(),
                            deviceName = info.deviceName,
                        )
                        return
                    }
                    val socketDevice = resolveSocketDevice(info.deviceId) ?: return
                    val connectType = action.toConnectType() ?: return
                    scope.launch {
                        when (
                            val result = handleDeviceConnectRequest(
                                socketDevice,
                                connectType,
                                database,
                                deviceState,
                            )
                        ) {
                            is DeviceConnectActionResult.RequiresRoleSelection ->
                                pendingRoleRequest = PendingRoleRequest(socketDevice, result.pendingType)
                            DeviceConnectActionResult.Completed -> Unit
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
                    scope.launch { handleDeviceShareRequest(socketDevice, shareAction, database, deviceState) }
                }

                RequestNotificationKind.LinkShare -> when (action) {
                    RequestAction.Approve -> fileShareState.approveLinkShareDevice(info.deviceId)
                    RequestAction.Reject -> fileShareState.rejectLinkShareDevice(info.deviceId)
                    else -> Unit
                }

                RequestNotificationKind.LinkShareUpload -> when (action) {
                    RequestAction.Approve -> fileShareState.approveLinkShareUploadDevice(info.deviceId)
                    RequestAction.Reject -> fileShareState.rejectLinkShareUploadDevice(info.deviceId)
                    else -> Unit
                }
            }
        }

        fun openItem(item: TimelineNotification, showDetailPane: Boolean) {
            when (item) {
                is TimelineNotification.Local -> {
                    val notification = item.notification
                    if (!notification.isRead) notificationState.markAsRead(notification.id)
                    if (RequestNotificationFactory.isPermissionReminderMetadata(notification.metadata)) {
                        StartupPermissionReminderCoordinator.markReminderHandled()
                        navigator.pushSafe(PermissionSettingsScreen())
                    } else if (shouldOpenLocalNotificationDirectly(notification)) {
                        openLocalNotification(navigator, notification)
                    } else if (showDetailPane) {
                        detailKey = item.key
                    } else {
                        openLocalNotification(navigator, notification)
                    }
                }

                is TimelineNotification.Account -> {
                    if (!item.isRead) scope.launch { accountRuntime.markRead(listOf(item.notification.id)) }
                    if (showDetailPane) detailKey = item.key
                    else openAccountNotificationDetail(navigator, item.notification)
                }

                is TimelineNotification.Announcement -> {
                    if (showDetailPane) detailKey = item.key
                    else openAnnouncementDetail(navigator, item.notification)
                }
            }
        }

        fun toggleSelection(item: TimelineNotification) {
            if (!item.isSelectable()) return
            selectedKeys = if (item.key in selectedKeys) selectedKeys - item.key else selectedKeys + item.key
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (selectionMode) {
                                AppStrings.ui_arg0_items_selected.format(arg0 = selectedKeys.size.toString())
                            } else {
                                AppStrings.ui_notification_center
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectionMode) {
                                selectionMode = false
                                selectedKeys = emptySet()
                            } else navigator.pop()
                        }) {
                            Icon(
                                if (selectionMode) Icons.Default.Close else Icons.AutoMirrored.Default.ArrowBack,
                                contentDescription = AppStrings.ui_close,
                            )
                        }
                    },
                    actions = {
                        if (selectionMode) {
                            TextButton(onClick = {
                                val allKeys = selectableItems.map(TimelineNotification::key).toSet()
                                selectedKeys = if (selectedKeys == allKeys) emptySet() else allKeys
                            }) {
                                Text(if (selectedKeys.size == selectableItems.size) AppStrings.ui_deselect_all else AppStrings.ui_select_all)
                            }
                        } else {
                            if (unreadTotal > 0) {
                                IconButton(onClick = {
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = AppStrings.ui_notification_loaded_all_read,
                                            actionLabel = AppStrings.ui_mark_read,
                                            withDismissAction = true,
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            when (effectiveTab) {
                                                NotificationTab.Local -> notificationState.markAllAsRead()
                                                NotificationTab.Account -> if (loggedIn) {
                                                    accountRuntime.markAllLoadedRead()
                                                }
                                                NotificationTab.Announcement ->
                                                    announcementRuntime.markAllLoadedRead()
                                            }
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.DoneAll, AppStrings.ui_notification_loaded_all_read)
                                }
                            }
                            if (selectableItems.isNotEmpty()) {
                                IconButton(onClick = { selectionMode = true }) {
                                    Icon(Icons.Default.Checklist, AppStrings.ui_notification_select)
                                }
                            }
                            if (loggedIn && accountTabSelected) {
                                IconButton(onClick = { scope.launch { accountRuntime.refresh(reset = true) } }) {
                                    Icon(Icons.Default.Refresh, AppStrings.ui_notification_retry)
                                }
                            }
                            if (announcementTabSelected) {
                                IconButton(onClick = { scope.launch { announcementRuntime.refresh(reset = true) } }) {
                                    Icon(Icons.Default.Refresh, AppStrings.ui_notification_retry)
                                }
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (selectionMode && selectedItems.isNotEmpty()) {
                    NotificationSelectionBar(
                        selectedItems = selectedItems,
                        onMarkRead = {
                            val itemsToMarkRead = selectedItems.filter { !it.isRead }
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = AppStrings.ui_notification_mark_selected_read_arg0.format(
                                        arg0 = itemsToMarkRead.size.toString(),
                                    ),
                                    actionLabel = AppStrings.ui_mark_read,
                                    withDismissAction = true,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    val localIds = itemsToMarkRead.filterIsInstance<TimelineNotification.Local>()
                                        .map { it.notification.id }
                                        .toSet()
                                    val accountIds = itemsToMarkRead.filterIsInstance<TimelineNotification.Account>()
                                        .map { it.notification.id }
                                    notificationState.markMultipleAsRead(localIds)
                                    accountRuntime.markRead(accountIds)
                                    selectedKeys = emptySet()
                                    selectionMode = false
                                }
                            }
                        },
                        onMarkUnread = {
                            val itemsToMarkUnread = selectedItems.filter(TimelineNotification::isRead)
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = AppStrings.ui_notification_mark_selected_unread_arg0.format(
                                        arg0 = itemsToMarkUnread.size.toString(),
                                    ),
                                    actionLabel = AppStrings.ui_mark_unread,
                                    withDismissAction = true,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    val localIds = itemsToMarkUnread.filterIsInstance<TimelineNotification.Local>()
                                        .map { it.notification.id }
                                        .toSet()
                                    val accountIds = itemsToMarkUnread.filterIsInstance<TimelineNotification.Account>()
                                        .map { it.notification.id }
                                    notificationState.markMultipleAsUnread(localIds)
                                    accountRuntime.markUnread(accountIds)
                                    selectedKeys = emptySet()
                                    selectionMode = false
                                }
                            }
                        },
                        onDelete = {
                            val itemsToDelete = selectedItems.toList()
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = AppStrings.ui_notification_delete_selected_arg0.format(
                                        arg0 = itemsToDelete.size.toString(),
                                    ),
                                    actionLabel = AppStrings.ui_delete,
                                    withDismissAction = true,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    val local = itemsToDelete.filterIsInstance<TimelineNotification.Local>()
                                    val accountIds = itemsToDelete.filterIsInstance<TimelineNotification.Account>()
                                        .map { it.notification.id }
                                    local.forEach { item ->
                                        if (RequestNotificationFactory.isPermissionReminderMetadata(item.notification.metadata)) {
                                            StartupPermissionReminderCoordinator.markReminderHandled()
                                        }
                                    }
                                    notificationState.deleteMultiple(local.map { it.notification.id }.toSet())
                                    accountRuntime.delete(accountIds)
                                    selectedKeys = emptySet()
                                    selectionMode = false
                                }
                            }
                        },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                val showNotificationTabs = shouldShowNotificationTabs(loggedIn)
                if (showNotificationTabs) {
                    NotificationTabs(
                        selected = effectiveTab,
                        visibleTabs = visibleNotificationTabs(loggedIn),
                        onChange = { tab ->
                            selectedTab = tab
                            selectionMode = false
                            selectedKeys = emptySet()
                            detailKey = null
                        },
                    )
                }
                NotificationStatusFilters(
                    read = readFilter,
                    onReadChange = { readFilter = it },
                    includeTopPadding = showNotificationTabs,
                )
                NotificationWorkspace(
                    items = timelineItems,
                    selectedKeys = selectedKeys,
                    selectionMode = selectionMode,
                    detailItem = detailItem,
                    accountState = accountState,
                    accountNotificationsVisible = accountNotificationsVisible,
                    announcementState = announcementState,
                    announcementNotificationsVisible = announcementNotificationsVisible,
                    mainState = mainState,
                    onSplitPaneChanged = {
                        splitPaneActive = it
                        notificationLayoutResolved = true
                    },
                    onClick = { item, showDetailPane ->
                        if (selectionMode) toggleSelection(item) else openItem(item, showDetailPane)
                    },
                    onLongClick = {
                        if (it.isSelectable()) {
                            selectionMode = true
                            toggleSelection(it)
                        }
                    },
                    onRequestAction = ::handleRequestAction,
                    onAccountAction = { action -> performAccountNotificationAction(mainState, action) },
                    onLoadMore = {
                        scope.launch {
                            when (effectiveTab) {
                                NotificationTab.Account -> accountRuntime.loadMore()
                                NotificationTab.Announcement -> announcementRuntime.loadMore()
                                NotificationTab.Local -> Unit
                            }
                        }
                    },
                    onRefresh = when {
                        accountNotificationsVisible -> {
                            { scope.launch { accountRuntime.refresh(reset = true) } }
                        }

                        announcementNotificationsVisible -> {
                            { scope.launch { announcementRuntime.refresh(reset = true) } }
                        }

                        else -> null
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        pendingShareAutoApprove?.let { request ->
            DeviceShareSavePathDialog(
                onConfirm = { savePath ->
                    scope.launch {
                        handleDeviceShareRequest(
                            request.socketDevice,
                            DeviceShareRequestAction.AutoSave,
                            database,
                            deviceState,
                            savePath,
                        )
                        pendingShareAutoApprove = null
                    }
                },
                onCancel = { pendingShareAutoApprove = null },
                onDismissRequest = {},
            )
        }

        pendingRoleRequest?.let { request ->
            val roleOptions = buildDeviceRoleOptionsUiState(roleState.roles)
            var selectedRoleId by remember(request.socketDevice.id) { mutableStateOf<Long?>(null) }
            DeviceRoleSelectionDialog(
                prompt = AppStrings.dialog_choose_device_role.format(deviceName = request.socketDevice.name),
                roleOptions = roleOptions,
                selectedRoleId = selectedRoleId,
                onRoleSelect = { selectedRoleId = it },
                onConfirm = {
                    val roleId = selectedRoleId ?: return@DeviceRoleSelectionDialog
                    scope.launch {
                        handleDeviceConnectRequest(
                            request.socketDevice,
                            request.connectionType,
                            database,
                            deviceState,
                            roleId,
                        )
                        pendingRoleRequest = null
                    }
                },
                onCancel = { pendingRoleRequest = null },
                onDismissRequest = {},
            )
        }
    }
}

private fun NotificationReadFilter.toAccountStatus(): UserNotificationStatus? = when (this) {
    NotificationReadFilter.All -> null
    NotificationReadFilter.Unread -> UserNotificationStatus.Unread
    NotificationReadFilter.Read -> UserNotificationStatus.Read
}

internal fun NotificationReadFilter.matches(isRead: Boolean): Boolean = when (this) {
    NotificationReadFilter.All -> true
    NotificationReadFilter.Unread -> !isRead
    NotificationReadFilter.Read -> isRead
}

internal fun TimelineNotification.isSelectable(): Boolean = when (this) {
    is TimelineNotification.Local -> notification.toRequestInfo() == null
    is TimelineNotification.Account -> true
    is TimelineNotification.Announcement -> false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationTabs(
    selected: NotificationTab,
    visibleTabs: List<NotificationTab>,
    onChange: (NotificationTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryTabRow(
        selectedTabIndex = visibleTabs.indexOf(selected).coerceAtLeast(0),
        modifier = modifier.fillMaxWidth(),
        containerColor = Color.Transparent,
    ) {
        visibleTabs.forEach { tab ->
            Tab(
                selected = selected == tab,
                onClick = { onChange(tab) },
                text = { Text(tab.label()) },
            )
        }
    }
}

private fun NotificationTab.label(): String = when (this) {
    NotificationTab.Local -> AppStrings.ui_notification_tab_local
    NotificationTab.Account -> AppStrings.ui_notification_tab_account
    NotificationTab.Announcement -> AppStrings.ui_notification_tab_announcement
}

@Composable
private fun NotificationStatusFilters(
    read: NotificationReadFilter,
    onReadChange: (NotificationReadFilter) -> Unit,
    includeTopPadding: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(
                start = 16.dp,
                top = if (includeTopPadding) 6.dp else 0.dp,
                end = 16.dp,
                bottom = 6.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ReadChip(NotificationReadFilter.All, AppStrings.ui_all, read, onReadChange)
            ReadChip(
                NotificationReadFilter.Unread,
                AppStrings.ui_notification_filter_unread,
                read,
                onReadChange,
            )
            ReadChip(
                NotificationReadFilter.Read,
                AppStrings.ui_notification_filter_read,
                read,
                onReadChange,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ReadChip(
    value: NotificationReadFilter,
    label: String,
    selected: NotificationReadFilter,
    onChange: (NotificationReadFilter) -> Unit,
) {
    FilterChip(
        selected = value == selected,
        onClick = { onChange(value) },
        label = { Text(label) },
    )
}

@Composable
private fun NotificationWorkspace(
    items: List<TimelineNotification>,
    selectedKeys: Set<UnifiedNotificationKey>,
    selectionMode: Boolean,
    detailItem: TimelineNotification?,
    accountState: UserNotificationSnapshot,
    accountNotificationsVisible: Boolean,
    announcementState: AnnouncementSnapshot,
    announcementNotificationsVisible: Boolean,
    mainState: MainState,
    onSplitPaneChanged: (Boolean) -> Unit,
    onClick: (TimelineNotification, Boolean) -> Unit,
    onLongClick: (TimelineNotification) -> Unit,
    onRequestAction: (RequestNotificationInfo, RequestAction) -> Unit,
    onAccountAction: (NotificationAction) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val loadUi = notificationPageLoadUi(
        accountVisible = accountNotificationsVisible,
        announcementVisible = announcementNotificationsVisible,
        accountState = accountState,
        announcementState = announcementState,
        visibleItemCount = items.size,
    )
    BoxWithConstraints(modifier) {
        val showDetailPane = shouldUseNotificationSplitPane(maxWidth)
        LaunchedEffect(showDetailPane) { onSplitPaneChanged(showDetailPane) }
        if (showDetailPane) {
            val listPaneWidth = (maxWidth * 0.4f).coerceIn(
                NotificationListPaneMinWidth,
                NotificationListPaneMaxWidth,
            )
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .width(listPaneWidth)
                        .fillMaxHeight(),
                ) {
                    NotificationTimeline(
                        items = items,
                        selectedKeys = selectedKeys,
                        selectionMode = selectionMode,
                        selectedDetailKey = detailItem?.key,
                        loadUi = loadUi,
                        onClick = { onClick(it, true) },
                        onLongClick = onLongClick,
                        onRequestAction = onRequestAction,
                        onAccountAction = onAccountAction,
                        onLoadMore = onLoadMore,
                        onRefresh = onRefresh,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(0.dp),
                    )
                }
                VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
                NotificationDetailPane(
                    item = detailItem,
                    mainState = mainState,
                    onRequestAction = onRequestAction,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            NotificationTimeline(
                items = items,
                selectedKeys = selectedKeys,
                selectionMode = selectionMode,
                selectedDetailKey = null,
                loadUi = loadUi,
                onClick = { onClick(it, false) },
                onLongClick = onLongClick,
                onRequestAction = onRequestAction,
                onAccountAction = onAccountAction,
                onLoadMore = onLoadMore,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun NotificationTimeline(
    items: List<TimelineNotification>,
    selectedKeys: Set<UnifiedNotificationKey>,
    selectionMode: Boolean,
    selectedDetailKey: UnifiedNotificationKey?,
    loadUi: NotificationPageLoadUi,
    onClick: (TimelineNotification) -> Unit,
    onLongClick: (TimelineNotification) -> Unit,
    onRequestAction: (RequestNotificationInfo, RequestAction) -> Unit,
    onAccountAction: (NotificationAction) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: (() -> Unit)?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp),
) {
    val listState = rememberLazyListState()
    val appendState = loadUi.toPageAppendState(items.size)
    LoadMoreOnNearEnd(
        state = listState,
        enabled = loadUi.hasMore && appendState is PageAppendState.Idle,
        onLoadMore = onLoadMore,
    )
    PageStateLayout(
        state = loadUi.toPageViewState(items.size),
        modifier = modifier,
        refreshState = loadUi.toPageRefreshState(items.size),
        onRefresh = onRefresh,
        onRetryRefresh = onRefresh,
        error = { error ->
            ErrorConnection(
                message = error.message ?: loadUi.unavailableFallback,
                actionLabel = onRefresh?.let { AppStrings.ui_try_again },
                onAction = onRefresh,
            )
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            items(
                items = items,
                key = { it.key.toString() },
                contentType = {
                    when (it) {
                        is TimelineNotification.Account -> "account"
                        is TimelineNotification.Announcement -> "announcement"
                        is TimelineNotification.Local -> "local"
                    }
                },
            ) { item ->
                TimelineNotificationRow(
                    item = item,
                    selected = item.key in selectedKeys,
                    detailSelected = item.key == selectedDetailKey,
                    selectionMode = selectionMode,
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                    onRequestAction = onRequestAction,
                    onAccountAction = onAccountAction,
                    modifier = Modifier.animateItem(),
                )
            }
            pageAppendFooter(
                state = appendState,
                onRetry = onLoadMore,
            )
        }
    }
}

@Composable
private fun TimelineNotificationRow(
    item: TimelineNotification,
    selected: Boolean,
    detailSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRequestAction: (RequestNotificationInfo, RequestAction) -> Unit,
    onAccountAction: (NotificationAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = notificationRowColors(
        colorScheme = MaterialTheme.colorScheme,
        selected = selected,
        detailSelected = detailSelected,
        isRead = item.isRead,
    )
    val requestInfo = (item as? TimelineNotification.Local)?.notification?.toRequestInfo()
    val catalogActions = when (item) {
        is TimelineNotification.Account -> item.notification.actions
        is TimelineNotification.Announcement -> item.notification.actions
        is TimelineNotification.Local -> emptyList()
    }
    val usesMarkdownPreview = item is TimelineNotification.Account || item is TimelineNotification.Announcement
    val previewBody = remember(item.body, usesMarkdownPreview) {
        if (usesMarkdownPreview) accountNotificationListPreview(item.body) else item.body
    }
    Column(modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    this.selected = selected || detailSelected
                    role = Role.Button
                }
                .combinedClickableWithContextClick(onClick = onClick, onLongClick = onLongClick),
            color = colors.container,
            contentColor = colors.content,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    if (selectionMode && item.isSelectable()) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { onClick() },
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.content,
                                checkmarkColor = colors.container,
                                uncheckedColor = colors.content,
                            ),
                        )
                    } else {
                        NotificationSourceIcon(
                            item = item,
                            size = 40.dp,
                            tint = colors.accent.takeIf { selected || detailSelected },
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!item.isRead) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
                            }
                            Text(
                                item.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (item.isRead) FontWeight.Medium else FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            previewBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.supportingContent,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.timestamp.timestampToAdaptiveDateTime(),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.supportingContent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!selectionMode && requestInfo != null) {
                    RequestActionRow(
                        requestInfo = requestInfo,
                        onAction = onRequestAction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (!selectionMode && catalogActions.isNotEmpty()) {
                    AccountNotificationActionRow(
                        actions = catalogActions,
                        onAction = onAccountAction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (!selectionMode && item is TimelineNotification.Local) {
                    AppUpdateActionRow(
                        metadata = item.notification.metadata,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

internal data class NotificationRowColors(
    val container: Color,
    val content: Color,
    val supportingContent: Color,
    val accent: Color,
)

internal fun notificationRowColors(
    colorScheme: ColorScheme,
    selected: Boolean,
    detailSelected: Boolean,
    isRead: Boolean,
): NotificationRowColors = when {
    selected -> NotificationRowColors(
        container = colorScheme.primaryContainer,
        content = colorScheme.onPrimaryContainer,
        supportingContent = colorScheme.onPrimaryContainer,
        accent = colorScheme.onPrimaryContainer,
    )
    detailSelected -> NotificationRowColors(
        container = colorScheme.secondaryContainer,
        content = colorScheme.onSecondaryContainer,
        supportingContent = colorScheme.onSecondaryContainer,
        accent = colorScheme.onSecondaryContainer,
    )
    !isRead -> NotificationRowColors(
        container = colorScheme.surfaceContainer,
        content = colorScheme.onSurface,
        supportingContent = colorScheme.onSurfaceVariant,
        accent = colorScheme.primary,
    )
    else -> NotificationRowColors(
        container = Color.Transparent,
        content = colorScheme.onSurface,
        supportingContent = colorScheme.onSurfaceVariant,
        accent = colorScheme.primary,
    )
}

internal data class NotificationIconPalette(
    val icon: ImageVector,
    val contentColor: Color,
)

@Composable
private fun NotificationSourceIcon(
    item: TimelineNotification,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color? = null,
) {
    val palette = notificationIconPalette(item)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            palette.icon,
            contentDescription = null,
            modifier = Modifier.size(size * 0.55f),
            tint = tint ?: palette.contentColor,
        )
    }
}

@Composable
internal fun accountNotificationPalette(type: String): NotificationIconPalette = when (type.lowercase()) {
    "error" -> NotificationIconPalette(
        Icons.Default.ErrorOutline,
        MaterialTheme.colorScheme.error,
    )
    "system" -> NotificationIconPalette(
        Icons.Default.WarningAmber,
        MaterialTheme.colorScheme.tertiary,
    )
    "support_case" -> NotificationIconPalette(
        Icons.Default.SupportAgent,
        MaterialTheme.colorScheme.secondary,
    )
    else -> NotificationIconPalette(
        Icons.Default.AccountCircle,
        MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun notificationIconPalette(item: TimelineNotification): NotificationIconPalette = when (item) {
    is TimelineNotification.Local -> NotificationIconPalette(
        icon = item.notification.getIcon(),
        contentColor = MaterialTheme.colorScheme.primary,
    )
    is TimelineNotification.Account -> accountNotificationPalette(item.notification.type)
    is TimelineNotification.Announcement -> announcementNotificationPalette()
}

@Composable
internal fun announcementNotificationPalette(): NotificationIconPalette = NotificationIconPalette(
    Icons.Default.Campaign,
    MaterialTheme.colorScheme.secondary,
)

@Composable
private fun NotificationDetailPane(
    item: TimelineNotification?,
    mainState: MainState,
    modifier: Modifier = Modifier,
    onRequestAction: ((RequestNotificationInfo, RequestAction) -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        if (item == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        AppStrings.ui_notification_detail_placeholder,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Surface
        }
        val account = (item as? TimelineNotification.Account)?.notification
        val announcement = (item as? TimelineNotification.Announcement)?.notification
        val local = (item as? TimelineNotification.Local)?.notification
        val requestInfo = local?.toRequestInfo()
        val palette = notificationIconPalette(item)
        val markdownBody = account != null || announcement != null
        val catalogActions = account?.actions.orEmpty().ifEmpty { announcement?.actions.orEmpty() }
        NotificationDetailLayout(
            icon = palette.icon,
            iconColor = palette.contentColor,
            category = announcement?.let { AppStrings.ui_notification_tab_announcement }
                ?: account?.displayCategory()
                ?: local?.displayCategory().orEmpty(),
            timestampText = item.timestamp.timestampToSyncDate(),
            title = item.title,
            modifier = Modifier.fillMaxSize(),
            footer = {
                if (requestInfo != null && onRequestAction != null) {
                    RequestActionRow(
                        requestInfo = requestInfo,
                        onAction = onRequestAction,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                } else if (catalogActions.isNotEmpty()) {
                    AccountNotificationActionRow(
                        actions = catalogActions,
                        onAction = { action -> performAccountNotificationAction(mainState, action) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                } else if (local != null) {
                    AppUpdateActionRow(
                        metadata = local.metadata,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                }
            },
        ) {
            if (!markdownBody) {
                Text(
                    text = item.body,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                NotificationMarkdownContent(
                    content = item.body,
                    onAction = { action -> performAccountNotificationAction(mainState, action) },
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun NotificationSelectionBar(
    selectedItems: List<TimelineNotification>,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onDelete: () -> Unit,
) {
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = PaddingValues(horizontal = 8.dp),
        tonalElevation = 3.dp,
    ) {
        if (selectedItems.any { !it.isRead }) {
            NotificationSelectionAction(
                icon = Icons.Default.Done,
                label = AppStrings.ui_mark_read,
                onClick = onMarkRead,
                modifier = Modifier.weight(1f),
            )
        }
        if (selectedItems.any(TimelineNotification::isRead)) {
            NotificationSelectionAction(
                icon = Icons.Default.Markunread,
                label = AppStrings.ui_mark_unread,
                onClick = onMarkUnread,
                modifier = Modifier.weight(1f),
            )
        }
        NotificationSelectionAction(
            icon = Icons.Default.Delete,
            label = AppStrings.ui_delete,
            onClick = onDelete,
            destructive = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NotificationSelectionAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun AccountNotification.displayCategory(): String = when (type.lowercase()) {
    "error" -> AppStrings.ui_error
    "system" -> AppStrings.ui_notification_tab_account
    "support_case" -> AppStrings.ui_feedback
    else -> AppStrings.ui_notification_tab_account
}

internal data class AccountNotificationActionLayout(
    val visible: List<NotificationAction>,
    val overflow: List<NotificationAction>,
)

internal fun accountNotificationActionLayout(
    actions: List<NotificationAction>,
    visibleLimit: Int = 2,
): AccountNotificationActionLayout {
    if (actions.size <= visibleLimit) {
        return AccountNotificationActionLayout(visible = actions, overflow = emptyList())
    }
    return AccountNotificationActionLayout(
        visible = actions.take(visibleLimit),
        overflow = actions.drop(visibleLimit),
    )
}

@Composable
internal fun AccountNotificationActionRow(
    actions: List<NotificationAction>,
    onAction: (NotificationAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = remember(actions) { accountNotificationActionLayout(actions) }
    if (layout.visible.isEmpty()) return
    val primaryIndex = layout.visible.indexOfFirst { action -> action.style == NotificationActionStyle.Primary }
        .takeIf { index -> index >= 0 } ?: 0
    var overflowExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        layout.visible.forEachIndexed { index, action ->
            val available = isAccountNotificationActionAvailable(action)
            val content: @Composable RowScope.() -> Unit = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = action.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!available) {
                        Text(
                            AppStrings.ui_notification_destination_unsupported,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (index == primaryIndex) {
                Button(
                    onClick = { onAction(action) },
                    enabled = available,
                    modifier = Modifier.weight(1f),
                    content = content,
                )
            } else {
                FilledTonalButton(
                    onClick = { onAction(action) },
                    enabled = available,
                    modifier = Modifier.weight(1f),
                    content = content,
                )
            }
        }
        if (layout.overflow.isNotEmpty()) {
            Box {
                IconButton(onClick = { overflowExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = AppStrings.ui_more_actions)
                }
                DropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { overflowExpanded = false },
                ) {
                    layout.overflow.forEach { action ->
                        val available = isAccountNotificationActionAvailable(action)
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = action.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!available) {
                                        Text(
                                            AppStrings.ui_notification_destination_unsupported,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            enabled = available,
                            onClick = {
                                overflowExpanded = false
                                onAction(action)
                            },
                        )
                    }
                }
            }
        }
    }
}

// 通知详情页面：设备消息与账号消息共用同一页面。
data class NotificationDetailScreen(
    val notification: Notification? = null,
    val accountNotification: AccountNotification? = null,
    val announcement: AnnouncementMessage? = null,
) : AppScreenRoute {
    init {
        require(listOfNotNull(notification, accountNotification, announcement).size == 1) {
            "NotificationDetailScreen requires exactly one of notification, accountNotification, or announcement"
        }
    }

    @Composable
    override fun Content() {
        val deviceNotification = notification
        val account = accountNotification
        val catalog = announcement
        when {
            catalog != null -> AnnouncementDetailContent(catalog)
            account != null -> AccountNotificationDetailContent(account)
            deviceNotification != null -> DeviceNotificationDetailContent(deviceNotification)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountNotificationDetailContent(notification: AccountNotification) {
    val navigator = LocalAppNavigator.currentOrThrow
    val runtime = koinInject<UserNotificationRuntime>()
    val mainState = koinInject<MainState>()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val palette = accountNotificationPalette(notification.type)
    LaunchedEffect(notification.id) {
        if (notification.status == UserNotificationStatus.Unread) runtime.markRead(listOf(notification.id))
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(AppStrings.ui_notification_details) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            navigateBackToNotificationList(
                                navigator = navigator,
                                targetTab = NotificationTab.Account,
                            )
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = AppStrings.ui_notification_delete_selected_arg0.format(arg0 = "1"),
                                actionLabel = AppStrings.ui_delete,
                                withDismissAction = true,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                runtime.delete(listOf(notification.id))
                                navigator.pop()
                            }
                        }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = AppStrings.ui_delete)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        NotificationDetailLayout(
            icon = palette.icon,
            iconColor = palette.contentColor,
            category = notification.displayCategory(),
            timestampText = notification.createdAtEpochMillis.timestampToSyncDate(),
            title = notification.title,
            modifier = Modifier.padding(padding).fillMaxSize(),
            footer = {
                if (notification.actions.isNotEmpty()) {
                    AccountNotificationActionRow(
                        actions = notification.actions,
                        onAction = { action -> performAccountNotificationAction(mainState, action) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                } else {
                    FilledTonalButton(
                        onClick = {
                            val markRead = notification.status != UserNotificationStatus.Read
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
                                        runtime.markRead(listOf(notification.id))
                                    } else {
                                        runtime.markUnread(listOf(notification.id))
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
                            if (notification.status == UserNotificationStatus.Read) {
                                Icons.Default.Markunread
                            } else {
                                Icons.Default.Done
                            },
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (notification.status == UserNotificationStatus.Read) {
                                AppStrings.ui_mark_unread
                            } else {
                                AppStrings.ui_mark_read
                            },
                        )
                    }
                }
            },
        ) {
            NotificationMarkdownContent(
                content = notification.content,
                onAction = { action -> performAccountNotificationAction(mainState, action) },
                modifier = Modifier.fillMaxWidth(),
                style = typography.bodyLarge,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnouncementDetailContent(announcement: AnnouncementMessage) {
    val navigator = LocalAppNavigator.currentOrThrow
    val mainState = koinInject<MainState>()
    val palette = announcementNotificationPalette()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(AppStrings.ui_notification_details) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            navigateBackToNotificationList(
                                navigator = navigator,
                                targetTab = NotificationTab.Announcement,
                            )
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        NotificationDetailLayout(
            icon = palette.icon,
            iconColor = palette.contentColor,
            category = AppStrings.ui_notification_tab_announcement,
            timestampText = announcement.publishedAtEpochMillis.timestampToSyncDate(),
            title = announcement.title,
            modifier = Modifier.padding(padding).fillMaxSize(),
            footer = {
                if (announcement.actions.isNotEmpty()) {
                    AccountNotificationActionRow(
                        actions = announcement.actions,
                        onAction = { action -> performAccountNotificationAction(mainState, action) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                }
            },
        ) {
            NotificationMarkdownContent(
                content = announcement.content,
                onAction = { action -> performAccountNotificationAction(mainState, action) },
                modifier = Modifier.fillMaxWidth(),
                style = typography.bodyLarge,
            )
        }
    }
}

internal fun openAccountNotificationDetail(
    navigator: AppNavigator,
    notification: AccountNotification,
) {
    navigator.pushSafe(NotificationDetailScreen(accountNotification = notification))
}

internal fun openAnnouncementDetail(
    navigator: AppNavigator,
    announcement: AnnouncementMessage,
) {
    navigator.pushSafe(NotificationDetailScreen(announcement = announcement))
}

package com.folderspan.ui.components.drawer

import strings.AppStrings

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.appDrawerAccountHeaderClickRoute
import com.folderspan.rememberProAccountHeader
import com.folderspan.PRO_AVAILABLE
import com.folderspan.isProAuthenticated
import com.folderspan.proFeedbackScreen
import com.folderspan.PlatformType
import com.folderspan.clipboard.rememberClipboardFilePasteController
import com.folderspan.crash.exitApp
import com.folderspan.extensions.DeviceIcon
import com.folderspan.proNotificationUnreadCount
import com.folderspan.service.http.server.SocketClientIPEnum
import com.folderspan.service.http.server.getAllIPAddresses
import com.folderspan.service.http.clipboard.ClipboardUrlShareInspector
import com.folderspan.ui.components.dialog.TextFieldDialog
import com.folderspan.ui.components.dialog.TaskInfoDialogContainer
import com.folderspan.ui.components.model.StringListUiState
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.screen.main.NotificationScreen
import com.folderspan.ui.screen.main.ToolboxScreen
import com.folderspan.ui.screen.main.notificationBellUnreadCount
import com.folderspan.ui.screen.settings.SettingsScreen
import com.folderspan.ui.screen.task.TaskListScreen
import com.folderspan.ui.screen.task.TaskResultScreen
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.FileOperationState
import com.folderspan.ui.state.file.ClipboardUrlDownloadCoordinator
import com.folderspan.ui.state.main.*
import com.folderspan.utils.SettingsUtils
import com.seiko.imageloader.rememberImagePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import com.folderspan.ui.state.main.DrawerState as MainDrawerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawerContainer() {
    val mainState = koinInject<MainState>()
    val taskState = koinInject<TaskState>()
    val drawerState = koinInject<MainDrawerState>()
    val deviceState = koinInject<DeviceState>()
    val fileState = koinInject<FileState>()
    val fileOperationState = koinInject<FileOperationState>()
    val clipboardUrlDownloadCoordinator = koinInject<ClipboardUrlDownloadCoordinator>()
    val clipboardUrlShareInspector = koinInject<ClipboardUrlShareInspector>()
    val clipboardUrlDownloadState by clipboardUrlDownloadCoordinator.state.collectAsState()
    val homeState = koinInject<HomeState>()
    val notificationState = koinInject<NotificationState>()
    val isDeviceAdd by deviceState.isDeviceAdd.collectAsState()
    val localIpAddressState by produceState(
        initialValue = LocalIpAddressState(),
        key1 = isDeviceAdd,
    ) {
        if (!isDeviceAdd) {
            value = LocalIpAddressState()
            return@produceState
        }
        value = LocalIpAddressState()
        value = withContext(Dispatchers.Default) {
            LocalIpAddressState(
                isLoaded = true,
                addresses = runCatching {
                    getAllIPAddresses(type = SocketClientIPEnum.ALL)
                }.getOrDefault(emptyList()),
                subnetOptions = StringListUiState(
                    runCatching {
                        appDrawerDeviceAddressInputPrefixes(
                            getAllIPAddresses(type = SocketClientIPEnum.IPV4_UP)
                        )
                    }.getOrDefault(emptyList())
                ),
            )
        }
    }
    val isShowDevice by drawerState.isShowDevice.collectAsState()
    val isShowWebRtc by drawerState.isShowWebRtc.collectAsState()
    val isShowNetwork by drawerState.isShowNetwork.collectAsState()
    val isShowSync by drawerState.isShowSync.collectAsState()
    val accountHeader = rememberProAccountHeader()
    val showSignInPrompt = rememberAppDrawerSignInPrompt(enabled = PRO_AVAILABLE && !isProAuthenticated())
    val bookmarkUiState = rememberAppDrawerBookmarkUiState()
    val deviceUiState = if (isShowDevice) rememberAppDrawerDeviceUiState() else null
    val webRtcUiState = if (isShowWebRtc) rememberAppDrawerWebRtcUiState() else null
    val shareUiState = rememberAppDrawerShareUiState()
    val fileShareUiState = rememberAppDrawerFileShareUiState()
    val networkUiState = if (isShowNetwork) rememberAppDrawerNetworkUiState() else null
    val syncUiState = if (isShowSync) rememberAppDrawerSyncUiState() else null

    val scope = rememberCoroutineScope()
    val tasks = remember(taskState.revision) { taskState.tasks.toList() }
    var checkedTask by remember { mutableStateOf<Task?>(null) }
    LaunchedEffect(tasks, checkedTask?.key) {
        val checkedTaskKey = checkedTask?.key ?: return@LaunchedEffect
        if (tasks.none { task -> task.key == checkedTaskKey }) {
            checkedTask = null
        }
    }
    val revision = notificationState.revision
    val accountUnreadCount = proNotificationUnreadCount()
    val localUnreadCount = remember(revision) {
        notificationState.notifications.count { item -> !item.isRead && item.shouldShowInBell() }
    }
    val unreadCount = notificationBellUnreadCount(
        localUnread = localUnreadCount,
        accountUnread = accountUnreadCount,
    )
    val clipboardOpenState = rememberClipboardOpenState(
        fileState,
        homeState,
        clipboardUrlDownloadCoordinator,
        clipboardUrlShareInspector,
    )
    val clipboardFilePasteController = rememberClipboardFilePasteController(fileState, fileOperationState)
    fun navigateToAccount() {
        appDrawerAccountHeaderClickRoute()?.let(mainState::pushScreen)
    }

    ModalDrawerSheet {
        TopAppBar(
            title = {
                AppDrawerAccountTitle(
                    header = accountHeader,
                    showSignInPrompt = showSignInPrompt,
                    onClick = if (PRO_AVAILABLE) ::navigateToAccount else null,
                )
            },
            navigationIcon = {
                if (PRO_AVAILABLE) IconButton(onClick = ::navigateToAccount) {
                    AppDrawerAccountNavigationIcon(
                        header = accountHeader,
                        showSignInPrompt = showSignInPrompt,
                    )
                } else {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { PlatformType.DeviceIcon() }
                }
            },
            actions = {
                IconButton({
                    mainState.pushScreen(NotificationScreen())
                }) {
                    BadgedBox(badge = {
                        if (unreadCount > 0) {
                            Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) }
                        }
                    }) {
                        Icon(Icons.Default.Notifications, null)
                    }
                }

                MoreOptionsDropdown(
                    onOpenFromClipboard = {
                        clipboardFilePasteController.openFromClipboard { content ->
                            clipboardOpenState.dispatchContent(content, ClipboardTextEntryMode.Open)
                        }
                    },
                    onOpenFeedback = if (PRO_AVAILABLE) { { proFeedbackScreen()?.let(mainState::pushScreen) } } else null,
                    onOpenSettings = { mainState.pushScreen(SettingsScreen()) },
                    onExit = ::exitApp,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )
        HorizontalDivider()
        LazyColumn {
            item(
                key = "drawer_toolbox",
                contentType = "drawer_toolbox_row",
            ) {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text(AppStrings.ui_toolbox) },
                    selected = false,
                    shape = RoundedCornerShape(0.dp),
                    onClick = { mainState.pushScreen(ToolboxScreen()) },
                    badge = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }
            item(
                key = "drawer_toolbox_divider",
                contentType = "drawer_divider",
            ) {
                HorizontalDivider()
            }
            if (taskState.tasks.isNotEmpty()) {
                item(
                    key = "drawer_task_group",
                    contentType = "drawer_task_group",
                ) {
                    AppDrawerTask(
                        tasks = tasks,
                        onOpenTaskList = { mainState.pushScreen(TaskListScreen) },
                        onTaskClick = { task -> checkedTask = task },
                    )
                }
                item(
                    key = "drawer_task_divider",
                    contentType = "drawer_divider",
                ) {
                    HorizontalDivider()
                }
            }
            appDrawerBookmark(bookmarkUiState)
            item(
                key = "drawer_bookmark_divider",
                contentType = "drawer_divider",
            ) {
                HorizontalDivider()
            }
            if (isShowDevice) {
                deviceUiState?.let { state -> appDrawerDevice(state) }
                item(
                    key = "drawer_device_divider",
                    contentType = "drawer_divider",
                ) {
                    HorizontalDivider()
                }
            }
            if (isShowWebRtc) {
                webRtcUiState?.let { state -> appDrawerWebRtc(state) }
                item(
                    key = "drawer_webrtc_divider",
                    contentType = "drawer_divider",
                ) {
                    HorizontalDivider()
                }
            }
            // 展示文件共享服务状态
            item(
                key = "drawer_file_share_group",
                contentType = "drawer_file_share_group",
            ) {
                AppDrawerFileShare(fileShareUiState)
            }
            appDrawerShare(shareUiState)
            if (isShowNetwork) {
                networkUiState?.let { state -> appDrawerNetwork(state) }
            }
            if (isShowNetwork || isShowSync) {
                item(
                    key = "drawer_network_sync_divider",
                    contentType = "drawer_divider",
                ) {
                    HorizontalDivider()
                }
            }
            if (isShowSync) {
                syncUiState?.let { state -> appDrawerSync(state) }
            }
        }
    }

    deviceUiState?.let { state -> AppDrawerDeviceDialog(state) }
    networkUiState?.let { state -> AppDrawerNetworkDialog(state) }

    checkedTask?.let { task ->
        TaskInfoDialogContainer(
            task = task,
            onDismiss = { checkedTask = null },
            onToResult = {
                mainState.pushScreen(TaskResultScreen(task))
                checkedTask = null
            },
        )
    }

    if (isDeviceAdd) {
        TextFieldDialog(
            AppStrings.ui_add_device, label = AppStrings.ui_ip_address_ip_address_port,
            initText = localIpAddressState.subnetOptions.items.firstOrNull().orEmpty(),
            optionsUiState = localIpAddressState.subnetOptions,
            verifyFun = { text ->
                if (text.isEmpty()) {
                    Pair(true, AppStrings.ui_please_enter_ip_address_ip_address_port)
                } else {
                    if (!localIpAddressState.isLoaded) {
                        Pair(true, AppStrings.ui_reading_local_address_please_wait)
                    } else if (localIpAddressState.addresses.any { item -> text.indexOf(item) == 0 }) {
                        Pair(true, AppStrings.ui_local_addresses_prohibited)
                    } else {
                        val regex = Regex(
                            """^(([0-9]{1,3}\.){3}[0-9]{1,3}|\[([a-fA-F0-9:]+)\])(:([0-9]{1,5}))?$"""
                        )
                        if (regex.matches(text)) {
                            Pair(false, "")
                        } else {
                            Pair(true, AppStrings.ui_please_enter_correct_ip_address_ip_address_port)
                        }
                    }
                }
            }
        ) { item ->
            if (item.isEmpty()) {
                deviceState.updateDeviceAdd(false)
                return@TextFieldDialog
            }

            val ip: String
            var port: String? = null

            if (item.startsWith("[")) {
                val rightBracketIndex = item.indexOf(']')
                if (rightBracketIndex != -1) {
                    // 截取完整的 IPv6 地址部分，如 [2001:db8::1]
                    ip = item.take(rightBracketIndex + 1)
                    // 检查是否存在端口部分
                    if (rightBracketIndex + 1 < item.length && item[rightBracketIndex + 1] == ':') {
                        port = item.substring(rightBracketIndex + 2)
                    }
                } else {
                    // 若没有找到 ']'，则默认为整串都是 IP；也可根据需要进行异常处理
                    ip = item
                }
            } else {
                // 普通情况（如 IPv4 或不带中括号的 IPv6），用冒号分割
                val parts = item.split(":")
                ip = parts[0]
                if (parts.size > 1) {
                    port = parts[1]
                }
            }

            deviceState.updateDeviceAdd(false)

            scope.launch(Dispatchers.Default) {
                try {
                    deviceState.connectDeviceByAddress(
                        ip = ip,
                        port = (port ?: SettingsUtils.fileShare.getPort()).toString().toInt(),
                    )
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                }
            }
        }
    }

    if (clipboardOpenState.showDialog) {
        ClipboardPathDialog(
            uiState = clipboardOpenState.dialogUiState,
            onSelect = clipboardOpenState::selectCandidate,
            onDismiss = clipboardOpenState::dismissDialog
        )
    }

    if (clipboardOpenState.showAlreadyRunningDialog) {
        AlertDialog(
            onDismissRequest = clipboardOpenState::dismissAlreadyRunningDialog,
            title = { Text(AppStrings.ui_clipboard_url_download_title) },
            text = { Text(AppStrings.ui_clipboard_url_download_already_running) },
            confirmButton = {
                TextButton(onClick = clipboardOpenState::dismissAlreadyRunningDialog) {
                    Text(AppStrings.ui_close)
                }
            },
        )
    }

    ClipboardUrlDownloadDialogHost(
        state = clipboardUrlDownloadState,
        coordinator = clipboardUrlDownloadCoordinator,
    )
}

private data class LocalIpAddressState(
    val isLoaded: Boolean = false,
    val addresses: List<String> = emptyList(),
    val subnetOptions: StringListUiState = StringListUiState(),
)

data class AppDrawerAccountHeader(
    val title: String,
    val subtitle: String? = null,
    val avatarLabel: String? = null,
    val avatarUrl: String? = null,
)

internal const val APP_DRAWER_SIGN_IN_PROMPT_INTERVAL_MS = 2_500L
@Composable
internal fun rememberAppDrawerSignInPrompt(enabled: Boolean): Boolean {
    var showPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            showPrompt = false
            return@LaunchedEffect
        }
        showPrompt = false
        while (true) {
            delay(APP_DRAWER_SIGN_IN_PROMPT_INTERVAL_MS)
            showPrompt = !showPrompt
        }
    }
    return enabled && showPrompt
}

@Composable
internal fun AppDrawerAccountTitle(
    header: AppDrawerAccountHeader,
    onClick: (() -> Unit)?,
    showSignInPrompt: Boolean = false,
) {
    val modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    if (header.subtitle == null) {
        AppDrawerSignInPromptSwitcher(
            showSignInPrompt = showSignInPrompt,
            modifier = modifier.clipToBounds(),
            contentAlignment = Alignment.CenterStart,
            prompt = {
                Text(
                    text = AppStrings.ui_tap_to_sign_in,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            idle = {
                Text(
                    text = header.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        return
    }

    Column(modifier = modifier) {
        Text(
            text = header.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = header.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun AppDrawerSignInPromptSwitcher(
    showSignInPrompt: Boolean,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.CenterStart,
    prompt: @Composable () -> Unit,
    idle: @Composable () -> Unit,
) {
    var displayedPrompt by remember { mutableStateOf(showSignInPrompt) }
    var slideDirection by remember { mutableFloatStateOf(0f) }
    val transitionProgress = remember { Animatable(1f) }
    val slideDistance = with(LocalDensity.current) { 8.dp.toPx() }
    val motionScheme = MaterialTheme.motionScheme

    LaunchedEffect(showSignInPrompt, motionScheme) {
        if (displayedPrompt == showSignInPrompt) return@LaunchedEffect

        slideDirection = if (showSignInPrompt) -1f else 1f
        transitionProgress.animateTo(
            targetValue = 0f,
            animationSpec = motionScheme.fastEffectsSpec(),
        )
        displayedPrompt = showSignInPrompt
        slideDirection = if (showSignInPrompt) 1f else -1f
        transitionProgress.snapTo(0f)
        transitionProgress.animateTo(
            targetValue = 1f,
            animationSpec = motionScheme.defaultEffectsSpec(),
        )
    }

    Box(
        modifier = modifier.graphicsLayer {
            val progress = transitionProgress.value.coerceIn(0f, 1f)
            alpha = progress
            translationY = slideDirection * (1f - progress) * slideDistance
        },
        contentAlignment = contentAlignment,
    ) {
        if (displayedPrompt) prompt() else idle()
    }
}

@Composable
internal fun AppDrawerAccountNavigationIcon(
    header: AppDrawerAccountHeader,
    showSignInPrompt: Boolean = false,
) {
    val label = header.avatarLabel
    if (label == null) {
        AppDrawerSignInPromptSwitcher(
            showSignInPrompt = showSignInPrompt,
            contentAlignment = Alignment.Center,
            prompt = {
                Text(
                    text = AppStrings.ui_login,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            },
            idle = {
                PlatformType.DeviceIcon()
            },
        )
        return
    }

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .size(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        val avatarUrl = header.avatarUrl
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (avatarUrl != null) {
            Image(
                painter = rememberImagePainter(avatarUrl),
                contentDescription = AppStrings.ui_user_avatar,
                modifier = Modifier
                    .clip(CircleShape)
                    .size(36.dp),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
internal fun MoreOptionsDropdown(
    onOpenFromClipboard: () -> Unit,
    onOpenFeedback: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
) {
    Box {
        var showDropdownMenu by remember { mutableStateOf(false) }

        IconButton(onClick = { showDropdownMenu = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = AppStrings.ui_more_options)
        }

        DropdownMenu(
            expanded = showDropdownMenu,
            onDismissRequest = { showDropdownMenu = false }
        ) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                text = { Text(AppStrings.ui_open_clipboard) },
                onClick = {
                    onOpenFromClipboard()
                    showDropdownMenu = false
                }
            )
            if (onOpenFeedback != null) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Feedback, null) },
                    text = { Text(AppStrings.ui_feedback_and_suggestions) },
                    onClick = {
                        onOpenFeedback()
                        showDropdownMenu = false
                    }
                )
            }
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.Settings, null) },
                text = { Text(AppStrings.settings_title) },
                onClick = {
                    onOpenSettings()
                    showDropdownMenu = false
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) },
                text = { Text(AppStrings.ui_exit_software) },
                onClick = {
                    showDropdownMenu = false
                    onExit()
                }
            )
        }
    }
}

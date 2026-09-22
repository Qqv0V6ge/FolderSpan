package com.folderspan.ui.screen.file.share

import strings.AppStrings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.folderspan.PlatformType
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.toIcon
import com.folderspan.data.main.device.Device
import com.folderspan.extensions.DeviceIcon
import com.folderspan.extensions.formatFileSize
import com.folderspan.extensions.timestampToSyncDate
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.ConnectType.*
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.server.HttpShareFileServer
import com.folderspan.service.http.server.SocketClientIPEnum
import com.folderspan.service.http.server.getAllIPAddresses
import com.folderspan.service.session.usesTriggeredDeviceDiscovery
import com.folderspan.share.SystemShareItem
import com.folderspan.share.shareSystemItems
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.buttons.IpsButton
import com.folderspan.ui.components.dialog.FileShareDeviceLogDialog
import com.folderspan.ui.components.dialog.FileShareMergeDialog
import com.folderspan.ui.components.dialog.FileShareQrCodeDialog
import com.folderspan.ui.components.dialog.FileShareSelectFilesDialog
import com.folderspan.ui.components.drawer.AppDrawerHeader
import com.folderspan.ui.components.file.FileIcon
import com.folderspan.ui.components.fileshare.FileShareLinkCardContainer
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.model.FileSelectionUiState
import com.folderspan.ui.components.model.StringListUiState
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.state.file.FileShareLikeCategory
import com.folderspan.ui.state.file.FileShareLikeCategory.*
import com.folderspan.ui.state.file.ShareListDropReceiver
import com.folderspan.ui.state.file.ShareListDropRegistry
import com.folderspan.ui.state.file.ShareListDropResourceRegistry
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.FileShareStatus
import com.folderspan.ui.state.file.FileShareType
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.state.settings.SettingsState
import com.folderspan.utils.DeviceRequestLogUtils
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.WindowSizeClass
import com.folderspan.utils.calculateGridColumnCount
import com.folderspan.utils.calculateWindowSizeClass
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.seiko.imageloader.model.ImageRequest
import kotlinx.coroutines.*
import org.koin.compose.koinInject

object FileShareScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow

        val scope = rememberCoroutineScope()

        val deviceState = koinInject<DeviceState>()
        val triggeredDeviceDiscovery = PlatformType.usesTriggeredDeviceDiscovery()

        val fileShareState = koinInject<FileShareState>()
        val settingsState = koinInject<SettingsState>()
        val shareListDropReceiver: ShareListDropReceiver = remember(fileShareState) {
            { droppedFiles -> fileShareState.updateIncomingFiles(droppedFiles) }
        }
        DisposableEffect(shareListDropReceiver) {
            ShareListDropRegistry.register(shareListDropReceiver)
            onDispose {
                ShareListDropRegistry.unregister(shareListDropReceiver)
                ShareListDropResourceRegistry.retainReferencedPaths(
                    fileShareState.files.map { item -> item.path }
                )
            }
        }
        var localIpAddresses by remember { mutableStateOf<List<String>>(emptyList()) }
        var pendingIncomingFiles by remember { mutableStateOf<List<FileSimpleInfo>?>(null) }
        var showFileMergeDialog by remember { mutableStateOf(false) }

        fun clearDeviceAccessLog(device: Device) {
            val log = fileShareState.deviceRequestLog.remove(device.id) ?: return
            scope.launch(Dispatchers.Default) {
                log.clear()
            }
        }

        val files = fileShareState.files
        val checkedFiles = fileShareState.checkedFiles
        val autoUpdateLinkShareFiles by
            settingsState.easyFileShareAutoUpdateLinkShareFiles.collectAsState()
        val autoUpdateDeviceShareFiles by
            settingsState.easyFileShareAutoUpdateDeviceShareFiles.collectAsState()
        val currentShareFiles = files.toList()
        var previousShareFiles by remember { mutableStateOf<List<FileSimpleInfo>?>(null) }

        val snackbarHostState = remember { SnackbarHostState() }
        val sheetSnackbarHostState = remember { SnackbarHostState() }

        fun synchronizeCurrentShareFiles() {
            val fileSnapshot = fileShareState.files.toList()
            if (autoUpdateLinkShareFiles) {
                fileShareState.syncAuthorizedLinkShareFiles(fileSnapshot)
            }
            if (autoUpdateDeviceShareFiles) {
                fileShareState.syncShareToDeviceFiles(fileSnapshot)
            }
        }

        LaunchedEffect(
            currentShareFiles,
            autoUpdateLinkShareFiles,
            autoUpdateDeviceShareFiles
        ) {
            val shareListChanged =
                previousShareFiles != null && previousShareFiles != currentShareFiles

            ShareListDropResourceRegistry.retainReferencedPaths(
                buildList {
                    addAll(currentShareFiles.map { item -> item.path })
                    addAll(fileShareState.incomingFiles.map { item -> item.path })
                    addAll(pendingIncomingFiles.orEmpty().map { item -> item.path })
                }
            )
            synchronizeCurrentShareFiles()

            val hasUnsynchronizedDevices = shareListChanged && (
                (!autoUpdateLinkShareFiles && fileShareState.authorizedLinkShareDevices.isNotEmpty()) ||
                    (!autoUpdateDeviceShareFiles && fileShareState.shareToDevices.isNotEmpty())
                )
            previousShareFiles = currentShareFiles
            if (hasUnsynchronizedDevices) {
                snackbarHostState.showLatestSnackbar(
                    message = AppStrings.ui_share_list_updated_auto_update_disabled_devices_keep_previous_files,
                    duration = SnackbarDuration.Long
                )
            }
        }

        val socketDevices = deviceState.socketDevices.sortedBy { item -> item.httpClient != null }

        /* 链接方式分享 */
        var isExpandLinkShare by remember { mutableStateOf(true) }

        // 全部、反选
        var selectFileType by remember { mutableStateOf(-1) }
        // 是否允许访问隐藏文件和文件夹
        var isHideFile by remember { mutableStateOf(false) }
        // 是否允许当前链接分享设备上传文件和文件夹
        var isAllowUpload by remember { mutableStateOf(false) }

        // 等待、允许、拒绝
        var category by remember { mutableStateOf(WAITING) }

        // 服务卡片
        var openQrCodeDialog by remember { mutableStateOf<Pair<String, ImageRequest>?>(null) }
        val httpShareFileServer = HttpShareFileServer.getInstance(fileShareState)
        var curLinkDevice by remember { mutableStateOf<Device?>(null) }
        var showDeviceLogDialog by remember { mutableStateOf(false) }
        var selectedDeviceForLog by remember { mutableStateOf<Device?>(null) }

        LaunchedEffect(fileShareState.incomingFiles) {
            val incomingFiles = fileShareState.incomingFiles
            if (incomingFiles.isEmpty()) {
                return@LaunchedEffect
            }
            val existingFiles = fileShareState.files.toList()
            when {
                existingFiles.isEmpty() -> {
                    fileShareState.files.addAll(incomingFiles)
                    fileShareState.checkedFiles.apply {
                        clear()
                        addAll(incomingFiles)
                    }
                    pendingIncomingFiles = null
                    showFileMergeDialog = false
                }

                shouldShowFileShareMergeDialog(existingFiles, incomingFiles) -> {
                    pendingIncomingFiles = incomingFiles
                    showFileMergeDialog = true
                }
            }
            fileShareState.clearIncomingFiles()
        }


        /* 分享到其他设备 */
        val loadingDevices by deviceState.loadingDevices.collectAsState()

        val infiniteTransition = rememberInfiniteTransition()
        val rotation by infiniteTransition.animateFloat(
            initialValue = 360f,
            targetValue = if (loadingDevices) 0f else 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 1500,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            )
        )
        val scannerScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (loadingDevices) 1.35f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 650,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            )
        )
        var curDevice by remember { mutableStateOf<SocketDevice?>(null) }


        val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
        var showBottomSheet by remember { mutableStateOf(false) }
        var fileShareType by remember { mutableStateOf(FileShareType.NONE) }
        var showFileSelector by remember { mutableStateOf(false) }
        val fileSelectorSelection = remember { mutableStateListOf<FileSimpleInfo>() }

        fun refreshSelectionState() {
            selectFileType = when {
                fileShareState.files.isEmpty() -> -1
                fileShareState.checkedFiles.size == fileShareState.files.size && fileShareState.files.isNotEmpty() -> 0
                else -> -1
            }
        }

        fun toggleFileSelection(file: FileSimpleInfo) {
            if (!checkedFiles.remove(file)) {
                checkedFiles.add(file)
            }
            refreshSelectionState()
        }

        fun requestFileRemoval(filesToRemove: List<FileSimpleInfo>) {
            val removalSnapshot = filesToRemove.distinct().filter { file -> file in files }
            if (removalSnapshot.isEmpty()) return

            scope.launch {
                val result = sheetSnackbarHostState.showLatestSnackbar(
                    message = AppStrings.ui_delete_selected_arg0_items.format(
                        arg0 = removalSnapshot.size.toString()
                    ),
                    actionLabel = AppStrings.ui_remove,
                    withDismissAction = true,
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    files.removeAll(removalSnapshot.toSet())
                    checkedFiles.removeAll(removalSnapshot.toSet())
                    refreshSelectionState()
                }
            }
        }

        LaunchedEffect(Unit) {
            isHideFile = SettingsUtils.easyFileShare.getHideFile()
            fileShareState.updateAllowUpload(SettingsUtils.easyFileShare.getAllowUpload())
            // 清除所有设备的请求日志
            DeviceRequestLogUtils.clearAllLogs()
            fileShareState.deviceRequestLog.clear()
        }

        LaunchedEffect(Unit) {
            val addresses = withContext(Dispatchers.Default) {
                getAllIPAddresses(type = SocketClientIPEnum.IPV4_UP)
            }
            localIpAddresses = addresses
            if (triggeredDeviceDiscovery) {
                withContext(Dispatchers.Default) {
                    deviceState.scanner(
                        addresses,
                        SettingsUtils.fileShare.getPort(),
                    )
                }
            }
        }

        fun launchFileSelector() {
            curLinkDevice = null
            curDevice = null
            fileShareType = FileShareType.NONE
            fileSelectorSelection.clear()
            showFileSelector = true
        }

        fun resolveSystemShareItems(): List<SystemShareItem> {
            if (checkedFiles.isEmpty()) return emptyList()
            return checkedFiles.filter { item -> item.path.isNotBlank() }.map { file ->
                SystemShareItem(
                    path = file.path,
                    displayName = file.name,
                    mimeType = file.mineType,
                    isDirectory = file.isDirectory
                )
            }
        }

        fun stopServiceAndExit() {
            scope.launch {
                val closingSnackbar = launch {
                    snackbarHostState.showLatestSnackbar(
                        message = AppStrings.ui_service_shutting_down,
                        duration = SnackbarDuration.Indefinite
                    )
                }
                try {
                    yield()
                    withContext(Dispatchers.Default) {
                        httpShareFileServer.stop()
                    }
                    fileShareState.clearLinkShareRuntimeAuthorizations()
                    navigator.pop()
                } finally {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    closingSnackbar.cancelAndJoin()
                }
            }
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_share) },
                    navigationIcon = {
                        IconButton({
                            if (!httpShareFileServer.isRunning()) {
                                navigator.pop()
                                return@IconButton
                            }
                            if (SettingsUtils.easyFileShare.getAutoStopOnExit()) {
                                stopServiceAndExit()
                                return@IconButton
                            }
                            scope.launch {
                                when (snackbarHostState.showLatestSnackbar(
                                    message = AppStrings.ui_you_sure_you_want_turn_off_file_sharing_service,
                                    actionLabel = AppStrings.ui_confirm,
                                    withDismissAction = true,
                                    duration = SnackbarDuration.Indefinite,
                                )) {
                                    SnackbarResult.ActionPerformed -> stopServiceAndExit()
                                    SnackbarResult.Dismissed -> navigator.pop()
                                }
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, null)
                        }
                    },
                    actions = {
                        BadgedBox({
                            if (showBottomSheet) return@BadgedBox
                            Badge { Text("${if (files.size > 100) "99+" else files.size}") }
                        }) {
                            IconButton({
                                showBottomSheet = !showBottomSheet
                                curLinkDevice = null
                                curDevice = null
                                fileShareType = FileShareType.NONE
                                checkedFiles.clear()
                                refreshSelectionState()
                            }) {
                                Icon(if (!showBottomSheet) Icons.Default.Description else Icons.Default.Close, null)
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { launchFileSelector() },
                    content = { Icon(Icons.Default.Add, contentDescription = null) },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(paddingValues)) {
                val isCompact = calculateWindowSizeClass(maxWidth, maxHeight) == WindowSizeClass.Compact
                val listContentPadding = PaddingValues(bottom = GridListFabPadding)
                val deviceStatusList = when (category) {
                    WAITING -> fileShareState.pendingLinkShareDevices
                    RUNNING -> fileShareState.authorizedLinkShareDevices.keys
                    REJECTED -> fileShareState.rejectedLinkShareDevices
                }.toList().distinctBy { device -> device.id }

                fun LazyGridScope.fileShareLinkShareItems(columnCount: Int) {
                    item(span = { GridItemSpan(columnCount) }) {
                        Column {
                            AppDrawerHeader(
                                title = AppStrings.ui_share_link,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
                                actions = {
                                    Icon(
                                        if (isExpandLinkShare) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        null,
                                        Modifier.clip(RoundedCornerShape(25.dp))
                                            .clickable { isExpandLinkShare = !isExpandLinkShare }
                                    )
                                })
                            AnimatedVisibility(isExpandLinkShare) {
                                FileShareLinkCardContainer(
                                    fileShareState = fileShareState,
                                    httpShareFileServer = httpShareFileServer,
                                    ipAddresses = localIpAddresses,
                                    onClickOpenQRCode = { item -> openQrCodeDialog = item },
                                    onShowMessage = { message ->
                                        scope.launch {
                                            snackbarHostState.showLatestSnackbar(message)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    item(span = { GridItemSpan(columnCount) }) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.padding(16.dp),
                        ) {
                            listOf(
                                WAITING to AppStrings.ui_wait,
                                RUNNING to AppStrings.ui_allow,
                                REJECTED to AppStrings.ui_reject,
                            ).forEachIndexed { index, (cat, label) ->
                                SegmentedButton(
                                    selected = category == cat,
                                    onClick = { category = cat },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                                ) {
                                    val deviceCount = when (cat) {
                                        WAITING -> fileShareState.pendingLinkShareDevices.distinctBy { device -> device.id }.size
                                        RUNNING -> fileShareState.authorizedLinkShareDevices.keys.distinctBy { device -> device.id }.size
                                        REJECTED -> fileShareState.rejectedLinkShareDevices.distinctBy { device -> device.id }.size
                                    }
                                    Text("$label($deviceCount)")
                                }
                            }
                        }
                    }

                    items(
                        items = deviceStatusList,
                        key = { device -> device.id }
                    ) { device ->
                        val authorizedAccess = fileShareState.getAuthorizedLinkShareDevice(device.id)
                        val isUploadPending = fileShareState.pendingLinkShareUploadDevices
                            .any { item -> item.id == device.id }
                        val isUploadRejected = fileShareState.rejectedLinkShareUploadDevices
                            .any { item -> item.id == device.id }
                        val uploadStatus = if (category == RUNNING) {
                            when {
                                authorizedAccess?.allowUpload == true -> AppStrings.ui_allow_upload
                                isUploadPending -> AppStrings.ui_request_upload
                                isUploadRejected -> AppStrings.ui_upload_rejected
                                else -> AppStrings.ui_upload_not_allowed
                            }
                        } else {
                            null
                        }
                        LinkShareDeviceStatusListItem(
                            category = category,
                            deviceName = device.name,
                            uploadStatus = uploadStatus,
                            onShowDeviceLog = {
                                showDeviceLogDialog = true
                                selectedDeviceForLog = device
                            },
                            onApprove = {
                                fileShareState.approveLinkShareDevice(device.id)
                            },
                            onRejectOrRemove = {
                                when (category) {
                                    WAITING -> {
                                        fileShareState.rejectLinkShareDevice(device.id)
                                    }

                                    RUNNING -> {
                                        val removedAuthorized = fileShareState.rejectAuthorizedLinkShareDevice(device)
                                        if (removedAuthorized) {
                                            clearDeviceAccessLog(device)
                                        }
                                    }

                                    REJECTED -> fileShareState.removeRejectedLinkShareDevice(device.id)
                                }
                            },
                            onClick = {
                                if (category == RUNNING) {
                                    curLinkDevice = device
                                    fileShareType = FileShareType.LINK

                                    val deviceFileShareInfo =
                                        fileShareState.getAuthorizedLinkShareDevice(device.id)
                                    if (deviceFileShareInfo != null) {
                                        isHideFile = deviceFileShareInfo.allowHidden
                                        isAllowUpload = deviceFileShareInfo.allowUpload
                                        fileShareState.checkedFiles.apply {
                                            clear()
                                            addAll(deviceFileShareInfo.files)
                                        }

                                        showBottomSheet = true
                                    }
                                }
                            }
                        )
                    }
                }

                fun LazyGridScope.fileShareOtherDeviceItems(columnCount: Int, showSectionDivider: Boolean) {
                    item(span = { GridItemSpan(columnCount) }) {
                        Column {
                            if (showSectionDivider) {
                                if (deviceStatusList.isEmpty()) {
                                    Spacer(Modifier.height(16.dp))
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                Spacer(Modifier.height(16.dp))
                            }
                            AppDrawerHeader(
                                title = AppStrings.ui_share_other_devices,
                                modifier = if (showSectionDivider) null else Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    bottom = 12.dp,
                                ),
                            ) {
                                Row {
                                    Icon(
                                        Icons.Default.Add,
                                        null,
                                        Modifier.clip(RoundedCornerShape(25.dp)).clickable {
                                            deviceState.updateDeviceAdd(true)
                                        }
                                    )
                                    if (triggeredDeviceDiscovery) {
                                        Spacer(Modifier.width(8.dp))
                                        Icon(
                                            if (loadingDevices) Icons.Default.Pause else Icons.Default.Sync,
                                            if (loadingDevices) AppStrings.ui_pause_scanning else AppStrings.ui_scanning_device,
                                            Modifier
                                                .clip(RoundedCornerShape(25.dp))
                                                .graphicsLayer {
                                                    rotationZ = if (loadingDevices) 0f else rotation
                                                    scaleX = scannerScale
                                                    scaleY = scannerScale
                                                }
                                                .clickable {
                                                    if (loadingDevices) {
                                                        deviceState.pauseScanner()
                                                        return@clickable
                                                    }
                                                    scope.launch {
                                                        val addresses = withContext(Dispatchers.Default) {
                                                            getAllIPAddresses(type = SocketClientIPEnum.IPV4_UP)
                                                        }
                                                        localIpAddresses = addresses
                                                        withContext(Dispatchers.Default) {
                                                            deviceState.scanner(
                                                                addresses,
                                                                SettingsUtils.fileShare.getPort(),
                                                                resumeIfPaused = true
                                                            )
                                                        }
                                                    }
                                                }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        IpsButton(
                                            ipAddresses = localIpAddresses,
                                            isScanning = loadingDevices,
                                            onIpClick = { ipAddress ->
                                                scope.launch(Dispatchers.Default) {
                                                    deviceState.scanner(
                                                        listOf(ipAddress),
                                                        SettingsUtils.fileShare.getPort(),
                                                        resumeIfPaused = true,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        Icons.Default.Settings,
                                        null,
                                        Modifier.clickable {
                                            navigator.push(FileShareManageScreen())
                                        }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        Icons.Default.History,
                                        null,
                                        Modifier.clickable {
                                            navigator.push(FileShareHistoryScreen())
                                        }
                                    )
                                }
                            }
                        }
                    }
                    items(
                        items = socketDevices,
                        key = { device -> "${device.transportType}:${device.id}" },
                        contentType = { "share-device" },
                    ) { device ->
                        ShareToDeviceListItem(
                            device = device,
                            sendStatus = fileShareState.sendFile[device.id],
                            sendMessage = fileShareState.sendFileMessage[device.id],
                            onCancel = {
                                deviceState.cancelShare(device.id)
                            },
                            onClick = {
                                if (fileShareState.sendFile[device.id] == FileShareStatus.WAITING) {
                                    return@ShareToDeviceListItem
                                }

                                if (SettingsUtils.easyFileShare.getTapToSendOnDevice()) {
                                    val hideFilePreference = SettingsUtils.easyFileShare.getDeviceHideFile()
                                    val selectedFiles = fileShareState.files.toList()
                                    if (selectedFiles.isEmpty()) {
                                        scope.launch {
                                            snackbarHostState.showLatestSnackbar(AppStrings.ui_please_select_file_you_want_share_first)
                                        }
                                        return@ShareToDeviceListItem
                                    }
                                    fileShareState.shareToDevices[device.id] =
                                        Pair(hideFilePreference, selectedFiles)
                                    if (
                                        fileShareState.sendFile[device.id] != FileShareStatus.COMPLETED &&
                                        fileShareState.sendFile[device.id] != FileShareStatus.WAITING
                                    ) {
                                        deviceState.share(device)
                                        scope.launch {
                                            snackbarHostState.showLatestSnackbar(
                                                AppStrings.ui_sent_arg0_items.format(arg0 = (selectedFiles.size).toString())
                                            )
                                        }
                                    }
                                    return@ShareToDeviceListItem
                                }

                                curDevice = device
                                fileShareType = FileShareType.DEVICE

                                val deviceFileShareInfo =
                                    fileShareState.shareToDevices[device.id] ?: Pair(
                                        SettingsUtils.easyFileShare.getDeviceHideFile(),
                                        listOf()
                                    )
                                isHideFile = deviceFileShareInfo.first
                                fileShareState.checkedFiles.apply {
                                    clear()
                                    addAll(deviceFileShareInfo.second)
                                }

                                showBottomSheet = true
                            }
                        )
                    }
                }

                if (isCompact) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(1),
                        contentPadding = listContentPadding,
                    ) {
                        fileShareLinkShareItems(columnCount = 1)
                        fileShareOtherDeviceItems(columnCount = 1, showSectionDivider = true)
                    }
                } else {
                    val linkSharePaneWidth = 360.dp
                    val deviceColumnCount = calculateGridColumnCount(maxWidth - linkSharePaneWidth, maxHeight)
                    Row(Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            modifier = Modifier.width(linkSharePaneWidth).fillMaxHeight(),
                            columns = GridCells.Fixed(1),
                            contentPadding = listContentPadding,
                        ) {
                            fileShareLinkShareItems(columnCount = 1)
                        }
                        VerticalDivider()
                        LazyVerticalGrid(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            columns = GridCells.Fixed(deviceColumnCount),
                            contentPadding = listContentPadding,
                        ) {
                            fileShareOtherDeviceItems(
                                columnCount = deviceColumnCount,
                                showSectionDivider = false,
                            )
                        }
                    }
                }
            }

            val isFileShareReady =
                curLinkDevice != null || curDevice != null || fileShareType == FileShareType.SYSTEM

            if (showBottomSheet) {
                LaunchedEffect(showBottomSheet, curLinkDevice, curDevice, fileShareType) {
                    if (!showBottomSheet || !isFileShareReady) return@LaunchedEffect

                    val linkAccess = curLinkDevice?.let { device ->
                        fileShareState.getAuthorizedLinkShareDevice(device.id)
                    }
                    isHideFile = when (fileShareType) {
                        FileShareType.NONE -> false
                        FileShareType.LINK -> linkAccess?.allowHidden
                            ?: SettingsUtils.easyFileShare.getHideFile()

                        FileShareType.DEVICE -> fileShareState.shareToDevices[curDevice!!.id]?.first
                            ?: SettingsUtils.easyFileShare.getDeviceHideFile()

                        FileShareType.SYSTEM -> false
                    }
                    isAllowUpload = when (fileShareType) {
                        FileShareType.LINK -> linkAccess?.allowUpload ?: fileShareState.allowUpload.value
                        FileShareType.NONE,
                        FileShareType.DEVICE,
                        FileShareType.SYSTEM -> false
                    }

                    if (fileShareState.checkedFiles.isEmpty()) {
                        fileShareState.checkedFiles.addAll(files)
                    }

                    selectFileType = when (fileShareType) {
                        FileShareType.NONE -> -1
                        FileShareType.LINK -> {
                            val temp = linkAccess?.files ?: listOf()
                            if (temp.size == fileShareState.files.size) 0 else -1
                        }

                        FileShareType.DEVICE -> {
                            val temp = fileShareState.shareToDevices[curDevice!!.id]?.second ?: listOf()
                            if (temp.size == fileShareState.files.size) 0 else -1
                        }

                        FileShareType.SYSTEM -> {
                            if (fileShareState.checkedFiles.size == fileShareState.files.size) 0 else -1
                        }
                    }
                }

                ModalBottomSheet(
                    onDismissRequest = {
                        showBottomSheet = false
                        curLinkDevice = null
                        curDevice = null
                        fileShareType = FileShareType.NONE
                        showFileSelector = false
                        isAllowUpload = false
                        fileSelectorSelection.clear()
                        sheetSnackbarHostState.currentSnackbarData?.dismiss()
                    },
                    sheetState = sheetState
                ) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(Modifier.padding(bottom = 8.dp)) {
                            Column {
                                Text(AppStrings.ui_share_list_arg0.format(arg0 = (files.size).toString()))
                                if (isFileShareReady) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(AppStrings.ui_please_select_file_folder_you_want_share, style = typography.bodySmall)
                                }
                            }

                            Spacer(Modifier.weight(1f))

                            if (checkedFiles.isNotEmpty()) {
                                IconButton(
                                    onClick = { requestFileRemoval(checkedFiles.toList()) }
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = AppStrings.ui_remove_shared_files
                                    )
                                }
                            }

                            if (isFileShareReady) {
                                val canShareSystem = fileShareType != FileShareType.SYSTEM || checkedFiles.isNotEmpty()
                                Button(
                                    onClick = {
                                        when (fileShareType) {
                                            FileShareType.NONE -> {}
                                            FileShareType.LINK -> {
                                                val device = curLinkDevice ?: return@Button
                                                val hadUploadRequest = fileShareState.pendingLinkShareUploadDevices
                                                    .any { item -> item.id == device.id }
                                                fileShareState.removePendingLinkShareDevice(device.id)
                                                fileShareState.authorizeLinkShareDevice(
                                                    device = device,
                                                    allowHidden = isHideFile,
                                                    allowUpload = isAllowUpload,
                                                    files = fileShareState.checkedFiles.toList(),
                                                    clearUploadRequestState = false,
                                                )
                                                if (isAllowUpload) {
                                                    fileShareState.approveLinkShareUploadDevice(device.id)
                                                } else if (hadUploadRequest) {
                                                    fileShareState.rejectLinkShareUploadDevice(device.id)
                                                }
                                                fileShareState.updateLinkShareDefaults(
                                                    isHideFile,
                                                    fileShareState.checkedFiles.toList(),
                                                    allowUpload = fileShareState.allowUpload.value
                                                )
                                            }

                                            FileShareType.DEVICE -> {
                                                if (fileShareState.sendFile[curDevice!!.id] != FileShareStatus.COMPLETED && fileShareState.sendFile[curDevice!!.id] != FileShareStatus.WAITING) {
                                                    deviceState.share(curDevice!!)
                                                }
                                                fileShareState.shareToDevices[curDevice!!.id] =
                                                    Pair(
                                                        isHideFile,
                                                        fileShareState.checkedFiles.toList()
                                                    )
                                            }

                                            FileShareType.SYSTEM -> {
                                                val items = resolveSystemShareItems()
                                                if (items.isEmpty()) {
                                                    scope.launch {
                                                        snackbarHostState.showLatestSnackbar(AppStrings.ui_no_files_share)
                                                    }
                                                    return@Button
                                                }
                                                if (!shareSystemItems(items)) {
                                                    scope.launch {
                                                        snackbarHostState.showLatestSnackbar(AppStrings.ui_current_platform_does_not_support_system_sharing)
                                                    }
                                                    return@Button
                                                }
                                            }
                                        }

                                        fileShareState.checkedFiles.clear()
                                        showBottomSheet = false
                                        curLinkDevice = null
                                        curDevice = null
                                        fileShareType = FileShareType.NONE
                                        isAllowUpload = false
                                    },
                                    enabled = canShareSystem
                                ) {
                                    Text(
                                        if (fileShareType == FileShareType.SYSTEM) {
                                            AppStrings.ui_share
                                        } else {
                                            AppStrings.ui_complete
                                        },
                                    )
                                }
                            }
                        }

                        if (files.isNotEmpty()) {
                            val selectionButtons: @Composable () -> Unit = {
                                SingleChoiceSegmentedButtonRow {
                                    listOf(AppStrings.ui_select_all, AppStrings.ui_counter_election).forEachIndexed { index, label ->
                                        SegmentedButton(
                                            selected = selectFileType == index,
                                            onClick = {
                                                selectFileType = index
                                                if (index == 0) {
                                                    fileShareState.checkedFiles.apply {
                                                        clear()
                                                        addAll(files)
                                                    }
                                                }
                                                if (index == 1) {
                                                    for (file in files) {
                                                        if (checkedFiles.contains(file)) {
                                                            fileShareState.checkedFiles.remove(file)
                                                        } else {
                                                            fileShareState.checkedFiles.add(file)
                                                        }
                                                    }
                                                }
                                            },
                                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                                        ) { Text(label) }
                                    }
                                }
                            }

                            val optionChips: @Composable (Modifier) -> Unit = { modifier ->
                                if (isFileShareReady && fileShareType != FileShareType.SYSTEM) {
                                    Row(
                                        modifier = modifier,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        FilterChip(
                                            selected = isHideFile,
                                            onClick = { isHideFile = !isHideFile },
                                            label = { Text(AppStrings.ui_hidden_files) },
                                            leadingIcon = {
                                                if (!isHideFile) return@FilterChip
                                                Icon(Icons.Default.Done, contentDescription = null)
                                            },
                                            shape = RoundedCornerShape(50)
                                        )

                                        if (fileShareType == FileShareType.LINK) {
                                            FilterChip(
                                                selected = isAllowUpload,
                                                onClick = { isAllowUpload = !isAllowUpload },
                                                label = { Text(AppStrings.ui_allow_upload) },
                                                leadingIcon = {
                                                    if (!isAllowUpload) return@FilterChip
                                                    Icon(Icons.Default.Done, contentDescription = null)
                                                },
                                                shape = RoundedCornerShape(50)
                                            )
                                        }
                                    }
                                }
                            }

                            BoxWithConstraints(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                            ) {
                                if (maxWidth < 520.dp) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        selectionButtons()
                                        optionChips(Modifier.fillMaxWidth())
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        selectionButtons()
                                        optionChips(Modifier.align(Alignment.CenterVertically))
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.height(16.dp))
                        }

                    }

                    SnackbarHost(
                        hostState = sheetSnackbarHostState,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )

                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(
                            items = files,
                            key = { file -> "${file.protocol}:${file.protocolId}:${file.path}" }
                        ) { file ->
                            val sizeText = remember(file.size, file.isDirectory) {
                                if (file.isDirectory) AppStrings.ui_arg0_items.format(arg0 = (file.size).toString()) else file.size.formatFileSize()
                            }
                            val createdDateText = remember(file.createdDate) {
                                file.createdDate.timestampToSyncDate()
                            }
                            val isSelected = checkedFiles.contains(file)
                            ListItem(
                                headlineContent = { Text(file.name) },
                                supportingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        file.protocol.toIcon()

                                        Text(sizeText, style = typography.bodySmall)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            createdDateText,
                                            style = typography.bodySmall
                                        )
                                    }
                                },
                                leadingContent = {
                                    IconToggleButton(
                                        checked = isSelected,
                                        onCheckedChange = { toggleFileSelection(file) }
                                    ) {
                                        if (isSelected) {
                                            Icon(Icons.Default.CheckBox, contentDescription = null)
                                        } else {
                                            FileIcon(file)
                                        }
                                    }
                                },
                                trailingContent = {
                                    IconButton(
                                        onClick = { requestFileRemoval(listOf(file)) }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = AppStrings.ui_remove_shared_files)
                                    }
                                },
                                modifier = Modifier.clickable { toggleFileSelection(file) }
                            )
                        }
                    }
                }

            }

            if (showFileSelector) {
                FileShareSelectFilesDialog(
                    openPath = PathUtils.getHomePath(),
                    initialSelectionUiState = FileSelectionUiState(fileSelectorSelection.toList()),
                    onDismiss = {
                        showFileSelector = false
                        fileSelectorSelection.clear()
                    },
                    onConfirm = { selectionUiState ->
                        val selected = selectionUiState.files
                        if (selected.isNotEmpty()) {
                            val existingPaths = fileShareState.files.map { item -> item.path }.toSet()
                            val newFiles = selected.filterNot { item -> item.path in existingPaths }

                            if (newFiles.isNotEmpty()) {
                                fileShareState.files.addAll(newFiles)
                                newFiles.forEach { file ->
                                    if (!fileShareState.checkedFiles.contains(file)) {
                                        fileShareState.checkedFiles.add(file)
                                    }
                                }
                                refreshSelectionState()
                            }
                        }
                        fileSelectorSelection.clear()
                        showFileSelector = false
                    }
                )
            }

            if (showFileMergeDialog) {
                val existingCount = files.size
                val existingSet = files.toSet()
                val uniqueIncomingCount = pendingIncomingFiles.orEmpty().count { item -> !existingSet.contains(item) }
                FileShareMergeDialog(
                    existingCount = existingCount,
                    uniqueIncomingCount = uniqueIncomingCount,
                    onReplace = {
                        pendingIncomingFiles?.let { incoming ->
                            fileShareState.files.apply {
                                clear()
                                addAll(incoming)
                            }
                        }
                        fileShareState.checkedFiles.clear()
                        pendingIncomingFiles = null
                        showFileMergeDialog = false
                    },
                    onAppend = {
                        val incoming = pendingIncomingFiles.orEmpty()
                        if (incoming.isNotEmpty()) {
                            val existing = fileShareState.files.toSet()
                            val newItems = incoming.filterNot { item -> existing.contains(item) }
                            if (newItems.isNotEmpty()) {
                                fileShareState.files.addAll(newItems)
                            }
                        }
                        pendingIncomingFiles = null
                        showFileMergeDialog = false
                    },
                    onCancel = {
                        pendingIncomingFiles = null
                        showFileMergeDialog = false
                        ShareListDropResourceRegistry.retainReferencedPaths(
                            fileShareState.files.map { item -> item.path }
                        )
                    }
                )
            }

            if (openQrCodeDialog != null) {
                FileShareQrCodeDialog(
                    data = openQrCodeDialog!!,
                    onDismiss = { openQrCodeDialog = null }
                )
            }

            if (showDeviceLogDialog && selectedDeviceForLog != null) {
                val deviceLog = selectedDeviceForLog?.let { device ->
                    fileShareState.deviceRequestLog[device.id]
                }
                val paths by produceState(emptyList(), selectedDeviceForLog) {
                    value = deviceLog?.getAllPaths() ?: emptyList()
                }

                FileShareDeviceLogDialog(
                    device = selectedDeviceForLog!!,
                    pathsUiState = StringListUiState(paths),
                    onDismiss = { showDeviceLogDialog = false }
                )
            }
        }
    }

}

internal fun shouldShowFileShareMergeDialog(
    existingFiles: List<FileSimpleInfo>,
    incomingFiles: List<FileSimpleInfo>,
): Boolean {
    return !(existingFiles.isEmpty() || incomingFiles.isEmpty()) && existingFiles.toSet() != incomingFiles.toSet()
}

@Composable
internal fun ShareToDeviceListItem(
    device: SocketDevice,
    sendStatus: FileShareStatus?,
    sendMessage: String?,
    onCancel: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        overlineContent = {
            val statusLabel = shareToDeviceListItemStatusLabel(
                sendStatus = sendStatus,
                sendMessage = sendMessage,
                connectType = device.connectType,
            )
            when (sendStatus) {
                FileShareStatus.COMPLETED -> Badge(
                    containerColor = colorScheme.primary
                ) { Text(statusLabel) }

                FileShareStatus.WAITING,
                FileShareStatus.SENDING -> Badge(
                    containerColor = colorScheme.tertiary
                ) { Text(statusLabel) }

                FileShareStatus.REJECTED -> Badge(
                    containerColor = if (
                        sendMessage?.contains(AppStrings.ui_reject) == true ||
                        sendMessage?.contains(AppStrings.ui_reject) == true
                    ) {
                        colorScheme.error
                    } else {
                        colorScheme.tertiary
                    }
                ) { Text(statusLabel) }

                FileShareStatus.ERROR -> Badge { Text(statusLabel) }

                null -> when (device.connectType) {
                    Connect -> Badge(
                        containerColor = colorScheme.primary
                    ) { Text(statusLabel) }

                    Loading -> Badge(
                        containerColor = colorScheme.tertiary
                    ) { Text(statusLabel) }

                    Fail,
                    UnConnect,
                    New,
                    Rejected -> Badge { Text(statusLabel) }
                }
            }
        },
        headlineContent = {
            Text(device.name)
        },
        supportingContent = {
            Text(shareToDeviceListItemSupportingText(sendStatus, sendMessage))
        },
        trailingContent = {
            if (shareToDeviceListItemCanCancel(sendStatus)) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, null)
                }
            }
        },
        leadingContent = { device.type.DeviceIcon() },
    )
}

internal fun shareToDeviceListItemCanCancel(sendStatus: FileShareStatus?): Boolean =
    sendStatus != null

internal fun shareToDeviceListItemConnectTypeLabel(connectType: ConnectType): String =
    when (connectType) {
        Connect -> AppStrings.ui_linked
        Fail -> AppStrings.ui_connection_failed
        UnConnect -> AppStrings.ui_not_connected
        Loading -> AppStrings.ui_connecting
        New -> AppStrings.ui_new_not_connected
        Rejected -> AppStrings.ui_connection_refused
    }

internal fun shareToDeviceListItemStatusLabel(
    sendStatus: FileShareStatus?,
    sendMessage: String?,
    connectType: ConnectType,
): String = when (sendStatus) {
    FileShareStatus.COMPLETED -> AppStrings.ui_connected
    FileShareStatus.WAITING -> AppStrings.ui_waiting_receive
    FileShareStatus.SENDING -> AppStrings.ui_sending
    FileShareStatus.REJECTED -> {
        if (
            sendMessage?.contains(AppStrings.ui_reject) == true ||
            sendMessage?.contains(AppStrings.ui_reject) == true
        ) {
            AppStrings.ui_rejected
        } else {
            AppStrings.ui_not_received
        }
    }

    FileShareStatus.ERROR -> AppStrings.ui_not_delivered
    null -> shareToDeviceListItemConnectTypeLabel(connectType)
}

internal fun shareToDeviceListItemSupportingText(
    sendStatus: FileShareStatus?,
    sendMessage: String?,
): String =
    when (sendStatus) {
        FileShareStatus.COMPLETED -> AppStrings.ui_connected

        FileShareStatus.WAITING -> AppStrings.ui_wait_other_party_receive

        FileShareStatus.SENDING -> AppStrings.share_status_sending

        FileShareStatus.REJECTED -> {
            if (!sendMessage.isNullOrBlank()) sendMessage else AppStrings.ui_other_party_did_not_receive
        }

        FileShareStatus.ERROR -> {
            if (!sendMessage.isNullOrBlank()) sendMessage else AppStrings.ui_sending_not_completed_please_try_again
        }

        else -> AppStrings.ui_not_connected
    }

@Composable
internal fun LinkShareDeviceStatusListItem(
    category: FileShareLikeCategory,
    deviceName: String,
    uploadStatus: String?,
    onShowDeviceLog: () -> Unit,
    onApprove: () -> Unit,
    onRejectOrRemove: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val supportingContent: (@Composable () -> Unit)? = uploadStatus?.let { status ->
        { Text(status) }
    }
    ListItem(
        overlineContent = {
            if (category == WAITING) {
                Text(AppStrings.ui_whether_allow_access)
            }
        },
        headlineContent = { Text(deviceName) },
        supportingContent = supportingContent,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (category == RUNNING) {
                    IconButton(onClick = onShowDeviceLog) {
                        Icon(Icons.Default.Info, null)
                    }
                }
                if (category == WAITING) {
                    IconButton(onClick = onApprove) {
                        Icon(Icons.Default.Done, null)
                    }
                }
                IconButton(onClick = onRejectOrRemove) {
                    Icon(if (category == RUNNING) Icons.Default.Block else Icons.Default.Close, null)
                }
            }
        },
        modifier = modifier.clickable(onClick = onClick)
    )
}

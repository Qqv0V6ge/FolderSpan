package com.folderspan.ui.components.fileshare

import strings.AppStrings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.folderspan.clipboard.writeClipboardText
import com.folderspan.extensions.randomString
import com.folderspan.service.http.server.HttpShareFileServer
import com.folderspan.service.http.server.ServerStartNotificationService
import com.folderspan.service.http.server.notifyServerStartFailure
import com.folderspan.service.http.server.toServerStartFailureMessage
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.theme.Typography
import com.folderspan.utils.LogKit
import com.folderspan.utils.SettingsUtils
import com.seiko.imageloader.component.fetcher.ByteArrayFetcher
import com.seiko.imageloader.model.ImageRequest
import com.seiko.imageloader.rememberImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import qrcode.QRCode

data class FileShareLinkCardUiState(
    val ipAddresses: List<String>,
    val address: String,
    val url: String,
    val password: String,
    val autoApprove: Boolean,
    val allowUpload: Boolean,
    val isRunning: Boolean,
    val isClosing: Boolean,
    val imageRequest: ImageRequest?,
    val serverControlsEnabled: Boolean,
    val hasRuntimeAuthorizations: Boolean,
)

@Composable
fun FileShareLinkCardContainer(
    fileShareState: FileShareState,
    httpShareFileServer: HttpShareFileServer,
    ipAddresses: List<String>,
    onClickOpenQRCode: (Pair<String, ImageRequest>) -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier.padding(start = 16.dp, end = 16.dp),
    runtimeSideEffectsEnabled: Boolean = true,
    serverControlsEnabled: Boolean = true,
    serverRunningOverride: Boolean? = null,
) {
    var address by remember { mutableStateOf(ipAddresses.firstOrNull() ?: "localhost") }

    LaunchedEffect(ipAddresses) {
        if (address !in ipAddresses) {
            address = ipAddresses.firstOrNull() ?: "localhost"
        }
    }

    var autoApprove by remember { mutableStateOf(false) }
    val password by fileShareState.connectPassword.collectAsState()
    val passwordAccessEnabled = password.isNotEmpty()
    val allowUpload by fileShareState.allowUpload.collectAsState()

    val url = remember(address) {
        "http://${address}:${SettingsUtils.easyFileShare.getPort()}/"
    }
    var qrUrl by remember { mutableStateOf(url) }

    var isRunning by remember(serverRunningOverride) {
        mutableStateOf(serverRunningOverride ?: httpShareFileServer.isRunning())
    }

    // 添加一个状态来跟踪是否正在关闭中
    var isClosing by remember { mutableStateOf(false) }

    fun startServerWithFeedback() {
        val port = SettingsUtils.easyFileShare.getPort()
        runCatching {
            httpShareFileServer.start(port)
        }.onFailure { throwable ->
            val message = throwable.toServerStartFailureMessage(port)
            LogKit.e(AppStrings.ui_failed_start_simple_sharing_service_arg0.format(arg0 = message), throwable)
            notifyServerStartFailure(ServerStartNotificationService.SimpleSharing, port, throwable)
            onShowMessage(message)
        }
    }

    // 自动启动服务（如果启用）
    LaunchedEffect(runtimeSideEffectsEnabled, serverRunningOverride) {
        if (!runtimeSideEffectsEnabled || serverRunningOverride != null) return@LaunchedEffect

        // 应用保存的设置
        autoApprove = SettingsUtils.easyFileShare.getAutoApprove()
        fileShareState.updateAutoApprove(autoApprove)

        if (password.isEmpty()) {
            if (SettingsUtils.easyFileShare.getPasswordAccess()) {
                fileShareState.updateConnectPassword(6.randomString(includeSpecial = false))
            } else {
                fileShareState.updateConnectPassword("")
            }
        }

        if (SettingsUtils.easyFileShare.isAutoStartOnOpen() && !httpShareFileServer.isRunning()) {
            if (fileShareState.files.isEmpty()) {
                onShowMessage(AppStrings.ui_please_add_files_you_want_share_first)
            } else {
                launch(Dispatchers.Default) {
                    startServerWithFeedback()
                    isRunning = httpShareFileServer.isRunning()
                }
            }
        }
    }

    // 监听共享状态变化
    val sharedServerRunning by fileShareState.isHttpServerRunning.collectAsState()


    // 同步本地状态与共享状态
    LaunchedEffect(sharedServerRunning, runtimeSideEffectsEnabled, serverRunningOverride) {
        if (!runtimeSideEffectsEnabled || serverRunningOverride != null) return@LaunchedEffect
        isRunning = sharedServerRunning
    }

    val qrCodeColor = colorScheme.primary.toArgb()
    val qrCodeBackground = colorScheme.background.toArgb()

    var imageRequest by remember { mutableStateOf<ImageRequest?>(null) }

    val scope = rememberCoroutineScope()

    fun enablePasswordAccess(clearRuntimeAuthorizations: Boolean) {
        if (autoApprove) {
            autoApprove = false
            fileShareState.updateAutoApprove(false)
        }
        fileShareState.updateConnectPassword(
            value = 6.randomString(includeSpecial = false),
            clearRuntimeAuthorizations = clearRuntimeAuthorizations
        )
    }

    LaunchedEffect(url, password, passwordAccessEnabled, fileShareState.files.toList()) {
        qrUrl = if (passwordAccessEnabled && runtimeSideEffectsEnabled) {
            val defaults = fileShareState.resolveLinkShareDefaults()
            val ticket = fileShareState.issueLinkShareTicket(
                allowHidden = defaults.allowHidden,
                allowUpload = defaults.allowUpload,
                files = defaults.files
            )
            "$url?ticket=${ticket.token}"
        } else {
            url
        }
    }

    LaunchedEffect(qrUrl, qrCodeColor, qrCodeBackground) {
        imageRequest = null
        val qrCodeBytes = withContext(Dispatchers.Default) {
            QRCode.ofSquares()
                .withColor(qrCodeColor)
                .withBackgroundColor(qrCodeBackground)
                .withSize(10)
                .build(qrUrl)
                .renderToBytes()
        }
        imageRequest = ImageRequest(
            data = qrCodeBytes,
        ) {
            components {
                add(ByteArrayFetcher.Factory())
            }
        }
    }

    fun toggleServer() {
        if (!serverControlsEnabled) return
        if (!isRunning && fileShareState.files.isEmpty()) {
            onShowMessage(AppStrings.ui_please_add_files_you_want_share_first)
            return
        }
        scope.launch {
            if (isRunning) {
                isClosing = true
                try {
                    withContext(Dispatchers.Default) { httpShareFileServer.stop() }
                    fileShareState.clearLinkShareRuntimeAuthorizations()
                } finally {
                    isRunning = httpShareFileServer.isRunning()
                    isClosing = false
                }
            } else {
                withContext(Dispatchers.Default) { startServerWithFeedback() }
                isRunning = httpShareFileServer.isRunning()
            }
        }
    }

    fun copyShareLink() {
        if (!serverControlsEnabled) return
        scope.launch {
            val copied = writeClipboardText(
                if (passwordAccessEnabled) {
                    AppStrings.ui_link_arg0_access_password_arg1.format(arg0 = url, arg1 = password)
                } else {
                    url
                }
            )
            onShowMessage(if (copied) AppStrings.ui_copied else AppStrings.ui_copy_failed)
        }
    }

    FileShareLinkCard(
        uiState = FileShareLinkCardUiState(
            ipAddresses = ipAddresses,
            address = address,
            url = url,
            password = password,
            autoApprove = autoApprove,
            allowUpload = allowUpload,
            isRunning = isRunning,
            isClosing = isClosing,
            imageRequest = imageRequest,
            serverControlsEnabled = serverControlsEnabled,
            hasRuntimeAuthorizations = fileShareState.hasLinkShareRuntimeAuthorizations(),
        ),
        onAddressChange = { selectedAddress -> address = selectedAddress },
        onOpenQrCode = {
            imageRequest?.let { request -> onClickOpenQRCode(qrUrl to request) }
        },
        onAutoApproveChange = { enabled ->
            if (enabled && passwordAccessEnabled) fileShareState.updateConnectPassword("")
            autoApprove = enabled
            fileShareState.updateAutoApprove(enabled)
        },
        onDisablePasswordAccess = { fileShareState.updateConnectPassword("") },
        onEnablePasswordAccess = ::enablePasswordAccess,
        onAllowUploadChange = fileShareState::updateAllowUpload,
        onToggleServer = ::toggleServer,
        onCopy = ::copyShareLink,
        modifier = modifier,
    )
}

@Composable
fun FileShareLinkCard(
    uiState: FileShareLinkCardUiState,
    onAddressChange: (String) -> Unit,
    onOpenQrCode: () -> Unit,
    onAutoApproveChange: (Boolean) -> Unit,
    onDisablePasswordAccess: () -> Unit,
    onEnablePasswordAccess: (Boolean) -> Unit,
    onAllowUploadChange: (Boolean) -> Unit,
    onToggleServer: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier.padding(horizontal = 16.dp),
) {
    var showAddress by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPasswordAccessClearDialog by remember { mutableStateOf(false) }
    val passwordAccessEnabled = uiState.password.isNotEmpty()

    Card(modifier) {
        Column {
            Row {
                Box(
                    Modifier.size(128.dp).clickable(onClick = onOpenQrCode),
                    contentAlignment = Alignment.Center,
                ) {
                    val imageRequest = uiState.imageRequest
                    if (imageRequest == null) {
                        CircularProgressIndicator()
                    } else {
                        Image(rememberImagePainter(imageRequest), null, Modifier.size(128.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(16.dp)) {
                    Text(
                        if (uiState.isRunning) AppStrings.ui_service_has_started else AppStrings.ui_service_not_started,
                        style = Typography.titleLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Column {
                            Text(uiState.url)
                            if (passwordAccessEnabled) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    AppStrings.ui_access_password_arg0.format(arg0 = uiState.password),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(showAddress) {
                FlowRow(
                    Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top)
                        .padding(start = 8.dp, end = 8.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    uiState.ipAddresses.forEach { ipAddress ->
                        FilterChip(
                            selected = uiState.address == ipAddress,
                            onClick = { onAddressChange(ipAddress) },
                            modifier = Modifier.padding(horizontal = 4.dp).align(Alignment.CenterVertically),
                            label = { Text(ipAddress) },
                        )
                    }
                }
            }
            AnimatedVisibility(showSettings) {
                FlowRow(
                    Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top)
                        .padding(start = 8.dp, end = 8.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    FileShareSettingChip(
                        selected = uiState.autoApprove,
                        label = AppStrings.ui_automatically_allow,
                        onClick = { onAutoApproveChange(!uiState.autoApprove) },
                    )
                    FileShareSettingChip(
                        selected = passwordAccessEnabled,
                        label = AppStrings.ui_password_access,
                        onClick = {
                            if (passwordAccessEnabled) {
                                onDisablePasswordAccess()
                            } else if (uiState.hasRuntimeAuthorizations) {
                                showPasswordAccessClearDialog = true
                            } else {
                                onEnablePasswordAccess(true)
                            }
                        },
                    )
                    FileShareSettingChip(
                        selected = uiState.allowUpload,
                        label = AppStrings.ui_allow_upload,
                        onClick = { onAllowUploadChange(!uiState.allowUpload) },
                    )
                }
            }
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onToggleServer,
                    enabled = uiState.serverControlsEnabled && !uiState.isClosing,
                ) {
                    Text(
                        when {
                            uiState.isClosing -> AppStrings.ui_closed
                            uiState.isRunning -> AppStrings.ui_close
                            else -> AppStrings.ui_start
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onCopy, enabled = uiState.serverControlsEnabled) {
                    Text(AppStrings.ui_copy)
                }
                Spacer(Modifier.weight(1f))
                FilledIconToggleButton(
                    checked = showAddress,
                    onCheckedChange = { showAddress = it },
                    enabled = uiState.serverControlsEnabled,
                ) { Icon(Icons.Default.MoreVert, null) }
                FilledIconToggleButton(
                    checked = showSettings,
                    onCheckedChange = { showSettings = it },
                    enabled = uiState.serverControlsEnabled,
                ) { Icon(Icons.Default.Settings, null) }
            }
        }
    }

    if (showPasswordAccessClearDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordAccessClearDialog = false },
            title = { Text(AppStrings.ui_enable_password_access) },
            text = { Text(AppStrings.ui_after_enabling_password_you_can_clear_old_authorization_require) },
            confirmButton = {
                TextButton(onClick = {
                    onEnablePasswordAccess(true)
                    showPasswordAccessClearDialog = false
                }) { Text(AppStrings.ui_clean_enable) }
            },
            dismissButton = {
                TextButton(onClick = {
                    onEnablePasswordAccess(false)
                    showPasswordAccessClearDialog = false
                }) { Text(AppStrings.ui_reserve_enable) }
            },
        )
    }
}

@Composable
private fun FileShareSettingChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 4.dp),
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Done, contentDescription = null) }
        } else {
            null
        },
    )
}

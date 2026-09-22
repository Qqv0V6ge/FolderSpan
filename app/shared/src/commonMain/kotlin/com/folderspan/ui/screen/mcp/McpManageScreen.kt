package com.folderspan.ui.screen.mcp

import strings.AppStrings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.folderspan.clipboard.writeClipboardText
import com.folderspan.permission.PermissionIds
import com.folderspan.permission.PermissionStatus
import com.folderspan.permission.PlatformPermissionProvider
import com.folderspan.service.mcp.McpServerSettings
import com.folderspan.service.mcp.McpServerSettingsStore
import com.folderspan.service.mcp.isValidMcpPort
import com.folderspan.service.mcp.auth.McpOneTimeTokenSecret
import com.folderspan.service.mcp.auth.McpTokenRecord
import com.folderspan.service.mcp.auth.McpTokenRepository
import com.folderspan.service.mcp.auth.McpTokenScope
import com.folderspan.service.mcp.http.McpAdvertisedEndpoint
import com.folderspan.service.mcp.http.McpHttpServiceInterface
import com.folderspan.service.mcp.http.McpHttpServiceStatus
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.seiko.imageloader.component.fetcher.ByteArrayFetcher
import com.seiko.imageloader.model.ImageRequest
import com.seiko.imageloader.rememberImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import qrcode.QRCode

internal object McpManageScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val service = koinInject<McpHttpServiceInterface>()
        val settingsStore = koinInject<McpServerSettingsStore>()
        val tokenRepository = koinInject<McpTokenRepository>()
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        var savedSettings by remember { mutableStateOf(settingsStore.read()) }
        var portText by remember(savedSettings.port) { mutableStateOf(savedSettings.port.toString()) }
        var status by remember { mutableStateOf(service.status()) }
        var tokens by remember { mutableStateOf<List<McpTokenRecord>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        var showTokenEditor by remember { mutableStateOf(false) }
        var editingToken by remember { mutableStateOf<McpTokenRecord?>(null) }
        var rotatingToken by remember { mutableStateOf<McpTokenRecord?>(null) }
        var oneTimeSecret by remember { mutableStateOf<McpOneTimeTokenSecret?>(null) }
        var qrCodeUrl by remember { mutableStateOf<String?>(null) }

        fun showMessage(message: String) {
            scope.launch { snackbarHostState.showLatestSnackbar(message) }
        }

        fun refreshTokens() {
            scope.launch {
                tokens = runCatching { tokenRepository.list() }
                    .getOrElse {
                        showMessage(AppStrings.ui_mcp_action_failed)
                        tokens
                    }
            }
        }

        fun runServiceAction(action: suspend () -> McpHttpServiceStatus) {
            if (busy) return
            scope.launch {
                busy = true
                try {
                    status = action()
                    status.errorMessage?.takeIf(String::isNotBlank)?.let(::showMessage)
                } catch (_: Throwable) {
                    showMessage(AppStrings.ui_mcp_action_failed)
                } finally {
                    busy = false
                }
            }
        }

        fun requestLocalNetworkPermissionIfNeeded(
            requiresLan: Boolean,
            onGranted: () -> Unit,
        ) {
            if (!requiresLan) {
                onGranted()
                return
            }
            val permission = PlatformPermissionProvider.permissions()
                .firstOrNull { item -> item.id == PermissionIds.LocalNetwork }
            if (permission == null) {
                onGranted()
                return
            }
            scope.launch {
                if (PlatformPermissionProvider.status(permission) == PermissionStatus.Granted) {
                    onGranted()
                } else {
                    PlatformPermissionProvider.request(permission) { result ->
                        if (result == PermissionStatus.Granted) onGranted()
                        else showMessage(AppStrings.ui_mcp_local_network_permission_denied)
                    }
                }
            }
        }

        fun applyServiceEnabled(enabled: Boolean) {
            val next = settingsStore.write(savedSettings.copy(enabled = enabled))
            savedSettings = next
            runServiceAction {
                if (enabled) service.start(next) else service.stop()
            }
        }

        fun requestServiceEnabled(enabled: Boolean) {
            if (!enabled) {
                applyServiceEnabled(false)
                return
            }
            requestLocalNetworkPermissionIfNeeded(savedSettings.lanAccess) {
                applyServiceEnabled(true)
            }
        }

        fun requestServiceRestart(settings: McpServerSettings = savedSettings) {
            requestLocalNetworkPermissionIfNeeded(settings.lanAccess) {
                runServiceAction { service.restart(settings) }
            }
        }

        fun applyLanAccess(enabled: Boolean) {
            val next = settingsStore.write(savedSettings.copy(lanAccess = enabled))
            savedSettings = next
            showMessage(AppStrings.ui_mcp_saved)
            if (next.enabled) {
                requestServiceRestart(next)
            }
        }

        fun requestLanAccess(enabled: Boolean) {
            if (!enabled) {
                applyLanAccess(false)
                return
            }
            requestLocalNetworkPermissionIfNeeded(true) {
                applyLanAccess(true)
            }
        }

        LaunchedEffect(Unit) {
            status = service.status()
            tokens = runCatching { tokenRepository.list() }.getOrDefault(emptyList())
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_mcp_management) },
                    navigationIcon = {
                        IconButton(
                            onClick = { navigator.pop() },
                            modifier = Modifier.sizeIn(minWidth = McpDimensions.touchTarget, minHeight = McpDimensions.touchTarget),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Default.ArrowBack,
                                contentDescription = AppStrings.ui_close,
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            McpManageResponsiveLayout(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                serviceSection = {
                    McpServiceSection(
                        settings = savedSettings,
                        status = status,
                        portText = portText,
                        busy = busy,
                        onPortChange = { portText = it.filter(Char::isDigit).take(5) },
                        onEnabledChange = ::requestServiceEnabled,
                        onLanAccessChange = ::requestLanAccess,
                        onSavePort = { port ->
                            val next = settingsStore.write(savedSettings.copy(port = port))
                            savedSettings = next
                            portText = next.port.toString()
                            showMessage(AppStrings.ui_mcp_saved)
                            if (next.enabled) {
                                requestServiceRestart(next)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mcp_service_card"),
                    )
                },
                endpointSection = {
                    McpEndpointSection(
                        status = status,
                        onShowQrCode = { qrCodeUrl = it },
                        onCopy = { value ->
                            scope.launch {
                                val message = if (writeClipboardText(value)) {
                                    AppStrings.ui_copied
                                } else {
                                    AppStrings.ui_copy_failed
                                }
                                showMessage(message)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mcp_endpoint_card"),
                    )
                },
                tokenSection = {
                    McpTokenSection(
                        tokens = tokens,
                        busy = busy,
                        onCreate = {
                            editingToken = null
                            showTokenEditor = true
                        },
                        onEdit = { token ->
                            editingToken = token
                            showTokenEditor = true
                        },
                        onEnabledChange = { token, enabled ->
                            scope.launch {
                                runCatching { tokenRepository.setEnabled(token.lookupId, enabled) }
                                    .onSuccess { refreshTokens() }
                                    .onFailure { showMessage(AppStrings.ui_mcp_action_failed) }
                            }
                        },
                        onRotate = { rotatingToken = it },
                        onDelete = { token ->
                            scope.launch {
                                confirmMcpTokenDeletion(snackbarHostState) {
                                    runCatching { tokenRepository.delete(token.lookupId) }
                                        .onSuccess { refreshTokens() }
                                        .onFailure { showMessage(AppStrings.ui_mcp_action_failed) }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mcp_token_card"),
                    )
                },
            )
        }

        if (showTokenEditor) {
            McpTokenEditorDialog(
                token = editingToken,
                onDismiss = {
                    showTokenEditor = false
                    editingToken = null
                },
                onConfirm = { name, scopes ->
                    val token = editingToken
                    scope.launch {
                        runCatching {
                            if (token == null) {
                                tokenRepository.create(name, scopes).also { oneTimeSecret = it }
                            } else {
                                tokenRepository.rename(token.lookupId, name)
                                tokenRepository.setScopes(token.lookupId, scopes)
                                null
                            }
                        }.onSuccess {
                            showTokenEditor = false
                            editingToken = null
                            refreshTokens()
                        }.onFailure {
                            showMessage(AppStrings.ui_mcp_action_failed)
                        }
                    }
                },
            )
        }

        rotatingToken?.let { token ->
            McpConfirmationDialog(
                title = AppStrings.ui_mcp_rotate_token,
                message = AppStrings.ui_mcp_rotate_confirmation,
                confirmText = AppStrings.ui_restart,
                onDismiss = { rotatingToken = null },
                onConfirm = {
                    rotatingToken = null
                    scope.launch {
                        runCatching { tokenRepository.rotate(token.lookupId) }
                            .onSuccess { secret ->
                                oneTimeSecret = secret
                                refreshTokens()
                            }
                            .onFailure { showMessage(AppStrings.ui_mcp_action_failed) }
                    }
                },
            )
        }

        oneTimeSecret?.let { secret ->
            McpOneTimeSecretDialog(
                secret = secret.token,
                onCopy = { content ->
                    scope.launch {
                        showMessage(
                            if (writeClipboardText(content)) AppStrings.ui_copied else AppStrings.ui_copy_failed
                        )
                    }
                },
                onClose = { oneTimeSecret = null },
            )
        }

        qrCodeUrl?.let { url ->
            McpQrCodeDialog(
                url = url,
                onCopy = {
                    scope.launch {
                        showMessage(if (writeClipboardText(url)) AppStrings.ui_copied else AppStrings.ui_copy_failed)
                    }
                },
                onDismiss = { qrCodeUrl = null },
            )
        }
    }
}

internal suspend fun confirmMcpTokenDeletion(
    snackbarHostState: SnackbarHostState,
    onConfirm: suspend () -> Unit,
) {
    val result = snackbarHostState.showLatestSnackbar(
        message = AppStrings.ui_mcp_delete_confirmation,
        actionLabel = AppStrings.ui_delete,
        withDismissAction = true,
        duration = SnackbarDuration.Short,
    )
    if (result == SnackbarResult.ActionPerformed) {
        onConfirm()
    }
}

internal enum class McpTokenPreset {
    ReadOnly,
    Operator,
    Administrator,
}

internal fun withImpliedMcpScopes(scopes: Set<McpTokenScope>): Set<McpTokenScope> {
    val implied = scopes.toMutableSet()
    if (McpTokenScope.FavoritesWrite in implied || McpTokenScope.FilesShare in implied) {
        implied += McpTokenScope.FilesRead
    }
    return implied
}

internal fun mcpPresetScopes(preset: McpTokenPreset): Set<McpTokenScope> {
    val readOnly = setOf(
        McpTokenScope.BookmarksRead,
        McpTokenScope.FavoritesRead,
        McpTokenScope.RecentsRead,
        McpTokenScope.TasksRead,
        McpTokenScope.DevicesRead,
        McpTokenScope.NetworksRead,
        McpTokenScope.SyncRead,
        McpTokenScope.FilesRead,
    )
    return when (preset) {
        McpTokenPreset.ReadOnly -> readOnly
        McpTokenPreset.Operator -> readOnly + setOf(
            McpTokenScope.BookmarksWrite,
            McpTokenScope.FavoritesWrite,
            McpTokenScope.RecentsWrite,
            McpTokenScope.TasksControl,
            McpTokenScope.DevicesConnect,
            McpTokenScope.NetworksConnect,
            McpTokenScope.SyncRun,
            McpTokenScope.FilesWrite,
        )
        McpTokenPreset.Administrator -> McpTokenScope.entries.toSet()
    }
}

internal enum class McpPermissionLevel {
    ReadOnly,
    Operator,
    Administrator,
    Custom,
}

internal enum class McpPermissionArea {
    Bookmarks,
    Favorites,
    Recents,
    Tasks,
    Devices,
    Networks,
    Sync,
    Files,
}

internal data class McpPermissionGroup(
    val area: McpPermissionArea,
    val scopes: List<McpTokenScope>,
)

internal fun mcpPermissionLevel(scopes: Set<McpTokenScope>): McpPermissionLevel = when (scopes) {
    mcpPresetScopes(McpTokenPreset.ReadOnly) -> McpPermissionLevel.ReadOnly
    mcpPresetScopes(McpTokenPreset.Operator) -> McpPermissionLevel.Operator
    mcpPresetScopes(McpTokenPreset.Administrator) -> McpPermissionLevel.Administrator
    else -> McpPermissionLevel.Custom
}

internal fun McpTokenScope.permissionArea(): McpPermissionArea = when (this) {
    McpTokenScope.BookmarksRead,
    McpTokenScope.BookmarksWrite -> McpPermissionArea.Bookmarks
    McpTokenScope.FavoritesRead,
    McpTokenScope.FavoritesWrite -> McpPermissionArea.Favorites
    McpTokenScope.RecentsRead,
    McpTokenScope.RecentsWrite -> McpPermissionArea.Recents
    McpTokenScope.TasksRead,
    McpTokenScope.TasksControl -> McpPermissionArea.Tasks
    McpTokenScope.DevicesRead,
    McpTokenScope.DevicesConnect -> McpPermissionArea.Devices
    McpTokenScope.NetworksRead,
    McpTokenScope.NetworksConnect -> McpPermissionArea.Networks
    McpTokenScope.SyncRead,
    McpTokenScope.SyncRun -> McpPermissionArea.Sync
    McpTokenScope.FilesRead,
    McpTokenScope.FilesWrite,
    McpTokenScope.FilesShare -> McpPermissionArea.Files
}

internal fun mcpPermissionGroups(scopes: Set<McpTokenScope>): List<McpPermissionGroup> =
    McpPermissionArea.entries.mapNotNull { area ->
        scopes.filter { scope -> scope.permissionArea() == area }
            .sortedBy(McpTokenScope::ordinal)
            .takeIf(List<McpTokenScope>::isNotEmpty)
            ?.let { matchingScopes -> McpPermissionGroup(area, matchingScopes) }
    }

internal fun McpAdvertisedEndpoint.selectionKey(): String = "$host|$port"

internal fun preferredMcpEndpoints(endpoints: List<McpAdvertisedEndpoint>): List<McpAdvertisedEndpoint> =
    endpoints.groupBy { endpoint -> endpoint.host to endpoint.port }
        .values
        .map { candidates ->
            candidates.firstOrNull { endpoint -> endpoint.scheme.equals("https", ignoreCase = true) }
                ?: candidates.first()
        }
        .sortedWith(compareBy(McpAdvertisedEndpoint::host, McpAdvertisedEndpoint::port))

internal fun mcpSchemesForAddress(
    endpoints: List<McpAdvertisedEndpoint>,
    addressKey: String?,
): Set<String> = endpoints
    .filter { endpoint -> endpoint.selectionKey() == addressKey }
    .map { endpoint -> endpoint.scheme.lowercase() }
    .toSet()

internal fun resolveMcpEndpoint(
    endpoints: List<McpAdvertisedEndpoint>,
    addressKey: String?,
    scheme: String,
): McpAdvertisedEndpoint? {
    val matches = endpoints.filter { endpoint -> endpoint.selectionKey() == addressKey }
        .ifEmpty { preferredMcpEndpoints(endpoints) }
    return matches.firstOrNull { endpoint -> endpoint.scheme.equals(scheme, ignoreCase = true) }
        ?: matches.firstOrNull { endpoint -> endpoint.scheme.equals("https", ignoreCase = true) }
        ?: matches.firstOrNull()
}

internal fun filterMcpEndpoints(
    endpoints: List<McpAdvertisedEndpoint>,
    query: String,
): List<McpAdvertisedEndpoint> {
    val uniqueAddresses = preferredMcpEndpoints(endpoints)
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return uniqueAddresses
    return uniqueAddresses.filter { endpoint ->
        endpoint.host.contains(normalizedQuery, ignoreCase = true)
    }
}

internal fun mcpManageColumnCount(width: Dp): Int =
    if (width >= McpDimensions.twoColumnBreakpoint) 2 else 1

internal fun mcpTokenGridColumnCount(width: Dp): Int {
    val columnWidth = McpDimensions.tokenItemMinWidth + McpDimensions.gap
    val availableWidth = width + McpDimensions.gap
    return (availableWidth.value / columnWidth.value)
        .toInt()
        .coerceIn(1, McpDimensions.maxTokenColumns)
}

internal fun mcpPermissionEditorColumnCount(width: Dp): Int =
    if (width >= McpDimensions.permissionGridBreakpoint) 2 else 1

@Composable
internal fun McpManageResponsiveLayout(
    modifier: Modifier = Modifier,
    serviceSection: @Composable ColumnScope.() -> Unit,
    endpointSection: @Composable ColumnScope.() -> Unit,
    tokenSection: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val columnCount = mcpManageColumnCount(maxWidth)
        val contentPadding = if (maxWidth >= McpDimensions.mediumWindowWidth) {
            McpDimensions.expandedContentPadding
        } else {
            McpDimensions.compactContentPadding
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.TopCenter)
                .testTag("mcp_manage_layout"),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(McpDimensions.sectionGap),
        ) {
            item(key = "mcp-sections", contentType = "section-columns") {
                if (columnCount == 1) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(McpDimensions.sectionGap),
                    ) {
                        serviceSection()
                        endpointSection()
                        tokenSection()
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(McpDimensions.sectionGap),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.width(McpDimensions.leftPaneWidth),
                            verticalArrangement = Arrangement.spacedBy(McpDimensions.sectionGap),
                        ) {
                            serviceSection()
                            endpointSection()
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            content = tokenSection,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun McpSection(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = McpDimensions.sectionHeaderPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(McpDimensions.smallGap),
        ) {
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            action?.invoke()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = McpDimensions.sectionContentPadding),
            verticalArrangement = Arrangement.spacedBy(McpDimensions.gap),
        ) {
            content()
        }
    }
}

@Composable
private fun McpServiceSection(
    settings: McpServerSettings,
    status: McpHttpServiceStatus,
    portText: String,
    busy: Boolean,
    onPortChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onLanAccessChange: (Boolean) -> Unit,
    onSavePort: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val port = portText.toIntOrNull()
    val portValid = port != null && isValidMcpPort(port)
    val pendingPortChange = port != settings.port
    McpSection(
        title = AppStrings.ui_mcp_service_status,
        modifier = modifier,
        action = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(McpDimensions.smallGap),
            ) {
                Text(
                    text = if (settings.enabled) AppStrings.ui_mcp_running else AppStrings.ui_mcp_stopped,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = status.supported && !busy && !pendingPortChange,
                    modifier = Modifier
                        .sizeIn(
                            minWidth = McpDimensions.touchTarget,
                            minHeight = McpDimensions.touchTarget,
                        )
                        .semantics { contentDescription = AppStrings.ui_mcp_enable },
                )
            }
        },
    ) {
        if (!status.supported) {
            Text(
                text = AppStrings.ui_mcp_unsupported,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = portText,
            onValueChange = onPortChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = status.supported && !busy,
            singleLine = true,
            label = { Text(AppStrings.ui_mcp_port) },
            isError = portText.isNotBlank() && !portValid,
            supportingText = if (!portValid) {
                { Text(AppStrings.ui_mcp_port_error) }
            } else {
                null
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(McpDimensions.smallGap),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = AppStrings.ui_mcp_lan_access,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = AppStrings.ui_mcp_lan_access_description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.lanAccess,
                onCheckedChange = onLanAccessChange,
                enabled = status.supported && !busy,
                modifier = Modifier
                    .sizeIn(
                        minWidth = McpDimensions.touchTarget,
                        minHeight = McpDimensions.touchTarget,
                    )
                    .semantics { contentDescription = AppStrings.ui_mcp_lan_access }
                    .testTag("mcp_lan_access"),
            )
        }
        status.errorMessage?.takeIf(String::isNotBlank)?.let { error ->
            Text(
                text = error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (status.supported && pendingPortChange) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = { onSavePort(requireNotNull(port)) },
                    enabled = portValid && !busy,
                    modifier = Modifier.sizeIn(minHeight = McpDimensions.touchTarget),
                ) {
                    Text(AppStrings.ui_mcp_save_restart)
                }
            }
        }
    }
}

@Composable
private fun McpEndpointSection(
    status: McpHttpServiceStatus,
    onShowQrCode: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val addresses = remember(status.endpoints) {
        preferredMcpEndpoints(status.endpoints)
    }
    var selectedAddressKey by remember { mutableStateOf<String?>(null) }
    var selectedScheme by remember { mutableStateOf("https") }
    var showEndpointPicker by remember { mutableStateOf(false) }
    val selectedEndpoint = resolveMcpEndpoint(
        endpoints = status.endpoints,
        addressKey = selectedAddressKey ?: addresses.firstOrNull()?.selectionKey(),
        scheme = selectedScheme,
    )
    val availableSchemes = mcpSchemesForAddress(
        endpoints = status.endpoints,
        addressKey = selectedEndpoint?.selectionKey(),
    )

    McpSection(
        title = AppStrings.ui_mcp_endpoints,
        modifier = modifier,
        action = if (addresses.isNotEmpty()) {
            {
                TextButton(
                    onClick = { showEndpointPicker = true },
                    modifier = Modifier.sizeIn(minHeight = McpDimensions.touchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                    Text(AppStrings.ui_mcp_choose_address)
                }
            }
        } else {
            null
        },
    ) {
        if (selectedEndpoint == null) {
            Text(
                text = AppStrings.ui_mcp_no_endpoints,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            McpSchemeToggle(
                selectedScheme = selectedEndpoint.scheme,
                availableSchemes = availableSchemes,
                onSchemeChange = { selectedScheme = it },
            )
            McpCopyRow(
                label = AppStrings.ui_mcp_stateful_endpoint,
                value = selectedEndpoint.statefulUrl,
                copyDescription = AppStrings.ui_mcp_copy_stateful,
                qrCodeDescription = AppStrings.ui_mcp_show_stateful_qr,
                onShowQrCode = onShowQrCode,
                onCopy = onCopy,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            McpCopyRow(
                label = AppStrings.ui_mcp_stateless_endpoint,
                value = selectedEndpoint.statelessUrl,
                copyDescription = AppStrings.ui_mcp_copy_stateless,
                qrCodeDescription = AppStrings.ui_mcp_show_stateless_qr,
                onShowQrCode = onShowQrCode,
                onCopy = onCopy,
            )
        }
        status.tlsFingerprintSha256
            .takeIf { fingerprint -> selectedEndpoint?.scheme == "https" && fingerprint.isNotBlank() }
            ?.let { fingerprint ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                McpCopyRow(
                    label = AppStrings.ui_mcp_tls_fingerprint,
                    value = fingerprint,
                    copyDescription = AppStrings.ui_mcp_copy_fingerprint,
                    onCopy = onCopy,
                )
            }
    }

    if (showEndpointPicker) {
        McpEndpointPickerDialog(
            endpoints = addresses,
            selectedEndpointKey = selectedEndpoint?.selectionKey(),
            onSelect = { endpoint ->
                selectedAddressKey = endpoint.selectionKey()
                showEndpointPicker = false
            },
            onDismiss = { showEndpointPicker = false },
        )
    }
}

@Composable
private fun McpCopyRow(
    label: String,
    value: String,
    copyDescription: String,
    qrCodeDescription: String? = null,
    onShowQrCode: ((String) -> Unit)? = null,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(McpDimensions.smallGap),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (qrCodeDescription != null && onShowQrCode != null) {
            IconButton(
                onClick = { onShowQrCode(value) },
                modifier = Modifier.sizeIn(
                    minWidth = McpDimensions.touchTarget,
                    minHeight = McpDimensions.touchTarget,
                ),
            ) {
                Icon(Icons.Default.QrCode2, contentDescription = qrCodeDescription)
            }
        }
        IconButton(
            onClick = { onCopy(value) },
            modifier = Modifier.sizeIn(minWidth = McpDimensions.touchTarget, minHeight = McpDimensions.touchTarget),
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = copyDescription)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpSchemeToggle(
    selectedScheme: String,
    availableSchemes: Set<String>,
    onSchemeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val schemes = listOf("http", "https")
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        schemes.forEachIndexed { index, scheme ->
            SegmentedButton(
                selected = selectedScheme.equals(scheme, ignoreCase = true),
                onClick = { onSchemeChange(scheme) },
                enabled = scheme in availableSchemes,
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = schemes.size,
                ),
                label = { Text(scheme.uppercase()) },
            )
        }
    }
}

@Composable
private fun McpEndpointPickerDialog(
    endpoints: List<McpAdvertisedEndpoint>,
    selectedEndpointKey: String?,
    onSelect: (McpAdvertisedEndpoint) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filteredEndpoints = remember(endpoints, query) { filterMcpEndpoints(endpoints, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_mcp_choose_address) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(McpDimensions.gap),
            ) {
                if (endpoints.size > McpDimensions.endpointSearchThreshold) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(AppStrings.ui_mcp_search_address) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    )
                }
                if (filteredEndpoints.isEmpty()) {
                    Text(
                        text = AppStrings.ui_mcp_no_matching_address,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = McpDimensions.endpointPickerHeight),
                    ) {
                        items(
                            items = filteredEndpoints,
                            key = McpAdvertisedEndpoint::selectionKey,
                        ) { endpoint ->
                            val selected = endpoint.selectionKey() == selectedEndpointKey
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = endpoint.host,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingContent = {
                                    RadioButton(selected = selected, onClick = null)
                                },
                                modifier = Modifier.selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(endpoint) },
                                ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minHeight = McpDimensions.touchTarget),
            ) {
                Text(AppStrings.ui_cancel)
            }
        },
    )
}

@Composable
private fun McpQrCodeDialog(
    url: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val qrCodeColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val qrCodeBackground = MaterialTheme.colorScheme.surface.toArgb()
    var imageRequest by remember(url) { mutableStateOf<ImageRequest?>(null) }
    var generationFailed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, qrCodeColor, qrCodeBackground) {
        imageRequest = null
        generationFailed = false
        runCatching {
            val qrCodeBytes = withContext(Dispatchers.Default) {
                QRCode.ofSquares()
                    .withColor(qrCodeColor)
                    .withBackgroundColor(qrCodeBackground)
                    .withSize(10)
                    .build(url)
                    .renderToBytes()
            }
            ImageRequest(data = qrCodeBytes) {
                components { add(ByteArrayFetcher.Factory()) }
            }
        }.onSuccess { request ->
            imageRequest = request
        }.onFailure {
            generationFailed = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_mcp_qr_title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(McpDimensions.gap),
            ) {
                Box(
                    modifier = Modifier.size(McpDimensions.qrCodeSize),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        generationFailed -> Text(
                            text = AppStrings.ui_mcp_qr_failed,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        imageRequest == null -> CircularProgressIndicator()
                        else -> Image(
                            painter = rememberImagePainter(requireNotNull(imageRequest)),
                            contentDescription = AppStrings.ui_mcp_qr_code_description,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                SelectionContainer {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minHeight = McpDimensions.touchTarget),
            ) {
                Text(AppStrings.ui_close)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCopy,
                modifier = Modifier.sizeIn(minHeight = McpDimensions.touchTarget),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Text(AppStrings.ui_copy)
            }
        },
    )
}

@Composable
private fun McpPermissionLevel.title(): String = when (this) {
    McpPermissionLevel.ReadOnly -> AppStrings.ui_mcp_read_only
    McpPermissionLevel.Operator -> AppStrings.ui_mcp_operator
    McpPermissionLevel.Administrator -> AppStrings.ui_mcp_administrator
    McpPermissionLevel.Custom -> AppStrings.ui_mcp_custom_permissions
}

@Composable
private fun McpPermissionLevel.description(): String = when (this) {
    McpPermissionLevel.ReadOnly -> AppStrings.ui_mcp_read_only_effect
    McpPermissionLevel.Operator -> AppStrings.ui_mcp_operator_effect
    McpPermissionLevel.Administrator -> AppStrings.ui_mcp_administrator_effect
    McpPermissionLevel.Custom -> AppStrings.ui_mcp_custom_effect
}

@Composable
private fun McpPermissionArea.title(): String = when (this) {
    McpPermissionArea.Bookmarks -> AppStrings.ui_mcp_area_bookmarks
    McpPermissionArea.Favorites -> AppStrings.ui_mcp_area_favorites
    McpPermissionArea.Recents -> AppStrings.ui_mcp_area_recents
    McpPermissionArea.Tasks -> AppStrings.ui_mcp_area_tasks
    McpPermissionArea.Devices -> AppStrings.ui_mcp_area_devices
    McpPermissionArea.Networks -> AppStrings.ui_mcp_area_networks
    McpPermissionArea.Sync -> AppStrings.ui_mcp_area_sync
    McpPermissionArea.Files -> AppStrings.ui_mcp_area_files
}

@Composable
private fun McpTokenScope.actionTitle(): String = when (this) {
    McpTokenScope.BookmarksRead,
    McpTokenScope.FavoritesRead,
    McpTokenScope.RecentsRead,
    McpTokenScope.TasksRead,
    McpTokenScope.DevicesRead,
    McpTokenScope.NetworksRead,
    McpTokenScope.SyncRead,
    McpTokenScope.FilesRead -> AppStrings.ui_mcp_action_view
    McpTokenScope.BookmarksWrite,
    McpTokenScope.FavoritesWrite,
    McpTokenScope.RecentsWrite -> AppStrings.ui_mcp_action_manage
    McpTokenScope.TasksControl -> AppStrings.ui_mcp_action_control
    McpTokenScope.DevicesConnect,
    McpTokenScope.NetworksConnect -> AppStrings.ui_mcp_action_connect
    McpTokenScope.SyncRun -> AppStrings.ui_mcp_action_run
    McpTokenScope.FilesWrite -> AppStrings.ui_mcp_action_modify
    McpTokenScope.FilesShare -> AppStrings.ui_mcp_action_share
}

@Composable
private fun McpPermissionGroup.label(): String {
    val actions = scopes.map { scope -> scope.actionTitle() }
    return "${area.title()} · ${actions.joinToString(AppStrings.ui_mcp_scope_separator)}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpPermissionSummary(
    scopes: Set<McpTokenScope>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val level = mcpPermissionLevel(scopes)
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest
        level == McpPermissionLevel.ReadOnly -> MaterialTheme.colorScheme.secondaryContainer
        level == McpPermissionLevel.Operator -> MaterialTheme.colorScheme.tertiaryContainer
        level == McpPermissionLevel.Administrator -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        level == McpPermissionLevel.ReadOnly -> MaterialTheme.colorScheme.onSecondaryContainer
        level == McpPermissionLevel.Operator -> MaterialTheme.colorScheme.onTertiaryContainer
        level == McpPermissionLevel.Administrator -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(McpDimensions.smallGap),
        verticalArrangement = Arrangement.spacedBy(McpDimensions.smallGap),
    ) {
        mcpPermissionGroups(scopes).forEach { group ->
            Surface(
                color = containerColor,
                contentColor = contentColor,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Text(
                    text = group.label(),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpTokenSection(
    tokens: List<McpTokenRecord>,
    busy: Boolean,
    onCreate: () -> Unit,
    onEdit: (McpTokenRecord) -> Unit,
    onEnabledChange: (McpTokenRecord, Boolean) -> Unit,
    onRotate: (McpTokenRecord) -> Unit,
    onDelete: (McpTokenRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    McpSection(
        title = AppStrings.ui_mcp_tokens,
        modifier = modifier,
        action = {
            IconButton(
                onClick = onCreate,
                enabled = !busy,
                modifier = Modifier.sizeIn(minWidth = McpDimensions.touchTarget, minHeight = McpDimensions.touchTarget),
            ) {
                Icon(Icons.Default.Add, contentDescription = AppStrings.ui_mcp_create_token)
            }
        },
    ) {
        if (tokens.isEmpty()) {
            Text(
                text = AppStrings.ui_mcp_no_tokens,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (tokens.isNotEmpty()) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columnCount = mcpTokenGridColumnCount(maxWidth)
                val totalGapWidth = McpDimensions.gap * (columnCount - 1)
                val itemWidth = (maxWidth - totalGapWidth) / columnCount
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    maxItemsInEachRow = columnCount,
                    horizontalArrangement = Arrangement.spacedBy(McpDimensions.gap),
                    verticalArrangement = Arrangement.spacedBy(McpDimensions.gap),
                ) {
                    tokens.forEach { token ->
                        McpTokenItem(
                            token = token,
                            onEdit = { onEdit(token) },
                            onEnabledChange = { enabled -> onEnabledChange(token, enabled) },
                            onRotate = { onRotate(token) },
                            onDelete = { onDelete(token) },
                            modifier = Modifier
                                .width(itemWidth)
                                .testTag("mcp_token_item_${token.lookupId}"),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun McpTokenItem(
    token: McpTokenRecord,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(token.lookupId) { mutableStateOf(false) }
    val permissionLevel = mcpPermissionLevel(token.scopes)
    val containerColor = if (token.enabled) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    OutlinedCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
    ) {
        Column {
            ListItem(
                headlineContent = {
                    Text(
                        text = token.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                supportingContent = {
                    Text(
                        text = if (token.enabled) {
                            permissionLevel.title()
                        } else {
                            "${AppStrings.ui_mcp_token_disabled} · ${permissionLevel.title()}"
                        },
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = token.enabled,
                            onCheckedChange = onEnabledChange,
                            modifier = Modifier.sizeIn(
                                minWidth = McpDimensions.touchTarget,
                                minHeight = McpDimensions.touchTarget,
                            ),
                        )
                        IconButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier
                                .sizeIn(
                                    minWidth = McpDimensions.touchTarget,
                                    minHeight = McpDimensions.touchTarget,
                                )
                                .testTag("mcp_token_details_toggle_${token.lookupId}"),
                        ) {
                            Icon(
                                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (expanded) {
                                    AppStrings.ui_mcp_collapse_token_details
                                } else {
                                    AppStrings.ui_mcp_expand_token_details
                                },
                            )
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = containerColor,
                    headlineColor = if (token.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.testTag("mcp_token_details_${token.lookupId}"),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier.padding(McpDimensions.sectionContentPadding),
                        verticalArrangement = Arrangement.spacedBy(McpDimensions.gap),
                    ) {
                        if (permissionLevel == McpPermissionLevel.Custom) {
                            McpPermissionSummary(
                                scopes = token.scopes,
                                enabled = token.enabled,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(
                                McpDimensions.smallGap,
                                Alignment.End,
                            ),
                        ) {
                            TextButton(onClick = onEdit) {
                                Text(AppStrings.ui_edit)
                            }
                            TextButton(onClick = onRotate) {
                                Text(AppStrings.ui_mcp_rotate_token)
                            }
                            TextButton(
                                onClick = onDelete,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Text(AppStrings.ui_delete)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpTokenEditorDialog(
    token: McpTokenRecord?,
    onDismiss: () -> Unit,
    onConfirm: (String, Set<McpTokenScope>) -> Unit,
) {
    var name by remember(token?.lookupId) { mutableStateOf(token?.name.orEmpty()) }
    var scopes by remember(token?.lookupId) {
        mutableStateOf(token?.scopes ?: mcpPresetScopes(McpTokenPreset.ReadOnly))
    }
    val selectedPreset = McpTokenPreset.entries.firstOrNull { mcpPresetScopes(it) == scopes }
    val allPermissionGroups = remember { mcpPermissionGroups(McpTokenScope.entries.toSet()) }
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxSize(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val dialogWidth = (maxWidth - McpDimensions.dialogOuterPadding * 2)
                .coerceAtLeast(1.dp)
                .coerceAtMost(McpDimensions.tokenDialogMaxWidth)
            val dialogHeight = (maxHeight - McpDimensions.dialogOuterPadding * 2)
                .coerceAtLeast(1.dp)
                .coerceAtMost(McpDimensions.tokenDialogMaxHeight)

            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(dialogWidth, dialogHeight),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = McpDimensions.dialogTonalElevation,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = McpDimensions.dialogContentPadding,
                            vertical = McpDimensions.dialogHeaderPadding,
                        ),
                        verticalArrangement = Arrangement.spacedBy(McpDimensions.smallGap),
                    ) {
                        Text(
                            text = if (token == null) {
                                AppStrings.ui_mcp_create_token
                            } else {
                                AppStrings.ui_mcp_edit_token
                            },
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }

                    HorizontalDivider()

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(
                            horizontal = McpDimensions.dialogContentPadding,
                            vertical = McpDimensions.dialogSectionPadding,
                        ),
                        verticalArrangement = Arrangement.spacedBy(McpDimensions.dialogSectionGap),
                    ) {
                        item(key = "token-name") {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it.take(80) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(AppStrings.ui_mcp_token_name) },
                            )
                        }

                        item(key = "permission-presets") {
                            McpTokenEditorSection(
                                title = AppStrings.ui_mcp_quick_permissions,
                            ) {
                                SingleChoiceSegmentedButtonRow(
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    McpTokenPreset.entries.forEachIndexed { index, preset ->
                                        SegmentedButton(
                                            selected = selectedPreset == preset,
                                            onClick = { scopes = mcpPresetScopes(preset) },
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = index,
                                                count = McpTokenPreset.entries.size,
                                            ),
                                            label = {
                                                Text(
                                                    text = preset.title(),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "permission-effect") {
                            McpTokenPermissionOverview(scopes = scopes)
                        }

                        item(key = "permission-groups") {
                            McpTokenEditorSection(
                                title = AppStrings.ui_mcp_scopes,
                            ) {
                                McpPermissionEditorGrid(
                                    groups = allPermissionGroups,
                                    scopes = scopes,
                                    onScopesChange = { scopes = it },
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = McpDimensions.dialogContentPadding,
                                vertical = McpDimensions.dialogActionPadding,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(
                            McpDimensions.smallGap,
                            Alignment.End,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onDismiss,
                        ) {
                            Text(AppStrings.ui_cancel)
                        }
                        Button(
                            onClick = { onConfirm(name.trim(), withImpliedMcpScopes(scopes)) },
                            enabled = name.isNotBlank() && scopes.isNotEmpty(),
                        ) {
                            Text(AppStrings.ui_save)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpTokenEditorSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(McpDimensions.gap),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        content()
    }
}

@Composable
private fun McpTokenPermissionOverview(scopes: Set<McpTokenScope>) {
    val permissionLevel = mcpPermissionLevel(scopes)
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        headlineContent = { Text(permissionLevel.title()) },
        supportingContent = { Text(permissionLevel.description()) },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
            supportingColor = MaterialTheme.colorScheme.onSecondaryContainer,
            leadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

@Composable
private fun McpPermissionEditorGrid(
    groups: List<McpPermissionGroup>,
    scopes: Set<McpTokenScope>,
    onScopesChange: (Set<McpTokenScope>) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnCount = mcpPermissionEditorColumnCount(maxWidth)
        Column(verticalArrangement = Arrangement.spacedBy(McpDimensions.gap)) {
            groups.chunked(columnCount).forEach { groupRow ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(McpDimensions.gap),
                    verticalAlignment = Alignment.Top,
                ) {
                    groupRow.forEach { group ->
                        McpPermissionGroupSection(
                            group = group,
                            selectedScopes = scopes,
                            onScopesChange = onScopesChange,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columnCount - groupRow.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun McpPermissionGroupSection(
    group: McpPermissionGroup,
    selectedScopes: Set<McpTokenScope>,
    onScopesChange: (Set<McpTokenScope>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(McpDimensions.gap),
    ) {
        Text(
            text = group.area.title(),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        MultiChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            group.scopes.forEachIndexed { index, item ->
                val selected = item in selectedScopes
                SegmentedButton(
                    checked = selected,
                    onCheckedChange = { checked ->
                        val next = if (checked) selectedScopes + item else selectedScopes - item
                        onScopesChange(withImpliedMcpScopes(next))
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = group.scopes.size,
                    ),
                    label = {
                        Text(
                            text = item.actionTitle(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun McpConfirmationDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.sizeIn(minHeight = McpDimensions.touchTarget),
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minHeight = McpDimensions.touchTarget),
            ) {
                Text(AppStrings.ui_cancel)
            }
        },
    )
}

internal fun mcpAuthorizationHeaders(token: String): String =
    "Authorization: Bearer $token\nContent-Type: application/json"

@Composable
internal fun McpOneTimeSecretDialog(
    secret: String,
    onCopy: (String) -> Unit,
    onClose: () -> Unit,
) {
    val requestHeaders = remember(secret) { mcpAuthorizationHeaders(secret) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(AppStrings.ui_mcp_secret_title) },
        icon = { Icon(Icons.Default.SettingsEthernet, contentDescription = null) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(McpDimensions.gap),
            ) {
                McpCopyableCodeRow(
                    label = AppStrings.ui_mcp_complete_token,
                    value = secret,
                    onClick = { onCopy(secret) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mcp_secret_token_copy_region"),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                McpCopyableCodeRow(
                    label = AppStrings.ui_mcp_request_headers,
                    value = requestHeaders,
                    onClick = { onCopy(requestHeaders) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mcp_secret_headers_copy_region"),
                )
                Text(
                    text = AppStrings.ui_mcp_secret_close_warning,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onClose,
                modifier = Modifier.sizeIn(minHeight = McpDimensions.touchTarget),
            ) {
                Text(AppStrings.ui_close)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onCopy(secret) },
                modifier = Modifier
                    .sizeIn(minHeight = McpDimensions.touchTarget)
                    .testTag("mcp_secret_copy_action"),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Text(AppStrings.ui_mcp_copy_secret)
            }
        },
    )
}

@Composable
private fun McpCopyableCodeRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.clickable(
            role = Role.Button,
            onClick = onClick,
        ),
        headlineContent = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
            )
        },
        supportingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun McpTokenPreset.title(): String = when (this) {
    McpTokenPreset.ReadOnly -> AppStrings.ui_mcp_read_only
    McpTokenPreset.Operator -> AppStrings.ui_mcp_operator
    McpTokenPreset.Administrator -> AppStrings.ui_mcp_administrator
}

private object McpDimensions {
    val compactContentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
    val expandedContentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)
    val mediumWindowWidth = 600.dp
    val twoColumnBreakpoint = 840.dp
    val leftPaneWidth = 440.dp
    val tokenItemMinWidth = 320.dp
    const val maxTokenColumns = 4
    val sectionGap = 32.dp
    val sectionHeaderPadding = 8.dp
    val sectionContentPadding = 16.dp
    val gap = 12.dp
    val smallGap = 8.dp
    val touchTarget = 48.dp
    val endpointPickerHeight = 360.dp
    val qrCodeSize = 240.dp
    val dialogOuterPadding = 16.dp
    val dialogContentPadding = 24.dp
    val dialogHeaderPadding = 20.dp
    val dialogSectionPadding = 20.dp
    val dialogActionPadding = 12.dp
    val dialogSectionGap = 24.dp
    val dialogTonalElevation = 6.dp
    val tokenDialogMaxWidth = 760.dp
    val tokenDialogMaxHeight = 760.dp
    val permissionGridBreakpoint = 560.dp
    const val endpointSearchThreshold = 6
}

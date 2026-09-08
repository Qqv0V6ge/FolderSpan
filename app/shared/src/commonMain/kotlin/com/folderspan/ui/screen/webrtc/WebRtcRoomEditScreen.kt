package com.folderspan.ui.screen.webrtc

import strings.AppStrings

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.webrtc.*
import com.folderspan.isProAuthenticated
import com.folderspan.service.webrtc.WebRtcRoomConnectionTestResult
import com.folderspan.service.webrtc.WebRtcRoomConnectionTester
import com.folderspan.service.webrtc.signaling.generateRoomId
import com.folderspan.service.webrtc.signaling.isValidRoomId
import com.folderspan.ui.components.buttons.TestConnectionButton
import com.folderspan.ui.components.fields.PasswordOutlinedTextField
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.state.main.WebRtcRoomState
import com.folderspan.utils.calculateGridColumnCount
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class WebRtcRoomEditScreen(
    private val room: WebRtcRoomProfile? = null
) : AppScreenRoute {
    private val isEdit: Boolean = room != null

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val roomState = koinInject<WebRtcRoomState>()
        val scope = rememberCoroutineScope()
        val connectionTester = remember { WebRtcRoomConnectionTester() }
        val snackbarHostState = remember { SnackbarHostState() }
        val roomKey = room?.id ?: -1L
        val canUseOfficialGateway = isProAuthenticated()
        val initialSource = room?.source ?: WebRtcRoomSource.Other

        var name by rememberSaveable(roomKey) { mutableStateOf(room?.name ?: "") }
        var otherWssUrl by rememberSaveable(roomKey) {
            mutableStateOf(if (initialSource == WebRtcRoomSource.Other) room?.wssUrl ?: "" else "")
        }
        var otherRoomId by rememberSaveable(roomKey) {
            mutableStateOf(if (initialSource == WebRtcRoomSource.Other) room?.roomId ?: "" else "")
        }
        var officialRoomId by rememberSaveable(roomKey) {
            mutableStateOf(if (initialSource == WebRtcRoomSource.Official) room?.roomId ?: "" else "")
        }
        var sourceName by rememberSaveable(roomKey) { mutableStateOf(initialSource.name) }
        var stunUrl by rememberSaveable(roomKey) { mutableStateOf(room?.stunUrl ?: "") }
        var turnUrl by rememberSaveable(roomKey) { mutableStateOf(room?.turnUrl ?: "") }
        var turnUsername by rememberSaveable(roomKey) { mutableStateOf(room?.turnUsername ?: "") }
        var turnPassword by rememberSaveable(roomKey) { mutableStateOf(room?.turnPassword ?: "") }
        var isIceTurnExpanded by rememberSaveable(roomKey) { mutableStateOf(false) }
        var isTestingWebSocket by remember { mutableStateOf(false) }
        val requestedSource = remember(sourceName) { sourceName.toWebRtcRoomSource() }
        val source = remember(requestedSource, canUseOfficialGateway) {
            resolveWebRtcRoomSource(
                source = requestedSource,
                canUseOfficialGateway = canUseOfficialGateway
            )
        }
        val sourceOptions = remember(canUseOfficialGateway) {
            webRtcRoomSourceOptions(canUseOfficialGateway = canUseOfficialGateway)
        }
        val officialWssUrl = if (canUseOfficialGateway) WebRtcOfficialGateway.websocketUrl() else ""

        LaunchedEffect(source, sourceName) {
            if (source.name != sourceName) {
                sourceName = source.name
            }
            if (source == WebRtcRoomSource.Official && officialRoomId.isBlank()) {
                officialRoomId = generateRoomId()
            }
        }

        val connectionFields = webRtcRoomDisplayedConnectionFields(
            source = source,
            otherWssUrl = otherWssUrl,
            otherRoomId = otherRoomId,
            officialWssUrl = officialWssUrl,
            officialRoomId = officialRoomId
        )
        val normalizedInput = remember(
            name,
            connectionFields,
            stunUrl,
            turnUrl,
            turnUsername,
            turnPassword,
            source
        ) {
            WebRtcRoomInput(
                name = name,
                wssUrl = connectionFields.wssUrl,
                roomId = connectionFields.roomId,
                stunUrl = stunUrl,
                turnUrl = turnUrl,
                turnUsername = turnUsername,
                turnPassword = turnPassword,
                source = source
            ).normalized()
        }

        val nameError = normalizedInput.name.isBlank()
        val wssUrlError = normalizedInput.wssUrl.isBlank()
        val roomIdBlank = normalizedInput.roomId.isBlank()
        val roomIdError = !roomIdBlank && !isValidRoomId(normalizedInput.roomId)
        val canSave = normalizedInput.canSave()
        val canTest = !wssUrlError

        val handleSaveClick: () -> Unit = save@{
            if (!canSave) return@save
            scope.launch {
                val saved = if (room == null) {
                    roomState.addRoom(normalizedInput) != null
                } else {
                    roomState.updateRoom(room.id, normalizedInput)
                }
                if (saved) {
                    navigator.pop()
                }
            }
        }

        val handleTestClick: () -> Unit = test@{
            if (!canTest || isTestingWebSocket) return@test
            scope.launch {
                isTestingWebSocket = true
                val message = try {
                    normalizedInput.toWebRtcConfigResult().fold(
                        onSuccess = { config ->
                            webRtcRoomConnectionTestMessage(
                                connectionTester.testWebSocketConnection(
                                    wssUrl = config.wssUrl,
                                    headers = config.headers,
                                )
                            )
                        },
                        onFailure = { error ->
                            error.message ?: AppStrings.ui_webrtc_connection_parameters_not_available
                        }
                    )
                } finally {
                    isTestingWebSocket = false
                }
                snackbarHostState.showSnackbar(message)
            }
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (isEdit) AppStrings.ui_edit_room else AppStrings.ui_add_new_room) },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(8.dp)
                ) {
                    FloatingActionButton(
                        onClick = handleSaveClick,
                        content = {
                            Icon(Icons.Default.Save, contentDescription = null)
                        }
                    )
                    TestConnectionButton(
                        isTesting = isTestingWebSocket,
                        onClick = handleTestClick,
                    )
                }
            },
        ) { padding ->
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalSpacing = 16.dp,
                horizontalSpacing = 16.dp,
                floatingActionButtonPadding = GridListFabPadding
            ) {
                WebRtcRoomForm(
                    name = name,
                    onNameChange = { name = it },
                    nameError = nameError,
                    source = source,
                    sourceOptions = sourceOptions,
                    onSourceChange = { selectedSource ->
                        sourceName = selectedSource.name
                    },
                    wssUrl = connectionFields.wssUrl,
                    onWssUrlChange = { value ->
                        if (source == WebRtcRoomSource.Other) {
                            otherWssUrl = value
                        }
                    },
                    wssUrlError = wssUrlError,
                    roomId = connectionFields.roomId,
                    onRoomIdChange = { value ->
                        if (source == WebRtcRoomSource.Official) {
                            officialRoomId = value
                        } else {
                            otherRoomId = value
                        }
                    },
                    roomIdBlank = roomIdBlank,
                    roomIdError = roomIdError,
                    onGenerateRoomId = {
                        if (source == WebRtcRoomSource.Official) {
                            officialRoomId = generateRoomId()
                        } else {
                            otherRoomId = generateRoomId()
                        }
                    },
                    stunUrl = stunUrl,
                    onStunUrlChange = { stunUrl = it },
                    turnUrl = turnUrl,
                    onTurnUrlChange = { turnUrl = it },
                    turnUsername = turnUsername,
                    onTurnUsernameChange = { turnUsername = it },
                    turnPassword = turnPassword,
                    onTurnPasswordChange = { turnPassword = it },
                    isIceTurnExpanded = isIceTurnExpanded,
                    onToggleIceTurnExpanded = { isIceTurnExpanded = !isIceTurnExpanded }
                )
            }
        }
    }
}

private fun LazyGridScope.WebRtcRoomForm(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: Boolean,
    source: WebRtcRoomSource,
    sourceOptions: List<WebRtcRoomSource>,
    onSourceChange: (WebRtcRoomSource) -> Unit,
    wssUrl: String,
    onWssUrlChange: (String) -> Unit,
    wssUrlError: Boolean,
    roomId: String,
    onRoomIdChange: (String) -> Unit,
    roomIdBlank: Boolean,
    roomIdError: Boolean,
    onGenerateRoomId: () -> Unit,
    stunUrl: String,
    onStunUrlChange: (String) -> Unit,
    turnUrl: String,
    onTurnUrlChange: (String) -> Unit,
    turnUsername: String,
    onTurnUsernameChange: (String) -> Unit,
    turnPassword: String,
    onTurnPasswordChange: (String) -> Unit,
    isIceTurnExpanded: Boolean,
    onToggleIceTurnExpanded: () -> Unit
) {
    if (sourceOptions.size > 1) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            WebRtcRoomSourceSelector(
                source = source,
                sourceOptions = sourceOptions,
                onSourceChange = onSourceChange
            )
        }
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        WebRtcFormSectionHeader(AppStrings.ui_room_information)
    }
    item {
        WebRtcRoomTextField(
            label = AppStrings.ui_room_name,
            value = name,
            onValueChange = onNameChange,
            isError = nameError
        )
    }
    if (webRtcRoomShowsConnectionFields(source)) {
        item {
            WebRtcRoomTextField(
                label = AppStrings.ui_websocket_address,
                value = wssUrl,
                onValueChange = onWssUrlChange,
                isError = wssUrlError,
                supportingText = {
                    if (wssUrlError) {
                        Text(AppStrings.ui_websocket_address_cannot_empty, color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            WebRtcRoomTextField(
                label = AppStrings.webrtc_room_id_label,
                value = roomId,
                onValueChange = onRoomIdChange,
                isError = roomIdBlank || roomIdError,
                supportingText = {
                    when {
                        roomIdBlank -> Text(AppStrings.ui_room_id_cannot_empty, color = MaterialTheme.colorScheme.error)
                        roomIdError -> Text(
                            AppStrings.ui_room_id_invalid_must_base64url_256_bit_value,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                trailingIcon = {
                    TextButton(onClick = onGenerateRoomId) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Text(AppStrings.ui_generate, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            )
        }
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            WebRtcFormSectionHeader(
                text = "ICE / TURN",
                action = {
                    IconButton(onClick = onToggleIceTurnExpanded) {
                        Icon(
                            imageVector = if (isIceTurnExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isIceTurnExpanded) AppStrings.ui_folding_ice_turn else AppStrings.ui_expand_ice_turn
                        )
                    }
                }
            )
            AnimatedVisibility(
                visible = isIceTurnExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                WebRtcIceTurnFields(
                    stunUrl = stunUrl,
                    onStunUrlChange = onStunUrlChange,
                    turnUrl = turnUrl,
                    onTurnUrlChange = onTurnUrlChange,
                    turnUsername = turnUsername,
                    onTurnUsernameChange = onTurnUsernameChange,
                    turnPassword = turnPassword,
                    onTurnPasswordChange = onTurnPasswordChange
                )
            }
        }
    }
}

@Composable
private fun WebRtcRoomSourceSelector(
    source: WebRtcRoomSource,
    sourceOptions: List<WebRtcRoomSource>,
    onSourceChange: (WebRtcRoomSource) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        sourceOptions.forEachIndexed { index, item ->
            SegmentedButton(
                selected = source == item,
                onClick = { onSourceChange(item) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = sourceOptions.size
                )
            ) {
                Text(item.label)
            }
        }
    }
}

private fun String.toWebRtcRoomSource(): WebRtcRoomSource =
    runCatching { WebRtcRoomSource.valueOf(this) }.getOrDefault(WebRtcRoomSource.Other)

internal data class WebRtcRoomDisplayedConnectionFields(
    val wssUrl: String,
    val roomId: String
)

internal fun webRtcRoomSourceOptions(canUseOfficialGateway: Boolean): List<WebRtcRoomSource> {
    if (!canUseOfficialGateway) return listOf(WebRtcRoomSource.Other)
    return WebRtcRoomSource.entries.toList()
}

internal fun resolveWebRtcRoomSource(
    source: WebRtcRoomSource,
    canUseOfficialGateway: Boolean
): WebRtcRoomSource {
    return if (source == WebRtcRoomSource.Official && !canUseOfficialGateway) {
        WebRtcRoomSource.Other
    } else {
        source
    }
}

internal fun webRtcRoomShowsConnectionFields(source: WebRtcRoomSource): Boolean =
    source != WebRtcRoomSource.Official

internal fun webRtcRoomDisplayedConnectionFields(
    source: WebRtcRoomSource,
    otherWssUrl: String,
    otherRoomId: String,
    officialWssUrl: String,
    officialRoomId: String
): WebRtcRoomDisplayedConnectionFields =
    if (source == WebRtcRoomSource.Official) {
        WebRtcRoomDisplayedConnectionFields(
            wssUrl = officialWssUrl,
            roomId = officialRoomId
        )
    } else {
        WebRtcRoomDisplayedConnectionFields(
            wssUrl = otherWssUrl,
            roomId = otherRoomId
        )
    }

internal fun webRtcRoomConnectionTestMessage(result: WebRtcRoomConnectionTestResult): String {
    return when (result) {
        WebRtcRoomConnectionTestResult.Success -> AppStrings.ui_connection_successful
        is WebRtcRoomConnectionTestResult.Failure -> {
            val reason = result.message.ifBlank { AppStrings.ui_connection_failed }
            when {
                reason.contains("Fail to fetch", ignoreCase = true) ||
                    reason.contains("Failed to fetch", ignoreCase = true) -> {
                    AppStrings.ui_connection_failed_browser_cannot_access_target_service_check_endpoint
                }

                reason == AppStrings.ui_connection_failed -> reason
                else -> AppStrings.ui_connection_failed_arg0.format(arg0 = reason)
            }
        }
    }
}

@Composable
private fun WebRtcIceTurnFields(
    stunUrl: String,
    onStunUrlChange: (String) -> Unit,
    turnUrl: String,
    onTurnUrlChange: (String) -> Unit,
    turnUsername: String,
    onTurnUsernameChange: (String) -> Unit,
    turnPassword: String,
    onTurnPasswordChange: (String) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        val useSingleColumn = calculateGridColumnCount(maxWidth, maxHeight) <= 1

        if (useSingleColumn) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                WebRtcRoomTextField(
                label = AppStrings.webrtc_stun_url_label,
                    value = stunUrl,
                    onValueChange = onStunUrlChange
                )
                WebRtcRoomTextField(
                label = AppStrings.webrtc_turn_url_label,
                    value = turnUrl,
                    onValueChange = onTurnUrlChange
                )
                WebRtcRoomTextField(
                    label = AppStrings.ui_turn_username,
                    value = turnUsername,
                    onValueChange = onTurnUsernameChange
                )
                WebRtcRoomTextField(
                    label = AppStrings.ui_turn_password,
                    value = turnPassword,
                    onValueChange = onTurnPasswordChange,
                    isPassword = true
                )
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    WebRtcRoomTextField(
                        label = AppStrings.webrtc_stun_url_label,
                        value = stunUrl,
                        onValueChange = onStunUrlChange,
                        modifier = Modifier.weight(1f)
                    )
                    WebRtcRoomTextField(
                        label = AppStrings.webrtc_turn_url_label,
                        value = turnUrl,
                        onValueChange = onTurnUrlChange,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    WebRtcRoomTextField(
                        label = AppStrings.ui_turn_username,
                        value = turnUsername,
                        onValueChange = onTurnUsernameChange,
                        modifier = Modifier.weight(1f)
                    )
                    WebRtcRoomTextField(
                        label = AppStrings.ui_turn_password,
                        value = turnPassword,
                        onValueChange = onTurnPasswordChange,
                        isPassword = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun WebRtcFormSectionHeader(
    text: String,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        action?.invoke()
    }
}

@Composable
private fun WebRtcRoomTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean = false,
    isPassword: Boolean = false,
    supportingText: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (isPassword) {
        PasswordOutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            supportingText = supportingText,
            modifier = modifier.fillMaxWidth()
        )
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            isError = isError,
            singleLine = true,
            supportingText = supportingText,
            trailingIcon = trailingIcon,
            modifier = modifier.fillMaxWidth()
        )
    }
}

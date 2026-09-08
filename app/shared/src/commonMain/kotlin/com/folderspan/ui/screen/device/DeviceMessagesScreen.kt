package com.folderspan.ui.screen.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.extensions.timestampToAdaptiveDateTime
import com.folderspan.service.message.*
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.pagestate.PageStateLayout
import com.folderspan.ui.components.pagestate.resolvePageViewState
import com.folderspan.ui.components.pagestate.toPageErrorState
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.state.main.DeviceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import strings.AppStrings
import androidx.compose.foundation.lazy.grid.items as gridItems

private const val DEVICE_MESSAGE_PAGE_SIZE = 50L
private const val DEVICE_MESSAGE_COLLAPSED_CHARACTER_COUNT = 480

internal fun buildDeviceMessagePeerIds(
    summaries: List<DeviceConversationSummary>,
    endpoints: Set<DeviceMessageEndpointIdentity>,
): List<String> {
    val lastMessageByPeer = summaries.associate { summary ->
        summary.peerDeviceId to summary.lastMessageAtMillis
    }
    return (lastMessageByPeer.keys + endpoints.map(DeviceMessageEndpointIdentity::peerDeviceId))
        .distinct()
        .sortedWith(
            compareByDescending<String> { peerDeviceId -> lastMessageByPeer[peerDeviceId] ?: Long.MIN_VALUE }
                .thenBy { peerDeviceId -> peerDeviceId },
        )
}

internal fun resolveDeviceMessageConversationEndpoint(
    selectedEndpoint: DeviceMessageEndpointIdentity?,
    initialEndpoint: DeviceMessageEndpointIdentity?,
    liveEndpoints: List<DeviceMessageEndpointIdentity>,
): DeviceMessageEndpointIdentity? =
    selectedEndpoint?.takeIf(liveEndpoints::contains)
        ?: initialEndpoint?.takeIf(liveEndpoints::contains)
        ?: liveEndpoints.firstOrNull()

internal fun deviceMessageCharacterCount(body: String): Int {
    var count = 0
    var index = 0
    while (index < body.length) {
        val character = body[index]
        val isSurrogatePair = character in '\uD800'..'\uDBFF' &&
                index + 1 < body.length &&
                body[index + 1] in '\uDC00'..'\uDFFF'
        index += if (isSurrogatePair) 2 else 1
        count += 1
    }
    return count
}

internal fun canSendDeviceMessage(
    body: String,
    bodyUtf8Length: Int,
    endpointOnline: Boolean,
    operationInProgress: Boolean,
): Boolean = endpointOnline &&
        !operationInProgress &&
        body.isNotBlank() &&
        bodyUtf8Length in 1..DEVICE_MESSAGE_MAX_BODY_BYTES

internal fun shouldCollapseDeviceMessageBody(body: String): Boolean =
    body.length > DEVICE_MESSAGE_COLLAPSED_CHARACTER_COUNT

class DeviceMessagesScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val deviceState = koinInject<DeviceState>()
        val conversations by deviceState.deviceMessageConversations.collectAsState()
        val endpoints by deviceState.deviceMessageLiveEndpoints.collectAsState()
        val peerIds = buildDeviceMessagePeerIds(conversations, endpoints)
        val summaries = conversations.associateBy(DeviceConversationSummary::peerDeviceId)

        LaunchedEffect(Unit) {
            deviceState.refreshDeviceMessageConversations()
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.device_messages) },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = AppStrings.ui_return,
                            )
                        }
                    },
                )
            },
        ) { padding ->
            GridList(
                isEmpty = peerIds.isEmpty(),
                emptyMessage = AppStrings.device_message_no_conversations,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                gridItems(
                    items = peerIds,
                    key = { peerDeviceId -> peerDeviceId },
                    contentType = { "device-message-conversation" },
                ) { peerDeviceId ->
                    val peerEndpoints = endpoints
                        .filter { identity -> identity.peerDeviceId == peerDeviceId }
                        .sortedBy { identity -> identity.transport.name }
                    val summary = summaries[peerDeviceId]
                    val displayName = deviceState.socketDevices
                        .firstOrNull { device -> device.id == peerDeviceId }
                        ?.name
                        ?.takeIf(String::isNotBlank)
                        ?: peerDeviceId
                    DeviceMessageConversationItem(
                        peerDeviceId = peerDeviceId,
                        displayName = displayName,
                        summary = summary,
                        endpoints = peerEndpoints,
                        onClick = {
                            navigator.push(
                                DeviceMessageScreen(
                                    peerDeviceId = peerDeviceId,
                                    deviceName = displayName,
                                    initialEndpoint = peerEndpoints.singleOrNull(),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceMessageConversationItem(
    peerDeviceId: String,
    displayName: String,
    summary: DeviceConversationSummary?,
    endpoints: List<DeviceMessageEndpointIdentity>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unreadCount = summary?.unreadCount ?: 0L
    ListItem(
        headlineContent = {
            Text(
                text = displayName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClick = onClick,
            ),
        overlineContent = if (displayName != peerDeviceId) {
            {
                Text(
                    text = peerDeviceId,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            null
        },
        supportingContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (endpoints.isNotEmpty()) {
                        AppStrings.device_message_online
                    } else {
                        AppStrings.device_message_offline
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (endpoints.isNotEmpty()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.weight(1f))
                summary?.let {
                    Text(
                        text = it.lastMessageAtMillis.timestampToAdaptiveDateTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                Icons.AutoMirrored.Filled.Message,
                contentDescription = null,
                tint = if (endpoints.isNotEmpty()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        trailingContent = if (unreadCount > 0L) {
            {
                Badge {
                    Text(unreadCount.coerceAtMost(99L).toString())
                }
            }
        } else {
            null
        },
    )
}

data class DeviceMessageScreen(
    val peerDeviceId: String,
    val deviceName: String,
    val initialEndpoint: DeviceMessageEndpointIdentity? = null,
) : AppScreenRoute {
    override val routeKey: String = "DeviceMessageScreen:$peerDeviceId"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val deviceState = koinInject<DeviceState>()
        val liveEndpoints by deviceState.deviceMessageLiveEndpoints.collectAsState()
        val selectedEndpoint by deviceState.selectedDeviceMessageEndpoint.collectAsState()
        val revision by deviceState.deviceMessageRevision.collectAsState()
        val peerEndpoints = liveEndpoints
            .filter { identity -> identity.peerDeviceId == peerDeviceId }
            .sortedWith(compareBy<DeviceMessageEndpointIdentity> { it.transport.name }.thenBy { it.connectionId })
        val activeEndpoint = resolveDeviceMessageConversationEndpoint(
            selectedEndpoint = selectedEndpoint,
            initialEndpoint = initialEndpoint,
            liveEndpoints = peerEndpoints,
        )
        val endpointOnline = activeEndpoint != null
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val listState = rememberLazyListState()
        var messages by remember(peerDeviceId) { mutableStateOf<List<DeviceStoredMessage>>(emptyList()) }
        var loadingMessages by remember(peerDeviceId) { mutableStateOf(true) }
        var messageLoadError by remember(peerDeviceId) { mutableStateOf<Throwable?>(null) }
        var hasOlderMessages by remember(peerDeviceId) { mutableStateOf(false) }
        var loadingOlder by remember(peerDeviceId) { mutableStateOf(false) }
        var draft by remember(peerDeviceId) { mutableStateOf("") }
        var sending by remember(peerDeviceId) { mutableStateOf(false) }
        var retryingMessageIds by remember(peerDeviceId) { mutableStateOf<Set<String>>(emptySet()) }
        val bodyUtf8Length = remember(draft) { draft.encodeToByteArray().size }
        val bodyCharacterCount = remember(draft) { deviceMessageCharacterCount(draft) }
        val bodyTooLarge = bodyUtf8Length > DEVICE_MESSAGE_MAX_BODY_BYTES
        val canSend = canSendDeviceMessage(draft, bodyUtf8Length, endpointOnline, sending)

        suspend fun reloadMessages(scrollToBottom: Boolean) {
            loadingMessages = messages.isEmpty()
            messageLoadError = null
            try {
                val page = deviceState.loadDeviceMessagePage(peerDeviceId, DEVICE_MESSAGE_PAGE_SIZE)
                messages = page.asReversed()
                hasOlderMessages = page.size.toLong() == DEVICE_MESSAGE_PAGE_SIZE
                if (scrollToBottom && messages.isNotEmpty()) {
                    listState.scrollToItem(messages.lastIndex)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                messageLoadError = error
            } finally {
                loadingMessages = false
            }
        }

        LaunchedEffect(peerDeviceId, activeEndpoint?.connectionId) {
            deviceState.openDeviceMessageConversation(peerDeviceId, activeEndpoint)
        }
        DisposableEffect(peerDeviceId) {
            onDispose { deviceState.closeDeviceMessageConversation(peerDeviceId) }
        }
        LaunchedEffect(revision, peerDeviceId) {
            reloadMessages(scrollToBottom = true)
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(deviceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = AppStrings.ui_return,
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val snackbarResult = snackbarHostState.showSnackbar(
                                        message = AppStrings.device_message_delete_confirm_body,
                                        actionLabel = AppStrings.ui_delete,
                                        withDismissAction = true,
                                    )
                                    if (snackbarResult == SnackbarResult.ActionPerformed) {
                                        deviceState.deleteDeviceMessageConversation(peerDeviceId)
                                        navigator.pop()
                                    }
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = AppStrings.device_message_delete_conversation,
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                PageStateLayout(
                    state = resolvePageViewState(
                        isLoading = loadingMessages,
                        errorState = messageLoadError
                            ?.takeIf { messages.isEmpty() }
                            ?.toPageErrorState(),
                        isEmpty = messages.isEmpty(),
                        emptyMessage = AppStrings.device_message_no_history,
                    ),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onRetry = if (messageLoadError != null) {
                        {
                            scope.launch {
                                reloadMessages(scrollToBottom = false)
                            }
                        }
                    } else {
                        null
                    },
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (hasOlderMessages) {
                            item(key = "load-older") {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    TextButton(
                                        enabled = !loadingOlder,
                                        onClick = {
                                            val oldest = messages.firstOrNull() ?: return@TextButton
                                            loadingOlder = true
                                            scope.launch {
                                                val older = deviceState.loadOlderDeviceMessages(
                                                    peerDeviceId = peerDeviceId,
                                                    beforeSentAtMillis = oldest.sentAtMillis,
                                                    beforeLocalId = oldest.localId,
                                                    limit = DEVICE_MESSAGE_PAGE_SIZE,
                                                )
                                                messages = older.asReversed() + messages
                                                hasOlderMessages = older.size.toLong() == DEVICE_MESSAGE_PAGE_SIZE
                                                loadingOlder = false
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(AppStrings.device_message_load_older)
                                    }
                                }
                            }
                        }
                        itemsIndexed(
                            items = messages,
                            key = { _, message -> message.localId },
                            contentType = { _, message -> message.direction },
                        ) { index, message ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                DeviceMessageBubble(
                                    message = message,
                                    canRetry = endpointOnline &&
                                        message.status in setOf(
                                            DeviceMessageStatus.Failed,
                                            DeviceMessageStatus.Unconfirmed,
                                        ) &&
                                        message.messageId !in retryingMessageIds,
                                    onRetry = {
                                        val endpoint = activeEndpoint ?: return@DeviceMessageBubble
                                        retryingMessageIds = retryingMessageIds + message.messageId
                                        scope.launch {
                                            val result = deviceState.retryDeviceMessage(
                                                endpoint,
                                                message.messageId,
                                            )
                                            retryingMessageIds = retryingMessageIds - message.messageId
                                            result.exceptionOrNull()?.let { failure ->
                                                snackbarHostState.showSnackbar(
                                                    deviceMessageFailureText(failure),
                                                )
                                            }
                                        }
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                DeviceMessageComposer(
                    value = draft,
                    onValueChange = { draft = it },
                    bodyUtf8Length = bodyUtf8Length,
                    bodyCharacterCount = bodyCharacterCount,
                    bodyTooLarge = bodyTooLarge,
                    online = endpointOnline,
                    sending = sending,
                    canSend = canSend,
                    onSend = {
                        val endpoint = activeEndpoint ?: return@DeviceMessageComposer
                        val body = draft
                        sending = true
                        scope.launch {
                            val result = deviceState.sendDeviceMessage(endpoint, body)
                            sending = false
                            if (result.isSuccess) {
                                draft = ""
                            } else {
                                snackbarHostState.showSnackbar(
                                    deviceMessageFailureText(result.exceptionOrNull()),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DeviceMessageBubble(
    message: DeviceStoredMessage,
    canRetry: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(message.localId) { mutableStateOf(false) }
    val outgoing = message.direction == DeviceMessageDirection.Outgoing
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier.width(maxWidth * 0.94f).padding(14.dp),
        ) {
            Row(
                modifier = Modifier.align(
                    if (outgoing) Alignment.End else Alignment.Start,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (outgoing) {
                    Text(
                        text = message.sentAtMillis.timestampToAdaptiveDateTime(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = deviceMessageStatusLabel(message.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = deviceMessageStatusColor(message.status),
                        maxLines = 1,
                    )
                }
                Text(
                    text = if (outgoing) AppStrings.ui_me else AppStrings.device_message_peer,
                    color = if (outgoing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!outgoing) {
                    Text(
                        text = deviceMessageStatusLabel(message.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = deviceMessageStatusColor(message.status),
                        maxLines = 1,
                    )
                    Text(
                        text = message.sentAtMillis.timestampToAdaptiveDateTime(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (expanded) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (shouldCollapseDeviceMessageBody(message.body)) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) {
                            AppStrings.device_message_show_less
                        } else {
                            AppStrings.device_message_show_more
                        },
                    )
                }
            }
            if (canRetry) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(AppStrings.device_message_retry)
                }
            }
        }
    }
}

@Composable
private fun DeviceMessageComposer(
    value: String,
    onValueChange: (String) -> Unit,
    bodyUtf8Length: Int,
    bodyCharacterCount: Int,
    bodyTooLarge: Boolean,
    online: Boolean,
    sending: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, tonalElevation = 2.dp) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!online) {
                Text(
                    text = AppStrings.device_message_offline_send_disabled,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    enabled = online && !sending,
                    minLines = 1,
                    maxLines = 6,
                    isError = bodyTooLarge,
                    placeholder = { Text(AppStrings.device_message_input_placeholder) },
                    supportingText = {
                        Text(
                            if (bodyTooLarge) {
                                AppStrings.device_message_body_too_large
                            } else {
                                AppStrings.device_message_character_count.format(
                                    arg0 = bodyCharacterCount.toString(),
                                )
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = AppStrings.ui_send,
                    )
                }
            }
            if (sending && bodyUtf8Length > 64 * 1024) {
                Text(
                    text = AppStrings.device_message_long_transfer,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun deviceMessageStatusColor(status: DeviceMessageStatus) = when (status) {
    DeviceMessageStatus.Failed -> MaterialTheme.colorScheme.error
    DeviceMessageStatus.Unconfirmed -> MaterialTheme.colorScheme.tertiary
    DeviceMessageStatus.Delivered,
    DeviceMessageStatus.Received -> MaterialTheme.colorScheme.primary

    DeviceMessageStatus.Sending -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun deviceMessageStatusLabel(status: DeviceMessageStatus): String = when (status) {
    DeviceMessageStatus.Sending -> AppStrings.device_message_sending
    DeviceMessageStatus.Delivered -> AppStrings.device_message_delivered
    DeviceMessageStatus.Failed -> AppStrings.device_message_failed
    DeviceMessageStatus.Unconfirmed -> AppStrings.device_message_unconfirmed
    DeviceMessageStatus.Received -> AppStrings.device_message_received
}

private fun deviceMessageFailureText(error: Throwable?): String = when (error) {
    is DeviceMessageValidationException -> AppStrings.device_message_body_too_large
    is DeviceMessagePersistenceException -> AppStrings.device_message_storage_failed
    is DeviceMessageTransferException -> when (error.error) {
        DeviceMessageTransferError.Offline,
        DeviceMessageTransferError.Unauthorized -> AppStrings.device_message_offline_send_disabled

        DeviceMessageTransferError.RateLimited -> AppStrings.device_message_rate_limited
        DeviceMessageTransferError.TransportFailure,
        DeviceMessageTransferError.TransferTimedOut -> AppStrings.device_message_connection_lost

        else -> AppStrings.device_message_send_failed
    }

    else -> AppStrings.device_message_send_failed
}

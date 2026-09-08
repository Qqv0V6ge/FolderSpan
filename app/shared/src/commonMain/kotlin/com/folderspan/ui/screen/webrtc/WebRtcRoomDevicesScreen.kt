package com.folderspan.ui.screen.webrtc

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.device.DeviceType
import com.folderspan.extensions.DeviceIcon
import com.folderspan.service.webrtc.controller.core.WebRtcPeerSessionSummary
import com.folderspan.service.webrtc.controller.core.WebRtcDeviceSessionPhase
import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import com.folderspan.service.webrtc.models.WebRtcPeerSessionDisplayStatus
import com.folderspan.service.webrtc.models.toDisplayLabel
import com.folderspan.service.webrtc.signaling.SignalingDevice
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.screen.device.DeviceListScaffold
import com.folderspan.ui.state.main.DeviceState
import org.koin.compose.koinInject

internal val WebRtcRoomDeviceActionSlotSize = 48.dp
internal val WebRtcRoomPeerDisconnectContentDescription: String
    get() = AppStrings.ui_disconnect
private val WebRtcRoomDeviceProgressIndicatorSize = 24.dp

internal enum class WebRtcRoomPeerItemClickAction {
    Connect,
    Disconnect
}

internal fun resolveWebRtcRoomPeerItemClickAction(
    status: WebRtcConnectionStatus,
    displayStatus: WebRtcPeerSessionDisplayStatus
): WebRtcRoomPeerItemClickAction {
    return if (status == WebRtcConnectionStatus.Connected || displayStatus in busyPeerStatuses) {
        WebRtcRoomPeerItemClickAction.Disconnect
    } else {
        WebRtcRoomPeerItemClickAction.Connect
    }
}

class WebRtcRoomDevicesScreen : AppScreenRoute {
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val deviceState = koinInject<DeviceState>()

        val peerSessionStates by deviceState.webRtcPeerSessionStates.collectAsState()

        var showSearchDialog by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        var searchDraft by remember { mutableStateOf("") }
        var deviceType by remember { mutableStateOf<DeviceType?>(null) }

        val keyword = searchQuery.trim()
        val filteredPeerSessionStates = peerSessionStates.filter { peerState ->
            val matchesType = deviceType == null || peerState.peer.resolveDeviceType() == deviceType
            val matchesKeyword = keyword.isBlank() || peerState.peer.displayName().contains(keyword, ignoreCase = true)
            matchesType && matchesKeyword
        }

        DeviceListScaffold(
            showSearchDialog = showSearchDialog,
            searchQuery = searchQuery,
            searchDraft = searchDraft,
            onSearchDraftChange = { searchDraft = it },
            onSearchOpen = {
                searchDraft = searchQuery
                showSearchDialog = true
            },
            onSearchConfirm = {
                searchQuery = searchDraft.trim()
                showSearchDialog = false
            },
            onSearchDismiss = { showSearchDialog = false },
            selectedDeviceType = deviceType,
            onDeviceTypeChange = { deviceType = it },
            onBackClick = navigator::pop,
            isEmpty = filteredPeerSessionStates.isEmpty(),
        ) {
            items(filteredPeerSessionStates, key = { item -> item.peer.id }) { peerState ->
                RoomPeerCard(
                    peerState = peerState,
                    onConnect = { deviceState.connectWebRtcPeer(peerState.peer.id) },
                    onDisconnect = { deviceState.disconnectWebRtcPeer(peerState.peer.id) }
                )
            }
        }
    }
}

@Composable
private fun RoomPeerCard(
    peerState: WebRtcPeerSessionSummary,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnectedPeer = peerState.status == WebRtcConnectionStatus.Connected
    val isBusyPeer = peerState.displayStatus in busyPeerStatuses
    val itemClickAction = resolveWebRtcRoomPeerItemClickAction(
        status = peerState.status,
        displayStatus = peerState.displayStatus
    )
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = {
                    when (itemClickAction) {
                        WebRtcRoomPeerItemClickAction.Connect -> onConnect()
                        WebRtcRoomPeerItemClickAction.Disconnect -> onDisconnect()
                    }
                }
        ),
        colors = ListItemDefaults.colors(
            containerColor = if (isConnectedPeer) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            headlineColor = if (isConnectedPeer) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            supportingColor = if (isConnectedPeer) {
                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.88f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            overlineColor = if (isConnectedPeer) {
                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.88f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        ),
        overlineContent = { Text(peerState.displayStatus.toDisplayLabel()) },
        headlineContent = {
            Text(
                text = peerState.peer.displayName(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(peerState.sessionPhase.toDisplayLabel())
        },
        leadingContent = {
            val deviceType = peerState.peer.resolveDeviceType()
            if (deviceType != null) {
                deviceType.DeviceIcon()
            } else {
                Icon(Icons.Default.Devices, contentDescription = null)
            }
        },
        trailingContent = {
            when {
                isConnectedPeer -> {
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.Default.Close, contentDescription = WebRtcRoomPeerDisconnectContentDescription)
                    }
                }

                isBusyPeer -> {
                    IconButton(
                        onClick = onDisconnect,
                        modifier = Modifier
                            .size(WebRtcRoomDeviceActionSlotSize)
                            .semantics {
                                contentDescription = WebRtcRoomPeerDisconnectContentDescription
                            }
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(WebRtcRoomDeviceProgressIndicatorSize),
                            strokeWidth = 3.dp
                        )
                    }
                }

                else -> {
                    IconButton(onClick = onConnect) {
                        Icon(Icons.Default.Link, contentDescription = AppStrings.ui_connect_devices)
                    }
                }
            }
        }
    )
}

private fun WebRtcDeviceSessionPhase.toDisplayLabel(): String = when (this) {
    WebRtcDeviceSessionPhase.Signaling -> AppStrings.ui_webrtc_phase_signaling
    WebRtcDeviceSessionPhase.PeerConnection -> AppStrings.ui_webrtc_phase_peer_connection
    WebRtcDeviceSessionPhase.DataChannel -> AppStrings.ui_webrtc_phase_data_channel
    WebRtcDeviceSessionPhase.SessionAuthentication -> AppStrings.ui_webrtc_phase_session_authentication
    WebRtcDeviceSessionPhase.Ready -> AppStrings.ui_webrtc_phase_ready
    WebRtcDeviceSessionPhase.Failed -> AppStrings.ui_webrtc_phase_failed
}

private val busyPeerStatuses = setOf(
    WebRtcPeerSessionDisplayStatus.WaitingForApproval,
    WebRtcPeerSessionDisplayStatus.IceNegotiating,
    WebRtcPeerSessionDisplayStatus.EstablishingDataChannel
)

private fun SignalingDevice.displayName(): String {
    return name?.takeIf { item -> item.isNotBlank() } ?: id
}

private fun SignalingDevice.resolveDeviceType(): DeviceType? {
    return type
        ?.takeIf { item -> item.isNotBlank() }
        ?.let { item -> runCatching { enumValueOf<DeviceType>(item) }.getOrNull() }
}

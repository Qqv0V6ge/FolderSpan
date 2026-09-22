package com.folderspan.ui.components.drawer

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.webrtc.WebRtcRoomProfile
import com.folderspan.data.main.webrtc.matchesActiveConfig
import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import com.folderspan.service.webrtc.models.toRoomDisplayLabel
import com.folderspan.ui.screen.webrtc.WebRtcRoomDevicesScreen
import com.folderspan.ui.screen.webrtc.WebRtcRoomEditScreen
import com.folderspan.ui.screen.webrtc.WebRtcRoomManageScreen
import com.folderspan.ui.screen.webrtc.webRtcRoomDrawerSubtitle
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.DrawerState
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.state.main.WebRtcRoomState
import org.koin.compose.koinInject

internal data class AppDrawerWebRtcUiState(
    val isExpanded: Boolean,
    val hasActiveRoomConfig: Boolean,
    val activeRoom: WebRtcRoomProfile?,
    val pinnedRooms: List<WebRtcRoomProfile>,
    val isConnecting: Boolean,
    val roomName: String,
    val roomSummary: String,
    val onAddRoom: () -> Unit,
    val onManageRooms: () -> Unit,
    val onToggleExpanded: () -> Unit,
    val onOpenActiveRoom: () -> Unit,
    val onDisconnectActiveRoom: () -> Unit,
    val onConnectRoom: (WebRtcRoomProfile) -> Unit,
)

@Composable
internal fun rememberAppDrawerWebRtcUiState(): AppDrawerWebRtcUiState {
    val mainState = koinInject<MainState>()
    val roomState = koinInject<WebRtcRoomState>()
    val drawerState = koinInject<DrawerState>()
    val deviceState = koinInject<DeviceState>()

    val isExpandWebRtc by drawerState.isExpandWebRtc.collectAsState()
    val connectionStatus by deviceState.webRtcConnectionStatus.collectAsState()
    val activeRoomConfig by deviceState.webRtcRoomConfig.collectAsState()
    val peerSessionStates by deviceState.webRtcPeerSessionStates.collectAsState()

    LaunchedEffect(roomState) {
        roomState.loadPersisted()
    }

    val rooms = roomState.rooms.toList()
    val roomSelection = remember(rooms, activeRoomConfig) {
        val activeRoom = rooms.firstOrNull { item -> item.matchesActiveConfig(activeRoomConfig) }
        val pinnedRooms = rooms.filter { item ->
            item.pinned && !item.matchesActiveConfig(activeRoomConfig)
        }
        activeRoom to pinnedRooms
    }
    val activeRoom = roomSelection.first
    val isConnecting = connectionStatus == WebRtcConnectionStatus.Connecting
    val roomName = activeRoom?.name ?: AppStrings.ui_current_room
    val roomSummary = when (connectionStatus) {
        WebRtcConnectionStatus.Connected,
        WebRtcConnectionStatus.Connecting -> AppStrings.ui_arg0_devices.format(arg0 = (peerSessionStates.size).toString())
        else -> connectionStatus.toRoomDisplayLabel()
    }

    return AppDrawerWebRtcUiState(
        isExpanded = isExpandWebRtc,
        hasActiveRoomConfig = activeRoomConfig != null,
        activeRoom = activeRoom,
        pinnedRooms = roomSelection.second,
        isConnecting = isConnecting,
        roomName = roomName,
        roomSummary = roomSummary,
        onAddRoom = { mainState.pushScreen(WebRtcRoomEditScreen()) },
        onManageRooms = { mainState.pushScreen(WebRtcRoomManageScreen()) },
        onToggleExpanded = { drawerState.updateExpandWebRtc(!isExpandWebRtc) },
        onOpenActiveRoom = { mainState.pushScreen(WebRtcRoomDevicesScreen()) },
        onDisconnectActiveRoom = deviceState::disconnectWebRtcRoom,
        onConnectRoom = deviceState::connectWebRtcRoom,
    )
}

internal fun LazyListScope.appDrawerWebRtc(uiState: AppDrawerWebRtcUiState) {
    item(
        key = "drawer_webrtc_header",
        contentType = "drawer_group_header",
    ) {
        AppDrawerHeader(
            title = AppStrings.webrtc_label,
            actions = { AppDrawerWebRtcActions(uiState) },
        )
    }

    if (uiState.isExpanded) {
        if (uiState.hasActiveRoomConfig) {
            item(
                key = "drawer_webrtc_active_room",
                contentType = "drawer_webrtc_active_row",
            ) {
                AppDrawerActiveWebRtcRoomItem(uiState)
            }
        }

        if (uiState.pinnedRooms.isNotEmpty()) {
            if (uiState.hasActiveRoomConfig) {
                item(
                    key = "drawer_webrtc_pinned_spacing",
                    contentType = "drawer_group_spacing",
                ) {
                    Spacer(Modifier.height(6.dp))
                }
            }

            items(
                items = uiState.pinnedRooms,
                key = { room -> "drawer_webrtc_pinned_${room.id}" },
                contentType = { "drawer_webrtc_pinned_row" },
            ) { room ->
                AppDrawerPinnedWebRtcRoomItem(
                    room = room,
                    uiState = uiState,
                )
            }
        }
    }

    item(
        key = "drawer_webrtc_footer",
        contentType = "drawer_group_spacing",
    ) {
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AppDrawerWebRtcActions(uiState: AppDrawerWebRtcUiState) {
    Row {
        Icon(
            Icons.Default.Add,
            null,
            Modifier
                .clip(RoundedCornerShape(25.dp))
                .clickable(onClick = uiState.onAddRoom)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.Settings,
            null,
            Modifier
                .clip(RoundedCornerShape(25.dp))
                .clickable(onClick = uiState.onManageRooms)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            if (uiState.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            null,
            Modifier
                .clip(RoundedCornerShape(25.dp))
                .clickable(onClick = uiState.onToggleExpanded)
        )
    }
}

@Composable
private fun AppDrawerActiveWebRtcRoomItem(uiState: AppDrawerWebRtcUiState) {
    NavigationDrawerItem(
        icon = {
            if (uiState.isConnecting) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            } else {
                Icon(Icons.Default.Link, contentDescription = null)
            }
        },
        label = {
            WebRtcDrawerLabel(
                title = uiState.roomName,
                subtitle = uiState.roomSummary,
            )
        },
        selected = false,
        badge = {
            if (uiState.isConnecting) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.tertiary
                ) {
                    Text(AppStrings.ui_connecting)
                }
            } else {
                Icon(
                    Icons.Default.Close,
                    AppStrings.ui_disconnect,
                    Modifier
                        .clip(RoundedCornerShape(25.dp))
                        .clickable(onClick = uiState.onDisconnectActiveRoom)
                )
            }
        },
        onClick = uiState.onOpenActiveRoom,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}

@Composable
private fun AppDrawerPinnedWebRtcRoomItem(
    room: WebRtcRoomProfile,
    uiState: AppDrawerWebRtcUiState,
) {
    NavigationDrawerItem(
        icon = { Icon(Icons.Default.PushPin, contentDescription = null) },
        label = {
            WebRtcDrawerLabel(
                title = room.name,
                subtitle = webRtcRoomDrawerSubtitle(
                    source = room.source,
                    wssUrl = room.wssUrl,
                ),
            )
        },
        badge = { Badge { Text(AppStrings.ui_not_connected) } },
        selected = false,
        onClick = { uiState.onConnectRoom(room) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}

@Composable
private fun WebRtcDrawerLabel(
    title: String,
    subtitle: String,
) {
    Column {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

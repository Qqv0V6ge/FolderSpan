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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkEntry
import com.folderspan.data.main.network.NetworkShare
import com.folderspan.ui.components.dialog.NetworkConnectDialog
import com.folderspan.ui.screen.network.NetworkAddEntryScreen
import com.folderspan.ui.screen.network.NetworkManageScreen
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.DrawerState
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.state.main.NetworkState
import org.koin.compose.koinInject

internal data class AppDrawerNetworkUiState(
    val currentDesk: DiskBase,
    val isLoading: Boolean,
    val isExpanded: Boolean,
    val connectedEntries: List<NetworkEntry>,
    val pinnedEntries: List<NetworkEntry>,
    val pendingConnect: NetworkEntry?,
    val onAddNetwork: () -> Unit,
    val onManageNetworks: () -> Unit,
    val onToggleExpanded: () -> Unit,
    val onConnectedEntryClick: (NetworkEntry) -> Unit,
    val onDisconnectEntry: (NetworkEntry) -> Unit,
    val onPinnedEntryClick: (NetworkEntry) -> Unit,
    val onDismissConnectDialog: () -> Unit,
    val onConfirmConnectDialog: (NetworkEntry) -> Unit,
)

@Composable
internal fun rememberAppDrawerNetworkUiState(): AppDrawerNetworkUiState {
    val mainState = koinInject<MainState>()
    val fileState = koinInject<FileState>()
    val networkState = koinInject<NetworkState>()
    val drawerState = koinInject<DrawerState>()
    val isExpandNetwork by drawerState.isExpandNetwork.collectAsState()
    val currentDesk by fileState.deskType.collectAsState()
    val isLoading by fileState.isLoading.collectAsState()
    var pendingConnect by remember { mutableStateOf<NetworkEntry?>(null) }

    val entries = networkState.entries.toList()
    val connectedSnapshot = networkState.connectedEntries.toList()
    val connectedEntries = remember(connectedSnapshot) { connectedSnapshot }
    val pinnedEntries = remember(entries, connectedEntries) {
        val connectedIds = connectedEntries.mapTo(hashSetOf()) { entry -> entry.id }
        entries
            .filter { entry ->
                entry.network.pinned && entry.id !in connectedIds
            }
            .sortedBy { entry -> entry.network.name.lowercase() }
    }

    return AppDrawerNetworkUiState(
        currentDesk = currentDesk,
        isLoading = isLoading,
        isExpanded = isExpandNetwork,
        connectedEntries = connectedEntries,
        pinnedEntries = pinnedEntries,
        pendingConnect = pendingConnect,
        onAddNetwork = { mainState.pushScreen(NetworkAddEntryScreen()) },
        onManageNetworks = { mainState.pushScreen(NetworkManageScreen()) },
        onToggleExpanded = { drawerState.updateExpandNetwork(!isExpandNetwork) },
        onConnectedEntryClick = { entry ->
            fileState.updateDesk(FileProtocol.Network, entry.network)
        },
        onDisconnectEntry = { entry ->
            entry.network.disconnect()
            networkState.connectedEntries.removeAll { item -> item.id == entry.id }
            if (currentDesk is Network && currentDesk == entry.network) {
                fileState.updateDesk(FileProtocol.Local, Local())
            }
        },
        onPinnedEntryClick = { entry ->
            if (networkState.connectedEntries.any { item -> item.id == entry.id }) {
                fileState.updateDesk(FileProtocol.Network, entry.network)
            } else {
                pendingConnect = entry
            }
        },
        onDismissConnectDialog = { pendingConnect = null },
        onConfirmConnectDialog = { entry ->
            pendingConnect = null
            networkState.connectEntry(entry)
            fileState.updateDesk(FileProtocol.Network, entry.network)
        },
    )
}

internal fun LazyListScope.appDrawerNetwork(uiState: AppDrawerNetworkUiState) {
    item(
        key = "drawer_network_header",
        contentType = "drawer_group_header",
    ) {
        AppDrawerHeader(
            title = AppStrings.ui_network,
            actions = { AppDrawerNetworkActions(uiState) },
        )
    }

    if (uiState.isExpanded) {
        items(
            items = uiState.connectedEntries,
            key = { entry -> "drawer_network_connected_${entry.id}" },
            contentType = { "drawer_network_connected_row" },
        ) { entry ->
            AppDrawerConnectedNetworkItem(
                entry = entry,
                uiState = uiState,
            )
        }

        if (uiState.pinnedEntries.isNotEmpty()) {
            item(
                key = "drawer_network_pinned_spacing",
                contentType = "drawer_group_spacing",
            ) {
                Spacer(Modifier.height(6.dp))
            }

            items(
                items = uiState.pinnedEntries,
                key = { entry -> "drawer_network_pinned_${entry.id}" },
                contentType = { "drawer_network_pinned_row" },
            ) { entry ->
                AppDrawerPinnedNetworkItem(
                    entry = entry,
                    uiState = uiState,
                )
            }
        }
    }

    item(
        key = "drawer_network_footer",
        contentType = "drawer_group_spacing",
    ) {
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AppDrawerNetworkActions(uiState: AppDrawerNetworkUiState) {
    Row {
        Icon(
            Icons.Default.Add,
            null,
            Modifier.clip(RoundedCornerShape(25.dp))
                .clickable(onClick = uiState.onAddNetwork)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.Settings,
            null,
            Modifier.clip(RoundedCornerShape(25.dp))
                .clickable(onClick = uiState.onManageNetworks)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            if (uiState.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            null,
            Modifier.clip(RoundedCornerShape(25.dp))
                .clickable(onClick = uiState.onToggleExpanded)
        )
    }
}

@Composable
private fun AppDrawerConnectedNetworkItem(
    entry: NetworkEntry,
    uiState: AppDrawerNetworkUiState,
) {
    val network = entry.network
    val protocolLabel = formatProtocolLabel(network)
    val isConnecting = uiState.currentDesk is Network && uiState.currentDesk == network && uiState.isLoading
    NavigationDrawerItem(
        icon = {
            if (isConnecting) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            } else {
                Icon(Icons.Default.Language, contentDescription = null)
            }
        },
        label = { Text("${network.name} ($protocolLabel)") },
        selected = uiState.currentDesk is Network && uiState.currentDesk == network,
        badge = {
            if (isConnecting) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.tertiary
                ) { Text(AppStrings.ui_connecting) }
            } else {
                Icon(
                    Icons.Default.Close,
                    AppStrings.ui_disconnect,
                    Modifier
                        .clip(RoundedCornerShape(25.dp))
                        .clickable { uiState.onDisconnectEntry(entry) }
                )
            }
        },
        onClick = { uiState.onConnectedEntryClick(entry) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}

@Composable
private fun AppDrawerPinnedNetworkItem(
    entry: NetworkEntry,
    uiState: AppDrawerNetworkUiState,
) {
    val network = entry.network
    val protocolLabel = formatProtocolLabel(network)
    NavigationDrawerItem(
        icon = { Icon(Icons.Default.Language, contentDescription = null) },
        label = { Text("${network.name} ($protocolLabel)") },
        selected = uiState.currentDesk is Network && uiState.currentDesk == network,
        onClick = { uiState.onPinnedEntryClick(entry) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}

@Composable
internal fun AppDrawerNetworkDialog(uiState: AppDrawerNetworkUiState) {
    uiState.pendingConnect?.let { entry ->
        NetworkConnectDialog(
            name = entry.network.name,
            onDismissRequest = uiState.onDismissConnectDialog,
            onConfirm = { uiState.onConfirmConnectDialog(entry) }
        )
    }
}

private fun formatProtocolLabel(network: Network): String {
    return if (network is NetworkShare) AppStrings.ui_link_sharing else network.protocol
}

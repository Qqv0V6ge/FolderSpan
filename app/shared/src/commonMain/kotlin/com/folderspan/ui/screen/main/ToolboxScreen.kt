package com.folderspan.ui.screen.main

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.screen.device.DeviceScreen
import com.folderspan.ui.screen.file.filter.FileFilterScreen
import com.folderspan.ui.screen.file.share.FileShareManageScreen
import com.folderspan.ui.screen.mcp.McpManageScreen
import com.folderspan.ui.screen.network.NetworkManageScreen
import com.folderspan.ui.screen.sync.SyncManageScreen
import com.folderspan.ui.screen.webrtc.WebRtcRoomManageScreen
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow

internal class ToolboxScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val tools = listOf(
            ToolboxEntry(
                title = AppStrings.ui_device_management,
                icon = Icons.Default.Devices,
                onClick = { navigator.push(DeviceScreen()) },
            ),
            ToolboxEntry(
                title = AppStrings.ui_cross_network_management,
                icon = Icons.Default.Link,
                onClick = { navigator.push(WebRtcRoomManageScreen()) },
            ),
            ToolboxEntry(
                title = AppStrings.ui_share_management,
                icon = Icons.Default.Share,
                onClick = { navigator.push(FileShareManageScreen()) },
            ),
            ToolboxEntry(
                title = AppStrings.ui_network_management,
                icon = Icons.Default.Language,
                onClick = { navigator.push(NetworkManageScreen()) },
            ),
            ToolboxEntry(
                title = AppStrings.ui_synchronization_management,
                icon = Icons.Default.Sync,
                onClick = { navigator.push(SyncManageScreen) },
            ),
            ToolboxEntry(
                title = AppStrings.ui_filter_type,
                icon = Icons.Default.GridView,
                onClick = { navigator.push(FileFilterScreen()) },
            ),
            ToolboxEntry(
                title = AppStrings.ui_mcp,
                icon = Icons.Default.SettingsEthernet,
                onClick = { navigator.push(McpManageScreen) },
            ),
        )

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_toolbox) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                        }
                    }
                )
            }
        ) { paddingValues ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                items(
                    items = tools,
                    key = { item -> item.title },
                    contentType = { "toolbox_entry" },
                ) { item ->
                    ToolboxGridItem(
                        item = item,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.95f),
                    )
                }
            }
        }
    }
}

private data class ToolboxEntry(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
private fun ToolboxGridItem(
    item: ToolboxEntry,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = item.onClick,
        modifier = modifier,
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(38.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

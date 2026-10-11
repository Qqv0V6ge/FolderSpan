package com.folderspan.ui.screen.settings

import strings.AppStrings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.state.main.DrawerState
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import org.koin.compose.koinInject

/**
 * 设置 -> 页面（二级页面）
 */
class PageSettingsScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val drawerState = koinInject<DrawerState>()
        val isShowDevice by drawerState.isShowDevice.collectAsState()
        val isShowWebRtc by drawerState.isShowWebRtc.collectAsState()
        val isShowNetwork by drawerState.isShowNetwork.collectAsState()
        val isShowSync by drawerState.isShowSync.collectAsState()
        val isFileGridView by drawerState.isFileGridView.collectAsState()

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_page) },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                )
            }
        ) { padding ->
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = AppStrings.ui_sidebar,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item {
                    PageVisibilitySwitchItem(
                        title = AppStrings.ui_lan_devices,
                        subtitle = AppStrings.ui_show_device_page_sidebar,
                        checked = isShowDevice,
                        onCheckedChange = drawerState::updateShowDevice
                    )
                }

                item {
                    PageVisibilitySwitchItem(
                        title = AppStrings.webrtc_label,
                        subtitle = AppStrings.ui_show_webrtc_page_sidebar,
                        checked = isShowWebRtc,
                        onCheckedChange = drawerState::updateShowWebRtc
                    )
                }

                item {
                    PageVisibilitySwitchItem(
                        title = AppStrings.ui_network,
                        subtitle = AppStrings.ui_show_web_pages_sidebar,
                        checked = isShowNetwork,
                        onCheckedChange = drawerState::updateShowNetwork
                    )
                }

                item {
                    PageVisibilitySwitchItem(
                        title = AppStrings.ui_sync,
                        subtitle = AppStrings.ui_show_sync_page_sidebar,
                        checked = isShowSync,
                        onCheckedChange = drawerState::updateShowSync
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = AppStrings.ui_file,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item {
                    PageVisibilitySwitchItem(
                        title = AppStrings.ui_grid_display,
                        subtitle = AppStrings.ui_file_page_uses_grid_layout_display_files,
                        checked = isFileGridView,
                        onCheckedChange = drawerState::updateFileGridView
                    )
                }
            }
        }
    }
}

@Composable
private fun PageVisibilitySwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

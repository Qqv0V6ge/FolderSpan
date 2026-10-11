package com.folderspan.ui.screen.settings

import strings.AppStrings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.folderspan.currentDeviceName
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.state.settings.SettingsState
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import org.koin.compose.koinInject

/**
 * 设置 -> 设备信息（二级页面）
 */
class DeviceInfoScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val settingsState = koinInject<SettingsState>()

        val deviceName = currentDeviceName()
        val deviceId by settingsState.deviceId.collectAsState()
        val deviceType by settingsState.deviceType.collectAsState()

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_device_information) },
                    navigationIcon = {
                        IconButton({ navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, null)
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
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_device_name) },
                        supportingContent = { Text(deviceName) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_device_id) },
                        supportingContent = { Text(deviceId) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_device_type) },
                        supportingContent = { Text(deviceType) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

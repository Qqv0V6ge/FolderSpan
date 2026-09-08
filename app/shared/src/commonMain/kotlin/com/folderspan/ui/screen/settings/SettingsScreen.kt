package com.folderspan.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.folderspan.permission.PlatformPermissionProvider
import com.folderspan.proManualDataSyncScreen
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import strings.AppStrings

/**
 * 设置页面
 */
class SettingsScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val permissions = remember { PlatformPermissionProvider.permissions() }
        val settingsItems = listOfNotNull(
            SettingItem(
                id = "appearance-language",
                title = AppStrings.settings_appearance_language_title,
                subtitle = AppStrings.settings_appearance_language_subtitle,
                onClick = { navigator.push(AppearanceSettingsScreen()) }
            ),
            SettingItem(
                id = "page",
                title = AppStrings.ui_page,
                subtitle = AppStrings.ui_configure_sidebar_display_switch,
                onClick = { navigator.push(PageSettingsScreen()) }
            ),
            proManualDataSyncScreen()?.let { syncScreen ->
                SettingItem(
                    id = "data-sync",
                    title = AppStrings.ui_data_synchronization,
                    subtitle = AppStrings.ui_manually_select_bookmarks_devices_roles_settings_other_data_sync,
                    onClick = { navigator.push(syncScreen) }
                )
            },
            SettingItem(
                id = "file-share",
                title = AppStrings.ui_file_sharing,
                subtitle = AppStrings.ui_configure_file_sharing_service_settings,
                onClick = { navigator.push(FileShareSettingsScreen()) }
            ),
            SettingItem(
                id = "easy-file-share",
                title = AppStrings.ui_quick_sharing,
                subtitle = AppStrings.ui_configure_quick_sharing_service_settings,
                onClick = { navigator.push(EasyFileShareSettingsScreen()) }
            ),
            SettingItem(
                id = "directories",
                title = AppStrings.ui_directory,
                subtitle = AppStrings.ui_view_system_application_directory_paths,
                onClick = { navigator.push(DirectoriesSettingsScreen()) }
            ),
        ).toMutableList()

        if (permissions.isNotEmpty()) {
            settingsItems.add(
                SettingItem(
                    id = "permissions",
                    title = AppStrings.ui_permissions,
                    subtitle = AppStrings.ui_view_request_platform_permissions,
                    onClick = { navigator.push(PermissionSettingsScreen()) }
                )
            )
        }

        settingsItems.add(
            SettingItem(
                id = "device-info",
                title = AppStrings.ui_device_information,
                subtitle = AppStrings.ui_view_edit_current_device_information,
                onClick = { navigator.push(DeviceInfoScreen()) }
            )
        )
        settingsItems.add(
            SettingItem(
                id = "laboratory",
                title = AppStrings.settings_laboratory_title,
                subtitle = AppStrings.settings_laboratory_subtitle,
                onClick = { navigator.push(LaboratorySettingsScreen()) }
            )
        )
        settingsItems.add(
            SettingItem(
                id = "about-software",
                title = AppStrings.settings_about_software_title,
                subtitle = AppStrings.settings_about_software_subtitle,
                onClick = { navigator.push(AboutSoftwareScreen()) }
            )
        )
        settingsItems.add(
            SettingItem(
                id = "developer",
                title = AppStrings.ui_developer_settings,
                subtitle = AppStrings.ui_enter_debugging_testing_tools,
                onClick = { navigator.push(DeveloperSettingsScreen()) }
            )
        )

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.settings_title) },
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
                items(
                    items = settingsItems,
                    key = { item -> item.id }
                ) { item ->
                    ListItem(
                        headlineContent = { Text(item.title) },
                        supportingContent = { Text(item.subtitle) },
                        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = item.onClick)
                    )
                }
            }
        }
    }
}

private data class SettingItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)

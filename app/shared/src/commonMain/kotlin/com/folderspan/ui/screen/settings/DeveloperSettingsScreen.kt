package com.folderspan.ui.screen.settings

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow

/**
 * 设置 -> 开发者设置（二级页面）
 */
class DeveloperSettingsScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_developer_settings) },
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
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_transmission_test) },
                        supportingContent = { Text(AppStrings.ui_test_copy_download_delete_tasks_between_multiple_files_multiple) },
                        trailingContent = {
                            Icon(Icons.Filled.ChevronRight, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navigator.push(TransferTestScreen()) }
                    )
                }
                item {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = AppStrings.developer_cleanup_application_data_title,
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        supportingContent = {
                            Text(AppStrings.developer_cleanup_application_data_description)
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        trailingContent = {
                            Icon(Icons.Filled.ChevronRight, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navigator.push(ApplicationDataCleanupScreen()) }
                    )
                }
            }
        }
    }
}

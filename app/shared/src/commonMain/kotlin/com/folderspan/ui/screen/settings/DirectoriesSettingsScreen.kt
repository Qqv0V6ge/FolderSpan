package com.folderspan.ui.screen.settings

import strings.AppStrings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.Local
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.state.file.FileState
import com.folderspan.utils.getPlatformDirectories
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.navigation.popUntilRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * 设置 -> 目录（二级页面）
 */
class DirectoriesSettingsScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val directories = remember { getPlatformDirectories() }
        val scope = rememberCoroutineScope()
        val fileState = koinInject<FileState>()
        val snackbarHostState = remember { SnackbarHostState() }
        var requestingAccess by remember { mutableStateOf(false) }

        AppScaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_directory) },
                    navigationIcon = {
                        IconButton({ navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, null)
                        }
                    }
                )
            }
        ) { padding ->
            if (directories.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(AppStrings.ui_no_directory_available_yet, style = MaterialTheme.typography.bodyMedium)
                }
                return@AppScaffold
            }

            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(
                    items = directories,
                    key = { item -> item.directory }
                ) { item ->
                    ListItem(
                        headlineContent = { Text(item.title) },
                        supportingContent = {
                            Column {
                                Text(item.description, style = MaterialTheme.typography.bodySmall)
                                if (item.requestAccess == null) {
                                    SelectionContainer {
                                        Text(item.directory, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        },
                        trailingContent = {
                            IconButton(
                                enabled = !requestingAccess,
                                onClick = {
                                    scope.launch {
                                        requestingAccess = true
                                        try {
                                            val access = item.requestAccess?.invoke() ?: Result.success(item.directory)
                                            if (access.isFailure) {
                                                snackbarHostState.showLatestSnackbar(AppStrings.ui_authorize_folder_failed)
                                                return@launch
                                            }
                                            val directory = access.getOrNull() ?: return@launch
                                            withContext(Dispatchers.Default) {
                                                if (fileState.deskType.value is Local) {
                                                    fileState.updatePath(directory)
                                                } else {
                                                    fileState.updateDesk(
                                                        protocol = FileProtocol.Local,
                                                        type = Local(),
                                                        pathOverride = directory
                                                    )
                                                }
                                            }
                                            navigator.popUntilRoot()
                                        } finally {
                                            requestingAccess = false
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Outlined.Folder,
                                    contentDescription = AppStrings.ui_open_arg0.format(arg0 = item.title)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

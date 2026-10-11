package com.folderspan.ui.screen.file.filter

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.GetFileFilterType
import com.folderspan.data.file.displayName
import com.folderspan.extensions.getExtensions
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.dialog.TextFieldDialog
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.state.file.FileFilterState
import com.folderspan.utils.VerificationUtils
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class FileFilterScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow

        val fileFilterState = koinInject<FileFilterState>()

        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_filter_type) },
                    navigationIcon = {
                        IconButton({ navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, null)
                        }
                    },
                    actions = {}
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { fileFilterState.updateCreateDialog(true) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(AppStrings.ui_create_new_action) }
                )
            }
        ) { item ->
            val filterFileTypes = fileFilterState.filterFileTypes
            GridList(
                modifier = Modifier.padding(item),
                floatingActionButtonPadding = GridListFabPadding
            ) {
                items(
                    items = filterFileTypes,
                    key = { fileFilter -> fileFilter.type }
                ) { fileFilter ->
                    val extensions = filterFileTypes.getExtensions(fileFilter.type)
                    ListItem(
                        headlineContent = { Text(fileFilter.displayName()) },
                        supportingContent = if (extensions.isNotEmpty()) {
                            {
                                Text(
                                    extensions.joinToString(", "),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } else {
                            null
                        },
                        leadingContent = { GetFileFilterType(fileFilter.type) },
                        trailingContent = {
                            Icon(
                                Icons.Outlined.Delete,
                                null,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(25.dp))
                                    .clickable {
                                        scope.launch(Dispatchers.Default) {
                                            when (snackbarHostState.showLatestSnackbar(
                                                message = fileFilter.displayName(),
                                                actionLabel = AppStrings.ui_delete,
                                                withDismissAction = true,
                                                duration = SnackbarDuration.Short
                                            )) {
                                                SnackbarResult.Dismissed -> {}
                                                SnackbarResult.ActionPerformed -> {
                                                    fileFilterState.deleteFilter(fileFilter)
                                                }
                                            }
                                        }
                                    }
                            )
                        },
                        modifier = Modifier.clickable {
                            navigator.push(FileFilterManagerScreen(fileFilter.id))
                        }
                    )
                }
            }
        }


        DialogContent()
    }

    @Composable
    fun DialogContent() {
        val fileFilterState = koinInject<FileFilterState>()
        val isCreateDialog by fileFilterState.isCreateDialog.collectAsState()
        val scope = rememberCoroutineScope()

        if (isCreateDialog) {
            TextFieldDialog(
                AppStrings.ui_new_type,
                label = AppStrings.ui_name,
                verifyFun = { text -> VerificationUtils.filterType(text, fileFilterState.filterFileTypes) }
            ) { item ->
                fileFilterState.updateCreateDialog(false)
                if (item.isEmpty()) return@TextFieldDialog
                scope.launch {
                    fileFilterState.createFilter(item)
                }
            }
        }
    }
}

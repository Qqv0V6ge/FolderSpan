package com.folderspan.ui.screen.file.filter

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.displayName
import com.folderspan.db.FileFilter
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

class FileFilterManagerScreen(private val filterId: Long) : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow

        val fileFilterState = koinInject<FileFilterState>()
        var fileFilter by remember { mutableStateOf<FileFilter?>(null) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(filterId) {
            fileFilter = fileFilterState.getFileFilter(filterId)
        }

        val snackbarHostState = remember { SnackbarHostState() }
        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_arg0_filter_type.format(arg0 = fileFilter?.displayName().orEmpty())) },
                    navigationIcon = {
                        IconButton({
                            // TODO 这里应该是HomeNavigator文件处理
                            scope.launch {
                                fileFilterState.syncFilterFileTypes()
                            }
                            navigator.pop()
                        }) {
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
            val currentFilter = fileFilter ?: return@AppScaffold
            val extensions = currentFilter.extensions
            GridList(
                modifier = Modifier.padding(item),
                floatingActionButtonPadding = GridListFabPadding
            ) {
                itemsIndexed(
                    items = extensions,
                    key = { _, type -> type }
                ) { index, type ->
                    ListItem(
                        headlineContent = { Text(type) },
                        trailingContent = {
                            Icon(
                                Icons.Outlined.Delete,
                                null,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(25.dp))
                                    .clickable {
                                        scope.launch(Dispatchers.Default) {
                                            when (snackbarHostState.showSnackbar(
                                                message = type,
                                                actionLabel = AppStrings.ui_delete,
                                                withDismissAction = true,
                                                duration = SnackbarDuration.Short
                                            )) {
                                                SnackbarResult.Dismissed -> {}
                                                SnackbarResult.ActionPerformed -> {
                                                    fileFilterState.updateFileFilter(
                                                        extensions
                                                            .toMutableList().apply {
                                                                removeAt(index)
                                                            },
                                                        currentFilter.id
                                                    )
                                                    fileFilter = fileFilterState.getFileFilter(filterId)
                                                }
                                            }
                                        }
                                    })
                        },
                        modifier = Modifier
                            .clickable {}
                    )
                }
            }
        }


        fileFilter?.let { current ->
            DialogContent(current) { updated ->
                fileFilter = updated
            }
        }
    }


    @Composable
    fun DialogContent(fileFilter: FileFilter, onUpdateFilter: (FileFilter) -> Unit) {
        val fileFilterState = koinInject<FileFilterState>()
        val isCreateDialog by fileFilterState.isCreateDialog.collectAsState()
        val scope = rememberCoroutineScope()

        if (isCreateDialog) {
            TextFieldDialog(
                AppStrings.ui_add_extension,
                label = AppStrings.ui_name,
                verifyFun = { text -> VerificationUtils.filterExtensions(text, fileFilter.extensions) }
            ) { item ->
                fileFilterState.updateCreateDialog(false)
                if (item.isEmpty()) return@TextFieldDialog
                val extension = if (item.indexOf(".") == 0) item else ".$item"
                scope.launch {
                    fileFilterState.updateFileFilter(
                        fileFilter.extensions
                            .toMutableList().apply {
                                add(extension)
                            },
                        fileFilter.id
                    )
                    onUpdateFilter(fileFilterState.getFileFilter(filterId))
                }
            }
        }
    }
}

package com.folderspan.ui.screen.file.share

import strings.AppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileShareHistory
import com.folderspan.data.file.ShareHistoryDirection
import com.folderspan.data.file.ShareHistoryStore
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.fileshare.FileShareHistoryDetailDialog
import com.folderspan.ui.components.fileshare.FileShareHistoryListItem
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.state.file.FileShareStatus
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class FileShareHistoryScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val historyStore = koinInject<ShareHistoryStore>()

        var histories by remember { mutableStateOf(emptyList<FileShareHistory>()) }
        var showDetailDialog by remember { mutableStateOf(false) }
        var selectedHistory by remember { mutableStateOf<FileShareHistory?>(null) }
        var showOutgoingOnly by remember { mutableStateOf(true) }
        var showIncomingOnly by remember { mutableStateOf(false) }
        var statusFilter by remember { mutableStateOf<FileShareStatus?>(null) }

        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        val statusOptions = mapOf(
            null to AppStrings.ui_all,
            FileShareStatus.WAITING to AppStrings.ui_waiting,
            FileShareStatus.SENDING to AppStrings.ui_transmitting,
            FileShareStatus.COMPLETED to AppStrings.ui_completed,
            FileShareStatus.REJECTED to AppStrings.ui_rejected,
            FileShareStatus.ERROR to AppStrings.ui_error
        )

        suspend fun reloadHistories(): List<FileShareHistory> {
            val direction = when {
                showOutgoingOnly -> ShareHistoryDirection.OUTGOING
                showIncomingOnly -> ShareHistoryDirection.INCOMING
                else -> null
            }
            return kotlinx.coroutines.withContext(Dispatchers.Default) {
                historyStore.query(direction = direction, status = statusFilter)
            }
        }

        LaunchedEffect(showOutgoingOnly, showIncomingOnly, statusFilter) {
            histories = reloadHistories()
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_share_history) },
                    navigationIcon = {
                        IconButton(navigator::pop) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, null)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            scope.launch(Dispatchers.Default) {
                                when (snackbarHostState.showLatestSnackbar(
                                    message = AppStrings.ui_you_sure_you_want_clear_all_history,
                                    actionLabel = AppStrings.ui_clear,
                                    duration = SnackbarDuration.Short,
                                )) {
                                    SnackbarResult.Dismissed -> {}
                                    SnackbarResult.ActionPerformed -> {
                                        historyStore.clear()
                                        histories = emptyList()
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = AppStrings.ui_clear_history)
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = showOutgoingOnly,
                            onClick = {
                                showOutgoingOnly = true
                                showIncomingOnly = false
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text(AppStrings.ui_send) }
                        SegmentedButton(
                            selected = showIncomingOnly,
                            onClick = {
                                showOutgoingOnly = false
                                showIncomingOnly = true
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text(AppStrings.ui_receive) }
                    }

                    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
                        var showStatusMenu by remember { mutableStateOf(false) }
                        TextButton(
                            onClick = { showStatusMenu = true },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (statusFilter != null) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        ) {
                            Text(statusOptions[statusFilter] ?: AppStrings.ui_all)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.Tune, contentDescription = AppStrings.ui_status_filter)
                        }
                        DropdownMenu(
                            expanded = showStatusMenu,
                            onDismissRequest = { showStatusMenu = false }
                        ) {
                            statusOptions.forEach { (key, value) ->
                                DropdownMenuItem(
                                    text = { Text(value) },
                                    onClick = {
                                        statusFilter = key
                                        showStatusMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                GridList(
                    modifier = Modifier.fillMaxSize(),
                    isEmpty = histories.isEmpty(),
                ) {
                    items(
                        items = histories,
                        key = { history -> history.id }
                    ) { history ->
                        FileShareHistoryListItem(
                            history = history,
                            onDelete = {
                                scope.launch(Dispatchers.Default) {
                                    when (snackbarHostState.showLatestSnackbar(
                                        message = AppStrings.ui_you_sure_delete_this_record,
                                        actionLabel = AppStrings.ui_delete,
                                        duration = SnackbarDuration.Short,
                                    )) {
                                    SnackbarResult.Dismissed -> {}
                                    SnackbarResult.ActionPerformed -> {
                                            historyStore.delete(history.id)
                                            histories = reloadHistories()
                                        }
                                    }
                                }
                            },
                            onClick = {
                                selectedHistory = history
                                showDetailDialog = true
                            }
                        )
                    }
                }
            }

            if (showDetailDialog && selectedHistory != null) {
                FileShareHistoryDetailDialog(
                    history = selectedHistory!!,
                    onDismiss = { showDetailDialog = false }
                )
            }
        }
    }
}

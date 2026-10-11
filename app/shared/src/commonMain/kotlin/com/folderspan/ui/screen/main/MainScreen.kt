package com.folderspan.ui.screen.main

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folderspan.ProContentProviders
import com.folderspan.ui.components.drawer.AppDrawerContainer
import com.folderspan.ui.components.file.FileDragPreview
import com.folderspan.ui.components.file.FileSelectorDependencies
import com.folderspan.ui.components.file.LocalFileSelectorDependencies
import com.folderspan.ui.components.scaffold.AppScaffoldBannerUiState
import com.folderspan.ui.components.scaffold.LocalAppScaffoldBannerUiState
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.state.main.NotificationState
import com.folderspan.ui.state.file.FileBookmarkState
import com.folderspan.ui.state.file.FileFilterState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.FileDropSessionRegistry
import com.folderspan.ui.components.dialog.FileWarningOperationDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.delay
import strings.AppStrings
import com.folderspan.utils.WindowSizeClass
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.koinInject

@Composable
fun MainScreen(
    screenType: WindowSizeClass,
    homeNavigatorContent: @Composable () -> Unit,
) {
    val mainState = koinInject<MainState>()
    val fileState = koinInject<FileState>()
    val fileDragSession by FileDropSessionRegistry.session.collectAsState()
    val dropConflictVisible by fileState.dropOperationState.isWarningOperationDialog.collectAsState()
    val dropSnackbarHostState = remember { SnackbarHostState() }
    val dragEntryWidth = with(LocalDensity.current) { 32.dp.toPx() }
    val dragNearDrawer = fileDragSession?.let { it.horizontal <= dragEntryWidth } == true
    LaunchedEffect(dragNearDrawer) {
        if (dragNearDrawer && !mainState.isExpandDrawer.value) {
            delay(350L)
            mainState.updateExpandDrawer(true)
        }
    }
    LaunchedEffect(fileState) {
        fileState.dropCopyCoordinator.receipts.collect { path ->
            dropSnackbarHostState.showSnackbar(AppStrings.ui_drop_copy_received.format(arg0 = path))
        }
    }
    DisposableEffect(fileState) {
        onDispose {
            FileDropSessionRegistry.end()
            fileState.dropCopyCoordinator.dispose()
        }
    }
    val notificationState = koinInject<NotificationState>()
    val fileFilterState = koinInject<FileFilterState>()
    val fileBookmarkState = koinInject<FileBookmarkState>()
    LaunchedEffect(fileBookmarkState) {
        fileBookmarkState.load()
    }
    val expandDrawer by mainState.isExpandDrawer.collectAsState()
    val notificationRevision = notificationState.revision
    val existingNotificationIds = remember(notificationRevision) {
        notificationState.notifications.map { notification -> notification.id }.toSet()
    }
    LaunchedEffect(existingNotificationIds) {
        notificationState.pruneIgnoredBanners(existingNotificationIds)
    }
    val ignoredBannerIds = notificationState.ignoredBannerIds.toSet()
    val latestUnread = remember(notificationRevision, ignoredBannerIds) {
        notificationState.notifications
            .filter { notification -> !notification.isRead && notification.shouldShowInBanner() }
            .maxByOrNull { notification -> notification.timestamp }
            ?.takeIf { notification -> notification.id !in ignoredBannerIds }
    }
    val scaffoldBannerUiState = AppScaffoldBannerUiState(
        notification = latestUnread,
        onOpen = { notification ->
            mainState.requestOpenScreen(NotificationDetailScreen(notification))
        },
        onDismiss = { notification -> notificationState.ignoreBanner(notification.id) },
    )
    val fileSelectorDependencies = FileSelectorDependencies(
        configuredFileFilters = fileFilterState.filterFileTypes.toList(),
        bookmarks = fileBookmarkState.bookmarks.toList(),
    )
    val compactDrawerState = rememberDrawerState(
        initialValue = if (expandDrawer) DrawerValue.Open else DrawerValue.Closed,
        confirmStateChange = { true },
    )

    ProContentProviders {
        CompositionLocalProvider(
            LocalAppScaffoldBannerUiState provides scaffoldBannerUiState,
            LocalFileSelectorDependencies provides fileSelectorDependencies,
        ) {
            BoxWithConstraints(modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown && FileDropSessionRegistry.session.value != null) {
                    FileDropSessionRegistry.end()
                    true
                } else false
            }) {
                val useModalNavigationDrawer = screenType == WindowSizeClass.Compact ||
                    shouldUseModalNavigationDrawer(
                        screenWidth = maxWidth,
                        drawerWidth = DrawerDefaults.MaximumDrawerWidth,
                    )

                SideEffect {
                    mainState.updateNavigationDrawerPresentation(useModalNavigationDrawer)
                }

                DisposableEffect(mainState) {
                    onDispose {
                        mainState.updateNavigationDrawerPresentation(false)
                    }
                }

                LaunchedEffect(useModalNavigationDrawer, compactDrawerState) {
                    if (!useModalNavigationDrawer) {
                        return@LaunchedEffect
                    }

                    snapshotFlow { compactDrawerState.currentValue }
                        .distinctUntilChanged()
                        .collect { currentValue ->
                            val isOpen = currentValue == DrawerValue.Open
                            val currentExpandDrawer = mainState.isExpandDrawer.value

                            if (currentExpandDrawer != isOpen) {
                                mainState.updateExpandDrawer(isOpen)
                            }
                        }
                }

                LaunchedEffect(useModalNavigationDrawer, expandDrawer) {
                    if (!useModalNavigationDrawer) {
                        return@LaunchedEffect
                    }

                    val targetValue = if (expandDrawer) DrawerValue.Open else DrawerValue.Closed
                    if (compactDrawerState.targetValue == targetValue) {
                        return@LaunchedEffect
                    }

                    if (targetValue == DrawerValue.Open) {
                        compactDrawerState.open()
                    } else {
                        compactDrawerState.close()
                    }
                }

                if (useModalNavigationDrawer) {
                    ModalNavigationDrawer(
                        drawerState = compactDrawerState,
                        gesturesEnabled = fileDragSession == null,
                        drawerContent = {
                            AppDrawerContainer()
                        },
                    ) {
                        homeNavigatorContent()
                    }
                } else {
                    Row(Modifier.background(colorScheme.background)) {
                        AnimatedVisibility(
                            visible = expandDrawer,
                            enter = expandHorizontally() + fadeIn(),
                            exit = shrinkHorizontally() + fadeOut(),
                        ) {
                            AppDrawerContainer()
                        }
                        homeNavigatorContent()
                    }
                }
                SnackbarHost(dropSnackbarHostState, Modifier.align(Alignment.BottomCenter))
                FileDragPreview(fileDragSession)
            }
        }
    }
    if (dropConflictVisible) {
        val operationState = fileState.dropOperationState
        FileWarningOperationDialog(
            fileOperations = operationState.files.filter { it.isConflict },
            onOperationTypeChange = { operation, type ->
                val index = operationState.files.indexOf(operation)
                if (index >= 0) operationState.files[index] = operation.withCopy(type = type)
            },
            onConfirm = { operationState.updateWarningOperationDialog(false) },
            onCancel = {
                operationState.files.clear()
                operationState.updateWarningOperationDialog(false)
            },
        )
    }
}

internal fun shouldUseModalNavigationDrawer(screenWidth: Dp, drawerWidth: Dp): Boolean =
    screenWidth < drawerWidth * 2f

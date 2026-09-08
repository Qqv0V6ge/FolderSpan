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
import androidx.compose.ui.unit.Dp
import com.folderspan.ProContentProviders
import com.folderspan.ui.components.drawer.AppDrawerContainer
import com.folderspan.ui.components.file.FileSelectorDependencies
import com.folderspan.ui.components.file.LocalFileSelectorDependencies
import com.folderspan.ui.components.scaffold.AppScaffoldBannerUiState
import com.folderspan.ui.components.scaffold.LocalAppScaffoldBannerUiState
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.state.main.NotificationState
import com.folderspan.ui.state.file.FileBookmarkState
import com.folderspan.ui.state.file.FileFilterState
import com.folderspan.utils.WindowSizeClass
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.koinInject

@Composable
fun MainScreen(
    screenType: WindowSizeClass,
    homeNavigatorContent: @Composable () -> Unit,
) {
    val mainState = koinInject<MainState>()
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
            BoxWithConstraints {
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
            }
        }
    }
}

internal fun shouldUseModalNavigationDrawer(screenWidth: Dp, drawerWidth: Dp): Boolean =
    screenWidth < drawerWidth * 2f

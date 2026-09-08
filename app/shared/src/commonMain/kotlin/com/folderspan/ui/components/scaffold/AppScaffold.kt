package com.folderspan.ui.components.scaffold

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.folderspan.ui.components.banner.MaterialBanner
import com.folderspan.ui.state.main.Notification
import com.folderspan.ui.state.main.NotificationType

data class AppScaffoldBannerUiState(
    val notification: Notification? = null,
    val onOpen: (Notification) -> Unit = {},
    val onDismiss: (Notification) -> Unit = {},
)

val LocalAppScaffoldBannerUiState = staticCompositionLocalOf { AppScaffoldBannerUiState() }

@Composable
fun AppScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: (@Composable () -> Unit)? = null,
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    bannerUiState: AppScaffoldBannerUiState = LocalAppScaffoldBannerUiState.current,
    content: @Composable (PaddingValues) -> Unit
) {
    val latestUnread = bannerUiState.notification
    val showBanner = latestUnread != null
    val hasFloatingContent = floatingActionButton != null || showBanner
    val effectiveFabPosition = if (showBanner) FabPosition.Center else floatingActionButtonPosition

    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButtonPosition = effectiveFabPosition,
        containerColor = containerColor,
        contentColor = contentColor,
        contentWindowInsets = contentWindowInsets,
        floatingActionButton = {
            if (hasFloatingContent) {
                Column(
                    modifier = if (showBanner) {
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    } else {
                        Modifier
                    },
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (floatingActionButton != null) {
                        floatingActionButton()
                    }
                    if (showBanner) {
                        val iconTint = when (latestUnread.type) {
                            NotificationType.Info -> MaterialTheme.colorScheme.primary
                            NotificationType.Warning -> MaterialTheme.colorScheme.tertiary
                            NotificationType.Error -> MaterialTheme.colorScheme.error
                            NotificationType.Success -> MaterialTheme.colorScheme.primary
                            NotificationType.Promotion -> MaterialTheme.colorScheme.secondary
                        }
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            val bannerModifier = if (maxWidth > 1024.dp) {
                                Modifier.width(1024.dp)
                            } else {
                                Modifier.fillMaxWidth()
                            }
                            MaterialBanner(
                                modifier = bannerModifier,
                                title = latestUnread.title,
                                message = latestUnread.message,
                                icon = latestUnread.getIcon(),
                                iconTint = iconTint,
                                iconBackground = iconTint.copy(alpha = 0.2f),
                                onActionClick = { bannerUiState.onOpen(latestUnread) },
                                onDismiss = { bannerUiState.onDismiss(latestUnread) }
                            )
                        }
                    }
                }
            }
        },
        content = content
    )
}

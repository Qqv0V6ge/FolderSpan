package com.folderspan.ui.components.pagestate

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.folderspan.ui.components.error.ErrorBase
import com.folderspan.ui.components.error.ErrorBlock
import com.folderspan.ui.components.error.ErrorConnection
import com.folderspan.ui.components.error.ErrorEmptyData
import com.folderspan.ui.components.loading.LoadingBase

@Composable
fun PageStateLayout(
    state: PageViewState,
    modifier: Modifier = Modifier,
    refreshState: PageRefreshState = PageRefreshState.Idle,
    onRefresh: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onRetryRefresh: (() -> Unit)? = null,
    fillMaxSize: Boolean = true,
    loading: (@Composable () -> Unit)? = null,
    error: (@Composable (PageErrorState) -> Unit)? = null,
    empty: (@Composable (String?) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val isRefreshing = refreshState is PageRefreshState.Refreshing
    val layoutModifier = if (fillMaxSize) modifier.fillMaxSize() else modifier
    when (state) {
        PageViewState.Loading -> {
            Box(
                modifier = layoutModifier
                    .testTag(PageStateTestTags.Loading),
                contentAlignment = Alignment.Center,
            ) {
                loading?.invoke() ?: LoadingBase()
            }
        }

        is PageViewState.Error -> {
            RefreshableStatusContainer(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = layoutModifier.testTag(PageStateTestTags.Error),
                fillMaxSize = fillMaxSize,
            ) {
                error?.invoke(state.error) ?: PageErrorContent(
                    error = state.error,
                    onRetry = onRetry,
                )
            }
        }

        is PageViewState.Empty -> {
            RefreshableStatusContainer(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = layoutModifier.testTag(PageStateTestTags.Empty),
                fillMaxSize = fillMaxSize,
            ) {
                empty?.invoke(state.message) ?: ErrorEmptyData(
                    message = state.message ?: AppStrings.ui_data_not_found,
                    actionLabel = onRetry?.let { AppStrings.ui_try_again },
                    onAction = onRetry,
                )
            }
        }

        PageViewState.Content -> {
            Column(
                modifier = layoutModifier.testTag(PageStateTestTags.Content),
            ) {
                if (refreshState is PageRefreshState.Error) {
                    PageRefreshErrorBanner(
                        message = refreshState.message,
                        onRetry = onRetryRefresh ?: onRefresh,
                    )
                }
                if (fillMaxSize) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        RefreshableContentContainer(
                            isRefreshing = isRefreshing,
                            onRefresh = onRefresh,
                            fillMaxSize = true,
                        ) {
                            content()
                        }
                    }
                } else {
                    RefreshableContentContainer(
                        isRefreshing = isRefreshing,
                        onRefresh = onRefresh,
                        fillMaxSize = false,
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
fun PageErrorContent(
    error: PageErrorState,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val actionLabel = onRetry?.let { AppStrings.ui_try_again }
    when (error.type) {
        PageErrorType.Authority -> ErrorBlock(
            message = error.message ?: AppStrings.message_task_permission_denied,
            modifier = modifier,
            actionLabel = actionLabel,
            onAction = onRetry,
        )

        PageErrorType.Cancellation -> ErrorBlock(
            message = AppStrings.ui_lost_connection,
            modifier = modifier,
            actionLabel = actionLabel,
            onAction = onRetry,
        )

        PageErrorType.Connection -> ErrorConnection(
            message = AppStrings.ui_connection_refused_please_reconnect,
            modifier = modifier,
            actionLabel = actionLabel,
            onAction = onRetry,
        )

        PageErrorType.General -> ErrorBase(
            text = AppStrings.ui_exception_occurs_arg0.format(
                arg0 = error.message ?: AppStrings.ui_operation_failed,
            ),
            modifier = modifier,
            actionLabel = actionLabel,
            onAction = onRetry,
        )
    }
}

@Composable
fun PageRefreshErrorBanner(
    message: String?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PageStateTestTags.RefreshError)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = PageStateDefaults.RefreshBannerHorizontalPadding,
                vertical = PageStateDefaults.RefreshBannerVerticalPadding,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PageStateDefaults.RefreshBannerVerticalPadding),
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null)
            Text(
                text = message?.takeIf { it.isNotBlank() } ?: AppStrings.ui_loading_failed,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (onRetry != null) {
                FilledTonalButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(AppStrings.ui_try_again)
                }
            }
        }
    }
}

@Composable
private fun RefreshableStatusContainer(
    isRefreshing: Boolean,
    onRefresh: (() -> Unit)?,
    modifier: Modifier = Modifier,
    fillMaxSize: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (onRefresh == null && !isRefreshing) {
        Box(modifier, contentAlignment = Alignment.Center) {
            content()
        }
        return
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh ?: {},
        modifier = modifier,
    ) {
        if (fillMaxSize) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = maxHeight)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    content()
                }
            }
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

@Composable
private fun RefreshableContentContainer(
    isRefreshing: Boolean,
    onRefresh: (() -> Unit)?,
    fillMaxSize: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val containerModifier = if (fillMaxSize) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
    if (onRefresh == null && !isRefreshing) {
        Box(containerModifier, content = content)
        return
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh ?: {},
        modifier = containerModifier,
        content = content,
    )
}

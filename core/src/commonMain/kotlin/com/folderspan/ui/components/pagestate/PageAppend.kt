package com.folderspan.ui.components.pagestate

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

private const val AppendFooterKey = "page-state-append-footer"
private const val AppendFooterContentType = "page-state-append-footer"

fun LazyGridScope.pageAppendFooter(
    state: PageAppendState,
    onRetry: (() -> Unit)? = null,
) {
    if (state is PageAppendState.Idle) return
    item(
        key = AppendFooterKey,
        span = { GridItemSpan(maxLineSpan) },
        contentType = AppendFooterContentType,
    ) {
        PageAppendFooter(state = state, onRetry = onRetry)
    }
}

fun LazyListScope.pageAppendFooter(
    state: PageAppendState,
    onRetry: (() -> Unit)? = null,
) {
    if (state is PageAppendState.Idle) return
    item(
        key = AppendFooterKey,
        contentType = AppendFooterContentType,
    ) {
        PageAppendFooter(state = state, onRetry = onRetry)
    }
}

@Composable
fun PageAppendFooter(
    state: PageAppendState,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (state is PageAppendState.Idle) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PageStateDefaults.AppendFooterMinHeight)
            .testTag(PageStateTestTags.AppendFooter)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            PageAppendState.Idle -> Unit
            PageAppendState.Loading -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = AppStrings.ui_loading_more,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is PageAppendState.Error -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = state.message?.takeIf { it.isNotBlank() }
                            ?: AppStrings.ui_failed_load_more,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (onRetry != null) {
                        FilledTonalButton(onClick = onRetry) {
                            Text(AppStrings.ui_try_again)
                        }
                    }
                }
            }

            PageAppendState.End -> {
                Text(
                    text = AppStrings.ui_no_more_data,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun LoadMoreOnNearEnd(
    state: LazyGridState,
    enabled: Boolean,
    onLoadMore: () -> Unit,
    prefetchDistance: Int = PageStateDefaults.AppendPrefetchDistance,
) {
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state, enabled, prefetchDistance) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible to state.layoutInfo.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (shouldTriggerLoadMore(lastVisible, total, prefetchDistance)) {
                    currentOnLoadMore()
                }
            }
    }
}

@Composable
fun LoadMoreOnNearEnd(
    state: LazyListState,
    enabled: Boolean,
    onLoadMore: () -> Unit,
    prefetchDistance: Int = PageStateDefaults.AppendPrefetchDistance,
) {
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state, enabled, prefetchDistance) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible to state.layoutInfo.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (shouldTriggerLoadMore(lastVisible, total, prefetchDistance)) {
                    currentOnLoadMore()
                }
            }
    }
}

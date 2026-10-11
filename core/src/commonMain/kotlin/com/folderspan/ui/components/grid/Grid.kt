package com.folderspan.ui.components.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folderspan.ui.components.pagestate.LoadMoreOnNearEnd
import com.folderspan.ui.components.pagestate.PageAppendState
import com.folderspan.ui.components.pagestate.PageErrorState
import com.folderspan.ui.components.pagestate.PageErrorType
import com.folderspan.ui.components.pagestate.PageRefreshState
import com.folderspan.ui.components.pagestate.PageStateLayout
import com.folderspan.ui.components.pagestate.PageViewState
import com.folderspan.ui.components.pagestate.pageAppendFooter
import com.folderspan.ui.components.pagestate.resolvePageViewState
import com.folderspan.ui.components.pagestate.toPageErrorState
import com.folderspan.utils.calculateGridColumnCount
import kotlin.math.max

// 默认的浮动按钮占位高度（56dp 按钮高度 + 32dp 上下间距）
val GridListFabPadding = 88.dp

typealias GridListErrorType = PageErrorType
typealias GridListErrorState = PageErrorState

fun Throwable.toGridListErrorState(): GridListErrorState? = toPageErrorState()

@Composable
fun GridList(
    isLoading: Boolean = false,
    errorState: GridListErrorState? = null,
    modifier: Modifier = Modifier,
    isEmpty: Boolean = false,
    emptyMessage: String? = null,
    refreshState: PageRefreshState = PageRefreshState.Idle,
    appendState: PageAppendState = PageAppendState.Idle,
    onRefresh: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onRetryRefresh: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    onRetryAppend: (() -> Unit)? = null,
    state: LazyGridState = rememberLazyGridState(),
    floatingActionButtonPadding: Dp = 0.dp,
    verticalSpacing: Dp = 0.dp,
    horizontalSpacing: Dp = 0.dp,
    adaptiveMinCellSize: Dp? = null,
    fixedColumnCount: Int? = null,
    minColumnCount: Int = 1,
    content: LazyGridScope.() -> Unit,
) {
    val pageState = resolvePageViewState(
        isLoading = isLoading,
        errorState = errorState,
        isEmpty = isEmpty,
        emptyMessage = emptyMessage,
    )
    if (onLoadMore != null) {
        LoadMoreOnNearEnd(
            state = state,
            enabled = pageState is PageViewState.Content && appendState is PageAppendState.Idle,
            onLoadMore = onLoadMore,
        )
    }
    PageStateLayout(
        state = pageState,
        modifier = modifier,
        refreshState = refreshState,
        onRefresh = onRefresh,
        onRetry = onRetry,
        onRetryRefresh = onRetryRefresh,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val resolvedMinColumnCount = minColumnCount.coerceAtLeast(1)
            val columns = adaptiveMinCellSize?.let { size ->
                GridCells.Adaptive(size)
            } ?: run {
                val columnCount = fixedColumnCount?.coerceAtLeast(1)
                    ?: max(calculateGridColumnCount(maxWidth, maxHeight), resolvedMinColumnCount)
                GridCells.Fixed(columnCount)
            }
            LazyVerticalGrid(
                columns = columns,
                state = state,
                verticalArrangement = Arrangement.spacedBy(verticalSpacing),
                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            ) {
                content()
                pageAppendFooter(
                    state = appendState,
                    onRetry = onRetryAppend ?: onLoadMore,
                )
                if (floatingActionButtonPadding > 0.dp) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Spacer(Modifier.height(floatingActionButtonPadding))
                    }
                }
            }
        }
    }
}

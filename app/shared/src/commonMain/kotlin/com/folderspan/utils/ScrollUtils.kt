package com.folderspan.utils

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.withFrameNanos

suspend fun LazyListState.scrollToItemAfterFrame(index: Int, scrollOffset: Int = 0) {
    withFrameNanos { }
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return
    scrollToItem(index.coerceIn(0, total - 1), scrollOffset.coerceAtLeast(0))
}

suspend fun LazyGridState.scrollToItemAfterFrame(index: Int, scrollOffset: Int = 0) {
    withFrameNanos { }
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return
    scrollToItem(index.coerceIn(0, total - 1), scrollOffset.coerceAtLeast(0))
}

suspend fun ScrollState.scrollToAfterFrame(value: Int) {
    withFrameNanos { }
    scrollTo(value.coerceAtLeast(0))
}

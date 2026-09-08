package com.folderspan.ui.components.pagestate

import strings.AppStrings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.folderspan.ui.components.grid.GridList

@Preview(name = "Loading", showBackground = true)
@Composable
private fun PageStateLoadingPreview() {
    PageStatePreviewScaffold {
        PageStateLayout(state = PageViewState.Loading) {
            PageStatePreviewItems()
        }
    }
}

@Preview(name = "Error Authority", showBackground = true)
@Composable
private fun PageStateErrorAuthorityPreview() {
    PageStatePreviewScaffold {
        PageStateLayout(
            state = PageViewState.Error(PageErrorState(PageErrorType.Authority)),
            onRetry = {},
        ) {
            PageStatePreviewItems()
        }
    }
}

@Preview(name = "Error Cancellation", showBackground = true)
@Composable
private fun PageStateErrorCancellationPreview() {
    PageStatePreviewScaffold {
        PageStateLayout(
            state = PageViewState.Error(PageErrorState(PageErrorType.Cancellation)),
            onRetry = {},
        ) {
            PageStatePreviewItems()
        }
    }
}

@Preview(name = "Error Connection", showBackground = true)
@Composable
private fun PageStateErrorConnectionPreview() {
    PageStatePreviewScaffold {
        PageStateLayout(
            state = PageViewState.Error(PageErrorState(PageErrorType.Connection)),
            onRetry = {},
        ) {
            PageStatePreviewItems()
        }
    }
}

@Preview(name = "Error General", showBackground = true)
@Composable
private fun PageStateErrorGeneralPreview() {
    PageStatePreviewScaffold {
        PageStateLayout(
            state = PageViewState.Error(
                PageErrorState(PageErrorType.General, "disk not ready"),
            ),
            onRetry = {},
        ) {
            PageStatePreviewItems()
        }
    }
}

@Preview(name = "Empty", showBackground = true)
@Composable
private fun PageStateEmptyPreview() {
    PageStatePreviewScaffold {
        PageStateLayout(state = PageViewState.Empty()) {
            PageStatePreviewItems()
        }
    }
}

@Preview(name = "Empty custom message", showBackground = true)
@Composable
private fun PageStateEmptyCustomPreview() {
    PageStatePreviewScaffold {
        PageStateLayout(
            state = PageViewState.Empty(AppStrings.ui_no_sync_tasks_yet),
            onRetry = {},
        ) {
            PageStatePreviewItems()
        }
    }
}

@Preview(name = "Content", showBackground = true)
@Composable
private fun PageStateContentPreview() {
    PageStatePreviewScaffold {
        PageStateLayout(state = PageViewState.Content) {
            PageStatePreviewItems()
        }
    }
}

@Preview(name = "Content refreshing", showBackground = true)
@Composable
private fun PageStateContentRefreshingPreview() {
    PageStatePreviewScaffold {
        PageStateLayout(
            state = PageViewState.Content,
            refreshState = PageRefreshState.Refreshing,
            onRefresh = {},
        ) {
            PageStatePreviewItems()
        }
    }
}

@Preview(name = "Content refresh error banner", showBackground = true)
@Composable
private fun PageStateContentRefreshErrorPreview() {
    PageStatePreviewScaffold {
        PageStateLayout(
            state = PageViewState.Content,
            refreshState = PageRefreshState.Error(AppStrings.ui_loading_failed),
            onRefresh = {},
            onRetryRefresh = {},
        ) {
            PageStatePreviewItems()
        }
    }
}

@Preview(name = "Refresh error banner", showBackground = true)
@Composable
private fun PageRefreshErrorBannerPreview() {
    MaterialTheme {
        PageRefreshErrorBanner(
            message = AppStrings.ui_loading_failed,
            onRetry = {},
        )
    }
}

@Preview(name = "Append loading", showBackground = true)
@Composable
private fun PageAppendLoadingPreview() {
    MaterialTheme {
        PageAppendFooter(state = PageAppendState.Loading)
    }
}

@Preview(name = "Append error", showBackground = true)
@Composable
private fun PageAppendErrorPreview() {
    MaterialTheme {
        PageAppendFooter(
            state = PageAppendState.Error(AppStrings.ui_failed_load_more),
            onRetry = {},
        )
    }
}

@Preview(name = "Append end", showBackground = true)
@Composable
private fun PageAppendEndPreview() {
    MaterialTheme {
        PageAppendFooter(state = PageAppendState.End)
    }
}

@Preview(name = "GridList loading", showBackground = true)
@Composable
private fun GridListLoadingPreview() {
    PageStatePreviewScaffold {
        GridList(isLoading = true) {
            pageStatePreviewGridItems()
        }
    }
}

@Preview(name = "GridList empty", showBackground = true)
@Composable
private fun GridListEmptyPreview() {
    PageStatePreviewScaffold {
        GridList(isEmpty = true, emptyMessage = AppStrings.ui_data_not_found) {
            pageStatePreviewGridItems()
        }
    }
}

@Preview(name = "GridList error", showBackground = true)
@Composable
private fun GridListErrorPreview() {
    PageStatePreviewScaffold {
        GridList(
            errorState = PageErrorState(PageErrorType.Connection),
            onRetry = {},
        ) {
            pageStatePreviewGridItems()
        }
    }
}

@Preview(name = "GridList content refreshing", showBackground = true)
@Composable
private fun GridListRefreshingPreview() {
    PageStatePreviewScaffold {
        GridList(
            refreshState = PageRefreshState.Refreshing,
            onRefresh = {},
        ) {
            pageStatePreviewGridItems()
        }
    }
}

@Preview(name = "GridList content + append loading", showBackground = true)
@Composable
private fun GridListAppendLoadingPreview() {
    PageStatePreviewScaffold {
        GridList(
            appendState = PageAppendState.Loading,
            onLoadMore = {},
        ) {
            pageStatePreviewGridItems()
        }
    }
}

@Preview(name = "GridList content + append error", showBackground = true)
@Composable
private fun GridListAppendErrorPreview() {
    PageStatePreviewScaffold {
        GridList(
            appendState = PageAppendState.Error(),
            onLoadMore = {},
            onRetryAppend = {},
        ) {
            pageStatePreviewGridItems()
        }
    }
}

@Preview(name = "GridList content + append end", showBackground = true)
@Composable
private fun GridListAppendEndPreview() {
    PageStatePreviewScaffold {
        GridList(appendState = PageAppendState.End) {
            pageStatePreviewGridItems()
        }
    }
}

@Preview(name = "GridList refresh error banner", showBackground = true)
@Composable
private fun GridListRefreshErrorPreview() {
    PageStatePreviewScaffold {
        GridList(
            refreshState = PageRefreshState.Error(AppStrings.ui_loading_failed),
            onRefresh = {},
            onRetryRefresh = {},
        ) {
            pageStatePreviewGridItems()
        }
    }
}

@Composable
private fun PageStatePreviewScaffold(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            content()
        }
    }
}

@Composable
private fun PageStatePreviewItems() {
    LazyColumn(Modifier.fillMaxSize()) {
        items(6) { index ->
            ListItem(
                headlineContent = { Text("Item ${index + 1}") },
                supportingContent = { Text("Preview row") },
            )
        }
    }
}

private fun LazyGridScope.pageStatePreviewGridItems() {
    items(6) { index ->
        ListItem(
            headlineContent = { Text("Item ${index + 1}") },
            supportingContent = { Text("Preview cell") },
        )
    }
}

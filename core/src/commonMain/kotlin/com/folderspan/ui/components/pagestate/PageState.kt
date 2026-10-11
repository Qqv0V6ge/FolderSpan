package com.folderspan.ui.components.pagestate

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp
import com.folderspan.exception.AuthorityException
import com.folderspan.exception.EmptyDataException
import com.folderspan.extensions.isConnectionException
import kotlinx.coroutines.CancellationException

enum class PageErrorType {
    Authority,
    Cancellation,
    Connection,
    General,
}

@Immutable
data class PageErrorState(
    val type: PageErrorType,
    val message: String? = null,
)

@Immutable
sealed interface PageViewState {
    @Immutable
    data object Loading : PageViewState

    @Immutable
    data class Error(val error: PageErrorState) : PageViewState

    @Immutable
    data class Empty(val message: String? = null) : PageViewState

    @Immutable
    data object Content : PageViewState
}

@Immutable
sealed interface PageRefreshState {
    @Immutable
    data object Idle : PageRefreshState

    @Immutable
    data object Refreshing : PageRefreshState

    @Immutable
    data class Error(val message: String? = null) : PageRefreshState
}

@Immutable
sealed interface PageAppendState {
    @Immutable
    data object Idle : PageAppendState

    @Immutable
    data object Loading : PageAppendState

    @Immutable
    data class Error(val message: String? = null) : PageAppendState

    @Immutable
    data object End : PageAppendState
}

object PageStateDefaults {
    const val AppendPrefetchDistance = 5
    val AppendFooterMinHeight = 48.dp
    val RefreshBannerHorizontalPadding = 12.dp
    val RefreshBannerVerticalPadding = 8.dp
}

object PageStateTestTags {
    const val Loading = "page-state-loading"
    const val Error = "page-state-error"
    const val Empty = "page-state-empty"
    const val Content = "page-state-content"
    const val RefreshError = "page-state-refresh-error"
    const val AppendFooter = "page-state-append-footer"
}

fun resolvePageViewState(
    isLoading: Boolean = false,
    errorState: PageErrorState? = null,
    isEmpty: Boolean = false,
    emptyMessage: String? = null,
): PageViewState = when {
    isLoading -> PageViewState.Loading
    errorState != null -> PageViewState.Error(errorState)
    isEmpty -> PageViewState.Empty(emptyMessage)
    else -> PageViewState.Content
}

fun Throwable.toPageErrorState(): PageErrorState? = when {
    this is EmptyDataException -> null
    this is AuthorityException -> PageErrorState(PageErrorType.Authority, message)
    this is CancellationException -> PageErrorState(PageErrorType.Cancellation, message)
    isConnectionException() -> PageErrorState(PageErrorType.Connection, message)
    else -> PageErrorState(PageErrorType.General, message)
}

fun Throwable.toPageEmptyMessage(): String? = (this as? EmptyDataException)?.message

fun shouldTriggerLoadMore(
    lastVisibleIndex: Int,
    totalItemsCount: Int,
    prefetchDistance: Int = PageStateDefaults.AppendPrefetchDistance,
): Boolean {
    return !(totalItemsCount <= 0 || lastVisibleIndex < 0) && lastVisibleIndex >= totalItemsCount - 1 - prefetchDistance.coerceAtLeast(0)
}

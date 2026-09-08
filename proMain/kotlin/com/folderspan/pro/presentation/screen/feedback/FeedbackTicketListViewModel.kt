package com.folderspan.pro.presentation.screen.feedback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.domain.model.FeedbackListQuery
import com.folderspan.pro.domain.usecase.FeedbackSessionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FeedbackTicketListViewModel(
    private val sessionService: FeedbackSessionService,
    private val onUnauthorized: () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(FeedbackTicketListUiState())
    val state: StateFlow<FeedbackTicketListUiState> = _state
    private var session: AuthSession? = null
    private var sessionIdentity: String? = null

    fun onSessionChanged(session: AuthSession?) {
        val nextIdentity = session?.userUuid ?: session?.accessToken
        if (sessionIdentity != nextIdentity) {
            sessionIdentity = nextIdentity
            _state.update {
                it.copy(
                    isSignedIn = session != null,
                    items = emptyList(),
                    total = 0,
                    page = 0,
                    hasMore = false,
                    errorMessage = null,
                    refreshMessage = null,
                )
            }
        } else {
            _state.update { it.copy(isSignedIn = session != null) }
        }
        this.session = session
    }

    fun updateFilters(
        type: String? = _state.value.typeFilter,
        platform: String? = _state.value.platformFilter,
        startTimeEpochSeconds: Long? = _state.value.startTimeEpochSeconds,
        endTimeEpochSeconds: Long? = _state.value.endTimeEpochSeconds,
    ) {
        _state.update {
            it.copy(
                typeFilter = type?.takeIf(String::isNotBlank),
                platformFilter = platform?.takeIf(String::isNotBlank),
                startTimeEpochSeconds = startTimeEpochSeconds,
                endTimeEpochSeconds = endTimeEpochSeconds,
                page = 0,
                hasMore = false,
            )
        }
        load(refresh = true)
    }

    fun load(refresh: Boolean = false) {
        val snapshot = _state.value
        if (!snapshot.isSignedIn) return
        if (snapshot.isLoading || snapshot.isLoadingMore) return
        if (!refresh && snapshot.page > 0 && !snapshot.hasMore) return
        val nextPage = if (refresh) 1 else snapshot.page + 1

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = refresh || it.items.isEmpty(),
                    isLoadingMore = !refresh && it.items.isNotEmpty(),
                    errorMessage = null,
                    refreshMessage = null,
                )
            }
            val current = _state.value
            val query = FeedbackListQuery(
                type = current.typeFilter,
                platform = current.platformFilter,
                startTimeEpochSeconds = current.startTimeEpochSeconds,
                endTimeEpochSeconds = current.endTimeEpochSeconds,
                page = nextPage,
                pageSize = FeedbackTicketPageSize,
            )
            when (val result = sessionService.list(query, session, onUnauthorized)) {
                is ApiResult.Success -> _state.update { state ->
                    val merged = if (refresh) result.data.items else state.items + result.data.items
                    val unique = merged.distinctBy { it.uuid }
                    state.copy(
                        items = unique,
                        total = result.data.total,
                        page = nextPage,
                        hasMore = unique.size < result.data.total && result.data.items.isNotEmpty(),
                        errorMessage = null,
                        refreshMessage = null,
                    )
                }
                is ApiResult.Failure -> _state.update { state ->
                    when {
                        state.items.isEmpty() -> state.copy(errorMessage = result.message)
                        refresh -> state.copy(refreshMessage = result.message)
                        else -> state.copy(errorMessage = result.message)
                    }
                }
            }
            _state.update { it.copy(isLoading = false, isLoadingMore = false) }
        }
    }
}

internal const val FeedbackTicketPageSize = 20

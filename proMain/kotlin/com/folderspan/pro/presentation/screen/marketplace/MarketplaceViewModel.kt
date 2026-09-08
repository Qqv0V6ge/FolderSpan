package com.folderspan.pro.presentation.screen.marketplace

import com.folderspan.pro.data.remote.PluginPagingSource
import com.folderspan.pro.domain.repository.PluginRepository
import com.folderspan.pro.domain.model.MarketplaceQuery
import com.folderspan.pro.domain.model.PluginCardUi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.milliseconds

class MarketplaceViewModel(
    private val repository: PluginRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MarketplaceUiState())
    val state: StateFlow<MarketplaceUiState> = _state.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val plugins: Flow<PagingData<PluginCardUi>> = state
        .debounce(250.milliseconds)
        .distinctUntilChanged()
        .flatMapLatest { state ->
            Pager(
                config = PagingConfig(
                    pageSize = 20,
                    initialLoadSize = 20,
                    prefetchDistance = 5,
                    enablePlaceholders = false,
                ),
                pagingSourceFactory = {
                    PluginPagingSource(
                        repository = repository,
                        query = MarketplaceQuery(
                            pageSize = 20,
                            keyword = state.query.takeIf { it.isNotBlank() },
                        ),
                    )
                },
            ).flow
        }
        .cachedIn(viewModelScope)

    fun onEvent(event: MarketplaceEvent) {
        when (event) {
            is MarketplaceEvent.QueryChanged -> {
                _state.update { it.copy(query = event.value) }
            }
        }
    }
}

package com.folderspan.pro.presentation.screen.marketplace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import com.folderspan.pro.domain.repository.PluginRepository

@Composable
fun MarketplaceRoute(
    viewModelKey: String? = null,
    repository: PluginRepository,
    onNavigateToUserProfile: () -> Unit,
) {
    val viewModel = viewModel(key = viewModelKey) { MarketplaceViewModel(repository) }
    val state by viewModel.state.collectAsState()
    val pagingItems = viewModel.plugins.collectAsLazyPagingItems()

    MarketplacePage(
        state = state,
        pagingItems = pagingItems,
        onEvent = viewModel::onEvent,
        onNavigateToUserProfile = onNavigateToUserProfile,
    )
}

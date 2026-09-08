package com.folderspan.pro.presentation.screen.marketplace

data class MarketplaceUiState(
    val query: String = "",
)

sealed interface MarketplaceEvent {
    data class QueryChanged(val value: String) : MarketplaceEvent
}

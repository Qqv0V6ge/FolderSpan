package com.folderspan.pro.domain.model

data class MarketplaceQuery(
    val page: Int = 1,
    val pageSize: Int = 20,
    val keyword: String? = null,
    val isFree: Boolean? = null,
    val securityChecked: Boolean? = null,
    val authorId: String? = null,
    val category: List<String>? = null,
    val tag: List<String>? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val status: String? = null,
    val license: String? = null,
    val orderBy: String? = null,
    val sort: String? = null,
) {
    fun forPage(page: Int, loadSize: Int): MarketplaceQuery =
        copy(page = page, pageSize = loadSize)
}

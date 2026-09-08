package com.folderspan.pro.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class PluginListQuery(
    val page: Int? = 1,
    val pageSize: Int? = 10,
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
)

@Serializable
data class PluginVersionQuery(
    val page: Int? = 1,
    val pageSize: Int? = 10,
    val status: String? = null,
    val platform: String? = null,
    val keyword: String? = null,
)

@Serializable
data class PluginVersionRatingQuery(
    val page: Int? = 1,
    val pageSize: Int? = 10,
    val minScore: Int? = null,
    val maxScore: Int? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val orderBy: String? = null,
    val sort: String? = null,
)

@Serializable
data class CreateRatingRequest(
    val score: Int,
    val comment: String? = null,
)

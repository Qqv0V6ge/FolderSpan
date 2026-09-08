package com.folderspan.pro.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SettingEntryKey(
    val type: String,
    val targetId: String,
    val key: String,
)

@Serializable
data class SettingSaveRequest(
    val type: String,
    val targetId: String,
    val key: String,
    val value: JsonElement,
)

@Serializable
data class SettingBatchSaveRequest(
    val items: List<SettingSaveRequest>,
)

@Serializable
data class SettingBatchDeleteRequest(
    val items: List<SettingEntryKey>,
)

@Serializable
data class SettingDeleteTargetRequest(
    val type: String,
    val targetId: String,
)

@Serializable
data class SettingCloneTargetRequest(
    val type: String,
    val sourceTargetId: String,
    val targetId: String,
    val name: String,
    val description: String? = null,
    val subType: String? = null,
)

data class SettingListQuery(
    val type: String,
    val targetId: String,
    val timestamp: Long,
)

data class SettingTargetQuery(
    val type: String,
    val keyword: String? = null,
    val page: Int? = null,
    val pageSize: Int? = null,
)

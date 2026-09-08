package com.folderspan.pro.data.repository

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.data.remote.api.SettingApiService
import com.folderspan.pro.data.remote.dto.SettingCloneTargetRequest
import com.folderspan.pro.data.remote.dto.SettingBatchDeleteRequest
import com.folderspan.pro.data.remote.dto.SettingBatchSaveRequest
import com.folderspan.pro.data.remote.dto.SettingDeleteTargetRequest
import com.folderspan.pro.data.remote.dto.SettingEntryKey
import com.folderspan.pro.data.remote.dto.SettingListQuery
import com.folderspan.pro.data.remote.dto.SettingSaveRequest
import com.folderspan.pro.data.remote.dto.SettingTargetQuery
import com.folderspan.pro.domain.model.DeviceSettingEntry
import com.folderspan.pro.domain.model.CloneSettingTargetCommand
import com.folderspan.pro.domain.model.RemoteSettingEntryKey
import com.folderspan.pro.domain.model.RemoteSettingSave
import com.folderspan.pro.domain.repository.SettingRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class DefaultSettingRepository(
    private val settingApiService: SettingApiService,
) : SettingRepository {
    override suspend fun listDeviceTargets(token: String): JsonResult =
        settingApiService.listTargets(
            query = SettingTargetQuery(type = "device"),
            token = token,
        )

    override suspend fun listSettings(
        type: String,
        targetId: String,
        timestamp: Long,
        token: String,
    ): ApiResult<List<DeviceSettingEntry>> =
        when (
            val result = settingApiService.listSettings(
                query = SettingListQuery(
                    type = type,
                    targetId = targetId,
                    timestamp = timestamp,
                ),
                token = token,
            )
        ) {
            is ApiResult.Success -> ApiResult.Success(result.data.toDeviceSettingEntries())
            is ApiResult.Failure -> result
        }

    override suspend fun saveSetting(type: String, targetId: String, entry: DeviceSettingEntry, token: String): JsonResult =
        settingApiService.saveSetting(
            request = SettingSaveRequest(
                type = type,
                targetId = targetId,
                key = entry.key,
                value = entry.value,
            ),
            token = token,
        )

    override suspend fun batchSaveSettings(items: List<RemoteSettingSave>, token: String): JsonResult =
        settingApiService.batchSaveSettings(
            request = SettingBatchSaveRequest(
                items = items.map { item ->
                    SettingSaveRequest(
                        type = item.type,
                        targetId = item.targetId,
                        key = item.key,
                        value = item.value,
                    )
                },
            ),
            token = token,
        )

    override suspend fun batchDeleteSettings(items: List<RemoteSettingEntryKey>, token: String): JsonResult =
        settingApiService.batchDeleteSettings(
            request = SettingBatchDeleteRequest(
                items = items.map { item ->
                    SettingEntryKey(
                        type = item.type,
                        targetId = item.targetId,
                        key = item.key,
                    )
                },
            ),
            token = token,
        )

    override suspend fun cloneTargetSettings(command: CloneSettingTargetCommand, token: String): JsonResult =
        settingApiService.cloneTargetSettings(
            request = SettingCloneTargetRequest(
                type = command.type,
                sourceTargetId = command.sourceTargetId,
                targetId = command.targetId,
                name = command.name,
                description = command.description,
                subType = command.subType,
            ),
            token = token,
        )

    override suspend fun deleteTargetSettings(type: String, targetId: String, token: String): JsonResult =
        settingApiService.deleteTargetSettings(
            request = SettingDeleteTargetRequest(
                type = type,
                targetId = targetId,
            ),
            token = token,
        )
}

private fun JsonElement.toDeviceSettingEntries(): List<DeviceSettingEntry> {
    val data = (this as? JsonObject)?.get("data") ?: this
    val records = when (data) {
        is JsonArray -> data
        is JsonObject -> data.arrayValue("records")
            ?: data.arrayValue("items")
            ?: data.arrayValue("list")
            ?: JsonArray(emptyList())

        else -> JsonArray(emptyList())
    }
    return records.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val key = obj.stringValue("key") ?: return@mapNotNull null
        val value = obj["value"] ?: return@mapNotNull null
        DeviceSettingEntry(
            key = key,
            value = value,
            updatedAt = obj.longValue("updatedAt")
                ?: obj.longValue("updated_at")
                ?: obj.longValue("timestamp"),
        )
    }
}

private fun JsonObject.arrayValue(name: String): JsonArray? = get(name) as? JsonArray

private fun JsonObject.stringValue(name: String): String? =
    (get(name) as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun JsonObject.longValue(name: String): Long? =
    (get(name) as? JsonPrimitive)
        ?.contentOrNull
        ?.toLongOrNull()
        ?.takeIf { it > 0L }

package com.folderspan.pro.data.mapper

import strings.AppStrings

import com.folderspan.pro.core.common.apiDataOrSelf
import com.folderspan.pro.presentation.screen.profile.SettingTargetViewData
import com.folderspan.pro.presentation.screen.profile.SettingTargetsPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

fun JsonElement.toSettingTargetsPayload(): SettingTargetsPayload? {
    val data = apiDataOrSelf()
    val records = when (data) {
        is JsonArray -> data
        is JsonObject -> data.arrayValue("records")
            ?: data.arrayValue("items")
            ?: data.arrayValue("list")
            ?: JsonArray(emptyList())

        else -> return null
    }
    val targets = records.mapNotNull { it.toSettingTargetViewData() }
    return SettingTargetsPayload(
        targets = targets,
        total = (data as? JsonObject)?.intValue("total") ?: targets.size,
    )
}

private fun JsonElement.toSettingTargetViewData(): SettingTargetViewData? {
    val obj = this as? JsonObject ?: return null
    val id = obj.stringValue("targetId")
        ?: obj.stringValue("id")
        ?: obj.stringValue("uuid")
        ?: obj.stringValue("deviceKey")
        ?: obj.stringValue("device_key")
        ?: return null
    return SettingTargetViewData(
        id = id,
        name = obj.stringValue("name")
            ?: obj.stringValue("deviceName")
            ?: obj.stringValue("device_name")
            ?: obj.stringValue("title")
            ?: obj.stringValue("label")
            ?: AppStrings.ui_unnamed_setting,
        deviceKey = obj.stringValue("deviceKey") ?: obj.stringValue("device_key"),
        type = obj.stringValue("type")
            ?: obj.stringValue("deviceType")
            ?: obj.stringValue("device_type"),
        subType = obj.stringValue("subType")
            ?: obj.stringValue("sub_type"),
        description = obj.stringValue("description") ?: obj.stringValue("summary"),
    )
}

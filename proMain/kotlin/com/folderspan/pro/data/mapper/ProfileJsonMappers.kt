package com.folderspan.pro.data.mapper

import strings.AppStrings

import com.folderspan.pro.core.common.apiDataOrSelf
import com.folderspan.pro.presentation.screen.profile.UserDeviceViewData
import com.folderspan.pro.presentation.screen.profile.UserDevicesPayload
import com.folderspan.pro.presentation.screen.profile.UserProfileViewData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

fun JsonElement.toUserProfileViewData(): UserProfileViewData? {
    val data = apiDataOrSelf() as? JsonObject ?: return null
    val name = data.stringValue("name").orEmpty().ifBlank { AppStrings.ui_unnamed_user }
    val email = data.stringValue("email").orEmpty().ifBlank { AppStrings.ui_no_email_provided }
    val uuid = data.stringValue("uuid").orEmpty().ifBlank { AppStrings.ui_no_account_number_provided }

    return UserProfileViewData(
        uuid = uuid,
        name = name,
        email = email,
        avatar = data.stringValue("avatar"),
        signature = data.stringValue("signature"),
        status = data.intValue("status"),
        profileEditable = (data["profileEditable"] as? JsonPrimitive)?.booleanOrNull ?: true,
    )
}

fun JsonElement.toProfileUpdateUserViewData(): UserProfileViewData? {
    val data = apiDataOrSelf() as? JsonObject ?: return null
    return data["user"]?.toUserProfileViewData()
}

fun JsonElement.toUserDevicesPayload(): UserDevicesPayload? {
    val data = apiDataOrSelf() as? JsonObject ?: return null
    val items = (data["devices"] as? JsonArray)
        ?.mapNotNull { element -> element.toUserDeviceViewData() }
        .orEmpty()

    return UserDevicesPayload(
        devices = items,
        total = data.intValue("total") ?: items.size,
    )
}

private fun JsonElement.toUserDeviceViewData(): UserDeviceViewData? {
    val obj = this as? JsonObject ?: return null
    val id = obj.longValue("id") ?: return null
    val deviceType = obj.stringValue("device_type") ?: return null
    val deviceName = obj.stringValue("device_name") ?: return null
    val deviceKey = obj.stringValue("device_key") ?: return null
    val createdAt = obj.longValue("created_at") ?: 0L

    return UserDeviceViewData(
        id = id,
        deviceType = deviceType,
        deviceName = deviceName,
        deviceKey = deviceKey,
        createdAt = createdAt,
    )
}

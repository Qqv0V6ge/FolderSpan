package com.folderspan.data.device

import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType

data class DeviceJoinDeviceRole(
    val id: String,
    val name: String,
    val type: DeviceType,
    val connectionType: DeviceConnectType,
    val firstConnection: Long,
    val lastConnection: Long,
    val category: DeviceCategory,
    val roleId: Long,
    val roleName: String,
)
package com.folderspan.ui.state.main

import com.folderspan.PlatformType
import com.folderspan.createSettings
import com.folderspan.currentDeviceName
import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.Settings

data class SyncDeviceIdentity(val id: String, val name: String) {
    companion object {
        fun current(): SyncDeviceIdentity = if (PlatformType == DeviceType.JS) {
            SyncDeviceIdentity(WebUiAttachment.hostInfo?.deviceId.orEmpty(), WebUiAttachment.hostInfo?.deviceName.orEmpty())
        } else {
            fromSettings(createSettings(), currentDeviceName())
        }

        fun fromSettings(settings: Settings, name: String): SyncDeviceIdentity =
            SyncDeviceIdentity(settings.getString(SettingsUtils.KEY_DEVICE_ID, ""), name)
    }
}

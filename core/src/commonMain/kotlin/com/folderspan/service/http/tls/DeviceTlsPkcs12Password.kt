package com.folderspan.service.http.tls

import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.Settings

internal object DeviceTlsPkcs12Password {
    fun read(settings: Settings): String? =
        settings.getStringOrNull(SettingsUtils.KEY_DEVICE_TLS_PKCS12_PASSWORD)?.takeIf { it.isNotEmpty() }

    fun write(settings: Settings, password: String) {
        settings.putString(SettingsUtils.KEY_DEVICE_TLS_PKCS12_PASSWORD, password)
    }
}

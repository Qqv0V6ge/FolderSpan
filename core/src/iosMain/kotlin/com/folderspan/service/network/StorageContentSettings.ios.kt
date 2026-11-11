package com.folderspan.service.network

import com.folderspan.settings.IosStrictKeychainStore
import com.folderspan.settings.SensitiveSettings
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings

internal actual fun createStorageContentSettings(): Settings = SensitiveSettings(
    plaintext = NSUserDefaultsSettings.Factory().create("FolderSpan"),
    vault = IosStrictKeychainStore,
)

package com.folderspan.utils

import com.folderspan.androidContext

internal actual fun platformSensitivePathRules(): List<SensitivePathRule> {
    val context = androidContext()
    return listOf(
        SensitivePathRule(
            path = context.dataDir.absolutePath,
            sensitivity = FileSensitivity.Critical,
            category = "application_private_data",
        ),
        SensitivePathRule(
            path = context.cacheDir.absolutePath,
            sensitivity = FileSensitivity.Sensitive,
            category = "application_cache",
        ),
    )
}

// Android 数据分区（ext4/f2fs）大小写敏感。
internal actual val securityBoundaryCaseInsensitive: Boolean = false

package com.folderspan.utils

import com.folderspan.cleanup.resolveDesktopApplicationDataDirectory

internal actual fun platformSensitivePathRules(): List<SensitivePathRule> {
    val applicationDataDirectory = resolveDesktopApplicationDataDirectory()
    val cacheDirectory = applicationDataDirectory.resolve("cache")
    return listOf(
        SensitivePathRule(
            path = applicationDataDirectory.toString(),
            sensitivity = FileSensitivity.Critical,
            category = "application_private_data",
        ),
        SensitivePathRule(
            path = cacheDirectory.toString(),
            sensitivity = FileSensitivity.Sensitive,
            category = "application_cache",
        ),
        SensitivePathRule(
            path = applicationDataDirectory.resolve("pro_http_cache").toString(),
            sensitivity = FileSensitivity.Sensitive,
            category = "pro_http_cache",
        ),
    )
}

internal actual val securityBoundaryCaseInsensitive: Boolean by lazy {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    // Windows 与默认 APFS 的 macOS 系统卷大小写不敏感，Linux 保持大小写敏感。
    osName.contains("win") || osName.contains("mac")
}

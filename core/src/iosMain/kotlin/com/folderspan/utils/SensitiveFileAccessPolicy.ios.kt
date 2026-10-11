package com.folderspan.utils

import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSTemporaryDirectory

internal actual fun platformSensitivePathRules(): List<SensitivePathRule> = listOf(
    SensitivePathRule(
        path = NSHomeDirectory().trimEnd('/') + "/Library",
        sensitivity = FileSensitivity.Critical,
        category = "application_private_data",
    ),
    SensitivePathRule(
        path = NSTemporaryDirectory(),
        sensitivity = FileSensitivity.Sensitive,
        category = "application_cache",
    ),
)

// 默认 APFS 数据卷大小写不敏感。
internal actual val securityBoundaryCaseInsensitive: Boolean = true

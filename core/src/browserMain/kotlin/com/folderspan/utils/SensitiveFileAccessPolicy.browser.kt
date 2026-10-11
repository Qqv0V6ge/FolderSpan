package com.folderspan.utils

import com.folderspan.service.webui.WebUiAttachment

internal actual fun platformSensitivePathRules(): List<SensitivePathRule> =
    checkNotNull(WebUiAttachment.hostInfo) { "Runtime login required" }.sensitivePaths.map {
        SensitivePathRule(it.path, it.sensitivity, it.category, it.descendants)
    } + SensitivePathRule(PrivateFileStorage.getAppPath(), FileSensitivity.Critical, "browser_private_data")
internal actual val securityBoundaryCaseInsensitive: Boolean get() =
    checkNotNull(WebUiAttachment.hostInfo) { "Runtime login required" }.caseInsensitive

package com.folderspan.utils

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID

fun requiresFileSoftwareConsent(protocol: FileProtocol, protocolId: String): Boolean =
    protocol != FileProtocol.Local && !(protocol == FileProtocol.Share && protocolId == SYSTEM_SHARE_DESK_ID)

fun ensureFileSoftwareConsent(protocol: FileProtocol, protocolId: String): Boolean =
    !requiresFileSoftwareConsent(protocol, protocolId) || SettingsUtils.ensureSoftwareConsent()

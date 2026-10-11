package com.folderspan.service.http.server.linkshare

import korlibs.crypto.sha256

internal const val LINK_SHARE_DERIVED_CLIENT_ID_LENGTH = 32

internal fun deriveStableLinkShareClientId(remoteHost: String?, userAgent: String?): String {
    val material = "${remoteHost.orEmpty().trim()}\n${userAgent.orEmpty().trim()}"
    return material.encodeToByteArray().sha256().hexLower.take(LINK_SHARE_DERIVED_CLIENT_ID_LENGTH)
}

internal fun String.isSafeLinkShareToken(): Boolean {
    return length in 16..128 && all { item ->
        item in 'a'..'z' || item in 'A'..'Z' || item in '0'..'9' || item == '-' || item == '_'
    }
}

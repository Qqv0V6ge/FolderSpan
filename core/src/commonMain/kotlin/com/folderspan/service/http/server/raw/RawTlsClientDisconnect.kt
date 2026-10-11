package com.folderspan.service.http.server.raw

import strings.AppStrings

internal fun isExpectedRawTlsClientDisconnectMessage(message: String): Boolean {
    val normalized = message.lowercase()
    val isAndroidSslProtocolReadFailure = normalized.contains("read error:") &&
        normalized.contains("failure in ssl library") &&
        normalized.contains("protocol error")

    return normalized.contains("connection reset") ||
        normalized.contains("broken pipe") ||
        normalized.contains("socket closed") ||
        normalized.contains("closed inbound") ||
        normalized.contains("close_notify") ||
        normalized.contains("unsupported or unrecognized ssl message") ||
        normalized.contains("unrecognized ssl message") ||
        normalized.contains("http request") ||
        normalized.contains("wrong_version_number") ||
        normalized.contains("plain http request") ||
        normalized.contains("plaintext connection") ||
        isAndroidSslProtocolReadFailure ||
        normalized.contains(AppStrings.ui_connection_closed.lowercase()) ||
        normalized.contains(AppStrings.ui_connection_closed)
}

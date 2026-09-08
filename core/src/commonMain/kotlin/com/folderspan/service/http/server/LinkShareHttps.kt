package com.folderspan.service.http.server

import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import com.folderspan.utils.NetworkHostUtils

const val LINK_SHARE_HTTPS_CONSENT_COOKIE = "FolderSpanLinkShareHttpsConsent"

data class LinkSharePageConfig(
    val httpBaseUrl: String,
    val httpsBaseUrl: String?,
    val tlsFingerprintSha256: String,
    val httpsConsentKey: String,
    val httpsConsentCookie: String = LINK_SHARE_HTTPS_CONSENT_COOKIE,
) {
    val httpsAvailable: Boolean
        get() = !httpsBaseUrl.isNullOrBlank() && httpsConsentKey.isNotBlank()
}

fun defaultLinkShareHttpsPort(httpPort: Int): Int? {
    return httpPort.takeIf { item -> item in 1..65535 }
}

fun buildLinkShareBaseUrl(scheme: String, host: String, port: Int): String {
    return "$scheme://${NetworkHostUtils.combineHostPort(host, port)}"
}

fun buildLinkShareHttpsConsentKey(
    host: String,
    port: Int,
    tlsFingerprintSha256: String,
): String {
    val fingerprint = normalizeTlsFingerprintSha256(tlsFingerprintSha256)
    if (host.isBlank() || port !in 1..65535 || fingerprint.isBlank()) {
        return ""
    }
    return listOf(
        "v1",
        host.trim().lowercase(),
        port.toString(),
        fingerprint
    ).joinToString(":")
}

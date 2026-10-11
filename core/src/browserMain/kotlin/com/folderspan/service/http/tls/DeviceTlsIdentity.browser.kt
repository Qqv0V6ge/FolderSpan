package com.folderspan.service.http.tls

import com.folderspan.service.webui.*

actual object DeviceTlsIdentity {
    actual fun loadOrCreate() = DeviceTlsIdentityInfo(checkNotNull(WebUiAttachment.hostInfo) { "Runtime login required" }.fingerprint)
    actual fun publicKeyPem(): String? = checkNotNull(WebUiAttachment.hostInfo) { "Runtime login required" }.publicKey
    actual suspend fun signSha256WithRsa(payload: ByteArray): String? = WebUiPlatformClient.json.decodeFromString(
        WebUiPlatformClient.exchange("POST", "$WEB_UI_PLATFORM_PATH/devices/sign", payload, "application/octet-stream").body.decodeToString())
    actual suspend fun verifySha256WithRsa(publicKeyPem: String, payload: ByteArray, signature: String): Boolean =
        WebUiPlatformClient.post<WebUiVerifySignature, Boolean>("/devices/verify", WebUiVerifySignature(publicKeyPem, payload, signature))
}

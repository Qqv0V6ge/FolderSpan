package com.folderspan.service.http.tls

import com.folderspan.service.webui.*

actual object DeviceTlsIdentity {
    actual fun loadOrCreate() = DeviceTlsIdentityInfo(checkNotNull(WebUiAttachment.hostInfo) { "Runtime login required" }.fingerprint)
    actual fun publicKeyPem(): String? = checkNotNull(WebUiAttachment.hostInfo) { "Runtime login required" }.publicKey

    internal actual fun certificatePem(): String? = WebUiAttachment.hostInfo?.deviceCertificatePem?.takeIf(String::isNotEmpty)

    internal actual suspend fun publicKeyFromCertificate(certificatePem: String): String? = WebUiPlatformClient.json.decodeFromString(
        WebUiPlatformClient.exchange(
            "POST", "$WEB_UI_PLATFORM_PATH/devices/certificate-key", certificatePem.encodeToByteArray(), "application/octet-stream",
        ).body.decodeToString()
    )

    internal actual suspend fun signDirectInvite(payload: ByteArray): String? = signSha256WithRsa(payload)

    actual suspend fun signSha256WithRsa(payload: ByteArray): String? = WebUiPlatformClient.json.decodeFromString(
        WebUiPlatformClient.exchange("POST", "$WEB_UI_PLATFORM_PATH/devices/sign", payload, "application/octet-stream").body.decodeToString())
    actual suspend fun verifySha256WithRsa(publicKeyPem: String, payload: ByteArray, signature: String): Boolean =
        WebUiPlatformClient.post<WebUiVerifySignature, Boolean>("/devices/verify", WebUiVerifySignature(publicKeyPem, payload, signature))
}

package com.folderspan.service.http.tls

import strings.AppStrings

data class DeviceTlsIdentityInfo(
    val fingerprintSha256: String
)

expect object DeviceTlsIdentity {
    fun loadOrCreate(): DeviceTlsIdentityInfo

    /** 返回可公开登记的 PEM 公钥；平台不支持时返回 null。 */
    fun publicKeyPem(): String?

    internal fun certificatePem(): String?

    internal suspend fun publicKeyFromCertificate(certificatePem: String): String?

    internal suspend fun signDirectInvite(payload: ByteArray): String?

    /** 使用本机 TLS 身份私钥签名，私钥不会离开平台身份存储。 */
    suspend fun signSha256WithRsa(payload: ByteArray): String?

    suspend fun verifySha256WithRsa(publicKeyPem: String, payload: ByteArray, signature: String): Boolean
}

fun currentDeviceTlsFingerprint(): String {
    return runCatching { DeviceTlsIdentity.loadOrCreate().fingerprintSha256 }
        .getOrDefault("")
}

fun normalizeTlsFingerprintSha256(value: String): String {
    return value
        .trim()
        .removePrefix("sha256/")
        .replace(":", "")
        .replace(" ", "")
        .uppercase()
}

// 错误文案随当前语言取值，避免 TLS 工具提前加载时固定语言，导致后续错误识别失败。
val SERVER_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE: String
    get() = AppStrings.ui_service_certificate_fingerprint_does_not_match
val DEVICE_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE: String
    get() = AppStrings.ui_device_tls_certificate_fingerprint_does_not_match
val SHARE_DEVICE_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE: String
    get() = AppStrings.ui_shared_device_tls_certificate_fingerprint_mismatch

fun Throwable.containsMessage(expected: String): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current.message?.contains(expected) == true) return true
        current = current.cause
    }
    return false
}

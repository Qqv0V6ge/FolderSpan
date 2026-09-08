package com.folderspan.service.http.tls

actual object DeviceTlsIdentity {
    actual fun loadOrCreate(): DeviceTlsIdentityInfo = DeviceTlsIdentityInfo("")
    actual fun publicKeyPem(): String? = null
    actual fun signSha256WithRsa(payload: ByteArray): String? = null
    actual fun verifySha256WithRsa(publicKeyPem: String, payload: ByteArray, signature: String): Boolean = false
}

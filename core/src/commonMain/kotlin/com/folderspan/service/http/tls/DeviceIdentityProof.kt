package com.folderspan.service.http.tls

import com.folderspan.service.account.AccountDeviceNonceReplayCache
import com.folderspan.service.account.newAccountDeviceNonce
import kotlin.math.abs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.time.Clock

const val DEVICE_IDENTITY_PROOF_VERSION = 1
const val DEVICE_CONNECT_IDENTITY_PURPOSE = "device-connect"
const val DEVICE_IDENTITY_PROOF_MAX_AGE_SECONDS = 60L

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeviceIdentityProof(
    @ProtoNumber(1) val version: Int,
    @ProtoNumber(2) val purpose: String,
    @ProtoNumber(3) val deviceId: String,
    @ProtoNumber(4) val fingerprintSha256: String,
    @ProtoNumber(5) val publicKeyPem: String,
    @ProtoNumber(6) val issuedAtEpochSeconds: Long,
    @ProtoNumber(7) val nonce: String,
    @ProtoNumber(8) val signature: String,
)

data class DeviceIdentityProofContext(
    val purpose: String,
    val deviceId: String,
    val fingerprintSha256: String,
    val targetDeviceId: String,
    val targetPath: String,
    val nonce: String,
    val issuedAtEpochSeconds: Long,
    val payloadSha256: String,
)

enum class DeviceIdentityProofVerification {
    Valid,
    Missing,
    NotTrusted,
    InvalidVersion,
    WrongPurpose,
    IdentityConflict,
    Expired,
    InvalidSignature,
    Replay,
}

fun createDeviceIdentityProof(
    targetDeviceId: String,
    targetPath: String,
    payloadSha256: String,
    nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    nonce: String = newAccountDeviceNonce(),
    sign: (ByteArray) -> String? = { payload -> DeviceTlsIdentity.signSha256WithRsa(payload) },
    publicKeyPem: String? = DeviceTlsIdentity.publicKeyPem(),
    fingerprintSha256: String = currentDeviceTlsFingerprint(),
    deviceId: String,
): DeviceIdentityProof? {
    if (targetDeviceId.isBlank() || deviceId.isBlank() || nonce.isBlank()) return null
    val normalizedFingerprint = normalizeTlsFingerprintSha256(fingerprintSha256)
    val pem = publicKeyPem?.trim().orEmpty()
    if (normalizedFingerprint.isBlank() || pem.isBlank()) return null
    val context = DeviceIdentityProofContext(
        purpose = DEVICE_CONNECT_IDENTITY_PURPOSE,
        deviceId = deviceId,
        fingerprintSha256 = normalizedFingerprint,
        targetDeviceId = targetDeviceId,
        targetPath = targetPath,
        nonce = nonce,
        issuedAtEpochSeconds = nowEpochSeconds,
        payloadSha256 = payloadSha256,
    )
    val signature = sign(context.canonicalPayload())?.takeIf(String::isNotBlank) ?: return null
    return DeviceIdentityProof(
        version = DEVICE_IDENTITY_PROOF_VERSION,
        purpose = DEVICE_CONNECT_IDENTITY_PURPOSE,
        deviceId = deviceId,
        fingerprintSha256 = normalizedFingerprint,
        publicKeyPem = pem,
        issuedAtEpochSeconds = nowEpochSeconds,
        nonce = nonce,
        signature = signature,
    )
}

fun DeviceIdentityProofContext.canonicalPayload(): ByteArray = buildString {
    append("folderspan-device-identity-proof-v1\n")
    append("purpose=").append(purpose).append('\n')
    append("device=").append(deviceId.trim()).append('\n')
    append("fingerprint=").append(normalizeTlsFingerprintSha256(fingerprintSha256)).append('\n')
    append("target=").append(targetDeviceId.trim()).append('\n')
    append("path=").append(targetPath).append('\n')
    append("issuedAt=").append(issuedAtEpochSeconds).append('\n')
    append("nonce=").append(nonce).append('\n')
    append("payloadSha256=").append(payloadSha256.lowercase()).append('\n')
}.encodeToByteArray()

suspend fun verifyDeviceIdentityProof(
    proof: DeviceIdentityProof?,
    expectedDeviceId: String,
    targetDeviceId: String,
    targetPath: String,
    payloadSha256: String,
    nowEpochMillis: Long,
    replayCache: AccountDeviceNonceReplayCache,
    trustedFingerprint: String,
    trustedPublicKeyPem: String,
    verifier: (publicKeyPem: String, payload: ByteArray, signature: String) -> Boolean =
        { publicKey, payload, signature -> DeviceTlsIdentity.verifySha256WithRsa(publicKey, payload, signature) },
): DeviceIdentityProofVerification {
    proof ?: return DeviceIdentityProofVerification.Missing
    if (proof.version != DEVICE_IDENTITY_PROOF_VERSION) return DeviceIdentityProofVerification.InvalidVersion
    if (proof.purpose != DEVICE_CONNECT_IDENTITY_PURPOSE) return DeviceIdentityProofVerification.WrongPurpose
    if (proof.deviceId.trim() != expectedDeviceId.trim()) return DeviceIdentityProofVerification.IdentityConflict
    val normalizedProofFingerprint = normalizeTlsFingerprintSha256(proof.fingerprintSha256)
    val normalizedTrustedFingerprint = normalizeTlsFingerprintSha256(trustedFingerprint)
    val normalizedTrustedPem = trustedPublicKeyPem.trim()
    if (normalizedTrustedFingerprint.isBlank() || normalizedTrustedPem.isBlank()) {
        return DeviceIdentityProofVerification.NotTrusted
    }
    if (normalizedProofFingerprint != normalizedTrustedFingerprint) {
        return DeviceIdentityProofVerification.IdentityConflict
    }
    if (proof.publicKeyPem.trim() != normalizedTrustedPem) {
        return DeviceIdentityProofVerification.IdentityConflict
    }
    val nowEpochSeconds = nowEpochMillis / 1_000L
    if (abs(nowEpochSeconds - proof.issuedAtEpochSeconds) > DEVICE_IDENTITY_PROOF_MAX_AGE_SECONDS) {
        return DeviceIdentityProofVerification.Expired
    }
    val context = DeviceIdentityProofContext(
        purpose = DEVICE_CONNECT_IDENTITY_PURPOSE,
        deviceId = proof.deviceId,
        fingerprintSha256 = normalizedProofFingerprint,
        targetDeviceId = targetDeviceId,
        targetPath = targetPath,
        nonce = proof.nonce,
        issuedAtEpochSeconds = proof.issuedAtEpochSeconds,
        payloadSha256 = payloadSha256,
    )
    if (!verifier(normalizedTrustedPem, context.canonicalPayload(), proof.signature)) {
        return DeviceIdentityProofVerification.InvalidSignature
    }
    if (!replayCache.consume(
            deviceKey = proof.deviceId,
            purpose = proof.purpose,
            nonce = proof.nonce,
            expiresAtEpochSeconds = proof.issuedAtEpochSeconds + DEVICE_IDENTITY_PROOF_MAX_AGE_SECONDS,
            nowEpochSeconds = nowEpochSeconds,
        )
    ) {
        return DeviceIdentityProofVerification.Replay
    }
    return DeviceIdentityProofVerification.Valid
}

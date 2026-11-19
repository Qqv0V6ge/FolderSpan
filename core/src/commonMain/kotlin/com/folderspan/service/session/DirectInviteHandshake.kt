package com.folderspan.service.session

import com.folderspan.data.main.webrtc.WebRtcDirectInvite
import com.folderspan.service.data.DeviceSessionBootstrapAuthorization
import com.folderspan.service.http.tls.DeviceTlsIdentity
import com.folderspan.service.http.tls.TrustedDeviceCertificateStore
import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import korlibs.crypto.sha256
import kotlin.io.encoding.Base64
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

internal const val DIRECT_INVITE_HANDSHAKE_VERSION = 1
internal const val DIRECT_INVITE_LOOKUP_RPC = "DirectInviteLookup"
internal const val DIRECT_INVITE_CHALLENGE_RPC = "DirectInviteChallenge"
internal const val DIRECT_INVITE_OFFER_RPC = "DirectInviteOffer"
internal const val DIRECT_INVITE_ICE_RPC = "DirectInviteIce"
internal const val DIRECT_INVITE_MAX_SDP_BYTES = 32 * 1024
internal const val DIRECT_INVITE_MAX_CONTROL_BYTES = 48 * 1024
internal const val DIRECT_INVITE_MAX_ICE_BATCH = 8
internal const val DIRECT_INVITE_MAX_CANDIDATES = 128

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DirectInviteChallenge(
    @ProtoNumber(1) val version: Int,
    @ProtoNumber(2) val nonce: String = "",
    @ProtoNumber(3) val issuerCertificate: String = "",
    @ProtoNumber(4) val issuerSignature: String = "",
) {
    override fun toString(): String = "DirectInviteChallenge(<redacted>)"
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DirectInviteLookup(
    @ProtoNumber(1) val code: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val proof: DirectInviteChallenge,
) {
    override fun toString(): String = "DirectInviteLookup(<redacted>)"
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DirectInviteOffer(
    @ProtoNumber(1) val deviceId: String,
    @ProtoNumber(2) val connectionAttemptId: String,
    @ProtoNumber(3) val credential: String,
    @ProtoNumber(4) val sdp: String,
    @ProtoNumber(5) val certificatePem: String,
    @ProtoNumber(6) val signature: String = "",
) {
    override fun toString() = "DirectInviteOffer(<redacted>)"
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DirectInviteAnswer(
    @ProtoNumber(1) val sdp: String,
    @ProtoNumber(2) val authorization: DeviceSessionBootstrapAuthorization,
) {
    override fun toString() = "DirectInviteAnswer(<redacted>)"
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DirectInviteIceCandidate(
    @ProtoNumber(1) val mid: String,
    @ProtoNumber(2) val index: Int,
    @ProtoNumber(3) val value: String,
) {
    fun isValid(): Boolean = mid.length <= 64 && index in 0..16 && value.length in 1..2048

    override fun toString() = "DirectInviteIceCandidate(<redacted>)"
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DirectInviteIceBatch(
    @ProtoNumber(1) val offset: Int,
    @ProtoNumber(2) val candidates: List<DirectInviteIceCandidate> = emptyList(),
) {
    fun isValid(): Boolean = offset in 0..DIRECT_INVITE_MAX_CANDIDATES &&
        candidates.size <= DIRECT_INVITE_MAX_ICE_BATCH && candidates.all { it.isValid() }
}

private val directInviteToken = Regex("[A-Za-z0-9_-]{43}")
private val directInviteAttempt = Regex("[A-Za-z0-9_-]{1,128}")
private val dtlsFingerprint = Regex("a=fingerprint:sha-256 ((?:[A-Fa-f0-9]{2}:){31}[A-Fa-f0-9]{2})", RegexOption.IGNORE_CASE)

internal fun DirectInviteOffer.binding(): DirectInviteBinding? {
    if (deviceId.length !in 1..128 || deviceId.any { it.isWhitespace() || it.isISOControl() } ||
        !directInviteAttempt.matches(connectionAttemptId) || !directInviteToken.matches(credential) ||
        certificatePem.length !in 1..8192 || sdp.length !in 1..DIRECT_INVITE_MAX_SDP_BYTES
    ) return null
    val sdpBytes = sdp.encodeToByteArray()
    if (sdpBytes.size > DIRECT_INVITE_MAX_SDP_BYTES) return null
    val fingerprints = sdp.lineSequence().filter { it.startsWith("a=fingerprint:", ignoreCase = true) }.map { line ->
        dtlsFingerprint.matchEntire(line)?.groupValues?.get(1)?.let(::normalizeTlsFingerprintSha256)
    }.toSet()
    if (fingerprints.size != 1 || null in fingerprints) return null
    val certificate = pemBytes(certificatePem, "CERTIFICATE") ?: return null
    return DirectInviteBinding(
        initiatorDeviceId = deviceId,
        initiatorCertificateSha256 = certificate.sha256().hexLower,
        connectionAttemptId = connectionAttemptId,
        offerSha256 = sdpBytes.sha256().hexLower,
    )
}

internal fun DirectInviteOffer.proofPayload(
    challenge: DirectInviteChallenge,
    targetDeviceId: String,
    targetFingerprint: String,
    binding: DirectInviteBinding,
): ByteArray = buildString {
    appendLine("folderspan-direct-invite-v${challenge.version}")
    appendLine(challenge.nonce)
    appendLine(targetDeviceId.toByteArrayDigest())
    appendLine(normalizeTlsFingerprintSha256(targetFingerprint))
    appendLine(binding.initiatorDeviceId.toByteArrayDigest())
    appendLine(binding.initiatorCertificateSha256)
    appendLine(binding.connectionAttemptId)
    appendLine(credential.toByteArrayDigest())
    appendLine(binding.offerSha256)
}.encodeToByteArray()

internal suspend fun createDirectInviteOffer(
    invite: WebRtcDirectInvite,
    challenge: DirectInviteChallenge,
    deviceId: String,
    connectionAttemptId: String,
    sdp: String,
    certificatePem: String,
    sign: suspend (ByteArray) -> String? = DeviceTlsIdentity::signDirectInvite,
): DirectInviteOffer {
    require(challenge.version == DIRECT_INVITE_HANDSHAKE_VERSION && directInviteToken.matches(challenge.nonce)) {
        "unsupported direct invitation handshake"
    }
    val offer = DirectInviteOffer(deviceId, connectionAttemptId, invite.credential, sdp, certificatePem)
    val binding = requireNotNull(offer.binding()) { "invalid direct invitation offer" }
    val signature = sign(offer.proofPayload(challenge, invite.deviceId, invite.fingerprintSha256, binding))
    check(!signature.isNullOrEmpty() && signature.length <= 1024) { "direct invitation signature unavailable" }
    return offer.copy(signature = signature)
}

internal suspend fun verifyDirectInviteOffer(
    offer: DirectInviteOffer,
    challenge: DirectInviteChallenge,
    targetDeviceId: String,
    targetFingerprint: String,
    trustedFingerprint: String = TrustedDeviceCertificateStore.expectedFingerprint(offer.deviceId),
    trustedKey: String = TrustedDeviceCertificateStore.expectedPublicKeyPem(offer.deviceId),
    certificateKey: suspend (String) -> String? = DeviceTlsIdentity::publicKeyFromCertificate,
    verify: suspend (String, ByteArray, String) -> Boolean = DeviceTlsIdentity::verifySha256WithRsa,
): DirectInviteBinding? {
    if (challenge.version != DIRECT_INVITE_HANDSHAKE_VERSION || !directInviteToken.matches(challenge.nonce) ||
        offer.signature.length !in 1..1024
    ) return null
    val binding = offer.binding() ?: return null
    if (trustedFingerprint.isNotEmpty() &&
        normalizeTlsFingerprintSha256(trustedFingerprint) != binding.initiatorCertificateSha256.uppercase()
    ) return null
    val publicKey = certificateKey(offer.certificatePem) ?: return null
    if (trustedKey.isNotEmpty()) {
        val trustedBytes = pemBytes(trustedKey, "PUBLIC KEY") ?: return null
        val publicBytes = pemBytes(publicKey, "PUBLIC KEY") ?: return null
        if (!trustedBytes.contentEquals(publicBytes)) return null
    }
    val payload = offer.proofPayload(challenge, targetDeviceId, targetFingerprint, binding)
    return binding.takeIf { verify(publicKey, payload, offer.signature) }
}

internal fun pemBytes(pem: String, label: String): ByteArray? {
    val normalized = pem.trim()
    val prefix = "-----BEGIN $label-----"
    val suffix = "-----END $label-----"
    if (!normalized.startsWith(prefix) || !normalized.endsWith(suffix)) return null
    return runCatching {
        Base64.decode(normalized.removePrefix(prefix).removeSuffix(suffix).filterNot(Char::isWhitespace))
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

private fun String.toByteArrayDigest(): String = encodeToByteArray().sha256().hexLower

internal fun DirectInviteOffer.sessionBinding(binding: DirectInviteBinding) =
    DirectInviteSessionBinding(credential.toByteArrayDigest(), binding)

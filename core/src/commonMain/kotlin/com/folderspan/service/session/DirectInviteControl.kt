package com.folderspan.service.session

import com.folderspan.data.main.webrtc.WebRtcDirectInvite
import com.folderspan.data.main.webrtc.WebRtcShareCode
import com.folderspan.service.http.tls.DeviceTlsIdentity
import com.folderspan.service.http.tls.TrustedDeviceCertificateStore
import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import com.folderspan.utils.ProtoBufCodec
import com.folderspan.utils.secureRandomBytes
import korlibs.crypto.sha256
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.TimeSource

private const val DIRECT_INVITE_CONTACT_TIMEOUT_MS = 8_000L
private const val DIRECT_INVITE_OFFER_TIMEOUT_MS = 20_000L

internal class DirectInviteControlHandler(
    private val localDeviceId: () -> String,
    private val localFingerprint: () -> String,
    private val exchangeIce: suspend (DirectInviteSessionBinding, DirectInviteIceBatch) -> DirectInviteIceBatch = { _, batch ->
        DirectInviteIceBatch(batch.offset)
    },
    private val rendezvousInvite: WebRtcDirectInvite? = null,
    private val issuerName: () -> String = localDeviceId,
    private val receiveOffer: suspend (DirectInviteOffer, DirectInviteBinding) -> DirectInviteAnswer,
) {
    private val mutex = Mutex()
    private var challenge: DirectInviteChallenge? = null
    private var issuedAt: TimeSource.Monotonic.ValueTimeMark? = null
    private var started = false
    private var accepted: DirectInviteSessionBinding? = null

    suspend fun handle(request: DeviceSessionControlRequest): DeviceSessionControlResponse {
        if (request.payload.size > DIRECT_INVITE_MAX_CONTROL_BYTES) return response(request, DEVICE_SESSION_RPC_BAD_REQUEST)
        return when (request.method) {
            DIRECT_INVITE_LOOKUP_RPC -> lookup(request)
            DIRECT_INVITE_CHALLENGE_RPC -> mutex.withLock {
                val query = runCatching { ProtoBufCodec.decode<DirectInviteChallenge>(request.payload) }.getOrNull()
                    ?: return@withLock response(request, DEVICE_SESSION_RPC_BAD_REQUEST)
                if (started) return@withLock response(request, DEVICE_SESSION_RPC_CONFLICT)
                started = true
                if (query.version != DIRECT_INVITE_HANDSHAKE_VERSION) {
                    return@withLock response(request, DEVICE_SESSION_RPC_OK, ProtoBufCodec.encode(DirectInviteChallenge(DIRECT_INVITE_HANDSHAKE_VERSION)))
                }
                val nonce = newDirectInviteNonce()
                val issued = if (rendezvousInvite == null) {
                    DirectInviteChallenge(DIRECT_INVITE_HANDSHAKE_VERSION, nonce)
                } else {
                    if (!rendezvousNonce.matches(query.nonce)) return@withLock response(request, DEVICE_SESSION_RPC_BAD_REQUEST)
                    signedChallenge(rendezvousInvite, query.nonce, nonce)
                }
                challenge = issued
                issuedAt = TimeSource.Monotonic.markNow()
                response(request, DEVICE_SESSION_RPC_OK, ProtoBufCodec.encode(issued))
            }
            DIRECT_INVITE_OFFER_RPC -> handleOffer(request)
            DIRECT_INVITE_ICE_RPC -> handleCandidates(request)
            else -> response(request, DEVICE_SESSION_RPC_UNAUTHORIZED)
        }
    }

    private suspend fun lookup(request: DeviceSessionControlRequest): DeviceSessionControlResponse {
        val invite = rendezvousInvite ?: return response(request, DEVICE_SESSION_RPC_UNAUTHORIZED)
        if (invite.expiresAtEpochMillis <= Clock.System.now().toEpochMilliseconds()) {
            return response(request, DEVICE_SESSION_RPC_FORBIDDEN)
        }
        val query = runCatching { ProtoBufCodec.decode<DirectInviteChallenge>(request.payload) }.getOrNull()
        if (query == null || query.version != DIRECT_INVITE_HANDSHAKE_VERSION || !rendezvousNonce.matches(query.nonce)) {
            return response(request, DEVICE_SESSION_RPC_BAD_REQUEST)
        }
        val name = issuerName().take(128).filterNot(Char::isISOControl).ifBlank { invite.deviceId }
        val proof = signedChallenge(invite, query.nonce, newDirectInviteNonce(), name)
        val metadata = DirectInviteLookup(WebRtcShareCode.encodeDirectInvite(invite), name, proof)
        return response(request, DEVICE_SESSION_RPC_OK, ProtoBufCodec.encode(metadata))
    }

    private suspend fun handleOffer(request: DeviceSessionControlRequest): DeviceSessionControlResponse {
        val issued = mutex.withLock {
            val valid = issuedAt?.elapsedNow()?.inWholeMilliseconds?.let { it < DIRECT_INVITE_OFFER_TIMEOUT_MS } == true
            challenge.takeIf { valid }.also { challenge = null }
        } ?: return response(request, DEVICE_SESSION_RPC_UNAUTHORIZED)
        val offer = runCatching { ProtoBufCodec.decode<DirectInviteOffer>(request.payload) }.getOrNull()
            ?: return response(request, DEVICE_SESSION_RPC_BAD_REQUEST)
        if (rendezvousInvite != null && offer.credential != rendezvousInvite.credential) {
            return response(request, DEVICE_SESSION_RPC_UNAUTHORIZED)
        }
        val binding = verifyDirectInviteOffer(offer, issued, localDeviceId(), localFingerprint())
            ?: return response(request, DEVICE_SESSION_RPC_UNAUTHORIZED)
        return try {
            val answer = withTimeout(DIRECT_INVITE_OFFER_TIMEOUT_MS) { receiveOffer(offer, binding) }
            val bytes = ProtoBufCodec.encode(answer)
            if (bytes.size > DIRECT_INVITE_MAX_CONTROL_BYTES) response(request, DEVICE_SESSION_RPC_INTERNAL)
            else {
                mutex.withLock { accepted = offer.sessionBinding(binding) }
                response(request, DEVICE_SESSION_RPC_OK, bytes)
            }
        } catch (error: DirectInviteException) {
            val status = when (error.reason) {
                DirectInviteDenial.AlreadyClaimed -> DEVICE_SESSION_RPC_CONFLICT
                DirectInviteDenial.Capacity -> DEVICE_SESSION_RPC_RATE_LIMITED
                else -> DEVICE_SESSION_RPC_FORBIDDEN
            }
            response(request, status)
        }
    }

    private suspend fun handleCandidates(request: DeviceSessionControlRequest): DeviceSessionControlResponse {
        val binding = mutex.withLock { accepted } ?: return response(request, DEVICE_SESSION_RPC_UNAUTHORIZED)
        val batch = runCatching { ProtoBufCodec.decode<DirectInviteIceBatch>(request.payload) }.getOrNull()
        if (batch == null || !batch.isValid()) return response(request, DEVICE_SESSION_RPC_BAD_REQUEST)
        return try {
            response(request, DEVICE_SESSION_RPC_OK, ProtoBufCodec.encode(exchangeIce(binding, batch)))
        } catch (_: DirectInviteException) {
            response(request, DEVICE_SESSION_RPC_FORBIDDEN)
        }
    }

    private fun response(
        request: DeviceSessionControlRequest,
        status: Int,
        payload: ByteArray = byteArrayOf(),
    ) = DeviceSessionControlResponse(requestId = request.requestId, status = status, payload = payload)
}

internal suspend fun <T> withDirectInviteControl(
    invite: WebRtcDirectInvite,
    scope: CoroutineScope,
    rendezvousUrl: String = DIRECT_INVITE_RENDEZVOUS_URL,
    action: suspend DeviceSessionPeer.(DirectInviteChallenge) -> T,
): T {
    val pin = verifiedInvitePin(invite)
    return withInvitePeer(scope, {
        if (invite.usesRendezvous) openDirectInviteRendezvous(rendezvousUrl, invite.credential, issuer = false)
        else connectPinnedDeviceSessionChannel(invite.host, invite.port, pin, DIRECT_INVITE_CONTACT_TIMEOUT_MS.toInt())
    }) {
        action(requestInviteChallenge(invite))
    }
}

internal suspend fun <T> withTemporaryDeviceControl(
    credential: String,
    scope: CoroutineScope,
    url: String,
    action: suspend DeviceSessionPeer.(WebRtcDirectInvite, String) -> T,
): T = withInvitePeer(scope, { openDirectInviteRendezvous(url, credential, issuer = false) }) {
    val unavailable = CompletableDeferred<Nothing>()
    setCloseHandler {
        unavailable.completeExceptionally(DeviceSessionClosedException("inviting device went offline"))
    }
    val nonce = newDirectInviteNonce()
    val lookup = withTimeout(DIRECT_INVITE_CONTACT_TIMEOUT_MS) {
        rpcValue<DirectInviteChallenge, DirectInviteLookup>(
            DIRECT_INVITE_LOOKUP_RPC, DirectInviteChallenge(DIRECT_INVITE_HANDSHAKE_VERSION, nonce),
        )
    }
    val invite = WebRtcShareCode.decodeDirectInvite(lookup.code)
    require(invite.usesRendezvous && invite.credential == credential &&
        WebRtcShareCode.temporaryIdMatches(credential, invite.fingerprintSha256) &&
        lookup.name.length in 1..128 && lookup.name.none(Char::isISOControl)) { "temporary ID does not match the device" }
    verifyRendezvousIssuer(invite, verifiedInvitePin(invite), nonce, lookup.proof, lookup.name)
    coroutineScope {
        val result = async { action(invite, lookup.name) }
        try {
            select {
                result.onAwait { it }
                unavailable.onAwait { it }
            }
        } finally {
            result.cancel()
        }
    }
}

private fun verifiedInvitePin(invite: WebRtcDirectInvite): String {
    WebRtcShareCode.validateDirectInvite(invite)
    if (invite.expiresAtEpochMillis <= Clock.System.now().toEpochMilliseconds()) {
        throw DirectInviteException(DirectInviteDenial.Expired)
    }
    val pinned = normalizeTlsFingerprintSha256(invite.fingerprintSha256)
    val known = TrustedDeviceCertificateStore.expectedFingerprint(invite.deviceId)
    require(known.isEmpty() || known == pinned) { "direct invitation certificate mismatch" }
    return pinned
}

internal suspend fun DeviceSessionPeer.requestInviteChallenge(invite: WebRtcDirectInvite): DirectInviteChallenge {
    val pin = verifiedInvitePin(invite)
    val nonce = if (invite.usesRendezvous) newDirectInviteNonce() else ""
    val challenge = withTimeout(DIRECT_INVITE_CONTACT_TIMEOUT_MS) {
        rpcValue<DirectInviteChallenge, DirectInviteChallenge>(
            DIRECT_INVITE_CHALLENGE_RPC, DirectInviteChallenge(DIRECT_INVITE_HANDSHAKE_VERSION, nonce),
        )
    }
    if (invite.usesRendezvous) verifyRendezvousIssuer(invite, pin, nonce, challenge)
    return challenge
}

private suspend fun <T> withInvitePeer(
    scope: CoroutineScope,
    open: suspend () -> DeviceSessionByteChannel,
    action: suspend DeviceSessionPeer.() -> T,
): T {
    var channel: DeviceSessionByteChannel? = null
    var peer: DeviceSessionPeer? = null
    try {
        withTimeout(DIRECT_INVITE_CONTACT_TIMEOUT_MS) { channel = open() }
        val plan = DeviceSessionWindowPlan.fromMemory()
        val active = DeviceSessionPeer(DeviceSessionTransport(checkNotNull(channel), plan, plan, isClient = true), receivePlan = plan)
        peer = active
        active.start(scope)
        return active.action()
    } finally {
        withContext(NonCancellable) {
            val active = peer
            if (active == null) channel?.close() else active.close()
        }
    }
}

internal suspend fun DeviceSessionPeer.submitDirectInviteOffer(
    invite: WebRtcDirectInvite,
    challenge: DirectInviteChallenge,
    attemptId: String,
    sdp: String,
    localDeviceId: String,
): DirectInviteAnswer {
    val certificate = checkNotNull(DeviceTlsIdentity.certificatePem()) { "direct invitation identity unavailable" }
    val offer = createDirectInviteOffer(invite, challenge, localDeviceId, attemptId, sdp, certificate)
    return withTimeoutOrNull(DIRECT_INVITE_OFFER_TIMEOUT_MS) {
        rpcValue<DirectInviteOffer, DirectInviteAnswer>(DIRECT_INVITE_OFFER_RPC, offer)
    } ?: throw DeviceSessionClosedException("direct invitation response timed out")
}

private val rendezvousNonce = Regex("[A-Za-z0-9_-]{43}")

private fun newDirectInviteNonce(): String =
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(secureRandomBytes(32))

private fun rendezvousProof(invite: WebRtcDirectInvite, receiver: String, issuer: String, name: String?): ByteArray {
    val codeHash = WebRtcShareCode.encodeDirectInvite(invite).encodeToByteArray().sha256().hexLower
    val proof = "FolderSpan.InviteIssuer.v1\n$codeHash\n$receiver\n$issuer"
    return (if (name == null) proof else "$proof\n$name").encodeToByteArray()
}

private suspend fun signedChallenge(
    invite: WebRtcDirectInvite, receiver: String, issuer: String, name: String? = null,
): DirectInviteChallenge {
    val certificate = checkNotNull(DeviceTlsIdentity.certificatePem())
    val signature = checkNotNull(DeviceTlsIdentity.signDirectInvite(rendezvousProof(invite, receiver, issuer, name)))
    return DirectInviteChallenge(DIRECT_INVITE_HANDSHAKE_VERSION, issuer, certificate, signature)
}

private suspend fun verifyRendezvousIssuer(
    invite: WebRtcDirectInvite,
    fingerprint: String,
    nonce: String,
    challenge: DirectInviteChallenge,
    name: String? = null,
) {
    require(challenge.version == DIRECT_INVITE_HANDSHAKE_VERSION && rendezvousNonce.matches(challenge.nonce) &&
        challenge.issuerCertificate.length in 1..8192 && challenge.issuerSignature.length in 1..1024) {
        "invalid invitation issuer proof"
    }
    val der = requireNotNull(pemBytes(challenge.issuerCertificate, "CERTIFICATE"))
    require(normalizeTlsFingerprintSha256(der.sha256().hexLower) == fingerprint) { "invitation issuer certificate mismatch" }
    val key = requireNotNull(DeviceTlsIdentity.publicKeyFromCertificate(challenge.issuerCertificate))
    require(DeviceTlsIdentity.verifySha256WithRsa(key, rendezvousProof(invite, nonce, challenge.nonce, name),
        challenge.issuerSignature)) { "invalid invitation issuer signature" }
}

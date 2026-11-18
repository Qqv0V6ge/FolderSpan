package com.folderspan.service.session

import com.folderspan.data.main.webrtc.WebRtcDirectInvite
import com.folderspan.data.main.webrtc.WebRtcShareCode
import com.folderspan.service.http.tls.DeviceTlsIdentity
import com.folderspan.service.http.tls.TrustedDeviceCertificateStore
import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import com.folderspan.utils.ProtoBufCodec
import com.folderspan.utils.secureRandomBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
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
            DIRECT_INVITE_CHALLENGE_RPC -> mutex.withLock {
                val version = runCatching { ProtoBufCodec.decode<DirectInviteChallenge>(request.payload).version }.getOrNull()
                    ?: return@withLock response(request, DEVICE_SESSION_RPC_BAD_REQUEST)
                if (started) return@withLock response(request, DEVICE_SESSION_RPC_CONFLICT)
                started = true
                if (version != DIRECT_INVITE_HANDSHAKE_VERSION) {
                    return@withLock response(request, DEVICE_SESSION_RPC_OK, ProtoBufCodec.encode(DirectInviteChallenge(DIRECT_INVITE_HANDSHAKE_VERSION)))
                }
                val nonce = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(secureRandomBytes(32))
                val issued = DirectInviteChallenge(DIRECT_INVITE_HANDSHAKE_VERSION, nonce)
                challenge = issued
                issuedAt = TimeSource.Monotonic.markNow()
                response(request, DEVICE_SESSION_RPC_OK, ProtoBufCodec.encode(issued))
            }
            DIRECT_INVITE_OFFER_RPC -> handleOffer(request)
            DIRECT_INVITE_ICE_RPC -> handleCandidates(request)
            else -> response(request, DEVICE_SESSION_RPC_UNAUTHORIZED)
        }
    }

    private suspend fun handleOffer(request: DeviceSessionControlRequest): DeviceSessionControlResponse {
        val issued = mutex.withLock {
            val valid = issuedAt?.elapsedNow()?.inWholeMilliseconds?.let { it < DIRECT_INVITE_OFFER_TIMEOUT_MS } == true
            challenge.takeIf { valid }.also { challenge = null }
        } ?: return response(request, DEVICE_SESSION_RPC_UNAUTHORIZED)
        val offer = runCatching { ProtoBufCodec.decode<DirectInviteOffer>(request.payload) }.getOrNull()
            ?: return response(request, DEVICE_SESSION_RPC_BAD_REQUEST)
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
    action: suspend DeviceSessionPeer.(DirectInviteChallenge) -> T,
): T {
    WebRtcShareCode.validateDirectInvite(invite)
    if (invite.expiresAtEpochMillis <= Clock.System.now().toEpochMilliseconds()) {
        throw DirectInviteException(DirectInviteDenial.Expired)
    }
    val pinned = normalizeTlsFingerprintSha256(invite.fingerprintSha256)
    val known = TrustedDeviceCertificateStore.expectedFingerprint(invite.deviceId)
    require(known.isEmpty() || known == pinned) { "direct invitation certificate mismatch" }
    var channel: DeviceSessionByteChannel? = null
    var peer: DeviceSessionPeer? = null
    try {
        withTimeout(DIRECT_INVITE_CONTACT_TIMEOUT_MS) {
            channel = connectPinnedDeviceSessionChannel(invite.host, invite.port, pinned, DIRECT_INVITE_CONTACT_TIMEOUT_MS.toInt())
        }
        val plan = DeviceSessionWindowPlan.fromMemory()
        val active = DeviceSessionPeer(DeviceSessionTransport(checkNotNull(channel), plan, plan, isClient = true), receivePlan = plan)
        peer = active
        active.start(scope)
        val challenge = withTimeout(DIRECT_INVITE_CONTACT_TIMEOUT_MS) {
            active.rpcValue<DirectInviteChallenge, DirectInviteChallenge>(
                DIRECT_INVITE_CHALLENGE_RPC, DirectInviteChallenge(DIRECT_INVITE_HANDSHAKE_VERSION),
            )
        }
        return active.action(challenge)
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

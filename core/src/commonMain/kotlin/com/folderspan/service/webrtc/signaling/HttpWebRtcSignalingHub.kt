package com.folderspan.service.webrtc.signaling

import strings.AppStrings

import com.folderspan.extensions.randomString
import com.folderspan.service.data.DeviceSessionBootstrapAuthorizationType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

enum class HttpWebRtcSignalingRole {
    Host,
    Browser
}

@Serializable
data class HttpWebRtcJoinRequest(
    val role: HttpWebRtcSignalingRole,
    val device: SignalingDevice
)

@Serializable
data class HttpWebRtcJoinResponse(
    val clientId: String,
    val clientToken: String = "",
    val peers: List<SignalingDevice>,
    val messages: List<SignalingMessage> = emptyList(),
    val nextCursor: Long = 0L,
    val code: String? = null,
    val message: String? = null
)

@Serializable
data class HttpWebRtcDiscoverResponse(
    val host: SignalingDevice? = null,
    val code: String? = null,
    val message: String? = null
)

@Serializable
data class HttpWebRtcSignalRequest(
    val clientId: String,
    val clientToken: String = "",
    val message: SignalingMessage
)

@Serializable
data class HttpWebRtcLeaveRequest(
    val clientId: String,
    val clientToken: String = ""
)

@Serializable
data class HttpWebRtcPollResponse(
    val messages: List<SignalingMessage>,
    val nextCursor: Long
)

@Serializable
data class HttpWebRtcSignalResponse(
    val accepted: Boolean,
    val code: String? = null,
    val message: String? = null
)

class HttpWebRtcSignalingHub {
    private val peers = linkedMapOf<String, Peer>()
    private val events = mutableListOf<Event>()
    private val sendWindows = mutableMapOf<String, RateWindow>()
    private val mutex = Mutex()
    private var nextSequence = 1L

    fun join(role: HttpWebRtcSignalingRole, device: SignalingDevice): HttpWebRtcJoinResponse {
        if (role == HttpWebRtcSignalingRole.Host) {
            return HttpWebRtcJoinResponse(
                clientId = "",
                peers = emptyList(),
                code = "HOST_JOIN_FORBIDDEN",
                message = "Host can only be registered by the local app"
            )
        }
        return joinInternal(role, device)
    }

    fun registerHost(device: SignalingDevice): HttpWebRtcJoinResponse {
        return joinInternal(HttpWebRtcSignalingRole.Host, device)
    }

    suspend fun registerHostLocked(device: SignalingDevice): HttpWebRtcJoinResponse {
        return mutex.withLock { registerHost(device) }
    }

    private fun joinInternal(role: HttpWebRtcSignalingRole, device: SignalingDevice): HttpWebRtcJoinResponse {
        val normalized = device.normalized()
        if (normalized.id.isBlank()) {
            return HttpWebRtcJoinResponse(
                clientId = "",
                peers = emptyList(),
                code = "INVALID_DEVICE",
                message = "Device id is required"
            )
        }
        if (!normalized.isWithinLimits()) {
            return HttpWebRtcJoinResponse(
                clientId = "",
                peers = emptyList(),
                code = "INVALID_DEVICE",
                message = "Device fields are too long"
            )
        }

        val existingPeer = peers[normalized.id]
        if (role == HttpWebRtcSignalingRole.Browser && existingPeer != null) {
            return HttpWebRtcJoinResponse(
                clientId = "",
                peers = emptyList(),
                code = "DEVICE_ALREADY_JOINED",
                message = "Device id is already joined"
            )
        }
        if (role == HttpWebRtcSignalingRole.Browser && browserPeerCount() >= MAX_BROWSER_PEERS) {
            return HttpWebRtcJoinResponse(
                clientId = "",
                peers = emptyList(),
                code = "PEER_LIMIT_EXCEEDED",
                message = "Too many browser peers are connected"
            )
        }

        if (role == HttpWebRtcSignalingRole.Host) {
            peers.entries.removeAll { item ->
                item.value.role == HttpWebRtcSignalingRole.Host && item.key != normalized.id
            }
        }
        val existingToken = existingPeer?.clientToken
        val clientToken = existingToken?.takeIf { item -> item.isNotBlank() }
            ?: 48.randomString(includeSpecial = false)
        peers[normalized.id] = Peer(role = role, device = normalized, clientToken = clientToken)
        val visiblePeers = when (role) {
            HttpWebRtcSignalingRole.Host -> peers.values
                .filter { item -> item.role == HttpWebRtcSignalingRole.Browser && item.device.id != normalized.id }
                .map { item -> item.device }

            HttpWebRtcSignalingRole.Browser -> peers.values
                .firstOrNull { item -> item.role == HttpWebRtcSignalingRole.Host }
                ?.let { item -> listOf(item.device) }
                .orEmpty()
        }

        val joined = SignalingMessage(
            type = "joined",
            from = normalized,
            peers = visiblePeers,
            ts = nowMs()
        )
        notifyPeerJoined(role, normalized)
        return HttpWebRtcJoinResponse(
            clientId = normalized.id,
            clientToken = clientToken,
            peers = visiblePeers,
            messages = listOf(joined),
            nextCursor = nextSequence - 1L
        )
    }

    fun discoverHost(): HttpWebRtcDiscoverResponse {
        val host = peers.values
            .firstOrNull { item -> item.role == HttpWebRtcSignalingRole.Host }
            ?.device
            ?.asDiscoveredHost()
        return HttpWebRtcDiscoverResponse(host = host)
    }

    suspend fun discoverHostLocked(): HttpWebRtcDiscoverResponse {
        return mutex.withLock { discoverHost() }
    }

    suspend fun joinLocked(role: HttpWebRtcSignalingRole, device: SignalingDevice): HttpWebRtcJoinResponse {
        return mutex.withLock { join(role, device) }
    }

    fun send(clientId: String, clientToken: String, message: SignalingMessage): HttpWebRtcSignalResponse {
        val normalizedClientId = clientId.trim()
        val fromId = message.from?.id?.trim().orEmpty()
        if (normalizedClientId.isBlank() || normalizedClientId != fromId) {
            return HttpWebRtcSignalResponse(
                accepted = false,
                code = "CLIENT_MISMATCH",
                message = "Signal sender does not match client id"
            )
        }
        val from = peers[normalizedClientId] ?: return HttpWebRtcSignalResponse(
            accepted = false,
            code = "NOT_JOINED",
            message = "Sender is not joined"
        )
        if (!from.clientToken.secureEquals(clientToken)) {
            return HttpWebRtcSignalResponse(
                accepted = false,
                code = "UNAUTHORIZED",
                message = "Invalid signaling client token"
            )
        }
        if (!message.isWithinLimits()) {
            return HttpWebRtcSignalResponse(
                accepted = false,
                code = "INVALID_MESSAGE",
                message = "Signaling message fields are too long"
            )
        }
        if (message.type == "connect-approved" && from.role != HttpWebRtcSignalingRole.Host) {
            return HttpWebRtcSignalResponse(
                accepted = false,
                code = "PEER_NOT_ALLOWED",
                message = AppStrings.ui_webrtc_only_host_can_approve_browser_connection,
            )
        }
        if (!message.hasValidConnectionAttemptBinding()) {
            return HttpWebRtcSignalResponse(
                accepted = false,
                code = "INVALID_CONNECTION_ATTEMPT",
                message = "Missing or mismatched WebRTC connection attempt binding",
            )
        }
        if (!allowSend(normalizedClientId)) {
            return HttpWebRtcSignalResponse(
                accepted = false,
                code = "RATE_LIMITED",
                message = "Too many signaling messages"
            )
        }
        val toId = message.to?.id?.trim().orEmpty()
        val to = peers[toId] ?: return HttpWebRtcSignalResponse(
            accepted = false,
            code = "PEER_NOT_FOUND",
            message = "Target peer is not joined"
        )
        if (!canRoute(from, to)) {
            return HttpWebRtcSignalResponse(
                accepted = false,
                code = "PEER_NOT_ALLOWED",
                message = "Browser peers can only signal with the app host"
            )
        }
        enqueue(to.device.id, message)
        return HttpWebRtcSignalResponse(accepted = true)
    }

    suspend fun sendLocked(
        clientId: String,
        clientToken: String,
        message: SignalingMessage
    ): HttpWebRtcSignalResponse {
        return mutex.withLock { send(clientId, clientToken, message) }
    }

    fun poll(clientId: String, clientToken: String, cursor: Long): HttpWebRtcPollResponse {
        val normalizedId = clientId.trim()
        val peer = peers[normalizedId]
        if (normalizedId.isBlank() || peer == null || !peer.clientToken.secureEquals(clientToken)) {
            return HttpWebRtcPollResponse(messages = emptyList(), nextCursor = cursor.coerceAtLeast(0L))
        }
        val startCursor = cursor.coerceAtLeast(0L)
        val messages = events
            .asSequence()
            .filter { event -> event.sequence > startCursor && event.recipientId == normalizedId }
            .toList()
        val nextCursor = messages.lastOrNull()?.sequence ?: startCursor
        return HttpWebRtcPollResponse(
            messages = messages.map { event -> event.message },
            nextCursor = nextCursor
        )
    }

    suspend fun pollLocked(clientId: String, clientToken: String, cursor: Long): HttpWebRtcPollResponse {
        return mutex.withLock { poll(clientId, clientToken, cursor) }
    }

    fun leave(clientId: String, clientToken: String): HttpWebRtcSignalResponse {
        val normalizedId = clientId.trim()
        val peer = peers[normalizedId] ?: return HttpWebRtcSignalResponse(accepted = true)
        if (!peer.clientToken.secureEquals(clientToken)) {
            return HttpWebRtcSignalResponse(
                accepted = false,
                code = "UNAUTHORIZED",
                message = "Invalid signaling client token"
            )
        }
        val leaving = peers.remove(normalizedId) ?: return HttpWebRtcSignalResponse(accepted = true)
        sendWindows.remove(normalizedId)
        val left = SignalingMessage(
            type = "peer-left",
            from = leaving.device,
            ts = nowMs()
        )
        when (leaving.role) {
            HttpWebRtcSignalingRole.Host -> peers.values
                .filter { item -> item.role == HttpWebRtcSignalingRole.Browser }
                .forEach { item -> enqueue(item.device.id, left) }

            HttpWebRtcSignalingRole.Browser -> peers.values
                .filter { item -> item.role == HttpWebRtcSignalingRole.Host }
                .forEach { item -> enqueue(item.device.id, left) }
        }
        return HttpWebRtcSignalResponse(accepted = true)
    }

    suspend fun leaveLocked(clientId: String, clientToken: String): HttpWebRtcSignalResponse {
        return mutex.withLock { leave(clientId, clientToken) }
    }

    private fun notifyPeerJoined(role: HttpWebRtcSignalingRole, device: SignalingDevice) {
        val joined = SignalingMessage(type = "peer-joined", from = device, ts = nowMs())
        when (role) {
            HttpWebRtcSignalingRole.Host -> peers.values
                .filter { item -> item.role == HttpWebRtcSignalingRole.Browser && item.device.id != device.id }
                .forEach { item -> enqueue(item.device.id, joined) }

            HttpWebRtcSignalingRole.Browser -> peers.values
                .filter { item -> item.role == HttpWebRtcSignalingRole.Host }
                .forEach { item -> enqueue(item.device.id, joined) }
        }
    }

    private fun canRoute(from: Peer, to: Peer): Boolean {
        return from.role != to.role && from.device.id != to.device.id
    }

    private fun browserPeerCount(): Int {
        return peers.values.count { item -> item.role == HttpWebRtcSignalingRole.Browser }
    }

    private fun allowSend(clientId: String): Boolean {
        val now = nowMs()
        val window = sendWindows[clientId]
        if (window == null || now - window.startedAtMs >= SEND_RATE_WINDOW_MS) {
            sendWindows[clientId] = RateWindow(startedAtMs = now, count = 1)
            return true
        }
        if (window.count >= MAX_SENDS_PER_WINDOW) return false
        sendWindows[clientId] = window.copy(count = window.count + 1)
        return true
    }

    private fun SignalingDevice.normalized(): SignalingDevice {
        return copy(
            id = id.trim(),
            name = name?.trim(),
            pathSeparator = pathSeparator?.trim(),
            host = host?.trim(),
            type = type?.trim(),
            connectType = connectType?.trim()
        )
    }

    private fun SignalingDevice.isWithinLimits(): Boolean {
        return id.length <= MAX_DEVICE_ID_LENGTH &&
            name.orEmpty().length <= MAX_DEVICE_FIELD_LENGTH &&
            pathSeparator.orEmpty().length <= MAX_DEVICE_FIELD_LENGTH &&
            host.orEmpty().length <= MAX_DEVICE_FIELD_LENGTH &&
            type.orEmpty().length <= MAX_DEVICE_FIELD_LENGTH &&
            connectType.orEmpty().length <= MAX_DEVICE_FIELD_LENGTH
    }

    private fun SignalingMessage.isWithinLimits(): Boolean {
        return type.length <= MAX_MESSAGE_TYPE_LENGTH &&
            roomId.orEmpty().length <= MAX_ROOM_ID_LENGTH &&
            sdp.orEmpty().length <= MAX_SDP_LENGTH &&
            candidate?.sdpMid.orEmpty().length <= MAX_DEVICE_FIELD_LENGTH &&
            candidate?.candidate.orEmpty().length <= MAX_CANDIDATE_LENGTH &&
            code.orEmpty().length <= MAX_DEVICE_FIELD_LENGTH &&
            connectionAttemptId.orEmpty().length <= MAX_AUTHORIZATION_FIELD_LENGTH &&
            bootstrapAuthorization?.opaqueAuthorization.orEmpty().length <= MAX_AUTHORIZATION_FIELD_LENGTH &&
            bootstrapAuthorization?.connectionAttemptId.orEmpty().length <= MAX_AUTHORIZATION_FIELD_LENGTH &&
            errorMessage.orEmpty().length <= MAX_ERROR_MESSAGE_LENGTH &&
            peers.orEmpty().size <= MAX_BROWSER_PEERS &&
            from?.isWithinLimits() != false &&
            to?.isWithinLimits() != false &&
            peers.orEmpty().all { item -> item.isWithinLimits() }
    }

    private fun SignalingMessage.hasValidConnectionAttemptBinding(): Boolean {
        val attemptId = connectionAttemptId?.takeIf(String::isNotBlank)
        return when (type) {
            "connect-request", "offer", "answer", "ice" -> attemptId != null
            "connect-approved" -> {
                val authorization = bootstrapAuthorization
                attemptId != null &&
                    authorization?.type == DeviceSessionBootstrapAuthorizationType.WEB_RTC_PREAPPROVED &&
                    authorization.connectionAttemptId == attemptId &&
                    authorization.opaqueAuthorization.isNotBlank()
            }
            else -> true
        }
    }

    private fun SignalingDevice.asDiscoveredHost(): SignalingDevice {
        return SignalingDevice(
            id = id,
            name = name,
            pathSeparator = pathSeparator,
            type = type
        )
    }

    private fun enqueue(recipientId: String, message: SignalingMessage) {
        events += Event(
            sequence = nextSequence++,
            recipientId = recipientId,
            message = message
        )
        if (events.size > MAX_EVENTS) {
            events.removeAt(0)
        }
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private data class Peer(
        val role: HttpWebRtcSignalingRole,
        val device: SignalingDevice,
        val clientToken: String
    )

    private data class Event(
        val sequence: Long,
        val recipientId: String,
        val message: SignalingMessage
    )

    private data class RateWindow(
        val startedAtMs: Long,
        val count: Int
    )

    companion object {
        private const val MAX_EVENTS = 1024
        private const val MAX_BROWSER_PEERS = 32
        private const val MAX_SENDS_PER_WINDOW = 32
        private const val SEND_RATE_WINDOW_MS = 60_000L
        private const val MAX_DEVICE_ID_LENGTH = 128
        private const val MAX_DEVICE_FIELD_LENGTH = 256
        private const val MAX_MESSAGE_TYPE_LENGTH = 64
        private const val MAX_ROOM_ID_LENGTH = 128
        private const val MAX_SDP_LENGTH = 16 * 1024
        private const val MAX_CANDIDATE_LENGTH = 4 * 1024
        private const val MAX_AUTHORIZATION_FIELD_LENGTH = 512
        private const val MAX_ERROR_MESSAGE_LENGTH = 512
    }
}

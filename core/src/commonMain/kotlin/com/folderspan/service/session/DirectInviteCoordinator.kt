package com.folderspan.service.session

import com.folderspan.data.main.webrtc.WebRtcDirectInvite
import com.folderspan.data.main.webrtc.WebRtcDirectConnectionException
import com.folderspan.data.main.webrtc.WebRtcDirectFailure
import com.folderspan.service.data.DeviceSessionBootstrapAuthorization
import com.folderspan.service.data.SocketDevice
import com.folderspan.data.main.webrtc.WebRtcShareCode
import com.folderspan.getSocketDevice
import com.folderspan.PlatformType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.http.tls.currentDeviceTlsFingerprint
import com.folderspan.service.webrtc.controller.MultiPeerWebRtcController
import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import com.folderspan.service.webrtc.models.buildWebRtcIceServers
import com.folderspan.service.webrtc.signaling.SignalingDevice
import com.folderspan.service.webrtc.signaling.SignalingIceCandidate
import com.folderspan.service.webrtc.signaling.SignalingMessage
import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.utils.secureRandomBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import strings.AppStrings

internal fun directInviteRuntimeError(): String? {
    if (PlatformType != DeviceType.JS) return null
    val target = WebUiAttachment.target
    return when {
        !WebUiAttachment.hasSession || !WebUiAttachment.connected || target == null -> AppStrings.direct_invite_runtime_required
        !target.protectsDeviceCredentials -> AppStrings.direct_invite_runtime_secure
        WebUiAttachment.hostInfo?.directInviteVersion != DIRECT_INVITE_HANDSHAKE_VERSION ||
            WebUiAttachment.hostInfo?.deviceCertificatePem.isNullOrBlank() -> AppStrings.direct_invite_runtime_version
        else -> null
    }
}

internal class DirectInviteCoordinator(
    private val scope: CoroutineScope,
    private val registry: DeviceSessionBootstrapAuthorizationRegistry,
    private val localDevice: () -> SocketDevice = ::getSocketDevice,
) {
    private val attempts = mutableMapOf<String, Attempt>()
    private val inbound = mutableMapOf<DirectInviteSessionBinding, Attempt>()
    private var lastIssuer: String? = null
    private val _states = MutableStateFlow<Map<String, WebRtcConnectionStatus>>(emptyMap())
    val states: StateFlow<Map<String, WebRtcConnectionStatus>> = _states

    suspend fun issue(host: String, port: Int, roleId: Long, stunUrl: String): WebRtcDirectInvite {
        val owner = currentDirectInviteIssuer()
        return onOwner(owner) {
            directInviteRuntimeError()?.let { error(it) }
            lastIssuer?.takeIf { it != owner }?.let { registry.revokeDirectInvites(it) }
            lastIssuer = owner
            registry.issueDirectInvite(owner, localDevice().id, host, port, currentDeviceTlsFingerprint(), roleId, stunUrl)
        }
    }

    suspend fun revoke(invite: WebRtcDirectInvite) = registry.revokeDirectInvite(currentDirectInviteIssuer(), invite.credential)

    fun controlHandler(owner: String): DirectInviteControlHandler {
        return DirectInviteControlHandler(
            localDeviceId = { localDevice().id },
            localFingerprint = ::currentDeviceTlsFingerprint,
            exchangeIce = { binding, batch -> onOwner(owner) {
                val attempt = inbound[binding] ?: throw DirectInviteException(DirectInviteDenial.Missing)
                batch.candidates.forEach(attempt::addPeerCandidate)
                val candidates = attempt.candidates.drop(batch.offset).take(DIRECT_INVITE_MAX_ICE_BATCH)
                DirectInviteIceBatch(batch.offset + candidates.size, candidates)
            } },
        ) { offer, binding ->
            val attempt = onOwner(owner) {
                val claim = registry.claimDirectInvite(owner, offer.credential, binding)
                inbound[claim.sessionBinding] ?: Attempt(
                    SignalingDevice(id = binding.initiatorDeviceId),
                    binding.connectionAttemptId,
                    claim.stunUrl,
                    claim,
                ).also { active ->
                    inbound[claim.sessionBinding] = active
                    active.start()
                    active.receive("offer", sdp = offer.sdp)
                }
            }
            DirectInviteAnswer(attempt.description.await(), checkNotNull(attempt.claim).authorization)
        }
    }

    suspend fun connect(code: String) {
        val invite = WebRtcShareCode.decodeDirectInvite(code)
        directInviteRuntimeError()?.let { error(it) }
        val owner = currentDirectInviteIssuer()
        val initiator = localDevice()
        val attempt = onOwner(owner) {
            require(invite.deviceId != initiator.id) { "cannot connect to your own direct invitation" }
            Attempt(SignalingDevice(id = invite.deviceId), newAttemptId(), invite.stunUrl, null)
        }
        var phase = WebRtcDirectFailure.Contact
        try {
            withTimeout(45_000L) {
                var answer: DirectInviteAnswer? = null
                var sent = 0
                var received = 0
                for (connection in 0..1) {
                    try {
                        withDirectInviteControl(invite, attempt.scope) { challenge ->
                            if (connection == 0) {
                                phase = WebRtcDirectFailure.Handshake
                                onOwner(owner) { attempt.start(initiator) }
                            } else if (attempt.connected.isCompleted) {
                                return@withDirectInviteControl
                            }
                            val offer = attempt.description.await()
                            val reply = submitDirectInviteOffer(invite, challenge, attempt.id, offer, initiator.id)
                            if (answer == null) {
                                answer = reply
                                phase = WebRtcDirectFailure.Ice
                                onOwner(owner) { attempt.receive("answer", reply.sdp, reply.authorization) }
                            } else {
                                check(answer == reply) { "direct invitation response changed" }
                            }
                            while (true) {
                                val candidates = onOwner(owner) {
                                    if (attempt.connected.isCompleted) return@onOwner null
                                    check(attempts[attempt.id] === attempt) { "direct invitation connection closed" }
                                    attempt.candidates.drop(sent).take(DIRECT_INVITE_MAX_ICE_BATCH)
                                } ?: break
                                val response = rpcValue<DirectInviteIceBatch, DirectInviteIceBatch>(
                                    DIRECT_INVITE_ICE_RPC, DirectInviteIceBatch(received, candidates),
                                )
                                check(response.isValid() && response.offset == received + response.candidates.size) {
                                    "invalid direct invitation candidate response"
                                }
                                sent += candidates.size
                                received = response.offset
                                onOwner(owner) { response.candidates.forEach(attempt::addPeerCandidate) }
                                delay(100L)
                            }
                        }
                        break
                    } catch (error: DeviceSessionClosedException) {
                        if (attempt.connected.isCompleted) break
                        if (connection != 0 || !attempt.description.isCompleted) throw error
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw WebRtcDirectConnectionException(phase, error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw WebRtcDirectConnectionException(phase, error)
        } finally {
            withContext(NonCancellable) {
                if (!attempt.connected.isCompleted) onOwner(null) { attempt.stop() }
            }
        }
    }

    suspend fun disconnect(attemptId: String) = onOwner(null) { attempts[attemptId]?.stop() }

    fun peer(attemptId: String): SignalingDevice? = attempts[attemptId]?.peer

    fun clients(attemptId: String): DeviceSessionClients? = attempts[attemptId]?.let { attempt ->
        attempt.controller.deviceSessionClients(attempt.peer.id)
    }

    suspend fun stop() = onOwner(null) {
        lastIssuer?.let { registry.revokeDirectInvites(it) }
        lastIssuer = null
        attempts.values.toList().forEach { it.stop() }
    }

    private suspend fun <T> onOwner(owner: String?, action: suspend () -> T): T =
        withContext(scope.coroutineContext.minusKey(Job)) {
            if (owner != null && owner != currentDirectInviteIssuer()) throw DirectInviteException(DirectInviteDenial.Missing)
            action()
        }

    private inner class Attempt(
        val peer: SignalingDevice,
        val id: String,
        private val stunUrl: String,
        val claim: DirectInviteClaim?,
    ) {
        val scope = CoroutineScope(this@DirectInviteCoordinator.scope.coroutineContext +
            SupervisorJob(this@DirectInviteCoordinator.scope.coroutineContext[Job]))
        val controller = MultiPeerWebRtcController(scope)
        val description = CompletableDeferred<String>()
        val connected = CompletableDeferred<Unit>()
        val candidates = mutableListOf<DirectInviteIceCandidate>()
        private val peerCandidates = linkedSetOf<DirectInviteIceCandidate>()

        fun start(device: SocketDevice = localDevice()) {
            if (attempts.size >= 32 || id in attempts) throw DirectInviteException(DirectInviteDenial.Capacity)
            attempts[id] = this
            controller.startDirect(peer, id, buildWebRtcIceServers(stunUrl), claim, device) { message ->
                when (message.type) {
                    "offer", "answer" -> message.sdp?.let(description::complete)
                    "ice" -> message.candidate?.let { candidate ->
                        if (candidates.size < DIRECT_INVITE_MAX_CANDIDATES) {
                            candidates += DirectInviteIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
                        }
                    }
                }
            }
            scope.launch {
                controller.peerSessionStates.collectLatest { list ->
                    val status = list.singleOrNull()?.status ?: return@collectLatest
                    _states.update { it + (id to status) }
                    when (status) {
                        WebRtcConnectionStatus.Connected -> connected.complete(Unit)
                        WebRtcConnectionStatus.Error, WebRtcConnectionStatus.Disconnected -> {
                            description.completeExceptionally(DeviceSessionIoException("direct WebRTC connection failed"))
                            stop()
                        }
                        else -> Unit
                    }
                }
            }
        }

        fun receive(type: String, sdp: String, authorization: DeviceSessionBootstrapAuthorization? = null) {
            controller.receiveDirect(SignalingMessage(type = type, from = peer, sdp = sdp,
                connectionAttemptId = id, bootstrapAuthorization = authorization))
        }

        fun addPeerCandidate(candidate: DirectInviteIceCandidate) {
            if (peerCandidates.size >= DIRECT_INVITE_MAX_CANDIDATES || !peerCandidates.add(candidate)) return
            controller.receiveDirect(SignalingMessage(type = "ice", from = peer, connectionAttemptId = id,
                candidate = SignalingIceCandidate(candidate.mid, candidate.index, candidate.value)))
        }

        suspend fun stop() {
            if (attempts[id] === this) {
                attempts.remove(id)
                _states.update { it - id }
            }
            claim?.let {
                if (inbound[it.sessionBinding] === this) inbound.remove(it.sessionBinding)
            }
            description.cancel()
            controller.disconnect()
            withContext(NonCancellable) {
                claim?.let { registry.revoke(it.authorization.opaqueAuthorization) }
                scope.cancel()
            }
        }
    }
}

internal fun currentDirectInviteIssuer(): String = "direct:${WebUiAttachment.sessionGeneration}"

private fun newAttemptId(): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(secureRandomBytes(24))

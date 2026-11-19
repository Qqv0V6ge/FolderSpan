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
import com.folderspan.service.webrtc.models.WebRtcIceServerConfig
import com.folderspan.service.webrtc.models.buildWebRtcIceServers
import com.folderspan.service.webrtc.signaling.SignalingDevice
import com.folderspan.service.webrtc.signaling.SignalingIceCandidate
import com.folderspan.service.webrtc.signaling.SignalingMessage
import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.utils.secureRandomBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
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
import kotlin.time.Clock
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
    private val rendezvousUrl: String = DIRECT_INVITE_RENDEZVOUS_URL,
    private val localDevice: () -> SocketDevice = ::getSocketDevice,
) {
    private val attempts = mutableMapOf<String, Attempt>()
    private val rendezvousJobs = mutableMapOf<String, Job>()
    private val inbound = mutableMapOf<DirectInviteSessionBinding, Attempt>()
    private var lastIssuer: String? = null
    private val _states = MutableStateFlow<Map<String, WebRtcConnectionStatus>>(emptyMap())
    val states: StateFlow<Map<String, WebRtcConnectionStatus>> = _states

    suspend fun issue(
        host: String, port: Int, roleId: Long, stunUrl: String,
        turnUrl: String = "", turnUsername: String = "", turnPassword: String = "",
        signalingUrl: String = rendezvousUrl,
    ): WebRtcDirectInvite {
        val owner = currentDirectInviteIssuer()
        return onOwner(owner) {
            directInviteRuntimeError()?.let { error(it) }
            lastIssuer?.takeIf { it != owner }?.let {
                registry.revokeDirectInvites(it)
                rendezvousJobs.values.toList().forEach(Job::cancel)
            }
            lastIssuer = owner
            val invite = registry.issueDirectInvite(
                owner, localDevice().id, host, port, currentDeviceTlsFingerprint(), roleId, stunUrl,
                turnUrl, turnUsername, turnPassword,
            )
            if (invite.usesRendezvous) {
                val ready = CompletableDeferred<Unit>()
                val job = scope.launch(start = CoroutineStart.LAZY) { runRendezvousIssuer(owner, invite, signalingUrl, ready) }
                rendezvousJobs[invite.credential] = job
                job.start()
                try {
                    ready.await()
                } catch (error: Throwable) {
                    job.cancel()
                    throw error
                }
            }
            invite
        }
    }

    suspend fun revoke(invite: WebRtcDirectInvite) = onOwner(currentDirectInviteIssuer()) {
        registry.revokeDirectInvite(currentDirectInviteIssuer(), invite.credential)
        rendezvousJobs.remove(invite.credential)?.cancel()
    }

    private suspend fun runRendezvousIssuer(
        owner: String, invite: WebRtcDirectInvite, signalingUrl: String, ready: CompletableDeferred<Unit>,
    ) {
        try {
            withTimeout((invite.expiresAtEpochMillis - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(1L)) {
                coroutineScope {
                    val channel = openDirectInviteRendezvous(signalingUrl, invite.credential, issuer = true)
                    try {
                        val plan = DeviceSessionWindowPlan.fromMemory()
                        val peer = DeviceSessionPeer(
                            DeviceSessionTransport(channel, plan, plan, isClient = false), plan,
                            role = DeviceSessionEndpointRole.Server,
                        )
                        val handler = controlHandler(owner, invite)
                        peer.setRequestHandler(handler::handle)
                        val connection = peer.start(this)
                        ready.complete(Unit)
                        connection.join()
                    } finally {
                        channel.close()
                    }
                }
            }
        } catch (error: CancellationException) {
            ready.completeExceptionally(error)
            throw error
        } catch (error: Exception) {
            ready.completeExceptionally(error)
        } finally {
            withContext(NonCancellable) {
                onOwner(null) {
                    rendezvousJobs.remove(invite.credential)
                    registry.revokeDirectInvite(owner, invite.credential)
                }
            }
        }
    }

    fun controlHandler(owner: String, invite: WebRtcDirectInvite? = null): DirectInviteControlHandler {
        return DirectInviteControlHandler(
            localDeviceId = { localDevice().id },
            localFingerprint = ::currentDeviceTlsFingerprint,
            rendezvousInvite = invite,
            issuerName = { localDevice().name },
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
                    buildWebRtcIceServers(claim.stunUrl, claim.turnUrl, claim.turnUsername, claim.turnPassword),
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

    suspend fun connect(
        code: String,
        signalingUrl: String = rendezvousUrl,
        confirmDevice: suspend (String) -> Unit = {},
    ) = coroutineScope {
        val credential = WebRtcShareCode.parseTemporaryDeviceId(code)
        val directInvite = if (credential == null) WebRtcShareCode.decodeDirectInvite(code) else null
        directInviteRuntimeError()?.let { error(it) }
        val owner = currentDirectInviteIssuer()
        val initiator = localDevice()
        var attempt: Attempt? = null
        var answer: DirectInviteAnswer? = null
        var sent = 0
        var received = 0
        var phase = WebRtcDirectFailure.Contact

        suspend fun prepare(invite: WebRtcDirectInvite, name: String): Attempt {
            require(invite.deviceId != initiator.id) { "cannot connect to your own direct invitation" }
            val available = (invite.expiresAtEpochMillis - Clock.System.now().toEpochMilliseconds())
                .coerceIn(1L, 300_000L)
            withTimeout(available) { confirmDevice(name) }
            return onOwner(owner) {
                Attempt(
                    SignalingDevice(id = invite.deviceId, name = name), newAttemptId(),
                    buildWebRtcIceServers(invite.stunUrl, invite.turnUrl, invite.turnUsername, invite.turnPassword), null,
                ).also { attempt = it }
            }
        }

        suspend fun DeviceSessionPeer.exchange(
            invite: WebRtcDirectInvite,
            challenge: DirectInviteChallenge,
            active: Attempt,
            retry: Boolean = false,
        ) {
            if (active.connected.isCompleted) return
            if (!retry) {
                phase = WebRtcDirectFailure.Handshake
                onOwner(owner) { active.start(initiator) }
            }
            val offer = active.description.await()
            val reply = submitDirectInviteOffer(invite, challenge, active.id, offer, initiator.id)
            if (answer == null) {
                answer = reply
                phase = WebRtcDirectFailure.Ice
                onOwner(owner) { active.receive("answer", reply.sdp, reply.authorization) }
            } else {
                check(answer == reply) { "direct invitation response changed" }
            }
            while (true) {
                val candidates = onOwner(owner) {
                    if (active.connected.isCompleted) return@onOwner null
                    check(attempts[active.id] === active) { "direct invitation connection closed" }
                    active.candidates.drop(sent).take(DIRECT_INVITE_MAX_ICE_BATCH)
                } ?: break
                val response = rpcValue<DirectInviteIceBatch, DirectInviteIceBatch>(
                    DIRECT_INVITE_ICE_RPC, DirectInviteIceBatch(received, candidates),
                )
                check(response.isValid() && response.offset == received + response.candidates.size) {
                    "invalid direct invitation candidate response"
                }
                sent += candidates.size
                received = response.offset
                onOwner(owner) { response.candidates.forEach(active::addPeerCandidate) }
                delay(100L)
            }
        }

        try {
            if (credential != null) {
                withTemporaryDeviceControl(credential, this, signalingUrl) { invite, name ->
                    val active = prepare(invite, name)
                    withTimeout(45_000L) { exchange(invite, requestInviteChallenge(invite), active) }
                }
            } else {
                val invite = checkNotNull(directInvite)
                val active = prepare(invite, invite.deviceId)
                withTimeout(45_000L) {
                    for (connection in 0..1) {
                        try {
                            withDirectInviteControl(invite, active.scope, signalingUrl) { challenge ->
                                exchange(invite, challenge, active, retry = connection != 0)
                            }
                            break
                        } catch (error: DeviceSessionClosedException) {
                            if (active.connected.isCompleted) break
                            if (invite.usesRendezvous || connection != 0 || !active.description.isCompleted) throw error
                        }
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
                attempt?.takeIf { !it.connected.isCompleted }?.let { active -> onOwner(null) { active.stop() } }
            }
        }
    }

    suspend fun disconnect(attemptId: String) = onOwner(null) { attempts[attemptId]?.stop() }

    fun peer(attemptId: String): SignalingDevice? = attempts[attemptId]?.peer

    fun clients(attemptId: String): DeviceSessionClients? = attempts[attemptId]?.let { attempt ->
        attempt.controller.deviceSessionClients(attempt.peer.id)
    }

    suspend fun stop() = onOwner(null) {
        rendezvousJobs.values.toList().forEach(Job::cancel)
        rendezvousJobs.clear()
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
        private val iceServers: List<WebRtcIceServerConfig>,
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
            controller.startDirect(peer, id, iceServers, claim, device) { message ->
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

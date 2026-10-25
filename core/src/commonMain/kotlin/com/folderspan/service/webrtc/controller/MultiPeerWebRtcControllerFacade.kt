package com.folderspan.service.webrtc.controller

import com.folderspan.runtime.RuntimeServiceFailures
import com.folderspan.PlatformType
import com.folderspan.createSettings
import com.folderspan.currentDeviceName
import com.folderspan.getSocketDevice
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.service.data.DeviceConnectAuthorizationMode
import com.folderspan.service.data.DeviceConnectRequest
import com.folderspan.service.data.DeviceSessionBootstrapAuthorization
import com.folderspan.service.data.DeviceThemeRequest
import com.folderspan.service.data.DeviceThemeResponse
import com.folderspan.service.message.DeviceMessageLiveEndpointRegistry
import com.folderspan.service.message.IncomingDeviceMessageCoordinator
import com.folderspan.service.message.newDeviceMessageId
import com.folderspan.service.session.DEVICE_SESSION_RPC_CONNECT
import com.folderspan.service.session.DEVICE_SESSION_RPC_THEME
import com.folderspan.service.session.DEVICE_SESSION_RPC_WEBRTC_ICE
import com.folderspan.service.session.DEVICE_SESSION_RPC_OK
import com.folderspan.service.session.DeviceSessionIceQuery
import com.folderspan.service.session.DeviceSessionConnectivityProbe
import com.folderspan.service.session.DeviceSessionClientAuthenticationContext
import com.folderspan.service.session.DeviceSessionClientMessagingContext
import com.folderspan.service.session.DeviceSessionClientRuntime
import com.folderspan.service.session.DeviceSessionClientRuntimeLauncher
import com.folderspan.service.session.DeviceSessionClients
import com.folderspan.service.session.DeviceSessionConnectResponse
import com.folderspan.service.session.createWebRtcDeviceSessionServerLauncher
import com.folderspan.service.webrtc.WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL
import com.folderspan.service.webrtc.WebRtcDeviceSessionByteChannel
import com.folderspan.service.webrtc.WebRtcDeviceSessionChannelOptions
import com.folderspan.service.webrtc.WebRtcRoomConnectionProbeClient
import com.folderspan.service.webrtc.controller.core.HttpSignalingOpenedRoomState
import com.folderspan.service.webrtc.controller.core.WebRtcConnectRequestEvent
import com.folderspan.service.webrtc.controller.core.WebRtcDeviceSessionPhase
import com.folderspan.service.webrtc.controller.core.WebRtcPeerSessionSummary
import com.folderspan.service.webrtc.controller.core.WebRtcRemoteNegotiation
import com.folderspan.service.webrtc.controller.core.WebRtcCandidateCapacityException
import com.folderspan.service.webrtc.controller.core.chooseTransferTargetPeerId
import com.folderspan.service.webrtc.controller.core.resolveHttpSignalingOpenedRoomState
import com.folderspan.service.webrtc.models.TransferProgress
import com.folderspan.service.webrtc.models.WebRtcConfig
import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import com.folderspan.service.webrtc.models.WebRtcDataChannel
import com.folderspan.service.webrtc.models.WebRtcDataChannelState
import com.folderspan.service.webrtc.models.WebRtcIceCandidate
import com.folderspan.service.webrtc.models.WebRtcIceServerConfig
import com.folderspan.service.webrtc.models.WebRtcIceSnapshot
import com.folderspan.service.webrtc.models.asRemoteObservation
import com.folderspan.service.webrtc.models.webRtcIceUfrags
import com.folderspan.service.webrtc.models.icePathFailureMessage
import com.folderspan.service.webrtc.models.WebRtcPeerConnection
import com.folderspan.service.webrtc.models.WebRtcPeerConnectionState
import com.folderspan.service.webrtc.models.WebRtcSdpType
import com.folderspan.service.webrtc.models.WebRtcSessionDescription
import com.folderspan.service.webrtc.models.buildWebRtcIceServerAttempts
import com.folderspan.service.webrtc.models.resolveWebRtcPeerSessionDisplayStatus
import com.folderspan.service.webrtc.models.toDebugSummary
import com.folderspan.service.webrtc.signaling.HttpWebRtcSignalingClient
import com.folderspan.service.webrtc.signaling.HttpWebRtcSignalingRole
import com.folderspan.service.webrtc.signaling.SignalingDevice
import com.folderspan.service.webrtc.signaling.SignalingIceCandidate
import com.folderspan.service.webrtc.signaling.SignalingMessage
import com.folderspan.service.webrtc.signaling.WebRtcSignalingClient
import com.folderspan.service.webrtc.signaling.asTargetDevice
import com.folderspan.service.webrtc.signaling.buildBrowserWebRtcConfig
import com.folderspan.service.webrtc.signaling.isValidRoomId
import com.folderspan.service.webrtc.signaling.toSignalingDevice
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import com.folderspan.utils.ProtoBufCodec
import com.folderspan.utils.SettingsUtils.KEY_DEVICE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import strings.AppStrings

/**
 * 仅负责 WebRTC 信令、PeerConnection 与公共 Device Session 的装配。
 * 文件、路径、书签、消息和 FSAR 均由认证后的 Device Session 客户端处理。
 */
class MultiPeerWebRtcController(
    private val scope: CoroutineScope,
    private val deviceMessageEndpointRegistry: DeviceMessageLiveEndpointRegistry? = null,
    private val incomingDeviceMessageCoordinator: IncomingDeviceMessageCoordinator? = null,
) {
    private inner class PeerSession(initialPeer: SignalingDevice) {
        var peer: SignalingDevice = initialPeer
        var status: WebRtcConnectionStatus = WebRtcConnectionStatus.Idle
        var isOfferer: Boolean = false
        var connectRequested: Boolean = false
        var manualDisconnect: Boolean = false
        var offerStarted: Boolean = false
        @Volatile var generation: Int = 0
        var connectionAttemptId: String = ""
        var bootstrapAuthorization: DeviceSessionBootstrapAuthorization? = null
        var isDeviceSessionClient: Boolean = false
        var isShareSession: Boolean = false
        var peerConnection: WebRtcPeerConnection? = null
        var peerConnectionState: WebRtcPeerConnectionState? = null
        var sessionChannel: WebRtcDataChannel? = null
        var sessionScope: CoroutineScope? = null
        var byteChannel: WebRtcDeviceSessionByteChannel? = null
        var clientRuntime: DeviceSessionClientRuntime? = null
        var serverJob: Job? = null
        var remoteNegotiation: WebRtcRemoteNegotiation? = null
        var connectionTimeout: Job? = null
        var authenticationTimeout: Job? = null
        var recoveryTimeout: Job? = null
        var negotiationJob: Job? = null
        var recoveryProbeJob: Job? = null
        var probeSession: DeviceSessionConnectivityProbe? = null
        var recovering: Boolean = false
        var recoveryNegotiationStarted: Boolean = false
        var recoveryDescriptionApplied: Boolean = false
        var localUfrags: Set<String> = emptySet()
        var remoteUfrags: Set<String> = emptySet()
        var pendingRemoteUfrags: Set<String> = emptySet()
        val retiredRemoteUfrags = ArrayDeque<String>()
        @Volatile var authenticated: Boolean = false
        @Volatile var negotiation: Int = 0
        var iceSampling: Job? = null
        @Volatile var localIce: WebRtcIceSnapshot? = null
        var remoteIce: WebRtcIceSnapshot? = null
        val peerConnectionCollectors = mutableListOf<Job>()
        val dataChannelCollectors = mutableListOf<Job>()
        val carrierFailures = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)

        val remoteId: String
            get() = peer.id

        fun hasOpenSessionChannel(): Boolean = sessionChannel?.state == WebRtcDataChannelState.Open

        fun isConnected(): Boolean = authenticated && status == WebRtcConnectionStatus.Connected && hasOpenSessionChannel()
    }

    private val peerSessions = mutableMapOf<String, PeerSession>()
    private val pendingOutboundApprovalIds = mutableSetOf<String>()
    private val pendingInboundApprovalIds = mutableSetOf<String>()
    private val approvedPeerIds = mutableSetOf<String>()
    private val serverLauncher = createWebRtcDeviceSessionServerLauncher()

    private var signalingClient: WebRtcRoomConnectionProbeClient? = null
    private var signalingMessagesJob: Job? = null
    private var signalingOpenedJob: Job? = null
    private var signalingFailureJob: Job? = null
    private var signalingClosedJob: Job? = null
    private var currentConfig: WebRtcConfig? = null
    private var currentLocalDeviceId: String = ""

    private val _connectionStatus = MutableStateFlow(WebRtcConnectionStatus.Idle)
    val connectionStatus: StateFlow<WebRtcConnectionStatus> = _connectionStatus.asStateFlow()

    private val _activeRoomConfig = MutableStateFlow<WebRtcConfig?>(null)
    val activeRoomConfig: StateFlow<WebRtcConfig?> = _activeRoomConfig.asStateFlow()

    private val _transfers = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val transfers: StateFlow<Map<String, TransferProgress>> = _transfers.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _availablePeers = MutableStateFlow<List<SignalingDevice>>(emptyList())
    private val _suggestedRemoteId = MutableStateFlow<String?>(null)
    private val _selectedPeerId = MutableStateFlow<String?>(null)
    private val _peerSessionStates = MutableStateFlow<List<WebRtcPeerSessionSummary>>(emptyList())
    val peerSessionStates: StateFlow<List<WebRtcPeerSessionSummary>> = _peerSessionStates.asStateFlow()

    private val _connectRequests = MutableSharedFlow<WebRtcConnectRequestEvent>(extraBufferCapacity = 16)
    val connectRequests = _connectRequests

    init {
        require(
            (deviceMessageEndpointRegistry == null) == (incomingDeviceMessageCoordinator == null)
        ) { "WebRTC message registry and coordinator must be configured together" }
    }

    fun connect(config: WebRtcConfig) {
        val localDeviceId = resolveLocalDeviceId()
        disconnectInternal(clearConfig = true)
        if (config.wssUrl.isBlank() || config.roomId.isBlank() || localDeviceId.isBlank()) {
            setConnectionError(AppStrings.ui_parameter_connection_incomplete)
            return
        }
        if (!isValidRoomId(config.roomId)) {
            setConnectionError(AppStrings.ui_room_id_invalid_base64_url_256_bit)
            return
        }
        currentConfig = config
        currentLocalDeviceId = localDeviceId
        _activeRoomConfig.value = config
        _connectionStatus.value = WebRtcConnectionStatus.Connecting
        connectWebSocketSignaling(config)
    }

    fun connectBrowserHttpSignalingHost(baseUrl: String) {
        connectHttpSignaling(
            baseUrl = baseUrl,
            role = HttpWebRtcSignalingRole.Host,
            incompleteMessage = AppStrings.ui_webrtc_connection_parameters_are_incomplete,
        )
    }

    fun connectBrowserHttpSignalingClient(baseUrl: String) {
        connectHttpSignaling(
            baseUrl = baseUrl,
            role = HttpWebRtcSignalingRole.Browser,
            incompleteMessage = AppStrings.ui_webrtc_server_address_cannot_be_empty,
        )
    }

    fun setConnectionError(message: String) {
        _lastError.value = message
        _connectionStatus.value = WebRtcConnectionStatus.Error
    }

    fun disconnect() {
        disconnectInternal(clearConfig = true)
    }

    suspend fun stop() {
        val signaling = signalingClient
        signalingClient = null
        val runtimes = peerSessions.values.mapNotNull { it.clientRuntime }
        val sessionJobs = peerSessions.values.mapNotNull { it.sessionScope?.coroutineContext?.get(Job) }
        peerSessions.values.forEach { it.clientRuntime = null }
        RuntimeServiceFailures.observe("webrtc-disconnect") { disconnectInternal(clearConfig = true) }
        RuntimeServiceFailures.observe("webrtc-signaling-stop") { signaling?.close() }
        runtimes.forEach { runtime -> RuntimeServiceFailures.observe("webrtc-session-stop") { runtime.close() } }
        sessionJobs.forEach { it.join() }
    }

    fun requestPeerConnection(
        remoteId: String,
        shareNonce: String = "",
        tlsFingerprintSha256: String = "",
    ) {
        val config = currentConfig ?: run {
            _lastError.value = AppStrings.webrtc_join_room_required
            return
        }
        if (remoteId.isBlank()) {
            _lastError.value = AppStrings.ui_remote_device_id_cannot_be_empty
            return
        }
        if (_availablePeers.value.none { it.id == remoteId }) {
            _lastError.value = AppStrings.ui_the_device_is_offline
            return
        }
        val session = ensureSession(remoteId)
        if (session.isConnected()) return
        closePeerConnection(session)
        session.connectionAttemptId = newDeviceMessageId()
        session.bootstrapAuthorization = null
        session.isDeviceSessionClient = true
        session.isShareSession = shareNonce.isNotBlank()
        session.manualDisconnect = false
        session.connectRequested = false
        session.status = WebRtcConnectionStatus.Connecting
        pendingOutboundApprovalIds += remoteId
        pendingInboundApprovalIds.remove(remoteId)
        approvedPeerIds.remove(remoteId)
        if (_selectedPeerId.value.isNullOrBlank()) _selectedPeerId.value = remoteId
        publishPeerSessionStates()
        scope.launch {
            sendSignaling(
                SignalingMessage(
                    type = "connect-request",
                    roomId = config.roomId,
                    from = resolveLocalDevice(config),
                    to = session.peer.asTargetDevice(),
                    connectionAttemptId = session.connectionAttemptId,
                    shareNonce = shareNonce.takeIf(String::isNotBlank),
                    tlsFingerprintSha256 = tlsFingerprintSha256.takeIf(String::isNotBlank),
                    ts = nowMs(),
                )
            )
        }
    }

    fun connectPeer(remoteId: String) {
        connectPeerInternal(remoteId)
    }

    fun disconnectPeer(remoteId: String) {
        pendingOutboundApprovalIds.remove(remoteId)
        pendingInboundApprovalIds.remove(remoteId)
        approvedPeerIds.remove(remoteId)
        val session = peerSessions.remove(remoteId) ?: return
        session.manualDisconnect = true
        session.connectRequested = false
        session.bootstrapAuthorization = null
        session.connectionAttemptId = ""
        closePeerConnection(session)
        publishPeerSessionStates()
    }

    fun approveConnectRequest(
        remoteId: String,
        authorization: DeviceSessionBootstrapAuthorization,
    ) {
        val config = currentConfig ?: return
        val session = ensureSession(remoteId)
        if (
            remoteId !in pendingInboundApprovalIds ||
            session.connectionAttemptId.isBlank() ||
            authorization.connectionAttemptId != session.connectionAttemptId
        ) {
            _lastError.value = AppStrings.ui_unauthorised_webrtc_connection_request
            return
        }
        session.isDeviceSessionClient = false
        pendingInboundApprovalIds.remove(remoteId)
        pendingOutboundApprovalIds.remove(remoteId)
        approvedPeerIds.add(remoteId)
        publishPeerSessionStates()
        scope.launch {
            sendSignaling(
                SignalingMessage(
                    type = "connect-approved",
                    roomId = config.roomId,
                    from = resolveLocalDevice(config),
                    to = session.peer.asTargetDevice(),
                    connectionAttemptId = session.connectionAttemptId,
                    bootstrapAuthorization = authorization,
                    ts = nowMs(),
                )
            )
        }
        if (!session.isConnected()) connectPeerInternal(remoteId)
    }

    fun rejectConnectRequest(
        remoteId: String,
        message: String = AppStrings.webrtc_connection_request_rejected,
    ) {
        val config = currentConfig ?: return
        val session = ensureSession(remoteId)
        pendingInboundApprovalIds.remove(remoteId)
        pendingOutboundApprovalIds.remove(remoteId)
        if (!session.isConnected()) approvedPeerIds.remove(remoteId)
        publishPeerSessionStates()
        scope.launch {
            sendSignaling(
                SignalingMessage(
                    type = "connect-rejected",
                    roomId = config.roomId,
                    from = resolveLocalDevice(config),
                    to = session.peer.asTargetDevice(),
                    connectionAttemptId = session.connectionAttemptId,
                    ts = nowMs(),
                    errorMessage = message,
                )
            )
        }
    }

    internal fun deviceSessionClients(remoteId: String): DeviceSessionClients? =
        peerSessions[remoteId]?.takeIf { it.isConnected() }?.clientRuntime?.clients

    internal fun isShareSession(remoteId: String): Boolean =
        peerSessions[remoteId]?.isShareSession == true

    suspend fun getPeerTheme(remoteId: String, isDark: Boolean): Result<DeviceThemeResponse> {
        val runtime = peerSessions[remoteId]?.takeIf { it.isConnected() }?.clientRuntime
            ?: return Result.failure(IllegalStateException(AppStrings.webrtc_target_device_not_connected))
        return runCatching {
            runtime.peer.rpcValue<DeviceThemeRequest, DeviceThemeResponse>(
                DEVICE_SESSION_RPC_THEME,
                DeviceThemeRequest(isDark),
            )
        }
    }

    private fun connectWebSocketSignaling(config: WebRtcConfig) {
        closeCurrentSignalingConnection()
        val signaling = WebRtcSignalingClient(config.wssUrl, config.headers)
        installSignalingCollectors(signaling, config, publishOpenedRoom = false)
        signaling.connect(scope)
        scope.launch {
            sendSignaling(
                SignalingMessage(
                    type = "join",
                    roomId = config.roomId,
                    from = resolveLocalDevice(config),
                    ts = nowMs(),
                )
            )
        }
    }

    private fun connectHttpSignaling(
        baseUrl: String,
        role: HttpWebRtcSignalingRole,
        incompleteMessage: String,
    ) {
        val normalizedBaseUrl = baseUrl.trim()
        val localDeviceId = resolveLocalDeviceId()
        disconnectInternal(clearConfig = true)
        if (normalizedBaseUrl.isBlank() || localDeviceId.isBlank()) {
            setConnectionError(incompleteMessage)
            return
        }
        val config = buildBrowserWebRtcConfig(normalizedBaseUrl)
        currentConfig = config
        currentLocalDeviceId = localDeviceId
        _connectionStatus.value = WebRtcConnectionStatus.Connecting
        closeCurrentSignalingConnection()
        val signaling = HttpWebRtcSignalingClient(config.wssUrl, role = role)
        installSignalingCollectors(signaling, config, publishOpenedRoom = true)
        signaling.connect(scope)
    }

    private fun installSignalingCollectors(
        signaling: WebRtcRoomConnectionProbeClient,
        config: WebRtcConfig,
        publishOpenedRoom: Boolean,
    ) {
        signalingClient = signaling
        signalingMessagesJob = signaling.messages.onEach(::handleSignal).launchIn(scope)
        signalingOpenedJob = signaling.openedEvents.onEach {
            if (signalingClient !== signaling || !publishOpenedRoom) return@onEach
            val state: HttpSignalingOpenedRoomState = resolveHttpSignalingOpenedRoomState(
                currentConfig = currentConfig,
                openedConfig = config,
            ) ?: return@onEach
            _activeRoomConfig.value = state.activeRoomConfig
            _connectionStatus.value = state.connectionStatus
        }.launchIn(scope)
        signalingFailureJob = signaling.failureEvents.onEach { error ->
            if (signalingClient !== signaling) return@onEach
            _lastError.value = error.message ?: AppStrings.ui_http_webrtc_signaling_connection_failed
            _connectionStatus.value = WebRtcConnectionStatus.Error
        }.launchIn(scope)
        signalingClosedJob = signaling.closedEvents.onEach {
            if (signalingClient !== signaling) return@onEach
            signalingClient = null
            if (currentConfig != null) {
                handleUnexpectedRoomDisconnect("WebRTC signaling disconnected")
            }
        }.launchIn(scope)
    }

    private fun closeCurrentSignalingConnection() {
        signalingClosedJob?.cancel()
        signalingClosedJob = null
        signalingOpenedJob?.cancel()
        signalingOpenedJob = null
        signalingFailureJob?.cancel()
        signalingFailureJob = null
        signalingMessagesJob?.cancel()
        signalingMessagesJob = null
        val client = signalingClient
        signalingClient = null
        if (client != null) scope.launch { client.close() }
    }

    private fun disconnectInternal(clearConfig: Boolean) {
        closeCurrentSignalingConnection()
        peerSessions.values.toList().forEach { session ->
            session.manualDisconnect = true
            session.connectRequested = false
            closePeerConnection(session)
        }
        peerSessions.clear()
        pendingOutboundApprovalIds.clear()
        pendingInboundApprovalIds.clear()
        approvedPeerIds.clear()
        _availablePeers.value = emptyList()
        _peerSessionStates.value = emptyList()
        _suggestedRemoteId.value = null
        _selectedPeerId.value = null
        _transfers.value = emptyMap()
        _lastError.value = null
        _connectionStatus.value = WebRtcConnectionStatus.Idle
        if (clearConfig) {
            currentConfig = null
            currentLocalDeviceId = ""
            _activeRoomConfig.value = null
        }
    }

    private fun handleUnexpectedRoomDisconnect(reason: String) {
        if (currentConfig == null) return
        LogKit.w("WebRTC room disconnected: $reason")
        disconnectInternal(clearConfig = false)
        _connectionStatus.value = WebRtcConnectionStatus.Disconnected
        _lastError.value = AppStrings.ui_webrtc_room_connection_is_closed_please_manually_reconnect
    }

    private fun handleUnexpectedPeerDisconnect(
        session: PeerSession,
        status: WebRtcConnectionStatus,
        reason: String,
    ) {
        if (session.manualDisconnect) return
        pendingOutboundApprovalIds.remove(session.remoteId)
        pendingInboundApprovalIds.remove(session.remoteId)
        approvedPeerIds.remove(session.remoteId)
        session.connectRequested = false
        session.bootstrapAuthorization = null
        session.connectionAttemptId = ""
        closePeerConnection(session)
        session.status = status
        _lastError.value = AppStrings.ui_the_connection_to_the_device_arg0_has_been_disconnected_please_manually.format(
            arg0 = session.peer.name?.takeIf(String::isNotBlank) ?: session.remoteId,
        )
        LogKit.w("WebRTC peer disconnected: remote=${session.remoteId} reason=$reason")
        publishPeerSessionStates()
    }

    private fun handleSignal(message: SignalingMessage) {
        val config = currentConfig ?: return
        message.from?.let { updatePeers(currentLocalDeviceId, listOf(it), appendOnly = true) }
        when (message.type) {
            "joined" -> {
                _connectionStatus.value = WebRtcConnectionStatus.Connected
                updatePeers(currentLocalDeviceId, message.peers.orEmpty())
            }

            "peer-joined" -> message.from?.let { updatePeers(currentLocalDeviceId, listOf(it), appendOnly = true) }

            "connect-request" -> {
                val remote = message.from ?: return
                val attemptId = message.connectionAttemptId?.takeIf(String::isNotBlank) ?: run {
                    _lastError.value = AppStrings.ui_unauthorised_webrtc_connection_request
                    return
                }
                val session = ensureSession(remote.id, remote)
                if (session.isConnected()) return
                closePeerConnection(session)
                session.connectionAttemptId = attemptId
                session.bootstrapAuthorization = null
                session.isDeviceSessionClient = false
                session.isShareSession = !message.shareNonce.isNullOrBlank()
                session.status = WebRtcConnectionStatus.Connecting
                pendingInboundApprovalIds += remote.id
                approvedPeerIds.remove(remote.id)
                publishPeerSessionStates()
                _connectRequests.tryEmit(
                    WebRtcConnectRequestEvent(
                        peer = remote,
                        roomId = message.roomId ?: config.roomId,
                        connectionAttemptId = attemptId,
                        shareNonce = message.shareNonce.orEmpty(),
                        tlsFingerprintSha256 = message.tlsFingerprintSha256.orEmpty(),
                    )
                )
            }

            "connect-approved" -> {
                val remote = message.from ?: return
                if (remote.id !in pendingOutboundApprovalIds) return
                val session = ensureSession(remote.id, remote)
                val authorization = message.bootstrapAuthorization
                if (
                    authorization == null ||
                    session.connectionAttemptId.isBlank() ||
                    authorization.connectionAttemptId != session.connectionAttemptId ||
                    message.connectionAttemptId != session.connectionAttemptId
                ) {
                    _lastError.value = AppStrings.ui_unauthorised_webrtc_connection_request
                    return
                }
                pendingOutboundApprovalIds.remove(remote.id)
                pendingInboundApprovalIds.remove(remote.id)
                approvedPeerIds.add(remote.id)
                session.bootstrapAuthorization = authorization
                session.isDeviceSessionClient = true
                publishPeerSessionStates()
                if (!session.isConnected()) connectPeerInternal(remote.id)
            }

            "connect-rejected" -> {
                val remote = message.from ?: return
                val session = ensureSession(remote.id, remote)
                if (message.connectionAttemptId != session.connectionAttemptId) return
                pendingOutboundApprovalIds.remove(remote.id)
                pendingInboundApprovalIds.remove(remote.id)
                approvedPeerIds.remove(remote.id)
                session.manualDisconnect = true
                session.connectRequested = false
                closePeerConnection(session)
                session.status = WebRtcConnectionStatus.Error
                _lastError.value = message.errorMessage ?: AppStrings.ui_remote_rejected_connection_request
                publishPeerSessionStates()
            }

            "offer" -> handleOffer(config, message)
            "answer" -> handleAnswer(message)
            "ice" -> handleIce(message)

            "peer-left" -> {
                val remoteId = message.from?.id ?: return
                updatePeers(currentLocalDeviceId, listOf(SignalingDevice(id = remoteId)), removeOnly = true)
                pendingOutboundApprovalIds.remove(remoteId)
                pendingInboundApprovalIds.remove(remoteId)
                approvedPeerIds.remove(remoteId)
                peerSessions.remove(remoteId)?.let { session ->
                    session.manualDisconnect = true
                    session.connectRequested = false
                    closePeerConnection(session)
                }
                publishPeerSessionStates()
            }

            "error" -> {
                _lastError.value = message.errorMessage ?: message.code ?: AppStrings.webrtc_signaling_error
                _connectionStatus.value = WebRtcConnectionStatus.Error
            }
        }
    }

    private fun handleOffer(config: WebRtcConfig, message: SignalingMessage) {
        val sdp = message.sdp ?: return
        val remote = message.from ?: return
        val session = ensureSession(remote.id, remote)
        if (
            remote.id !in approvedPeerIds ||
            session.connectionAttemptId.isBlank() ||
            message.connectionAttemptId != session.connectionAttemptId
        ) {
            _lastError.value = AppStrings.ui_unauthorised_webrtc_connection_request
            return
        }
        val ufrags = sdp.webRtcIceUfrags()
        if (session.rejectRemoteDescription(ufrags)) return
        if (session.authenticated) {
            if (session.isOfferer || ufrags.isEmpty() || (session.recovering && session.recoveryNegotiationStarted) ||
                !beginIceRecovery(session, notifyOfferer = false)
            ) return
            session.recoveryNegotiationStarted = true
            session.negotiation++
        } else {
            session.isOfferer = false
        }
        session.manualDisconnect = false
        session.connectRequested = true
        _suggestedRemoteId.value = remote.id
        if (_selectedPeerId.value.isNullOrBlank()) _selectedPeerId.value = remote.id
        if (session.peerConnection == null) setupPeerConnection(session)
        val peerConnection = session.peerConnection ?: return
        session.pendingRemoteUfrags = ufrags
        val generation = session.generation
        val attemptId = session.connectionAttemptId
        val negotiation = session.negotiation
        fun isCurrent() = session.isCurrent(peerConnection, generation) && session.negotiation == negotiation
        session.remoteNegotiation?.description(ufrags) {
            if (!isCurrent()) return@description
            runCatching {
                peerConnection.setRemoteDescription(WebRtcSessionDescription(WebRtcSdpType.Offer, sdp))
                if (!isCurrent()) return@description
                session.rememberRemoteDescription(ufrags)
                val answer = peerConnection.createAnswer()
                if (!isCurrent()) return@description
                session.localUfrags = answer.sdp.webRtcIceUfrags()
                peerConnection.setLocalDescription(answer)
                if (!isCurrent()) return@description
                sendSignaling(
                    SignalingMessage(
                        type = "answer",
                        roomId = config.roomId,
                        from = resolveLocalDevice(config),
                        to = session.peer.asTargetDevice(),
                        sdp = answer.sdp,
                        connectionAttemptId = attemptId,
                        ts = nowMs(),
                    )
                )
                if (isCurrent() && session.recovering) {
                    session.recoveryDescriptionApplied = true
                    verifyIceRecovery(session)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (isCurrent()) {
                    failPeerSession(session, error, AppStrings.ui_process_offer_failure)
                }
            }
        }
    }

    private fun handleAnswer(message: SignalingMessage) {
        val sdp = message.sdp ?: return
        val remote = message.from ?: return
        val session = ensureSession(remote.id, remote)
        if (
            remote.id !in approvedPeerIds ||
            session.connectionAttemptId.isBlank() ||
            message.connectionAttemptId != session.connectionAttemptId
        ) return
        if (!session.isOfferer) return
        val ufrags = sdp.webRtcIceUfrags()
        if (session.rejectRemoteDescription(ufrags)) return
        if (session.authenticated && (!session.recovering || !session.recoveryNegotiationStarted || ufrags.isEmpty())) return
        session.pendingRemoteUfrags = ufrags
        val peerConnection = session.peerConnection ?: return
        val generation = session.generation
        val negotiation = session.negotiation
        fun isCurrent() = session.isCurrent(peerConnection, generation) && session.negotiation == negotiation
        session.remoteNegotiation?.description(ufrags) {
            if (!isCurrent()) return@description
            runCatching {
                peerConnection.setRemoteDescription(
                    WebRtcSessionDescription(WebRtcSdpType.Answer, sdp)
                )
                if (!isCurrent()) return@description
                session.rememberRemoteDescription(ufrags)
                if (session.recovering) {
                    session.recoveryDescriptionApplied = true
                    verifyIceRecovery(session)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (isCurrent()) {
                    failPeerSession(session, error, AppStrings.ui_processing_answer_failed)
                }
            }
        }
    }

    private fun handleIce(message: SignalingMessage) {
        val remote = message.from ?: return
        val session = peerSessions[remote.id] ?: return
        if (
            remote.id !in approvedPeerIds ||
            session.connectionAttemptId.isBlank() ||
            message.connectionAttemptId != session.connectionAttemptId
        ) return
        if (message.code == "ice-restart" && message.candidate == null) {
            val requestedUfrags = message.sdp.orEmpty().split(' ').filter(String::isNotBlank).toSet()
            if (session.isOfferer && requestedUfrags.isNotEmpty() && requestedUfrags == session.remoteUfrags) {
                beginIceRecovery(session)
            }
            return
        }
        val candidate = message.candidate ?: return
        if (session.peerConnection == null) setupPeerConnection(session)
        session.remoteNegotiation?.candidate(
            WebRtcIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate),
        )
    }

    private fun connectPeerInternal(remoteId: String) {
        currentConfig ?: run {
            _lastError.value = AppStrings.webrtc_join_room_required
            return
        }
        val session = ensureSession(remoteId)
        if (remoteId !in approvedPeerIds || session.connectionAttemptId.isBlank()) {
            _lastError.value = AppStrings.ui_unauthorised_webrtc_connection_request
            return
        }
        session.manualDisconnect = false
        session.connectRequested = true
        session.isOfferer = currentLocalDeviceId < remoteId
        if (session.peerConnection == null) setupPeerConnection(session)
        publishPeerSessionStates()
        if (session.isOfferer && _availablePeers.value.any { it.id == remoteId }) startOfferIfNeeded(session)
    }

    private fun ensureSession(remoteId: String, incomingPeer: SignalingDevice? = null): PeerSession {
        val existing = peerSessions[remoteId]
        if (existing != null) {
            val peer = incomingPeer ?: _availablePeers.value.firstOrNull { it.id == remoteId }
            if (peer != null) existing.peer = mergeSignalingDevice(existing.peer, peer)
            publishPeerSessionStates()
            return existing
        }
        val peer = incomingPeer
            ?: _availablePeers.value.firstOrNull { it.id == remoteId }
            ?: SignalingDevice(id = remoteId)
        return PeerSession(peer).also { session ->
            session.isOfferer = currentLocalDeviceId < remoteId
            peerSessions[remoteId] = session
            publishPeerSessionStates()
        }
    }

    private fun setupPeerConnection(session: PeerSession) {
        closePeerConnection(session)
        val config = currentConfig ?: return
        val peerConnection = createPeerConnection(config.iceServers, session.remoteId).getOrElse { error ->
            failPeerSession(session, error, AppStrings.ui_connection_failed)
            return
        }
        val generation = session.generation
        val attempt = session.connectionAttemptId
        session.peerConnection = peerConnection
        session.peerConnectionState = WebRtcPeerConnectionState.New
        session.status = WebRtcConnectionStatus.Connecting
        session.offerStarted = false
        session.remoteNegotiation = WebRtcRemoteNegotiation(
            scope = scope,
            isCurrent = {
                session.isCurrent(peerConnection, generation) && session.remoteId in approvedPeerIds &&
                    attempt.isNotBlank() && session.connectionAttemptId == attempt
            },
            addCandidate = peerConnection::addIceCandidate,
            onFailure = { error -> failPeerSession(session, error,
                if (error is WebRtcCandidateCapacityException) AppStrings.webrtc_ice_candidate_limit_exceeded else AppStrings.ui_connection_failed) },
        )
        session.connectionTimeout = scope.launch {
            delay(30_000.milliseconds)
            if (session.isCurrent(peerConnection, generation) && !session.hasOpenSessionChannel()) {
                failPeerSession(session, IllegalStateException("WebRTC ICE/DataChannel connection timed out"),
                    config.iceServers.icePathFailureMessage(peerConnection.lastIceServerErrorCode))
            }
        }
        publishPeerSessionStates()

        session.peerConnectionCollectors += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peerConnection.onIceCandidate.collect { candidate ->
                if (!session.isCurrent(peerConnection, generation) || session.remoteId !in approvedPeerIds ||
                    session.connectionAttemptId != attempt || candidate.candidate.isBlank()
                ) return@collect
                val activeConfig = currentConfig ?: return@collect
                sendSignaling(
                    SignalingMessage(
                        type = "ice",
                        roomId = activeConfig.roomId,
                        from = resolveLocalDevice(activeConfig),
                        to = session.peer.asTargetDevice(),
                        candidate = SignalingIceCandidate(
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex,
                            candidate = candidate.candidate,
                        ),
                        connectionAttemptId = session.connectionAttemptId,
                        ts = nowMs(),
                    )
                )
            }
        }

        session.peerConnectionCollectors += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peerConnection.onDataChannel.collect { channel ->
                if (generation == session.generation) attachSessionChannel(session, channel, generation)
            }
        }

        session.peerConnectionCollectors += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peerConnection.onConnectionStateChange.collect { state ->
                if (generation == session.generation) handlePeerConnectionState(session, state)
            }
        }
    }

    private fun handlePeerConnectionState(session: PeerSession, state: WebRtcPeerConnectionState) {
        session.peerConnectionState = state
        when (state) {
            WebRtcPeerConnectionState.New, WebRtcPeerConnectionState.Connecting -> {
                if (!session.authenticated || session.recovering) session.status = WebRtcConnectionStatus.Connecting
                publishPeerSessionStates()
            }
            WebRtcPeerConnectionState.Connected -> {
                if (session.recovering) verifyIceRecovery(session)
                else if (!session.authenticated) session.status = WebRtcConnectionStatus.Connecting
                publishPeerSessionStates()
            }
            WebRtcPeerConnectionState.Disconnected -> if (!beginIceRecovery(session)) {
                session.carrierFailures.tryEmit(IllegalStateException("PeerConnection disconnected"))
                handleUnexpectedPeerDisconnect(session, WebRtcConnectionStatus.Disconnected, "PeerConnection disconnected")
            }
            WebRtcPeerConnectionState.Closed -> {
                session.carrierFailures.tryEmit(IllegalStateException("PeerConnection closed"))
                handleUnexpectedPeerDisconnect(session, WebRtcConnectionStatus.Disconnected, "PeerConnection closed")
            }
            WebRtcPeerConnectionState.Failed -> failPeerSession(session, IllegalStateException("PeerConnection failed"),
                currentConfig?.iceServers.orEmpty().icePathFailureMessage(session.peerConnection?.lastIceServerErrorCode))
        }
    }

    private fun PeerSession.rejectRemoteDescription(ufrags: Set<String>): Boolean = ufrags.isNotEmpty() &&
        (ufrags == remoteUfrags || pendingRemoteUfrags.isNotEmpty() || ufrags.any { it in retiredRemoteUfrags })

    private fun PeerSession.rememberRemoteDescription(ufrags: Set<String>) {
        retiredRemoteUfrags.addAll(remoteUfrags - ufrags)
        while (retiredRemoteUfrags.size > 16) retiredRemoteUfrags.removeFirst()
        remoteUfrags = ufrags
        pendingRemoteUfrags = emptySet()
    }

    private fun beginIceRecovery(session: PeerSession, notifyOfferer: Boolean = true): Boolean {
        val config = currentConfig ?: return false
        val connection = session.peerConnection ?: return false
        if (!session.authenticated || session.manualDisconnect || !session.hasOpenSessionChannel() ||
            session.serverJob?.isActive != true ||
            session.remoteId !in approvedPeerIds || session.connectionAttemptId.isBlank() ||
            signalingClient == null || _connectionStatus.value != WebRtcConnectionStatus.Connected ||
            session.peerConnectionState in listOf(WebRtcPeerConnectionState.Failed, WebRtcPeerConnectionState.Closed)
        ) return false
        if (session.recovering) return true
        session.recovering = true
        session.recoveryNegotiationStarted = false
        session.recoveryDescriptionApplied = false
        session.negotiationJob?.cancel()
        session.negotiationJob = null
        session.recoveryProbeJob?.cancel()
        session.recoveryProbeJob = null
        session.status = WebRtcConnectionStatus.Connecting
        session.iceSampling?.cancel()
        session.iceSampling = null
        session.localIce = null
        session.remoteIce = null
        val generation = session.generation
        val attempt = session.connectionAttemptId
        session.recoveryTimeout = scope.launch {
            delay(30_000L)
            if (session.isCurrent(connection, generation) && session.recovering) {
                failPeerSession(session, IllegalStateException("WebRTC ICE recovery timed out"), AppStrings.webrtc_ice_recovery_timeout)
            }
        }
        publishPeerSessionStates()
        if (session.isOfferer) {
            session.recoveryNegotiationStarted = true
            val negotiation = ++session.negotiation
            session.negotiationJob = scope.launch {
                fun isCurrent() = session.isCurrent(connection, generation) && session.negotiation == negotiation && session.recovering
                try {
                    val offer = connection.createOffer(iceRestart = true)
                    if (!isCurrent()) return@launch
                    session.localUfrags = offer.sdp.webRtcIceUfrags()
                    connection.setLocalDescription(offer)
                    if (!isCurrent()) return@launch
                    sendSignaling(SignalingMessage(type = "offer", roomId = config.roomId, from = resolveLocalDevice(config),
                        to = session.peer.asTargetDevice(), sdp = offer.sdp, connectionAttemptId = attempt, ts = nowMs()))
                } catch (error: CancellationException) { throw error }
                catch (error: Throwable) { if (isCurrent()) failPeerSession(session, error, AppStrings.ui_create_offer_failure) }
            }
        } else if (notifyOfferer) {
            session.negotiationJob = scope.launch {
                if (!session.isCurrent(connection, generation)) return@launch
                sendSignaling(SignalingMessage(type = "ice", code = "ice-restart", roomId = config.roomId,
                    from = resolveLocalDevice(config), to = session.peer.asTargetDevice(),
                    sdp = session.localUfrags.sorted().joinToString(" "), connectionAttemptId = attempt, ts = nowMs()))
            }
        }
        return true
    }

    private fun verifyIceRecovery(session: PeerSession) {
        if (!session.recovering || !session.recoveryDescriptionApplied ||
            session.peerConnectionState in listOf(WebRtcPeerConnectionState.Failed, WebRtcPeerConnectionState.Closed) ||
            !session.hasOpenSessionChannel() || session.recoveryProbeJob?.isActive == true
        ) return
        val probe = session.probeSession ?: return
        val connection = session.peerConnection ?: return
        val generation = session.generation
        val negotiation = session.negotiation
        session.recoveryProbeJob = scope.launch {
            val usable = try { probe() }
                catch (error: CancellationException) { throw error }
                catch (_: Throwable) { false }
            if (!session.isCurrent(connection, generation) || session.negotiation != negotiation || !session.recovering) return@launch
            if (!usable || !session.hasOpenSessionChannel()) {
                failPeerSession(session, IllegalStateException("Device Session recovery probe failed"), AppStrings.ui_connection_failed)
                return@launch
            }
            session.recovering = false
            session.recoveryTimeout?.cancel()
            session.recoveryTimeout = null
            session.status = WebRtcConnectionStatus.Connected
            startIceSampling(session)
            publishPeerSessionStates()
        }
    }

    private fun createPeerConnection(
        iceServers: List<WebRtcIceServerConfig>,
        remoteId: String,
    ): Result<WebRtcPeerConnection> {
        var lastError: Throwable? = null
        buildWebRtcIceServerAttempts(iceServers).forEach { attempt ->
            val result = runCatching { WebRtcPeerConnection(attempt.iceServers) }
            if (result.isSuccess) {
                if (attempt.label != "configured") {
                    LogKit.w(
                        "PeerConnection created with fallback: remote=$remoteId " +
                            "attempt=${attempt.label} ice=${attempt.iceServers.toDebugSummary()}"
                    )
                }
                return result
            }
            lastError = result.exceptionOrNull()
        }
        return Result.failure(lastError ?: IllegalStateException("Creating PeerConnection failed"))
    }

    private fun startOfferIfNeeded(session: PeerSession) {
        val config = currentConfig ?: return
        if (session.offerStarted || !session.isOfferer) return
        val peerConnection = session.peerConnection ?: return
        session.offerStarted = true
        val generation = session.generation
        val attemptId = session.connectionAttemptId
        val options = WebRtcDeviceSessionChannelOptions()
        val channel = peerConnection.createDataChannel(
            label = options.label,
            ordered = options.ordered,
            maxRetransmits = options.maxRetransmits,
        )
        if (!session.isCurrent(peerConnection, generation)) {
            runCatching { channel?.close() }
            return
        }
        if (channel == null) {
            session.offerStarted = false
            failPeerSession(session, IllegalStateException("Creating Device Session DataChannel failed"), AppStrings.ui_connection_failed)
            return
        }
        attachSessionChannel(session, channel, generation)
        session.negotiationJob = scope.launch {
            if (!session.isCurrent(peerConnection, generation)) return@launch
            runCatching {
                val offer = peerConnection.createOffer()
                if (!session.isCurrent(peerConnection, generation)) return@launch
                session.localUfrags = offer.sdp.webRtcIceUfrags()
                peerConnection.setLocalDescription(offer)
                if (!session.isCurrent(peerConnection, generation)) return@launch
                sendSignaling(
                    SignalingMessage(
                        type = "offer",
                        roomId = config.roomId,
                        from = resolveLocalDevice(config),
                        to = session.peer.asTargetDevice(),
                        sdp = offer.sdp,
                        connectionAttemptId = attemptId,
                        ts = nowMs(),
                    )
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (session.isCurrent(peerConnection, generation)) {
                    session.offerStarted = false
                    failPeerSession(session, error, AppStrings.ui_create_offer_failure)
                }
            }
        }
    }

    private fun PeerSession.isCurrent(connection: WebRtcPeerConnection, capturedGeneration: Int): Boolean =
        generation == capturedGeneration && peerConnection === connection

    private fun attachSessionChannel(
        session: PeerSession,
        channel: WebRtcDataChannel,
        generation: Int,
    ) {
        if (generation != session.generation) {
            runCatching { channel.close() }
            return
        }
        if (channel.label != WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL) {
            runCatching { channel.close() }
            failPeerSession(
                session,
                IllegalStateException("Unsupported Device Session channel: ${channel.label}"),
                AppStrings.ui_webrtc_session_protocol_incompatible,
            )
            return
        }
        if (session.sessionChannel != null && session.sessionChannel !== channel) {
            runCatching { channel.close() }
            failPeerSession(
                session,
                IllegalStateException("Duplicate Device Session channel"),
                AppStrings.ui_webrtc_session_protocol_incompatible,
            )
            return
        }
        session.sessionChannel = channel

        fun onOpen() {
            if (generation == session.generation) {
                session.connectionTimeout?.cancel()
                session.connectionTimeout = null
                startDeviceSession(session, channel, generation)
            }
        }
        // Register before sampling state: native OPEN can arrive before a queued collector starts.
        val closeCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            channel.onClose.collect {
                if (generation != session.generation || session.sessionChannel !== channel) return@collect
                session.carrierFailures.tryEmit(IllegalStateException("Device Session DataChannel closed"))
                session.sessionChannel = null
                closeDeviceSession(session)
                if (!session.manualDisconnect) {
                    handleUnexpectedPeerDisconnect(
                        session,
                        WebRtcConnectionStatus.Disconnected,
                        "Device Session DataChannel closed",
                    )
                }
            }
        }
        if (generation != session.generation || session.sessionChannel !== channel) {
            closeCollector.cancel()
            return
        }
        session.dataChannelCollectors += closeCollector
        val openCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            channel.onOpen.collect { onOpen() }
        }
        if (generation != session.generation || session.sessionChannel !== channel) {
            openCollector.cancel()
            return
        }
        session.dataChannelCollectors += openCollector
        if (channel.state == WebRtcDataChannelState.Open) scope.launch { onOpen() }
        publishPeerSessionStates()
    }

    private fun startDeviceSession(
        session: PeerSession,
        channel: WebRtcDataChannel,
        generation: Int,
    ) {
        if (generation != session.generation || session.byteChannel != null) return
        val sessionJob = SupervisorJob(scope.coroutineContext[Job])
        val sessionScope = CoroutineScope(
            scope.coroutineContext + sessionJob + Dispatchers.Default +
                CoroutineName("webrtc-device-session-${session.remoteId}"),
        )
        val byteChannel = WebRtcDeviceSessionByteChannel(
            dataChannel = channel,
            scope = sessionScope,
            carrierFailures = session.carrierFailures,
        )
        session.sessionScope = sessionScope
        session.byteChannel = byteChannel
        session.status = WebRtcConnectionStatus.Connecting
        publishPeerSessionStates()

        session.authenticationTimeout = scope.launch {
            delay(15_000.milliseconds)
            if (generation == session.generation && !session.authenticated) {
                failPeerSession(
                    session,
                    IllegalStateException("Device Session authentication timed out"),
                    AppStrings.ui_webrtc_session_authentication_timeout,
                )
            }
        }

        if (session.isDeviceSessionClient) {
            val authorization = session.bootstrapAuthorization
            session.bootstrapAuthorization = null
            session.serverJob = sessionScope.launch {
                try {
                    val bootstrap = checkNotNull(authorization) {
                        "missing WebRTC Device Session bootstrap authorization"
                    }
                    val messaging = deviceMessageEndpointRegistry?.let { registry ->
                        incomingDeviceMessageCoordinator?.let { coordinator ->
                            DeviceSessionClientMessagingContext(registry, coordinator)
                        }
                    }
                    val runtime = DeviceSessionClientRuntimeLauncher.start(
                        channel = byteChannel,
                        scope = sessionScope,
                        authentication = DeviceSessionClientAuthenticationContext(
                            peerDeviceId = session.remoteId,
                            authenticate = { peer ->
                                peer.rpcValue<DeviceConnectRequest, DeviceSessionConnectResponse>(
                                    DEVICE_SESSION_RPC_CONNECT,
                                    DeviceConnectRequest(
                                        device = getSocketDevice(),
                                        authorizationMode = DeviceConnectAuthorizationMode.STANDARD,
                                        bootstrapAuthorization = bootstrap,
                                    ),
                                )
                            },
                        ),
                        messaging = messaging,
                    )
                    val attached = try {
                        check(runtime.connectResponse.connection.connectType == DeviceConnectType.APPROVED) {
                            "WebRTC Device Session authorization rejected"
                        }
                        markSessionAuthenticated(session, generation, runtime)
                    } catch (error: Throwable) {
                        withContext(NonCancellable) {
                            if (error !is CancellationException) withContext(scope.coroutineContext.minusKey(Job)) {
                                if (generation == session.generation) failSessionProtocol(session, error)
                            }
                            runtime.close()
                        }
                        throw error
                    }
                    if (!attached) {
                        runtime.close()
                        return@launch
                    }
                    runtime.sessionJob.join()
                    withContext(scope.coroutineContext.minusKey(Job)) {
                        if (generation == session.generation && !session.manualDisconnect) {
                            handleUnexpectedPeerDisconnect(session, WebRtcConnectionStatus.Disconnected, "Device Session closed")
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    withContext(scope.coroutineContext.minusKey(Job)) {
                        if (generation == session.generation) failSessionProtocol(session, error)
                    }
                }
            }
        } else {
            val launcher = serverLauncher
            if (launcher == null) {
                failSessionProtocol(session, IllegalStateException("Device Session server is unavailable"))
                return
            }
            session.serverJob = sessionScope.launch {
                try {
                    launcher.run(
                        channel = byteChannel,
                        remoteDeviceId = session.remoteId,
                        connectionAttemptId = session.connectionAttemptId,
                        scope = sessionScope,
                        iceSnapshot = { session.localIce.takeIf { generation == session.generation && session.authenticated } },
                        onAuthenticated = { probe ->
                            markSessionAuthenticated(session, generation, probe = probe)
                        },
                    )
                    withContext(scope.coroutineContext.minusKey(Job)) {
                        if (generation == session.generation && !session.manualDisconnect) {
                            handleUnexpectedPeerDisconnect(session, WebRtcConnectionStatus.Disconnected, "Device Session closed")
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    withContext(scope.coroutineContext.minusKey(Job)) {
                        if (generation == session.generation) failSessionProtocol(session, error)
                    }
                }
            }
        }
    }

    private suspend fun markSessionAuthenticated(
        session: PeerSession,
        generation: Int,
        runtime: DeviceSessionClientRuntime? = null,
        probe: DeviceSessionConnectivityProbe? = null,
    ): Boolean = withContext(scope.coroutineContext.minusKey(Job)) {
        if (generation != session.generation) return@withContext false
        if (runtime != null) session.clientRuntime = runtime
        session.probeSession = probe ?: runtime?.let { active -> suspend {
            val nonce = newDeviceMessageId().encodeToByteArray()
            active.peer.connection.ping(nonce).contentEquals(nonce)
        } }
        session.authenticated = true
        session.authenticationTimeout?.cancel()
        session.authenticationTimeout = null
        session.status = WebRtcConnectionStatus.Connected
        startIceSampling(session)
        publishPeerSessionStates()
        true
    }

    private fun failSessionProtocol(session: PeerSession, error: Throwable) {
        failPeerSession(
            session,
            error,
            AppStrings.ui_webrtc_session_protocol_error_arg0.format(
                arg0 = error.message ?: AppStrings.ui_connection_failed,
            ),
        )
    }

    private fun startIceSampling(session: PeerSession) {
        session.iceSampling?.cancel()
        val connection = session.peerConnection ?: return
        val generation = session.generation
        val negotiation = session.negotiation
        val attempt = session.connectionAttemptId
        session.iceSampling = scope.launch {
            while (session.isCurrent(connection, generation) && session.negotiation == negotiation && session.authenticated && !session.recovering) {
                val path = try { withTimeoutOrNull(2_000L) { connection.getSelectedIcePath() } }
                    catch (error: CancellationException) { throw error }
                    catch (_: Throwable) { null }
                if (!session.isCurrent(connection, generation) || session.negotiation != negotiation || session.recovering) return@launch
                session.localIce = path?.let { WebRtcIceSnapshot(currentLocalDeviceId, session.remoteId, attempt, negotiation, it) }
                val runtime = session.clientRuntime
                val remote = if (runtime == null) null else try {
                    withTimeoutOrNull(2_000L) {
                        val response = runtime.peer.rpc(DEVICE_SESSION_RPC_WEBRTC_ICE, ProtoBufCodec.encode(DeviceSessionIceQuery(attempt, negotiation)))
                        if (response.status != DEVICE_SESSION_RPC_OK) null else ProtoBufCodec.decode<WebRtcIceSnapshot>(response.payload)
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Throwable) { null }
                if (!session.isCurrent(connection, generation) || session.negotiation != negotiation || session.recovering) return@launch
                session.remoteIce = remote?.asRemoteObservation(currentLocalDeviceId, session.remoteId, attempt, negotiation)
                publishPeerSessionStates()
                delay(2_000L)
            }
        }
    }

    private fun failPeerSession(session: PeerSession, error: Throwable, fallback: String) {
        pendingOutboundApprovalIds.remove(session.remoteId)
        pendingInboundApprovalIds.remove(session.remoteId)
        approvedPeerIds.remove(session.remoteId)
        session.connectRequested = false
        session.bootstrapAuthorization = null
        session.connectionAttemptId = ""
        // Invalidate collectors before closing so late carrier events cannot replace the error.
        closePeerConnection(session)
        session.status = WebRtcConnectionStatus.Error
        _lastError.value = fallback
        LogKit.w("WebRTC peer session failed: remote=${session.remoteId} cause=${error::class.simpleName}")
        publishPeerSessionStates()
    }

    private fun closeDeviceSession(session: PeerSession) {
        val runtime = session.clientRuntime
        session.clientRuntime = null
        session.serverJob?.cancel()
        session.serverJob = null
        session.byteChannel?.close()
        session.byteChannel = null
        session.sessionScope?.cancel()
        session.sessionScope = null
        if (runtime != null) scope.launch { runtime.close() }
    }

    private fun closePeerConnection(session: PeerSession) {
        session.generation += 1
        session.authenticationTimeout?.cancel()
        session.authenticationTimeout = null
        session.recoveryTimeout?.cancel()
        session.recoveryTimeout = null
        session.negotiationJob?.cancel()
        session.negotiationJob = null
        session.recoveryProbeJob?.cancel()
        session.recoveryProbeJob = null
        session.probeSession = null
        session.recovering = false
        session.recoveryNegotiationStarted = false
        session.recoveryDescriptionApplied = false
        session.localUfrags = emptySet()
        session.remoteUfrags = emptySet()
        session.pendingRemoteUfrags = emptySet()
        session.retiredRemoteUfrags.clear()
        session.authenticated = false
        session.negotiation = 0
        session.iceSampling?.cancel()
        session.iceSampling = null
        session.localIce = null
        session.remoteIce = null
        session.remoteNegotiation?.close()
        session.remoteNegotiation = null
        session.connectionTimeout?.cancel()
        session.connectionTimeout = null
        session.dataChannelCollectors.forEach(Job::cancel)
        session.dataChannelCollectors.clear()
        session.peerConnectionCollectors.forEach(Job::cancel)
        session.peerConnectionCollectors.clear()
        closeDeviceSession(session)
        val channel = session.sessionChannel
        val peerConnection = session.peerConnection
        session.sessionChannel = null
        session.peerConnection = null
        session.peerConnectionState = null
        session.offerStarted = false
        runCatching { channel?.close() }
        runCatching { peerConnection?.close() }
    }

    private suspend fun sendSignaling(message: SignalingMessage) {
        if (
            message.type in directedSignalingTypes &&
            currentConfig?.headers?.isNotEmpty() == true &&
            message.to?.userUuid.isNullOrBlank()
        ) {
            LogKit.w("Official WebRTC signaling skipped: type=${message.type} to=${message.to?.id} missing userUuid")
            _lastError.value = AppStrings.ui_cannot_connect_webrtc_device_rejoin_room
            return
        }
        runCatching {
            val client = signalingClient ?: error(AppStrings.webrtc_signaling_not_connected)
            client.send(message)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            LogKit.w("Sending WebRTC signal failed: type=${message.type} to=${message.to?.id}", error)
            if (currentConfig?.roomId == message.roomId) {
                handleUnexpectedRoomDisconnect("Send ${message.type} failed")
            }
        }
    }

    private fun publishPeerSessionStates() {
        val peersById = linkedMapOf<String, SignalingDevice>()
        _availablePeers.value.forEach { peer -> peersById[peer.id] = peer }
        peerSessions.values.forEach { session ->
            peersById[session.remoteId] = mergeSignalingDevice(peersById[session.remoteId], session.peer)
        }
        val preferred = _suggestedRemoteId.value
        val normalizedTarget = chooseTransferTargetPeerId(
            selectedPeerId = _selectedPeerId.value,
            preferredPeerId = preferred,
            connectedPeerIds = peerSessions.values.filter { it.isConnected() }.mapTo(mutableSetOf()) { it.remoteId },
            availablePeerIds = peersById.keys,
        )
        _selectedPeerId.value = normalizedTarget
        _peerSessionStates.value = peersById.values
            .sortedWith(compareBy<SignalingDevice> { it.name ?: it.id }.thenBy { it.id })
            .map { peer ->
                val session = peerSessions[peer.id]
                val awaitingApproval = peer.id in pendingOutboundApprovalIds
                WebRtcPeerSessionSummary(
                    peer = peer,
                    status = session?.status ?: WebRtcConnectionStatus.Idle,
                    displayStatus = resolveWebRtcPeerSessionDisplayStatus(
                        status = session?.status ?: WebRtcConnectionStatus.Idle,
                        isAwaitingApproval = awaitingApproval,
                        peerConnectionState = session?.peerConnectionState,
                        hasPrimaryChannel = session?.hasOpenSessionChannel() == true,
                    ),
                    isOfferer = session?.isOfferer ?: (currentLocalDeviceId < peer.id),
                    isSelectedTarget = normalizedTarget == peer.id,
                    isPreferredPeer = preferred == peer.id,
                    isAwaitingApproval = awaitingApproval,
                    isShareSession = session?.isShareSession == true,
                    sessionPhase = resolveDeviceSessionPhase(session, awaitingApproval),
                    localIce = session?.localIce,
                    remoteIce = session?.remoteIce,
                )
            }
    }

    private fun resolveDeviceSessionPhase(
        session: PeerSession?,
        awaitingApproval: Boolean,
    ): WebRtcDeviceSessionPhase = when {
        session?.status == WebRtcConnectionStatus.Error -> WebRtcDeviceSessionPhase.Failed
        session?.recovering == true -> WebRtcDeviceSessionPhase.PeerConnection
        session?.status == WebRtcConnectionStatus.Connected -> WebRtcDeviceSessionPhase.Ready
        awaitingApproval || session == null || session.connectionAttemptId.isBlank() ->
            WebRtcDeviceSessionPhase.Signaling
        session.peerConnection == null || session.peerConnectionState != WebRtcPeerConnectionState.Connected ->
            WebRtcDeviceSessionPhase.PeerConnection
        !session.hasOpenSessionChannel() -> WebRtcDeviceSessionPhase.DataChannel
        else -> WebRtcDeviceSessionPhase.SessionAuthentication
    }

    private fun updatePeers(
        localId: String,
        peers: List<SignalingDevice>,
        appendOnly: Boolean = false,
        removeOnly: Boolean = false,
    ) {
        val filtered = peers.filter { it.id.isNotBlank() && it.id != localId }
        val current = _availablePeers.value.toMutableList()
        when {
            removeOnly -> filtered.forEach { peer -> current.removeAll { it.id == peer.id } }
            appendOnly -> filtered.forEach { peer ->
                val index = current.indexOfFirst { it.id == peer.id }
                if (index >= 0) current[index] = mergeSignalingDevice(current[index], peer) else current += peer
            }
            else -> {
                current.clear()
                filtered.groupBy(SignalingDevice::id).values.forEach { duplicates ->
                    current += duplicates.drop(1).fold(duplicates.first(), ::mergeSignalingDevice)
                }
            }
        }
        _availablePeers.value = current
        current.forEach { peer ->
            peerSessions[peer.id]?.let { session -> session.peer = mergeSignalingDevice(session.peer, peer) }
        }
        if (_suggestedRemoteId.value.isNullOrBlank()) _suggestedRemoteId.value = current.firstOrNull()?.id
        publishPeerSessionStates()
    }

    private fun mergeSignalingDevice(current: SignalingDevice?, incoming: SignalingDevice): SignalingDevice {
        if (current == null) return incoming
        return SignalingDevice(
            id = incoming.id.ifBlank { current.id },
            name = incoming.name?.takeIf(String::isNotBlank) ?: current.name,
            pathSeparator = incoming.pathSeparator?.takeIf(String::isNotBlank) ?: current.pathSeparator,
            host = incoming.host?.takeIf(String::isNotBlank) ?: current.host,
            port = incoming.port ?: current.port,
            type = incoming.type?.takeIf(String::isNotBlank) ?: current.type,
            connectType = incoming.connectType?.takeIf(String::isNotBlank) ?: current.connectType,
            userUuid = incoming.userUuid?.trim()?.takeIf(String::isNotBlank) ?: current.userUuid,
        )
    }

    private fun resolveLocalDeviceId(): String =
        runCatching { createSettings().getString(KEY_DEVICE_ID, "") }.getOrDefault("")

    private fun resolveLocalDevice(config: WebRtcConfig): SignalingDevice {
        val localDeviceId = resolveLocalDeviceId()
        return runCatching { getSocketDevice() }.getOrNull()?.toSignalingDevice(overriddenId = localDeviceId)
            ?: SignalingDevice(
                id = localDeviceId,
                name = runCatching { currentDeviceName() }.getOrNull()?.ifBlank { null } ?: PlatformType.name,
                pathSeparator = PathUtils.getPathSeparator(),
                type = PlatformType.name,
            )
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        val directedSignalingTypes = setOf(
            "connect-request",
            "connect-approved",
            "connect-rejected",
            "offer",
            "answer",
            "ice",
        )
    }
}

package com.folderspan.ui.state.main

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.folderspan.PlatformType
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.ShareHistoryInput
import com.folderspan.data.file.ShareHistoryStore
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.webrtc.WebRtcRoomProfile
import com.folderspan.data.main.webrtc.WebRtcOfficialGateway
import com.folderspan.data.main.webrtc.toWebRtcConfigResult
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.extensions.getSubnetIps
import com.folderspan.extensions.randomString
import com.folderspan.getSocketDevice
import com.folderspan.localization.LocalizedMessage
import com.folderspan.localization.LocalizedMessageKeys
import com.folderspan.notification.*
import com.folderspan.service.account.AccountDeviceAutomation
import com.folderspan.service.account.AccountDeviceLanDecision
import com.folderspan.service.account.AccountDeviceNonceReplayCache
import com.folderspan.service.account.AccountDeviceProofContext
import com.folderspan.service.account.AccountDeviceProofVerification
import com.folderspan.service.account.AccountDeviceTrustRefreshTrigger
import com.folderspan.service.account.AccountDeviceTrustRegistry
import com.folderspan.service.account.ACCOUNT_DEVICE_CHALLENGE_HEADER
import com.folderspan.service.account.ACCOUNT_DEVICE_DISCOVERY_PURPOSE
import com.folderspan.service.account.accountDeviceProofFromHeaders
import com.folderspan.service.account.accountDeviceSha256
import com.folderspan.service.account.newAccountDeviceNonce
import com.folderspan.service.account.resolveAccountDeviceLanDecision
import com.folderspan.service.account.verifyAccountDeviceProof
import com.folderspan.service.bookmark.DeviceBookmarkService
import com.folderspan.service.data.*
import com.folderspan.service.data.ConnectType.Fail
import com.folderspan.service.file.DeviceFileCopyService
import com.folderspan.service.file.DeviceFileService
import com.folderspan.service.http.client.*
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.CONNECT_TIMEOUT
import com.folderspan.service.http.server.SocketClientIPEnum
import com.folderspan.service.http.server.getAllIPAddresses
import com.folderspan.service.http.server.getLocalIpv4Set
import com.folderspan.service.http.server.selectAdvertisedHttpHost
import com.folderspan.service.http.tls.DEVICE_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE
import com.folderspan.service.http.tls.containsMessage
import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import com.folderspan.service.message.DeviceMessageLiveEndpointRegistry
import com.folderspan.service.message.DeviceConversationSummary
import com.folderspan.service.message.DeviceMessageEndpointIdentity
import com.folderspan.service.message.DeviceMessageService
import com.folderspan.service.message.DeviceStoredMessage
import com.folderspan.service.message.DeviceMessageTransport
import com.folderspan.service.message.IncomingDeviceMessageCoordinator
import com.folderspan.service.message.SqlDelightDeviceMessageStore
import com.folderspan.service.path.DevicePathService
import com.folderspan.service.session.DeviceLanBeaconListener
import com.folderspan.service.session.DeviceLanBeacons
import com.folderspan.service.session.DeviceSessionClientManager
import com.folderspan.service.session.DeviceSessionBootstrapAuthorizationRegistry
import com.folderspan.service.session.ReadOnlyShareSessionAdapter
import com.folderspan.service.session.identifyDeviceSessionEndpoint
import com.folderspan.service.session.resolveLanBeaconLocalHosts
import com.folderspan.service.session.shouldAcceptLanBeacon
import com.folderspan.service.session.usesTriggeredDeviceDiscovery
import com.folderspan.service.webrtc.controller.MultiPeerWebRtcController
import com.folderspan.service.webrtc.controller.core.WebRtcConnectRequestEvent
import com.folderspan.service.webrtc.controller.core.WebRtcPeerSessionSummary
import com.folderspan.service.webrtc.download.WebRtcBrowserDownloadRegistry
import com.folderspan.service.webrtc.models.WebRtcConfig
import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import com.folderspan.service.webrtc.signaling.HttpWebRtcDiscoveryClient
import com.folderspan.service.webrtc.signaling.SignalingDevice
import com.folderspan.service.webrtc.signaling.buildBrowserWebRtcDeviceBaseUrl
import com.folderspan.service.webrtc.signaling.canEnableBrowserWebRtcSignaling
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.ui.state.device.DeviceSharePathGrant
import com.folderspan.ui.state.device.DeviceSharePathScope
import com.folderspan.ui.state.device.DeviceTokenFingerprint
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.FileShareStatus
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.usesWebRtcFileTransport
import com.folderspan.ui.theme.getDefaultColorScheme
import com.folderspan.ui.theme.toSerializable
import com.folderspan.utils.*
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import strings.AppStrings
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

private const val ACCOUNT_AUTO_CONNECT_RETRY_MILLIS = 5_000L
private const val DISCOVERY_REQUEST_TIMEOUT_MILLIS = 3_000L
private const val DISCOVERY_CONNECT_TIMEOUT_MILLIS = 1_000L

data class HttpDeviceConnectionFailure(
    val device: SocketDevice,
    val error: Throwable,
)

private data class PendingWebRtcConnectRequest(
    val peer: SignalingDevice,
    val controller: MultiPeerWebRtcController,
    val connectionAttemptId: String,
)

internal enum class DeviceAddressConnectMode {
    SessionPort,
    BrowserWebRtc,
}

internal fun resolveDeviceAddressConnectMode(platformType: DeviceType): DeviceAddressConnectMode {
    return if (platformType == DeviceType.JS) {
        DeviceAddressConnectMode.BrowserWebRtc
    } else {
        DeviceAddressConnectMode.SessionPort
    }
}

internal suspend fun resolveManualSessionDevice(
    host: String,
    port: Int,
    knownDevices: List<SocketDevice>,
    identify: suspend (host: String, port: Int) -> SocketDevice,
): SocketDevice {
    val normalizedHost = host.trim()
    val knownDevice = knownDevices.firstOrNull { device ->
        device.transportType == DeviceTransportType.Session &&
            device.id.isNotBlank() &&
            device.normalizedTlsFingerprint().isNotBlank() &&
            device.host.trim().equals(normalizedHost, ignoreCase = true) &&
            device.httpsPortOrFallback() == port
    }
    return knownDevice?.withCopy(
        host = normalizedHost,
        httpsPort = port,
    ) ?: identify(normalizedHost, port)
}

internal fun canUseBrowserWebRtcSignalingBaseUrl(
    baseUrl: String,
    platformType: DeviceType,
    browserSecureContext: Boolean,
): Boolean {
    return canEnableBrowserWebRtcSignaling(
        baseUrl = baseUrl,
        browserSecureContext = platformType != DeviceType.JS || browserSecureContext,
    )
}

internal fun isDiscoveryScanSourceAddress(address: String): Boolean {
    val octets = address.split(".").map { item -> item.toIntOrNull() }
    return !(octets.size != 4 || octets.any { item -> item == null || item !in 0..255 }) && (octets[0] != 198 || octets[1] !in 18..19)
    // RFC 2544 benchmarking addresses are commonly used by TUN interfaces and are not LAN peers.
}

internal fun resolveDeviceMessageEndpointSelection(
    selected: DeviceMessageEndpointIdentity?,
    preferred: Pair<String, DeviceMessageTransport>?,
    liveEndpoints: Set<DeviceMessageEndpointIdentity>,
): DeviceMessageEndpointIdentity? {
    if (selected != null && selected in liveEndpoints) return selected
    val (peerDeviceId, transport) = preferred ?: return null
    return liveEndpoints.singleOrNull { identity ->
        identity.peerDeviceId == peerDeviceId && identity.transport == transport
    }
}

class DeviceState : KoinComponent, AccountDeviceAutomation {
    private val database by inject<FolderSpanDatabase>()
    private val shareHistoryStore by inject<ShareHistoryStore>()
    private val deviceCertificateState by inject<DeviceCertificateState>()
    private val fileShareState by inject<FileShareState>()
    private val notificationState by inject<NotificationState>()
    private val taskState by inject<TaskState>()
    private val mainState by inject<MainState>()
    private val accountDeviceTrustRegistry by inject<AccountDeviceTrustRegistry>()
    private val accountDeviceTrustRefreshTrigger by inject<AccountDeviceTrustRefreshTrigger>()
    private val accountDeviceNonceReplayCache by inject<AccountDeviceNonceReplayCache>()
    private val mainScope = MainScope()
    internal val deviceMessageStore by lazy { SqlDelightDeviceMessageStore(database) }
    internal val deviceMessageEndpointRegistry = DeviceMessageLiveEndpointRegistry()
    private val _deviceMessageConversations = MutableStateFlow<List<DeviceConversationSummary>>(emptyList())
    val deviceMessageConversations: StateFlow<List<DeviceConversationSummary>> = _deviceMessageConversations
    private val _deviceMessageRevision = MutableStateFlow(0L)
    val deviceMessageRevision: StateFlow<Long> = _deviceMessageRevision
    val deviceMessageLiveEndpoints: StateFlow<Set<DeviceMessageEndpointIdentity>> =
        deviceMessageEndpointRegistry.liveEndpoints
    private val _selectedDeviceMessageEndpoint = MutableStateFlow<DeviceMessageEndpointIdentity?>(null)
    val selectedDeviceMessageEndpoint: StateFlow<DeviceMessageEndpointIdentity?> =
        _selectedDeviceMessageEndpoint
    private var selectedDeviceMessageTransport: Pair<String, DeviceMessageTransport>? = null
    private val _openDeviceMessageConversationId = MutableStateFlow<String?>(null)
    internal val incomingDeviceMessageCoordinator by lazy {
        IncomingDeviceMessageCoordinator(
            store = deviceMessageStore,
            endpointRegistry = deviceMessageEndpointRegistry,
            isConversationOpen = { peerDeviceId ->
                _openDeviceMessageConversationId.value == peerDeviceId
            },
            onIncomingPersisted = {
                mainScope.launch { refreshDeviceMessageConversations() }
            },
        )
    }
    internal val deviceMessageService by lazy {
        DeviceMessageService(
            store = deviceMessageStore,
            endpointRegistry = deviceMessageEndpointRegistry,
            onOutgoingChanged = {
                mainScope.launch { refreshDeviceMessageConversations() }
            },
        )
    }

    suspend fun refreshDeviceMessageConversations() {
        _deviceMessageConversations.value = deviceMessageStore.conversations()
        _deviceMessageRevision.value += 1L
    }

    suspend fun loadDeviceMessagePage(
        peerDeviceId: String,
        limit: Long = 50L,
        offset: Long = 0L,
    ): List<DeviceStoredMessage> = deviceMessageStore.page(peerDeviceId, limit, offset)

    suspend fun loadOlderDeviceMessages(
        peerDeviceId: String,
        beforeSentAtMillis: Long,
        beforeLocalId: Long,
        limit: Long = 50L,
    ): List<DeviceStoredMessage> = deviceMessageStore.pageBefore(
        peerDeviceId = peerDeviceId,
        beforeSentAtMillis = beforeSentAtMillis,
        beforeLocalId = beforeLocalId,
        limit = limit,
    )

    fun availableDeviceMessageEndpoints(peerDeviceId: String): List<DeviceMessageEndpointIdentity> =
        deviceMessageLiveEndpoints.value
            .filter { identity -> identity.peerDeviceId == peerDeviceId }
            .sortedWith(compareBy<DeviceMessageEndpointIdentity> { it.transport.name }.thenBy { it.connectionId })

    fun resolveDeviceMessageEndpoint(device: SocketDevice): DeviceMessageEndpointIdentity? {
        return deviceMessageLiveEndpoints.value.singleOrNull { identity ->
            identity.peerDeviceId == device.id && identity.transport == DeviceMessageTransport.Session
        }
    }

    fun selectDeviceMessageEndpoint(identity: DeviceMessageEndpointIdentity?): Boolean {
        if (identity != null && identity !in deviceMessageLiveEndpoints.value) return false
        _selectedDeviceMessageEndpoint.value = identity
        selectedDeviceMessageTransport = identity?.let { it.peerDeviceId to it.transport }
        return true
    }

    fun selectDeviceMessageEndpoint(device: SocketDevice): DeviceMessageEndpointIdentity? =
        resolveDeviceMessageEndpoint(device)?.also { identity ->
            _selectedDeviceMessageEndpoint.value = identity
            selectedDeviceMessageTransport = identity.peerDeviceId to identity.transport
        }

    fun openDeviceMessageConversation(
        peerDeviceId: String,
        endpointIdentity: DeviceMessageEndpointIdentity?,
    ) {
        _openDeviceMessageConversationId.value = peerDeviceId
        _selectedDeviceMessageEndpoint.value = endpointIdentity?.takeIf { identity ->
            identity.peerDeviceId == peerDeviceId && identity in deviceMessageLiveEndpoints.value
        }
        selectedDeviceMessageTransport = _selectedDeviceMessageEndpoint.value?.let { identity ->
            identity.peerDeviceId to identity.transport
        }
        mainScope.launch {
            deviceMessageStore.markConversationRead(peerDeviceId)
            refreshDeviceMessageConversations()
        }
    }

    fun closeDeviceMessageConversation(peerDeviceId: String) {
        if (_openDeviceMessageConversationId.value == peerDeviceId) {
            _openDeviceMessageConversationId.value = null
            _selectedDeviceMessageEndpoint.value = null
            selectedDeviceMessageTransport = null
        }
    }

    suspend fun sendDeviceMessage(
        endpointIdentity: DeviceMessageEndpointIdentity,
        body: String,
    ): Result<DeviceStoredMessage> {
        val result = deviceMessageService.send(endpointIdentity, body)
        refreshDeviceMessageConversations()
        return result
    }

    suspend fun retryDeviceMessage(
        endpointIdentity: DeviceMessageEndpointIdentity,
        messageId: String,
    ): Result<DeviceStoredMessage> {
        val result = deviceMessageService.retry(endpointIdentity, messageId)
        refreshDeviceMessageConversations()
        return result
    }

    suspend fun deleteDeviceMessageConversation(peerDeviceId: String) {
        deviceMessageStore.deleteConversation(peerDeviceId)
        if (_openDeviceMessageConversationId.value == peerDeviceId) {
            _openDeviceMessageConversationId.value = null
            _selectedDeviceMessageEndpoint.value = null
            selectedDeviceMessageTransport = null
        }
        refreshDeviceMessageConversations()
    }
    private val notificationConfig = NotificationFactoryConfig(
        showInBell = true,
        showInBanner = true,
        sendSystemNotification = true
    )
    private val connectionTimeoutJobs = mutableMapOf<String, Job>()
    private val shareTimeoutJobs = mutableMapOf<String, Job>()
    private val accountAuthorizedDeviceIds = mutableSetOf<String>()
    private val accountAutoConnectMutex = Mutex()
    private val accountAutoConnectInFlight = mutableSetOf<String>()
    private val accountAutoConnectRetryAfter = mutableMapOf<String, Long>()
    private val discoveryClientConfig: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = DISCOVERY_REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = DISCOVERY_CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = DISCOVERY_REQUEST_TIMEOUT_MILLIS
        }
    }
    private val discoveryPingPool = DiscoveryCapturingClientPool(config = discoveryClientConfig)
    private val client = createDiscoveryNoProxyHttpClient(LeafCertificateCapture(), discoveryClientConfig)
    private val httpWebRtcDiscoveryClient = HttpWebRtcDiscoveryClient(client)
    private val webRtcController = MultiPeerWebRtcController(
        scope = mainScope,
        deviceMessageEndpointRegistry = deviceMessageEndpointRegistry,
        incomingDeviceMessageCoordinator = incomingDeviceMessageCoordinator,
    )
    private val browserWebRtcController = MultiPeerWebRtcController(
        scope = mainScope,
        deviceMessageEndpointRegistry = deviceMessageEndpointRegistry,
        incomingDeviceMessageCoordinator = incomingDeviceMessageCoordinator,
    )

    private val _isDeviceAdd: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isDeviceAdd: StateFlow<Boolean> = _isDeviceAdd
    fun updateDeviceAdd(value: Boolean) {
        _isDeviceAdd.value = value
    }

    private val _pendingConnectNewDevice: MutableStateFlow<SocketDevice?> = MutableStateFlow(null)
    val pendingConnectNewDevice: StateFlow<SocketDevice?> = _pendingConnectNewDevice

    private val _httpDeviceConnectionFailure: MutableStateFlow<HttpDeviceConnectionFailure?> =
        MutableStateFlow(null)
    val httpDeviceConnectionFailure: StateFlow<HttpDeviceConnectionFailure?> =
        _httpDeviceConnectionFailure

    fun consumeHttpDeviceConnectionFailure() {
        _httpDeviceConnectionFailure.value = null
    }

    fun trustHttpDeviceConnectionFailureNewCertificateAndConnect() {
        val failure = _httpDeviceConnectionFailure.value ?: return
        if (!failure.error.containsMessage(DEVICE_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE)) return
        val device = failure.device
        if (device.normalizedTlsFingerprint().isBlank()) return

        _httpDeviceConnectionFailure.value = null
        updateSocketDeviceConnectType(device, ConnectType.Loading)
        mainScope.launch {
            try {
                connectHttpDevice(device, allowChangedTrustedCertificate = true)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                LogKit.e(AppStrings.ui_device_connection_failed_arg0.format(arg0 = (device.id)), error)
                updateSocketDeviceConnectType(device, Fail)
            }
        }
    }

    fun requestConnectNewDevice(device: SocketDevice) {
        _pendingConnectNewDevice.value = device
    }

    fun consumeConnectNewDevice() {
        _pendingConnectNewDevice.value = null
    }

    val devices = mutableStateListOf<Device>()

    val socketDevices = mutableStateListOf<SocketDevice>()

    val webRtcConnectionStatus: StateFlow<WebRtcConnectionStatus> = webRtcController.connectionStatus
    val webRtcTransfers = webRtcController.transfers
    val webRtcLastError = webRtcController.lastError
    val webRtcRoomConfig = webRtcController.activeRoomConfig
    val webRtcPeerSessionStates = webRtcController.peerSessionStates
    val browserWebRtcConnectionStatus: StateFlow<WebRtcConnectionStatus> = browserWebRtcController.connectionStatus
    val browserWebRtcPeerSessionStates = browserWebRtcController.peerSessionStates

    // 存储已连接的远程设备ID和连接时间的映射，key为设备ID，value为连接时间戳
    val remoteDeviceConnections = mutableStateMapOf<String, Long>()
    private val remoteDeviceLastSeenMutex = Mutex()
    private val remoteDeviceLastSeen = mutableMapOf<String, Long>()

    private val remoteDeviceConnectionMonitor = RemoteDeviceConnectionMonitor(
        scope = mainScope,
        getConnections = { remoteDeviceLastSeenMutex.withLock { remoteDeviceLastSeen.toMap() } },
        onStale = { candidateIds ->
            val staleIds = remoteDeviceLastSeenMutex.withLock {
                findStaleRemoteDeviceConnectionIds(
                    connections = remoteDeviceLastSeen
                        .filterKeys { deviceId -> deviceId in candidateIds },
                    nowMs = Clock.System.now().toEpochMilliseconds(),
                ).also { confirmedIds ->
                    confirmedIds.forEach(remoteDeviceLastSeen::remove)
                }
            }
            if (staleIds.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    staleIds.forEach { deviceId ->
                        remoteDeviceConnections.remove(deviceId)
                        deviceCertificateState.removeDeviceToken(deviceId)
                    }
                }
            }
        },
    )

    suspend fun registerRemoteDeviceConnection(
        deviceId: String,
        token: String,
        roleId: Long,
        fingerprint: DeviceTokenFingerprint,
    ) {
        val connectedAt = Clock.System.now().toEpochMilliseconds()
        remoteDeviceLastSeenMutex.withLock {
            remoteDeviceLastSeen[deviceId] = connectedAt
        }
        withContext(Dispatchers.Main) {
            deviceCertificateState.setDeviceTokenAndPermission(deviceId, token, roleId, fingerprint)
            remoteDeviceConnections[deviceId] = connectedAt
        }
    }

    suspend fun markRemoteDeviceConnected(deviceId: String) {
        val connectedAt = Clock.System.now().toEpochMilliseconds()
        remoteDeviceLastSeenMutex.withLock {
            remoteDeviceLastSeen[deviceId] = connectedAt
        }
    }

    private val _loadingDevices: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val loadingDevices: StateFlow<Boolean> = _loadingDevices
    fun updateLoadingDevices(value: Boolean) {
        _loadingDevices.value = value
    }
    private val _scannerPaused: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private var scannerJob: Job? = null
    private val lanBeaconListener = DeviceLanBeaconListener(mainScope, ::handleIncomingLanBeacon)

    fun pauseScanner() {
        if (!PlatformType.usesTriggeredDeviceDiscovery()) return
        _scannerPaused.value = true
        scannerJob?.cancel()
        updateLoadingDevices(false)
    }

    // Map<设备id, Pair<设备链接类型, 结束倒计时>>
    val connectionRequest = mutableStateMapOf<String, Pair<DeviceConnectType, Long>>()
    val connectionRequestsMissingNotification = mutableStateMapOf<String, Long>()
    private val pendingWebRtcConnectRequests = mutableMapOf<String, PendingWebRtcConnectRequest>()
    internal val deviceSessionBootstrapAuthorizationRegistry =
        DeviceSessionBootstrapAuthorizationRegistry()
    private val webRtcIssuedBootstrapAuthorizations = mutableMapOf<String, String>()

    fun updateConnectionRequest(
        deviceId: String,
        connectionType: DeviceConnectType,
        requestedAt: Long,
        deviceName: String? = null
    ) {
        connectionRequest[deviceId] = Pair(connectionType, requestedAt)
        if (connectionType == DeviceConnectType.WAITING) {
            scheduleConnectionTimeout(deviceId, requestedAt)
            val notificationResult = postConnectionRequestNotification(deviceId, deviceName, requestedAt)
            if (notificationResult.needsFallbackHandling()) {
                connectionRequestsMissingNotification[deviceId] = requestedAt
                LogKit.w(
                    AppStrings.ui_connection_request_does_not_establish_visible_notification_remains_waiting.format(arg0 = deviceId) +
                        "inAppStored=${notificationResult.inAppStored}, " +
                        "inAppVisible=${notificationResult.inAppVisible}, " +
                        "systemRequested=${notificationResult.systemNotificationRequested}, " +
                        "systemSuppressed=${notificationResult.systemNotificationSuppressed}, " +
                        "systemPosted=${notificationResult.systemNotificationPosted}"
                )
            } else {
                connectionRequestsMissingNotification.remove(deviceId)
            }
        } else {
            clearConnectionTimeout(deviceId)
            clearConnectionRequestNotification(deviceId)
            connectionRequestsMissingNotification.remove(deviceId)
            pendingWebRtcConnectRequests.remove(deviceId)?.let { request ->
                val peer = request.peer
                when (connectionType) {
                    DeviceConnectType.APPROVED,
                    DeviceConnectType.AUTO_CONNECT -> {
                        mainScope.launch {
                            val authorization = issueWebRtcBootstrapAuthorization(
                                deviceId = peer.id,
                                connectionAttemptId = request.connectionAttemptId,
                            )
                            if (authorization == null) {
                                LogKit.w(AppStrings.ui_webrtc_connection_approval_failed_failed_generate_access_token_device.format(arg0 = peer.id))
                                updateConnectionRequest(
                                    deviceId = peer.id,
                                    connectionType = DeviceConnectType.REJECTED,
                                    requestedAt = Clock.System.now().toEpochMilliseconds(),
                                    deviceName = peer.name
                                )
                                return@launch
                            }
                            request.controller.approveConnectRequest(peer.id, authorization)
                        }
                    }

                    DeviceConnectType.REJECTED,
                    DeviceConnectType.PERMANENTLY_BANNED -> {
                        mainScope.launch {
                            request.controller.rejectConnectRequest(peer.id)
                        }
                    }
                }
            }
        }
    }

    fun removeConnectionRequest(deviceId: String) {
        connectionRequest.remove(deviceId)
        clearConnectionTimeout(deviceId)
        clearConnectionRequestNotification(deviceId)
        connectionRequestsMissingNotification.remove(deviceId)
    }

    // 管理每台设备的心跳任务
    private val heartbeatJobs = mutableMapOf<String, Job>()

    private val presenceMonitor = DevicePresenceMonitor(
        scope = mainScope,
        client = client,
        getProbeTargets = {
            withContext(Dispatchers.Main) {
                sessionPresenceProbeTargets(socketDevices)
            }
        },
        onStale = ::handleStaleHttpDevices,
        getNetworkSignature = {
            getAllIPAddresses(SocketClientIPEnum.IPV4_UP).toSet()
        },
        onNetworkChanged = ::handleLocalNetworkChanged,
    ).also { item -> item.start() }

    private suspend fun handleStaleHttpDevices(staleIds: Set<String>) {
        removeSessionSocketDevices(candidateIds = staleIds)
    }

    private suspend fun handleLocalNetworkChanged() {
        val removedIds = removeSessionSocketDevices(onlyInactive = true)
        removedIds.forEach { deviceId -> presenceMonitor.forget(deviceId) }
    }

    private suspend fun removeSessionSocketDevices(
        candidateIds: Set<String>? = null,
        onlyInactive: Boolean = false,
    ): Set<String> {
        val fileState = inject<FileState>().value
        return withContext(Dispatchers.Main) {
            val staleDevices = socketDevices.filter { item ->
                item.transportType == DeviceTransportType.Session &&
                    (candidateIds == null || item.id in candidateIds) &&
                    item.sessionClient == null &&
                    (!onlyInactive || !item.hasActiveConnection())
            }
            staleDevices.forEach { item ->
                item.httpClient?.disconnect(notifyCancel = false)
            }
            val removableIds = staleDevices.map { item -> item.id }.toSet()
            socketDevices.removeAll { item ->
                item.transportType == DeviceTransportType.Session && item.id in removableIds
            }
            if (_pendingConnectNewDevice.value?.id in removableIds) {
                _pendingConnectNewDevice.value = null
            }
            removableIds.forEach { deviceId ->
                heartbeatJobs.remove(deviceId)?.cancel()
                replaceDisconnectedHttpDeviceWithWebRtc(deviceId)
                syncCurrentDeskAfterHttpDisconnect(deviceId, fileState)
            }
            removableIds
        }
    }

    suspend fun markHttpDeviceSeen(deviceId: String) {
        presenceMonitor.markSeen(deviceId)
    }

    suspend fun snapshotSocketDevices(
        transportType: DeviceTransportType? = null,
    ): List<SocketDevice> = withContext(Dispatchers.Main) {
        socketDevices
            .filter { item -> transportType == null || item.transportType == transportType }
            .map { item -> item.withCopy() }
    }

    suspend fun snapshotConnectionRequests(): Map<String, Pair<DeviceConnectType, Long>> =
        withContext(Dispatchers.Main) { connectionRequest.toMap() }

    suspend fun connectDiscoveredDevice(
        deviceId: String,
        transportType: DeviceTransportType,
    ): SocketDevice {
        val device = withContext(Dispatchers.Main) {
            socketDevices.firstOrNull { item ->
                item.id == deviceId && item.transportType == transportType
            }?.withCopy()
        } ?: throw NoSuchElementException("online device was not found")
        connect(device)
        return snapshotSocketDevices(transportType)
            .firstOrNull { item -> item.id == deviceId }
            ?: device
    }

    init {
        mainScope.launch {
            deviceMessageStore.recoverInterruptedSends()
            refreshDeviceMessageConversations()
        }
        deviceMessageLiveEndpoints
            .onEach { endpoints ->
                _selectedDeviceMessageEndpoint.value = resolveDeviceMessageEndpointSelection(
                    selected = _selectedDeviceMessageEndpoint.value,
                    preferred = selectedDeviceMessageTransport,
                    liveEndpoints = endpoints,
                )
            }
            .launchIn(mainScope)
        remoteDeviceConnectionMonitor.start()
        if (!PlatformType.usesTriggeredDeviceDiscovery()) {
            lanBeaconListener.start()
        }
        webRtcPeerSessionStates
            .onEach { peerStates ->
                syncWebRtcSocketDevices(peerStates, webRtcController)
            }
            .launchIn(mainScope)
        webRtcController.connectRequests
            .onEach { event ->
                handleIncomingWebRtcConnectRequest(event, webRtcController)
            }
            .launchIn(mainScope)
        browserWebRtcPeerSessionStates
            .onEach { peerStates ->
                syncWebRtcSocketDevices(peerStates, browserWebRtcController)
            }
            .launchIn(mainScope)
        browserWebRtcController.connectRequests
            .onEach { event ->
                handleIncomingWebRtcConnectRequest(event, browserWebRtcController)
            }
            .launchIn(mainScope)
    }

    suspend fun scanner(address: List<String>, port: Int, resumeIfPaused: Boolean = false) {
        if (!PlatformType.usesTriggeredDeviceDiscovery()) return
        if (resumeIfPaused) {
            _scannerPaused.value = false
        }
        if (_scannerPaused.value || scannerJob?.isActive == true) return

        val currentJob = currentCoroutineContext().job
        scannerJob = currentJob
        updateLoadingDevices(true)
        try {
            val path = "${PathUtils.getCachePath()}${PathUtils.getPathSeparator()}scanner_server_cache"
            val readFile = withContext(Dispatchers.Default) {
                FileUtils.readFile(FileAccessPermission.Allowed, path)
            }
            val cachedIps = readFile.getOrNull()
                ?.decodeToString()
                ?.split(",")
                ?.map { item -> item.trim() }
                ?.filter { item -> item.isNotEmpty() && isDiscoveryScanSourceAddress(item) }
                .orEmpty()
            currentCoroutineContext().ensureActive()
            // 扫描结果收集到本地集合，避免跨线程修改共享可变集合
            val scannedIps = mutableListOf<String>()
            val (localDeviceId, localIpSet) = withContext(Dispatchers.Default) {
                val device = runCatching { getSocketDevice() }.getOrNull()
                val ipSet = getLocalIpv4Set(address + listOfNotNull(device?.host))
                Pair(device?.id.orEmpty(), ipSet)
            }
            currentCoroutineContext().ensureActive()
            val ipAddresses = mutableSetOf<String>().apply {
                addAll(cachedIps)
            }
            ipAddresses.removeAll(localIpSet)

            for (chunk in ipAddresses.chunked(64)) {
                currentCoroutineContext().ensureActive()
                val results = coroutineScope {
                    chunk.map { ip ->
                        async(Dispatchers.Default) {
                            if (ip in localIpSet) {
                                // 双重兜底：绝不扫描或 Ping 本机 IP。
                                return@async null
                            }
                            if (discoverBrowserWebRtcDevice(ip, port) != null) ip else null
                        }
                    }.awaitAll()
                }
                scannedIps.addAll(results.filterNotNull())
            }

            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Main) {
                val removedBrowserDeviceIds = removeUnavailableBrowserWebRtcDevices(
                    socketDevices = socketDevices,
                    probedHosts = ipAddresses,
                    reachableHosts = scannedIps.toSet(),
                )
                if (_pendingConnectNewDevice.value?.id in removedBrowserDeviceIds) {
                    _pendingConnectNewDevice.value = null
                }
                devices.removeAll { item ->
                    item.id in removedBrowserDeviceIds &&
                        socketDevices.none { socket -> socket.id == item.id && socket.hasActiveConnection() }
                }
                if (localDeviceId.isNotBlank()) {
                    socketDevices.removeAll { item -> item.id == localDeviceId }
                    devices.removeAll { item -> item.id == localDeviceId }
                }
            }

            val toByteArray = scannedIps.joinToString(",").toByteArray()
            FileUtils.writeBytes(
                permission = FileAccessPermission.Allowed,
                path = path,
                fileSize = toByteArray.size.toLong(),
                data = toByteArray,
                offset = 0,
            )
        } finally {
            if (scannerJob == currentJob) {
                scannerJob = null
                updateLoadingDevices(false)
            }
        }
    }

    private suspend fun discoverBrowserWebRtcDevice(ip: String, port: Int): SocketDevice? {
        val baseUrl = buildBrowserWebRtcDeviceBaseUrl(ip, port)
        if (!canEnableBrowserWebRtc(baseUrl)) {
            return null
        }
        val hostResult = try {
            httpWebRtcDiscoveryClient.discoverHost(baseUrl)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LogKit.e(AppStrings.ui_webrtc_host_discovery_failed_arg0.format(arg0 = (baseUrl)), error)
            null
        }
        val host = hostResult?.getOrNull() ?: return null

        val device = host.toWebRtcSocketDevice(ConnectType.UnConnect).withCopy(
            host = ip,
            port = port,
            httpsPort = port,
            transportType = DeviceTransportType.WebRtc,
            httpClient = null,
        )
        withContext(Dispatchers.Main) {
            upsertDiscoveredSocketDevice(
                socketDevices = socketDevices,
                device = device,
                browserWebRtcTransport = true,
            )
            if (
                browserWebRtcConnectionStatus.value == WebRtcConnectionStatus.Idle ||
                browserWebRtcConnectionStatus.value == WebRtcConnectionStatus.Disconnected ||
                browserWebRtcConnectionStatus.value == WebRtcConnectionStatus.Error
            ) {
                browserWebRtcController.connectBrowserHttpSignalingClient(baseUrl)
            }
        }
        return device
    }

    fun connectWebRtcRoom(config: WebRtcConfig) {
        webRtcController.connect(config)
    }

    fun connectWebRtcRoom(room: WebRtcRoomProfile) {
        val config = room.toWebRtcConfigResult().getOrElse { error ->
            webRtcController.setConnectionError(error.message ?: AppStrings.ui_webrtc_room_connection_parameters_not_available)
            return
        }
        connectWebRtcRoom(config)
    }

    fun disconnectWebRtcRoom() {
        pendingWebRtcConnectRequests.clear()
        webRtcController.disconnect()
    }

    fun connectWebRtcPeer(remoteId: String) {
        webRtcController.requestPeerConnection(remoteId)
    }

    fun disconnectWebRtcPeer(remoteId: String) {
        webRtcController.disconnectPeer(remoteId)
    }

    suspend fun getWebRtcDeviceTheme(deviceId: String, isDark: Boolean): Result<DeviceThemeResponse> {
        return resolveWebRtcControllerForPeer(deviceId).getPeerTheme(deviceId, isDark)
    }

    suspend fun getRemoteDeviceTheme(device: Device, isDark: Boolean): Result<DeviceThemeResponse>? {
        device.host.values.firstOrNull()?.deviceRouteClient?.getDeviceTheme(isDark)?.let { return it }
        val sessionClient = withContext(Dispatchers.Main) {
            socketDevices.firstOrNull { item ->
                item.id == device.id && item.sessionClient != null
            }?.sessionClient
        }
        sessionClient?.deviceRouteClient?.getDeviceTheme(isDark)?.let { return it }
        if (device.pathClient != null || device.bookmarkClient != null || device.fileClient != null) {
            return getWebRtcDeviceTheme(device.id, isDark)
        }
        return null
    }

    fun updateSocketDeviceConnectType(device: SocketDevice, connectType: ConnectType) {
        val shouldKeepHttpDevice =
            device.transportType == DeviceTransportType.WebRtc &&
                socketDevices.any { item ->
                    item.id == device.id &&
                        item.transportType == DeviceTransportType.Session &&
                        item.httpClient != null || item.sessionClient != null
                }
        if (!shouldKeepHttpDevice) {
            devices.removeAll { item -> item.id == device.id }
        }
        val updatedDevices = socketDevices.map { item ->
            if (!item.matchesRecord(device)) return@map item
            val keepHttpClient =
                item.transportType == DeviceTransportType.Session &&
                    (connectType == ConnectType.Connect || connectType == ConnectType.Loading)
            item.withCopy(
                connectType = connectType,
                httpClient = if (keepHttpClient) item.httpClient else null,
                sessionClient = if (keepHttpClient) item.sessionClient else null,
            )
        }
        socketDevices.clear()
        socketDevices.addAll(updatedDevices)
    }

    fun disconnectSocketDevice(device: SocketDevice): Boolean {
        return when (device.transportType) {
            DeviceTransportType.Session -> {
                val disconnected = device.sessionClient?.disconnect() == true ||
                    device.httpClient?.disconnect() == true
                if (disconnected || device.connectType == ConnectType.Loading) {
                    updateSocketDeviceConnectType(device, ConnectType.UnConnect)
                }
                disconnected || device.connectType == ConnectType.Loading
            }

            DeviceTransportType.WebRtc -> {
                disconnectWebRtcPeer(device.id)
                revokeWebRtcBootstrapAuthorization(device.id)
                updateSocketDeviceConnectType(device, ConnectType.UnConnect)
                true
            }
        }
    }

    suspend fun connectDeviceByAddress(ip: String, port: Int): SocketDevice? {
        return when (resolveDeviceAddressConnectMode(PlatformType)) {
            DeviceAddressConnectMode.BrowserWebRtc -> {
                val device = discoverBrowserWebRtcDevice(ip, port) ?: return null
                connectBrowserWebRtcDevice(device)
                device
            }

            DeviceAddressConnectMode.SessionPort -> {
                LogKit.i(AppStrings.ui_manual_ip_session_connection_host_arg0_port_arg1.format(arg0 = (ip), arg1 = (port).toString()))
                val socketDevice = resolveManualSessionDevice(
                    host = ip,
                    port = port,
                    knownDevices = snapshotSocketDevices(DeviceTransportType.Session),
                    identify = ::identifyDeviceSessionEndpoint,
                )
                withContext(Dispatchers.Main) {
                    upsertDiscoveredSocketDevice(
                        socketDevices = socketDevices,
                        device = socketDevice,
                        browserWebRtcTransport = false,
                    )
                }
                connect(socketDevice)
                socketDevice
            }
        }
    }

    private suspend fun handleIncomingLanBeacon(host: String, payload: ByteArray) {
        val self = runCatching { getSocketDevice() }.getOrNull()
        val localDeviceId = self?.id.orEmpty()
        val localIpSet = resolveLanBeaconLocalHosts(
            detectedLocalHosts = getLocalIpv4Set(),
            localDeviceHost = self?.host,
        )
        val beacon = DeviceLanBeacons.decode(payload) ?: return
        if (!DeviceLanBeacons.verify(beacon)) {
            LogKit.w(AppStrings.ui_ignore_beacon_signature_invalid_host_arg0_id_arg1.format(arg0 = (host), arg1 = (beacon.deviceId)))
            return
        }
        if (!shouldAcceptLanBeacon(host, beacon.deviceId, localDeviceId, localIpSet)) {
            return
        }
        ingestDiscoveredLanDevice(DeviceLanBeacons.toSocketDevice(beacon, host), localIpSet)
    }

    private suspend fun ingestDiscoveredLanDevice(device: SocketDevice, localIpSet: Set<String>) {
        if (device.id.isBlank()) return
        if (device.host in localIpSet) {
            return
        }
        presenceMonitor.markSeen(device.id)
        val deviceData = database.deviceQueries.queryById(device.id).executeAsOneOrNullAwait()
        if (deviceData == null) {
            database.deviceQueries.insert(
                id = device.id,
                name = device.name,
                host = device.host,
                port = device.httpsPort.toLong(),
                type = device.type
            ).awaitDatabaseReady()
            SyncSnapshotChangeNotifier.onDeviceConfigurationChanged()
        } else if ((deviceData.name != device.name && deviceData.hasRemarks == false) || deviceData.type != device.type) {
            database.deviceQueries.updateNameAndTypeById(device.name, device.type, device.id)
                .awaitDatabaseReady()
            SyncSnapshotChangeNotifier.onDeviceConfigurationChanged()
        }
        val savedPolicy = database.deviceConnectQueries
            .queryByIdAndCategory(device.id, DeviceCategory.CLIENT)
            .executeAsOneOrNullAwait()
        applyDiscoveredConnectType(device, savedPolicy?.connectionType)
        val nowEpochMillis = Clock.System.now().toEpochMilliseconds()
        val accountFeatureEnabled = SettingsUtils.fileShare.isAccountDeviceAutoConnectEnabled()
        val snapshot = accountDeviceTrustRegistry.state.value
        val accountDecision = resolveAccountDeviceLanDecision(
            featureEnabled = accountFeatureEnabled,
            snapshot = snapshot,
            nowEpochMillis = nowEpochMillis,
            deviceKey = device.id,
            proofVerification = AccountDeviceProofVerification.Missing,
            permanentlyRejected = savedPolicy?.connectionType == DeviceConnectType.PERMANENTLY_BANNED,
            connectedOrConnecting = isConnectedOrConnecting(device.id),
        )
        val shouldAutoConnect = savedPolicy?.connectionType == DeviceConnectType.AUTO_CONNECT ||
            accountDecision == AccountDeviceLanDecision.AutoConnect
        if (shouldAutoConnect && beginAccountAutoConnect(device.id, nowEpochMillis)) {
            LogKit.i(AppStrings.ui_device_auto_connected_id_arg0_policy_arg1_account_arg2.format(arg0 = (device.id), arg1 = (savedPolicy?.connectionType).toString(), arg2 = (accountDecision).toString()))
            device.connectType = ConnectType.Loading
            try {
                connect(connectDevice = device)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LogKit.e(AppStrings.ui_account_device_connection_failed_arg0.format(arg0 = (device.id)), error)
                device.connectType = Fail
                recordAccountAutoConnectFailure(device.id, nowEpochMillis)
            } finally {
                finishAccountAutoConnect(device.id)
            }
        }
        withContext(Dispatchers.Main) {
            upsertDiscoveredSocketDevice(
                socketDevices = socketDevices,
                device = device,
                browserWebRtcTransport = shouldConnectHttpDeviceThroughBrowserWebRtc(device),
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun pingDevice(ip: String, port: Int, localIpSet: Set<String>? = null): SocketDevice? {
        if (PlatformType == DeviceType.JS) {
            LogKit.w(AppStrings.ui_browser_webrtc_prohibits_sending_device_ping_target_arg0_arg1.format(arg0 = ip, arg1 = (port).toString()))
            return null
        }

        // 构造将要发送的本机设备信息：若本机 host 与目标 ip 不在同一网段(/24)，
        // 则选择一个与目标 ip 同网段的本机 IPv4 地址作为 host。
        val localAddresses = getAllIPAddresses(SocketClientIPEnum.IPV4_UP)
        val selfDevice = getSocketDevice()
        val requestDevice = selfDevice.withCopy(
            host = selectAdvertisedHttpHost(
                currentHost = selfDevice.host,
                targetHost = ip,
                candidateHosts = localAddresses,
            )
        )
        val resolvedLocalIps = localIpSet ?: getLocalIpv4Set(listOf(requestDevice.host))
        if (ip in resolvedLocalIps) {
            // 避免向本机或本地地址发送 Ping。
            return null
        }

        val accountFeatureEnabled = SettingsUtils.fileShare.isAccountDeviceAutoConnectEnabled()
        if (accountFeatureEnabled && accountDeviceTrustRegistry.state.value.isNearExpiry(Clock.System.now().toEpochMilliseconds())) {
            accountDeviceTrustRefreshTrigger.refreshIfNeeded(force = false)
        }
        val discoveryChallenge = if (accountFeatureEnabled) newAccountDeviceNonce() else ""
        val encryptedTransport = usePlainHttpDeviceTransport()
        val pingHttp = discoveryPingPool.withClient { pingClient, capture ->
            val response = pingClient.post {
                url {
                    host = ip
                    path("/ping")
                    this.port = port
                    protocol = deviceApiProtocol(plainHttpTransport = encryptedTransport)
                }

                contentType(ContentType.Application.ProtoBuf)
                header(HttpHeaders.Connection, "close")
                header(DISCOVERY_PING_HEADER, "true")
                if (discoveryChallenge.isNotBlank()) {
                    header(ACCOUNT_DEVICE_CHALLENGE_HEADER, discoveryChallenge)
                }
                setFolderSpanRequestBody(
                    ProtoBuf.encodeToByteArray(SocketDevice.serializer(), requestDevice),
                    encryptedTransport
                )
            }
            CapturedDiscoveryPing(
                body = response.folderSpanBodyBytes(),
                handshakeFingerprint = capture.take(),
                headers = response.headers.names().associateWith { name -> response.headers[name] },
            )
        }

        val responseBody = pingHttp.body
        val socketDevice = responseBody.takeIf { item -> item.isNotEmpty() }?.let { item ->
            ProtoBuf.decodeFromByteArray(SocketDevice.serializer(), item)
        }?.takeIf { item -> item.id != requestDevice.id } ?: return null

        val handshakeTlsPin = requireHandshakeTlsPin(pingHttp.handshakeFingerprint) ?: return null
        socketDevice.tlsFingerprintSha256 = handshakeTlsPin

        socketDevice.let { device ->
            device.host = ip
            device.httpsPort = device.httpsPortOrFallback()
            presenceMonitor.markSeen(device.id)

            val deviceData = database.deviceQueries.queryById(device.id).executeAsOneOrNullAwait()
            if (deviceData == null) {
                database.deviceQueries.insert(
                    id = device.id,
                    name = device.name,
                    host = device.host,
                    port = device.httpsPort.toLong(),
                    type = device.type
                ).awaitDatabaseReady()
                SyncSnapshotChangeNotifier.onDeviceConfigurationChanged()
            } else {
                if ((deviceData.name != device.name && deviceData.hasRemarks == false) || deviceData.type != device.type) {
                    database.deviceQueries.updateNameAndTypeById(device.name, device.type, device.id)
                        .awaitDatabaseReady()
                    SyncSnapshotChangeNotifier.onDeviceConfigurationChanged()
                }
            }

            val savedPolicy = database.deviceConnectQueries
                .queryByIdAndCategory(device.id, DeviceCategory.CLIENT)
                .executeAsOneOrNullAwait()
            applyDiscoveredConnectType(device, savedPolicy?.connectionType)

            val nowEpochMillis = Clock.System.now().toEpochMilliseconds()
            val snapshot = accountDeviceTrustRegistry.state.value
            val proof = accountDeviceProofFromHeaders { name -> pingHttp.headers[name] }
            val proofVerification = if (discoveryChallenge.isBlank()) {
                AccountDeviceProofVerification.Missing
            } else {
                verifyAccountDeviceProof(
                    proof = proof,
                    snapshot = snapshot,
                    context = AccountDeviceProofContext(
                        purpose = ACCOUNT_DEVICE_DISCOVERY_PURPOSE,
                        signerDeviceKey = device.id,
                        targetDeviceKey = requestDevice.id,
                        targetPath = "/ping",
                        nonce = discoveryChallenge,
                        issuedAtEpochSeconds = proof?.issuedAtEpochSeconds ?: 0L,
                        payloadSha256 = responseBody.accountDeviceSha256(),
                    ),
                    nowEpochMillis = nowEpochMillis,
                    replayCache = accountDeviceNonceReplayCache,
                )
            }
            val accountDecision = resolveAccountDeviceLanDecision(
                featureEnabled = accountFeatureEnabled,
                snapshot = snapshot,
                nowEpochMillis = nowEpochMillis,
                deviceKey = device.id,
                proofVerification = proofVerification,
                permanentlyRejected = savedPolicy?.connectionType == DeviceConnectType.PERMANENTLY_BANNED,
                connectedOrConnecting = isConnectedOrConnecting(device.id),
            )

            val shouldAutoConnect = savedPolicy?.connectionType == DeviceConnectType.AUTO_CONNECT ||
                accountDecision == AccountDeviceLanDecision.AutoConnect
            if (shouldAutoConnect && beginAccountAutoConnect(device.id, nowEpochMillis)) {
                device.connectType = ConnectType.Loading
                try {
                    connect(
                        connectDevice = device,
                        accountDeviceAuthorization = accountDecision == AccountDeviceLanDecision.AutoConnect,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    LogKit.e(AppStrings.ui_account_device_connection_failed_arg0.format(arg0 = (device.id)), error)
                    device.connectType = Fail
                    recordAccountAutoConnectFailure(device.id, nowEpochMillis)
                } finally {
                    finishAccountAutoConnect(device.id)
                }
            }
            // 更新 UI 相关的可观察集合需要在主线程
            withContext(Dispatchers.Main) {
                upsertDiscoveredSocketDevice(
                    socketDevices = socketDevices,
                    device = device,
                    browserWebRtcTransport = shouldConnectHttpDeviceThroughBrowserWebRtc(device),
                )
            }
        }

        return socketDevice
    }

    fun connectInBackground(connectDevice: SocketDevice) {
        mainScope.launch {
            try {
                connect(connectDevice)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                updateSocketDeviceConnectType(connectDevice, Fail)
                inject<FileState>().value.updateDesk(FileProtocol.Local, Local())
            }
        }
    }

    suspend fun connect(
        connectDevice: SocketDevice,
        accountDeviceAuthorization: Boolean = false,
    ) {
        LogKit.i(
            AppStrings.ui_device_connection_id_arg0_transport_arg1.format(arg0 = (connectDevice.id), arg1 = (connectDevice.transportType).toString()) +
                "host=${connectDevice.host}:${connectDevice.httpsPort} accountAuth=$accountDeviceAuthorization"
        )
        if (connectDevice.transportType == DeviceTransportType.WebRtc) {
            LogKit.i(AppStrings.ui_device_connection_via_webrtc_id_arg0.format(arg0 = (connectDevice.id)))
            connectWebRtcDevice(connectDevice)
            return
        }
        if (shouldConnectHttpDeviceThroughBrowserWebRtc(connectDevice)) {
            LogKit.i(AppStrings.ui_device_connection_via_browser_webrtc_id_arg0.format(arg0 = (connectDevice.id)))
            connectBrowserWebRtcDevice(connectDevice)
            return
        }
        LogKit.i(AppStrings.ui_device_connection_via_tls_session_id_arg0.format(arg0 = (connectDevice.id)))
        connectHttpDevice(
            connectDevice = connectDevice,
            accountDeviceAuthorization = accountDeviceAuthorization,
        )
    }

    override suspend fun isPermanentlyRejected(deviceKey: String): Boolean {
        val clientDecision = database.deviceConnectQueries
            .queryByIdAndCategory(deviceKey, DeviceCategory.CLIENT)
            .executeAsOneOrNullAwait()
        val serverDecision = database.deviceConnectQueries
            .queryByIdAndCategory(deviceKey, DeviceCategory.SERVER)
            .executeAsOneOrNullAwait()
        return clientDecision?.connectionType == DeviceConnectType.PERMANENTLY_BANNED ||
            serverDecision?.connectionType == DeviceConnectType.PERMANENTLY_BANNED
    }

    override suspend fun isConnectedOrConnecting(deviceKey: String): Boolean = withContext(Dispatchers.Main) {
        socketDevices.any { item ->
            item.id == deviceKey &&
                (item.connectType == ConnectType.Connect || item.connectType == ConnectType.Loading)
        }
    }

    private fun applyDiscoveredConnectType(device: SocketDevice, savedConnectionType: DeviceConnectType?) {
        device.connectType = when (savedConnectionType) {
            null -> ConnectType.New
            DeviceConnectType.PERMANENTLY_BANNED -> ConnectType.Rejected
            else -> ConnectType.UnConnect
        }
    }

    private suspend fun beginAccountAutoConnect(deviceKey: String, nowEpochMillis: Long): Boolean =
        accountAutoConnectMutex.withLock {
            if (deviceKey in accountAutoConnectInFlight) return@withLock false
            if ((accountAutoConnectRetryAfter[deviceKey] ?: Long.MIN_VALUE) > nowEpochMillis) return@withLock false
            accountAutoConnectInFlight += deviceKey
            true
        }

    private suspend fun finishAccountAutoConnect(deviceKey: String) {
        accountAutoConnectMutex.withLock { accountAutoConnectInFlight -= deviceKey }
    }

    private suspend fun recordAccountAutoConnectFailure(deviceKey: String, nowEpochMillis: Long) {
        accountAutoConnectMutex.withLock {
            accountAutoConnectRetryAfter[deviceKey] = nowEpochMillis + ACCOUNT_AUTO_CONNECT_RETRY_MILLIS
        }
    }

    suspend fun markAccountDeviceAutoAuthorized(deviceKey: String) {
        withContext(Dispatchers.Main) {
            accountAuthorizedDeviceIds.add(deviceKey)
        }
    }

    override suspend fun revokeTemporaryTrust(deviceKey: String, disconnect: Boolean) {
        remoteDeviceLastSeenMutex.withLock {
            remoteDeviceLastSeen.remove(deviceKey)
        }
        withContext(Dispatchers.Main) {
            if (!accountAuthorizedDeviceIds.remove(deviceKey)) return@withContext
            deviceCertificateState.removeDeviceToken(deviceKey)
            revokeWebRtcBootstrapAuthorization(deviceKey)
            remoteDeviceConnections.remove(deviceKey)
            if (disconnect) {
                socketDevices.filter { item -> item.id == deviceKey }.toList().forEach(::disconnectSocketDevice)
            }
        }
    }

    override suspend fun clearTemporaryTrust(disconnect: Boolean) {
        val deviceKeys = withContext(Dispatchers.Main) { accountAuthorizedDeviceIds.toList() }
        deviceKeys.forEach { deviceKey -> revokeTemporaryTrust(deviceKey, disconnect) }
        accountAutoConnectMutex.withLock {
            accountAutoConnectInFlight.clear()
            accountAutoConnectRetryAfter.clear()
        }
    }

    private suspend fun connectBrowserWebRtcDevice(connectDevice: SocketDevice) {
        val baseUrl = connectDevice.browserWebRtcBaseUrl()
        if (baseUrl.isBlank()) {
            throw IllegalStateException(AppStrings.ui_browser_webrtc_connection_address_incomplete)
        }
        if (!canEnableBrowserWebRtc(baseUrl)) {
            throw IllegalStateException(AppStrings.ui_browser_webrtc_secure_page_requires_http_signaling)
        }
        if (
            browserWebRtcConnectionStatus.value == WebRtcConnectionStatus.Idle ||
            browserWebRtcConnectionStatus.value == WebRtcConnectionStatus.Disconnected ||
            browserWebRtcConnectionStatus.value == WebRtcConnectionStatus.Error
        ) {
            browserWebRtcController.connectBrowserHttpSignalingClient(baseUrl)
        }
        val peer = SignalingDevice(
            id = connectDevice.id,
            name = connectDevice.name,
            pathSeparator = connectDevice.pathSeparator,
            host = connectDevice.host,
            port = connectDevice.httpsPortOrFallback(),
            type = connectDevice.type.name
        )
        upsertWebRtcSocketDevice(peer, ConnectType.Loading)
        withContext(Dispatchers.Main) {
            removeHttpDevicesForBrowserWebRtcPeers(
                socketDevices = socketDevices,
                peerIds = setOf(connectDevice.id),
                browserWebRtcTransport = true,
            )
        }
        waitForBrowserWebRtcPeer(connectDevice.id)
        browserWebRtcController.requestPeerConnection(connectDevice.id)
    }

    private suspend fun waitForBrowserWebRtcPeer(deviceId: String) {
        withTimeout((CONNECT_TIMEOUT * 1000L).milliseconds) {
            while (browserWebRtcPeerSessionStates.value.none { item -> item.peer.id == deviceId }) {
                delay(100.milliseconds)
            }
        }
    }

    private fun shouldConnectHttpDeviceThroughBrowserWebRtc(device: SocketDevice): Boolean {
        return PlatformType == DeviceType.JS &&
            device.transportType == DeviceTransportType.Session &&
            device.host.isNotBlank()
    }

    private fun SocketDevice.browserWebRtcBaseUrl(): String {
        val port = httpsPortOrFallback()
        return buildBrowserWebRtcDeviceBaseUrl(host, port)
    }

    private fun canEnableBrowserWebRtc(baseUrl: String): Boolean =
        canUseBrowserWebRtcSignalingBaseUrl(
            baseUrl = baseUrl,
            platformType = PlatformType,
            browserSecureContext = WebRtcBrowserDownloadRegistry.isSecureContext(),
        )

    fun resolveConnectedDevice(
        deviceId: String,
        preferredDevice: Device? = null,
    ): Device? {
        if (preferredDevice != null && (deviceId.isBlank() || preferredDevice.id == deviceId)) {
            return preferredDevice
        }
        if (deviceId.isBlank()) return null

        devices.firstOrNull { item -> item.id == deviceId }?.let { return it }

        val socketDevice = socketDevices.firstOrNull { item ->
            item.id == deviceId &&
                when (item.transportType) {
                    DeviceTransportType.Session -> item.sessionClient != null || item.httpClient != null
                    DeviceTransportType.WebRtc -> item.connectType == ConnectType.Connect
                }
        } ?: return null

        return when (socketDevice.transportType) {
            DeviceTransportType.Session -> socketDevice.toDevice()
            DeviceTransportType.WebRtc -> socketDevice.toConnectedWebRtcDevice()
        }
    }

    fun hasActiveDeviceConnection(deviceId: String): Boolean {
        return socketDevices.any { device -> device.id == deviceId && device.hasActiveConnection() }
    }

    fun hasActiveShareConnection(deviceId: String): Boolean {
        if (deviceId in pendingShareConnectionIds.value) return true
        if (shareSessionManagers[deviceId]?.isActive == true) return true
        if (shares.any { share ->
                share.id == deviceId && (share.session?.isActive != false)
            }
        ) {
            return true
        }
        return listOf(webRtcController, browserWebRtcController).any { controller ->
            controller.isShareSession(deviceId) && controller.deviceSessionClients(deviceId) != null
        }
    }

    private fun connectWebRtcDevice(connectDevice: SocketDevice) {
        val controller = resolveWebRtcControllerForPeer(connectDevice.id)
        if (controller.connectionStatus.value != WebRtcConnectionStatus.Connected) {
            throw IllegalStateException(AppStrings.ui_join_webrtc_room_first)
        }
        if (controller.peerSessionStates.value.none { item -> item.peer.id == connectDevice.id }) {
            throw IllegalStateException(AppStrings.ui_device_no_longer_in_current_room)
        }
        controller.requestPeerConnection(
            remoteId = connectDevice.id,
            shareNonce = connectDevice.shareConnectNonce,
            tlsFingerprintSha256 = getSocketDevice().normalizedTlsFingerprint(),
        )
    }

    private fun resolveWebRtcControllerForPeer(deviceId: String): MultiPeerWebRtcController {
        return if (browserWebRtcPeerSessionStates.value.any { item -> item.peer.id == deviceId }) {
            browserWebRtcController
        } else {
            webRtcController
        }
    }

    private suspend fun connectHttpDevice(
        connectDevice: SocketDevice,
        allowChangedTrustedCertificate: Boolean = false,
        accountDeviceAuthorization: Boolean = false,
    ) {
        val fileState = inject<FileState>().value
        val connection = mainScope.async(Dispatchers.Default) {
            val sessionClientManager = DeviceSessionClientManager()
            LogKit.i(AppStrings.ui_create_device_session_client_id_arg0.format(arg0 = (connectDevice.id)))
            sessionClientManager.onCancelConnect { reason ->
                val connectType = when (reason) {
                    HttpRouteDisconnectReason.Unauthorized -> Fail
                    HttpRouteDisconnectReason.Explicit -> ConnectType.UnConnect
                }
                clearHttpDeviceConnection(
                    deviceId = connectDevice.id,
                    fileState = fileState,
                    connectType = connectType,
                )
            }

            try {
                val connected = sessionClientManager.connect(
                    connectDevice = connectDevice,
                    allowChangedTrustedCertificate = allowChangedTrustedCertificate,
                    accountDeviceAuthorization = accountDeviceAuthorization,
                )
                if (!connected) return@async
                startSessionHeartbeat(connectDevice.id, sessionClientManager, fileState)
            } catch (error: Throwable) {
                if (error !is CancellationException) {
                    LogKit.e(
                        AppStrings.ui_http_device_connection_failed_id_arg0_host_arg1_arg2.format(arg0 = connectDevice.id, arg1 = connectDevice.host, arg2 = (connectDevice.httpsPort).toString()) +
                            "message=${error.message}",
                        error
                    )
                    recordHttpDeviceConnectionFailure(connectDevice, error)
                    clearHttpDeviceConnection(
                        deviceId = connectDevice.id,
                        fileState = fileState,
                        connectType = Fail,
                    )
                }
                throw error
            }
        }
        connection.await()
    }

    private suspend fun recordHttpDeviceConnectionFailure(
        connectDevice: SocketDevice,
        error: Throwable,
    ) {
        withContext(Dispatchers.Main) {
            _httpDeviceConnectionFailure.value = HttpDeviceConnectionFailure(connectDevice, error)
        }
    }

    private suspend fun clearHttpDeviceConnection(
        deviceId: String,
        fileState: FileState,
        connectType: ConnectType,
        cancelHeartbeat: Boolean = true,
    ) {
        withContext(Dispatchers.Main) {
            // 401、显式断开或心跳失败时，移除该设备的连接状态
            val updatedDevices = socketDevices.map { item ->
                if (item.id == deviceId) {
                    item.withCopy(connectType = connectType, httpClient = null, sessionClient = null)
                } else {
                    item
                }
            }
            socketDevices.clear()
            socketDevices.addAll(updatedDevices)
            replaceDisconnectedHttpDeviceWithWebRtc(deviceId)
            syncCurrentDeskAfterHttpDisconnect(deviceId, fileState)
            // 取消并移除对应心跳任务
            if (cancelHeartbeat) {
                heartbeatJobs.remove(deviceId)?.cancel()
            }
        }
    }

    private fun replaceDisconnectedHttpDeviceWithWebRtc(deviceId: String) {
        val replacement = connectedWebRtcDeviceOrNull(deviceId)
        devices.removeAll { item -> item.id == deviceId }
        if (replacement != null) {
            devices.add(replacement)
        }
    }

    private fun syncCurrentDeskAfterHttpDisconnect(deviceId: String, fileState: FileState) {
        val currentDesk = fileState.deskType.value as? Device ?: return
        if (currentDesk.id != deviceId || currentDesk.isWebRtcDesk()) return

        val replacement = devices.firstOrNull { item -> item.id == deviceId }
        if (replacement != null) {
            fileState.updateDesk(FileProtocol.Device, replacement)
        } else {
            fileState.updateDesk(FileProtocol.Local, Local())
        }
    }

    private fun connectedWebRtcDeviceOrNull(deviceId: String): Device? {
        val socketDevice = socketDevices.firstOrNull { item ->
            item.id == deviceId &&
                item.transportType == DeviceTransportType.WebRtc &&
                item.connectType == ConnectType.Connect
        } ?: return null
        return socketDevice.toConnectedWebRtcDevice(resolveWebRtcControllerForPeer(deviceId))
    }

    private suspend fun startSessionHeartbeat(
        deviceId: String,
        sessionClientManager: DeviceSessionClientManager,
        fileState: FileState,
    ) {
        withContext(Dispatchers.Main) {
            heartbeatJobs[deviceId]?.cancel()
            LogKit.i(AppStrings.ui_device_session_heartbeat_start_id_arg0.format(arg0 = (deviceId)))
            heartbeatJobs[deviceId] = mainScope.launch {
                try {
                    sessionClientManager.deviceRouteClient.heartbeat()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    LogKit.e(AppStrings.ui_http_device_heartbeat_failed_disconnected_id_arg0_message_arg1.format(arg0 = deviceId, arg1 = (error.message).toString()), error)
                    clearHttpDeviceConnection(
                        deviceId = deviceId,
                        fileState = fileState,
                        connectType = Fail,
                        cancelHeartbeat = false,
                    )
                } finally {
                    heartbeatJobs.remove(deviceId)
                }
            }
        }
    }

    private fun syncWebRtcSocketDevices(
        peerStates: List<WebRtcPeerSessionSummary>,
        controller: MultiPeerWebRtcController
    ) {
        val peerIds = peerStates.mapTo(mutableSetOf()) { item -> item.peer.id }
        removeHttpDevicesForBrowserWebRtcPeers(
            socketDevices = socketDevices,
            peerIds = peerIds,
            browserWebRtcTransport = controller === browserWebRtcController,
        )
        val removedIds = socketDevices
            .filter { item -> item.transportType == DeviceTransportType.WebRtc && item.id !in peerIds }
            .map { item -> item.id }
        if (removedIds.isNotEmpty()) {
            removedIds.forEach { deviceId ->
                pendingWebRtcConnectRequests.remove(deviceId)
                revokeWebRtcBootstrapAuthorization(deviceId)
            }
            socketDevices.removeAll { item ->
                item.transportType == DeviceTransportType.WebRtc && item.id !in peerIds
            }
            devices.removeAll { item ->
                item.id in removedIds &&
                    socketDevices.none { socket ->
                        socket.transportType == DeviceTransportType.Session &&
                            socket.id == item.id &&
                            (socket.httpClient != null || socket.sessionClient != null)
                    }
            }
        }

        peerStates.forEach { peerState ->
            val peer = peerState.peer
            val connectType = if (peerState.isShareSession) ConnectType.UnConnect else mapWebRtcStatus(peerState.status)
            val device = peer.toWebRtcSocketDevice(connectType)
            val existingIndex = socketDevices.indexOfFirst { item -> item.matchesRecord(device) }
            if (existingIndex == -1) {
                socketDevices.add(device)
            } else {
                val existing = socketDevices[existingIndex]
                socketDevices[existingIndex] = existing.withCopy(
                    name = device.name,
                    pathSeparator = device.pathSeparator,
                    host = device.host,
                    port = device.port,
                    type = device.type,
                    connectType = device.connectType,
                    transportType = DeviceTransportType.WebRtc
                )
            }
        }
        syncConnectedWebRtcDevices(peerStates, controller)
        syncCurrentDeskAfterWebRtcStateChange(peerStates)
    }

    private fun mapWebRtcStatus(status: WebRtcConnectionStatus): ConnectType {
        return when (status) {
            WebRtcConnectionStatus.Idle -> ConnectType.UnConnect
            WebRtcConnectionStatus.Connecting -> ConnectType.Loading
            WebRtcConnectionStatus.Connected -> ConnectType.Connect
            WebRtcConnectionStatus.Disconnected -> ConnectType.UnConnect
            WebRtcConnectionStatus.Error -> Fail
        }
    }

    private fun SignalingDevice.toWebRtcSocketDevice(connectType: ConnectType): SocketDevice {
        return SocketDevice(
            id = id,
            name = name?.takeIf { item -> item.isNotBlank() } ?: id,
            pathSeparator = pathSeparator ?: "/",
            host = host ?: "",
            port = port ?: 0,
            type = type
                ?.takeIf { item -> item.isNotBlank() }
                ?.let { item -> runCatching { enumValueOf<DeviceType>(item) }.getOrNull() }
                ?: DeviceType.JVM,
            connectType = connectType,
            transportType = DeviceTransportType.WebRtc
        )
    }

    private fun SocketDevice.toConnectedWebRtcDevice(controller: MultiPeerWebRtcController = webRtcController): Device {
        val clients = checkNotNull(controller.deviceSessionClients(id)) {
            "WebRTC Device Session clients are not ready for $id"
        }
        return Device(
            id = id,
            name = name.ifBlank { id },
            pathSeparator = pathSeparator.ifBlank { PathUtils.getPathSeparator() },
            host = mutableMapOf(),
            type = type,
            token = token,
            pathClient = clients,
            bookmarkClient = clients,
            fileClient = clients,
            transportType = DeviceTransportType.WebRtc,
        )
    }

    private fun syncConnectedWebRtcDevices(
        peerStates: List<WebRtcPeerSessionSummary>,
        controller: MultiPeerWebRtcController
    ) {
        val connectedPeers = peerStates.filter { item ->
            !item.isShareSession &&
                item.status == WebRtcConnectionStatus.Connected &&
                controller.deviceSessionClients(item.peer.id) != null
        }
        val connectedIds = connectedPeers.mapTo(mutableSetOf()) { item -> item.peer.id }

        devices.removeAll { item ->
            item.id !in connectedIds &&
                socketDevices.any { socket ->
                    socket.transportType == DeviceTransportType.WebRtc && socket.id == item.id
                } &&
                socketDevices.none { socket ->
                    socket.transportType == DeviceTransportType.Session &&
                        socket.id == item.id &&
                        (socket.httpClient != null || socket.sessionClient != null)
                }
        }

        connectedPeers.forEach { peerState ->
            val peer = peerState.peer
            val device = peer
                .toWebRtcSocketDevice(ConnectType.Connect)
                .toConnectedWebRtcDevice(controller)
            val index = devices.indexOfFirst { item -> item.id == peer.id }
            if (index == -1) {
                devices.add(device)
            } else if (socketDevices.none { item ->
                    item.id == peer.id &&
                        item.transportType == DeviceTransportType.Session &&
                        (item.httpClient != null || item.sessionClient != null)
                }
            ) {
                devices[index] = device
            }
        }
    }

    private fun syncCurrentDeskAfterWebRtcStateChange(peerStates: List<WebRtcPeerSessionSummary>) {
        val fileState = inject<FileState>().value
        val currentDesk = fileState.deskType.value as? Device ?: return
        if (!currentDesk.isWebRtcDesk()) return

        val peerState = peerStates.firstOrNull { item -> item.peer.id == currentDesk.id }
        val shouldFallback =
            peerState == null ||
                peerState.status == WebRtcConnectionStatus.Idle ||
                peerState.status == WebRtcConnectionStatus.Disconnected ||
                peerState.status == WebRtcConnectionStatus.Error
        if (!shouldFallback) return

        val replacement = devices.firstOrNull { item -> item.id == currentDesk.id && item !== currentDesk }
        if (replacement != null) {
            fileState.updateDesk(FileProtocol.Device, replacement)
        } else {
            fileState.updateDesk(FileProtocol.Local, Local())
        }
    }

    private fun Device.isWebRtcDesk(): Boolean {
        return usesWebRtcFileTransport()
    }

    private suspend fun handleIncomingWebRtcConnectRequest(
        event: WebRtcConnectRequestEvent,
        controller: MultiPeerWebRtcController
    ) {
        val peer = event.peer
        val requestedAt = Clock.System.now().toEpochMilliseconds()
        if (event.shareNonce.isNotBlank()) {
            val shareDevice = peer.toWebRtcSocketDevice(ConnectType.Loading).withCopy(
                tlsFingerprintSha256 = event.tlsFingerprintSha256,
            )
            val grant = consumeAllowedDeviceShareConnection(shareDevice, event.shareNonce)
            if (grant == null) {
                controller.rejectConnectRequest(peer.id)
                return
            }
            val authorization = issueWebRtcBootstrapAuthorization(
                deviceId = peer.id,
                connectionAttemptId = event.connectionAttemptId,
                allowDefaultRole = true,
                sharePathScope = grant.pathScope,
            )
            if (authorization == null) {
                controller.rejectConnectRequest(peer.id, AppStrings.ui_no_available_permissions_configured)
            } else {
                controller.approveConnectRequest(peer.id, authorization)
            }
            return
        }
        pendingWebRtcConnectRequests[peer.id] = PendingWebRtcConnectRequest(
            peer = peer,
            controller = controller,
            connectionAttemptId = event.connectionAttemptId,
        )
        val currentConnectType = controller.peerSessionStates.value
            .firstOrNull { item -> item.peer.id == peer.id }
            ?.status
            ?.let(::mapWebRtcStatus)
            ?: ConnectType.UnConnect
        upsertWebRtcSocketDevice(peer, currentConnectType)

        val connectDecision = resolveWebRtcConnectDecision(peer.id)
        when (connectDecision) {
            WebRtcConnectDecision.Approve -> {
                pendingWebRtcConnectRequests.remove(peer.id)
                val authorization = issueWebRtcBootstrapAuthorization(
                    deviceId = peer.id,
                    connectionAttemptId = event.connectionAttemptId,
                )
                if (authorization == null) {
                    LogKit.w(AppStrings.ui_webrtc_auto_approval_failed_failed_generate_access_token_device.format(arg0 = peer.id))
                    controller.rejectConnectRequest(peer.id, AppStrings.ui_no_available_permissions_configured)
                } else {
                    controller.approveConnectRequest(peer.id, authorization)
                }
            }

            WebRtcConnectDecision.Reject -> {
                pendingWebRtcConnectRequests.remove(peer.id)
                controller.rejectConnectRequest(peer.id)
            }

            WebRtcConnectDecision.Waiting -> {
                updateConnectionRequest(
                    deviceId = peer.id,
                    connectionType = DeviceConnectType.WAITING,
                    requestedAt = requestedAt,
                    deviceName = peer.name
                )
            }
        }
    }

    private suspend fun resolveWebRtcConnectDecision(deviceId: String): WebRtcConnectDecision {
        val queriedDevice = database.deviceConnectQueries
            .queryByIdAndCategory(deviceId, DeviceCategory.SERVER)
            .executeAsOneOrNullAwait()

        if (queriedDevice?.connectionType == DeviceConnectType.PERMANENTLY_BANNED) {
            return WebRtcConnectDecision.Reject
        }
        if (
            queriedDevice?.connectionType == DeviceConnectType.AUTO_CONNECT ||
            deviceId in accountAuthorizedDeviceIds ||
            SettingsUtils.getBoolean(
                SettingsUtils.KEY_FILE_SHARE_AUTO_AUTHORIZE_DEVICE_CONNECT,
                false,
            )
        ) {
            return WebRtcConnectDecision.Approve
        }
        return WebRtcConnectDecision.Waiting
    }

    private suspend fun issueWebRtcBootstrapAuthorization(
        deviceId: String,
        connectionAttemptId: String,
        allowDefaultRole: Boolean = false,
        sharePathScope: DeviceSharePathScope? = null,
    ): DeviceSessionBootstrapAuthorization? {
        val queriedDevice = database.deviceConnectQueries
            .queryByIdAndCategory(deviceId, DeviceCategory.SERVER)
            .executeAsOneOrNullAwait()
        val roleId = when {
            queriedDevice != null && queriedDevice.roleId > 0L -> queriedDevice.roleId
            SettingsUtils.getBoolean(SettingsUtils.KEY_FILE_SHARE_AUTO_AUTHORIZE_DEVICE_CONNECT, false) -> {
                resolveAutoAuthorizeRoleId(
                    SettingsUtils.getLong(SettingsUtils.KEY_FILE_SHARE_AUTO_AUTHORIZE_ROLE_ID, 2L)
                )
            }

            deviceId in accountAuthorizedDeviceIds -> resolveAutoAuthorizeRoleId(
                SettingsUtils.getLong(SettingsUtils.KEY_FILE_SHARE_AUTO_AUTHORIZE_ROLE_ID, 2L)
            )

            allowDefaultRole -> resolveAutoAuthorizeRoleId(
                SettingsUtils.getLong(SettingsUtils.KEY_FILE_SHARE_AUTO_AUTHORIZE_ROLE_ID, 2L)
            )

            else -> null
        } ?: return null

        val authorization = deviceSessionBootstrapAuthorizationRegistry.issue(
            initiatorDeviceId = deviceId,
            targetDeviceId = getSocketDevice().id,
            connectionAttemptId = connectionAttemptId,
            roleId = roleId,
            sharePathScope = sharePathScope,
        )
        webRtcIssuedBootstrapAuthorizations.put(
            deviceId,
            authorization.opaqueAuthorization,
        )?.let { previous ->
            deviceSessionBootstrapAuthorizationRegistry.revoke(previous)
        }
        return authorization
    }

    private fun revokeWebRtcBootstrapAuthorization(deviceId: String) {
        webRtcIssuedBootstrapAuthorizations.remove(deviceId)?.let { authorization ->
            mainScope.launch {
                deviceSessionBootstrapAuthorizationRegistry.revoke(authorization)
            }
        }
    }

    private suspend fun resolveAutoAuthorizeRoleId(preferredRoleId: Long): Long? {
        if (database.deviceRoleQueries.selectById(preferredRoleId).executeAsOneOrNullAwait() != null) {
            return preferredRoleId
        }
        if (preferredRoleId != 2L && database.deviceRoleQueries.selectById(2L).executeAsOneOrNullAwait() != null) {
            return 2L
        }
        return database.deviceRoleQueries.selectAll().executeAsListAwait().firstOrNull()?.id
    }

    private fun upsertWebRtcSocketDevice(peer: SignalingDevice, connectType: ConnectType) {
        val device = peer.toWebRtcSocketDevice(connectType)
        val index = socketDevices.indexOfFirst { item -> item.matchesRecord(device) }
        if (index == -1) {
            socketDevices.add(device)
        } else if (socketDevices[index].transportType == DeviceTransportType.WebRtc) {
            socketDevices[index] = socketDevices[index].withCopy(
                name = device.name,
                pathSeparator = device.pathSeparator,
                host = device.host,
                port = device.port,
                type = device.type,
                connectType = connectType,
                transportType = DeviceTransportType.WebRtc
            )
        }
    }

    private enum class WebRtcConnectDecision {
        Approve,
        Reject,
        Waiting
    }


    val shares = mutableStateListOf<Share>()

    // Map<设备id, Pair<设备链接类型, 结束倒计时>>
    val shareRequest = mutableStateMapOf<String, Pair<DeviceConnectType, Long>>()

    fun updateShareRequest(
        deviceId: String,
        connectionType: DeviceConnectType,
        requestedAt: Long,
        deviceName: String? = null
    ) {
        shareRequest[deviceId] = Pair(connectionType, requestedAt)
        if (connectionType == DeviceConnectType.WAITING) {
            scheduleShareTimeout(deviceId, requestedAt)
            postShareRequestNotification(deviceId, deviceName, requestedAt)
        } else {
            clearShareTimeout(deviceId)
            clearShareRequestNotification(deviceId)
        }
    }

    fun removeShareRequest(deviceId: String) {
        shareRequest.remove(deviceId)
        shareRequestDevices.remove(deviceId)
        clearShareTimeout(deviceId)
        clearShareRequestNotification(deviceId)
    }

    // 允许远程设备连接快捷分享服务
    val allowDeviceShareConnection = mutableStateMapOf<String, DeviceShareConnectionGrant>()

    private val shareRequestDevices = mutableStateMapOf<String, SocketDevice>()

    // 共享连接结果 Map<设备Id, 共享连接结果>
    private val shareConnectionResults = mutableStateMapOf<String, DeviceShareConnectionResult>()

    fun publishShareConnectionResult(
        deviceId: String,
        status: FileShareStatus,
        message: String = ""
    ) {
        shareConnectionResults[deviceId] = DeviceShareConnectionResult(status, message)
    }

    fun consumeShareConnectionResult(deviceId: String): DeviceShareConnectionResult? {
        return shareConnectionResults.remove(deviceId)
    }

    // 设备分享会话任务
    private val shareJobs = mutableMapOf<String, Job>()
    private val shareGrantExpiryJobs = mutableMapOf<String, Job>()
    private val shareSessionManagers = mutableMapOf<String, DeviceSessionClientManager>()
    private val shareSessionHeartbeatJobs = mutableMapOf<String, Job>()
    private val shareSessionLifecycleJobs = mutableMapOf<String, Job>()
    private val pendingShareConnectionIds = MutableStateFlow<Set<String>>(emptySet())

    private fun resolveDeviceName(deviceId: String, deviceName: String?): String {
        return deviceName
            ?: socketDevices.firstOrNull { item -> item.id == deviceId }?.name
            ?: deviceId
    }

    fun rememberShareRequestDevice(device: SocketDevice, connectNonce: String) {
        shareRequestDevices[device.id] = device.withCopy(shareConnectNonce = connectNonce)
    }

    fun resolveShareRequestDevice(deviceId: String): SocketDevice? {
        return shareRequestDevices[deviceId]
            ?: socketDevices.firstOrNull { item -> item.id == deviceId }
    }

    private fun allowDeviceShareConnection(device: SocketDevice, nonce: String) {
        if (device.id.isBlank() || nonce.isBlank()) return
        val allowedPaths = fileShareState.shareToDevices[device.id]
            ?.second
            .orEmpty()
            .map { file -> DeviceSharePathGrant(file.path, file.isDirectory) }
        if (allowedPaths.isEmpty()) return
        val grant = DeviceShareConnectionGrant(
            deviceId = device.id,
            tlsFingerprintSha256 = device.normalizedTlsFingerprint(),
            nonce = nonce,
            expiresAtMillis = Clock.System.now().toEpochMilliseconds() + CONNECT_TIMEOUT * 1000L,
            allowedPaths = allowedPaths,
        )
        allowDeviceShareConnection[device.id] = grant
        shareGrantExpiryJobs.remove(device.id)?.cancel()
        shareGrantExpiryJobs[device.id] = mainScope.launch {
            val remaining = (grant.expiresAtMillis - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0L)
            delay(remaining.milliseconds)
            if (allowDeviceShareConnection[device.id] == grant) {
                allowDeviceShareConnection.remove(device.id)
            }
            shareGrantExpiryJobs.remove(device.id)
        }
    }

    fun consumeAllowedDeviceShareConnection(device: SocketDevice, nonce: String): DeviceShareConnectionGrant? {
        val grant = allowDeviceShareConnection[device.id] ?: return null
        val now = Clock.System.now().toEpochMilliseconds()
        if (now > grant.expiresAtMillis) {
            allowDeviceShareConnection.remove(device.id)
            shareGrantExpiryJobs.remove(device.id)?.cancel()
            return null
        }
        if (!grant.matches(device, nonce, now)) {
            return null
        }
        allowDeviceShareConnection.remove(device.id)
        shareGrantExpiryJobs.remove(device.id)?.cancel()
        return grant
    }

    private fun postConnectionRequestNotification(
        deviceId: String,
        deviceName: String?,
        requestedAt: Long
    ): RequestNotificationPostResult {
        val resolvedName = resolveDeviceName(deviceId, deviceName)
        val bundle = RequestNotificationFactory.buildDeviceConnectNotification(
            deviceId = deviceId,
            deviceName = resolvedName,
            timestamp = requestedAt,
            config = notificationConfig
        )
        return RequestNotificationDispatcher.post(notificationState, bundle)
    }

    private fun clearConnectionRequestNotification(deviceId: String) {
        val requestId = RequestNotificationFactory.requestId(RequestNotificationKind.DeviceConnect, deviceId)
        RequestNotificationDispatcher.remove(notificationState, requestId)
    }

    private fun postShareRequestNotification(deviceId: String, deviceName: String?, requestedAt: Long) {
        val resolvedName = resolveDeviceName(deviceId, deviceName)
        val bundle = RequestNotificationFactory.buildDeviceShareNotification(
            deviceId = deviceId,
            deviceName = resolvedName,
            timestamp = requestedAt,
            config = notificationConfig
        )
        RequestNotificationDispatcher.post(notificationState, bundle)
    }

    private fun clearShareRequestNotification(deviceId: String) {
        val requestId = RequestNotificationFactory.requestId(RequestNotificationKind.DeviceShare, deviceId)
        RequestNotificationDispatcher.remove(notificationState, requestId)
    }

    private fun scheduleConnectionTimeout(deviceId: String, requestedAt: Long) {
        connectionTimeoutJobs.remove(deviceId)?.cancel()
        val deadline = requestedAt + CONNECT_TIMEOUT * 1000L
        connectionTimeoutJobs[deviceId] = mainScope.launch {
            while (connectionRequest[deviceId]?.first == DeviceConnectType.WAITING) {
                val remaining = deadline - Clock.System.now().toEpochMilliseconds()
                if (remaining <= 0) {
                    updateConnectionRequest(
                        deviceId = deviceId,
                        connectionType = DeviceConnectType.REJECTED,
                        requestedAt = Clock.System.now().toEpochMilliseconds(),
                        deviceName = resolveDeviceName(deviceId, pendingWebRtcConnectRequests[deviceId]?.peer?.name)
                    )
                    break
                }
                delay(1000.milliseconds)
            }
            clearConnectionTimeout(deviceId)
        }
    }

    private fun clearConnectionTimeout(deviceId: String) {
        connectionTimeoutJobs.remove(deviceId)?.cancel()
    }

    private fun scheduleShareTimeout(deviceId: String, requestedAt: Long) {
        shareTimeoutJobs.remove(deviceId)?.cancel()
        val deadline = requestedAt + CONNECT_TIMEOUT * 1000L
        shareTimeoutJobs[deviceId] = mainScope.launch {
            while (shareRequest[deviceId]?.first == DeviceConnectType.WAITING) {
                val remaining = deadline - Clock.System.now().toEpochMilliseconds()
                if (remaining <= 0) {
                    shareRequest[deviceId] = Pair(
                        DeviceConnectType.REJECTED,
                        Clock.System.now().toEpochMilliseconds()
                    )
                    publishShareConnectionResult(
                        deviceId = deviceId,
                        status = FileShareStatus.REJECTED,
                        message = AppStrings.ui_other_party_did_not_receive
                    )
                    clearShareRequestNotification(deviceId)
                    break
                }
                delay(1000.milliseconds)
            }
            clearShareTimeout(deviceId)
        }
    }

    private fun clearShareTimeout(deviceId: String) {
        shareTimeoutJobs.remove(deviceId)?.cancel()
    }

    fun share(device: SocketDevice) {
        // 若已存在任务则先取消，确保最新会话
        shareJobs.remove(device.id)?.cancel()
        fileShareState.sendFileMessage.remove(device.id)

        shareJobs[device.id] = mainScope.launch {
            var httpClient: HttpClient? = null
            var completedOnce = false
            var completedPollFailures = 0

            suspend fun keepCompletedSessionAfterPollFailure(message: String): Boolean {
                if (!completedOnce) return false
                completedPollFailures++
                fileShareState.sendFile[device.id] = FileShareStatus.COMPLETED
                LogKit.w(
                    "${AppStrings.ui_share_short_poll_failed}: " +
                        "deviceId=${device.id} consecutive=$completedPollFailures message=$message",
                )
                delay((completedPollFailures * 1_000L).coerceAtMost(30_000L).milliseconds)
                return true
            }

            try {
                LogKit.i(AppStrings.ui_initiate_shared_short_polling_deviceid_arg0_host_arg1_arg2.format(arg0 = device.id, arg1 = device.host, arg2 = (device.port).toString()))
                val shareConnectNonce = 32.randomString(includeSpecial = false)
                val selfDevice = getSocketDevice()
                val requestDevice = selfDevice.withCopy(
                    host = selectAdvertisedHttpHost(
                        currentHost = selfDevice.host,
                        targetHost = device.host,
                        candidateHosts = getAllIPAddresses(SocketClientIPEnum.IPV4_UP),
                    )
                )
                val tlsFingerprint = device.normalizedTlsFingerprint()
                val encryptedTransport = usePlainHttpDeviceTransport()
                val baseUrl = device.deviceShareApprovalBaseUrl()
                httpClient = createPinnedNoProxyHttpClient(tlsFingerprint) {
                    expectSuccess = false
                    install(HttpTimeout) {
                        requestTimeoutMillis = 2000
                        connectTimeoutMillis = 2000
                        socketTimeoutMillis = 2000
                    }
                }

                var recordedWaiting = false
                while (isActive) {
                    val response = try {
                        httpClient.post("$baseUrl/api/share/heartbeat") {
                            contentType(ContentType.Application.ProtoBuf)
                            applyFileShareAccessKey()
                            setFolderSpanRequestBody(
                                ProtoBufCodec.encode(
                                    ShareRequestPollRequest(
                                        device = requestDevice,
                                        checkOnly = completedOnce,
                                        connectNonce = shareConnectNonce
                                    )
                                ),
                                encryptedTransport
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (keepCompletedSessionAfterPollFailure(error.message.orEmpty())) continue
                        throw error
                    }

                    if (!response.status.isSuccess()) {
                        val error = readHttpResponseException(response)
                        LogKit.w(
                            "${AppStrings.ui_share_short_poll_failed}: " +
                                "HTTP ${response.status.value} ${error.message}"
                        )
                        if (keepCompletedSessionAfterPollFailure("HTTP ${response.status.value} ${error.message}")) continue
                        fileShareState.sendFile[device.id] = FileShareStatus.ERROR
                        break
                    }

                    val pollResponse = try {
                        val bytes = response.folderSpanBodyBytes()
                        ProtoBufCodec.decode<ShareRequestPollResponse>(bytes)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (e: Exception) {
                        LogKit.e(AppStrings.ui_shared_short_poll_parsing_failed_arg0.format(arg0 = (e.message).toString()), e)
                        if (keepCompletedSessionAfterPollFailure(e.message.orEmpty())) continue
                        fileShareState.sendFile[device.id] = FileShareStatus.ERROR
                        break
                    }
                    completedPollFailures = 0

                    val fileShareStatus = pollResponse.status
                    val responseMessage = pollResponse.message
                    LogKit.d(AppStrings.ui_shared_short_polling_status_status_arg0_deviceid_arg1.format(arg0 = (fileShareStatus).toString(), arg1 = device.id))
                    if (completedOnce && fileShareStatus != FileShareStatus.COMPLETED) {
                        LogKit.i(AppStrings.ui_shared_connection_has_been_disconnected_clean_up_session_deviceid.format(arg0 = device.id))
                        markShareDisconnected(device.id)
                        return@launch
                    }
                    if (fileShareStatus == FileShareStatus.REJECTED) {
                        LogKit.i(AppStrings.ui_sharing_request_denied_clearing_session_deviceid_arg0.format(arg0 = device.id))
                        markShareRejected(
                            deviceId = device.id,
                            message = responseMessage.ifBlank { AppStrings.ui_other_party_did_not_receive }
                        )
                        return@launch
                    }
                    when (fileShareStatus) {
                        FileShareStatus.SENDING -> {}
                        FileShareStatus.ERROR -> {
                            if (responseMessage.isNotBlank()) {
                                fileShareState.sendFileMessage[device.id] = responseMessage
                            }
                        }

                        FileShareStatus.COMPLETED -> {
                            if (!completedOnce) {
                                LogKit.i(AppStrings.ui_shared_short_polling_completed_other_party_allowed_connect_deviceid.format(arg0 = device.id))
                                allowDeviceShareConnection(device, shareConnectNonce)
                                completedOnce = true
                            }
                        }

                        FileShareStatus.WAITING -> {
                            LogKit.d(AppStrings.ui_shared_short_polling_waiting_confirmation_other_party_deviceid_arg0.format(arg0 = device.id))
                            if (!recordedWaiting) {
                                recordedWaiting = true
                                withContext(Dispatchers.Default) {
                                    fileShareState.shareToDevices[device.id]?.second?.forEach { fileSimpleInfo ->
                                        shareHistoryStore.add(
                                            ShareHistoryInput(
                                                fileName = fileSimpleInfo.name,
                                                filePath = fileSimpleInfo.path,
                                                fileSize = fileSimpleInfo.size,
                                                isDirectory = fileSimpleInfo.isDirectory,
                                                sourceDeviceId = "",
                                                sourceDeviceName = AppStrings.ui_me,
                                                sourceDeviceType = DeviceType.JS,
                                                targetDeviceId = device.id,
                                                targetDeviceName = device.name,
                                                targetDeviceType = device.type,
                                                isOutgoing = true,
                                                status = fileShareStatus,
                                                errorMessage = "",
                                                savePath = "",
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    fileShareState.sendFile[device.id] = fileShareStatus

                    if (!completedOnce &&
                        fileShareStatus != FileShareStatus.WAITING &&
                        fileShareStatus != FileShareStatus.SENDING
                    ) {
                        break
                    }
                    delay(if (completedOnce) 2000.milliseconds else 1000.milliseconds)
                }
            } catch (_: CancellationException) {
                // 取消分享会话：重置状态
                LogKit.d(AppStrings.ui_shared_short_polling_session_canceled_deviceid_arg0.format(arg0 = device.id))
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_shared_short_polling_session_exception_arg0.format(arg0 = (e.message).toString()), e)
                if (completedOnce) {
                    markShareDisconnected(device.id)
                } else {
                    fileShareState.sendFile[device.id] = FileShareStatus.ERROR
                }
            } finally {
                // 关闭本次会话的 HttpClient
                runCatching { httpClient?.close() }
                LogKit.d(AppStrings.ui_shared_short_polling_session_ended_closed_deviceid_arg0.format(arg0 = device.id))
            }
        }
    }

    fun cancelShare(deviceId: String) {
        // 取消共享轮询任务
        shareJobs.remove(deviceId)?.cancel()
        // 撤销允许连接标记，避免对方继续访问
        allowDeviceShareConnection.remove(deviceId)
        shareGrantExpiryJobs.remove(deviceId)?.cancel()
        deviceCertificateState.removeDeviceToken(deviceId)
        revokeWebRtcBootstrapAuthorization(deviceId)
        shareRequestDevices.remove(deviceId)
        // 清理状态与数据，恢复为未开始
        fileShareState.clearShareToDevice(deviceId)
    }

    fun markShareDisconnected(deviceId: String) {
        // 对端断开后发送端恢复为未分享，不保留 ERROR / 文件列表残留。
        cancelShare(deviceId)
    }

    fun markShareRejected(deviceId: String, message: String = AppStrings.ui_other_party_did_not_receive) {
        cancelShare(deviceId)
        val shareState = fileShareState
        shareState.sendFile[deviceId] = FileShareStatus.REJECTED
        if (message.isNotBlank()) {
            shareState.sendFileMessage[deviceId] = message
        }
    }


    fun connectShare(
        device: SocketDevice,
        action: DeviceShareRequestAction = DeviceShareRequestAction.View,
        savePath: String? = null
    ) {
        mainScope.launch(Dispatchers.Default) {
            if (action == DeviceShareRequestAction.Reject || action == DeviceShareRequestAction.AutoReject) return@launch
            val shouldSave = action == DeviceShareRequestAction.Save || action == DeviceShareRequestAction.AutoSave
            val resolvedSavePath = if (shouldSave) resolveDeviceShareSavePath(device.id, savePath) else ""
            pendingShareConnectionIds.update { ids -> ids + device.id }
            try {
                runDeviceShareConnectionAttempt(
                    block = {
                        DeviceShareTransferCoordinator(
                            connectShare = ::connectShareGrantedSession,
                            loadSharedEntries = ::loadShareEntries,
                            copyEntriesToLocal = ::copyShareEntriesToLocal,
                            openShareDesk = ::openShareDesk,
                            disconnectShare = ::disconnectShareConnection,
                        ).execute(
                            device = device,
                            action = action,
                            savePath = resolvedSavePath,
                        )
                        if (shouldSave) {
                            postDeviceShareSaveNotification(
                                deviceName = device.name,
                                savePath = resolvedSavePath,
                                isAutoSave = action == DeviceShareRequestAction.AutoSave,
                                success = true,
                                errorMessage = null,
                            )
                        }
                    },
                    onFailure = { e ->
                        publishShareConnectionResult(device.id, FileShareStatus.ERROR, e.message.orEmpty())
                        disconnectPendingShareConnection(device.id)
                        if (shouldSave) {
                            postDeviceShareSaveNotification(
                                deviceName = device.name,
                                savePath = resolvedSavePath,
                                isAutoSave = action == DeviceShareRequestAction.AutoSave,
                                success = false,
                                errorMessage = e.message,
                            )
                        } else {
                            postDeviceShareViewFailureNotification(device.name, e.message)
                        }
                        LogKit.e(AppStrings.ui_connection_sharing_failed_arg0.format(arg0 = (e.message).toString()), e)
                    },
                    onCancelled = { disconnectPendingShareConnection(device.id) },
                )
            } finally {
                pendingShareConnectionIds.update { ids -> ids - device.id }
            }
        }
    }

    private suspend fun connectShareGrantedSession(device: SocketDevice): Share {
        val sessionDevice = device.withCopy(
            transportType = DeviceTransportType.Session,
            shareConnectNonce = device.shareConnectNonce,
        )
        var sessionFailure: Throwable? = null
        for (attempt in 0 until 12) {
            val manager = DeviceSessionClientManager(
                registerInDeviceState = false,
                enableMessaging = false,
            )
            try {
                if (manager.connect(sessionDevice)) {
                    shareSessionManagers.remove(device.id)?.disconnect(notifyCancel = false)
                    shareSessionManagers[device.id] = manager
                    startShareSessionHeartbeat(device.id, manager)
                    return device.toShare(
                        ReadOnlyShareSessionAdapter(
                            devicePathClient = manager.pathRouteClient,
                            deviceFileClient = manager.fileRouteClient,
                            active = { manager.isActive },
                            disconnectSession = {
                                closeLanShareSession(device.id, manager)
                            },
                        )
                    )
                }
                manager.disconnect(notifyCancel = false)
            } catch (error: CancellationException) {
                manager.disconnect(notifyCancel = false)
                throw error
            } catch (error: Throwable) {
                manager.disconnect(notifyCancel = false)
                sessionFailure = error
                break
            }
            if (attempt < 11) delay(250.milliseconds)
        }

        val webRtcPeer = (webRtcPeerSessionStates.value + browserWebRtcPeerSessionStates.value)
            .firstOrNull { state -> state.peer.id == device.id }
            ?.peer
        if (webRtcPeer != null) {
            if (sessionFailure != null) {
                // 审批结果由 heartbeat 返回后，发送端才创建 scoped grant；LAN 立即失败时给该信令一次落地时间。
                delay(1_250.milliseconds)
            }
            val webRtcDevice = webRtcPeer.toWebRtcSocketDevice(ConnectType.Loading).withCopy(
                tlsFingerprintSha256 = device.tlsFingerprintSha256,
                shareConnectNonce = device.shareConnectNonce,
            )
            val controller = resolveWebRtcControllerForPeer(device.id)
            controller.requestPeerConnection(
                remoteId = device.id,
                shareNonce = device.shareConnectNonce,
                tlsFingerprintSha256 = getSocketDevice().normalizedTlsFingerprint(),
            )
            return withTimeout((CONNECT_TIMEOUT * 1000L).milliseconds) {
                while (true) {
                    controller.deviceSessionClients(device.id)?.let { clients ->
                        if (controller.isShareSession(device.id)) {
                            return@withTimeout webRtcDevice.toShare(
                                ReadOnlyShareSessionAdapter(
                                    devicePathClient = clients,
                                    deviceFileClient = clients,
                                    active = {
                                        controller.isShareSession(device.id) &&
                                            controller.deviceSessionClients(device.id) != null
                                    },
                                    disconnectSession = {
                                        controller.disconnectPeer(device.id)
                                        true
                                    },
                                )
                            )
                        }
                    }
                    delay(100.milliseconds)
                }
                error(AppStrings.ui_not_connected)
            }
        }
        throw sessionFailure ?: IllegalStateException(AppStrings.ui_not_connected)
    }

    private suspend fun resolveDeviceShareSavePath(deviceId: String, preferredPath: String?): String {
        val requested = preferredPath?.trim().orEmpty()
        if (requested.isNotEmpty()) return requested
        return database.deviceReceiveShareQueries.selectById(deviceId)
            .executeAsOneOrNullAwait()
            ?.path
            .orEmpty()
            .ifBlank { PathUtils.getHomePath() }
    }

    private suspend fun loadShareEntries(share: Share): List<FileSimpleInfo> {
        return share.getRootList().getOrThrow()
    }

    private suspend fun copyShareEntriesToLocal(
        share: Share,
        entries: List<FileSimpleInfo>,
        savePath: String,
    ) {
        val destinationRoot = withContext(Dispatchers.Default) {
            FileUtils.getFile(FileAccessPermission.Allowed, savePath)
        }.getOrThrow()
        check(destinationRoot.isDirectory) { AppStrings.ui_save_path_unavailable }
        val sources = entries.map { entry ->
            entry.withCopy(protocol = FileProtocol.Share, protocolId = share.id)
        }
        // 保存与查看后粘贴走同一条整批入队链路；runtime 要从 shares 解析源，但不打开桌面。
        registerShareSession(share)
        val destinationChildren = withContext(Dispatchers.Default) {
            PathUtils.getFileAndFolder(FileAccessPermission.Allowed, destinationRoot.path)
        }.getOrDefault(emptyList())
        inject<FileState>().value.pasteCopyFilesWithReplace(
            destFileInfo = destinationRoot.withCopy(protocol = FileProtocol.Local, protocolId = ""),
            srcFiles = sources,
            listDestinationChildren = { Result.success(destinationChildren) },
            awaitCompletion = true,
        )
    }

    private fun registerShareSession(share: Share) {
        shares.filter { item -> item.id == share.id && item !== share }.forEach { existing ->
            existing.disconnect()
        }
        if (shares.none { item -> item === share }) {
            shares.removeAll { item -> item.id == share.id }
            shares.add(share)
        }
    }

    private fun openShareDesk(share: Share, entries: List<FileSimpleInfo>) {
        check(entries.all { entry -> entry.protocol == FileProtocol.Share && entry.protocolId == share.id }) {
            AppStrings.ui_failed_to_retrieve
        }
        val fileState = inject<FileState>().value
        registerShareSession(share)
        fileState.updateDesk(
            protocol = FileProtocol.Share,
            type = share,
            pathOverride = "/",
        )
        shareSessionLifecycleJobs.remove(share.id)?.cancel()
        shareSessionLifecycleJobs[share.id] = mainScope.launch {
            while (share.session?.isActive == true) delay(500.milliseconds)
            if (shares.remove(share) && (fileState.deskType.value as? Share) === share) {
                fileState.updateDesk(FileProtocol.Local, Local())
            }
            shareSessionLifecycleJobs.remove(share.id)
        }
    }

    private fun disconnectShareConnection(share: Share) {
        shareSessionLifecycleJobs.remove(share.id)?.cancel()
        share.disconnect()
        shares.remove(share)
        val fileState = inject<FileState>().value
        if ((fileState.deskType.value as? Share) === share) {
            fileState.updateDesk(FileProtocol.Local, Local())
        }
    }

    private fun disconnectPendingShareConnection(deviceId: String) {
        shares.filter { share -> share.id == deviceId }.toList().forEach(::disconnectShareConnection)
        shareSessionManagers.remove(deviceId)?.disconnect(notifyCancel = false)
        shareSessionHeartbeatJobs.remove(deviceId)?.cancel()
        val controller = runCatching { resolveWebRtcControllerForPeer(deviceId) }.getOrNull()
        if (controller?.isShareSession(deviceId) == true) controller.disconnectPeer(deviceId)
    }

    private fun closeLanShareSession(
        deviceId: String,
        manager: DeviceSessionClientManager,
    ): Boolean {
        if (shareSessionManagers[deviceId] === manager) {
            shareSessionManagers.remove(deviceId)
            shareSessionHeartbeatJobs.remove(deviceId)?.cancel()
        }
        return manager.disconnect(notifyCancel = false)
    }

    private fun startShareSessionHeartbeat(
        deviceId: String,
        manager: DeviceSessionClientManager,
    ) {
        shareSessionHeartbeatJobs.remove(deviceId)?.cancel()
        shareSessionHeartbeatJobs[deviceId] = mainScope.launch {
            try {
                coroutineScope {
                    val heartbeat = launch { manager.deviceRouteClient.heartbeat() }
                    try {
                        while (manager.isActive && heartbeat.isActive) delay(100.milliseconds)
                    } finally {
                        heartbeat.cancelAndJoin()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LogKit.w(
                    AppStrings.ui_device_session_heartbeat_failed_consecutive_arg0_message_arg1
                        .format(arg0 = "share", arg1 = (error.message).toString()),
                    error,
                )
            } finally {
                if (shareSessionManagers[deviceId] === manager) {
                    shareSessionManagers.remove(deviceId)
                    shares.filter { share -> share.id == deviceId && share.session?.isActive != true }
                        .toList()
                        .forEach(::disconnectShareConnection)
                }
                shareSessionHeartbeatJobs.remove(deviceId)
            }
        }
    }

    private fun postDeviceShareSaveNotification(
        deviceName: String,
        savePath: String,
        isAutoSave: Boolean,
        success: Boolean,
        errorMessage: String?,
    ) {
        val titleKey = if (success) {
            if (isAutoSave) LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_AUTO_SAVE_SUCCESS_TITLE
            else LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_SAVE_SUCCESS_TITLE
        } else {
            if (isAutoSave) LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_AUTO_SAVE_FAILURE_TITLE
            else LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_SAVE_FAILURE_TITLE
        }
        val args = mapOf("deviceName" to deviceName, "savePath" to savePath)
        val message = if (success) {
            LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_SAVE_SUCCESS_BODY, args)
        } else {
            errorMessage?.takeIf(String::isNotBlank)?.let { reason ->
                LocalizedMessage(
                    LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_SAVE_FAILURE_BODY_WITH_DETAIL,
                    args + ("reason" to reason),
                )
            } ?: LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_SAVE_FAILURE_BODY, args)
        }
        RequestNotificationDispatcher.post(
            notificationState,
            RequestNotificationFactory.buildLocalizedCustomNotification(
                kind = if (isAutoSave) "device_share_auto_save" else "device_share_save",
                localizedTitle = LocalizedMessage(titleKey),
                localizedMessage = message,
                type = if (success) NotificationType.Success else NotificationType.Error,
                localizedCategory = LocalizedMessage(LocalizedMessageKeys.CATEGORY_SHARE),
                config = NotificationFactoryConfig(sendSystemNotification = false),
            ),
        )
    }

    private fun postDeviceShareViewFailureNotification(deviceName: String, errorMessage: String?) {
        val args = mapOf("deviceName" to deviceName)
        val message = errorMessage?.takeIf(String::isNotBlank)?.let { reason ->
            LocalizedMessage(
                LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_VIEW_FAILURE_BODY_WITH_DETAIL,
                args + ("reason" to reason),
            )
        } ?: LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_VIEW_FAILURE_BODY, args)
        RequestNotificationDispatcher.post(
            notificationState,
            RequestNotificationFactory.buildLocalizedCustomNotification(
                kind = "device_share_view",
                localizedTitle = LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_VIEW_FAILURE_TITLE),
                localizedMessage = message,
                type = NotificationType.Error,
                localizedCategory = LocalizedMessage(LocalizedMessageKeys.CATEGORY_SHARE),
                config = notificationConfig,
            ),
        )
    }
}

data class DeviceShareConnectionGrant(
    val deviceId: String,
    val tlsFingerprintSha256: String,
    val nonce: String,
    val expiresAtMillis: Long,
    val allowedPaths: List<DeviceSharePathGrant> = emptyList(),
) {
    val pathScope: DeviceSharePathScope = DeviceSharePathScope(allowedPaths)

    fun matches(device: SocketDevice, requestNonce: String, nowMillis: Long): Boolean {
        if (device.id != deviceId || nowMillis > expiresAtMillis) return false
        val expectedFingerprint = normalizeTlsFingerprintSha256(tlsFingerprintSha256)
        val actualFingerprint = device.normalizedTlsFingerprint()
        return !(expectedFingerprint.isBlank() || actualFingerprint.isBlank() || expectedFingerprint != actualFingerprint) && nonce.constantTimeEquals(requestNonce)
    }
}

data class DeviceShareConnectionResult(
    val status: FileShareStatus,
    val message: String,
)

private fun String.constantTimeEquals(other: String): Boolean {
    val expected = encodeToByteArray()
    val actual = other.encodeToByteArray()
    var diff = expected.size xor actual.size
    val maxSize = maxOf(expected.size, actual.size)
    for (index in 0 until maxSize) {
        val expectedByte = expected.getOrNull(index)?.toInt() ?: 0
        val actualByte = actual.getOrNull(index)?.toInt() ?: 0
        diff = diff or (expectedByte xor actualByte)
    }
    return diff == 0
}

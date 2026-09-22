package com.folderspan.service.session

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.getSocketDevice
import com.folderspan.service.account.ACCOUNT_DEVICE_CONNECT_PURPOSE
import com.folderspan.service.account.AccountDeviceProofContext
import com.folderspan.service.account.AccountDeviceTrustRefreshTrigger
import com.folderspan.service.account.AccountDeviceTrustRegistry
import com.folderspan.service.account.accountDeviceSha256
import com.folderspan.service.account.createAccountDeviceProof
import com.folderspan.service.account.newAccountDeviceNonce
import com.folderspan.service.bookmark.DeviceBookmarkClient
import com.folderspan.service.data.AppendToFileRequest
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.CopyPathControlAction
import com.folderspan.service.data.CopyPathControlRequest
import com.folderspan.service.data.CopyPathProgress
import com.folderspan.service.data.CopyPathRequest
import com.folderspan.service.data.CreateBookmarkRequest
import com.folderspan.service.data.CreateDirectoryRequest
import com.folderspan.service.data.CreateFileRequest
import com.folderspan.service.data.CreateFolderRequest
import com.folderspan.service.data.DeleteBookmarkRequest
import com.folderspan.service.data.DeleteDirectoryRequest
import com.folderspan.service.data.DeleteRequest
import com.folderspan.service.data.DeviceConnectAuthorizationMode
import com.folderspan.service.data.DeviceConnectRequest
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.data.DeviceThemeRequest
import com.folderspan.service.data.DeviceThemeResponse
import com.folderspan.service.data.EmptyRequest
import com.folderspan.service.data.GetFileByPathAndNameRequest
import com.folderspan.service.data.GetFileByPathRequest
import com.folderspan.service.data.ListRequest
import com.folderspan.service.data.PathExistsRequest
import com.folderspan.service.data.ReadFileLinesRequest
import com.folderspan.service.data.RenameInfo
import com.folderspan.service.data.RenameRequest
import com.folderspan.service.data.ReorderBookmarksRequest
import com.folderspan.service.data.SerializableResult
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.data.UpdateBookmarkRequest
import com.folderspan.service.data.resolveRequiredCopyRequestId
import com.folderspan.service.file.ContinuousRangeDeviceFileClient
import com.folderspan.service.file.DeviceFileClient
import com.folderspan.service.http.client.HttpRouteDisconnectReason
import com.folderspan.service.http.client.httpsPortOrFallback
import com.folderspan.service.http.client.normalizedTlsFingerprint
import com.folderspan.service.http.client.shouldBlockChangedTrustedDeviceCertificate
import com.folderspan.service.http.client.toHttpConnectType
import com.folderspan.service.http.tls.DEVICE_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE
import com.folderspan.service.http.tls.TrustedDeviceCertificateStore
import com.folderspan.service.http.tls.createDeviceIdentityProof
import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import com.folderspan.service.message.DeviceMessageClient
import com.folderspan.service.operation.HttpTransferStatus
import com.folderspan.service.http.archive.FolderSpanArchiveReadRequest
import com.folderspan.service.http.archive.FolderSpanArchiveStreamCapabilities
import com.folderspan.service.http.archive.FolderSpanArchiveTransferResult
import com.folderspan.service.http.archive.FolderSpanArchiveWriteRequest
import com.folderspan.service.path.DevicePathClient
import com.folderspan.service.path.DevicePathEntries
import com.folderspan.service.path.normalizeDeviceTraversePath
import com.folderspan.service.path.restoreDevicePathEntries
import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.utils.LogKit
import com.folderspan.utils.ProtoBufCodec
import com.folderspan.utils.SettingsUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import strings.AppStrings

internal suspend fun identifyDeviceSessionEndpoint(host: String, port: Int): SocketDevice {
    return identifyDeviceSessionEndpoint(host, port) { targetHost, targetPort ->
        withContext(Dispatchers.Default) {
            connectUnpinnedDeviceSessionChannel(targetHost, targetPort)
        }
    }
}

internal suspend fun identifyDeviceSessionEndpoint(
    host: String,
    port: Int,
    openConnection: suspend (host: String, port: Int) -> DeviceSessionBootstrapConnection,
): SocketDevice {
    val bootstrap = openConnection(host, port)
    val fingerprint = normalizeTlsFingerprintSha256(bootstrap.peerFingerprintSha256)
    if (fingerprint.isBlank()) {
        bootstrap.channel.close()
        throw IllegalStateException(AppStrings.ui_device_tls_certificate_fingerprint_is_empty)
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sessionPlan = DeviceSessionWindowPlan.fromMemory()
    val session = DeviceSessionPeer(
        transport = DeviceSessionTransport(
            channel = bootstrap.channel,
            sendPlan = sessionPlan,
            receivePlan = sessionPlan,
            isClient = true,
        ),
        receivePlan = sessionPlan,
    )
    return try {
        session.start(scope)
        val device = session.rpcValue<EmptyRequest, SocketDevice>(
            DEVICE_SESSION_RPC_IDENTIFY,
            EmptyRequest(),
        )
        check(device.id.isNotBlank()) { AppStrings.ui_device_id_response_is_missing }
        val advertisedFingerprint = device.normalizedTlsFingerprint()
        check(advertisedFingerprint.isBlank() || advertisedFingerprint == fingerprint) {
            AppStrings.ui_device_identifies_the_certificate_fingerprint_and_tls_handshake_mismatch
        }
        device.withCopy(
            host = host,
            transportType = DeviceTransportType.Session,
            httpsPort = port,
            tlsFingerprintSha256 = fingerprint,
            token = "",
            httpClient = null,
            sessionClient = null,
        )
    } finally {
        runCatching { session.close() }
        scope.cancel()
    }
}

class DeviceSessionClientManager(
    private val registerInDeviceState: Boolean = true,
    private val enableMessaging: Boolean = true,
) : KoinComponent {
    private val deviceState by inject<DeviceState>()
    private val accountDeviceTrustRegistry by inject<AccountDeviceTrustRegistry>()
    private val accountDeviceTrustRefreshTrigger by inject<AccountDeviceTrustRefreshTrigger>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var cancelConnectCallback: (suspend (HttpRouteDisconnectReason) -> Unit)? = null
    private var runtime: DeviceSessionClientRuntime? = null
    private var heartbeatJob: Job? = null
    @Volatile
    private var active = false
    val isActive: Boolean
        get() = active
    internal var deviceMessageClient: DeviceMessageClient? = null
        private set
    lateinit var fileRouteClient: DeviceFileClient
    lateinit var pathRouteClient: DevicePathClient
    lateinit var bookmarkRouteClient: DeviceBookmarkClient
    internal lateinit var deviceRouteClient: DeviceSessionHeartbeatClient

    internal fun onCancelConnect(callback: suspend (HttpRouteDisconnectReason) -> Unit): DeviceSessionClientManager {
        cancelConnectCallback = callback
        return this
    }

    suspend fun connect(
        connectDevice: SocketDevice,
        allowChangedTrustedCertificate: Boolean = false,
        accountDeviceAuthorization: Boolean = false,
    ): Boolean {
        val trustedDevice = DeviceIdentityTrust.shared.resolve(connectDevice)
        if (trustedDevice !== connectDevice && registerInDeviceState) {
            withContext(Dispatchers.Main) {
                upsertSocketDevice(trustedDevice, trustedDevice.connectType, token = "", manager = null)
            }
        }
        return connectTrustedDevice(trustedDevice, allowChangedTrustedCertificate, accountDeviceAuthorization)
    }

    private suspend fun connectTrustedDevice(
        connectDevice: SocketDevice,
        allowChangedTrustedCertificate: Boolean,
        accountDeviceAuthorization: Boolean,
    ): Boolean {
        val tlsFingerprint = connectDevice.normalizedTlsFingerprint()
        LogKit.i(
            AppStrings.ui_device_session_connection_id_arg0_host_arg1.format(arg0 = (connectDevice.id), arg1 = (connectDevice.host)) +
                "${connectDevice.httpsPortOrFallback()} accountAuth=$accountDeviceAuthorization"
        )
        if (tlsFingerprint.isBlank()) {
            LogKit.w(AppStrings.ui_device_session_termination_missing_tls_certificate_fingerprint_arg0.format(arg0 = (connectDevice.id)))
            throw IllegalStateException(AppStrings.ui_device_lacks_tls_certificate_fingerprint_cannot_establish_session_connection)
        }
        if (shouldBlockChangedTrustedDeviceCertificate(
                deviceId = connectDevice.id,
                tlsFingerprint = tlsFingerprint,
                allowChangedTrustedCertificate = allowChangedTrustedCertificate,
            )
        ) {
            LogKit.w(AppStrings.ui_device_session_termination_certificate_fingerprint_does_not_match_id_arg0.format(arg0 = (connectDevice.id)))
            throw IllegalStateException(DEVICE_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE)
        }
        LogKit.i(AppStrings.ui_tls_handshake_host_arg0_port_arg1.format(arg0 = (connectDevice.host), arg1 = (connectDevice.httpsPortOrFallback()).toString()))
        val channel = connectPinnedDeviceSessionChannel(
            host = connectDevice.host,
            port = connectDevice.httpsPortOrFallback(),
            expectedFingerprintSha256 = tlsFingerprint,
        )
        LogKit.i(AppStrings.ui_device_session_tls_complete_id_arg0_alpn_arg1.format(arg0 = (connectDevice.id), arg1 = (DEVICE_SESSION_ALPN)))
        val sessionPlan = DeviceSessionWindowPlan.fromMemory()
        val sessionRuntime = try {
            DeviceSessionClientRuntimeLauncher.start(
                channel = channel,
                scope = scope,
                authentication = DeviceSessionClientAuthenticationContext(
                    peerDeviceId = connectDevice.id,
                    authenticate = { session ->
                        performConnect(session, connectDevice, accountDeviceAuthorization)
                    },
                ),
                messaging = if (enableMessaging) {
                    DeviceSessionClientMessagingContext(
                        endpointRegistry = deviceState.deviceMessageEndpointRegistry,
                        incomingCoordinator = deviceState.incomingDeviceMessageCoordinator,
                    )
                } else {
                    null
                },
                sessionPlan = sessionPlan,
            )
        } catch (error: Throwable) {
            LogKit.e(
                AppStrings.ui_device_session_connection_failed_id_arg0_message_arg1.format(
                    arg0 = connectDevice.id,
                    arg1 = error.message.toString(),
                ),
                error,
            )
            throw error
        }
        runtime = sessionRuntime
        val session = sessionRuntime.peer
        try {
            val sessionConnectResponse = sessionRuntime.connectResponse
            val connectResponse = sessionConnectResponse.connection
            LogKit.i(
                AppStrings.ui_device_session_id_arg0_type_arg1.format(arg0 = (connectDevice.id), arg1 = (connectResponse.connectType).toString()) +
                    "mode=${connectResponse.authorizationMode} tokenLen=${connectResponse.token.length}"
            )
            val resolvedConnectType = connectResponse.connectType.toHttpConnectType()
            if (connectResponse.connectType != DeviceConnectType.APPROVED) {
                LogKit.w(AppStrings.ui_device_session_not_approved_id_arg0_type_arg1.format(arg0 = (connectDevice.id), arg1 = (connectResponse.connectType).toString()))
                if (registerInDeviceState) {
                    withContext(Dispatchers.Main) {
                        upsertSocketDevice(connectDevice, resolvedConnectType, token = "", manager = null)
                    }
                }
                sessionRuntime.close()
                runtime = null
                return false
            }
            TrustedDeviceCertificateStore.save(connectDevice.id, tlsFingerprint)
            if (registerInDeviceState && connectResponse.authorizationMode == DeviceConnectAuthorizationMode.ACCOUNT_DEVICE) {
                deviceState.markAccountDeviceAutoAuthorized(connectDevice.id)
            }
            val negotiatedTransferStatus = sessionRuntime.transferStatus
            LogKit.i(
                AppStrings.ui_device_session_transmission_parameters_id_arg0.format(arg0 = (connectDevice.id)) +
                    "parallel=${negotiatedTransferStatus.maxParallelRequests} " +
                    "chunk=${negotiatedTransferStatus.maxChunkBytes} " +
                    "window=${sessionPlan.sessionWindowBytes}",
            )
            val clients = sessionRuntime.clients
            fileRouteClient = clients
            pathRouteClient = clients
            bookmarkRouteClient = clients
            deviceRouteClient = DeviceSessionHeartbeatClient(
                peer = session,
                onSuccess = {
                    if (registerInDeviceState) deviceState.markHttpDeviceSeen(connectDevice.id)
                },
                onFailure = { handleCancelConnect() },
            )
            deviceMessageClient = sessionRuntime.messageClient
            if (registerInDeviceState) {
                withContext(Dispatchers.Main) {
                    upsertSocketDevice(connectDevice, resolvedConnectType, connectResponse.token, this@DeviceSessionClientManager)
                }
                deviceState.markHttpDeviceSeen(connectDevice.id)
            }
            active = true
            sessionRuntime.sessionJob.invokeOnCompletion { active = false }
            LogKit.i(AppStrings.ui_device_session_is_bound_id_arg0.format(arg0 = (connectDevice.id)))
            return true
        } catch (error: Throwable) {
            LogKit.e(AppStrings.ui_device_session_connection_failed_id_arg0_message_arg1.format(arg0 = (connectDevice.id), arg1 = (error.message).toString()), error)
            deviceMessageClient = null
            active = false
            sessionRuntime.close()
            throw error
        }
    }

    fun disconnect(notifyCancel: Boolean = true): Boolean {
        LogKit.i(AppStrings.ui_device_conversation_ended_notifycancel_arg0.format(arg0 = (notifyCancel).toString()))
        active = false
        heartbeatJob?.cancel()
        if (notifyCancel) {
            scope.launch {
                try {
                    cancelConnectCallback?.invoke(HttpRouteDisconnectReason.Explicit)
                } finally {
                    deviceMessageClient = null
                    if (registerInDeviceState) cleanupConnectedDevice(ConnectType.UnConnect)
                    runtime?.close()
                    runtime = null
                    scope.cancel()
                }
            }
        } else {
            scope.launch {
                try {
                    deviceMessageClient = null
                    runtime?.close()
                } finally {
                    runtime = null
                    scope.cancel()
                }
            }
        }
        return true
    }

    suspend fun ping(): ByteArray {
        return runtime?.peer?.ping(byteArrayOf(1, 2, 3, 4)) ?: throw DeviceSessionClosedException()
    }

    private suspend fun performConnect(
        session: DeviceSessionPeer,
        connectDevice: SocketDevice,
        accountDeviceAuthorization: Boolean,
    ): DeviceSessionConnectResponse {
        val selfDevice = getSocketDevice()
        val featureEnabled = SettingsUtils.fileShare.isAccountDeviceAutoConnectEnabled()
        if (accountDeviceAuthorization && featureEnabled &&
            accountDeviceTrustRegistry.state.value.isNearExpiry(Clock.System.now().toEpochMilliseconds())
        ) {
            accountDeviceTrustRefreshTrigger.refreshIfNeeded(force = false)
        }
        val trustSnapshot = accountDeviceTrustRegistry.state.value
        val nowEpochMillis = Clock.System.now().toEpochMilliseconds()
        val authorizationMode = if (accountDeviceAuthorization) {
            DeviceConnectAuthorizationMode.ACCOUNT_DEVICE
        } else {
            DeviceConnectAuthorizationMode.STANDARD
        }
        val unsignedRequest = DeviceConnectRequest(
            device = selfDevice,
            shareNonce = connectDevice.shareConnectNonce,
            authorizationMode = authorizationMode,
        )
        val unsignedPayloadSha256 = ProtoBufCodec.encode(unsignedRequest).accountDeviceSha256()
        val identityProof = createDeviceIdentityProof(
            targetDeviceId = connectDevice.id,
            targetPath = "/api/devices/connect",
            payloadSha256 = unsignedPayloadSha256,
            nowEpochSeconds = nowEpochMillis / 1_000L,
            deviceId = selfDevice.id,
        )
        val accountProof = if (accountDeviceAuthorization) {
            check(featureEnabled) { AppStrings.ui_account_device_auto_connect_is_disabled }
            check(trustSnapshot.isFresh(nowEpochMillis)) { AppStrings.ui_account_device_trust_list_expired }
            check(trustSnapshot.devices.containsKey(connectDevice.id)) { AppStrings.ui_the_device_is_not_in_the_current_accounts_trusted_list }
            val nonce = newAccountDeviceNonce()
            checkNotNull(
                createAccountDeviceProof(
                    AccountDeviceProofContext(
                        purpose = ACCOUNT_DEVICE_CONNECT_PURPOSE,
                        signerDeviceKey = selfDevice.id,
                        targetDeviceKey = connectDevice.id,
                        targetPath = "/api/devices/connect",
                        nonce = nonce,
                        issuedAtEpochSeconds = nowEpochMillis / 1_000L,
                        payloadSha256 = unsignedPayloadSha256,
                    )
                )
            ) { AppStrings.ui_no_account_device_connection_proof_can_be_generated }
        } else {
            null
        }
        LogKit.i(AppStrings.ui_device_session_connect_rpc_target_arg0_mode_arg1.format(arg0 = (connectDevice.id), arg1 = (authorizationMode).toString()))
        return session.rpcValue(
            DEVICE_SESSION_RPC_CONNECT,
            unsignedRequest.copy(
                accountDeviceProof = accountProof,
                identityProof = identityProof,
            ),
        )
    }

    private suspend fun handleCancelConnect() {
        LogKit.w(AppStrings.ui_device_session_authentication_is_invalid_cleaning_connections)
        active = false
        try {
            cancelConnectCallback?.invoke(HttpRouteDisconnectReason.Unauthorized)
        } finally {
            deviceMessageClient = null
            if (registerInDeviceState) cleanupConnectedDevice(ConnectType.UnConnect)
            runtime?.close()
            runtime = null
            scope.cancel()
        }
    }

    private suspend fun cleanupConnectedDevice(connectType: ConnectType) {
        withContext(Dispatchers.Main) {
            val index = deviceState.socketDevices.indexOfFirst { item ->
                item.sessionClient == this@DeviceSessionClientManager
            }
            if (index >= 0) {
                val socketDevice = deviceState.socketDevices[index]
                deviceState.devices.removeAll { item -> item.id == socketDevice.id }
                deviceState.socketDevices[index] = socketDevice.withCopy(
                    connectType = connectType,
                    token = "",
                    sessionClient = null,
                )
            }
        }
    }

    private fun upsertSocketDevice(
        connectDevice: SocketDevice,
        connectType: ConnectType,
        token: String,
        manager: DeviceSessionClientManager?,
    ) {
        val socketDevice = connectDevice.withCopy(
            connectType = connectType,
            token = token,
            sessionClient = manager,
        )
        val index = deviceState.socketDevices.indexOfFirst { item -> item.id == connectDevice.id }
        if (index >= 0) {
            deviceState.socketDevices[index] = socketDevice
        } else {
            deviceState.socketDevices.add(socketDevice)
        }
        if (socketDevice.connectType == ConnectType.Connect) {
            deviceState.devices.add(socketDevice.toDevice())
        }
    }
}

internal class DeviceSessionHeartbeatClient(
    private val peer: DeviceSessionPeer,
    private val onSuccess: suspend () -> Unit = {},
    private val onFailure: suspend () -> Unit,
) {
    suspend fun getDeviceTheme(isDark: Boolean): Result<DeviceThemeResponse> {
        return runCatching {
            peer.rpcValue(DEVICE_SESSION_RPC_THEME, DeviceThemeRequest(isDark))
        }
    }

    suspend fun heartbeat() {
        var consecutiveFailures = 0
        var lastSuccess = Clock.System.now().toEpochMilliseconds()
        while (currentCoroutineContext().isActive) {
            try {
                peer.ping(Clock.System.now().toEpochMilliseconds().toString().encodeToByteArray())
                consecutiveFailures = 0
                lastSuccess = Clock.System.now().toEpochMilliseconds()
                onSuccess()
                LogKit.d(AppStrings.ui_device_session_heartbeat_successful)
                delay(3_000.milliseconds)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                consecutiveFailures++
                LogKit.w(AppStrings.ui_device_session_heartbeat_failed_consecutive_arg0_message_arg1.format(arg0 = (consecutiveFailures).toString(), arg1 = (error.message).toString()))
                val now = Clock.System.now().toEpochMilliseconds()
                if (consecutiveFailures >= 5 || now - lastSuccess >= 120_000L) {
                    LogKit.e(AppStrings.ui_device_session_heartbeat_stops_and_disconnects_consecutive_arg0.format(arg0 = (consecutiveFailures).toString()), error)
                    onFailure()
                    throw error
                }
                delay((1_000L * consecutiveFailures).coerceAtMost(30_000L).milliseconds)
            }
        }
    }
}

internal fun createDeviceSessionClients(
    peer: DeviceSessionPeer,
    transferStatus: HttpTransferStatus,
    archiveCapabilities: FolderSpanArchiveStreamCapabilities,
): DeviceSessionClients = DeviceSessionClients(
    peer = peer,
    transferStatus = transferStatus,
    archiveCapabilities = archiveCapabilities,
)

internal class DeviceSessionClients(
    private val peer: DeviceSessionPeer,
    private val transferStatus: HttpTransferStatus,
    private val archiveCapabilities: FolderSpanArchiveStreamCapabilities,
) : ContinuousRangeDeviceFileClient, DevicePathClient, DeviceBookmarkClient {
    private data class CopyProgressSubscription(
        val callback: (suspend (CopyPathProgress) -> Unit)?,
    )

    private val copyProgressMutex = Mutex()
    private val copyProgressSubscriptions = mutableMapOf<String, CopyProgressSubscription>()

    override fun transferStatus(): HttpTransferStatus = transferStatus.copy(
        sampledAtMillis = Clock.System.now().toEpochMilliseconds(),
    )

    override suspend fun archiveCapabilities(): FolderSpanArchiveStreamCapabilities = archiveCapabilities

    override suspend fun readArchiveStream(
        request: FolderSpanArchiveReadRequest,
        onChunk: suspend (ByteArray) -> Unit,
    ): Result<Boolean> = runCatching {
        require(archiveCapabilities.supports(request.options)) { "archive stream options are not supported" }
        peer.openArchiveReadStream(request, onChunk)
        true
    }

    override suspend fun writeArchiveStream(
        request: FolderSpanArchiveWriteRequest,
        chunks: Flow<ByteArray>,
    ): Result<FolderSpanArchiveTransferResult> = runCatching {
        check(archiveCapabilities.codecs.isNotEmpty()) { "archive streaming is not supported" }
        peer.openArchiveWriteStream(request, chunks)
    }

    override suspend fun renames(renameInfos: List<RenameInfo>): Result<List<Result<Boolean>>> {
        return batchBooleanRpcResult(DEVICE_SESSION_RPC_RENAME, RenameRequest(renameInfos))
    }

    override suspend fun createFolders(paths: List<String>): Result<List<Result<Boolean>>> {
        return batchBooleanRpcResult(DEVICE_SESSION_RPC_CREATE_FOLDERS, CreateFolderRequest(paths))
    }

    override suspend fun createFiles(paths: List<String>): Result<List<Result<Boolean>>> {
        return batchBooleanRpcResult(DEVICE_SESSION_RPC_CREATE_FILES, CreateFileRequest(paths))
    }

    override suspend fun deletes(paths: List<String>): Result<List<Result<Boolean>>> {
        return batchBooleanRpcResult(DEVICE_SESSION_RPC_DELETE, DeleteRequest(paths))
    }

    private suspend inline fun <reified Request> batchBooleanRpcResult(
        method: String,
        request: Request,
    ): Result<List<Result<Boolean>>> {
        return peer.rpcResult<Request, List<SerializableResult>>(method, request).toBooleanBatchResult()
    }

    override suspend fun writeRangeStream(
        path: String,
        fileSize: Long,
        startOffset: Long,
        endOffset: Long,
        chunks: Flow<ByteArray>,
    ): Result<Boolean> {
        return runCatching {
            peer.openWriteStream(path, fileSize, startOffset, endOffset, chunks)
            true
        }
    }

    override suspend fun readStream(
        path: String,
        startOffset: Long,
        endOffset: Long,
        onChunk: suspend (ByteArray) -> Unit,
    ): Result<Boolean> {
        return runCatching {
            peer.openReadStream(path, startOffset, endOffset, onChunk)
            true
        }
    }

    override suspend fun writeBytes(
        fileSize: Long,
        blockIndex: Long,
        blockLength: Long,
        path: String,
        byteArray: ByteArray,
        startOffset: Long,
    ): Result<Boolean> {
        return runCatching {
            require(blockLength == byteArray.size.toLong()) { "blockLength does not match byteArray size" }
            require(
                fileSize >= 0L &&
                    startOffset >= 0L &&
                    blockLength in 0L..fileSize &&
                    startOffset <= fileSize - blockLength
            ) {
                "invalid device session write range"
            }
            peer.openWriteStream(
                path = path,
                fileSize = fileSize,
                startOffset = startOffset,
                endOffset = startOffset + blockLength,
                data = flow { emit(byteArray) },
            )
            true
        }
    }

    override suspend fun readBytes(path: String, startOffset: Long, endOffset: Long): Result<ByteArray> {
        return runCatching {
            val chunks = ArrayList<ByteArray>()
            peer.openReadStream(path, startOffset, endOffset) { chunk -> chunks += chunk }
            val size = chunks.sumOf { item -> item.size }
            val result = ByteArray(size)
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(result, destinationOffset = offset)
                offset += chunk.size
            }
            result
        }
    }

    override suspend fun getFileByPath(path: String): Result<FileSimpleInfo> {
        return peer.rpcResult(DEVICE_SESSION_RPC_GET_FILE_BY_PATH, GetFileByPathRequest(path))
    }

    override suspend fun getFileByPathAndName(path: String, name: String): Result<FileSimpleInfo> {
        return peer.rpcResult(DEVICE_SESSION_RPC_GET_FILE_BY_PATH_AND_NAME, GetFileByPathAndNameRequest(path, name))
    }

    override suspend fun getFileInfoByPath(path: String): Result<FileInfo> {
        return peer.rpcResult(DEVICE_SESSION_RPC_GET_FILE_INFO_BY_PATH, GetFileByPathRequest(path))
    }

    override suspend fun getFileInfoByPathAndName(path: String, name: String): Result<FileInfo> {
        return peer.rpcResult(DEVICE_SESSION_RPC_GET_FILE_INFO_BY_PATH_AND_NAME, GetFileByPathAndNameRequest(path, name))
    }

    override suspend fun readFileLines(path: String): Result<List<String>> {
        return peer.rpcResult(DEVICE_SESSION_RPC_READ_FILE_LINES, ReadFileLinesRequest(path))
    }

    override suspend fun appendToFile(path: String, content: String): Result<Boolean> {
        return peer.rpcResult(DEVICE_SESSION_RPC_APPEND_TO_FILE, AppendToFileRequest(path, content))
    }

    override suspend fun copyPath(
        srcPath: String,
        destPath: String,
        onProgress: (suspend (CopyPathProgress) -> Unit)?,
        requestId: String?,
    ): Result<Boolean> {
        val resolvedRequestId = resolveRequiredCopyRequestId(requestId)
        val registered = copyProgressMutex.withLock {
            if (copyProgressSubscriptions.containsKey(resolvedRequestId)) {
                false
            } else {
                copyProgressSubscriptions[resolvedRequestId] = CopyProgressSubscription(onProgress)
                true
            }
        }
        if (!registered) {
            return Result.failure(
                IllegalStateException("device session copy request is already active: $resolvedRequestId"),
            )
        }
        return try {
            peer.rpcResult(
                DEVICE_SESSION_RPC_COPY_PATH,
                CopyPathRequest(srcPath, destPath, resolvedRequestId),
            )
        } finally {
            copyProgressMutex.withLock {
                copyProgressSubscriptions.remove(resolvedRequestId)
            }
        }
    }

    internal suspend fun handleIncomingRequest(
        request: DeviceSessionControlRequest,
    ): DeviceSessionControlResponse? {
        if (request.method != DEVICE_SESSION_RPC_COPY_PROGRESS) return null
        val event = runCatching {
            ProtoBufCodec.decode<DeviceSessionCopyProgressEvent>(request.payload)
        }.getOrElse { error ->
            return DeviceSessionControlResponse(
                requestId = request.requestId,
                status = DEVICE_SESSION_RPC_BAD_REQUEST,
                errorMessage = error.message.orEmpty(),
            )
        }
        val subscription = copyProgressMutex.withLock {
            copyProgressSubscriptions[event.requestId]
        } ?: return DeviceSessionControlResponse(
            requestId = request.requestId,
            status = DEVICE_SESSION_RPC_NOT_FOUND,
            errorMessage = "unknown device session copy request",
        )
        subscription.callback?.invoke(event.progress)
        return DeviceSessionControlResponse(
            requestId = request.requestId,
            status = DEVICE_SESSION_RPC_OK,
        )
    }

    override suspend fun controlCopy(requestId: String, action: CopyPathControlAction): Result<Boolean> {
        return peer.rpcResult(DEVICE_SESSION_RPC_COPY_CONTROL, CopyPathControlRequest(requestId, action))
    }

    override suspend fun getRootPaths(requestId: String?, batchId: String?): Result<List<com.folderspan.data.file.PathInfo>> {
        return peer.rpcResult(DEVICE_SESSION_RPC_ROOT_PATHS, EmptyRequest())
    }

    override suspend fun listPath(
        request: ListRequest,
        requestId: String?,
        batchId: String?,
    ): Result<List<FileSimpleInfo>> {
        return peer.rpcResult<ListRequest, DevicePathEntries>(
            DEVICE_SESSION_RPC_LIST_PATH,
            request,
        ).map { entries ->
            restoreDevicePathEntries(request.path, entries)
        }
    }

    override fun traversePath(
        path: String,
        requestId: String?,
        batchId: String?,
    ): Flow<Result<List<FileSimpleInfo>>> = channelFlow {
        send(listPath(ListRequest(normalizeDeviceTraversePath(path)), requestId, batchId))
    }

    override suspend fun exists(path: String): Result<Boolean> {
        return peer.rpcResult(DEVICE_SESSION_RPC_PATH_EXISTS, PathExistsRequest(path))
    }

    override suspend fun createDirectory(path: String): Result<Boolean> {
        return peer.rpcResult(DEVICE_SESSION_RPC_CREATE_DIRECTORY, CreateDirectoryRequest(path))
    }

    override suspend fun deleteDirectory(path: String): Result<Boolean> {
        return peer.rpcResult(DEVICE_SESSION_RPC_DELETE_DIRECTORY, DeleteDirectoryRequest(path))
    }

    override suspend fun getBookmarks(): Result<List<DrawerBookmark>> {
        return peer.rpcResult(DEVICE_SESSION_RPC_BOOKMARK_LIST, EmptyRequest())
    }

    override suspend fun createBookmark(
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String,
    ): Result<Boolean> {
        return peer.rpcResult(DEVICE_SESSION_RPC_BOOKMARK_CREATE, CreateBookmarkRequest(name, path, iconType, iconPath))
    }

    override suspend fun updateBookmark(
        id: Long,
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String,
    ): Result<Boolean> {
        return peer.rpcResult(DEVICE_SESSION_RPC_BOOKMARK_UPDATE, UpdateBookmarkRequest(id, name, path, iconType, iconPath))
    }

    override suspend fun deleteBookmark(id: Long): Result<Boolean> {
        return peer.rpcResult(DEVICE_SESSION_RPC_BOOKMARK_DELETE, DeleteBookmarkRequest(id))
    }

    override suspend fun reorderBookmarks(orderedIds: List<Long>): Result<Boolean> {
        return peer.rpcResult(DEVICE_SESSION_RPC_BOOKMARK_REORDER, ReorderBookmarksRequest(orderedIds))
    }
}

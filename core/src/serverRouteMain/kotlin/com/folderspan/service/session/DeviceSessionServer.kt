package com.folderspan.service.session

import com.folderspan.exception.AuthorityException
import com.folderspan.extensions.randomString
import com.folderspan.getSocketDevice
import com.folderspan.routes.RawHttpApiDispatcher
import com.folderspan.routes.evaluateDeviceConnect
import com.folderspan.service.data.AppendToFileRequest
import com.folderspan.service.data.CopyPathControlRequest
import com.folderspan.service.data.CopyPathRequest
import com.folderspan.service.data.CreateBookmarkRequest
import com.folderspan.service.data.CreateDirectoryRequest
import com.folderspan.service.data.CreateFileRequest
import com.folderspan.service.data.CreateFolderRequest
import com.folderspan.service.data.DeleteBookmarkRequest
import com.folderspan.service.data.DeleteDirectoryRequest
import com.folderspan.service.data.DeleteRequest
import com.folderspan.service.data.DeviceConnectRequest
import com.folderspan.service.data.DeviceConnectResponse
import com.folderspan.service.data.DeviceConnectAuthorizationMode
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.service.data.DeviceThemeRequest
import com.folderspan.service.data.DeviceThemeResponse
import com.folderspan.service.data.EmptyRequest
import com.folderspan.service.data.GetFileByPathAndNameRequest
import com.folderspan.service.data.GetFileByPathRequest
import com.folderspan.service.data.ListRequest
import com.folderspan.service.data.PathExistsRequest
import com.folderspan.service.data.ReadBytesRequest
import com.folderspan.service.data.ReadFileLinesRequest
import com.folderspan.service.data.RenameRequest
import com.folderspan.service.data.ReorderBookmarksRequest
import com.folderspan.service.data.SerializableResult
import com.folderspan.service.data.UpdateBookmarkRequest
import com.folderspan.service.data.WriteBytesStreamRequest
import com.folderspan.service.data.toSerializableResult
import com.folderspan.service.message.DeviceMessageEndpointIdentity
import com.folderspan.service.message.DeviceMessageLiveEndpoint
import com.folderspan.service.message.DeviceMessageTransport
import com.folderspan.service.message.newDeviceMessageId
import com.folderspan.service.http.archive.ArchiveEntryExtractor
import com.folderspan.service.http.archive.ArchiveStreamDecoder
import com.folderspan.service.http.archive.ArchiveStreamEncoder
import com.folderspan.service.http.archive.FOLDER_SPAN_ARCHIVE_BUFFER_BYTES
import com.folderspan.service.http.archive.FOLDER_SPAN_ARCHIVE_MAX_ENTRIES
import com.folderspan.service.http.archive.FOLDER_SPAN_ARCHIVE_MAX_PAYLOAD_BYTES
import com.folderspan.service.http.archive.FolderSpanArchiveEntryRequest
import com.folderspan.service.http.archive.FolderSpanArchiveReadRequest
import com.folderspan.service.http.archive.FolderSpanArchiveStreamCapabilities
import com.folderspan.service.http.archive.FolderSpanArchiveStreamException
import com.folderspan.service.http.archive.FolderSpanArchiveTransferResult
import com.folderspan.service.http.archive.FolderSpanArchiveWriteRequest
import com.folderspan.ui.state.device.DeviceTokenFingerprint
import com.folderspan.ui.theme.getDefaultColorScheme
import com.folderspan.ui.theme.toSerializable
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import com.folderspan.utils.ProtoBufCodec
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.executeAsOneOrNullAwait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.TimeSource
import strings.AppStrings
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.routes.resolveAutoAuthorizeRoleId

private const val DEVICE_SESSION_FILE_READ_BLOCK_BYTES = 256 * 1024

internal sealed interface DeviceSessionServerApprovalContext {
    data object Standard : DeviceSessionServerApprovalContext

    class Preapproved(
        val authorize: suspend (
            request: DeviceConnectRequest,
            fingerprint: DeviceTokenFingerprint,
        ) -> DeviceConnectResponse,
    ) : DeviceSessionServerApprovalContext
}

internal object DeviceSessionServerRuntimeLauncher {
    suspend fun run(
        dispatcher: RawHttpApiDispatcher,
        channel: DeviceSessionByteChannel,
        remoteHost: String?,
        scope: CoroutineScope,
        approvalContext: DeviceSessionServerApprovalContext,
        onAuthenticated: suspend () -> Unit = {},
    ) {
        DeviceSessionServer(
            dispatcher = dispatcher,
            channel = channel,
            remoteHost = remoteHost,
            scope = scope,
            approvalContext = approvalContext,
            onAuthenticated = onAuthenticated,
        ).run()
    }
}

internal actual fun createWebRtcDeviceSessionServerLauncher(): WebRtcDeviceSessionServerLauncher? =
    WebRtcDeviceSessionServerLauncher { channel, remoteDeviceId, connectionAttemptId, scope, onAuthenticated ->
        val dispatcher = RawHttpApiDispatcher()
        DeviceSessionServerRuntimeLauncher.run(
            dispatcher = dispatcher,
            channel = channel,
            remoteHost = null,
            scope = scope,
            approvalContext = DeviceSessionServerApprovalContext.Preapproved { request, fingerprint ->
                dispatcher.authorizeWebRtcPreapprovedSession(
                    request = request,
                    fingerprint = fingerprint,
                    expectedRemoteDeviceId = remoteDeviceId,
                    expectedConnectionAttemptId = connectionAttemptId,
                )
            },
            onAuthenticated = onAuthenticated,
        )
    }

private suspend fun RawHttpApiDispatcher.authorizeWebRtcPreapprovedSession(
    request: DeviceConnectRequest,
    fingerprint: DeviceTokenFingerprint,
    expectedRemoteDeviceId: String,
    expectedConnectionAttemptId: String,
): DeviceConnectResponse {
    val authorization = request.bootstrapAuthorization ?: return rejectedWebRtcSessionConnect()
    if (
        request.authorizationMode != DeviceConnectAuthorizationMode.STANDARD ||
        request.accountDeviceProof != null ||
        request.device.id != expectedRemoteDeviceId
    ) {
        return rejectedWebRtcSessionConnect()
    }
    val configuredRoleId = database.deviceConnectQueries
        .queryByIdAndCategory(request.device.id, DeviceCategory.SERVER)
        .executeAsOneOrNullAwait()
        ?.roleId
        ?.takeIf { roleId -> roleId > 0L }
    val currentRoleId = configuredRoleId ?: resolveAutoAuthorizeRoleId(
        SettingsUtils.getLong(SettingsUtils.KEY_FILE_SHARE_AUTO_AUTHORIZE_ROLE_ID, 2L),
    ) ?: return rejectedWebRtcSessionConnect()
    val result = deviceState.deviceSessionBootstrapAuthorizationRegistry.consume(
        authorization = authorization,
        initiatorDeviceId = request.device.id,
        targetDeviceId = getSocketDevice().id,
        connectionAttemptId = expectedConnectionAttemptId,
        currentRoleId = currentRoleId,
    )
    val grant = (result as? DeviceSessionBootstrapAuthorizationConsumeResult.Granted)?.grant
        ?: return rejectedWebRtcSessionConnect()
    val token = 32.randomString()
    deviceState.registerRemoteDeviceConnection(
        deviceId = request.device.id,
        token = token,
        roleId = grant.roleId,
        fingerprint = fingerprint,
    )
    grant.sharePathScope?.let { scope ->
        deviceCertificateState.setDeviceSharePathScope(token, scope)
    }
    return DeviceConnectResponse(
        connectType = DeviceConnectType.APPROVED,
        token = token,
        authorizationMode = DeviceConnectAuthorizationMode.STANDARD,
    )
}

private fun rejectedWebRtcSessionConnect() = DeviceConnectResponse(
    connectType = DeviceConnectType.REJECTED,
    token = "",
    authorizationMode = DeviceConnectAuthorizationMode.STANDARD,
)

internal class DeviceSessionServer(
    private val dispatcher: RawHttpApiDispatcher,
    private val channel: DeviceSessionByteChannel,
    private val remoteHost: String?,
    private val scope: CoroutineScope,
    private val approvalContext: DeviceSessionServerApprovalContext,
    private val onAuthenticated: suspend () -> Unit,
) {
    private val sessionPlan = DeviceSessionWindowPlan.fromMemory()
    private val writeLocks = HashMap<String, Mutex>()
    private val writeLocksMutex = Mutex()
    private val incomingWritesMutex = Mutex()
    private val fileStreamSlots = Semaphore(sessionPlan.maxFileStreams)
    private val transport = DeviceSessionTransport(
        channel = channel,
        sendPlan = sessionPlan,
        receivePlan = sessionPlan,
        isClient = false,
        onPing = {
            boundDeviceId?.let { deviceId -> dispatcher.deviceState.markRemoteDeviceConnected(deviceId) }
        },
    )
    private val controlEndpoint = DeviceSessionControlEndpoint(
        connection = transport.connection,
        role = DeviceSessionEndpointRole.Server,
        scope = scope,
        requestHandler = DeviceSessionRequestHandler(::dispatchRpc),
        onResponseSent = { _, response ->
            if (response.status == DEVICE_SESSION_RPC_UNAUTHORIZED && boundToken != null) {
                LogKit.w(AppStrings.ui_device_session_denied_sending_goaway_remote_arg0.format(arg0 = (remoteHost).toString()))
                transport.connection.goAway()
            }
        },
    )
    private val incomingWrites = HashMap<Int, IncomingWrite>()
    private val outgoingStreams = HashMap<Int, Job>()
    private val outgoingStreamsMutex = Mutex()
    private val incomingMessageStreams = HashMap<Int, DeviceSessionIncomingStream>()
    private val controlAssembler = DeviceSessionControlAssembler()
    private var messageHandler: DeviceSessionMessageHandler? = null
    private var messageEndpointIdentity: DeviceMessageEndpointIdentity? = null
    @Volatile
    private var boundToken: String? = null
    @Volatile
    private var boundDeviceId: String? = null

    suspend fun run() {
        LogKit.i(AppStrings.ui_device_session_server_start_remote_arg0.format(arg0 = (remoteHost).toString()))
        val job = transport.start(scope)
        try {
            for (frame in transport.connection.incomingEvents) {
                handleFrame(frame)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            LogKit.e("device session server failed: ${error.message}", error)
            runCatching { transport.connection.goAway() }
        } finally {
            LogKit.i(AppStrings.ui_device_session_server_ends_remote_arg0_device_arg1.format(arg0 = (remoteHost).toString(), arg1 = (boundDeviceId).toString()))
            cleanupMessageEndpoint()
            boundToken?.let(dispatcher.deviceCertificateState::removeToken)
            controlEndpoint.close()
            transport.close()
            job.cancel()
        }
    }

    private suspend fun handleFrame(frame: DeviceSessionFrame) {
        when (frame.type) {
            DeviceSessionFrameType.Data -> {
                if (frame.streamId == DEVICE_SESSION_CONTROL_STREAM_ID) {
                    val messages = try {
                        controlAssembler.push(frame.payload)
                    } catch (error: DeviceSessionIoException) {
                        LogKit.w(AppStrings.ui_device_session_control_message_assembly_failed_arg0.format(arg0 = (error.message).toString()))
                        emptyList()
                    }
                    messages.forEach { message -> handleControl(message) }
                } else {
                    val write = incomingWritesMutex.withLock { incomingWrites[frame.streamId] }
                    if (write != null) {
                        if (!write.enqueue(frame.payload)) {
                            incomingWritesMutex.withLock { incomingWrites.remove(frame.streamId) }
                            runCatching { transport.connection.sendRst(frame.streamId) }
                        }
                    } else {
                        val messageStream = incomingMessageStreams[frame.streamId]
                        if (messageStream != null) {
                            try {
                                messageStream.onData(frame.payload)
                                transport.connection.sendWindowUpdate(frame.streamId, frame.payload.size)
                            } catch (error: Throwable) {
                                incomingMessageStreams.remove(frame.streamId)
                                messageStream.onReset()
                                transport.connection.sendRst(frame.streamId)
                            }
                        }
                    }
                }
            }
            DeviceSessionFrameType.Open -> handleOpen(frame)
            DeviceSessionFrameType.Trailer -> {
                LogKit.d(AppStrings.ui_device_session_receives_trailer_stream_arg0.format(arg0 = (frame.streamId).toString()))
                incomingWritesMutex.withLock { incomingWrites.remove(frame.streamId) }?.let { write ->
                    write.closeInput()
                    runCatching { write.completed.await() }
                        .onFailure { error ->
                            LogKit.w(
                                AppStrings.ui_device_session_write_stream_finalize_failed_arg0_message_arg1.format(arg0 = (frame.streamId).toString(), arg1 = (error.message).toString()),
                            )
                        }
                }
                incomingMessageStreams.remove(frame.streamId)?.onTrailer(frame.payload)
            }
            DeviceSessionFrameType.Rst -> {
                LogKit.w(AppStrings.ui_device_session_receives_rst_stream_arg0.format(arg0 = (frame.streamId).toString()))
                incomingWritesMutex.withLock { incomingWrites.remove(frame.streamId) }?.closeInput(
                    DeviceSessionIoException("stream reset: ${frame.streamId}"),
                )
                incomingMessageStreams.remove(frame.streamId)?.onReset()
                outgoingStreamsMutex.withLock { outgoingStreams.remove(frame.streamId) }?.cancel()
            }
            DeviceSessionFrameType.GoAway -> {
                LogKit.w(AppStrings.ui_device_session_receives_goaway_remote_arg0.format(arg0 = (remoteHost).toString()))
                transport.close()
            }
            else -> Unit
        }
    }

    private suspend fun handleControl(payload: ByteArray) {
        controlEndpoint.handle(payload)
    }

    private suspend fun dispatchRpc(request: DeviceSessionControlRequest): DeviceSessionControlResponse {
        if (!isDeviceSessionRpcAllowedBeforeAuthorization(request.method)) {
            val token = boundToken
            if (token.isNullOrBlank() || !dispatcher.deviceCertificateState.isTokenValid(token)) {
                return DeviceSessionControlResponse(
                    requestId = request.requestId,
                    status = DEVICE_SESSION_RPC_UNAUTHORIZED,
                    errorMessage = AppStrings.ui_unauthorized,
                )
            }
            if (
                dispatcher.deviceCertificateState.getDeviceSharePathScope(token) != null &&
                !isDeviceShareSessionRpcAllowed(request.method)
            ) {
                return DeviceSessionControlResponse(
                    requestId = request.requestId,
                    status = DEVICE_SESSION_RPC_FORBIDDEN,
                    errorMessage = AppStrings.error_path_access_denied,
                )
            }
            dispatcher.deviceState.markRemoteDeviceConnected(boundDeviceId.orEmpty())
        }
        return when (request.method) {
            DEVICE_SESSION_RPC_IDENTIFY -> ok(request, ProtoBufCodec.encode(getSocketDevice()))
            DEVICE_SESSION_RPC_CONNECT -> handleConnect(request)
            DEVICE_SESSION_RPC_THEME -> ok(request, ProtoBufCodec.encode(handleTheme(request.payload)))
            DEVICE_SESSION_RPC_LIST_PATH -> result(request, dispatcher.pathService.list(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_ROOT_PATHS -> result(request, dispatcher.pathService.rootPaths(requireToken()))
            DEVICE_SESSION_RPC_PATH_EXISTS -> result(request, dispatcher.pathService.exists(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_CREATE_DIRECTORY -> result(request, dispatcher.pathService.createDirectory(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_DELETE_DIRECTORY -> result(request, dispatcher.pathService.deleteDirectory(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_CREATE_FOLDERS,
            DEVICE_SESSION_RPC_CREATE_EMPTY_DIRECTORIES -> batchBooleanResult(request, dispatcher.fileService.createFolders(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_CREATE_FILES -> batchBooleanResult(request, dispatcher.fileService.createFiles(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_RENAME -> batchBooleanResult(request, dispatcher.fileService.renames(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_DELETE -> batchBooleanResult(request, dispatcher.fileService.deletes(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_COPY_PATH -> handleCopy(request)
            DEVICE_SESSION_RPC_COPY_CONTROL -> result(request, dispatcher.fileCopyService.controlCopy(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_GET_FILE_BY_PATH -> result(request, dispatcher.fileService.getFileByPath(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_GET_FILE_INFO_BY_PATH -> result(request, dispatcher.fileService.getFileInfoByPath(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_GET_FILE_BY_PATH_AND_NAME -> result(request, dispatcher.fileService.getFileByPathAndName(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_GET_FILE_INFO_BY_PATH_AND_NAME -> result(request, dispatcher.fileService.getFileInfoByPathAndName(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_READ_FILE_LINES -> result(request, dispatcher.fileService.readFileLines(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_APPEND_TO_FILE -> result(request, dispatcher.fileService.appendToFile(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_BOOKMARK_LIST -> result(request, dispatcher.bookmarkService.getBookmarks(requireToken()))
            DEVICE_SESSION_RPC_BOOKMARK_CREATE -> result(request, dispatcher.bookmarkService.createBookmark(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_BOOKMARK_UPDATE -> result(request, dispatcher.bookmarkService.updateBookmark(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_BOOKMARK_DELETE -> result(request, dispatcher.bookmarkService.deleteBookmark(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_BOOKMARK_REORDER -> result(request, dispatcher.bookmarkService.reorderBookmarks(requireToken(), ProtoBufCodec.decode(request.payload)))
            DEVICE_SESSION_RPC_BEGIN_MESSAGE,
            DEVICE_SESSION_RPC_COMMIT_MESSAGE -> messageHandler?.handle(request) ?: DeviceSessionControlResponse(
                requestId = request.requestId,
                status = DEVICE_SESSION_RPC_UNAUTHORIZED,
                errorMessage = AppStrings.ui_unauthorized,
            )
            else -> DeviceSessionControlResponse(
                requestId = request.requestId,
                status = DEVICE_SESSION_RPC_NOT_FOUND,
                errorMessage = request.method,
            )
        }
    }

    private suspend fun handleConnect(request: DeviceSessionControlRequest): DeviceSessionControlResponse {
        val body = ProtoBufCodec.decode<DeviceConnectRequest>(request.payload)
        val fingerprint = DeviceTokenFingerprint(
            deviceId = body.device.id,
            clientIp = remoteHost,
            userAgent = DEVICE_SESSION_ALPN,
        )
        val evaluatedResponse = when (val context = approvalContext) {
            DeviceSessionServerApprovalContext.Standard -> dispatcher.evaluateDeviceConnect(
                body = body,
                fingerprint = fingerprint,
                allowShareSessionAuthorization = true,
            )
            is DeviceSessionServerApprovalContext.Preapproved -> context.authorize(body, fingerprint)
        }
        val response = if (
            body.shareNonce.isNotBlank() &&
            evaluatedResponse.token.isNotBlank() &&
            dispatcher.deviceCertificateState.getDeviceSharePathScope(evaluatedResponse.token) == null
        ) {
            dispatcher.deviceCertificateState.removeToken(evaluatedResponse.token)
            evaluatedResponse.copy(
                connectType = DeviceConnectType.REJECTED,
                token = "",
            )
        } else {
            evaluatedResponse
        }
        if (response.token.isNotBlank()) {
            boundToken = response.token
            boundDeviceId = body.device.id
            if (dispatcher.deviceCertificateState.getDeviceSharePathScope(response.token) == null) {
                registerMessageEndpoint(body.device.id, response.token)
            }
            onAuthenticated()
            LogKit.i(AppStrings.ui_device_is_bound_to_token_device_arg0_remote_arg1.format(arg0 = (body.device.id), arg1 = (remoteHost).toString()))
        } else {
            LogKit.w(AppStrings.ui_device_session_connect_token_missing_device_arg0_type_arg1.format(arg0 = (body.device.id), arg1 = (response.connectType).toString()))
        }
        val sessionResponse = DeviceSessionConnectResponse(
            connection = response,
            maxFileStreams = sessionPlan.maxFileStreams,
            recommendedChunkBytes = sessionPlan.recommendedChunkBytes(),
            archiveCapabilities = FolderSpanArchiveStreamCapabilities.local(),
        )
        return DeviceSessionControlResponse(
            requestId = request.requestId,
            status = DEVICE_SESSION_RPC_OK,
            payload = ProtoBufCodec.encode(sessionResponse),
        )
    }

    private fun handleTheme(payload: ByteArray): DeviceThemeResponse {
        val body = ProtoBufCodec.decode<DeviceThemeRequest>(payload)
        val colorScheme = dispatcher.mainState.currentColorSchemes.value?.let { item ->
            if (body.isDark) item.dark else item.light
        } ?: getDefaultColorScheme(body.isDark)
        return DeviceThemeResponse(colorScheme.toSerializable())
    }

    private suspend fun handleCopy(request: DeviceSessionControlRequest): DeviceSessionControlResponse {
        val body = ProtoBufCodec.decode<CopyPathRequest>(request.payload)
        val result = dispatcher.fileCopyService.copyPath(requireToken(), body) { progress ->
            val response = controlEndpoint.rpc(
                DEVICE_SESSION_RPC_COPY_PROGRESS,
                ProtoBufCodec.encode(
                    DeviceSessionCopyProgressEvent(
                        requestId = body.requestId,
                        progress = progress,
                    ),
                ),
            )
            if (!response.isSuccess) {
                throw DeviceSessionRpcException(
                    status = response.status,
                    message = response.errorMessage.ifBlank { DEVICE_SESSION_RPC_COPY_PROGRESS },
                )
            }
        }
        return result(request, result)
    }

    private suspend fun handleOpen(frame: DeviceSessionFrame) {
        val open = runCatching { DeviceSessionProtocol.decodeStreamOpen(frame.payload) }.getOrNull() ?: run {
            LogKit.w(AppStrings.ui_device_session_open_cannot_decode_stream_arg0.format(arg0 = (frame.streamId).toString()))
            transport.connection.sendRst(frame.streamId)
            return
        }
        val token = boundToken
        if (token.isNullOrBlank() || !dispatcher.deviceCertificateState.isTokenValid(token)) {
            LogKit.w(AppStrings.ui_device_session_open_unauthorized_stream_arg0_kind_arg1.format(arg0 = (frame.streamId).toString(), arg1 = (open.kind)))
            transport.connection.sendRst(frame.streamId)
            return
        }
        val shareScope = dispatcher.deviceCertificateState.getDeviceSharePathScope(token)
        val resolvedOpen = if (shareScope != null) {
            resolveDeviceShareSessionStreamOpen(open, shareScope) ?: run {
                LogKit.w(AppStrings.ui_device_session_open_unauthorized_stream_arg0_kind_arg1.format(arg0 = (frame.streamId).toString(), arg1 = (open.kind)))
                transport.connection.sendRst(frame.streamId)
                return
            }
        } else {
            open
        }
        LogKit.i(AppStrings.ui_device_session_stream_arg0_kind_arg1_path_arg2_size_arg3_offset_arg4.format(arg0 = (frame.streamId).toString(), arg1 = (resolvedOpen.kind), arg2 = (resolvedOpen.path), arg3 = (resolvedOpen.fileSize).toString(), arg4 = (resolvedOpen.startOffset).toString()))
        when (resolvedOpen.kind) {
            DEVICE_SESSION_STREAM_WRITE -> startFileStreamOrReject(frame.streamId, resolvedOpen.kind) {
                startWrite(frame.streamId, token, resolvedOpen)
            }
            DEVICE_SESSION_STREAM_READ -> startFileStreamOrReject(frame.streamId, resolvedOpen.kind) {
                startRead(frame.streamId, token, resolvedOpen)
            }
            DEVICE_SESSION_STREAM_ARCHIVE_READ -> startFileStreamOrReject(frame.streamId, resolvedOpen.kind) {
                startArchiveRead(frame.streamId, token, resolvedOpen)
            }
            DEVICE_SESSION_STREAM_ARCHIVE_WRITE -> startFileStreamOrReject(frame.streamId, resolvedOpen.kind) {
                startArchiveWrite(frame.streamId, token, resolvedOpen)
            }
            DEVICE_SESSION_STREAM_MANIFEST -> {
                transport.connection.sendTrailer(frame.streamId)
            }
            DEVICE_SESSION_STREAM_MESSAGE_BODY -> {
                val incoming = messageHandler?.open(frame.streamId, resolvedOpen)
                if (incoming == null) {
                    transport.connection.sendRst(frame.streamId)
                } else {
                    incomingMessageStreams[frame.streamId] = incoming
                }
            }
            else -> transport.connection.sendRst(frame.streamId)
        }
    }

    private suspend fun startFileStreamOrReject(
        streamId: Int,
        kind: String,
        start: suspend () -> Unit,
    ) {
        if (!fileStreamSlots.tryAcquire()) {
            LogKit.w(
                AppStrings.ui_device_session_file_stream_concurrency_limit_exceeded_arg0_kind_arg1.format(arg0 = (streamId).toString(), arg1 = (kind)) +
                    "limit=${sessionPlan.maxFileStreams}",
            )
            transport.connection.sendRst(streamId)
            return
        }
        try {
            start()
        } catch (error: Throwable) {
            fileStreamSlots.release()
            throw error
        }
    }

    private suspend fun registerMessageEndpoint(deviceId: String, token: String) {
        cleanupMessageEndpoint()
        val identity = DeviceMessageEndpointIdentity(
            peerDeviceId = deviceId,
            transport = DeviceMessageTransport.Session,
            connectionId = newDeviceMessageId(),
        )
        val handler = DeviceSessionMessageHandler(
            endpointIdentity = identity,
            coordinator = dispatcher.deviceState.incomingDeviceMessageCoordinator,
        )
        val client = SessionDeviceMessageClient(
            endpointIdentity = identity,
            rpcCall = controlEndpoint::rpc,
            openBodyStream = ::openMessageBodyStream,
        )
        messageEndpointIdentity = identity
        messageHandler = handler
        dispatcher.deviceState.deviceMessageEndpointRegistry.register(
            DeviceMessageLiveEndpoint(
                identity = identity,
                client = client,
                isAuthorized = {
                    boundToken == token && dispatcher.deviceCertificateState.isTokenValid(token)
                },
            ),
        )
    }

    private suspend fun cleanupMessageEndpoint() {
        val identity = messageEndpointIdentity ?: return
        dispatcher.deviceState.incomingDeviceMessageCoordinator.discardEndpoint(identity)
        dispatcher.deviceState.deviceMessageEndpointRegistry.unregister(identity)
        if (messageEndpointIdentity == identity) {
            messageEndpointIdentity = null
            messageHandler = null
        }
    }

    private suspend fun openMessageBodyStream(
        messageId: String,
        data: Flow<ByteArray>,
    ) {
        val streamId = transport.connection.openStream(
            DeviceSessionProtocol.encodeStreamOpen(
                DeviceSessionStreamOpen(
                    kind = DEVICE_SESSION_STREAM_MESSAGE_BODY,
                    payload = ProtoBufCodec.encode(DeviceSessionMessageBodyOpen(messageId)),
                ),
            ),
        )
        try {
            data.collect { chunk ->
                if (chunk.isNotEmpty()) transport.connection.sendData(streamId, chunk)
            }
            transport.connection.sendTrailer(streamId)
        } catch (error: Throwable) {
            runCatching { transport.connection.sendRst(streamId) }
            throw error
        }
    }

    private suspend fun startWrite(streamId: Int, token: String, open: DeviceSessionStreamOpen) {
        val lock = writeLocksMutex.withLock {
            writeLocks.getOrPut(open.path) { Mutex() }
        }
        val incomingWrite = IncomingWrite(
            queueCapacity = sessionPlan.streamFrameQueueCapacity(),
            onBytesPersisted = { bytesWritten ->
                transport.connection.sendWindowUpdate(streamId, bytesWritten)
            },
        )
        incomingWritesMutex.withLock { incomingWrites[streamId] = incomingWrite }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                lock.withLock {
                    val prepared = dispatcher.fileService.prepareWriteBytes(
                        authToken = token,
                        request = WriteBytesStreamRequest(
                            fileSize = open.fileSize,
                            blockIndex = 0L,
                            blockLength = (open.endOffset - open.startOffset).coerceAtLeast(0L),
                            path = open.path,
                            blockStartOffset = open.startOffset,
                        ),
                        maxBlockLength = Long.MAX_VALUE,
                    ).getOrElse { error ->
                        LogKit.w(AppStrings.ui_device_session_write_stream_prepare_failed_arg0_path_arg1_message_arg2.format(arg0 = (streamId).toString(), arg1 = (open.path), arg2 = (error.message).toString()))
                        transport.connection.sendRst(streamId)
                        throw error
                    }
                    LogKit.i(AppStrings.ui_device_session_write_stream_flush_arg0_path_arg1_offset_arg2_length_arg3.format(arg0 = (streamId).toString(), arg1 = (prepared.path), arg2 = (prepared.blockOffset).toString(), arg3 = (prepared.blockLength).toString()))
                    val targetExistedBefore = PathUtils.exists(FileAccessPermission.Allowed, prepared.path)
                    withIncompleteDeviceSessionTargetCleanup(
                        path = prepared.path,
                        targetExistedBefore = targetExistedBefore,
                    ) {
                        FileUtils.writeByteStream(
                            permission = FileAccessPermission.Allowed,
                            path = prepared.path,
                            fileSize = prepared.fileSize,
                            startOffset = prepared.blockOffset,
                            expectedBytes = prepared.blockLength,
                            bufferSize = DEVICE_SESSION_MAX_PAYLOAD_BYTES,
                            readNext = { buffer, length ->
                                val chunk = incomingWrite.data.receiveCatching().getOrNull()
                                    ?: return@writeByteStream -1
                                val copy = minOf(length, chunk.size)
                                chunk.copyInto(buffer, endIndex = copy)
                                copy
                            },
                            onBytesWritten = { _, bytesWritten ->
                                incomingWrite.acknowledgePersisted(bytesWritten)
                            },
                        ).getOrThrow()
                    }
                }
                incomingWrite.completed.complete(Unit)
                incomingWritesMutex.withLock { incomingWrites.remove(streamId) }
                transport.connection.sendTrailer(streamId)
                LogKit.i(AppStrings.ui_device_session_write_stream_completed_arg0_path_arg1.format(arg0 = (streamId).toString(), arg1 = (open.path)))
            } catch (error: CancellationException) {
                incomingWritesMutex.withLock { incomingWrites.remove(streamId) }
                incomingWrite.fail(error)
                throw error
            } catch (error: Throwable) {
                incomingWritesMutex.withLock { incomingWrites.remove(streamId) }
                LogKit.e(AppStrings.ui_device_session_write_stream_failed_arg0_path_arg1_message_arg2.format(arg0 = (streamId).toString(), arg1 = (open.path), arg2 = (error.message).toString()), error)
                incomingWrite.fail(error)
                runCatching { transport.connection.sendRst(streamId) }
            } finally {
                outgoingStreamsMutex.withLock { outgoingStreams.remove(streamId) }
                fileStreamSlots.release()
            }
        }
        outgoingStreamsMutex.withLock { outgoingStreams[streamId] = job }
        job.start()
    }

    private suspend fun startRead(streamId: Int, token: String, open: DeviceSessionStreamOpen) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val started = TimeSource.Monotonic.markNow()
            var bytesSent = 0L
            var sendDataDuration = Duration.ZERO
            try {
                val prepared = dispatcher.fileService.prepareReadBytes(
                    authToken = token,
                    request = ReadBytesRequest(
                        path = open.path,
                        startOffset = open.startOffset,
                        endOffset = open.endOffset,
                    ),
                    maxRangeLength = Long.MAX_VALUE,
                ).getOrThrow()
                LogKit.i(AppStrings.ui_device_session_read_stream_started_arg0_path_arg1_start_arg2_end_arg3.format(arg0 = (streamId).toString(), arg1 = (prepared.path), arg2 = (prepared.startOffset).toString(), arg3 = (prepared.endOffset).toString()))
                FileUtils.readFileRangeChunks(
                    permission = FileAccessPermission.Allowed,
                    path = prepared.path,
                    start = prepared.startOffset,
                    end = prepared.endOffset,
                    chunkSize = DEVICE_SESSION_FILE_READ_BLOCK_BYTES.toLong(),
                ).collect { chunkResult ->
                    val chunk = chunkResult.getOrThrow().second
                    if (chunk.isNotEmpty()) {
                        val sendStarted = TimeSource.Monotonic.markNow()
                        transport.connection.sendData(streamId, chunk)
                        sendDataDuration += sendStarted.elapsedNow()
                        bytesSent += chunk.size.toLong()
                    }
                }
                transport.connection.sendTrailer(streamId)
                val durationMs = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1L)
                val bytesPerSecond = bytesSent * 1000L / durationMs
                LogKit.i(
                    "event=device_session_read_completed stream=$streamId bytes=$bytesSent " +
                        "durationMs=$durationMs bytesPerSecond=$bytesPerSecond " +
                        "sendDataMs=${sendDataDuration.inWholeMilliseconds}"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LogKit.e(
                    "event=device_session_read_failed stream=$streamId bytes=$bytesSent " +
                        "durationMs=${started.elapsedNow().inWholeMilliseconds} " +
                        "sendDataMs=${sendDataDuration.inWholeMilliseconds} message=${error.message}",
                    error,
                )
                runCatching { transport.connection.sendRst(streamId) }
            } finally {
                outgoingStreamsMutex.withLock { outgoingStreams.remove(streamId) }
                fileStreamSlots.release()
            }
        }
        outgoingStreamsMutex.withLock { outgoingStreams[streamId] = job }
        job.start()
    }

    private suspend fun startArchiveRead(streamId: Int, token: String, open: DeviceSessionStreamOpen) {
        val request = ProtoBufCodec.decode<FolderSpanArchiveReadRequest>(open.payload)
        require(FolderSpanArchiveStreamCapabilities.local().supports(request.options)) {
            "requested archive stream options are unavailable"
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val completed = linkedSetOf<String>()
            var committedFileBytes = 0L
            try {
                ArchiveStreamEncoder.encode(
                    entries = request.entries,
                    options = request.options,
                    readFileChunks = { entry -> archiveFileChunks(token, entry) },
                ).collect { chunk ->
                    transport.connection.sendData(streamId, chunk)
                }
                request.entries.forEach { entry ->
                    completed += entry.relativePath
                    if (!entry.directory) committedFileBytes += entry.size
                }
                transport.connection.sendTrailer(
                    streamId,
                    ProtoBufCodec.encode(
                        DeviceSessionArchiveTrailer(
                            FolderSpanArchiveTransferResult(completed.toList(), committedFileBytes)
                        )
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LogKit.e("archive read stream failed: ${error.message}", error)
                runCatching { transport.connection.sendRst(streamId) }
            } finally {
                outgoingStreamsMutex.withLock { outgoingStreams.remove(streamId) }
                fileStreamSlots.release()
            }
        }
        outgoingStreamsMutex.withLock { outgoingStreams[streamId] = job }
        job.start()
    }

    private suspend fun archiveFileChunks(
        token: String,
        entry: FolderSpanArchiveEntryRequest,
    ): Flow<ByteArray> {
        if (entry.directory) return emptyFlow()
        val prepared = dispatcher.fileService.prepareStreamRead(token, entry.sourcePath).getOrThrow()
        require(!prepared.isDirectory && prepared.size == entry.size) {
            "archive source changed while planning: ${entry.relativePath}"
        }
        return flow {
            FileUtils.readFileChunks(
                permission = FileAccessPermission.Allowed,
                path = entry.sourcePath,
                chunkSize = FOLDER_SPAN_ARCHIVE_BUFFER_BYTES.toLong(),
            ).collect { chunk ->
                val bytes = chunk.getOrThrow().second
                if (bytes.isNotEmpty()) emit(bytes)
            }
        }
    }

    private suspend fun startArchiveWrite(streamId: Int, token: String, open: DeviceSessionStreamOpen) {
        val request = ProtoBufCodec.decode<FolderSpanArchiveWriteRequest>(open.payload)
        require(request.destinationRootPath.isNotBlank()) { "archive destination root is empty" }
        require(request.expectedEntries in 1..FOLDER_SPAN_ARCHIVE_MAX_ENTRIES) {
            "archive entry count exceeds limit"
        }
        require(request.expectedFileBytes in 0..FOLDER_SPAN_ARCHIVE_MAX_PAYLOAD_BYTES) {
            "archive file bytes exceed limit"
        }
        val incomingWrite = IncomingWrite(
            queueCapacity = sessionPlan.streamFrameQueueCapacity(),
            onBytesPersisted = { bytes -> transport.connection.sendWindowUpdate(streamId, bytes) },
        )
        incomingWritesMutex.withLock { incomingWrites[streamId] = incomingWrite }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var response = DeviceSessionArchiveTrailer(FolderSpanArchiveTransferResult())
            try {
                val result = ArchiveStreamDecoder.decode(
                    chunks = flow {
                        for (chunk in incomingWrite.data) {
                            emit(chunk)
                            incomingWrite.acknowledgePersisted(chunk.size)
                        }
                    },
                    sink = ArchiveEntryExtractor(
                        scope = this,
                        destinationRootPath = request.destinationRootPath,
                        prepareDirectory = { path ->
                            val created = dispatcher.fileService.createFolders(
                                token,
                                CreateFolderRequest(listOf(path)),
                            ).getOrThrow().single().getOrThrow()
                            check(created) { "archive directory creation failed: $path" }
                        },
                        prepareFile = { path, size ->
                            dispatcher.fileService.prepareStreamWrite(token, path, size).getOrThrow()
                        },
                    ),
                )
                require(result.completedRelativePaths.size == request.expectedEntries) {
                    "archive committed entry count does not match request"
                }
                require(result.committedFileBytes == request.expectedFileBytes) {
                    "archive committed bytes do not match request"
                }
                response = DeviceSessionArchiveTrailer(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: FolderSpanArchiveStreamException) {
                response = DeviceSessionArchiveTrailer(
                    result = error.partialResult,
                    errorMessage = error.message.orEmpty().ifBlank { "archive extraction failed" },
                )
            } catch (error: Throwable) {
                response = DeviceSessionArchiveTrailer(
                    result = FolderSpanArchiveTransferResult(),
                    errorMessage = error.message.orEmpty().ifBlank { "archive extraction failed" },
                )
            } finally {
                incomingWritesMutex.withLock { incomingWrites.remove(streamId) }
                outgoingStreamsMutex.withLock { outgoingStreams.remove(streamId) }
                incomingWrite.completed.complete(Unit)
                runCatching {
                    transport.connection.sendTrailer(streamId, ProtoBufCodec.encode(response))
                }
                fileStreamSlots.release()
            }
        }
        outgoingStreamsMutex.withLock { outgoingStreams[streamId] = job }
        job.start()
    }

    private fun requireToken(): String = boundToken ?: throw AuthorityException(AppStrings.ui_unauthorized)

    private fun ok(request: DeviceSessionControlRequest, payload: ByteArray): DeviceSessionControlResponse {
        return DeviceSessionControlResponse(
            requestId = request.requestId,
            status = DEVICE_SESSION_RPC_OK,
            payload = payload,
        )
    }

    private inline fun <reified T> result(
        request: DeviceSessionControlRequest,
        value: Result<T>,
    ): DeviceSessionControlResponse {
        return if (value.isSuccess) {
            DeviceSessionControlResponse(
                requestId = request.requestId,
                status = DEVICE_SESSION_RPC_OK,
                payload = ProtoBufCodec.encode(value.toSerializableResult()),
            )
        } else {
            val error = value.exceptionOrNull() ?: Exception("rpc failed")
            DeviceSessionControlResponse(
                requestId = request.requestId,
                status = rpcStatus(error),
                errorMessage = error.message.orEmpty(),
            )
        }
    }

    private fun batchBooleanResult(
        request: DeviceSessionControlRequest,
        value: Result<List<Result<Boolean>>>,
    ): DeviceSessionControlResponse {
        return result(request, value.toSerializableBooleanBatchResult())
    }

    private fun rpcStatus(error: Throwable): Int {
        return when (error) {
            is AuthorityException -> DEVICE_SESSION_RPC_FORBIDDEN
            is IllegalArgumentException -> DEVICE_SESSION_RPC_BAD_REQUEST
            else -> DEVICE_SESSION_RPC_INTERNAL
        }
    }

}

internal class IncomingWrite(
    queueCapacity: Int,
    private val onBytesPersisted: suspend (Int) -> Unit,
) {
    val data = Channel<ByteArray>(capacity = queueCapacity.coerceAtLeast(1))
    val completed = CompletableDeferred<Unit>()
    private val inputClosed = CompletableDeferred<Unit>()

    suspend fun enqueue(chunk: ByteArray): Boolean {
        return try {
            select {
                inputClosed.onAwait { false }
                data.onSend(chunk) { true }
            }
        } catch (_: ClosedSendChannelException) {
            false
        }
    }

    suspend fun acknowledgePersisted(bytesWritten: Int) {
        require(bytesWritten >= 0) { "bytesWritten must be non-negative" }
        if (bytesWritten > 0) onBytesPersisted(bytesWritten)
    }

    fun closeInput(cause: Throwable? = null) {
        inputClosed.complete(Unit)
        data.close(cause)
    }

    fun fail(error: Throwable) {
        closeInput(error)
        completed.completeExceptionally(error)
    }
}

internal suspend fun <T> withIncompleteDeviceSessionTargetCleanup(
    path: String,
    targetExistedBefore: Boolean,
    block: suspend () -> T,
): T {
    return try {
        block()
    } catch (error: Throwable) {
        if (!targetExistedBefore && PathUtils.exists(FileAccessPermission.Allowed, path)) {
            val deleted = FileUtils.deleteFile(FileAccessPermission.Allowed, path).getOrDefault(false)
            if (!deleted) {
                LogKit.w("Device Session could not remove incomplete receive target: $path")
            }
        }
        throw error
    }
}

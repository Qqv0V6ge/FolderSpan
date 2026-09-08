package com.folderspan.service.session

import com.folderspan.exception.AuthorityException
import com.folderspan.service.data.SerializableResult
import com.folderspan.service.data.toResult
import com.folderspan.service.http.archive.FolderSpanArchiveReadRequest
import com.folderspan.service.http.archive.FolderSpanArchiveRemoteWriteException
import com.folderspan.service.http.archive.FolderSpanArchiveTransferResult
import com.folderspan.service.http.archive.FolderSpanArchiveWriteRequest
import com.folderspan.utils.LogKit
import com.folderspan.utils.ProtoBufCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import strings.AppStrings

internal class DeviceSessionRpcException(
    val status: Int,
    message: String,
) : IllegalStateException(message)

internal interface DeviceSessionIncomingStream {
    suspend fun onData(chunk: ByteArray)

    suspend fun onTrailer(payload: ByteArray) = Unit

    suspend fun onReset() = Unit
}

internal fun interface DeviceSessionStreamOpenHandler {
    suspend fun open(
        streamId: Int,
        open: DeviceSessionStreamOpen,
    ): DeviceSessionIncomingStream?
}

internal class DeviceSessionPeer(
    private val transport: DeviceSessionTransport,
    private val receivePlan: DeviceSessionWindowPlan,
    private val role: DeviceSessionEndpointRole = DeviceSessionEndpointRole.Client,
) {
    private val mutex = Mutex()
    private val streamData = HashMap<Int, Channel<ByteArray>>()
    private val streamTrailers = HashMap<Int, CompletableDeferred<ByteArray>>()
    private val incomingStreams = HashMap<Int, DeviceSessionIncomingStream>()
    private val controlAssembler = DeviceSessionControlAssembler()
    private var requestHandler: DeviceSessionRequestHandler? = null
    private var streamOpenHandler: DeviceSessionStreamOpenHandler? = null
    private var closeHandler: (suspend () -> Unit)? = null
    private var closeNotified = false
    private var controlEndpoint: DeviceSessionControlEndpoint? = null
    private var dispatcherJob: Job? = null

    val connection: DeviceSessionConnection
        get() = transport.connection

    fun start(scope: CoroutineScope): Job {
        LogKit.i(AppStrings.ui_device_session_scheduling_starts)
        check(controlEndpoint == null) { "Device session peer already started" }
        controlEndpoint = DeviceSessionControlEndpoint(
            connection = connection,
            role = role,
            scope = scope,
            requestHandler = requestHandler,
        )
        val transportJob = transport.start(scope)
        dispatcherJob = scope.launch { dispatchIncoming() }
        return transportJob
    }

    suspend fun setRequestHandler(handler: DeviceSessionRequestHandler?) {
        requestHandler = handler
        controlEndpoint?.setRequestHandler(handler)
    }

    suspend fun setStreamOpenHandler(handler: DeviceSessionStreamOpenHandler?) {
        mutex.withLock { streamOpenHandler = handler }
    }

    suspend fun setCloseHandler(handler: (suspend () -> Unit)?) {
        mutex.withLock { closeHandler = handler }
    }

    suspend fun rpc(method: String, payload: ByteArray): DeviceSessionControlResponse {
        val endpoint = controlEndpoint ?: throw DeviceSessionClosedException("device session peer not started")
        LogKit.d(AppStrings.ui_device_session_rpc_send_method_arg0_payload_arg1.format(arg0 = (method), arg1 = (payload.size).toString()))
        val response = endpoint.rpc(method, payload)
        LogKit.d(AppStrings.ui_device_session_rpc_returns_method_arg0_status_arg1_payload_arg2.format(arg0 = (method), arg1 = (response.status).toString(), arg2 = (response.payload.size).toString()))
        return response
    }

    suspend inline fun <reified Req, reified Res> rpcValue(method: String, request: Req): Res {
        val response = rpc(method, ProtoBufCodec.encode(request))
        if (!response.isSuccess) {
            throw DeviceSessionRpcException(response.status, response.errorMessage.ifBlank { method })
        }
        return ProtoBufCodec.decode(response.payload)
    }

    suspend inline fun <reified Req, reified Res> rpcResult(method: String, request: Req): Result<Res> {
        val response = rpc(method, ProtoBufCodec.encode(request))
        if (!response.isSuccess) {
            return Result.failure(rpcFailure(response))
        }
        return ProtoBufCodec.decode<SerializableResult>(response.payload).toResult()
    }

    suspend fun openWriteStream(
        path: String,
        fileSize: Long,
        startOffset: Long,
        endOffset: Long = fileSize,
        data: Flow<ByteArray>,
    ) {
        require(startOffset >= 0L && endOffset >= startOffset && endOffset <= fileSize) {
            "invalid device session write range: $startOffset..$endOffset/$fileSize"
        }
        val trailer = CompletableDeferred<ByteArray>()
        var allocatedStreamId: Int? = null
        val streamId = try {
            connection.openStream(
                payload = DeviceSessionProtocol.encodeStreamOpen(
                    DeviceSessionStreamOpen(
                        kind = DEVICE_SESSION_STREAM_WRITE,
                        path = path,
                        fileSize = fileSize,
                        startOffset = startOffset,
                        endOffset = endOffset,
                    )
                ),
                onStreamAllocated = { streamId ->
                    allocatedStreamId = streamId
                    mutex.withLock { streamTrailers[streamId] = trailer }
                },
            )
        } catch (error: Throwable) {
            allocatedStreamId?.let { streamId ->
                mutex.withLock { streamTrailers.remove(streamId) }
            }
            throw error
        }
        LogKit.i(
            AppStrings.ui_device_session_write_stream_opened_arg0_path_arg1_size_arg2.format(arg0 = (streamId).toString(), arg1 = (path), arg2 = (fileSize).toString()) +
                "start=$startOffset end=$endOffset",
        )
        try {
            val expectedBytes = endOffset - startOffset
            var sentBytes = 0L
            data.collect { chunk ->
                if (chunk.isNotEmpty()) {
                    require(sentBytes + chunk.size <= expectedBytes) {
                        "device session write exceeds declared range: " +
                            "${sentBytes + chunk.size}/$expectedBytes"
                    }
                    connection.sendData(streamId, chunk)
                    sentBytes += chunk.size
                }
            }
            require(sentBytes == expectedBytes) {
                "device session write length mismatch: $sentBytes/$expectedBytes"
            }
            connection.sendTrailer(streamId)
            trailer.await()
            LogKit.i(AppStrings.ui_device_session_write_stream_completed_arg0_path_arg1.format(arg0 = (streamId).toString(), arg1 = (path)))
        } catch (error: Throwable) {
            LogKit.w(AppStrings.ui_device_session_write_stream_failed_arg0_path_arg1_message_arg2.format(arg0 = (streamId).toString(), arg1 = (path), arg2 = (error.message).toString()))
            runCatching { connection.sendRst(streamId) }
            val remoteFailure = if (trailer.isCompleted || error is DeviceSessionClosedException) {
                runCatching { trailer.await() }.exceptionOrNull()
            } else {
                null
            }
            throw remoteFailure ?: error
        } finally {
            mutex.withLock { streamTrailers.remove(streamId) }
        }
    }

    suspend fun openReadStream(
        path: String,
        startOffset: Long,
        endOffset: Long,
        onChunk: suspend (ByteArray) -> Unit,
    ) {
        val data = Channel<ByteArray>(capacity = receivePlan.streamFrameQueueCapacity())
        val trailer = CompletableDeferred<ByteArray>()
        var allocatedStreamId: Int? = null
        val streamId = try {
            connection.openStream(
                payload = DeviceSessionProtocol.encodeStreamOpen(
                    DeviceSessionStreamOpen(
                        kind = DEVICE_SESSION_STREAM_READ,
                        path = path,
                        startOffset = startOffset,
                        endOffset = endOffset,
                        fileSize = (endOffset - startOffset).coerceAtLeast(0L),
                    )
                ),
                onStreamAllocated = { streamId ->
                    allocatedStreamId = streamId
                    mutex.withLock {
                        streamData[streamId] = data
                        streamTrailers[streamId] = trailer
                    }
                },
            )
        } catch (error: Throwable) {
            allocatedStreamId?.let { streamId ->
                mutex.withLock {
                    streamData.remove(streamId)
                    streamTrailers.remove(streamId)
                }
            }
            data.close(error)
            throw error
        }
        LogKit.i(AppStrings.ui_device_session_read_stream_opened_arg0_path_arg1_start_arg2_end_arg3.format(arg0 = (streamId).toString(), arg1 = (path), arg2 = (startOffset).toString(), arg3 = (endOffset).toString()))
        try {
            for (chunk in data) {
                onChunk(chunk)
                connection.sendWindowUpdate(streamId, chunk.size)
            }
            trailer.await()
            LogKit.i(AppStrings.ui_device_session_read_stream_completed_arg0_path_arg1.format(arg0 = (streamId).toString(), arg1 = (path)))
        } catch (error: Throwable) {
            LogKit.w(AppStrings.ui_device_session_read_stream_failed_arg0_path_arg1_message_arg2.format(arg0 = (streamId).toString(), arg1 = (path), arg2 = (error.message).toString()))
            runCatching { connection.sendRst(streamId) }
            throw error
        } finally {
            mutex.withLock {
                streamData.remove(streamId)
                streamTrailers.remove(streamId)
            }
        }
    }

    suspend fun openArchiveReadStream(
        request: FolderSpanArchiveReadRequest,
        onChunk: suspend (ByteArray) -> Unit,
    ): FolderSpanArchiveTransferResult {
        val data = Channel<ByteArray>(capacity = receivePlan.streamFrameQueueCapacity())
        val trailer = CompletableDeferred<ByteArray>()
        val streamId = openReceivingStream(
            open = DeviceSessionStreamOpen(
                kind = DEVICE_SESSION_STREAM_ARCHIVE_READ,
                payload = ProtoBufCodec.encode(request),
            ),
            data = data,
            trailer = trailer,
        )
        try {
            for (chunk in data) {
                onChunk(chunk)
                connection.sendWindowUpdate(streamId, chunk.size)
            }
            val result = ProtoBufCodec.decode<DeviceSessionArchiveTrailer>(trailer.await())
            if (result.errorMessage.isNotBlank()) {
                throw FolderSpanArchiveRemoteWriteException(result.errorMessage, result.result)
            }
            return result.result
        } catch (error: Throwable) {
            runCatching { connection.sendRst(streamId) }
            throw error
        } finally {
            mutex.withLock {
                streamData.remove(streamId)
                streamTrailers.remove(streamId)
            }
        }
    }

    suspend fun openArchiveWriteStream(
        request: FolderSpanArchiveWriteRequest,
        data: Flow<ByteArray>,
    ): FolderSpanArchiveTransferResult {
        val trailer = CompletableDeferred<ByteArray>()
        var allocatedStreamId: Int? = null
        val streamId = try {
            connection.openStream(
                payload = DeviceSessionProtocol.encodeStreamOpen(
                    DeviceSessionStreamOpen(
                        kind = DEVICE_SESSION_STREAM_ARCHIVE_WRITE,
                        payload = ProtoBufCodec.encode(request),
                    )
                ),
                onStreamAllocated = { allocated ->
                    allocatedStreamId = allocated
                    mutex.withLock { streamTrailers[allocated] = trailer }
                },
            )
        } catch (error: Throwable) {
            allocatedStreamId?.let { allocated -> mutex.withLock { streamTrailers.remove(allocated) } }
            throw error
        }
        try {
            data.collect { chunk ->
                if (chunk.isNotEmpty()) connection.sendData(streamId, chunk)
            }
            connection.sendTrailer(streamId)
            val response = ProtoBufCodec.decode<DeviceSessionArchiveTrailer>(trailer.await())
            if (response.errorMessage.isNotBlank()) {
                throw FolderSpanArchiveRemoteWriteException(response.errorMessage, response.result)
            }
            return response.result
        } catch (error: Throwable) {
            runCatching { connection.sendRst(streamId) }
            throw error
        } finally {
            mutex.withLock { streamTrailers.remove(streamId) }
        }
    }

    private suspend fun openReceivingStream(
        open: DeviceSessionStreamOpen,
        data: Channel<ByteArray>,
        trailer: CompletableDeferred<ByteArray>,
    ): Int {
        var allocatedStreamId: Int? = null
        return try {
            connection.openStream(
                payload = DeviceSessionProtocol.encodeStreamOpen(open),
                onStreamAllocated = { allocated ->
                    allocatedStreamId = allocated
                    mutex.withLock {
                        streamData[allocated] = data
                        streamTrailers[allocated] = trailer
                    }
                },
            )
        } catch (error: Throwable) {
            allocatedStreamId?.let { allocated ->
                mutex.withLock {
                    streamData.remove(allocated)
                    streamTrailers.remove(allocated)
                }
            }
            data.close(error)
            throw error
        }
    }

    suspend fun openMessageBodyStream(
        messageId: String,
        data: Flow<ByteArray>,
    ) {
        val streamId = connection.openStream(
            DeviceSessionProtocol.encodeStreamOpen(
                DeviceSessionStreamOpen(
                    kind = DEVICE_SESSION_STREAM_MESSAGE_BODY,
                    payload = ProtoBufCodec.encode(DeviceSessionMessageBodyOpen(messageId)),
                ),
            ),
        )
        try {
            data.collect { chunk ->
                if (chunk.isNotEmpty()) connection.sendData(streamId, chunk)
            }
            connection.sendTrailer(streamId)
        } catch (error: Throwable) {
            runCatching { connection.sendRst(streamId) }
            throw error
        }
    }

    suspend fun ping(payload: ByteArray): ByteArray = connection.ping(payload)

    suspend fun close() {
        LogKit.i(AppStrings.ui_device_session_closed)
        notifyClosed()
        controlEndpoint?.close()
        failPendingStreams(DeviceSessionClosedException())
        transport.close()
        dispatcherJob?.cancel()
    }

    private suspend fun dispatchIncoming() {
        try {
            for (frame in connection.incomingEvents) {
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
                        val channel = mutex.withLock { streamData[frame.streamId] }
                        if (channel != null) {
                            channel.send(frame.payload)
                        } else {
                            val handler = mutex.withLock { incomingStreams[frame.streamId] }
                            if (handler != null) {
                                handler.onData(frame.payload)
                                connection.sendWindowUpdate(frame.streamId, frame.payload.size)
                            }
                        }
                    }
                }
                DeviceSessionFrameType.Open -> handleOpen(frame)
                DeviceSessionFrameType.Trailer -> {
                    val channel = mutex.withLock { streamData.remove(frame.streamId) }
                    val trailer = mutex.withLock { streamTrailers.remove(frame.streamId) }
                    val handler = mutex.withLock { incomingStreams.remove(frame.streamId) }
                    channel?.close()
                    trailer?.complete(frame.payload)
                    handler?.onTrailer(frame.payload)
                }
                DeviceSessionFrameType.Rst -> {
                    LogKit.w(AppStrings.ui_device_session_receives_rst_stream_arg0.format(arg0 = (frame.streamId).toString()))
                    val channel = mutex.withLock { streamData.remove(frame.streamId) }
                    val trailer = mutex.withLock { streamTrailers.remove(frame.streamId) }
                    val handler = mutex.withLock { incomingStreams.remove(frame.streamId) }
                    val error = DeviceSessionIoException("stream reset: ${frame.streamId}")
                    channel?.close(error)
                    trailer?.completeExceptionally(error)
                    handler?.onReset()
                }
                DeviceSessionFrameType.GoAway -> {
                    LogKit.w(AppStrings.ui_device_session_receives_goaway)
                    close()
                    return
                }
                    else -> Unit
                }
            }
        } finally {
            notifyClosed()
            controlEndpoint?.close()
            failPendingStreams(DeviceSessionClosedException())
        }
    }

    private suspend fun failPendingStreams(error: Throwable) {
        val pending = mutex.withLock {
            val channels = streamData.values.toList()
            val trailers = streamTrailers.values.toList()
            val handlers = incomingStreams.values.toList()
            streamData.clear()
            streamTrailers.clear()
            incomingStreams.clear()
            Triple(channels, trailers, handlers)
        }
        pending.first.forEach { channel -> channel.close(error) }
        pending.second.forEach { trailer -> trailer.completeExceptionally(error) }
        pending.third.forEach { handler -> runCatching { handler.onReset() } }
    }

    private suspend fun notifyClosed() {
        val handler = mutex.withLock {
            if (closeNotified) return
            closeNotified = true
            closeHandler
        }
        handler?.invoke()
    }

    private suspend fun handleControl(payload: ByteArray) {
        controlEndpoint?.handle(payload)
    }

    private suspend fun handleOpen(frame: DeviceSessionFrame) {
        val open = runCatching { DeviceSessionProtocol.decodeStreamOpen(frame.payload) }.getOrNull()
        val handler = mutex.withLock { streamOpenHandler }
        if (open == null || handler == null) {
            connection.sendRst(frame.streamId)
            return
        }
        val incoming = runCatching { handler.open(frame.streamId, open) }.getOrNull()
        if (incoming == null) {
            connection.sendRst(frame.streamId)
            return
        }
        mutex.withLock { incomingStreams[frame.streamId] = incoming }
    }

    private fun rpcFailure(response: DeviceSessionControlResponse): Throwable {
        return when (response.status) {
            DEVICE_SESSION_RPC_UNAUTHORIZED, DEVICE_SESSION_RPC_FORBIDDEN ->
                AuthorityException(response.errorMessage.ifBlank { AppStrings.ui_unauthorized })
            else -> DeviceSessionRpcException(response.status, response.errorMessage)
        }
    }
}

package com.folderspan.service.session

import com.folderspan.service.message.DEVICE_MESSAGE_MAX_CHUNK_BYTES
import com.folderspan.service.message.DeviceMessageBeginResult
import com.folderspan.service.message.DeviceMessageClient
import com.folderspan.service.message.DeviceMessageEndpointIdentity
import com.folderspan.service.message.DeviceMessageMetadata
import com.folderspan.service.message.DeviceMessageReceipt
import com.folderspan.service.message.DeviceMessageTransferError
import com.folderspan.service.message.DeviceMessageTransferException
import com.folderspan.service.message.IncomingDeviceMessageCoordinator
import com.folderspan.utils.ProtoBufCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout

internal const val DEVICE_SESSION_MESSAGE_RPC_TIMEOUT_MILLIS = 30_000L

internal class DeviceSessionMessageHandler(
    private val endpointIdentity: DeviceMessageEndpointIdentity,
    private val coordinator: IncomingDeviceMessageCoordinator,
) : DeviceSessionRequestHandler, DeviceSessionStreamOpenHandler {
    override suspend fun handle(request: DeviceSessionControlRequest): DeviceSessionControlResponse {
        return when (request.method) {
            DEVICE_SESSION_RPC_BEGIN_MESSAGE -> {
                val metadata = ProtoBufCodec.decode<DeviceMessageMetadata>(request.payload)
                val response = when (val begin = coordinator.begin(endpointIdentity, metadata)) {
                    DeviceMessageBeginResult.Ready -> DeviceSessionMessageBeginResponse(ready = true)
                    is DeviceMessageBeginResult.AlreadyPersisted -> DeviceSessionMessageBeginResponse(
                        ready = false,
                        receipt = begin.receipt,
                    )
                }
                ok(request, ProtoBufCodec.encode(response))
            }
            DEVICE_SESSION_RPC_COMMIT_MESSAGE -> {
                val commit = ProtoBufCodec.decode<DeviceSessionMessageCommitRequest>(request.payload)
                val receipt = coordinator.commit(endpointIdentity, commit.messageId)
                ok(request, ProtoBufCodec.encode(receipt))
            }
            else -> DeviceSessionControlResponse(
                requestId = request.requestId,
                status = DEVICE_SESSION_RPC_NOT_FOUND,
                errorMessage = request.method,
            )
        }
    }

    override suspend fun open(
        streamId: Int,
        open: DeviceSessionStreamOpen,
    ): DeviceSessionIncomingStream? {
        if (open.kind != DEVICE_SESSION_STREAM_MESSAGE_BODY) return null
        val message = runCatching { ProtoBufCodec.decode<DeviceSessionMessageBodyOpen>(open.payload) }.getOrNull()
            ?: return null
        if (!coordinator.hasActiveTransfer(endpointIdentity, message.messageId)) return null
        return object : DeviceSessionIncomingStream {
            private var nextChunkIndex = 0

            override suspend fun onData(chunk: ByteArray) {
                coordinator.appendChunk(
                    endpointIdentity = endpointIdentity,
                    messageId = message.messageId,
                    chunkIndex = nextChunkIndex,
                    chunk = chunk,
                )
                nextChunkIndex += 1
            }

            override suspend fun onReset() {
                coordinator.discardTransfer(endpointIdentity, message.messageId)
            }
        }
    }

    private fun ok(
        request: DeviceSessionControlRequest,
        payload: ByteArray,
    ): DeviceSessionControlResponse = DeviceSessionControlResponse(
        requestId = request.requestId,
        status = DEVICE_SESSION_RPC_OK,
        payload = payload,
    )
}

internal class SessionDeviceMessageClient(
    override val endpointIdentity: DeviceMessageEndpointIdentity,
    private val rpcCall: suspend (String, ByteArray) -> DeviceSessionControlResponse,
    private val openBodyStream: suspend (String, Flow<ByteArray>) -> Unit,
    private val rpcTimeoutMillis: Long = DEVICE_SESSION_MESSAGE_RPC_TIMEOUT_MILLIS,
) : DeviceMessageClient {
    constructor(
        peer: DeviceSessionPeer,
        endpointIdentity: DeviceMessageEndpointIdentity,
    ) : this(
        endpointIdentity = endpointIdentity,
        rpcCall = peer::rpc,
        openBodyStream = peer::openMessageBodyStream,
    )

    override val maxChunkBytes: Int = minOf(DEVICE_MESSAGE_MAX_CHUNK_BYTES, DEVICE_SESSION_MAX_PAYLOAD_BYTES)

    override suspend fun send(
        metadata: DeviceMessageMetadata,
        bodyChunks: Flow<ByteArray>,
    ): Result<DeviceMessageReceipt> {
        val begin = try {
            withTimeout(rpcTimeoutMillis) {
                rpcValue<DeviceMessageMetadata, DeviceSessionMessageBeginResponse>(DEVICE_SESSION_RPC_BEGIN_MESSAGE, metadata)
            }
        } catch (error: Throwable) {
            return Result.failure(error.toMessageTransferFailure(deliveryUncertain = false))
        }
        begin.receipt?.let { receipt -> return Result.success(receipt) }
        if (!begin.ready) {
            return Result.failure(DeviceMessageTransferException(DeviceMessageTransferError.DuplicateConflict))
        }
        try {
            openBodyStream(metadata.messageId, bodyChunks)
        } catch (error: Throwable) {
            return Result.failure(error.toMessageTransferFailure(deliveryUncertain = false))
        }
        return try {
            Result.success(
                withTimeout(rpcTimeoutMillis) {
                    rpcValue<DeviceSessionMessageCommitRequest, DeviceMessageReceipt>(
                        DEVICE_SESSION_RPC_COMMIT_MESSAGE,
                        DeviceSessionMessageCommitRequest(metadata.messageId),
                    )
                },
            )
        } catch (error: Throwable) {
            Result.failure(error.toMessageTransferFailure(deliveryUncertain = true))
        }
    }

    private suspend inline fun <reified Req, reified Res> rpcValue(method: String, request: Req): Res {
        val response = rpcCall(method, ProtoBufCodec.encode(request))
        if (!response.isSuccess) {
            throw DeviceSessionRpcException(response.status, response.errorMessage.ifBlank { method })
        }
        return ProtoBufCodec.decode(response.payload)
    }
}

private fun Throwable.toMessageTransferFailure(deliveryUncertain: Boolean): Throwable {
    if (this is DeviceMessageTransferException) return this
    val error = when ((this as? DeviceSessionRpcException)?.status) {
        DEVICE_SESSION_RPC_UNAUTHORIZED, DEVICE_SESSION_RPC_FORBIDDEN -> DeviceMessageTransferError.Unauthorized
        DEVICE_SESSION_RPC_RATE_LIMITED -> DeviceMessageTransferError.RateLimited
        DEVICE_SESSION_RPC_CONFLICT -> DeviceMessageTransferError.DuplicateConflict
        else -> DeviceMessageTransferError.TransportFailure
    }
    return DeviceMessageTransferException(error, deliveryUncertain = deliveryUncertain, cause = this)
}

package com.folderspan.service.session

import com.folderspan.exception.AuthorityException
import com.folderspan.service.message.DeviceMessagePersistenceException
import com.folderspan.service.message.DeviceMessageTransferError
import com.folderspan.service.message.DeviceMessageTransferException
import com.folderspan.service.message.DeviceMessageValidationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class DeviceSessionEndpointRole(
    val firstRequestId: Int,
    val expectedRemoteRequestParity: Int,
) {
    Client(firstRequestId = 1, expectedRemoteRequestParity = 0),
    Server(firstRequestId = 2, expectedRemoteRequestParity = 1),
}

internal fun deviceSessionRpcStatus(error: Throwable): Int = when (error) {
    is AuthorityException -> DEVICE_SESSION_RPC_FORBIDDEN
    is DeviceMessageValidationException, is IllegalArgumentException -> DEVICE_SESSION_RPC_BAD_REQUEST
    is DeviceMessagePersistenceException -> DEVICE_SESSION_RPC_INTERNAL
    is DeviceMessageTransferException -> when (error.error) {
        DeviceMessageTransferError.Offline,
        DeviceMessageTransferError.Unauthorized -> DEVICE_SESSION_RPC_UNAUTHORIZED
        DeviceMessageTransferError.RateLimited -> DEVICE_SESSION_RPC_RATE_LIMITED
        DeviceMessageTransferError.DuplicateConflict,
        DeviceMessageTransferError.TransferAlreadyActive -> DEVICE_SESSION_RPC_CONFLICT
        else -> DEVICE_SESSION_RPC_BAD_REQUEST
    }
    else -> DEVICE_SESSION_RPC_INTERNAL
}

internal fun interface DeviceSessionRequestHandler {
    suspend fun handle(request: DeviceSessionControlRequest): DeviceSessionControlResponse
}

internal class DeviceSessionControlEndpoint(
    private val connection: DeviceSessionConnection,
    private val role: DeviceSessionEndpointRole,
    private val scope: CoroutineScope,
    requestHandler: DeviceSessionRequestHandler? = null,
    private val onResponseSent: suspend (DeviceSessionControlRequest, DeviceSessionControlResponse) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()
    private val pending = mutableMapOf<Int, CompletableDeferred<DeviceSessionControlResponse>>()
    private val requestJobs = mutableSetOf<Job>()
    private var nextRequestId = role.firstRequestId
    private var handler = requestHandler
    private var closed = false

    suspend fun setRequestHandler(requestHandler: DeviceSessionRequestHandler?) {
        mutex.withLock { handler = requestHandler }
    }

    suspend fun rpc(method: String, payload: ByteArray): DeviceSessionControlResponse {
        val waiter = CompletableDeferred<DeviceSessionControlResponse>()
        val requestId = mutex.withLock {
            ensureOpenLocked()
            allocateRequestIdLocked().also { id -> pending[id] = waiter }
        }
        try {
            connection.sendControl(
                DeviceSessionProtocol.encodeControlRequest(
                    DeviceSessionControlRequest(requestId = requestId, method = method, payload = payload),
                ),
            )
        } catch (error: Throwable) {
            mutex.withLock { pending.remove(requestId) }
            waiter.completeExceptionally(error)
            throw error
        }
        return waiter.await()
    }

    suspend fun handle(payload: ByteArray) {
        when (val decoded = DeviceSessionProtocol.decodeControlMessage(payload)) {
            is DeviceSessionControlResponse -> {
                val waiter = mutex.withLock { pending.remove(decoded.requestId) }
                waiter?.complete(decoded)
            }
            is DeviceSessionControlRequest -> dispatchRequest(decoded)
        }
    }

    suspend fun close(cause: Throwable = DeviceSessionClosedException()) {
        val (waiters, jobs) = mutex.withLock {
            if (closed) return
            closed = true
            val currentWaiters = pending.values.toList()
            val currentJobs = requestJobs.toList()
            pending.clear()
            requestJobs.clear()
            currentWaiters to currentJobs
        }
        waiters.forEach { waiter -> waiter.completeExceptionally(cause) }
        jobs.forEach(Job::cancel)
    }

    private suspend fun dispatchRequest(request: DeviceSessionControlRequest) {
        val requestHandler = mutex.withLock {
            if (closed) return
            if (request.requestId <= 0 || request.requestId and 1 != role.expectedRemoteRequestParity) {
                null
            } else {
                handler
            }
        }
        val job = scope.launch {
            val response = if (requestHandler == null) {
                DeviceSessionControlResponse(
                    requestId = request.requestId,
                    status = DEVICE_SESSION_RPC_NOT_FOUND,
                    errorMessage = request.method,
                )
            } else {
                try {
                    requestHandler.handle(request).copy(requestId = request.requestId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    DeviceSessionControlResponse(
                        requestId = request.requestId,
                        status = deviceSessionRpcStatus(error),
                        errorMessage = error.message.orEmpty(),
                    )
                }
            }
            val sent = runCatching {
                connection.sendControl(DeviceSessionProtocol.encodeControlResponse(response))
            }
            if (sent.isSuccess) onResponseSent(request, response)
        }
        mutex.withLock {
            if (closed) {
                job.cancel()
            } else {
                requestJobs += job
                job.invokeOnCompletion {
                    scope.launch { mutex.withLock { requestJobs.remove(job) } }
                }
            }
        }
    }

    private fun allocateRequestIdLocked(): Int {
        repeat(Int.MAX_VALUE / 2) {
            val candidate = nextRequestId
            nextRequestId = if (candidate >= Int.MAX_VALUE - 2) {
                role.firstRequestId
            } else {
                candidate + 2
            }
            if (!pending.containsKey(candidate)) return candidate
        }
        throw IllegalStateException("No device session request IDs available")
    }

    private fun ensureOpenLocked() {
        if (closed) throw DeviceSessionClosedException()
    }
}

package com.folderspan.service.session

import com.folderspan.service.operation.HttpTransferRuntimeTuning
import com.folderspan.service.operation.HttpTransferStatus
import com.folderspan.service.operation.HTTP_TRANSFER_STATUS_FRESH_MILLIS
import com.folderspan.utils.ProtoBufCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.Clock

private const val TRANSFER_FEEDBACK_SAMPLE_MILLIS = 2_000L
private const val TRANSFER_FEEDBACK_MIN_UPDATE_MILLIS = 100L

@Serializable
internal data class DeviceSessionTransferFeedbackSubscription(val receiveUpdates: Boolean = true)

@Serializable
internal data class DeviceSessionTransferFeedbackUpdate(val sequence: Long, val status: HttpTransferStatus)

/** Lives behind authenticated control handlers; neither subscribing nor reading creates a connection. */
internal class DeviceSessionTransferFeedback(
    scope: CoroutineScope,
    private val limits: HttpTransferStatus,
    activity: Flow<Int>,
    private val rpc: suspend (String, ByteArray) -> DeviceSessionControlResponse,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private data class Received(val sequence: Long = 0, val status: HttpTransferStatus? = null, val at: Long = 0)
    private val received = MutableStateFlow(Received())
    private val sending = MutableStateFlow(false)
    private val receiving = MutableStateFlow(false)
    private val closed = MutableStateFlow(false)
    private val active = MutableStateFlow(0)
    private val pending = Channel<Unit>(Channel.CONFLATED)
    private val jobs: List<Job>

    init {
        val sampler = scope.launch {
            combine(sending, activity) { enabled, count -> enabled to count.coerceAtLeast(0) }
                .distinctUntilChanged().collectLatest { (enabled, count) ->
                    active.value = count
                    if (enabled) {
                        pending.trySend(Unit)
                        if (count > 0) while (true) {
                            delay(TRANSFER_FEEDBACK_SAMPLE_MILLIS)
                            pending.trySend(Unit)
                        }
                    }
                }
        }
        val sender = scope.launch {
            var sequence = 0L
            var sentAt: Long? = null
            for (signal in pending) {
                if (!sending.value || closed.value) continue
                sentAt?.let { previous ->
                    delay((TRANSFER_FEEDBACK_MIN_UPDATE_MILLIS - (nowMillis() - previous)
                        .coerceIn(0, TRANSFER_FEEDBACK_MIN_UPDATE_MILLIS)).coerceAtLeast(0))
                }
                pending.tryReceive()
                if (!sending.value || closed.value) continue
                if (sequence == Long.MAX_VALUE) { sending.value = false; continue }
                val update = DeviceSessionTransferFeedbackUpdate(++sequence, sample())
                sentAt = nowMillis()
                try {
                    val response = rpc(DEVICE_SESSION_RPC_TRANSFER_FEEDBACK, ProtoBufCodec.encode(update))
                    if (!response.isSuccess) sending.value = false
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { sending.value = false }
            }
        }
        jobs = listOf(sampler, sender)
    }

    private fun sample(): HttpTransferStatus {
        val hard = limits.clamped()
        val plan = HttpTransferRuntimeTuning.plan(hard.maxChunkBytes, hard.maxParallelRequests)
        return hard.copy(recommendedChunkBytes = plan.recommendedChunkBytes,
            recommendedParallelRequests = plan.recommendedParallelRequests,
            activeRequests = active.value, busy = plan.busy || active.value >= hard.maxParallelRequests,
            retryAfterMillis = if (plan.busy) 50 else 0, sampledAtMillis = nowMillis().coerceAtLeast(1))
    }

    fun current(): HttpTransferStatus {
        val fallback = limits.clamped().copy(recommendedParallelRequests = 1, activeRequests = 0,
            busy = false, retryAfterMillis = 0, sampledAtMillis = 0)
        if (closed.value || !receiving.value) return fallback
        val latest = received.value
        val status = latest.status ?: return fallback
        val age = nowMillis() - latest.at
        if (age < 0 || age >= HTTP_TRANSFER_STATUS_FRESH_MILLIS) return fallback
        return status.copy(retryAfterMillis = (status.retryAfterMillis - age).coerceAtLeast(0))
    }

    suspend fun subscribe(): Boolean {
        if (closed.value) return false
        // The request explicitly asks for peer updates; its response explicitly subscribes the peer.
        receiving.value = true
        try {
            val response = rpc(DEVICE_SESSION_RPC_SUBSCRIBE_TRANSFER_FEEDBACK,
                ProtoBufCodec.encode(DeviceSessionTransferFeedbackSubscription()))
            val accepted = response.isSuccess && ProtoBufCodec.decode<DeviceSessionTransferFeedbackSubscription>(response.payload).receiveUpdates
            sending.value = accepted
            if (!accepted) receiving.value = false
            return accepted
        } catch (cancel: CancellationException) { receiving.value = false; throw cancel }
        catch (_: Exception) { receiving.value = false; return false }
    }

    fun handle(request: DeviceSessionControlRequest): DeviceSessionControlResponse? {
        if (request.method != DEVICE_SESSION_RPC_SUBSCRIBE_TRANSFER_FEEDBACK && request.method != DEVICE_SESSION_RPC_TRANSFER_FEEDBACK) return null
        if (closed.value) return DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_FORBIDDEN)
        return try {
            if (request.method == DEVICE_SESSION_RPC_SUBSCRIBE_TRANSFER_FEEDBACK) {
                val subscription = ProtoBufCodec.decode<DeviceSessionTransferFeedbackSubscription>(request.payload)
                receiving.value = subscription.receiveUpdates
                sending.value = subscription.receiveUpdates
                if (!subscription.receiveUpdates) received.update { it.copy(status = null) }
                DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK,
                    ProtoBufCodec.encode(DeviceSessionTransferFeedbackSubscription(subscription.receiveUpdates)))
            } else {
                if (!receiving.value) return DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_FORBIDDEN)
                val update = ProtoBufCodec.decode<DeviceSessionTransferFeedbackUpdate>(request.payload)
                require(update.sequence > 0 && update.status.version == 1 && update.status.sampledAtMillis > 0)
                val raw = update.status.clamped()
                val bounded = raw.copy(maxParallelRequests = minOf(raw.maxParallelRequests, limits.maxParallelRequests),
                    maxChunkBytes = minOf(raw.maxChunkBytes, limits.maxChunkBytes),
                    activeRequests = minOf(raw.activeRequests, raw.maxParallelRequests, limits.maxParallelRequests)).clamped()
                val arrived = request.receivedAtMillis ?: nowMillis()
                val now = nowMillis()
                val fresh = (request.receivedAtMillis == null || arrived > 0) && now >= arrived &&
                    now - arrived < HTTP_TRANSFER_STATUS_FRESH_MILLIS
                received.update { previous ->
                    if (update.sequence > previous.sequence && receiving.value && !closed.value)
                        Received(update.sequence, bounded.takeIf { fresh }, arrived) else previous
                }
                DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK)
            }
        } catch (_: Exception) { DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_BAD_REQUEST) }
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        receiving.value = false
        sending.value = false
        received.update { it.copy(status = null) }
        pending.close()
        withContext(NonCancellable) { jobs.forEach { it.cancelAndJoin() } }
    }
}

package com.folderspan.service.webrtc

import com.folderspan.service.session.DeviceSessionByteChannel
import com.folderspan.service.session.DeviceSessionIoException
import com.folderspan.service.webrtc.models.WebRtcDataChannel
import com.folderspan.service.webrtc.models.WebRtcDataChannelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal const val WEB_RTC_DEVICE_SESSION_PROTOCOL_VERSION = 1
internal const val WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL = "folderspan-device-session-v1"

internal data class WebRtcDeviceSessionChannelOptions(
    val label: String = WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL,
    val ordered: Boolean = true,
    val maxRetransmits: Int? = null,
)

internal data class WebRtcDeviceSessionCarrierLimits(
    val messageBytes: Int = 16 * 1024,
    val maxInboundMessageBytes: Int = messageBytes,
    val maxInboundQueuedBytes: Long = 8L * 1024L * 1024L,
    val maxInboundMessages: Int = 512,
    val bufferedAmountHighBytes: Long = 4L * 1024L * 1024L,
    val bufferedAmountLowBytes: Long = 1L * 1024L * 1024L,
    val bufferedAmountTimeout: Duration = 30.seconds,
    val bufferedAmountPollInterval: Duration = 5.milliseconds,
) {
    init {
        require(messageBytes > 0) { "messageBytes must be positive" }
        require(maxInboundMessageBytes >= messageBytes) {
            "maxInboundMessageBytes must allow negotiated carrier messages"
        }
        require(maxInboundQueuedBytes >= maxInboundMessageBytes) {
            "maxInboundQueuedBytes must allow at least one carrier message"
        }
        require(maxInboundMessages > 0) { "maxInboundMessages must be positive" }
        require(bufferedAmountHighBytes > 0L) { "bufferedAmountHighBytes must be positive" }
        require(bufferedAmountLowBytes in 0L until bufferedAmountHighBytes) {
            "bufferedAmountLowBytes must be below the high watermark"
        }
        require(bufferedAmountTimeout.isPositive()) { "bufferedAmountTimeout must be positive" }
        require(bufferedAmountPollInterval.isPositive()) {
            "bufferedAmountPollInterval must be positive"
        }
    }
}

/**
 * Adapts an ordered, reliable WebRTC DataChannel to the continuous byte stream expected by
 * Device Session. DataChannel message boundaries are deliberately hidden from the Session layer.
 */
internal class WebRtcDeviceSessionByteChannel(
    private val dataChannel: WebRtcDataChannel,
    scope: CoroutineScope,
    private val limits: WebRtcDeviceSessionCarrierLimits = WebRtcDeviceSessionCarrierLimits(),
    carrierFailures: Flow<Throwable> = emptyFlow(),
) : DeviceSessionByteChannel {
    private val inbound = Channel<ByteArray>(capacity = limits.maxInboundMessages)
    private val inboundBytesMutex = Mutex()
    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private val lifecycleJobs = mutableListOf<Job>()

    private var pendingInboundBytes = 0L
    private var currentInbound: ByteArray? = null
    private var currentInboundOffset = 0
    private var terminalCause: Throwable? = null
    private var closed = false

    init {
        require(dataChannel.label == WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL) {
            "unsupported Device Session DataChannel label: ${dataChannel.label}"
        }
        lifecycleJobs += dataChannel.onMessage
            .onEach(::enqueueIncoming)
            .catch { error -> fail(carrierError("receiving failed", error)) }
            .launchIn(scope)
        lifecycleJobs += dataChannel.onClose
            .onEach { fail(carrierError("DataChannel closed")) }
            .catch { error -> fail(carrierError("close observation failed", error)) }
            .launchIn(scope)
        lifecycleJobs += carrierFailures
            .onEach { error -> fail(carrierError("PeerConnection closed", error)) }
            .catch { error -> fail(carrierError("PeerConnection observation failed", error)) }
            .launchIn(scope)
        if (dataChannel.state == WebRtcDataChannelState.Closed) {
            fail(carrierError("DataChannel is already closed"))
        }
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkRange(buffer, offset, length)
        if (length == 0) return 0
        return readMutex.withLock {
            var copied = 0
            while (copied < length) {
                val current = currentInbound
                    ?: (if (copied > 0) tryReceiveNext() else receiveNext())
                    ?: break
                val available = current.size - currentInboundOffset
                val count = minOf(length - copied, available)
                current.copyInto(
                    destination = buffer,
                    destinationOffset = offset + copied,
                    startIndex = currentInboundOffset,
                    endIndex = currentInboundOffset + count,
                )
                currentInboundOffset += count
                copied += count
                inboundBytesMutex.withLock {
                    pendingInboundBytes = (pendingInboundBytes - count).coerceAtLeast(0L)
                }
                if (currentInboundOffset == current.size) {
                    currentInbound = null
                    currentInboundOffset = 0
                }
            }
            if (copied > 0) copied else closedReadResult()
        }
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        checkRange(buffer, offset, length)
        if (length == 0) return
        writeMutex.withLock {
            var sourceOffset = offset
            val endOffset = offset + length
            while (sourceOffset < endOffset) {
                ensureOpenForWrite()
                awaitWritableBuffer()
                val chunkLength = minOf(limits.messageBytes, endOffset - sourceOffset)
                val chunk = buffer.copyOfRange(sourceOffset, sourceOffset + chunkLength)
                if (!dataChannel.send(chunk)) {
                    val error = carrierError("DataChannel rejected Session bytes")
                    fail(error)
                    throw error
                }
                sourceOffset += chunkLength
            }
        }
    }

    override suspend fun flush() = Unit

    override fun close() {
        closeInternal(cause = null, closeDataChannel = true)
    }

    private suspend fun enqueueIncoming(message: ByteArray) {
        if (message.isEmpty()) return
        if (message.size > limits.maxInboundMessageBytes) {
            fail(
                carrierError(
                    "carrier message exceeds ${limits.maxInboundMessageBytes} bytes: ${message.size}",
                ),
            )
            return
        }
        val accepted = inboundBytesMutex.withLock {
            if (closed) return@withLock false
            val updatedBytes = pendingInboundBytes + message.size
            if (updatedBytes > limits.maxInboundQueuedBytes) {
                return@withLock false
            }
            val result = inbound.trySend(message)
            if (result.isFailure) return@withLock false
            pendingInboundBytes = updatedBytes
            true
        }
        if (!accepted && !closed) {
            fail(
                carrierError(
                    "carrier inbound queue exceeded ${limits.maxInboundMessages} messages or " +
                        "${limits.maxInboundQueuedBytes} bytes",
                ),
            )
        }
    }

    private fun tryReceiveNext(): ByteArray? {
        val received = inbound.tryReceive()
        val message = received.getOrNull()
        if (message != null) {
            currentInbound = message
            currentInboundOffset = 0
            return message
        }
        received.exceptionOrNull()?.let { throw it }
        return null
    }

    private suspend fun receiveNext(): ByteArray? {
        val received = inbound.receiveCatching()
        val message = received.getOrNull()
        if (message != null) {
            currentInbound = message
            currentInboundOffset = 0
            return message
        }
        received.exceptionOrNull()?.let { throw it }
        return null
    }

    private fun closedReadResult(): Int {
        terminalCause?.let { throw it }
        return -1
    }

    private suspend fun awaitWritableBuffer() {
        if (dataChannel.bufferedAmount < limits.bufferedAmountHighBytes) return
        try {
            withTimeout(limits.bufferedAmountTimeout) {
                while (dataChannel.bufferedAmount > limits.bufferedAmountLowBytes) {
                    ensureOpenForWrite()
                    delay(limits.bufferedAmountPollInterval)
                }
            }
        } catch (error: TimeoutCancellationException) {
            val failure = carrierError(
                "DataChannel bufferedAmount did not drain below ${limits.bufferedAmountLowBytes} bytes",
                error,
            )
            fail(failure)
            throw failure
        } catch (error: CancellationException) {
            throw error
        }
    }

    private fun ensureOpenForWrite() {
        terminalCause?.let { throw it }
        if (closed || dataChannel.state != WebRtcDataChannelState.Open) {
            val error = carrierError("DataChannel is not open: ${dataChannel.state}")
            fail(error)
            throw error
        }
    }

    private fun fail(error: Throwable) {
        closeInternal(cause = error, closeDataChannel = true)
    }

    private fun closeInternal(cause: Throwable?, closeDataChannel: Boolean) {
        if (closed) return
        closed = true
        terminalCause = cause
        inbound.close(cause)
        lifecycleJobs.forEach(Job::cancel)
        lifecycleJobs.clear()
        if (closeDataChannel) runCatching { dataChannel.close() }
    }

    private fun carrierError(message: String, cause: Throwable? = null): DeviceSessionIoException {
        return DeviceSessionIoException("WebRTC Device Session carrier: $message", cause)
    }

    private fun checkRange(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
            "invalid byte channel range: offset=$offset length=$length size=${buffer.size}"
        }
    }
}

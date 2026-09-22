package com.folderspan.service.webrtc

import com.folderspan.service.session.DeviceSessionIo
import com.folderspan.service.session.DeviceSessionIoException
import com.folderspan.service.webrtc.models.WebRtcDataChannel
import com.folderspan.service.webrtc.models.WebRtcDataChannelState
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class WebRtcDeviceSessionByteChannelTest {
    @Test
    fun sessionChannelUsesVersionedReliableOrderedConfiguration() {
        val options = WebRtcDeviceSessionChannelOptions()

        assertEquals(1, WEB_RTC_DEVICE_SESSION_PROTOCOL_VERSION)
        assertEquals("folderspan-device-session-v1", options.label)
        assertTrue(options.ordered)
        assertEquals(null, options.maxRetransmits)
    }

    @Test
    fun writeFragmentsSessionBytesAtCarrierMessageLimit() = runSuspendTest {
        val scope = CoroutineScope(currentCoroutineContext())
        val dataChannel = FakeDataChannel()
        val channel = WebRtcDeviceSessionByteChannel(
            dataChannel = dataChannel,
            scope = scope,
            limits = testLimits(messageBytes = 4),
        )

        channel.writeAndFlush(ByteArray(10) { it.toByte() }, 0, 10)

        assertEquals(listOf(4, 4, 2), dataChannel.sent.map(ByteArray::size))
        assertContentEquals(byteArrayOf(0, 1, 2, 3), dataChannel.sent.first())
        channel.close()
    }

    @Test
    fun readReassemblesAcrossDataChannelMessageBoundaries() = runSuspendTest {
        val scope = CoroutineScope(currentCoroutineContext())
        val dataChannel = FakeDataChannel()
        val channel = WebRtcDeviceSessionByteChannel(dataChannel, scope, testLimits())
        yield()

        dataChannel.emitMessage(byteArrayOf(1, 2))
        dataChannel.emitMessage(byteArrayOf(3, 4, 5))
        val actual = DeviceSessionIo.readExact(channel, 5)

        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), actual)
        channel.close()
    }

    @Test
    fun frameHeaderMaySpanCarrierMessages() = runSuspendTest {
        val scope = CoroutineScope(currentCoroutineContext())
        val dataChannel = FakeDataChannel()
        val channel = WebRtcDeviceSessionByteChannel(dataChannel, scope, testLimits())
        val frameBytes = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        yield()

        dataChannel.emitMessage(frameBytes.copyOfRange(0, 3))
        dataChannel.emitMessage(frameBytes.copyOfRange(3, 5))
        dataChannel.emitMessage(frameBytes.copyOfRange(5, frameBytes.size))

        assertContentEquals(frameBytes, DeviceSessionIo.readExact(channel, frameBytes.size))
        channel.close()
    }

    @Test
    fun writeWaitsForBufferedAmountToReachLowWatermark() = runSuspendTest {
        val scope = CoroutineScope(currentCoroutineContext())
        val dataChannel = FakeDataChannel().apply { bufferedAmountValue = 9L }
        val channel = WebRtcDeviceSessionByteChannel(
            dataChannel,
            scope,
            testLimits(highBytes = 8L, lowBytes = 3L),
        )
        val completed = CompletableDeferred<Unit>()
        val write = scope.async {
            channel.write(byteArrayOf(1), 0, 1)
            completed.complete(Unit)
        }
        yield()

        assertFalse(completed.isCompleted)
        assertTrue(dataChannel.sent.isEmpty())
        dataChannel.bufferedAmountValue = 3L
        withTimeout(500L) { write.await() }

        assertEquals(1, dataChannel.sent.size)
        channel.close()
    }

    @Test
    fun bufferedAmountIsSampledAtWatermarkAcrossWritesInsteadOfPerFragment() = runSuspendTest {
        val dataChannel = FakeDataChannel()
        val channel = WebRtcDeviceSessionByteChannel(
            dataChannel, CoroutineScope(currentCoroutineContext()),
        )
        try {
            // A draining native channel stays at zero, but each send still consumes
            // the conservative budget, including sends from separate write calls.
            repeat(4) { channel.write(ByteArray(1024 * 1024), 0, 1024 * 1024) }
            assertEquals(256, dataChannel.sent.size)
            assertEquals(1, dataChannel.bufferedAmountReads)

            channel.write(byteArrayOf(1), 0, 1)
            assertEquals(2, dataChannel.bufferedAmountReads)
        } finally {
            channel.close()
        }
    }

    @Test
    fun bufferedLimitStillAppliesAcrossBatchesAndConcurrentWriters() = runSuspendTest {
        val scope = CoroutineScope(currentCoroutineContext())
        val dataChannel = FakeDataChannel().apply { accountSentBytes = true }
        val channel = WebRtcDeviceSessionByteChannel(
            dataChannel, scope, testLimits(messageBytes = 3, highBytes = 8, lowBytes = 2),
        )
        try {
            channel.write(ByteArray(6), 0, 6)
            val nextBatch = scope.async(start = CoroutineStart.UNDISPATCHED) { channel.write(ByteArray(6), 0, 6) }
            val competingWriter = scope.async { channel.write(ByteArray(3), 0, 3) }
            yield()
            assertEquals(9L, dataChannel.bufferedAmountValue)
            assertEquals(9, dataChannel.sent.sumOf { it.size })
            assertFalse(nextBatch.isCompleted)
            assertFalse(competingWriter.isCompleted)
            dataChannel.bufferedAmountValue = 2
            withTimeout(500L) { nextBatch.await(); competingWriter.await() }
            assertEquals(15, dataChannel.sent.sumOf { it.size })
            assertEquals(8L, dataChannel.bufferedAmountValue)
        } finally {
            channel.close()
        }
    }

    @Test
    fun bufferedAmountTimeoutFailsWriteAndClosesCarrier() = runSuspendTest {
        val scope = CoroutineScope(currentCoroutineContext())
        val dataChannel = FakeDataChannel().apply { bufferedAmountValue = 9L }
        val channel = WebRtcDeviceSessionByteChannel(
            dataChannel,
            scope,
            testLimits(
                highBytes = 8L,
                lowBytes = 3L,
                timeoutMillis = 25L,
            ),
        )

        assertFailsWith<DeviceSessionIoException> {
            channel.write(byteArrayOf(1), 0, 1)
        }
        assertTrue(dataChannel.wasClosed)
    }

    @Test
    fun sendFailurePropagatesAndClosesCarrier() = runSuspendTest {
        val scope = CoroutineScope(currentCoroutineContext())
        val dataChannel = FakeDataChannel().apply { allowSend = false }
        val channel = WebRtcDeviceSessionByteChannel(dataChannel, scope, testLimits())

        assertFailsWith<DeviceSessionIoException> {
            channel.write(byteArrayOf(1), 0, 1)
        }
        assertTrue(dataChannel.wasClosed)
    }

    @Test
    fun dataChannelCloseWakesPendingRead() = runSuspendTest {
        val scope = CoroutineScope(currentCoroutineContext())
        val dataChannel = FakeDataChannel()
        val channel = WebRtcDeviceSessionByteChannel(dataChannel, scope, testLimits())
        yield()
        val read = scope.async {
            runCatching { channel.read(ByteArray(1), 0, 1) }
        }
        yield()

        dataChannel.emitRemoteClose()

        val result = withTimeout(500L) { read.await() }
        assertTrue(result.exceptionOrNull() is DeviceSessionIoException)
    }

    @Test
    fun peerConnectionFailureWakesPendingRead() = runSuspendTest {
        val scope = CoroutineScope(currentCoroutineContext())
        val dataChannel = FakeDataChannel()
        val failure = DeviceSessionIoException("peer failed")
        val channel = WebRtcDeviceSessionByteChannel(
            dataChannel = dataChannel,
            scope = scope,
            limits = testLimits(),
            carrierFailures = flowOf(failure),
        )

        assertFailsWith<DeviceSessionIoException> {
            withTimeout(500L) { channel.read(ByteArray(1), 0, 1) }
        }
        assertTrue(dataChannel.wasClosed)
    }

    @Test
    fun oversizedInboundMessageClosesCarrierBeforeSessionRead() = runSuspendTest {
        val scope = CoroutineScope(currentCoroutineContext())
        val dataChannel = FakeDataChannel()
        val channel = WebRtcDeviceSessionByteChannel(
            dataChannel,
            scope,
            testLimits(messageBytes = 4, maxInboundMessageBytes = 4),
        )
        yield()

        dataChannel.emitMessage(ByteArray(5))

        assertFailsWith<DeviceSessionIoException> {
            withTimeout(500L) { channel.read(ByteArray(1), 0, 1) }
        }
        assertTrue(dataChannel.wasClosed)
    }

    @Test
    fun inboundByteLimitClosesCarrier() = runSuspendTest {
        val scope = CoroutineScope(currentCoroutineContext())
        val dataChannel = FakeDataChannel()
        val channel = WebRtcDeviceSessionByteChannel(
            dataChannel,
            scope,
            testLimits(
                messageBytes = 4,
                maxInboundMessageBytes = 4,
                maxInboundQueuedBytes = 5L,
            ),
        )
        yield()

        dataChannel.emitMessage(ByteArray(4))
        dataChannel.emitMessage(ByteArray(2))
        yield()

        assertTrue(dataChannel.wasClosed)
        assertFailsWith<DeviceSessionIoException> {
            channel.read(ByteArray(8), 0, 8)
        }
    }

    @Test
    fun concurrentWritesRemainMessageOrdered() = runSuspendTest {
        val scope = CoroutineScope(currentCoroutineContext())
        val dataChannel = FakeDataChannel()
        val channel = WebRtcDeviceSessionByteChannel(
            dataChannel,
            scope,
            testLimits(messageBytes = 2),
        )

        val first = scope.async { channel.write(byteArrayOf(1, 1, 1, 1), 0, 4) }
        val second = scope.async { channel.write(byteArrayOf(2, 2, 2, 2), 0, 4) }
        first.await()
        second.await()

        val flattened = dataChannel.sent.flatMap { it.asIterable() }
        assertTrue(
            flattened == listOf<Byte>(1, 1, 1, 1, 2, 2, 2, 2) ||
                flattened == listOf<Byte>(2, 2, 2, 2, 1, 1, 1, 1),
        )
        channel.close()
    }

    private fun testLimits(
        messageBytes: Int = 8,
        maxInboundMessageBytes: Int = messageBytes,
        maxInboundQueuedBytes: Long = 64L,
        highBytes: Long = 32L,
        lowBytes: Long = 8L,
        timeoutMillis: Long = 250L,
    ): WebRtcDeviceSessionCarrierLimits = WebRtcDeviceSessionCarrierLimits(
        messageBytes = messageBytes,
        maxInboundMessageBytes = maxInboundMessageBytes,
        maxInboundQueuedBytes = maxInboundQueuedBytes,
        maxInboundMessages = 8,
        bufferedAmountHighBytes = highBytes,
        bufferedAmountLowBytes = lowBytes,
        bufferedAmountTimeout = timeoutMillis.milliseconds,
        bufferedAmountPollInterval = 1.milliseconds,
    )

    private class FakeDataChannel : WebRtcDataChannel {
        private val openEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        private val closeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        private val messages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)

        var stateValue = WebRtcDataChannelState.Open
        var bufferedAmountValue = 0L
        var bufferedAmountReads = 0
        var accountSentBytes = false
        var allowSend = true
        var wasClosed = false
        val sent = mutableListOf<ByteArray>()

        override val label: String = WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL
        override val state: WebRtcDataChannelState
            get() = stateValue
        override val bufferedAmount: Long
            get() {
                bufferedAmountReads++
                return bufferedAmountValue
            }
        override val onOpen: Flow<Unit> = openEvents.asSharedFlow()
        override val onClose: Flow<Unit> = closeEvents.asSharedFlow()
        override val onMessage: Flow<ByteArray> = messages.asSharedFlow()

        override fun send(data: ByteArray): Boolean {
            if (!allowSend || stateValue != WebRtcDataChannelState.Open) return false
            sent += data.copyOf()
            if (accountSentBytes) bufferedAmountValue += data.size
            return true
        }

        override fun close() {
            wasClosed = true
            stateValue = WebRtcDataChannelState.Closed
            closeEvents.tryEmit(Unit)
        }

        suspend fun emitMessage(data: ByteArray) {
            messages.emit(data)
        }

        suspend fun emitRemoteClose() {
            stateValue = WebRtcDataChannelState.Closed
            closeEvents.emit(Unit)
        }
    }
}

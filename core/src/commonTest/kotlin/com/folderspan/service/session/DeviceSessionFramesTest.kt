package com.folderspan.service.session

import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.service.data.DeviceConnectAuthorizationMode
import com.folderspan.service.data.DeviceConnectResponse
import com.folderspan.service.http.archive.FolderSpanArchiveCompressionCodec
import com.folderspan.service.http.archive.FolderSpanArchiveEntryRequest
import com.folderspan.service.http.archive.FolderSpanArchiveReadRequest
import com.folderspan.service.http.archive.FolderSpanArchiveStreamCapabilities
import com.folderspan.service.http.archive.FolderSpanArchiveStreamOptions
import com.folderspan.service.operation.HttpTransferRuntimeMemoryStatus
import com.folderspan.test.runSuspendTest
import com.folderspan.ui.state.device.DeviceSharePathGrant
import com.folderspan.ui.state.device.DeviceSharePathScope
import com.folderspan.utils.ProtoBufCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

class DeviceSessionFramesTest {
    @Test
    fun encodeDecodeRoundTripPreservesLittleEndianHeader() {
        val payload = byteArrayOf(9, 8, 7, 6)
        val encoded = DeviceSessionFrames.encode(
            DeviceSessionFrame(
                type = DeviceSessionFrameType.Data,
                streamId = 7,
                payload = payload,
            ),
        )

        assertEquals(DEVICE_SESSION_HEADER_SIZE + payload.size, encoded.size)
        assertEquals(DeviceSessionFrameType.Data.code.toByte(), encoded[0])
        assertEquals(7, DeviceSessionFrames.readIntLe(encoded, 1))
        assertEquals(payload.size, DeviceSessionFrames.readIntLe(encoded, 5))

        val decoded = DeviceSessionFrames.decode(encoded)
        val frame = assertIs<DeviceSessionDecodeResult.Frame>(decoded).frame
        assertEquals(DeviceSessionFrameType.Data, frame.type)
        assertEquals(7, frame.streamId)
        assertTrue(payload.contentEquals(frame.payload))
    }

    @Test
    fun decodeRejectsUnknownTypeOversizedAndTruncatedFrames() {
        val unknown = DeviceSessionFrames.encode(
            DeviceSessionFrame(type = DeviceSessionFrameType.Ping, streamId = 0, payload = byteArrayOf(1)),
        )
        unknown[0] = 99
        assertEquals(
            DeviceSessionProtocolError.UnknownType,
            (DeviceSessionFrames.decode(unknown) as DeviceSessionDecodeResult.Invalid).error,
        )

        val oversizedLength = DEVICE_SESSION_MAX_PAYLOAD_BYTES + 1
        val oversized = ByteArray(DEVICE_SESSION_HEADER_SIZE)
        oversized[0] = DeviceSessionFrameType.Data.code.toByte()
        oversized[5] = (oversizedLength and 0xFF).toByte()
        oversized[6] = ((oversizedLength ushr 8) and 0xFF).toByte()
        oversized[7] = ((oversizedLength ushr 16) and 0xFF).toByte()
        oversized[8] = ((oversizedLength ushr 24) and 0xFF).toByte()
        assertEquals(
            DeviceSessionProtocolError.OversizedPayload,
            (DeviceSessionFrames.decode(oversized) as DeviceSessionDecodeResult.Invalid).error,
        )

        val truncated = DeviceSessionFrames.encode(
            DeviceSessionFrame(type = DeviceSessionFrameType.Data, streamId = 3, payload = byteArrayOf(1, 2, 3)),
        ).copyOf(DEVICE_SESSION_HEADER_SIZE + 1)
        assertEquals(
            DeviceSessionProtocolError.Truncated,
            (DeviceSessionFrames.decode(truncated) as DeviceSessionDecodeResult.Invalid).error,
        )
    }

    @Test
    fun encodeRejectsPayloadAboveCap() {
        assertFailsWith<IllegalArgumentException> {
            DeviceSessionFrames.encode(
                DeviceSessionFrame(
                    type = DeviceSessionFrameType.Data,
                    streamId = 1,
                    payload = ByteArray(DEVICE_SESSION_MAX_PAYLOAD_BYTES + 1),
                ),
            )
        }
    }
}

class DeviceSessionCreditTest {
    @Test
    fun unknownMemoryUsesWideSessionWithAFairPerStreamWindow() = runSuspendTest {
        val plan = DeviceSessionWindowPlan.fromMemory(HttpTransferRuntimeMemoryStatus.unknown())
        val credit = DeviceSessionCredit(plan)
        val frameCapacity = plan.streamWindowBytes / DEVICE_SESSION_MAX_PAYLOAD_BYTES

        repeat(frameCapacity) {
            assertTrue(
                credit.tryConsume(
                    streamId = 1,
                    bytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES,
                ),
            )
        }

        assertEquals(16, frameCapacity)
        assertEquals(plan.streamWindowBytes, credit.inFlightBytes())
        assertFalse(credit.tryConsume(streamId = 1, bytes = 1))
        assertTrue(credit.tryConsume(streamId = 2, bytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES))
    }

    @Test
    fun criticalMemoryClampsSessionWindow() {
        val plan = DeviceSessionWindowPlan.fromMemory(
            HttpTransferRuntimeMemoryStatus(
                availableHeapBytes = 4L * 1024L * 1024L,
                maxHeapBytes = 64L * 1024L * 1024L,
                lowMemory = true,
            ),
        )
        assertEquals(DEVICE_SESSION_CRITICAL_WINDOW_BYTES, plan.sessionWindowBytes)
        assertEquals(DEVICE_SESSION_CRITICAL_WINDOW_BYTES, plan.streamWindowBytes)
        assertEquals(1, plan.maxFileStreams)
    }

    @Test
    fun frameQueueCapacityTracksSmallWindowsAndCapsWideWindows() {
        val critical = DeviceSessionWindowPlan(
            sessionWindowBytes = DEVICE_SESSION_CRITICAL_WINDOW_BYTES,
            streamWindowBytes = DEVICE_SESSION_CRITICAL_WINDOW_BYTES,
            maxFileStreams = 1,
        )
        val wide = DeviceSessionWindowPlan(
            sessionWindowBytes = DEVICE_SESSION_WIDE_WINDOW_BYTES,
            streamWindowBytes = DEVICE_SESSION_WIDE_WINDOW_BYTES,
            maxFileStreams = 16,
        )

        assertEquals(4, critical.frameQueueCapacity())
        assertEquals(DEVICE_SESSION_MAX_FRAME_QUEUE_CAPACITY, wide.frameQueueCapacity())
        assertEquals(DEVICE_SESSION_MAX_FRAME_QUEUE_CAPACITY, wide.streamFrameQueueCapacity())
    }

    @Test
    fun smallHeapKeepsAFullStreamWindowUntilAvailableMemoryIsTight() {
        fun plan(availableMiB: Long, lowMemory: Boolean = false) = DeviceSessionWindowPlan.fromMemory(
            HttpTransferRuntimeMemoryStatus(
                availableHeapBytes = availableMiB * 1024L * 1024L,
                maxHeapBytes = 256L * 1024L * 1024L,
                lowMemory = lowMemory,
            ),
        )

        for (availableMiB in listOf(16L, 64L, 128L)) {
            val healthy = plan(availableMiB)
            assertEquals(1024 * 1024, healthy.streamWindowBytes)
            assertEquals(4 * 1024 * 1024, healthy.sessionWindowBytes)
            assertEquals(4, healthy.maxFileStreams)
            assertEquals(16, healthy.streamFrameQueueCapacity())
        }
        assertEquals(256 * 1024, plan(15).streamWindowBytes)
        assertEquals(1024 * 1024, plan(15).sessionWindowBytes)
        assertEquals(256 * 1024, plan(7).sessionWindowBytes)
        assertEquals(256 * 1024, plan(128, lowMemory = true).sessionWindowBytes)
    }

    @Test
    fun memoryPlansGiveEachStreamAFairShareOfTheSessionWindow() {
        val tight = DeviceSessionWindowPlan.fromMemory(
            HttpTransferRuntimeMemoryStatus(
                availableHeapBytes = 12L * 1024L * 1024L,
                maxHeapBytes = 512L * 1024L * 1024L,
            ),
        )
        val ample = DeviceSessionWindowPlan.fromMemory(
            HttpTransferRuntimeMemoryStatus(
                availableHeapBytes = 24L * 1024L * 1024L,
                maxHeapBytes = 512L * 1024L * 1024L,
            ),
        )
        val wide = DeviceSessionWindowPlan.fromMemory(
            HttpTransferRuntimeMemoryStatus(
                availableHeapBytes = 128L * 1024L * 1024L,
                maxHeapBytes = 512L * 1024L * 1024L,
            ),
        )

        assertEquals(DEVICE_SESSION_CRITICAL_WINDOW_BYTES, tight.streamWindowBytes)
        assertEquals(DEVICE_SESSION_TIGHT_WINDOW_BYTES, ample.streamWindowBytes)
        assertEquals(DEVICE_SESSION_TIGHT_WINDOW_BYTES, wide.streamWindowBytes)
        listOf(tight, ample, wide).forEach { plan ->
            assertTrue(
                plan.streamWindowBytes.toLong() * plan.maxFileStreams <= plan.sessionWindowBytes.toLong(),
            )
        }
    }

    @Test
    fun transferStatusUsesTheStricterLocalAndRemoteSessionLimits() {
        val plan = DeviceSessionWindowPlan(
            sessionWindowBytes = DEVICE_SESSION_WIDE_WINDOW_BYTES,
            streamWindowBytes = DEVICE_SESSION_WIDE_WINDOW_BYTES,
            maxFileStreams = 16,
        )

        val status = plan.toTransferStatus(
            remoteMaxFileStreams = 8,
            remoteRecommendedChunkBytes = 512 * 1024,
            sampledAtMillis = 123L,
        )

        assertEquals(8, status.recommendedParallelRequests)
        assertEquals(8, status.maxParallelRequests)
        assertEquals(512 * 1024, status.recommendedChunkBytes)
        assertEquals(512 * 1024, status.maxChunkBytes)
        assertEquals(123L, status.sampledAtMillis)
    }

    @Test
    fun transferStatusRejectsMissingRemoteSessionCapabilities() {
        val plan = DeviceSessionWindowPlan.fromMemory(HttpTransferRuntimeMemoryStatus.unknown())

        assertFailsWith<IllegalArgumentException> {
            plan.toTransferStatus(
                remoteMaxFileStreams = 0,
                remoteRecommendedChunkBytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES,
                sampledAtMillis = 1L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            plan.toTransferStatus(
                remoteMaxFileStreams = 1,
                remoteRecommendedChunkBytes = 0,
                sampledAtMillis = 1L,
            )
        }
    }

    @Test
    fun transferStatusKeepsAggregateChunkAllocationInsideSessionWindow() {
        val plans = listOf(
            DeviceSessionWindowPlan(
                sessionWindowBytes = DEVICE_SESSION_CRITICAL_WINDOW_BYTES,
                streamWindowBytes = DEVICE_SESSION_CRITICAL_WINDOW_BYTES,
                maxFileStreams = 1,
            ),
            DeviceSessionWindowPlan(
                sessionWindowBytes = DEVICE_SESSION_TIGHT_WINDOW_BYTES,
                streamWindowBytes = DEVICE_SESSION_TIGHT_WINDOW_BYTES,
                maxFileStreams = 4,
            ),
            DeviceSessionWindowPlan(
                sessionWindowBytes = DEVICE_SESSION_AMPLE_WINDOW_BYTES,
                streamWindowBytes = DEVICE_SESSION_AMPLE_WINDOW_BYTES,
                maxFileStreams = 8,
            ),
            DeviceSessionWindowPlan(
                sessionWindowBytes = DEVICE_SESSION_WIDE_WINDOW_BYTES,
                streamWindowBytes = DEVICE_SESSION_WIDE_WINDOW_BYTES,
                maxFileStreams = 16,
            ),
        )

        plans.forEach { plan ->
            val status = plan.toTransferStatus(
                remoteMaxFileStreams = plan.maxFileStreams,
                remoteRecommendedChunkBytes = plan.recommendedChunkBytes(),
                sampledAtMillis = 1L,
            )
            assertTrue(
                status.recommendedChunkBytes.toLong() * status.recommendedParallelRequests <=
                    plan.sessionWindowBytes.toLong(),
            )
        }
    }

    @Test
    fun zeroCreditBlocksUntilWindowUpdate() = runSuspendTest {
        val credit = DeviceSessionCredit(
            DeviceSessionWindowPlan(
                sessionWindowBytes = 8,
                streamWindowBytes = 8,
                maxFileStreams = 1,
            ),
        )
        assertTrue(credit.tryConsume(streamId = 1, bytes = 8))
        assertFalse(credit.tryConsume(streamId = 1, bytes = 1))
        assertEquals(0, credit.remainingSessionBytes())

        coroutineScope {
            val waiter = async {
                credit.awaitAndConsume(streamId = 1, bytes = 4)
                true
            }
            delay(10)
            assertFalse(waiter.isCompleted)
            credit.grant(streamId = 1, bytes = 8)
            assertTrue(waiter.await())
        }
        assertEquals(4, credit.inFlightBytes())
    }
}

class DeviceSessionConnectionTest {
    @Test
    fun cancelledWriterDoesNotConsumeAvailableCredit() = runSuspendTest {
        coroutineScope {
            val outgoing = Channel<DeviceSessionFrame>(Channel.UNLIMITED)
            val credit = DeviceSessionCredit(DeviceSessionWindowPlan(8, 8, 1))
            val connection = DeviceSessionConnection(
                outgoing, Channel(Channel.UNLIMITED), credit, isClient = true,
            )
            try {
                val stream = connection.openStream()
                outgoing.receive()
                var failure: Throwable? = null
                launch {
                    currentCoroutineContext().cancel()
                    failure = runCatching { connection.sendData(stream, byteArrayOf(1)) }.exceptionOrNull()
                }.join()
                assertIs<CancellationException>(failure)
                assertEquals(0, credit.inFlightBytes())
                assertTrue(outgoing.tryReceive().isFailure)
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun missingWindowUpdateFailsInsteadOfBlockingForever() = runSuspendTest {
        val plan = DeviceSessionWindowPlan(
            sessionWindowBytes = 8,
            streamWindowBytes = 8,
            maxFileStreams = 1,
        )
        val connection = DeviceSessionConnection(
            outgoing = Channel<DeviceSessionFrame>(Channel.UNLIMITED),
            incoming = Channel<DeviceSessionFrame>(Channel.UNLIMITED),
            sendCredit = DeviceSessionCredit(plan),
            isClient = true,
            creditWaitTimeoutMillis = 10L,
        )
        val streamId = connection.openStream()
        connection.sendData(streamId, ByteArray(8))

        val failure = assertFailsWith<DeviceSessionIoException> {
            connection.sendData(streamId, byteArrayOf(1))
        }

        assertTrue(failure.message.orEmpty().contains("credit timeout"))
        connection.close()
    }

    @Test
    fun incomingFrameQueueBackpressuresBeforeProcessingLaterWindowUpdates() = runSuspendTest {
        val plan = DeviceSessionWindowPlan(
            sessionWindowBytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES,
            streamWindowBytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES,
            maxFileStreams = 1,
        )
        val incoming = Channel<DeviceSessionFrame>(Channel.UNLIMITED)
        val outgoing = Channel<DeviceSessionFrame>(Channel.UNLIMITED)
        val credit = DeviceSessionCredit(plan)
        val streamId = DeviceSessionFrames.clientStreamId(0)
        assertTrue(credit.tryConsume(streamId, 1))
        val connection = DeviceSessionConnection(
            outgoing = outgoing,
            incoming = incoming,
            sendCredit = credit,
            isClient = false,
            incomingFrameCapacity = 1,
        )

        coroutineScope {
            val runner = launch { connection.run() }
            incoming.send(DeviceSessionFrame(DeviceSessionFrameType.Open, streamId))
            incoming.send(DeviceSessionFrame(DeviceSessionFrameType.Data, streamId, byteArrayOf(7)))
            incoming.send(DeviceSessionFrame(DeviceSessionFrameType.Data, streamId, byteArrayOf(8)))
            incoming.send(DeviceSessionFrames.windowUpdate(streamId, 1))

            delay(10)
            assertEquals(1, credit.inFlightBytes())
            assertEquals(DeviceSessionFrameType.Open, connection.incomingEvents.receive().type)
            delay(10)
            assertEquals(1, credit.inFlightBytes())
            assertEquals(DeviceSessionFrameType.Data, connection.incomingEvents.receive().type)
            assertEquals(DeviceSessionFrameType.Data, connection.incomingEvents.receive().type)
            while (credit.inFlightBytes() != 0) yield()

            incoming.close()
            runner.join()
        }
    }

    @Test
    fun loopbackInterleavesDataAndAnswersPing() = runSuspendTest {
        val pair = InMemoryDeviceSessionPair(
            clientPlan = DeviceSessionWindowPlan(
                sessionWindowBytes = 64 * 1024,
                streamWindowBytes = 64 * 1024,
                maxFileStreams = 4,
            ),
        )
        pair.use { client, server ->
            val first = client.openStream()
            val second = client.openStream()
            val opened = mutableListOf<Int>()
            opened += assertIs<DeviceSessionFrame>(server.incomingEvents.receive()).streamId
            opened += assertIs<DeviceSessionFrame>(server.incomingEvents.receive()).streamId
            assertEquals(setOf(first, second), opened.toSet())

            client.sendData(first, byteArrayOf(1, 2))
            client.sendData(second, byteArrayOf(3, 4, 5))
            val firstData = server.incomingEvents.receive()
            val secondData = server.incomingEvents.receive()
            assertEquals(DeviceSessionFrameType.Data, firstData.type)
            assertEquals(first, firstData.streamId)
            assertTrue(byteArrayOf(1, 2).contentEquals(firstData.payload))
            assertEquals(second, secondData.streamId)
            assertTrue(byteArrayOf(3, 4, 5).contentEquals(secondData.payload))

            val pong = client.ping(byteArrayOf(9, 8, 7))
            assertTrue(byteArrayOf(9, 8, 7).contentEquals(pong))
        }
    }

    @Test
    fun streamCapRefusesAdditionalOpens() = runSuspendTest {
        val pair = InMemoryDeviceSessionPair(maxActiveStreams = 1)
        pair.use { client, _ ->
            client.openStream()
            assertFailsWith<DeviceSessionStreamLimitException> {
                client.openStream()
            }
        }
    }

    @Test
    fun rstAndGoAwayCloseTheSessionPath() = runSuspendTest {
        val pair = InMemoryDeviceSessionPair()
        pair.use { client, server ->
            val streamId = client.openStream()
            assertEquals(DeviceSessionFrameType.Open, server.incomingEvents.receive().type)
            client.sendRst(streamId, errorCode = 7)
            val rst = server.incomingEvents.receive()
            assertEquals(DeviceSessionFrameType.Rst, rst.type)
            assertEquals(7, DeviceSessionFrames.readIntLe(rst.payload))

            client.goAway()
            val goAway = server.incomingEvents.receive()
            assertEquals(DeviceSessionFrameType.GoAway, goAway.type)
            val received = server.receivedGoAway()
            requireNotNull(received)
            assertEquals(streamId, received.lastStreamId)
        }
    }
}

class DeviceSessionProtocolTest {
    @Test
    fun shareSessionAllowsOnlyVirtualPathListingAndReadStreams() {
        assertTrue(isDeviceShareSessionRpcAllowed(DEVICE_SESSION_RPC_ROOT_PATHS))
        assertTrue(isDeviceShareSessionRpcAllowed(DEVICE_SESSION_RPC_LIST_PATH))
        assertFalse(isDeviceShareSessionRpcAllowed(DEVICE_SESSION_RPC_GET_FILE_BY_PATH))
        assertFalse(isDeviceShareSessionRpcAllowed(DEVICE_SESSION_RPC_CREATE_FILES))
        assertTrue(isDeviceShareSessionStreamAllowed(DEVICE_SESSION_STREAM_READ))
        assertTrue(isDeviceShareSessionStreamAllowed(DEVICE_SESSION_STREAM_ARCHIVE_READ))
        assertFalse(isDeviceShareSessionStreamAllowed(DEVICE_SESSION_STREAM_WRITE))
        assertFalse(isDeviceShareSessionStreamAllowed(DEVICE_SESSION_STREAM_MESSAGE_BODY))
    }

    @Test
    fun shareArchiveReadResolvesEveryVirtualSourcePath() {
        val open = archiveReadOpen(
            listOf(
                FolderSpanArchiveEntryRequest(
                    sourcePath = "/node_modules/pkg-a/index.js",
                    relativePath = "pkg-a/index.js",
                    size = 12L,
                ),
                FolderSpanArchiveEntryRequest(
                    sourcePath = "/node_modules/pkg-b/package.json",
                    relativePath = "pkg-b/package.json",
                    size = 34L,
                ),
            ),
        )

        val resolved = resolveDeviceShareSessionStreamOpen(open, sharePathScope())

        requireNotNull(resolved)
        val request = ProtoBufCodec.decode<FolderSpanArchiveReadRequest>(resolved.payload)
        assertEquals(
            listOf(
                "/shared/node_modules/pkg-a/index.js",
                "/shared/node_modules/pkg-b/package.json",
            ),
            request.entries.map { entry -> entry.sourcePath },
        )
        assertEquals(
            listOf("pkg-a/index.js", "pkg-b/package.json"),
            request.entries.map { entry -> entry.relativePath },
        )
    }

    @Test
    fun shareReadStillResolvesVirtualSourcePath() {
        val open = DeviceSessionStreamOpen(
            kind = DEVICE_SESSION_STREAM_READ,
            path = "/node_modules/pkg-a/index.js",
        )

        val resolved = resolveDeviceShareSessionStreamOpen(open, sharePathScope())

        assertEquals("/shared/node_modules/pkg-a/index.js", resolved?.path)
    }

    @Test
    fun shareArchiveReadRejectsWholeBatchWhenOnePathIsUnauthorized() {
        val open = archiveReadOpen(
            listOf(
                FolderSpanArchiveEntryRequest(
                    sourcePath = "/node_modules/pkg-a/index.js",
                    relativePath = "pkg-a/index.js",
                ),
                FolderSpanArchiveEntryRequest(
                    sourcePath = "/private/secret.txt",
                    relativePath = "secret.txt",
                ),
            ),
        )

        assertEquals(null, resolveDeviceShareSessionStreamOpen(open, sharePathScope()))
    }

    @Test
    fun shareArchiveReadRejectsMalformedPayload() {
        val open = DeviceSessionStreamOpen(
            kind = DEVICE_SESSION_STREAM_ARCHIVE_READ,
            payload = byteArrayOf(0x7F),
        )

        assertEquals(null, resolveDeviceShareSessionStreamOpen(open, sharePathScope()))
    }

    @Test
    fun shareArchiveReadRejectsInvalidRelativePath() {
        val open = archiveReadOpen(
            listOf(
                FolderSpanArchiveEntryRequest(
                    sourcePath = "/node_modules/pkg-a/index.js",
                    relativePath = "../index.js",
                ),
            ),
        )

        assertEquals(null, resolveDeviceShareSessionStreamOpen(open, sharePathScope()))
    }

    private fun archiveReadOpen(entries: List<FolderSpanArchiveEntryRequest>): DeviceSessionStreamOpen {
        return DeviceSessionStreamOpen(
            kind = DEVICE_SESSION_STREAM_ARCHIVE_READ,
            payload = ProtoBufCodec.encode(
                FolderSpanArchiveReadRequest(
                    entries = entries,
                    options = FolderSpanArchiveStreamOptions(),
                ),
            ),
        )
    }

    private fun sharePathScope(): DeviceSharePathScope {
        return DeviceSharePathScope(
            grants = listOf(DeviceSharePathGrant("/shared/node_modules", isDirectory = true)),
            pathSeparator = "/",
        )
    }

    @Test
    fun connectResponseRoundTripRequiresExplicitSessionCapabilities() {
        val response = DeviceSessionConnectResponse(
            connection = DeviceConnectResponse(
                connectType = DeviceConnectType.APPROVED,
                token = "token",
                authorizationMode = DeviceConnectAuthorizationMode.STANDARD,
            ),
            maxFileStreams = 3,
            recommendedChunkBytes = 512 * 1024,
            archiveCapabilities = FolderSpanArchiveStreamCapabilities(
                codecs = listOf(FolderSpanArchiveCompressionCodec.NONE),
                maxEntries = 512,
                maxFileBytes = 1024 * 1024L,
                maxBatchBytes = 32 * 1024 * 1024L,
            ),
        )

        val restored = ProtoBufCodec.decode<DeviceSessionConnectResponse>(
            ProtoBufCodec.encode(response),
        )

        assertEquals(DeviceConnectType.APPROVED, restored.connection.connectType)
        assertEquals(3, restored.maxFileStreams)
        assertEquals(512 * 1024, restored.recommendedChunkBytes)
        assertEquals(listOf(FolderSpanArchiveCompressionCodec.NONE), restored.archiveCapabilities.codecs)
    }

    @Test
    fun connectResponseWithoutArchiveCapabilitiesIsRejected() {
        val legacy = LegacyDeviceSessionConnectResponse(
            connection = DeviceConnectResponse(
                connectType = DeviceConnectType.APPROVED,
                token = "token",
                authorizationMode = DeviceConnectAuthorizationMode.STANDARD,
            ),
            maxFileStreams = 2,
            recommendedChunkBytes = 512 * 1024,
        )

        assertFailsWith<Exception> {
            ProtoBufCodec.decode<DeviceSessionConnectResponse>(ProtoBufCodec.encode(legacy))
        }
    }

    @Test
    fun controlRequestRoundTripKeepsMethodAndPayload() {
        val request = DeviceSessionControlRequest(
            requestId = 7,
            method = DEVICE_SESSION_RPC_CONNECT,
            payload = byteArrayOf(1, 2, 3),
        )
        val encoded = DeviceSessionProtocol.encodeControlRequest(request)
        val decoded = DeviceSessionProtocol.decodeControlMessage(encoded)
        val restored = assertIs<DeviceSessionControlRequest>(decoded)
        assertEquals(7, restored.requestId)
        assertEquals(DEVICE_SESSION_RPC_CONNECT, restored.method)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(restored.payload))
    }

    @Test
    fun streamOpenRoundTripKeepsWriteMetadata() {
        val open = DeviceSessionStreamOpen(
            kind = DEVICE_SESSION_STREAM_WRITE,
            path = "/tmp/file.bin",
            fileSize = 1024L,
            startOffset = 64L,
            endOffset = 1024L,
        )
        val restored = DeviceSessionProtocol.decodeStreamOpen(DeviceSessionProtocol.encodeStreamOpen(open))
        assertEquals(DEVICE_SESSION_STREAM_WRITE, restored.kind)
        assertEquals("/tmp/file.bin", restored.path)
        assertEquals(1024L, restored.fileSize)
        assertEquals(64L, restored.startOffset)
    }

    @Test
    fun messageStreamOpenRoundTripKeepsOnlyStableMessageId() {
        val messageId = "message-stream-open-001"
        val open = DeviceSessionStreamOpen(
            kind = DEVICE_SESSION_STREAM_MESSAGE_BODY,
            payload = ProtoBufCodec.encode(DeviceSessionMessageBodyOpen(messageId)),
        )

        val restored = DeviceSessionProtocol.decodeStreamOpen(DeviceSessionProtocol.encodeStreamOpen(open))
        val body = ProtoBufCodec.decode<DeviceSessionMessageBodyOpen>(restored.payload)

        assertEquals(DEVICE_SESSION_STREAM_MESSAGE_BODY, restored.kind)
        assertEquals(messageId, body.messageId)
        assertEquals("", restored.path)
    }

    @Test
    fun controlAssemblerReassemblesChunkedEnvelope() {
        val payload = ByteArray(DEVICE_SESSION_MAX_PAYLOAD_BYTES + 17) { index -> (index % 251).toByte() }
        val enveloped = DeviceSessionProtocol.envelopeControl(payload)
        val assembler = DeviceSessionControlAssembler()
        val first = assembler.push(enveloped.copyOfRange(0, DEVICE_SESSION_MAX_PAYLOAD_BYTES))
        assertTrue(first.isEmpty())
        val restored = assembler.push(enveloped.copyOfRange(DEVICE_SESSION_MAX_PAYLOAD_BYTES, enveloped.size))
        assertEquals(1, restored.size)
        assertTrue(payload.contentEquals(restored.single()))
    }

    @Test
    fun controlAssemblerOverflowResetsAndAcceptsLaterMessage() {
        val assembler = DeviceSessionControlAssembler(maxMessageBytes = 16)
        assertFailsWith<DeviceSessionIoException> {
            assembler.push(ByteArray(32))
        }
        val restored = assembler.push(DeviceSessionProtocol.envelopeControl(byteArrayOf(9, 8, 7)))
        assertEquals(1, restored.size)
        assertTrue(byteArrayOf(9, 8, 7).contentEquals(restored.single()))
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class LegacyDeviceSessionConnectResponse(
    @ProtoNumber(1) val connection: DeviceConnectResponse,
    @ProtoNumber(2) val maxFileStreams: Int,
    @ProtoNumber(3) val recommendedChunkBytes: Int,
)

class DeviceSessionConnectionControlTest {
    @Test
    fun sendControlSplitsPayloadAboveFrameCap() = runSuspendTest {
        val pair = InMemoryDeviceSessionPair()
        pair.use { client, server ->
            val payload = ByteArray(DEVICE_SESSION_MAX_PAYLOAD_BYTES + 8) { 7 }
            client.sendControl(DeviceSessionProtocol.encodeControlRequest(
                DeviceSessionControlRequest(requestId = 1, method = "ListPath", payload = payload),
            ))
            val first = server.incomingEvents.receive()
            val second = server.incomingEvents.receive()
            assertEquals(DeviceSessionFrameType.Data, first.type)
            assertEquals(DEVICE_SESSION_CONTROL_STREAM_ID, first.streamId)
            assertEquals(DEVICE_SESSION_MAX_PAYLOAD_BYTES, first.payload.size)
            assertEquals(DeviceSessionFrameType.Data, second.type)
            val assembler = DeviceSessionControlAssembler()
            val messages = assembler.push(first.payload) + assembler.push(second.payload)
            val decoded = DeviceSessionProtocol.decodeControlMessage(messages.single())
            val restored = assertIs<DeviceSessionControlRequest>(decoded)
            assertEquals(1, restored.requestId)
            assertEquals("ListPath", restored.method)
            assertEquals(payload.size, restored.payload.size)
        }
    }

    @Test
    fun sendDataSplitsPayloadAboveFrameCap() = runSuspendTest {
        val pair = InMemoryDeviceSessionPair()
        pair.use { client, server ->
            val streamId = client.openStream()
            assertEquals(DeviceSessionFrameType.Open, server.incomingEvents.receive().type)
            val payload = ByteArray(DEVICE_SESSION_MAX_PAYLOAD_BYTES + 12) { 3 }
            coroutineScope {
                val sender = async {
                    client.sendData(streamId, payload)
                }
                val first = server.incomingEvents.receive()
                server.sendWindowUpdate(streamId, first.payload.size)
                val second = server.incomingEvents.receive()
                sender.await()
                assertEquals(DEVICE_SESSION_MAX_PAYLOAD_BYTES, first.payload.size)
                assertEquals(12, second.payload.size)
                assertEquals(streamId, first.streamId)
                assertEquals(streamId, second.streamId)
            }
        }
    }

    @Test
    fun oversizedOpenPayloadDoesNotConsumeStreamSlot() = runSuspendTest {
        val pair = InMemoryDeviceSessionPair(maxActiveStreams = 1)
        pair.use { client, _ ->
            assertFailsWith<IllegalArgumentException> {
                client.openStream(ByteArray(DEVICE_SESSION_MAX_PAYLOAD_BYTES + 1))
            }
            client.openStream()
        }
    }
}

class DeviceSessionTransportBatchTest {
    @Test
    fun transportWritesAlreadyQueuedFramesInOneBoundedFlush() = runSuspendTest {
        coroutineScope {
            val channel = RecordingDeviceSessionByteChannel()
            val transport = DeviceSessionTransport(
                channel = channel,
                sendPlan = DeviceSessionWindowPlan.fromMemory(),
                isClient = true,
            )
            val transportJob = transport.start(this)
            val streamId = transport.connection.openStream()
            transport.connection.sendData(
                streamId = streamId,
                payload = ByteArray(DEVICE_SESSION_MAX_PAYLOAD_BYTES * 4) { index -> index.toByte() },
            )
            transport.connection.sendTrailer(streamId)

            withTimeout(1_000) {
                while (channel.writes.isEmpty() || channel.readContextName == null) yield()
            }
            transportJob.cancelAndJoin()

            assertEquals("session-io", channel.readContextName)
            assertEquals("session-io", channel.writeContextName)
            assertEquals(1, channel.writes.size)
            assertEquals(1, channel.flushes)
            val frames = DeviceSessionFrames.decodeAll(channel.writes.single())
            assertEquals(
                listOf(
                    DeviceSessionFrameType.Open,
                    DeviceSessionFrameType.Data,
                    DeviceSessionFrameType.Data,
                    DeviceSessionFrameType.Data,
                    DeviceSessionFrameType.Data,
                    DeviceSessionFrameType.Trailer,
                ),
                frames.map { it.type },
            )
            assertTrue(frames.filter { it.type == DeviceSessionFrameType.Data }.all {
                it.payload.size == DEVICE_SESSION_MAX_PAYLOAD_BYTES
            })
        }
    }
}

private class RecordingDeviceSessionByteChannel : DeviceSessionByteChannel {
    override val ioContext = CoroutineName("session-io")
    var readContextName: String? = null
    var writeContextName: String? = null
    val writes = mutableListOf<ByteArray>()
    var flushes = 0

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        readContextName = currentCoroutineContext()[CoroutineName]?.name.orEmpty()
        awaitCancellation()
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        writeContextName = currentCoroutineContext()[CoroutineName]?.name.orEmpty()
        writes += buffer.copyOfRange(offset, offset + length)
    }

    override suspend fun flush() {
        flushes++
    }

    override fun close() = Unit
}

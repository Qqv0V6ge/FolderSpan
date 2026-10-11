package com.folderspan.service.session

import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.exception.AuthorityException
import com.folderspan.service.data.CopyPathProgress
import com.folderspan.service.data.CopyPathRequest
import com.folderspan.service.data.CreateBookmarkRequest
import com.folderspan.service.data.EmptyRequest
import com.folderspan.service.data.ListRequest
import com.folderspan.service.data.SerializableResult
import com.folderspan.service.data.toSerializableResult
import com.folderspan.service.message.DeviceConversationSummary
import com.folderspan.service.message.DeviceMessageDirection
import com.folderspan.service.message.DeviceMessageEndpointIdentity
import com.folderspan.service.message.DeviceMessageLiveEndpoint
import com.folderspan.service.message.DeviceMessageMetadata
import com.folderspan.service.message.DeviceMessagePersistenceException
import com.folderspan.service.message.DeviceMessageStatus
import com.folderspan.service.message.DeviceMessageStore
import com.folderspan.service.message.DeviceMessageTransport
import com.folderspan.service.message.DeviceStoredMessage
import com.folderspan.service.message.IncomingDeviceMessageCoordinator
import com.folderspan.service.message.IncomingDeviceMessagePersistence
import com.folderspan.service.message.ValidatedDeviceMessage
import com.folderspan.service.message.chunkDeviceMessageBody
import com.folderspan.service.message.DeviceMessageLiveEndpointRegistry
import com.folderspan.service.message.DEVICE_MESSAGE_MAX_BODY_BYTES
import com.folderspan.service.message.prepareDeviceMessage
import com.folderspan.service.http.archive.FolderSpanArchiveCompressionCodec
import com.folderspan.service.http.archive.FolderSpanArchiveEntryRequest
import com.folderspan.service.http.archive.FolderSpanArchiveReadRequest
import com.folderspan.service.http.archive.FolderSpanArchiveRemoteWriteException
import com.folderspan.service.http.archive.FolderSpanArchiveStreamCapabilities
import com.folderspan.service.http.archive.FolderSpanArchiveStreamOptions
import com.folderspan.service.http.archive.FolderSpanArchiveTransferResult
import com.folderspan.service.http.archive.FolderSpanArchiveWriteRequest
import com.folderspan.service.operation.HttpTransferStatus
import com.folderspan.service.path.DevicePathEntries
import com.folderspan.service.webrtc.WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL
import com.folderspan.service.webrtc.WebRtcDeviceSessionByteChannel
import com.folderspan.service.webrtc.models.WebRtcDataChannel
import com.folderspan.service.webrtc.models.WebRtcDataChannelState
import com.folderspan.test.runSuspendTest
import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.ProtoBufCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceSessionSymmetricControlTest {
    @Test
    fun publicClientBehaviorMatchesContinuousAndWebRtcCarriers() = runSuspendTest {
        TestDeviceSessionCarrier.entries.forEach { carrier ->
            DeviceSessionPeerTestPair(carrier).use { client, server ->
                val clients = createDeviceSessionClients(
                    peer = client,
                    transferStatus = HttpTransferStatus.default(),
                    archiveCapabilities = FolderSpanArchiveStreamCapabilities.local(),
                )
                val clientMessages = createTestSessionMessageSide(
                    peer = client,
                    peerDeviceId = "server-device",
                    connectionId = "client-${carrier.name}",
                )
                val serverMessages = createTestSessionMessageSide(
                    peer = server,
                    peerDeviceId = "client-device",
                    connectionId = "server-${carrier.name}",
                )
                val writtenFileBytes = mutableListOf<Byte>()
                val writtenArchiveBytes = mutableListOf<Byte>()
                val bookmark = DrawerBookmark(
                    id = 9L,
                    name = "Carrier",
                    type = DrawerBookmarkType.Custom,
                    path = "/carrier",
                )

                client.setRequestHandler { request ->
                    clients.handleIncomingRequest(request) ?: clientMessages.handler.handle(request)
                }
                client.setStreamOpenHandler(clientMessages.handler)
                server.setRequestHandler { request ->
                    val result = when (request.method) {
                        DEVICE_SESSION_RPC_LIST_PATH -> {
                            val entries: DevicePathEntries = mapOf(
                                Pair(FileProtocol.Local, "peer-device") to mutableListOf(
                                    FileSimpleInfo(
                                        name = "entry.txt",
                                        isDirectory = false,
                                        isHidden = false,
                                        path = "/entry.txt",
                                        mineType = "text/plain",
                                        size = 3L,
                                        createdDate = 1L,
                                        updatedDate = 2L,
                                    ),
                                ),
                            )
                            Result.success(entries).toSerializableResult()
                        }
                        DEVICE_SESSION_RPC_BOOKMARK_LIST -> {
                            Result.success(listOf(bookmark)).toSerializableResult()
                        }
                        DEVICE_SESSION_RPC_CREATE_FILES -> {
                            Result.success(
                                listOf(
                                    Result.success(true),
                                    Result.failure<Boolean>(
                                        AuthorityException(AppStrings.message_task_permission_denied),
                                    ),
                                ),
                            ).toSerializableBooleanBatchResult().toSerializableResult()
                        }
                        DEVICE_SESSION_RPC_COPY_PATH -> {
                            val copy = ProtoBufCodec.decode<CopyPathRequest>(request.payload)
                            val progressResponse = server.rpc(
                                DEVICE_SESSION_RPC_COPY_PROGRESS,
                                ProtoBufCodec.encode(
                                    DeviceSessionCopyProgressEvent(
                                        requestId = copy.requestId,
                                        progress = CopyPathProgress(
                                            progressCur = 1L,
                                            progressMax = 1L,
                                            path = copy.destPath,
                                            done = true,
                                            success = true,
                                        ),
                                    ),
                                ),
                            )
                            assertEquals(DEVICE_SESSION_RPC_OK, progressResponse.status)
                            Result.success(true).toSerializableResult()
                        }
                        else -> return@setRequestHandler serverMessages.handler.handle(request)
                    }
                    DeviceSessionControlResponse(
                        requestId = request.requestId,
                        status = DEVICE_SESSION_RPC_OK,
                        payload = ProtoBufCodec.encode(result),
                    )
                }
                server.setStreamOpenHandler { streamId, open ->
                    when (open.kind) {
                        DEVICE_SESSION_STREAM_WRITE -> object : DeviceSessionIncomingStream {
                            override suspend fun onData(chunk: ByteArray) {
                                writtenFileBytes += chunk.toList()
                            }

                            override suspend fun onTrailer(payload: ByteArray) {
                                server.connection.sendTrailer(streamId)
                            }
                        }
                        DEVICE_SESSION_STREAM_READ -> {
                            server.connection.sendData(streamId, byteArrayOf(4, 5, 6))
                            server.connection.sendTrailer(streamId)
                            object : DeviceSessionIncomingStream {
                                override suspend fun onData(chunk: ByteArray) = Unit
                            }
                        }
                        DEVICE_SESSION_STREAM_ARCHIVE_READ -> {
                            server.connection.sendData(streamId, byteArrayOf(7, 8))
                            server.connection.sendTrailer(
                                streamId,
                                ProtoBufCodec.encode(
                                    DeviceSessionArchiveTrailer(
                                        FolderSpanArchiveTransferResult(listOf("entry.txt"), 2L),
                                    ),
                                ),
                            )
                            object : DeviceSessionIncomingStream {
                                override suspend fun onData(chunk: ByteArray) = Unit
                            }
                        }
                        DEVICE_SESSION_STREAM_ARCHIVE_WRITE -> object : DeviceSessionIncomingStream {
                            override suspend fun onData(chunk: ByteArray) {
                                writtenArchiveBytes += chunk.toList()
                            }

                            override suspend fun onTrailer(payload: ByteArray) {
                                server.connection.sendTrailer(
                                    streamId,
                                    ProtoBufCodec.encode(
                                        DeviceSessionArchiveTrailer(
                                            FolderSpanArchiveTransferResult(listOf("entry.txt"), 2L),
                                        ),
                                    ),
                                )
                            }
                        }
                        else -> serverMessages.handler.open(streamId, open)
                    }
                }

                assertTrue(
                    clients.writeRangeStream(
                        path = "/target.bin",
                        fileSize = 3L,
                        startOffset = 0L,
                        endOffset = 3L,
                        chunks = flowOf(byteArrayOf(1, 2, 3)),
                    ).getOrThrow(),
                )
                assertContentEquals(byteArrayOf(1, 2, 3), writtenFileBytes.toByteArray())

                val readBytes = mutableListOf<Byte>()
                assertTrue(
                    clients.readStream("/source.bin", 0L, 3L) { chunk ->
                        readBytes += chunk.toList()
                    }.getOrThrow(),
                )
                assertContentEquals(byteArrayOf(4, 5, 6), readBytes.toByteArray())

                val listed = clients.traversePath("/root").single().getOrThrow().single()
                assertEquals("/root/entry.txt", listed.path)
                assertEquals(bookmark, clients.getBookmarks().getOrThrow().single())

                val createResults = clients.createFiles(listOf("/allowed", "/denied")).getOrThrow()
                assertTrue(createResults.first().getOrThrow())
                assertIs<AuthorityException>(createResults.last().exceptionOrNull())

                val progress = mutableListOf<CopyPathProgress>()
                assertTrue(
                    clients.copyPath(
                        srcPath = "/source",
                        destPath = "/target",
                        onProgress = { item -> progress += item },
                        requestId = "copy-${carrier.name}",
                    ).getOrThrow(),
                )
                assertEquals(1L, progress.single().progressCur)

                val archiveReadBytes = mutableListOf<Byte>()
                assertTrue(
                    clients.readArchiveStream(
                        FolderSpanArchiveReadRequest(
                            entries = listOf(FolderSpanArchiveEntryRequest("/entry.txt", "entry.txt", 2L)),
                            options = FolderSpanArchiveStreamOptions(FolderSpanArchiveCompressionCodec.NONE),
                        ),
                    ) { chunk -> archiveReadBytes += chunk.toList() }.getOrThrow(),
                )
                assertContentEquals(byteArrayOf(7, 8), archiveReadBytes.toByteArray())
                val archiveWrite = clients.writeArchiveStream(
                    FolderSpanArchiveWriteRequest("/target", 1, 2L),
                    flowOf(byteArrayOf(9, 10)),
                ).getOrThrow()
                assertEquals(listOf("entry.txt"), archiveWrite.completedRelativePaths)
                assertContentEquals(byteArrayOf(9, 10), writtenArchiveBytes.toByteArray())

                val prepared = prepareDeviceMessage(
                    body = "carrier-message",
                    messageId = "carrier-${carrier.name.lowercase()}-0001",
                    sentAtEpochMillis = 1_700_000_000_000L,
                    nowEpochMillis = 1_700_000_000_000L,
                )
                clientMessages.client.send(
                    prepared.metadata,
                    flowOf(prepared.bodyBytes),
                ).getOrThrow()
                assertEquals(
                    "carrier-message",
                    serverMessages.store.page("client-device", 10).single().body,
                )
            }
        }
    }

    @Test
    fun pathAndBookmarkClientsPreservePublicResultsAcrossSessionRpc() = runSuspendTest {
        DeviceSessionPeerTestPair().use { client, server ->
            val clients = createDeviceSessionClients(
                peer = client,
                transferStatus = HttpTransferStatus.default(),
                archiveCapabilities = FolderSpanArchiveStreamCapabilities.local(),
            )
            val bookmark = DrawerBookmark(
                id = 7L,
                name = "Work",
                type = DrawerBookmarkType.Custom,
                path = "/work",
            )
            server.setRequestHandler { request ->
                val result = when (request.method) {
                    DEVICE_SESSION_RPC_LIST_PATH -> {
                        val listRequest = ProtoBufCodec.decode<ListRequest>(
                            request.payload,
                        )
                        assertEquals("/root", listRequest.path)
                        val entries: DevicePathEntries = mapOf(
                            Pair(FileProtocol.Local, "peer-device") to mutableListOf(
                                FileSimpleInfo(
                                    name = "child.txt",
                                    isDirectory = false,
                                    isHidden = false,
                                    path = "/child.txt",
                                    mineType = "text/plain",
                                    size = 3L,
                                    createdDate = 1L,
                                    updatedDate = 2L,
                                ),
                            ),
                        )
                        Result.success(entries).toSerializableResult()
                    }
                    DEVICE_SESSION_RPC_BOOKMARK_LIST -> {
                        Result.success(listOf(bookmark)).toSerializableResult()
                    }
                    DEVICE_SESSION_RPC_BOOKMARK_CREATE -> {
                        val create = ProtoBufCodec.decode<CreateBookmarkRequest>(
                            request.payload,
                        )
                        assertEquals("New", create.name)
                        assertEquals("/new", create.path)
                        Result.success(true).toSerializableResult()
                    }
                    else -> error("unexpected method: ${request.method}")
                }
                DeviceSessionControlResponse(
                    requestId = request.requestId,
                    status = DEVICE_SESSION_RPC_OK,
                    payload = ProtoBufCodec.encode(result),
                )
            }

            val listed = clients.listPath(
                request = ListRequest("/root"),
            ).getOrThrow().single()
            assertEquals("/root/child.txt", listed.path)
            assertEquals("peer-device", listed.protocolId)
            assertEquals(bookmark, clients.getBookmarks().getOrThrow().single())
            assertTrue(
                clients.createBookmark(
                    name = "New",
                    path = "/new",
                    iconType = DrawerBookmarkType.Custom,
                    iconPath = "",
                ).getOrThrow(),
            )
        }
    }

    @Test
    fun copyProgressIsRoutedToTheMatchingClientRequest() = runSuspendTest {
        DeviceSessionPeerTestPair().use { client, server ->
            val clients = createDeviceSessionClients(
                peer = client,
                transferStatus = HttpTransferStatus.default(),
                archiveCapabilities = FolderSpanArchiveStreamCapabilities.local(),
            )
            client.setRequestHandler { request ->
                clients.handleIncomingRequest(request)
                    ?: DeviceSessionControlResponse(
                        requestId = request.requestId,
                        status = DEVICE_SESSION_RPC_NOT_FOUND,
                        errorMessage = request.method,
                    )
            }
            server.setRequestHandler { request ->
                assertEquals(DEVICE_SESSION_RPC_COPY_PATH, request.method)
                val copyRequest = ProtoBufCodec.decode<CopyPathRequest>(
                    request.payload,
                )
                listOf(1L, 2L).forEach { current ->
                    val progressResponse = server.rpc(
                        DEVICE_SESSION_RPC_COPY_PROGRESS,
                        ProtoBufCodec.encode(
                            DeviceSessionCopyProgressEvent(
                                requestId = copyRequest.requestId,
                                progress = CopyPathProgress(
                                    progressCur = current,
                                    progressMax = 2L,
                                    path = copyRequest.destPath,
                                    done = current == 2L,
                                    success = current == 2L,
                                ),
                            ),
                        ),
                    )
                    assertEquals(DEVICE_SESSION_RPC_OK, progressResponse.status)
                }
                DeviceSessionControlResponse(
                    requestId = request.requestId,
                    status = DEVICE_SESSION_RPC_OK,
                    payload = ProtoBufCodec.encode(Result.success(true).toSerializableResult()),
                )
            }
            val progress = mutableListOf<Long>()

            val copied = clients.copyPath(
                srcPath = "/source",
                destPath = "/target",
                onProgress = { item -> progress += item.progressCur },
                requestId = "copy-progress-test",
            ).getOrThrow()

            assertTrue(copied)
            assertEquals(listOf(1L, 2L), progress)
        }
    }

    @Test
    fun batchBooleanRpcRoundTripsMixedItemResults() = runSuspendTest {
        DeviceSessionPeerTestPair().use { client, server ->
            server.setRequestHandler { request ->
                val operationResult = Result.success(
                    listOf(
                        Result.success(true),
                        Result.failure<Boolean>(AuthorityException(AppStrings.message_task_permission_denied)),
                    )
                )
                DeviceSessionControlResponse(
                    requestId = request.requestId,
                    status = DEVICE_SESSION_RPC_OK,
                    payload = ProtoBufCodec.encode(
                        operationResult.toSerializableBooleanBatchResult().toSerializableResult()
                    ),
                )
            }

            val restored = client
                .rpcResult<EmptyRequest, List<SerializableResult>>("BatchBoolean", EmptyRequest())
                .toBooleanBatchResult()
                .getOrThrow()

            assertTrue(restored[0].getOrThrow())
            assertIs<AuthorityException>(restored[1].exceptionOrNull())
            assertEquals(AppStrings.message_task_permission_denied, restored[1].exceptionOrNull()?.message)
        }
    }

    @Test
    fun simultaneousBidirectionalRpcUsesDisjointRequestIds() = runSuspendTest {
        DeviceSessionPeerTestPair().use { client, server ->
            val receivedByClient = mutableListOf<Int>()
            val receivedByServer = mutableListOf<Int>()
            val captureMutex = Mutex()
            client.setRequestHandler { request ->
                captureMutex.withLock { receivedByClient += request.requestId }
                DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, request.payload)
            }
            server.setRequestHandler { request ->
                captureMutex.withLock { receivedByServer += request.requestId }
                DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, request.payload)
            }

            coroutineScope {
                val clientCalls = (0 until 20).map { index ->
                    async { client.rpc("client-$index", byteArrayOf(index.toByte())) }
                }
                val serverCalls = (0 until 20).map { index ->
                    async { server.rpc("server-$index", byteArrayOf(index.toByte())) }
                }
                (clientCalls + serverCalls).forEach { call ->
                    assertEquals(DEVICE_SESSION_RPC_OK, call.await().status)
                }
            }

            assertEquals((1..39 step 2).toSet(), receivedByServer.toSet())
            assertEquals((2..40 step 2).toSet(), receivedByClient.toSet())
        }
    }

    @Test
    fun closeFailsPendingRpc() = runSuspendTest {
        DeviceSessionPeerTestPair().use { client, server ->
            val requestStarted = CompletableDeferred<Unit>()
            server.setRequestHandler { request ->
                requestStarted.complete(Unit)
                awaitCancellation()
            }
            supervisorScope {
                val pending = async { client.rpc("never-completes", byteArrayOf()) }
                requestStarted.await()
                client.close()
                assertFailsWith<DeviceSessionClosedException> { pending.await() }
            }
        }
    }
}

class DeviceSessionFileStreamTest {
    @Test
    fun archiveReadUsesChunkedStreamAndResultTrailer() = runSuspendTest {
        DeviceSessionPeerTestPair().use { client, server ->
            server.setStreamOpenHandler { streamId, open ->
                assertEquals(DEVICE_SESSION_STREAM_ARCHIVE_READ, open.kind)
                val request = ProtoBufCodec.decode<FolderSpanArchiveReadRequest>(open.payload)
                assertEquals(listOf("a.txt"), request.entries.map { it.relativePath })
                server.connection.sendData(streamId, byteArrayOf(1, 2))
                server.connection.sendData(streamId, byteArrayOf(3, 4))
                server.connection.sendTrailer(
                    streamId,
                    ProtoBufCodec.encode(
                        DeviceSessionArchiveTrailer(
                            FolderSpanArchiveTransferResult(listOf("a.txt"), 4L)
                        )
                    ),
                )
                object : DeviceSessionIncomingStream {
                    override suspend fun onData(chunk: ByteArray) = Unit
                }
            }
            val chunks = mutableListOf<ByteArray>()

            val result = client.openArchiveReadStream(
                FolderSpanArchiveReadRequest(
                    entries = listOf(FolderSpanArchiveEntryRequest("/a", "a.txt", 4L)),
                    options = FolderSpanArchiveStreamOptions(
                        FolderSpanArchiveCompressionCodec.NONE,
                    ),
                ),
            ) { chunk -> chunks += chunk }

            assertEquals(listOf(2, 2), chunks.map { it.size })
            assertEquals(listOf("a.txt"), result.completedRelativePaths)
        }
    }

    @Test
    fun archiveWriteWaitsForCommittedPathsTrailer() = runSuspendTest {
        DeviceSessionPeerTestPair().use { client, server ->
            val received = mutableListOf<ByteArray>()
            server.setStreamOpenHandler { streamId, open ->
                assertEquals(DEVICE_SESSION_STREAM_ARCHIVE_WRITE, open.kind)
                object : DeviceSessionIncomingStream {
                    override suspend fun onData(chunk: ByteArray) {
                        received += chunk
                    }

                    override suspend fun onTrailer(payload: ByteArray) {
                        server.connection.sendTrailer(
                            streamId,
                            ProtoBufCodec.encode(
                                DeviceSessionArchiveTrailer(
                                    FolderSpanArchiveTransferResult(listOf("a.txt"), 3L)
                                )
                            ),
                        )
                    }
                }
            }

            val result = client.openArchiveWriteStream(
                FolderSpanArchiveWriteRequest("/target", 1, 3L),
                flowOf(byteArrayOf(1), byteArrayOf(2, 3)),
            )

            assertEquals(listOf(1, 2), received.map { it.size })
            assertEquals(listOf("a.txt"), result.completedRelativePaths)
        }
    }

    @Test
    fun archiveWritePreservesPartialCommitWhenTargetRejectsLaterEntry() = runSuspendTest {
        DeviceSessionPeerTestPair().use { client, server ->
            server.setStreamOpenHandler { streamId, open ->
                assertEquals(DEVICE_SESSION_STREAM_ARCHIVE_WRITE, open.kind)
                object : DeviceSessionIncomingStream {
                    override suspend fun onData(chunk: ByteArray) = Unit

                    override suspend fun onTrailer(payload: ByteArray) {
                        server.connection.sendTrailer(
                            streamId,
                            ProtoBufCodec.encode(
                                DeviceSessionArchiveTrailer(
                                    result = FolderSpanArchiveTransferResult(listOf("done.txt"), 4L),
                                    errorMessage = "permission denied for pending.txt",
                                )
                            ),
                        )
                    }
                }
            }

            val failure = assertFailsWith<FolderSpanArchiveRemoteWriteException> {
                client.openArchiveWriteStream(
                    FolderSpanArchiveWriteRequest("/target", 2, 8L),
                    flowOf(byteArrayOf(1, 2, 3, 4)),
                )
            }

            assertEquals(listOf("done.txt"), failure.partialResult.completedRelativePaths)
            assertEquals(4L, failure.partialResult.committedFileBytes)
        }
    }

    @Test
    fun remoteResetCancelsArchiveWriteWaitingForCredit() = runSuspendTest {
        DeviceSessionPeerTestPair().use { client, server ->
            server.setStreamOpenHandler { _, _ -> null }

            val result = runCatching {
                withTimeout(2_000L) {
                    client.openArchiveWriteStream(
                        FolderSpanArchiveWriteRequest("/target", 1, DEVICE_SESSION_MAX_PAYLOAD_BYTES * 4L),
                        flow {
                            repeat(4) { emit(ByteArray(DEVICE_SESSION_MAX_PAYLOAD_BYTES)) }
                        },
                    )
                }
            }

            assertTrue(result.isFailure)
            assertTrue(
                result.exceptionOrNull() is DeviceSessionIoException ||
                    result.exceptionOrNull() is DeviceSessionClosedException
            )
        }
    }

    @Test
    fun writeCompletesOnlyAfterReceiverAcknowledgesPersistence() = runSuspendTest {
        DeviceSessionPeerTestPair().use { client, server ->
            val dataReceived = CompletableDeferred<Unit>()
            val allowPersistenceAck = CompletableDeferred<Unit>()
            server.setStreamOpenHandler { streamId, open ->
                assertEquals(DEVICE_SESSION_STREAM_WRITE, open.kind)
                object : DeviceSessionIncomingStream {
                    override suspend fun onData(chunk: ByteArray) {
                        assertTrue(byteArrayOf(1, 2, 3).contentEquals(chunk))
                        dataReceived.complete(Unit)
                    }

                    override suspend fun onTrailer(payload: ByteArray) {
                        allowPersistenceAck.await()
                        server.connection.sendTrailer(streamId)
                    }
                }
            }

            coroutineScope {
                val upload = async {
                    client.openWriteStream(
                        path = "/target.bin",
                        fileSize = 3L,
                        startOffset = 0L,
                        data = flowOf(byteArrayOf(1, 2, 3)),
                    )
                }
                dataReceived.await()
                delay(10)
                assertFalse(upload.isCompleted)

                allowPersistenceAck.complete(Unit)
                upload.await()
            }
        }
    }

    @Test
    fun remoteResetAbortsLargeWriteInsteadOfWaitingForeverForCredit() = runSuspendTest {
        DeviceSessionPeerTestPair().use { client, server ->
            server.setStreamOpenHandler { _, _ -> null }

            assertFailsWith<DeviceSessionIoException> {
                withTimeout(2_000L) {
                    client.openWriteStream(
                        path = "/rejected.bin",
                        fileSize = (DEVICE_SESSION_MAX_PAYLOAD_BYTES * 4L),
                        startOffset = 0L,
                        data = flow {
                            repeat(4) {
                                emit(ByteArray(DEVICE_SESSION_MAX_PAYLOAD_BYTES))
                            }
                        },
                    )
                }
            }
        }
    }
}

class DeviceSessionMessageTransportTest {
    @Test
    fun missingCommitReceiptTimesOutAsUnconfirmedDelivery() = runSuspendTest {
        val identity = DeviceMessageEndpointIdentity(
            peerDeviceId = "server-device",
            transport = DeviceMessageTransport.Session,
            connectionId = "timeout-connection",
        )
        val client = SessionDeviceMessageClient(
            endpointIdentity = identity,
            rpcCall = { method, _ ->
                if (method == DEVICE_SESSION_RPC_BEGIN_MESSAGE) {
                    DeviceSessionControlResponse(
                        requestId = 1,
                        status = DEVICE_SESSION_RPC_OK,
                        payload = ProtoBufCodec.encode(DeviceSessionMessageBeginResponse(ready = true)),
                    )
                } else {
                    awaitCancellation()
                }
            },
            openBodyStream = { _, _ -> },
            rpcTimeoutMillis = 10L,
        )
        val message = prepareDeviceMessage("timeout", "receipt-timeout-000001", NOW, NOW)

        val failure = client.send(message.metadata, flowOf(message.bodyBytes)).exceptionOrNull()

        val transferFailure = assertIs<com.folderspan.service.message.DeviceMessageTransferException>(failure)
        assertTrue(transferFailure.deliveryUncertain)
    }

    @Test
    fun bothRolesSendOneMiBAndPersistAuthenticatedPeerIdentity() = runSuspendTest {
        DeviceSessionPeerTestPair().use { clientPeer, serverPeer ->
            val clientSide = messageSide(
                peer = clientPeer,
                peerDeviceId = "server-device",
                connectionId = "client-connection",
            )
            val serverSide = messageSide(
                peer = serverPeer,
                peerDeviceId = "client-device",
                connectionId = "server-connection",
            )
            clientPeer.setRequestHandler(clientSide.handler)
            clientPeer.setStreamOpenHandler(clientSide.handler)
            serverPeer.setRequestHandler(serverSide.handler)
            serverPeer.setStreamOpenHandler(serverSide.handler)

            val largeBody = "a".repeat(DEVICE_MESSAGE_MAX_BODY_BYTES)
            val clientMessage = prepareDeviceMessage(largeBody, "client-large-message-01", NOW, NOW)
            val serverMessage = prepareDeviceMessage("from server", "server-small-message-01", NOW, NOW)
            coroutineScope {
                val toServer = async {
                    clientSide.client.send(
                        clientMessage.metadata,
                        chunkDeviceMessageBody(clientMessage.bodyBytes, clientSide.client.maxChunkBytes),
                    ).getOrThrow()
                }
                val toClient = async {
                    serverSide.client.send(
                        serverMessage.metadata,
                        chunkDeviceMessageBody(serverMessage.bodyBytes, 3),
                    ).getOrThrow()
                }
                assertEquals(clientMessage.metadata.messageId, toServer.await().messageId)
                assertEquals(serverMessage.metadata.messageId, toClient.await().messageId)
            }

            val receivedByServer = serverSide.store.page("client-device", 10).single()
            val receivedByClient = clientSide.store.page("server-device", 10).single()
            assertEquals(largeBody, receivedByServer.body)
            assertEquals("client-device", receivedByServer.peerDeviceId)
            assertEquals("from server", receivedByClient.body)
            assertEquals("server-device", receivedByClient.peerDeviceId)
        }
    }

    @Test
    fun duplicateAndPartialTransfersNeverCreateDuplicateOrIncompleteHistory() = runSuspendTest {
        DeviceSessionPeerTestPair().use { clientPeer, serverPeer ->
            val clientSide = messageSide(clientPeer, "server-device", "client-connection")
            val serverSide = messageSide(serverPeer, "client-device", "server-connection")
            clientPeer.setRequestHandler(clientSide.handler)
            clientPeer.setStreamOpenHandler(clientSide.handler)
            serverPeer.setRequestHandler(serverSide.handler)
            serverPeer.setStreamOpenHandler(serverSide.handler)

            val complete = prepareDeviceMessage("complete", "duplicate-message-0001", NOW, NOW)
            repeat(2) {
                clientSide.client.send(
                    complete.metadata,
                    chunkDeviceMessageBody(complete.bodyBytes, 2),
                ).getOrThrow()
            }
            assertEquals(1L, serverSide.store.count("client-device"))

            val partial = prepareDeviceMessage("incomplete", "partial-message-000001", NOW, NOW)
            val begin = clientPeer.rpc(
                DEVICE_SESSION_RPC_BEGIN_MESSAGE,
                ProtoBufCodec.encode(partial.metadata),
            )
            assertEquals(DEVICE_SESSION_RPC_OK, begin.status)
            clientPeer.openMessageBodyStream(partial.metadata.messageId, flowOf(byteArrayOf('i'.code.toByte())))
            val commit = clientPeer.rpc(
                DEVICE_SESSION_RPC_COMMIT_MESSAGE,
                ProtoBufCodec.encode(DeviceSessionMessageCommitRequest(partial.metadata.messageId)),
            )
            assertEquals(DEVICE_SESSION_RPC_BAD_REQUEST, commit.status)
            assertEquals(1L, serverSide.store.count("client-device"))
        }
    }

    @Test
    fun unapprovedAndRevokedEndpointsRejectBeforeBodyPersistence() = runSuspendTest {
        DeviceSessionPeerTestPair().use { clientPeer, serverPeer ->
            val metadata = prepareDeviceMessage("blocked", "blocked-message-00001", NOW, NOW).metadata

            val unapproved = clientPeer.rpc(DEVICE_SESSION_RPC_BEGIN_MESSAGE, ProtoBufCodec.encode(metadata))
            assertEquals(DEVICE_SESSION_RPC_NOT_FOUND, unapproved.status)

            var authorized = true
            val serverSide = messageSide(
                peer = serverPeer,
                peerDeviceId = "client-device",
                connectionId = "server-connection",
                authorization = { authorized },
            )
            serverPeer.setRequestHandler(serverSide.handler)
            serverPeer.setStreamOpenHandler(serverSide.handler)
            authorized = false

            val revoked = clientPeer.rpc(DEVICE_SESSION_RPC_BEGIN_MESSAGE, ProtoBufCodec.encode(metadata))
            assertEquals(DEVICE_SESSION_RPC_UNAUTHORIZED, revoked.status)
            assertEquals(0L, serverSide.store.count("client-device"))
        }
    }

    private suspend fun messageSide(
        peer: DeviceSessionPeer,
        peerDeviceId: String,
        connectionId: String,
        authorization: () -> Boolean = { true },
    ): SessionMessageSide {
        val identity = DeviceMessageEndpointIdentity(
            peerDeviceId = peerDeviceId,
            transport = DeviceMessageTransport.Session,
            connectionId = connectionId,
        )
        val registry = DeviceMessageLiveEndpointRegistry()
        val store = SessionMemoryMessageStore()
        val coordinator = IncomingDeviceMessageCoordinator(
            store = store,
            endpointRegistry = registry,
            nowMillis = { NOW + 1 },
        )
        val client = SessionDeviceMessageClient(peer, identity)
        registry.register(DeviceMessageLiveEndpoint(identity, client, authorization))
        return SessionMessageSide(
            store = store,
            client = client,
            handler = DeviceSessionMessageHandler(identity, coordinator),
        )
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

private data class SessionMessageSide(
    val store: SessionMemoryMessageStore,
    val client: SessionDeviceMessageClient,
    val handler: DeviceSessionMessageHandler,
)

private suspend fun createTestSessionMessageSide(
    peer: DeviceSessionPeer,
    peerDeviceId: String,
    connectionId: String,
): SessionMessageSide {
    val identity = DeviceMessageEndpointIdentity(
        peerDeviceId = peerDeviceId,
        transport = DeviceMessageTransport.Session,
        connectionId = connectionId,
    )
    val registry = DeviceMessageLiveEndpointRegistry()
    val store = SessionMemoryMessageStore()
    val coordinator = IncomingDeviceMessageCoordinator(
        store = store,
        endpointRegistry = registry,
        nowMillis = { 1_700_000_000_001L },
    )
    val client = SessionDeviceMessageClient(peer, identity)
    registry.register(DeviceMessageLiveEndpoint(identity, client) { true })
    return SessionMessageSide(
        store = store,
        client = client,
        handler = DeviceSessionMessageHandler(identity, coordinator),
    )
}

private enum class TestDeviceSessionCarrier {
    ContinuousByteStream,
    WebRtcDataChannel,
}

private class DeviceSessionPeerTestPair(
    private val carrier: TestDeviceSessionCarrier = TestDeviceSessionCarrier.ContinuousByteStream,
) {
    private val plan = DeviceSessionWindowPlan(
        sessionWindowBytes = 2 * 1024 * 1024,
        streamWindowBytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES,
        maxFileStreams = 8,
    )

    suspend fun <T> use(block: suspend (DeviceSessionPeer, DeviceSessionPeer) -> T): T = coroutineScope {
        val (clientChannel, serverChannel) = createChannels(this)
        val client = DeviceSessionPeer(
            transport = DeviceSessionTransport(clientChannel, plan, plan, isClient = true),
            receivePlan = plan,
            role = DeviceSessionEndpointRole.Client,
        )
        val server = DeviceSessionPeer(
            transport = DeviceSessionTransport(serverChannel, plan, plan, isClient = false),
            receivePlan = plan,
            role = DeviceSessionEndpointRole.Server,
        )
        client.start(this)
        server.start(this)
        try {
            block(client, server)
        } finally {
            client.close()
            server.close()
        }
    }

    private fun createChannels(scope: CoroutineScope): Pair<DeviceSessionByteChannel, DeviceSessionByteChannel> {
        return when (carrier) {
            TestDeviceSessionCarrier.ContinuousByteStream -> {
                val clientToServer = Channel<ByteArray>(Channel.UNLIMITED)
                val serverToClient = Channel<ByteArray>(Channel.UNLIMITED)
                Pair(
                    TestDeviceSessionByteChannel(serverToClient, clientToServer),
                    TestDeviceSessionByteChannel(clientToServer, serverToClient),
                )
            }
            TestDeviceSessionCarrier.WebRtcDataChannel -> {
                val client = TestSessionDataChannel()
                val server = TestSessionDataChannel()
                client.connect(server)
                server.connect(client)
                Pair(
                    WebRtcDeviceSessionByteChannel(client, scope),
                    WebRtcDeviceSessionByteChannel(server, scope),
                )
            }
        }
    }
}

private class TestSessionDataChannel : WebRtcDataChannel {
    private val incoming = Channel<ByteArray>(capacity = 512)
    private val openEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val closeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var peer: TestSessionDataChannel? = null
    private var open = true

    override val label: String = WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL
    override val state: WebRtcDataChannelState
        get() = if (open) WebRtcDataChannelState.Open else WebRtcDataChannelState.Closed
    override val bufferedAmount: Long = 0L
    override val onOpen: Flow<Unit> = openEvents.asSharedFlow()
    override val onClose: Flow<Unit> = closeEvents.asSharedFlow()
    override val onMessage: Flow<ByteArray> = incoming.receiveAsFlow()

    fun connect(peer: TestSessionDataChannel) {
        this.peer = peer
    }

    override fun send(data: ByteArray): Boolean {
        if (!open) return false
        val target = peer?.takeIf { it.open } ?: return false
        return target.incoming.trySend(data.copyOf()).isSuccess
    }

    override fun close() {
        closeLocal(notifyPeer = true)
    }

    private fun closeLocal(notifyPeer: Boolean) {
        if (!open) return
        open = false
        incoming.close()
        closeEvents.tryEmit(Unit)
        if (notifyPeer) peer?.closeLocal(notifyPeer = false)
    }
}

private class TestDeviceSessionByteChannel(
    private val incoming: Channel<ByteArray>,
    private val outgoing: Channel<ByteArray>,
) : DeviceSessionByteChannel {
    private var current = ByteArray(0)
    private var currentOffset = 0

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (currentOffset >= current.size) {
            current = incoming.receiveCatching().getOrNull() ?: return -1
            currentOffset = 0
        }
        val count = minOf(length, current.size - currentOffset)
        current.copyInto(buffer, destinationOffset = offset, startIndex = currentOffset, endIndex = currentOffset + count)
        currentOffset += count
        return count
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        outgoing.send(buffer.copyOfRange(offset, offset + length))
    }

    override suspend fun flush() = Unit

    override fun close() {
        outgoing.close()
    }
}

private class SessionMemoryMessageStore : DeviceMessageStore {
    private val messages = mutableListOf<DeviceStoredMessage>()
    private var nextId = 1L

    override suspend fun insertOutgoing(
        peerDeviceId: String,
        message: ValidatedDeviceMessage,
        createdAtMillis: Long,
    ): DeviceStoredMessage = unsupported()

    override suspend fun updateOutgoingStatus(
        peerDeviceId: String,
        messageId: String,
        status: DeviceMessageStatus,
        receivedAtMillis: Long?,
    ): DeviceStoredMessage = unsupported()

    override suspend fun prepareExplicitRetry(peerDeviceId: String, messageId: String): DeviceStoredMessage = unsupported()

    override suspend fun persistIncoming(
        peerDeviceId: String,
        message: ValidatedDeviceMessage,
        receivedAtMillis: Long,
        isRead: Boolean,
    ): IncomingDeviceMessagePersistence {
        val existing = find(peerDeviceId, message.metadata.messageId, DeviceMessageDirection.Incoming)
        if (existing != null) return IncomingDeviceMessagePersistence(existing, duplicate = true)
        val stored = DeviceStoredMessage(
            localId = nextId++,
            messageId = message.metadata.messageId,
            peerDeviceId = peerDeviceId,
            direction = DeviceMessageDirection.Incoming,
            body = message.body,
            bodyUtf8Length = message.metadata.utf8Length,
            bodySha256 = message.metadata.sha256,
            sentAtMillis = message.metadata.sentAtEpochMillis,
            receivedAtMillis = receivedAtMillis,
            status = DeviceMessageStatus.Received,
            isRead = isRead,
            createdAtMillis = receivedAtMillis,
        )
        messages += stored
        return IncomingDeviceMessagePersistence(stored, duplicate = false)
    }

    override suspend fun recoverInterruptedSends(): Long = 0L

    override suspend fun page(peerDeviceId: String, limit: Long, offset: Long): List<DeviceStoredMessage> =
        messages.filter { it.peerDeviceId == peerDeviceId }
            .sortedByDescending(DeviceStoredMessage::localId)
            .drop(offset.toInt())
            .take(limit.toInt())

    override suspend fun pageBefore(
        peerDeviceId: String,
        beforeSentAtMillis: Long,
        beforeLocalId: Long,
        limit: Long,
    ): List<DeviceStoredMessage> = unsupported()

    override suspend fun conversations(): List<DeviceConversationSummary> = unsupported()

    override suspend fun conversation(peerDeviceId: String): DeviceConversationSummary? = unsupported()

    override suspend fun count(peerDeviceId: String): Long = messages.count { it.peerDeviceId == peerDeviceId }.toLong()

    override suspend fun find(
        peerDeviceId: String,
        messageId: String,
        direction: DeviceMessageDirection,
    ): DeviceStoredMessage? = messages.firstOrNull { item ->
        item.peerDeviceId == peerDeviceId && item.messageId == messageId && item.direction == direction
    }

    override suspend fun markConversationRead(peerDeviceId: String) = Unit

    override suspend fun deleteConversation(peerDeviceId: String) {
        messages.removeAll { it.peerDeviceId == peerDeviceId }
    }

    private fun <T> unsupported(): T = throw DeviceMessagePersistenceException("unsupported test operation")
}

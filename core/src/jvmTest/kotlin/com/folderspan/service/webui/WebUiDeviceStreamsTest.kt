package com.folderspan.service.webui

import com.folderspan.service.http.archive.*
import com.folderspan.service.http.server.linkshare.*
import com.folderspan.service.session.*
import com.folderspan.service.operation.HttpTransferStatus
import com.folderspan.ui.state.file.copyLocalRangeToContinuousDevice
import com.folderspan.utils.ProtoBufCodec
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WebUiDeviceStreamsTest {
    @Test
    fun checkpointUploadResumesThroughRuntimeAndNeverProxiesFileBodies(): Unit = runBlocking {
        withTimeout(20_000) {
            withStreams { pair ->
                val content = ByteArray(4 * 1024 * 1024 + 73) { (it % 251).toByte() }
                val source = Files.write(pair.root.resolve("checkpoint-source.bin"), content)
                val target = content.copyOf()
                val startOffset = 1024 * 1024L
                target.fill(0, startOffset.toInt())
                val confirmed = mutableListOf<Pair<Long, Int>>()
                val commits = mutableListOf<Pair<Long, Int>>()
                pair.native.setStreamOpenHandler(DeviceSessionStreamOpenHandler { id, open ->
                    var offset = open.startOffset.toInt()
                    object : DeviceSessionIncomingStream {
                        override suspend fun onData(chunk: ByteArray) {
                            chunk.copyInto(target, offset)
                            offset += chunk.size
                        }
                        override suspend fun onTrailer(payload: ByteArray) {
                            assertEquals(open.endOffset, offset.toLong())
                            confirmed += open.startOffset to (open.endOffset - open.startOffset).toInt()
                            pair.native.connection.sendTrailer(id)
                        }
                    }
                })
                val client = DeviceSessionClients(pair.browser, HttpTransferStatus.default(), FolderSpanArchiveStreamCapabilities.unsupported())
                assertTrue(copyLocalRangeToContinuousDevice(
                    client, source.toString(), "remote.bin", content.size.toLong(), startOffset, content.size.toLong(),
                    segmentSize = 1024 * 1024,
                    onBytesCommitted = { offset, bytes ->
                        assertEquals(confirmed.last(), offset to bytes)
                        commits += offset to bytes
                    },
                ).getOrThrow())
                assertEquals(confirmed, commits)
                assertEquals(4, commits.size)
                assertContentEquals(content, target)
                assertEquals(0, pair.managementFileBytes)
            }
        }
    }

    @Test
    fun largeControlAndBrowserOwnedStreamsStayWithinManagementRequestLimit(): Unit = runBlocking {
        withTimeout(20_000) {
            withStreams { pair ->
                val content = ByteArray(1024 * 1024 + 71) { (it % 251).toByte() }
                pair.native.setRequestHandler(DeviceSessionRequestHandler { request ->
                    DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, request.payload)
                })
                assertContentEquals(content, pair.browser.rpc("large-control", content).payload)
                assertEquals(0, pair.managementFileBytes)
                pair.browser.openWriteStream("browser-owned", content.size.toLong(), 0, content.size.toLong(), flow { emit(content) })
                assertContentEquals(content, pair.remoteFiles["browser-owned"])
                assertEquals(content.size, pair.managementFileBytes)
                assertTrue(pair.maxManagementRequestBytes <= WEB_UI_FILE_STREAM_CHUNK_BYTES)
            }
        }
    }

    @Test
    fun hostFileUploadsAndDownloadsBypassManagementBodies(): Unit = runBlocking {
        withTimeout(20_000) {
            withStreams { pair ->
                val content = ByteArray(4 * 1024 * 1024 + 73) { ((it * 17) % 251).toByte() }
                val source = Files.write(pair.root.resolve("source.bin"), content)
                pair.browser.transferHostStream(
                    DeviceSessionStreamOpen(DEVICE_SESSION_STREAM_WRITE, "remote.bin", content.size.toLong(), 0, content.size.toLong()),
                    DeviceSessionHostTransfer(0, DeviceSessionHostTransferKind.SendFile, source.toString(), content.size.toLong(), 0, content.size.toLong()), {},
                )
                assertContentEquals(content, pair.remoteFiles["remote.bin"])
                val destination = pair.root.resolve("download.bin")
                pair.browser.transferHostStream(
                    DeviceSessionStreamOpen(DEVICE_SESSION_STREAM_READ, "remote.bin", content.size.toLong(), 0, content.size.toLong()),
                    DeviceSessionHostTransfer(0, DeviceSessionHostTransferKind.ReceiveFile, destination.toString(), content.size.toLong(), 0, content.size.toLong()), {},
                )
                assertContentEquals(content, Files.readAllBytes(destination))
                assertEquals(0, pair.managementFileBytes)
                assertTrue(pair.managementBytes < content.size / 10, "管理连接只应传输控制和进度信息")
            }
        }
    }

    @Test
    fun remotePullAndWriteUseHostStreamsAfterBrowserAcceptsOpen(): Unit = runBlocking {
        withTimeout(20_000) {
            withStreams { pair ->
                val content = ByteArray(2 * 1024 * 1024 + 19) { (it % 239).toByte() }
                val source = Files.write(pair.root.resolve("shared.bin"), content)
                val destination = pair.root.resolve("received.bin")
                pair.browser.setStreamOpenHandler(DeviceSessionStreamOpenHandler { id, open ->
                    val job = pair.scope.launch {
                        val receive = open.kind == DEVICE_SESSION_STREAM_WRITE
                        pair.browser.connection.hostTransfer(DeviceSessionHostTransfer(id,
                            if (receive) DeviceSessionHostTransferKind.ReceiveFile else DeviceSessionHostTransferKind.SendFile,
                            if (receive) destination.toString() else source.toString(), content.size.toLong(), open.startOffset, open.endOffset))
                        pair.browser.connection.sendTrailer(id)
                    }
                    object : DeviceSessionIncomingStream {
                        override suspend fun onData(chunk: ByteArray) { error("文件正文进入了浏览器") }
                        override suspend fun onReset() { job.cancel() }
                    }
                })
                val downloaded = ByteArrayOutputStream()
                pair.native.openReadStream(source.toString(), 31, content.size.toLong() - 17) { downloaded.write(it) }
                assertContentEquals(content.copyOfRange(31, content.size - 17), downloaded.toByteArray())
                pair.native.openWriteStream(destination.toString(), content.size.toLong(), 0, content.size.toLong(), flow { emit(content) })
                assertContentEquals(content, Files.readAllBytes(destination))
                assertEquals(0, pair.managementFileBytes)
            }
        }
    }

    @Test
    fun fsarRoundTripRunsOnHostAndReportsCommittedFiles(): Unit = runBlocking {
        withTimeout(20_000) {
            withStreams { pair ->
                val first = ByteArray(190_013) { (it % 227).toByte() }
                val second = "second archive file".encodeToByteArray()
                val firstPath = Files.write(pair.root.resolve("first.bin"), first)
                val secondPath = Files.write(pair.root.resolve("second.txt"), second)
                val entries = listOf(
                    FolderSpanArchiveEntryRequest(firstPath.toString(), "nested/first.bin", first.size.toLong()),
                    FolderSpanArchiveEntryRequest(secondPath.toString(), "second.txt", second.size.toLong()),
                )
                val options = FolderSpanArchiveStreamOptions(FolderSpanArchiveCompressionCodec.NONE)
                val remoteRoot = pair.root.resolve("remote-archive")
                val read = FolderSpanArchiveReadRequest(entries, options)
                val write = FolderSpanArchiveWriteRequest(remoteRoot.toString(), 2, (first.size + second.size).toLong())
                val uploaded = pair.browser.transferHostStream(
                    DeviceSessionStreamOpen(DEVICE_SESSION_STREAM_ARCHIVE_WRITE, payload = ProtoBufCodec.encode(write)),
                    DeviceSessionHostTransfer(0, DeviceSessionHostTransferKind.SendArchive, archiveRead = read), {},
                )
                assertEquals(listOf("nested/first.bin", "second.txt"), uploaded.completedRelativePaths)
                assertContentEquals(first, Files.readAllBytes(remoteRoot.resolve("nested/first.bin")))
                pair.remoteFiles["remote-first"] = first
                pair.remoteFiles["remote-second"] = second
                val remoteRead = read.copy(entries = entries.mapIndexed { index, entry -> entry.copy(sourcePath = if (index == 0) "remote-first" else "remote-second") })
                val localRoot = pair.root.resolve("local-archive")
                val downloaded = pair.browser.transferHostStream(
                    DeviceSessionStreamOpen(DEVICE_SESSION_STREAM_ARCHIVE_READ, payload = ProtoBufCodec.encode(remoteRead)),
                    DeviceSessionHostTransfer(0, DeviceSessionHostTransferKind.ReceiveArchive, archiveWrite = write.copy(destinationRootPath = localRoot.toString())), {},
                )
                assertEquals(uploaded, downloaded)
                assertContentEquals(first, Files.readAllBytes(localRoot.resolve("nested/first.bin")))
                assertContentEquals(second, Files.readAllBytes(localRoot.resolve("second.txt")))
                assertEquals(0, pair.managementFileBytes)
            }
        }
    }

    @Test
    fun pausedDownloadStopsPersistingAndCancellationDeletesNewTarget(): Unit = runBlocking {
        withTimeout(20_000) {
            withStreams { pair ->
                val content = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
                pair.remoteFiles["slow.bin"] = content
                pair.readDelay = 8
                val destination = pair.root.resolve("cancelled.bin")
                val paused = CompletableDeferred<Unit>()
                var pauseRequested = false
                val download = pair.scope.launch {
                    pair.browser.transferHostStream(
                        DeviceSessionStreamOpen(DEVICE_SESSION_STREAM_READ, "slow.bin", content.size.toLong(), 0, content.size.toLong()),
                        DeviceSessionHostTransfer(0, DeviceSessionHostTransferKind.ReceiveFile, destination.toString(), content.size.toLong(), 0, content.size.toLong()),
                        onProgress = { if (it.values.sum() > 0) { pauseRequested = true; paused.complete(Unit); awaitCancellation() } },
                        isPaused = { pauseRequested },
                    )
                }
                paused.await()
                val transfer = pair.session.resource(checkNotNull(pair.lastTransfer)) as PlatformDeviceTransfer
                delay(250)
                val before = transfer.status(false).progress
                delay(250)
                val after = transfer.status(false).progress
                assertEquals(before, after)
                download.cancelAndJoin()
                assertFalse(Files.exists(destination), "取消时应清理本次创建的未完成目标")
                assertFailsWith<IllegalArgumentException> { pair.session.resource(checkNotNull(pair.lastTransfer)) }
                pair.readDelay = 0
                val next = Files.writeString(pair.root.resolve("after-cancel.txt"), "still connected")
                val size = Files.size(next)
                pair.browser.transferHostStream(DeviceSessionStreamOpen(DEVICE_SESSION_STREAM_WRITE, "after-cancel", size, 0, size),
                    DeviceSessionHostTransfer(0, DeviceSessionHostTransferKind.SendFile, next.toString(), size, 0, size), {})
                assertEquals("still connected", pair.remoteFiles["after-cancel"]?.decodeToString())
            }
        }
    }

    @Test
    fun handlesAreSessionBoundAndSymbolicLinkSourcesAreRejected(): Unit = runBlocking {
        withTimeout(20_000) {
            withStreams { pair ->
                val other = WebUiPlatformSession("other")
                try {
                    assertFailsWith<IllegalArgumentException> {
                        JvmWebUiPlatform().handle(LinkShareHttpRequest.from("GET", "$WEB_UI_PLATFORM_PATH/devices/transport?handle=${pair.handle}&operation=events"), other)
                    }
                    val source = Files.writeString(pair.root.resolve("ordinary.txt"), "ordinary")
                    val link = Files.createSymbolicLink(pair.root.resolve("link.txt"), source)
                    assertFails {
                        pair.browser.transferHostStream(DeviceSessionStreamOpen(DEVICE_SESSION_STREAM_WRITE, "denied.txt", 8, 0, 8),
                            DeviceSessionHostTransfer(0, DeviceSessionHostTransferKind.SendFile, link.toString(), 8, 0, 8), {})
                    }
                    assertNull(pair.remoteFiles["denied.txt"])
                    assertEquals(0, pair.managementFileBytes)
                } finally { other.close() }
            }
        }
    }

    @Test
    fun deviceRelayUsesTwoHostConnectionsWithoutManagementBody(): Unit = runBlocking {
        withTimeout(20_000) {
            withStreams { source ->
                val target = StreamPair(source.scope, source.session, source.bridge)
                try {
                    target.start()
                    val content = ByteArray(3 * 1024 * 1024 + 29) { (it % 233).toByte() }
                    source.remoteFiles["relay-source"] = content
                    source.browser.transferHostRelay(target.browser,
                        DeviceSessionStreamOpen(DEVICE_SESSION_STREAM_READ, "relay-source", content.size.toLong(), 0, content.size.toLong()),
                        DeviceSessionStreamOpen(DEVICE_SESSION_STREAM_WRITE, "relay-target", content.size.toLong(), 0, content.size.toLong()),
                        DeviceSessionHostTransfer(0, DeviceSessionHostTransferKind.Relay, "relay-source", content.size.toLong(), 0, content.size.toLong()), {})
                    assertContentEquals(content, target.remoteFiles["relay-target"])
                    val options = FolderSpanArchiveStreamOptions(FolderSpanArchiveCompressionCodec.NONE)
                    val smallFile = content.copyOf(190_003)
                    source.remoteFiles["archive-source"] = smallFile
                    val read = FolderSpanArchiveReadRequest(listOf(FolderSpanArchiveEntryRequest("archive-source", "nested/file.bin", smallFile.size.toLong())), options)
                    val destination = target.root.resolve("relayed-archive")
                    val write = FolderSpanArchiveWriteRequest(destination.toString(), 1, smallFile.size.toLong())
                    val archived = source.browser.transferHostRelay(target.browser,
                        DeviceSessionStreamOpen(DEVICE_SESSION_STREAM_ARCHIVE_READ, payload = ProtoBufCodec.encode(read)),
                        DeviceSessionStreamOpen(DEVICE_SESSION_STREAM_ARCHIVE_WRITE, payload = ProtoBufCodec.encode(write)),
                        DeviceSessionHostTransfer(0, DeviceSessionHostTransferKind.Relay, archiveRead = read), {})
                    assertEquals(listOf("nested/file.bin"), archived.completedRelativePaths)
                    assertEquals(smallFile.size.toLong(), archived.committedFileBytes)
                    assertContentEquals(smallFile, Files.readAllBytes(destination.resolve("nested/file.bin")))
                    assertEquals(0, source.managementFileBytes + target.managementFileBytes)
                } finally { target.close(closeSession = false) }
            }
        }
    }

    @Test
    fun archiveEntriesWaitForApprovalAndFailurePreservesCommittedFiles(): Unit = runBlocking {
        withTimeout(20_000) {
            withStreams { pair ->
                pair.forcedCallbackId = "entry-authorization"
                val destination = pair.root.resolve("approved-archive")
                val entries = listOf(
                    FolderSpanArchiveEntryRequest("first", "first.txt", 5),
                    FolderSpanArchiveEntryRequest("second", "denied.txt", 6),
                )
                val options = FolderSpanArchiveStreamOptions(FolderSpanArchiveCompressionCodec.NONE)
                val write = FolderSpanArchiveWriteRequest(destination.toString(), 2, 11)
                pair.browser.setStreamOpenHandler(DeviceSessionStreamOpenHandler { id, _ ->
                    val job = pair.scope.launch {
                        val response = try {
                            DeviceSessionArchiveTrailer(checkNotNull(pair.browser.connection.hostTransfer(
                                DeviceSessionHostTransfer(id, DeviceSessionHostTransferKind.ReceiveArchive, archiveWrite = write))))
                        } catch (error: FolderSpanArchiveRemoteWriteException) {
                            DeviceSessionArchiveTrailer(error.completedArchiveTransferResult(), error.message.orEmpty())
                        }
                        pair.browser.connection.sendTrailer(id, ProtoBufCodec.encode(response))
                    }
                    object : DeviceSessionIncomingStream {
                        override suspend fun onData(chunk: ByteArray) { error("归档正文进入了浏览器") }
                        override suspend fun onReset() { job.cancel() }
                    }
                })
                val transfer = pair.scope.async {
                    runCatching {
                        pair.native.openArchiveWriteStream(write, ArchiveStreamEncoder.encode(entries, options) { entry ->
                            flow { emit(if (entry.sourcePath == "first") "first".encodeToByteArray() else "second".encodeToByteArray()) }
                        })
                    }
                }
                val first = pair.bridge.unclaimed.receive()
                assertEquals("entry-authorization", first.handle)
                assertEquals("device-prepare-entry", first.type)
                assertEquals(destination.resolve("first.txt").toString(), WebUiPlatformClient.json.decodeFromString<WebUiDevicePrepareEntry>(first.data).path)
                assertFalse(Files.exists(destination.resolve("first.txt")))
                pair.session.reply(first.id, byteArrayOf())
                val second = pair.bridge.unclaimed.receive()
                assertEquals("first", Files.readString(destination.resolve("first.txt")))
                assertFalse(Files.exists(destination.resolve("denied.txt")))
                pair.session.reply(second.id, byteArrayOf(), failed = true)
                val failure = assertIs<FolderSpanArchiveRemoteWriteException>(transfer.await().exceptionOrNull())
                assertEquals(listOf("first.txt"), failure.completedArchiveTransferResult().completedRelativePaths)
                assertEquals(5L, failure.completedArchiveTransferResult().committedFileBytes)
                assertFalse(Files.exists(destination.resolve("denied.txt")))
                assertEquals(0, pair.managementFileBytes)
            }
        }
    }

    @Test
    fun cancelledArchiveKeepsCommittedEntriesAndDeletesActiveFile(): Unit = runBlocking {
        withTimeout(20_000) {
            withStreams { pair ->
                pair.remoteFiles["first"] = "committed".encodeToByteArray()
                pair.remoteFiles["second"] = ByteArray(490_013) { (it % 223).toByte() }
                pair.readDelay = 80
                val entries = listOf(
                    FolderSpanArchiveEntryRequest("first", "first.txt", 9),
                    FolderSpanArchiveEntryRequest("second", "partial.bin", 490_013),
                )
                val read = FolderSpanArchiveReadRequest(entries, FolderSpanArchiveStreamOptions(FolderSpanArchiveCompressionCodec.NONE))
                val destination = pair.root.resolve("cancelled-archive")
                val write = FolderSpanArchiveWriteRequest(destination.toString(), 2, 490_022)
                val paused = CompletableDeferred<Unit>()
                var pauseRequested = false
                val transfer = pair.scope.launch {
                    pair.browser.transferHostStream(
                        DeviceSessionStreamOpen(DEVICE_SESSION_STREAM_ARCHIVE_READ, payload = ProtoBufCodec.encode(read)),
                        DeviceSessionHostTransfer(0, DeviceSessionHostTransferKind.ReceiveArchive, archiveWrite = write),
                        onProgress = { if ((it["partial.bin"] ?: 0) > 0) { pauseRequested = true; paused.complete(Unit); awaitCancellation() } },
                        isPaused = { pauseRequested })
                }
                paused.await()
                val progress = (pair.session.resource(checkNotNull(pair.lastTransfer)) as PlatformDeviceTransfer).status(false).progress
                assertTrue(checkNotNull(progress["partial.bin"]) in 1 until 490_013L)
                assertEquals("committed", Files.readString(destination.resolve("first.txt")))
                assertTrue(Files.exists(destination.resolve("partial.bin")))
                transfer.cancelAndJoin()
                assertEquals("committed", Files.readString(destination.resolve("first.txt")))
                assertFalse(Files.exists(destination.resolve("partial.bin")))
                assertEquals(0, pair.managementFileBytes)
            }
        }
    }

    @Test
    fun directShareRangeIsReadByHostAndRejectsSymbolicLinks(): Unit = runBlocking {
        withTimeout(10_000) {
            val session = WebUiPlatformSession("direct-sharing")
            val root = Files.createTempDirectory("direct-share-range").toRealPath()
            try {
                val content = ByteArray(1024 * 1024 + 59) { (it % 229).toByte() }
                val source = Files.write(root.resolve("source.bin"), content)
                suspend fun response(path: String): LinkShareHttpResponse = coroutineScope {
                    val pending = async { relayPlatformHttp(session, "browser", LinkShareHttpRequest.from("GET", "/download")) }
                    val event = session.poll().single()
                    session.reply(event.id, WebUiPlatformClient.json.encodeToString(WebUiOutgoingHttp(206,
                        listOf("Content-Type" to "application/octet-stream"), content.size.toLong() - 37,
                        LinkShareHostFileRange(path, 17, content.size.toLong() - 37))).encodeToByteArray())
                    pending.await()
                }
                val shared = response(source.toString())
                assertEquals(206, shared.statusCode)
                val output = ByteArrayOutputStream()
                (shared.body as LinkShareHttpResponseBody.Stream).writer(object : LinkShareHttpResponseBodyWriter {
                    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) { output.write(bytes, offset, length) }
                    override suspend fun flush() = Unit
                })
                assertContentEquals(content.copyOfRange(17, content.size - 20), output.toByteArray())
                val link = Files.createSymbolicLink(root.resolve("link.bin"), source)
                assertFails { response(link.toString()) }
            } finally { session.close(); root.toFile().deleteRecursively() }
        }
    }

    private suspend fun withStreams(block: suspend (StreamPair) -> Unit) = coroutineScope {
        SettingsUtils.init(MapSettings())
        val pair = StreamPair(this)
        try { pair.start(); block(pair) } finally { pair.close() }
    }
}

private class StreamPair(val scope: CoroutineScope, val session: WebUiPlatformSession = WebUiPlatformSession("host-stream-test"),
    val bridge: StreamEventBridge = StreamEventBridge(scope, session)) {
    val root: Path = Files.createTempDirectory("host-device-streams").toRealPath()
    val remoteFiles = mutableMapOf<String, ByteArray>()
    var readDelay = 0L
    var forcedCallbackId: String? = null
    var lastTransfer: String? = null
    var managementBytes = 0
    var maxManagementRequestBytes = 0
    var managementFileBytes = 0
    lateinit var handle: String
    private val toHost = Channel<ByteArray>(64)
    private val toDevice = Channel<ByteArray>(64)
    private val hostChannel = StreamTestChannel(toHost, toDevice)
    private val nativeChannel = StreamTestChannel(toDevice, toHost)
    private val plan = DeviceSessionWindowPlan(2 * 1024 * 1024, 1024 * 1024, 4)
    lateinit var browser: DeviceSessionPeer
    val native = DeviceSessionPeer(DeviceSessionTransport(nativeChannel, plan, plan, false), plan, DeviceSessionEndpointRole.Server)
    private var browserJob: Job? = null
    private var nativeJob: Job? = null

    suspend fun start() {
        handle = ownDeviceChannel(session, hostChannel).first
        val platform = JvmWebUiPlatform()
        val proxy = WebUiDeviceTransport(handle, WebUiDeviceTransportOpen(plan, plan, true), exchange = { method, path, body ->
            val input = if (path.contains("operation=transfer") && body != null && forcedCallbackId != null) {
                WebUiPlatformClient.json.encodeToString(WebUiPlatformClient.json.decodeFromString<DeviceSessionHostTransfer>(body.decodeToString())
                    .copy(callbackId = checkNotNull(forcedCallbackId))).encodeToByteArray()
            } else body ?: byteArrayOf()
            maxManagementRequestBytes = maxOf(maxManagementRequestBytes, input.size)
            if (method == "POST" && path.contains("operation=data")) managementFileBytes += input.size
            if (method == "POST" && path.contains("operation=control")) {
                val operation = WebUiPlatformClient.json.decodeFromString<WebUiDeviceOperation>(input.decodeToString())
                if (operation.operation == "data" && operation.streamId != 0) managementFileBytes += operation.payload.size
            }
            val response = platform.handle(LinkShareHttpRequest.from(method, path, body = LinkShareHttpRequestBody.Bytes(input)), session)
            val bytes = (response.body as LinkShareHttpResponseBody.Bytes).bytes
            managementBytes += (body?.size ?: 0) + bytes.size
            if (method == "GET" && path.contains("operation=body")) managementFileBytes += bytes.size
            if (method == "POST" && path.contains("operation=transfer")) lastTransfer = WebUiPlatformClient.json.decodeFromString<WebUiHandle>(bytes.decodeToString()).id
            WebUiHttpExchange(response.statusCode, response.headers.associate { it.name to it.value }, bytes)
        }, subscribe = bridge::register, unsubscribe = bridge::unregister)
        val browserChannel = object : DeviceSessionPlatformChannel {
            override fun frameTransport(sendPlan: DeviceSessionWindowPlan, receivePlan: DeviceSessionWindowPlan, isClient: Boolean, onPing: (suspend (ByteArray) -> Unit)?) = proxy
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = error("raw browser reads are disabled")
            override suspend fun write(buffer: ByteArray, offset: Int, length: Int) { error("raw browser writes are disabled") }
            override suspend fun flush() = Unit
            override fun close() = Unit
        }
        browser = DeviceSessionPeer(DeviceSessionTransport(browserChannel, plan, plan, true), plan)
        native.setStreamOpenHandler(DeviceSessionStreamOpenHandler { id, open ->
            val collected = ByteArrayOutputStream()
            var sending: Job? = null
            if (open.kind == DEVICE_SESSION_STREAM_READ || open.kind == DEVICE_SESSION_STREAM_ARCHIVE_READ) {
                sending = scope.launch {
                    try {
                    if (open.kind == DEVICE_SESSION_STREAM_READ) {
                        val bytes = checkNotNull(remoteFiles[open.path])
                        var offset = open.startOffset.toInt()
                        while (offset < open.endOffset) {
                            if (readDelay > 0) delay(readDelay)
                            val end = minOf(offset + 65536, open.endOffset.toInt())
                            native.connection.sendData(id, bytes.copyOfRange(offset, end))
                            offset = end
                        }
                        native.connection.sendTrailer(id)
                    } else {
                        val read = ProtoBufCodec.decode<FolderSpanArchiveReadRequest>(open.payload)
                        ArchiveStreamEncoder.encode(read.entries, read.options) { entry -> flow { emit(checkNotNull(remoteFiles[entry.sourcePath])) } }
                            .collect { bytes ->
                                var offset = 0
                                while (offset < bytes.size) {
                                    if (readDelay > 0) delay(readDelay)
                                    val end = minOf(offset + 65536, bytes.size)
                                    native.connection.sendData(id, bytes.copyOfRange(offset, end))
                                    offset = end
                                }
                            }
                        native.connection.sendTrailer(id, ProtoBufCodec.encode(DeviceSessionArchiveTrailer(
                            FolderSpanArchiveTransferResult(read.entries.map { it.relativePath }, read.entries.sumOf { it.size }))))
                    }
                    } catch (_: DeviceSessionClosedException) {
                        // Reset wakes credit waiters before the peer dispatches onReset.
                    }
                }
            }
            object : DeviceSessionIncomingStream {
                override suspend fun onData(chunk: ByteArray) { collected.write(chunk) }
                override suspend fun onTrailer(payload: ByteArray) {
                    if (open.kind == DEVICE_SESSION_STREAM_WRITE) {
                        remoteFiles[open.path] = collected.toByteArray()
                        native.connection.sendTrailer(id)
                    } else if (open.kind == DEVICE_SESSION_STREAM_ARCHIVE_WRITE) {
                        val write = ProtoBufCodec.decode<FolderSpanArchiveWriteRequest>(open.payload)
                        val result = ArchiveStreamDecoder.decode(flow { emit(collected.toByteArray()) }, ArchiveEntryExtractor(scope, write.destinationRootPath))
                        native.connection.sendTrailer(id, ProtoBufCodec.encode(DeviceSessionArchiveTrailer(result)))
                    }
                }
                override suspend fun onReset() { sending?.cancel() }
            }
        })
        nativeJob = native.start(scope)
        browserJob = browser.start(scope)
    }

    suspend fun close(closeSession: Boolean = true) {
        browser.close()
        native.close()
        browserJob?.cancelAndJoin()
        nativeJob?.cancelAndJoin()
        if (closeSession) { bridge.close(); session.close() }
        root.toFile().deleteRecursively()
    }
}

private class StreamTestChannel(private val input: Channel<ByteArray>, private val output: Channel<ByteArray>) : DeviceSessionByteChannel {
    private var pending = byteArrayOf()
    private var position = 0
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (position == pending.size) {
            pending = input.receiveCatching().getOrNull() ?: return -1
            position = 0
        }
        val count = minOf(length, pending.size - position, 8191)
        pending.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }
    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) { output.send(buffer.copyOfRange(offset, offset + length)) }
    override suspend fun flush() = Unit
    override fun close() { output.close() }
}

private class StreamEventBridge(scope: CoroutineScope, private val session: WebUiPlatformSession) {
    val unclaimed = Channel<WebUiPlatformEvent>(32)
    private val callbacks = java.util.concurrent.ConcurrentHashMap<String, Channel<WebUiPlatformEvent>>()
    private val workers = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val eventScope = scope
    private val pump = scope.launch {
        try {
            while (isActive) {
                val event = session.nextEvent()
                val target = callbacks[event.handle]
                if (target == null) unclaimed.send(event) else target.send(event)
            }
        } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) { }
    }
    suspend fun register(handle: String, failed: (Throwable) -> Unit, listener: suspend (WebUiPlatformEvent) -> ByteArray) {
        val queue = Channel<WebUiPlatformEvent>(32)
        check(callbacks.putIfAbsent(handle, queue) == null)
        workers[handle] = eventScope.launch {
            try {
                for (event in queue) {
                    try {
                        val reply = listener(event)
                        if (event.requiresReply) session.reply(event.id, reply)
                    } catch (failure: Exception) {
                        if (event.requiresReply) session.reply(event.id, byteArrayOf(), true) else throw failure
                    }
                }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { failed(failure) }
        }
    }
    suspend fun unregister(handle: String) {
        callbacks.remove(handle)?.cancel()
        workers.remove(handle)?.cancelAndJoin()
    }
    suspend fun close() { pump.cancelAndJoin(); workers.values.forEach { it.cancelAndJoin() }; unclaimed.cancel() }
}

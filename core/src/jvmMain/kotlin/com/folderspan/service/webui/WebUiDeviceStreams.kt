package com.folderspan.service.webui

import com.folderspan.service.http.archive.*
import com.folderspan.service.http.server.linkshare.*
import com.folderspan.service.session.*
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/** Owns framing/credit and file bodies, but never owns business authorization or tasks. */
internal class PlatformDeviceTransport(
    private val owner: PlatformDeviceChannel,
    private val session: WebUiPlatformSession,
    options: WebUiDeviceTransportOpen,
    private val eventHandle: String,
    parentScope: CoroutineScope = session.scope,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val transport = DeviceSessionTransport(owner.channel, options.sendPlan, options.receivePlan, options.isClient,
        onPing = { events.send(DeviceSessionFrames.ping(it)) })
    val connection = transport.connection as DeviceSessionConnection
    private val events = Channel<DeviceSessionFrame>(options.receivePlan.frameQueueCapacity())
    private val mutex = Mutex()
    private val bodyMutex = Mutex()
    private val bodies = Channel<DeviceSessionFrame>(options.receivePlan.frameQueueCapacity())
    private class PendingOpen(parent: Job) {
        val ready = CompletableDeferred<Unit>(parent)
        val sent = CompletableDeferred<Unit>(parent)
        var worker: Job? = null
        var startRequested = false

        fun fail(error: Throwable) {
            ready.completeExceptionally(error)
            sent.completeExceptionally(error)
        }
    }
    private val opening = mutableMapOf<Int, PendingOpen>()
    private val routes = mutableMapOf<Int, CompletableDeferred<PlatformDeviceTransfer?>>()
    private val transfers = mutableMapOf<Int, PlatformDeviceTransfer>()
    private val queueCapacity = options.receivePlan.streamFrameQueueCapacity()
    private val maxTransfers = minOf(options.sendPlan.maxFileStreams, options.receivePlan.maxFileStreams)

    init {
        scope.launch {
            try {
                for (frame in events) session.emitBytes(eventHandle, "device-frames", DeviceSessionFrames.encodeAll(listOf(frame)))
            } finally {
                withContext(NonCancellable) { runCatching { session.emit(eventHandle, "device-end") } }
                bodies.cancel()
            }
        }
        transport.start(scope)
        scope.launch {
            try {
                for (frame in connection.incomingEvents) {
                    when (frame.type) {
                        DeviceSessionFrameType.Open -> {
                            val open = runCatching { DeviceSessionProtocol.decodeStreamOpen(frame.payload) }.getOrNull()
                            if (open?.kind == DEVICE_SESSION_STREAM_WRITE || open?.kind == DEVICE_SESSION_STREAM_ARCHIVE_WRITE) {
                                mutex.withLock { routes.getOrPut(frame.streamId) { CompletableDeferred() } }
                            }
                            events.send(frame)
                        }
                        DeviceSessionFrameType.Data -> {
                            val route = mutex.withLock { routes[frame.streamId] }
                            if (route == null && frame.streamId == 0) events.send(frame)
                            else if (route == null) {
                                bodies.send(frame)
                                events.send(DeviceSessionFrame(DeviceSessionFrameType.Data, frame.streamId, byteArrayOf()))
                            }
                            else {
                                val transfer = route.await()
                                try {
                                    transfer?.input?.send(frame.payload)
                                } catch (_: ClosedSendChannelException) {
                                    if (transfer?.archiveFailed == true) connection.sendWindowUpdate(frame.streamId, frame.payload.size)
                                    else runCatching { connection.sendRst(frame.streamId) }
                                } catch (error: CancellationException) {
                                    currentCoroutineContext().ensureActive()
                                    if (transfer?.archiveFailed == true) connection.sendWindowUpdate(frame.streamId, frame.payload.size)
                                    else runCatching { connection.sendRst(frame.streamId) }
                                }
                            }
                        }
                        DeviceSessionFrameType.Trailer, DeviceSessionFrameType.Rst -> {
                            val error = if (frame.type == DeviceSessionFrameType.Rst) DeviceSessionIoException("stream reset: ${frame.streamId}") else null
                            mutex.withLock {
                                routes.remove(frame.streamId)?.complete(null)
                                transfers[frame.streamId]?.input?.close(error)
                            }
                            events.send(frame)
                        }
                        else -> events.send(frame)
                    }
                }
            } catch (error: Throwable) {
                events.close(error)
                if (error is CancellationException) throw error
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        routes.values.forEach { it.completeExceptionally(DeviceSessionClosedException()) }
                        transfers.values.forEach { it.input.close(DeviceSessionClosedException()) }
                    }
                    events.close()
                    owner.close()
                    job.cancel()
                }
            }
        }
    }

    suspend fun body(streamId: Int): LinkShareHttpResponse = bodyMutex.withLock {
        val frame = bodies.receive()
        require(frame.streamId == streamId) { "device body stream order mismatch" }
        LinkShareHttpResponse.bytes(bytes = frame.payload)
    }

    suspend fun operate(input: WebUiDeviceOperation): WebUiDeviceOperationResult {
        require(input.streamId >= 0)
        return when (input.operation) {
            "allocate" -> {
                val allocated = CompletableDeferred<Int>(job)
                val pending = PendingOpen(job)
                var streamId: Int? = null
                pending.worker = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        connection.openStream(input.payload) { id ->
                            streamId = id
                            mutex.withLock { opening[id] = pending }
                            allocated.complete(id)
                            pending.ready.await()
                        }
                        // openStream returns once OPEN is in the ordered outgoing queue, not after a peer ACK.
                        pending.sent.complete(Unit)
                    } catch (error: Throwable) {
                        allocated.completeExceptionally(error)
                        pending.fail(error)
                        withContext(NonCancellable) {
                            mutex.withLock {
                                streamId?.let { id -> if (opening[id] === pending) opening.remove(id) }
                            }
                        }
                    }
                }
                pending.worker!!.start()
                try {
                    WebUiDeviceOperationResult(streamId = allocated.await())
                } catch (error: Throwable) {
                    allocated.completeExceptionally(error)
                    pending.fail(error)
                    pending.worker?.cancel()
                    throw error
                }
            }
            "start" -> {
                val pending = mutex.withLock {
                    requireNotNull(opening[input.streamId]?.takeUnless { it.startRequested }) {
                        "unknown pending stream"
                    }.also { it.startRequested = true }
                }
                pending.ready.complete(Unit)
                try {
                    pending.sent.await()
                    WebUiDeviceOperationResult()
                } catch (error: Throwable) {
                    pending.fail(error)
                    pending.worker?.cancel()
                    throw error
                } finally {
                    withContext(NonCancellable) {
                        mutex.withLock { if (opening[input.streamId] === pending) opening.remove(input.streamId) }
                    }
                }
            }
            "accept" -> WebUiDeviceOperationResult(accepted = connection.acceptRemoteStream(input.streamId))
            "data" -> { connection.sendData(input.streamId, input.payload); WebUiDeviceOperationResult() }
            "control-frame" -> { connection.sendControlFrame(input.payload); WebUiDeviceOperationResult() }
            "trailer" -> { connection.sendTrailer(input.streamId, input.payload); WebUiDeviceOperationResult() }
            "window" -> { require(input.value >= 0); connection.sendWindowUpdate(input.streamId, input.value); WebUiDeviceOperationResult() }
            "reset" -> {
                val pending = mutex.withLock {
                    val pending = opening.remove(input.streamId)
                    routes.remove(input.streamId)?.complete(null)
                    transfers[input.streamId]?.cancel()
                    pending
                }
                pending?.fail(DeviceSessionIoException("stream reset: ${input.streamId}"))
                pending?.worker?.cancel()
                connection.sendRst(input.streamId, input.value)
                WebUiDeviceOperationResult()
            }
            "ping" -> WebUiDeviceOperationResult(payload = connection.ping(input.payload))
            "go-away" -> { connection.goAway(); WebUiDeviceOperationResult() }
            else -> error("unknown device transport operation")
        }
    }

    suspend fun awaitOpenSent(streamId: Int) {
        val pending = mutex.withLock { opening[streamId] }
        pending?.sent?.await()
    }

    suspend fun transfer(request: DeviceSessionHostTransfer): String {
        require(request.streamId > 0)
        val receiving = request.kind == DeviceSessionHostTransferKind.ReceiveFile || request.kind == DeviceSessionHostTransferKind.ReceiveArchive || request.kind == DeviceSessionHostTransferKind.Relay
        val checked = when (request.kind) {
            DeviceSessionHostTransferKind.Relay -> {
                require(request.targetStreamId > 0)
                val target = session.resource(request.targetHandle) as? PlatformDeviceChannel ?: error("not a device channel")
                requireNotNull(target.transport) { "relay target transport not started" }
                require(request.startOffset >= 0 && request.endOffset >= request.startOffset)
                request
            }
            DeviceSessionHostTransferKind.SendFile, DeviceSessionHostTransferKind.ReceiveFile -> {
                require(request.fileSize >= 0 && request.startOffset >= 0 && request.endOffset >= request.startOffset && request.endOffset <= request.fileSize)
                request.copy(path = checkedPlatformPath(request.path, allowMissing = receiving, rejectSymbolicLinks = true))
            }
            DeviceSessionHostTransferKind.SendArchive -> {
                val read = requireNotNull(request.archiveRead)
                require(read.entries.size in 1..FOLDER_SPAN_ARCHIVE_MAX_ENTRIES)
                require(FolderSpanArchiveStreamCapabilities.local().supports(read.options))
                request.copy(archiveRead = read.copy(entries = read.entries.map { if (it.directory) it else it.copy(sourcePath = checkedPlatformPath(it.sourcePath, rejectSymbolicLinks = true)) }))
            }
            DeviceSessionHostTransferKind.ReceiveArchive -> {
                val write = requireNotNull(request.archiveWrite)
                require(write.expectedEntries in 1..FOLDER_SPAN_ARCHIVE_MAX_ENTRIES && write.expectedFileBytes in 0..FOLDER_SPAN_ARCHIVE_MAX_PAYLOAD_BYTES)
                request.copy(archiveWrite = write.copy(destinationRootPath = checkedPlatformPath(write.destinationRootPath, allowMissing = true, rejectSymbolicLinks = true)))
            }
        }
        val transfer = PlatformDeviceTransfer(checked, this, session, scope, queueCapacity)
        mutex.withLock {
            require(request.streamId !in transfers) { "stream already has a transfer" }
            check(transfers.size < maxTransfers) { "device transfer capacity reached" }
            transfers[request.streamId] = transfer
        }
        val handle = session.own(transfer) {
            transfer.close()
            mutex.withLock {
                transfers.remove(request.streamId)
                // Keep dropping late body frames until the peer terminates this stream.
                routes[request.streamId]?.let {
                    it.complete(null)
                    routes[request.streamId] = CompletableDeferred(null)
                }
            }
        }
        mutex.withLock {
            if (receiving) routes.getOrPut(request.streamId) { CompletableDeferred() }.complete(transfer)
            // A transfer requested inside onStreamAllocated must release OPEN itself: the caller
            // cannot send explicit start until that transfer returns. Retain the entry for start.
            opening[request.streamId]?.ready?.complete(Unit)
        }
        transfer.start()
        return handle
    }

    suspend fun close() {
        owner.close()
        job.cancelAndJoin()
    }
}

internal class PlatformDeviceTransfer(
    private val request: DeviceSessionHostTransfer,
    private val transport: PlatformDeviceTransport,
    private val session: WebUiPlatformSession,
    private val scope: CoroutineScope,
    capacity: Int,
) {
    val input = Channel<ByteArray>(capacity)
    private val mutex = Mutex()
    private val progress = linkedMapOf<String, Long>()
    private val running = kotlinx.coroutines.flow.MutableStateFlow(!request.initiallyPaused)
    private var result = FolderSpanArchiveTransferResult()
    private var error = ""
    private var complete = false
    private var worker: Job? = null
    private var reporting: Job? = null
    @Volatile var archiveFailed = false
        private set

    fun start() {
        worker = scope.launch {
            try {
                transport.awaitOpenSent(request.streamId)
                result = when (request.kind) {
                    DeviceSessionHostTransferKind.SendFile -> sendFile()
                    DeviceSessionHostTransferKind.ReceiveFile -> receiveFile()
                    DeviceSessionHostTransferKind.SendArchive -> sendArchive()
                    DeviceSessionHostTransferKind.ReceiveArchive -> receiveArchive()
                    DeviceSessionHostTransferKind.Relay -> relay()
                }
            } catch (failure: Throwable) {
                result = failure.completedArchiveTransferResult()
                error = failure.message.orEmpty().ifBlank { "device file transfer failed" }
                archiveFailed = request.kind == DeviceSessionHostTransferKind.ReceiveArchive && failure !is CancellationException
                // The browser sends an archive trailer containing committed entries on failure.
                if (!archiveFailed) withContext(NonCancellable) { runCatching { transport.connection.sendRst(request.streamId) } }
                if (failure is CancellationException) throw failure
            } finally {
                withContext(NonCancellable) {
                    input.cancel()
                    mutex.withLock { complete = true }
                }
            }
        }
        if (request.progressCallbackId.isNotBlank()) reporting = scope.launch {
            try {
                while (isActive) {
                    delay(200)
                    val snapshot = status(false)
                    val data = platformJson.encodeToString(snapshot)
                    if (snapshot.complete) {
                        session.finishLatest(request.progressCallbackId, "device-progress", data)
                        break
                    }
                    session.emitLatest(request.progressCallbackId, "device-progress", data)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                session.close(awaitRelease = false)
            }
        }
    }

    private suspend fun awaitRunning() { running.first { it }; currentCoroutineContext().ensureActive() }
    private suspend fun report(path: String, bytes: Int) { mutex.withLock { progress[path] = (progress[path] ?: 0L) + bytes } }

    private suspend fun sendFile(): FolderSpanArchiveTransferResult {
        FileUtils.readFileRangeChunks(FileAccessPermission.Allowed, request.path, request.startOffset, request.endOffset, 256L * 1024).buffer(1).collect { item ->
            awaitRunning()
            val bytes = item.getOrThrow().second
            transport.connection.sendData(request.streamId, bytes)
            report(request.path, bytes.size)
        }
        return FolderSpanArchiveTransferResult(listOf(request.path), request.endOffset - request.startOffset)
    }

    private suspend fun receiveFile(): FolderSpanArchiveTransferResult {
        val existed = PathUtils.exists(FileAccessPermission.Allowed, request.path)
        var received = 0L
        withIncompleteDeviceSessionTargetCleanup(request.path, existed) {
            FileUtils.writeByteStream(FileAccessPermission.Allowed, request.path, request.fileSize, request.startOffset,
                request.endOffset - request.startOffset, DEVICE_SESSION_MAX_PAYLOAD_BYTES,
                readNext = { buffer, length ->
                    awaitRunning()
                    val next = input.receiveCatching()
                    next.exceptionOrNull()?.let { throw it }
                    val chunk = next.getOrNull() ?: return@writeByteStream -1
                    awaitRunning()
                    require(chunk.size <= length) { "device file stream exceeds declared range" }
                    chunk.copyInto(buffer)
                    chunk.size
                }, onBytesWritten = { _, bytes ->
                    received += bytes
                    report(request.path, bytes)
                    transport.connection.sendWindowUpdate(request.streamId, bytes)
                }).getOrThrow()
            // The sender must terminate the stream, including an empty file, before it is committed.
            val extra = input.receiveCatching()
            extra.exceptionOrNull()?.let { throw it }
            require(extra.isClosed) { "device file stream exceeds declared range" }
            require(received == request.endOffset - request.startOffset) { "incomplete device file stream" }
        }
        return FolderSpanArchiveTransferResult(listOf(request.path), received)
    }

    private suspend fun sendArchive(): FolderSpanArchiveTransferResult {
        val read = requireNotNull(request.archiveRead)
        ArchiveStreamEncoder.encode(read.entries, read.options) { entry ->
            flow {
                if (!entry.directory) {
                    val info = FileUtils.getFile(FileAccessPermission.Allowed, entry.sourcePath).getOrThrow()
                    require(!info.isDirectory && info.size == entry.size) { "archive source changed while planning" }
                    FileUtils.readFileChunks(FileAccessPermission.Allowed, entry.sourcePath, FOLDER_SPAN_ARCHIVE_BUFFER_BYTES.toLong()).buffer(1).collect { item ->
                        awaitRunning()
                        val bytes = item.getOrThrow().second
                        emit(bytes)
                        report(entry.relativePath, bytes.size)
                    }
                }
            }
        }.collect { bytes -> awaitRunning(); transport.connection.sendData(request.streamId, bytes) }
        return FolderSpanArchiveTransferResult(read.entries.map { it.relativePath }, read.entries.filterNot { it.directory }.sumOf { it.size })
    }

    private suspend fun relay(): FolderSpanArchiveTransferResult {
        val target = requireNotNull((session.resource(request.targetHandle) as PlatformDeviceChannel).transport)
        target.awaitOpenSent(request.targetStreamId)
        var received = 0L
        val chunks = flow {
            for (chunk in input) {
                awaitRunning()
                emit(chunk)
                received += chunk.size
                transport.connection.sendWindowUpdate(request.streamId, chunk.size)
                report(request.path, chunk.size)
            }
        }
        val stream = request.archiveRead?.let { validateArchiveRelayPrelude(chunks, it.options) } ?: chunks
        stream.collect { bytes -> target.connection.sendData(request.targetStreamId, bytes) }
        if (request.archiveRead == null) require(received == request.endOffset - request.startOffset) { "relay stream length mismatch" }
        return FolderSpanArchiveTransferResult(committedFileBytes = received)
    }

    private suspend fun prepare(path: String, size: Long, directory: Boolean) {
        checkedPlatformPath(path, allowMissing = true, rejectSymbolicLinks = true)
        if (request.callbackId.isNotBlank()) session.callback(request.callbackId, "device-prepare-entry",
            platformJson.encodeToString(WebUiDevicePrepareEntry(path, size, directory)))
    }

    private suspend fun receiveArchive(): FolderSpanArchiveTransferResult = supervisorScope {
        val write = requireNotNull(request.archiveWrite)
        val extractor = ArchiveEntryExtractor(this, write.destinationRootPath,
            prepareDirectory = { path -> prepare(path, 0, true); FileUtils.createFolder(FileAccessPermission.Allowed, path).getOrThrow() },
            prepareFile = { path, size -> prepare(path, size, false) })
        val decoded = ArchiveStreamDecoder.decode(
            chunks = flow { for (chunk in input) { awaitRunning(); emit(chunk); transport.connection.sendWindowUpdate(request.streamId, chunk.size) } },
            sink = object : FolderSpanArchiveEntrySink by extractor {
                override suspend fun abort(header: FolderSpanArchiveEntryHeader, cause: Throwable) {
                    withContext(NonCancellable) { extractor.abort(header, cause) }
                }
            },
            onFileBytes = { path, bytes -> report(path, bytes) },
        )
        require(decoded.completedRelativePaths.size == write.expectedEntries && decoded.committedFileBytes == write.expectedFileBytes) { "archive committed entries or bytes do not match request" }
        decoded
    }

    suspend fun status(pause: Boolean): WebUiDeviceTransferStatus {
        if (pause) running.value = false
        return mutex.withLock { WebUiDeviceTransferStatus(complete, progress.toMap(), result, error) }
    }
    fun setPaused(paused: Boolean) { running.value = !paused }
    fun cancel() { worker?.cancel(); input.cancel() }
    suspend fun close() {
        if (!mutex.withLock { complete }) {
            cancel()
            withContext(NonCancellable) { runCatching { transport.connection.sendRst(request.streamId) } }
        }
        worker?.join()
        reporting?.cancelAndJoin()
    }
}

internal suspend fun deviceStreamPlatformRequest(request: LinkShareHttpRequest, session: WebUiPlatformSession): LinkShareHttpResponse {
    if (request.path.endsWith("/transfers")) {
        val transfer = session.resource(requireNotNull(request.query("handle"))) as? PlatformDeviceTransfer ?: error("not a device transfer")
        return when (request.method) {
            "GET" -> platformResponse(transfer.status(request.query("pause") == "true"))
            "POST" -> { transfer.setPaused(request.query("pause") == "true"); platformResponse(true) }
            else -> LinkShareHttpResponse.text(404, "unknown device transfer operation")
        }
    }
    val channel = session.resource(requireNotNull(request.query("handle"))) as? PlatformDeviceChannel ?: error("not a device channel")
    val operation = request.query("operation")
    if (request.method == "POST" && operation == null) {
        val options = request.platformBody<WebUiDeviceTransportOpen>()
        for (plan in listOf(options.sendPlan, options.receivePlan)) require(plan.sessionWindowBytes <= DEVICE_SESSION_WIDE_WINDOW_BYTES && plan.maxFileStreams <= DEVICE_SESSION_MAX_ACTIVE_STREAMS)
        channel.readMutex.withLock {
            check(channel.transport == null) { "device transport already started" }
            channel.transport = PlatformDeviceTransport(channel, session, options, requireNotNull(request.query("handle")))
        }
        return platformResponse(true)
    }
    val transport = requireNotNull(channel.transport) { "device transport not started" }
    return when (request.method to operation) {
        "GET" to "body" -> transport.body(requireNotNull(request.query("streamId")?.toIntOrNull()))
        "POST" to "data" -> platformResponse(transport.operate(WebUiDeviceOperation("data", requireNotNull(request.query("streamId")?.toIntOrNull()), request.boundedBytes())))
        "POST" to "control" -> {
            val input = request.platformBody<WebUiDeviceOperation>()
            require(input.operation != "data") { "file body requires HTTP" }
            platformResponse(transport.operate(input))
        }
        "POST" to "transfer" -> platformResponse(WebUiHandle(transport.transfer(request.platformBody())))
        else -> LinkShareHttpResponse.text(404, "unknown device stream operation")
    }
}

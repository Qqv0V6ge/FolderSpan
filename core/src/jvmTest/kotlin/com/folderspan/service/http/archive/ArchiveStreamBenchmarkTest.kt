package com.folderspan.service.http.archive

import com.folderspan.service.session.DEVICE_SESSION_MAX_PAYLOAD_BYTES
import com.folderspan.service.session.DeviceSessionArchiveTrailer
import com.folderspan.service.session.DeviceSessionByteChannel
import com.folderspan.service.session.DeviceSessionEndpointRole
import com.folderspan.service.session.DeviceSessionIncomingStream
import com.folderspan.service.session.DeviceSessionPeer
import com.folderspan.service.session.DeviceSessionTransport
import com.folderspan.service.session.DeviceSessionWindowPlan
import com.folderspan.service.session.DEVICE_SESSION_STREAM_ARCHIVE_WRITE
import com.folderspan.service.session.DEVICE_SESSION_STREAM_WRITE
import com.folderspan.utils.ProtoBufCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Manual, repeatable acceptance benchmark. It is intentionally opt-in so regular test runs do not
 * create 50,000 destination files. Run with FOLDERSPAN_RUN_ARCHIVE_BENCHMARK=1.
 */
class ArchiveStreamBenchmarkTest {
    @Test
    fun benchmarkTenThousandSmallFilesAcrossTransferAdapters() = runBlocking {
        if (System.getenv("FOLDERSPAN_RUN_ARCHIVE_BENCHMARK") != "1") return@runBlocking
        val fixtureRoot = Files.createTempDirectory("folderspan-archive-benchmark-")
        try {
            val fixture = createFixture(fixtureRoot.resolve("source"), 10_000)
            val payloadBytes = fixture.sumOf(BenchmarkFile::size)
            val metrics = mutableListOf<BenchmarkMetric>()
            runSessionPerFileScenario(
                name = "warmup-session-per-file",
                fixture = fixture.take(128),
                targetRoot = fixtureRoot.resolve("warmup-session-per-file"),
            )
            runSessionArchiveScenario(
                name = "warmup-session-archive",
                fixture = fixture.take(128),
                targetRoot = fixtureRoot.resolve("warmup-session-archive"),
            )
            runArchiveScenario(
                name = "warmup-zstd",
                fixture = fixture.take(128),
                targetRoot = fixtureRoot.resolve("warmup-zstd"),
                options = FolderSpanArchiveStreamOptions(
                    FolderSpanArchiveCompressionCodec.ZSTD,
                ),
                boundedRelay = false,
            )
            metrics += runSessionPerFileScenario(
                name = "session-lan-per-file-stream",
                fixture = fixture,
                targetRoot = fixtureRoot.resolve("session-per-file-target"),
            )
            metrics += runSessionArchiveScenario(
                name = "session-lan-fsar2-none",
                fixture = fixture,
                targetRoot = fixtureRoot.resolve("session-archive-target"),
            )
            metrics += runArchiveScenario(
                name = "webrtc-wan-fsar2-zstd",
                fixture = fixture,
                targetRoot = fixtureRoot.resolve("webrtc-target"),
                options = FolderSpanArchiveStreamOptions(
                    FolderSpanArchiveCompressionCodec.ZSTD,
                ),
                boundedRelay = false,
            )
            metrics += runArchiveScenario(
                name = "device-relay-fsar2-zstd",
                fixture = fixture,
                targetRoot = fixtureRoot.resolve("relay-target"),
                options = FolderSpanArchiveStreamOptions(
                    FolderSpanArchiveCompressionCodec.ZSTD,
                ),
                boundedRelay = true,
            )
            metrics += runArchiveScenario(
                name = "link-share-http-fsar2-zstd",
                fixture = fixture,
                targetRoot = fixtureRoot.resolve("http-target"),
                options = FolderSpanArchiveStreamOptions(
                    FolderSpanArchiveCompressionCodec.ZSTD,
                ),
                boundedRelay = false,
            )

            metrics.forEach { metric ->
                println(
                    listOf(
                        "ARCHIVE_BENCHMARK",
                        metric.name,
                        "files=${metric.files}",
                        "payload_bytes=${metric.payloadBytes}",
                        "wall_ms=${metric.wallMillis}",
                        "cpu_ms=${metric.cpuMillis}",
                        "peak_heap_bytes=${metric.peakHeapBytes}",
                        "network_bytes=${metric.networkBytes}",
                    ).joinToString("|")
                )
            }
        } finally {
            fixtureRoot.toFile().deleteRecursively()
        }
    }

    private suspend fun runSessionPerFileScenario(
        name: String,
        fixture: List<BenchmarkFile>,
        targetRoot: Path,
    ): BenchmarkMetric = measure(name, fixture.size, fixture.sumOf(BenchmarkFile::size)) {
        targetRoot.createDirectories()
        val pair = BenchmarkDeviceSessionPair()
        pair.use { client, server ->
            server.setStreamOpenHandler { streamId, open ->
                check(open.kind == DEVICE_SESSION_STREAM_WRITE)
                val destination = Path.of(open.path)
                destination.parent.createDirectories()
                val output = destination.outputStream()
                object : DeviceSessionIncomingStream {
                    override suspend fun onData(chunk: ByteArray) {
                        output.write(chunk)
                    }

                    override suspend fun onTrailer(payload: ByteArray) {
                        output.close()
                        server.connection.sendTrailer(streamId)
                    }

                    override suspend fun onReset() {
                        output.close()
                    }
                }
            }
            fixture.chunked(8).forEach { group ->
                coroutineScope {
                    group.map { file ->
                        async {
                            client.openWriteStream(
                                path = targetRoot.resolve(file.relativePath).toString(),
                                fileSize = file.size,
                                startOffset = 0L,
                                data = readFileChunks(file.path),
                            )
                        }
                    }.forEach { transfer -> transfer.await() }
                }
            }
        }
        pair.networkBytes()
    }

    private suspend fun runSessionArchiveScenario(
        name: String,
        fixture: List<BenchmarkFile>,
        targetRoot: Path,
    ): BenchmarkMetric = measure(name, fixture.size, fixture.sumOf(BenchmarkFile::size)) {
        targetRoot.createDirectories()
        val selection = selectSmallFileArchiveBatches(
            items = fixture,
            sourcePath = { file -> file.path.toString() },
            relativePath = BenchmarkFile::relativePath,
            size = BenchmarkFile::size,
        )
        check(selection.remainingItems.isEmpty())
        val pair = BenchmarkDeviceSessionPair()
        coroutineScope {
            val extractionScope = this
            pair.use { client, server ->
                server.setStreamOpenHandler { streamId, open ->
                    check(open.kind == DEVICE_SESSION_STREAM_ARCHIVE_WRITE)
                    val request = ProtoBufCodec.decode<FolderSpanArchiveWriteRequest>(open.payload)
                    val input = Channel<ByteArray>(4)
                    val extraction = extractionScope.async {
                        ArchiveStreamDecoder.decode(
                            chunks = flow {
                                for (chunk in input) emit(chunk)
                            },
                            sink = ArchiveEntryExtractor(extractionScope, request.destinationRootPath),
                        )
                    }
                    object : DeviceSessionIncomingStream {
                        override suspend fun onData(chunk: ByteArray) {
                            input.send(chunk)
                        }

                        override suspend fun onTrailer(payload: ByteArray) {
                            input.close()
                            val result = extraction.await()
                            server.connection.sendTrailer(
                                streamId,
                                ProtoBufCodec.encode(DeviceSessionArchiveTrailer(result)),
                            )
                        }

                        override suspend fun onReset() {
                            input.close()
                            extraction.cancel()
                        }
                    }
                }
                var completedEntries = 0
                selection.batches.forEach { batch ->
                    val stream = ArchiveStreamEncoder.encode(
                        entries = batch.requestEntries,
                        options = FolderSpanArchiveStreamOptions(
                            FolderSpanArchiveCompressionCodec.NONE,
                        ),
                        readFileChunks = { request -> readFileChunks(Path.of(request.sourcePath)) },
                    )
                    val result = client.openArchiveWriteStream(
                        request = FolderSpanArchiveWriteRequest(
                            destinationRootPath = targetRoot.toString(),
                            expectedEntries = batch.requestEntries.size,
                            expectedFileBytes = batch.requestEntries.sumOf { entry -> entry.size },
                        ),
                        data = stream,
                    )
                    completedEntries += result.completedRelativePaths.size
                }
                assertEquals(fixture.size, completedEntries)
            }
        }
        pair.networkBytes()
    }

    private suspend fun runArchiveScenario(
        name: String,
        fixture: List<BenchmarkFile>,
        targetRoot: Path,
        options: FolderSpanArchiveStreamOptions,
        boundedRelay: Boolean,
    ): BenchmarkMetric = measure(name, fixture.size, fixture.sumOf(BenchmarkFile::size)) {
        targetRoot.createDirectories()
        val selection = selectSmallFileArchiveBatches(
            items = fixture,
            sourcePath = { file -> file.path.toString() },
            relativePath = BenchmarkFile::relativePath,
            size = BenchmarkFile::size,
        )
        check(selection.remainingItems.isEmpty())
        var networkBytes = 0L
        var completedEntries = 0
        coroutineScope {
            selection.batches.forEach { batch ->
                val encoded = ArchiveStreamEncoder.encode(batch.requestEntries, options) { request ->
                    readFileChunks(Path.of(request.sourcePath))
                }.onEach { chunk -> networkBytes += chunk.size.toLong() }
                val transported = if (boundedRelay) boundedArchiveRelay(encoded) else encoded
                val result = ArchiveStreamDecoder.decode(
                    chunks = transported,
                    sink = ArchiveEntryExtractor(this, targetRoot.toString()),
                )
                completedEntries += result.completedRelativePaths.size
            }
        }
        assertEquals(fixture.size, completedEntries)
        networkBytes
    }

    private suspend fun measure(
        name: String,
        files: Int,
        payloadBytes: Long,
        block: suspend () -> Long,
    ): BenchmarkMetric = coroutineScope {
        System.gc()
        delay(50)
        val running = AtomicBoolean(true)
        val peakHeap = AtomicLong(usedHeapBytes())
        val sampler = launch(Dispatchers.Default) {
            while (running.get()) {
                peakHeap.accumulateAndGet(usedHeapBytes(), ::maxOf)
                delay(2)
            }
        }
        val cpuStart = processCpuNanos()
        val wallStart = System.nanoTime()
        val networkBytes = block()
        val wallNanos = System.nanoTime() - wallStart
        val cpuNanos = processCpuNanos() - cpuStart
        running.set(false)
        sampler.cancelAndJoin()
        peakHeap.accumulateAndGet(usedHeapBytes(), ::maxOf)
        BenchmarkMetric(
            name = name,
            files = files,
            payloadBytes = payloadBytes,
            wallMillis = wallNanos / 1_000_000L,
            cpuMillis = cpuNanos / 1_000_000L,
            peakHeapBytes = peakHeap.get(),
            networkBytes = networkBytes,
        )
    }

    private fun createFixture(root: Path, count: Int): List<BenchmarkFile> {
        root.createDirectories()
        val fixtureContent = "module.exports = folderspan_small_file_fixture;\n"
        return List(count) { index ->
            val relativePath = "pkg-${index / 100}/file-$index.js"
            val path = root.resolve(relativePath)
            path.parent.createDirectories()
            val size = 64 + (index * 31 % 896)
            val bytes = ByteArray(size) { offset ->
                fixtureContent[(index + offset) % fixtureContent.length].code.toByte()
            }
            path.outputStream().use { output -> output.write(bytes) }
            BenchmarkFile(path, relativePath, size.toLong())
        }
    }
}

private fun readFileChunks(path: Path): Flow<ByteArray> = flow {
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(FOLDER_SPAN_ARCHIVE_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) emit(buffer.copyOf(read))
        }
    }
}

private fun boundedArchiveRelay(source: Flow<ByteArray>): Flow<ByteArray> = flow {
    coroutineScope {
        val channel = Channel<ByteArray>(SMALL_FILE_ARCHIVE_RELAY_CAPACITY)
        val producer = launch {
            var failure: Throwable? = null
            try {
                source.collect { chunk -> channel.send(chunk) }
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                channel.close(failure)
            }
        }
        try {
            for (chunk in channel) emit(chunk)
            producer.join()
        } finally {
            producer.cancel()
        }
    }
}

private fun usedHeapBytes(): Long = Runtime.getRuntime().let { runtime ->
    runtime.totalMemory() - runtime.freeMemory()
}

private fun processCpuNanos(): Long {
    val bean = ManagementFactory.getOperatingSystemMXBean()
    return (bean as? com.sun.management.OperatingSystemMXBean)?.processCpuTime ?: 0L
}

private data class BenchmarkFile(
    val path: Path,
    val relativePath: String,
    val size: Long,
)

private data class BenchmarkMetric(
    val name: String,
    val files: Int,
    val payloadBytes: Long,
    val wallMillis: Long,
    val cpuMillis: Long,
    val peakHeapBytes: Long,
    val networkBytes: Long,
)

private class BenchmarkDeviceSessionPair {
    private val clientToServer = Channel<ByteArray>(Channel.UNLIMITED)
    private val serverToClient = Channel<ByteArray>(Channel.UNLIMITED)
    private val bytesOnWire = AtomicLong(0L)
    private val plan = DeviceSessionWindowPlan(
        sessionWindowBytes = 2 * 1024 * 1024,
        streamWindowBytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES,
        maxFileStreams = 8,
    )
    private val clientTransport = DeviceSessionTransport(
        channel = BenchmarkDeviceSessionByteChannel(serverToClient, clientToServer, bytesOnWire),
        sendPlan = plan,
        isClient = true,
    )
    private val serverTransport = DeviceSessionTransport(
        channel = BenchmarkDeviceSessionByteChannel(clientToServer, serverToClient, bytesOnWire),
        sendPlan = plan,
        isClient = false,
    )
    private val client = DeviceSessionPeer(
        transport = clientTransport,
        receivePlan = plan,
        role = DeviceSessionEndpointRole.Client,
    )
    private val server = DeviceSessionPeer(
        transport = serverTransport,
        receivePlan = plan,
        role = DeviceSessionEndpointRole.Server,
    )

    fun networkBytes(): Long = bytesOnWire.get()

    suspend fun <T> use(block: suspend (DeviceSessionPeer, DeviceSessionPeer) -> T): T = coroutineScope {
        client.start(this)
        server.start(this)
        try {
            block(client, server)
        } finally {
            client.close()
            server.close()
        }
    }
}

private class BenchmarkDeviceSessionByteChannel(
    private val incoming: Channel<ByteArray>,
    private val outgoing: Channel<ByteArray>,
    private val bytesOnWire: AtomicLong,
) : DeviceSessionByteChannel {
    private var current = ByteArray(0)
    private var currentOffset = 0

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (currentOffset >= current.size) {
            current = incoming.receiveCatching().getOrNull() ?: return -1
            currentOffset = 0
        }
        val count = minOf(length, current.size - currentOffset)
        current.copyInto(
            destination = buffer,
            destinationOffset = offset,
            startIndex = currentOffset,
            endIndex = currentOffset + count,
        )
        currentOffset += count
        return count
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        bytesOnWire.addAndGet(length.toLong())
        outgoing.send(buffer.copyOfRange(offset, offset + length))
    }

    override suspend fun flush() = Unit

    override fun close() {
        outgoing.close()
    }
}

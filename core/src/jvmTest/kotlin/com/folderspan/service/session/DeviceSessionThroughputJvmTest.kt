package com.folderspan.service.session

import com.folderspan.service.http.archive.FolderSpanArchiveStreamCapabilities
import com.folderspan.service.http.tls.DeviceTlsIdentity
import com.folderspan.service.operation.HttpTransferStatus
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * Uses the real file download path with DeviceSessionThroughputAndroidTest on the phone.
 * Export SESSION_BENCHMARK_HOST and SESSION_BENCHMARK_FINGERPRINT for Wi-Fi,
 * or SESSION_BENCHMARK_LOCAL=1 for loopback TLS and a real file on the build volume.
 * SESSION_BENCHMARK_WINDOWS=1048576,1048576 fixes the stream window at 1 MiB;
 * a zero window uses the server default.
 * SESSION_BENCHMARK_BLOCKS sets the number of 256 KiB blocks per file (default 2048).
 * SESSION_BENCHMARK_IO_CONTEXT=0 restores per-read dispatcher switching for comparison.
 * SESSION_BENCHMARK_NO_DELAY=1 enables TCP_NODELAY; alternate compares it per connection.
 * SESSION_BENCHMARK_PORTS and WINDOWS compare combinations in alternating order.
 * SESSION_BENCHMARK_REPETITIONS defaults to 2; match the Android connections count.
 * Run with the selected environment:
 * ./gradlew --no-daemon :core:jvmTest --rerun --tests '*DeviceSessionThroughputJvmTest'.
 */
class DeviceSessionThroughputJvmTest {
    @Test
    fun downloadGeneratedFileOverWifi() = runBlocking(Dispatchers.Default) {
        val local = System.getenv("SESSION_BENCHMARK_LOCAL") == "1"
        val host = if (local) "127.0.0.1" else System.getenv("SESSION_BENCHMARK_HOST").orEmpty()
        assumeTrue(host.isNotBlank())
        val localServer = if (local) startLocalBenchmarkServer(this) else null
        val fingerprint = if (local) DeviceTlsIdentity.loadOrCreate().fingerprintSha256
        else checkNotNull(System.getenv("SESSION_BENCHMARK_FINGERPRINT"))
        val port = localServer?.localPort ?: (System.getenv("SESSION_BENCHMARK_PORT")?.toInt() ?: 15241)
        val ports = System.getenv("SESSION_BENCHMARK_PORTS")?.split(',')?.map(String::toInt) ?: listOf(port)
        val repetitions = System.getenv("SESSION_BENCHMARK_REPETITIONS")?.toInt() ?: 2
        val file = Files.createTempFile(Path.of("build"), "folderspan-transfer-benchmark-", ".bin")
        val blocks = System.getenv("SESSION_BENCHMARK_BLOCKS")?.toInt() ?: 2048
        val windows = System.getenv("SESSION_BENCHMARK_WINDOWS")?.split(',')?.map(String::toInt)
            ?: listOf(256 * 1024, 0)
        val ranges = ports.flatMap { endpoint -> windows.map { endpoint to it } }
        val total = blocks * 256L * 1024L
        val block = ByteArray(256 * 1024) { it.toByte() }
        val expected = CRC32().apply { repeat(blocks) { update(block) } }.value
        try {
            repeat(repetitions) { repetition ->
                val ordered = if (repetition % 2 == 0) ranges else ranges.reversed()
                for ((index, range) in ordered.withIndex()) {
                    val (endpoint, window) = range
                    val noDelay = when (System.getenv("SESSION_BENCHMARK_NO_DELAY")) {
                        "alternate" -> (repetition + index) % 2 == 1
                        else -> System.getenv("SESSION_BENCHMARK_NO_DELAY") == "1"
                    }
                    coroutineScope {
                        val socket = createPinnedDeviceSessionSocket(host, endpoint, fingerprint)
                        socket.soTimeout = 30_000
                        socket.tcpNoDelay = noDelay
                        DataOutputStream(socket.outputStream).apply {
                            writeInt(2)
                            writeInt(window)
                            writeInt(blocks)
                            flush()
                        }
                        val plan = DeviceSessionWindowPlan.fromMemory()
                        val peer = DeviceSessionPeer(
                            DeviceSessionTransport(benchmarkChannel(socket), plan, isClient = true),
                            plan,
                        )
                        val job = peer.start(this)
                        val client = DeviceSessionClients(
                            peer, HttpTransferStatus.default(), FolderSpanArchiveStreamCapabilities.unsupported(),
                        )
                        var written = 0L
                        val started = System.nanoTime()
                        val cpu = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
                        val cpuStarted = cpu.processCpuTime
                        try {
                            withTimeout(90_000) {
                                assertTrue(client.downloadRangeToFile(
                                    "generated.bin", 0, total, file.toString(), total,
                                ) { _, bytes -> written += bytes }.getOrThrow())
                            }
                            val seconds = (System.nanoTime() - started) / 1e9
                            val cpuSeconds = (cpu.processCpuTime - cpuStarted) / 1e9
                            assertEquals(total, written)
                            assertEquals(total, Files.size(file))
                            val crc = CRC32()
                            Files.newInputStream(file).use { input ->
                                while (true) {
                                    val read = input.read(block)
                                    if (read < 0) break
                                    crc.update(block, 0, read)
                                }
                            }
                            assertEquals(expected, crc.value)
                            println("SESSION_FILE_BENCHMARK repetition=$repetition window=$window bytes=$written seconds=$seconds MBps=${written / seconds / 1e6} crc32=${crc.value} cpuSeconds=$cpuSeconds local=$local port=$endpoint noDelay=$noDelay")
                        } finally {
                            peer.close()
                            job.cancelAndJoin()
                        }
                    }
                }
            }
        } finally {
            localServer?.close()
            Files.deleteIfExists(file)
        }
    }

    private fun benchmarkChannel(socket: Socket): DeviceSessionByteChannel {
        val channel = JvmDeviceSessionByteChannel(socket)
        return if (System.getenv("SESSION_BENCHMARK_IO_CONTEXT") == "0") {
            object : DeviceSessionByteChannel by channel {
                override val ioContext = EmptyCoroutineContext
            }
        } else channel
    }

    private fun startLocalBenchmarkServer(scope: CoroutineScope): SSLServerSocket {
        val server = DeviceTlsIdentity.createSessionServerSslContext().serverSocketFactory
            .createServerSocket(0, 8, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.soTimeout = 30_000
        server.advertiseDeviceSessionAlpn()
        scope.launch(Dispatchers.IO) {
            val block = ByteArray(256 * 1024) { it.toByte() }
            while (!server.isClosed) {
                val socket = try { server.accept() as SSLSocket } catch (error: java.net.SocketException) {
                    if (server.isClosed) break else throw error
                }
                socket.use {
                    socket.soTimeout = 30_000
                    socket.advertiseDeviceSessionAlpn()
                    socket.startHandshake()
                    val input = DataInputStream(socket.inputStream)
                    check(input.readInt() == 2)
                    val window = input.readInt().let { if (it == 0) 1024 * 1024 else it }
                    val blocks = input.readInt()
                    check(window in 256 * 1024..4 * 1024 * 1024 && blocks in 1..8192)
                    coroutineScope {
                        val transport = DeviceSessionTransport(
                            benchmarkChannel(socket), DeviceSessionWindowPlan(4 * 1024 * 1024, window, 4),
                            isClient = false,
                        )
                        val job = transport.start(this)
                        try {
                            withTimeout(90_000) {
                                val open = transport.connection.incomingEvents.receive()
                                check(open.type == DeviceSessionFrameType.Open)
                                repeat(blocks) { transport.connection.sendData(open.streamId, block) }
                                transport.connection.sendTrailer(open.streamId)
                                transport.connection.incomingEvents.receiveCatching()
                            }
                        } finally {
                            transport.close()
                            job.cancelAndJoin()
                        }
                    }
                }
            }
        }
        return server
    }
}

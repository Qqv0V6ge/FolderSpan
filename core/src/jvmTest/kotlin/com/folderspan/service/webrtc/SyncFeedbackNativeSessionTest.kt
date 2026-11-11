package com.folderspan.service.webrtc

import com.folderspan.service.http.tls.DeviceTlsIdentity
import com.folderspan.service.operation.HttpTransferStatus
import com.folderspan.service.session.DEVICE_SESSION_MAX_PAYLOAD_BYTES
import com.folderspan.service.session.DEVICE_SESSION_RPC_OK
import com.folderspan.service.session.DeviceSessionControlResponse
import com.folderspan.service.session.DeviceSessionEndpointRole
import com.folderspan.service.session.DeviceSessionPeer
import com.folderspan.service.session.DeviceSessionTransport
import com.folderspan.service.session.DeviceSessionWindowPlan
import com.folderspan.service.session.JvmDeviceSessionByteChannel
import com.folderspan.service.session.advertiseDeviceSessionAlpn
import com.folderspan.service.session.createPinnedDeviceSessionSocket
import com.folderspan.service.session.DeviceSessionIncomingStream
import com.folderspan.service.session.DeviceSessionTransferFeedback
import com.folderspan.test.runSuspendTest
import java.util.concurrent.atomic.AtomicLong
import java.nio.file.Path
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

class SyncFeedbackNativeSessionTest {
    @Test fun busyFeedbackAndRecoveryShareRealDataChannelWithFilesAndControl() = runSuspendTest {
        withRealWebRtcSessionPair { client, server, _ -> verifyFeedback("real-SCTP-DTLS", client, server) }
    }

    @Test fun busyFeedbackAndRecoverySharePinnedTlsWithFilesAndControl() = runBlocking(Dispatchers.IO) {
        val testData = Path.of("build/test-user-data").toAbsolutePath().normalize()
        assertTrue(Path.of(System.getProperty("user.home")).normalize().startsWith(testData))
        assertTrue(Path.of(System.getProperty("java.util.prefs.userRoot")).normalize().startsWith(testData))
        coroutineScope {
            val context = DeviceTlsIdentity.createSessionServerSslContext()
            (context.serverSocketFactory.createServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()) as SSLServerSocket).use { listener ->
                listener.soTimeout = 10_000
                listener.advertiseDeviceSessionAlpn()
                val accepted = async(Dispatchers.IO) {
                    (listener.accept() as SSLSocket).apply {
                        soTimeout = 10_000
                        advertiseDeviceSessionAlpn()
                        startHandshake()
                    }
                }
                createPinnedDeviceSessionSocket("127.0.0.1", listener.localPort,
                    DeviceTlsIdentity.loadOrCreate().fingerprintSha256).use { outgoing ->
                    accepted.await().use { incoming ->
                        val plan = DeviceSessionWindowPlan(2 * 1024 * 1024, DEVICE_SESSION_MAX_PAYLOAD_BYTES, 4)
                        val client = DeviceSessionPeer(DeviceSessionTransport(JvmDeviceSessionByteChannel(outgoing), plan, plan,
                            isClient = true), plan, DeviceSessionEndpointRole.Client)
                        val server = DeviceSessionPeer(DeviceSessionTransport(JvmDeviceSessionByteChannel(incoming), plan, plan,
                            isClient = false), plan, DeviceSessionEndpointRole.Server)
                        val jobs = listOf(client.start(this), server.start(this))
                        try { withTimeout(15_000) { verifyFeedback("real-pinned-TLS", client, server) } }
                        finally { withContext(NonCancellable) {
                            client.close()
                            server.close()
                            jobs.forEach { it.cancelAndJoin() }
                        } }
                    }
                }
            }
        }
    }

    private suspend fun verifyFeedback(transport: String, client: DeviceSessionPeer, server: DeviceSessionPeer) {
        val scope = CoroutineScope(currentCoroutineContext())
        val limits = HttpTransferStatus(maxParallelRequests = 2, recommendedParallelRequests = 2,
            maxChunkBytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES, recommendedChunkBytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES)
        val local = DeviceSessionTransferFeedback(scope, limits, client.transferActivity(), client::rpc)
        val remote = DeviceSessionTransferFeedback(scope, limits, server.transferActivity(), server::rpc)
        client.setRequestHandler { request -> local.handle(request)
            ?: DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, request.payload) }
        server.setRequestHandler { request -> remote.handle(request)
            ?: DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, request.payload) }
        val received = AtomicLong()
        server.setStreamOpenHandler { id, _ -> object : DeviceSessionIncomingStream {
            override suspend fun onData(chunk: ByteArray) {
                assertTrue(chunk.all { it == 7.toByte() })
                received.addAndGet(chunk.size.toLong())
            }
            override suspend fun onTrailer(payload: ByteArray) { server.connection.sendTrailer(id) }
        } }
        val release = CompletableDeferred<Unit>()
        val runtime = Runtime.getRuntime()
        val baseline = runtime.totalMemory() - runtime.freeMemory()
        var peak = baseline
        val sampler = scope.launch { while (true) {
            peak = maxOf(peak, runtime.totalMemory() - runtime.freeMemory())
            delay(1)
        } }
        try {
            assertTrue(local.subscribe())
            coroutineScope {
                val copies = (0..1).map { index -> async {
                    client.openWriteStream(path = "/acceptance-$index.bin", fileSize = 4L * DEVICE_SESSION_MAX_PAYLOAD_BYTES,
                        startOffset = 0, data = flow {
                            emit(ByteArray(DEVICE_SESSION_MAX_PAYLOAD_BYTES) { 7 })
                            release.await()
                            repeat(3) { emit(ByteArray(DEVICE_SESSION_MAX_PAYLOAD_BYTES) { 7 }) }
                        })
                } }
                withTimeout(5000) { while (!local.current().busy) delay(10) }
                assertTrue(local.current().activeRequests >= 2)
                repeat(20) {
                    val payload = "live-control-$it".encodeToByteArray()
                    assertContentEquals(payload, client.rpc("Echo", payload).payload)
                }
                val started = System.nanoTime()
                release.complete(Unit)
                copies.forEach { it.await() }
                val elapsed = (System.nanoTime() - started) / 1_000_000.0
                withTimeout(5000) { while (local.current().busy || local.current().activeRequests != 0) delay(10) }
                assertEquals(8L * DEVICE_SESSION_MAX_PAYLOAD_BYTES, received.get())
                println("SYNC_NATIVE_FEEDBACK transport=$transport streams=2 rpc=20 bytes=${received.get()} remainingTransferMs=$elapsed baselineHeap=$baseline sampledPeakHeap=$peak busyRecovered=true")
            }
        } finally {
            release.complete(Unit)
            sampler.cancelAndJoin()
            local.close()
            remote.close()
        }
        assertEquals(0L, local.current().sampledAtMillis)
    }
}

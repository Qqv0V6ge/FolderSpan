package com.folderspan.service.webui

import com.folderspan.service.http.server.linkshare.*
import com.folderspan.service.http.tls.DeviceTlsIdentity
import com.folderspan.service.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

internal class PlatformDeviceChannel(val channel: DeviceSessionByteChannel) {
    var transport: PlatformDeviceTransport? = null
    val readMutex = Mutex()
    val writeMutex = Mutex()
    val closed = CompletableDeferred<Unit>()
    fun close() { try { channel.close() } finally { closed.complete(Unit) } }
    suspend fun shutdown() { close(); transport?.close() }
}

internal suspend fun ownDeviceChannel(session: WebUiPlatformSession, channel: DeviceSessionByteChannel): Pair<String, PlatformDeviceChannel> {
    val owned = PlatformDeviceChannel(channel)
    return session.own(owned) { owned.shutdown() } to owned
}

internal suspend fun devicePlatformRequest(request: LinkShareHttpRequest, session: WebUiPlatformSession): LinkShareHttpResponse {
    val operation = request.path.removePrefix("$WEB_UI_PLATFORM_PATH/devices/")
    if (operation == "transport" || operation == "transfers") return deviceStreamPlatformRequest(request, session)
    return when (request.method to operation) {
        "POST" to "connect" -> {
            val input = request.platformBody<WebUiDeviceConnect>()
            require(input.host.isNotBlank() && input.port in 1..65535)
            val connection = if (input.fingerprint == null) connectUnpinnedDeviceSessionChannel(input.host, input.port)
            else DeviceSessionBootstrapConnection(connectPinnedDeviceSessionChannel(input.host, input.port, input.fingerprint), input.fingerprint)
            platformResponse(WebUiDeviceConnected(ownDeviceChannel(session, connection.channel).first, connection.peerFingerprintSha256))
        }
        "GET" to "channel" -> {
            val channel = session.resource(requireNotNull(request.query("handle"))) as? PlatformDeviceChannel ?: error("not a device channel")
            check(channel.transport == null) { "device channel uses framed transport" }
            val size = requireNotNull(request.query("length")?.toIntOrNull())
            require(size in 1..WEB_UI_FILE_STREAM_CHUNK_BYTES)
            channel.readMutex.withLock {
                val bytes = ByteArray(size)
                val read = channel.channel.read(bytes, 0, size)
                LinkShareHttpResponse.bytes(bytes = bytes.copyOf(read.coerceAtLeast(0)), headers = listOf(LinkShareHttpHeader("X-FolderSpan-End", (read < 0).toString())))
            }
        }
        "PUT" to "channel" -> {
            val channel = session.resource(requireNotNull(request.query("handle"))) as? PlatformDeviceChannel ?: error("not a device channel")
            check(channel.transport == null) { "device channel uses framed transport" }
            val bytes = request.boundedBytes()
            channel.writeMutex.withLock { channel.channel.writeAndFlush(bytes, 0, bytes.size) }
            platformResponse(true)
        }
        "POST" to "udp/broadcast" -> {
            val port = requireNotNull(request.query("port")?.toIntOrNull()); require(port in 1..65535)
            DeviceLanBeaconUdp.broadcast(request.boundedBytes(2048), port)
            platformResponse(true)
        }
        "POST" to "udp/listen" -> {
            val input = request.platformBody<WebUiUdpListen>(); require(input.port in 1..65535)
            val job = session.scope.launch(start = CoroutineStart.LAZY) {
                try {
                    DeviceLanBeaconUdp.listen(input.port) { host, bytes -> session.emit(input.callbackId, "udp-packet", platformJson.encodeToString(WebUiUdpPacket(host, bytes))) }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (isActive) runCatching { session.emit(input.callbackId, "udp-error", "UDP listener failed") }
                }
            }
            val id = session.own(job) { job.cancelAndJoin() }
            job.start()
            platformResponse(WebUiHandle(id))
        }
        "POST" to "sign" -> platformResponse(DeviceTlsIdentity.signSha256WithRsa(request.boundedBytes()))
        "POST" to "verify" -> {
            val input = request.platformBody<WebUiVerifySignature>()
            platformResponse(DeviceTlsIdentity.verifySha256WithRsa(input.publicKey, input.payload, input.signature))
        }
        else -> LinkShareHttpResponse.text(404, "unknown device platform operation")
    }
}

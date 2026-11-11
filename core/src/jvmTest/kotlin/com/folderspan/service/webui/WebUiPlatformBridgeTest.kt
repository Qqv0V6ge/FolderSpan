package com.folderspan.service.webui

import com.folderspan.data.main.network.Network
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.nio.file.Files
import java.security.KeyPairGenerator
import com.folderspan.service.http.archive.FolderSpanArchiveCompressionCodec
import com.folderspan.service.http.server.linkshare.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlin.test.*

class WebUiPlatformBridgeTest {
    @Test fun emptyDirectoryDeletionRetainsChildrenAndRejectsLinks(): Unit = runBlocking {
        val root = Files.createTempDirectory("platform-empty")
        val session = WebUiPlatformSession("empty-test")
        try {
            val empty = Files.createDirectory(root.resolve("empty"))
            assertTrue(post<WebUiPathRequest, Boolean>(session, "/files/delete-empty-directory", WebUiPathRequest(empty.toString())))
            Files.createDirectory(empty)
            val child = Files.writeString(empty.resolve("new"), "keep")
            assertFails { post<WebUiPathRequest, Boolean>(session, "/files/delete-empty-directory", WebUiPathRequest(empty.toString())) }
            assertFails { post<WebUiPathRequest, Boolean>(session, "/files/delete-empty-directory", WebUiPathRequest(child.toString())) }
            val link = Files.createSymbolicLink(root.resolve("link"), empty)
            assertFails { post<WebUiPathRequest, Boolean>(session, "/files/delete-empty-directory", WebUiPathRequest(link.toString())) }
            assertEquals("keep", Files.readString(child))
        } finally { session.close(); root.toFile().deleteRecursively() }
    }

    private val platform = JvmWebUiPlatform()
    private val json = WebUiPlatformClient.json

    private suspend fun request(session: WebUiPlatformSession, method: String, path: String, bytes: ByteArray = byteArrayOf()) =
        platform.handle(LinkShareHttpRequest.from(method, WEB_UI_PLATFORM_PATH + path, body = LinkShareHttpRequestBody.Bytes(bytes)), session)
    private fun LinkShareHttpResponse.bytes() = (body as LinkShareHttpResponseBody.Bytes).bytes
    private inline fun <reified T> LinkShareHttpResponse.value(): T = json.decodeFromString(bytes().decodeToString())
    private suspend inline fun <reified T, reified R> post(session: WebUiPlatformSession, path: String, value: T): R =
        request(session, "POST", path, json.encodeToString(value).encodeToByteArray()).value()

    @Test fun moveUsesValidatedPathsAndRefusesExistingTargets(): Unit = runBlocking {
        val root = Files.createTempDirectory("platform-move")
        val session = WebUiPlatformSession("move-test")
        try {
            val source = Files.writeString(root.resolve("source"), "payload")
            val folder = Files.createDirectory(root.resolve("folder"))
            val target = folder.resolve("target")
            assertTrue(post<WebUiMoveRequest, Boolean>(session, "/files/move", WebUiMoveRequest(source.toString(), target.toString())))
            val occupied = Files.writeString(root.resolve("occupied"), "keep")
            assertFails { post<WebUiMoveRequest, Boolean>(session, "/files/move", WebUiMoveRequest(target.toString(), occupied.toString())) }
            val link = Files.createSymbolicLink(root.resolve("link"), folder)
            assertFails { post<WebUiMoveRequest, Boolean>(session, "/files/move", WebUiMoveRequest(link.resolve("target").toString(), source.toString())) }
            assertFails { post<WebUiMoveRequest, Boolean>(session, "/files/move", WebUiMoveRequest(target.toString(), link.resolve("new").toString())) }
            assertEquals("payload", Files.readString(target))
            assertEquals("keep", Files.readString(occupied))
            assertEquals(404, request(session, "POST", "/files/unknown-operation").statusCode)
        } finally { session.close(); root.toFile().deleteRecursively() }
    }

    @Test
    fun modificationTimeUsesValidatedHostPathsAndPreservesContent(): Unit = runBlocking {
        val directory = Files.createTempDirectory("platform-modified-time")
        val session = WebUiPlatformSession("modified-time-test")
        try {
            val file = Files.writeString(directory.resolve("payload.txt"), "keep content")
            val timestamp = 1_700_000_000_000L
            assertTrue(post<WebUiSetModifiedTimeRequest, Boolean>(session, "/files/set-modified-time",
                WebUiSetModifiedTimeRequest(file.toString(), timestamp)))
            assertEquals(timestamp, Files.getLastModifiedTime(file).toMillis())
            assertEquals("keep content", Files.readString(file))
            val link = Files.createSymbolicLink(directory.resolve("link"), file)
            assertFails { post<WebUiSetModifiedTimeRequest, Boolean>(session, "/files/set-modified-time",
                WebUiSetModifiedTimeRequest(link.toString(), timestamp + 1000)) }
            assertFails { post<WebUiSetModifiedTimeRequest, Boolean>(session, "/files/set-modified-time",
                WebUiSetModifiedTimeRequest(file.toString(), -1)) }
            assertEquals(404, request(session, "POST", "/files/unknown-operation").statusCode)
            assertEquals(com.folderspan.createSettings().getString(com.folderspan.utils.SettingsUtils.KEY_DEVICE_ID, ""),
                platform.hostInfo(session).deviceId)
        } finally {
            session.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun sftpHostTrustAndBinaryTransfersUseSessionCallbacks(): Unit = runBlocking {
        withTimeout(30_000) {
            val directory = Files.createTempDirectory("platform-sftp")
            val session = WebUiPlatformSession("sftp-test")
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val server = SshServer.setUpDefaultServer().apply {
                host = "127.0.0.1"
                port = 0
                keyPairProvider = KeyPairProvider.wrap(key)
                passwordAuthenticator = PasswordAuthenticator { _, _, _ -> true }
                subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
                fileSystemFactory = VirtualFileSystemFactory(directory)
            }
            var confirmations = 0
            val browser = launch {
                while (isActive) session.poll().forEach { event ->
                    assertEquals("sftp-host-key", event.type)
                    assertEquals("browser-trust", event.handle)
                    confirmations++
                    session.reply(event.id, "true".encodeToByteArray())
                }
            }
            try {
                server.start()
                val config = Network("Test", "/", "SFTP", "127.0.0.1:${server.port}", "test", "password").connectionConfig()
                val handle = post<WebUiNetworkOpen, WebUiHandle>(session, "/networks", WebUiNetworkOpen("browser-trust", config)).id
                val content = ByteArray(WEB_UI_FILE_STREAM_CHUNK_BYTES + 37) { (it % 239).toByte() }
                val upload = post<WebUiNetworkPath, WebUiHandle>(session, "/networks/upload", WebUiNetworkPath(handle, "/payload.bin", size = content.size.toLong())).id
                var offset = 0
                while (offset < content.size) {
                    val end = minOf(content.size, offset + 65536)
                    request(session, "PUT", "/networks/uploads?handle=$upload", content.copyOfRange(offset, end))
                    offset = end
                }
                assertTrue(request(session, "POST", "/networks/uploads?handle=$upload").value())
                session.release(upload)
                assertContentEquals(content, Files.readAllBytes(directory.resolve("payload.bin")))
                val listing = post<WebUiNetworkPath, WebUiHandle>(session, "/networks/directories", WebUiNetworkPath(handle, "/")).id
                assertEquals("payload.bin", request(session, "GET", "/networks/directories?handle=$listing").value<WebUiNetworkBatch>().entries.single().name)
                val download = post<WebUiNetworkPath, WebUiHandle>(session, "/networks/download", WebUiNetworkPath(handle, "/payload.bin", size = content.size.toLong())).id
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val part = request(session, "GET", "/networks/downloads?handle=$download")
                    if (part.header("X-FolderSpan-End") == "true") break
                    bytes.write(part.bytes())
                }
                assertContentEquals(content, bytes.toByteArray())
                session.release(download)
                val pending = post<WebUiNetworkPath, WebUiHandle>(session, "/networks/upload", WebUiNetworkPath(handle, "/partial.bin", size = 100)).id
                request(session, "PUT", "/networks/uploads?handle=$pending", byteArrayOf(1))
                withTimeout(5_000) { session.release(pending) }
                assertFailsWith<IllegalArgumentException> { session.resource(pending) }
                assertTrue(confirmations > 0)
            } finally {
                browser.cancelAndJoin()
                session.close()
                server.stop(true)
                directory.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun outboundHttpStreamsBinaryAndCancellationStopsPendingRequest(): Unit = runBlocking {
        withTimeout(20_000) {
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            val content = ByteArray(WEB_UI_FILE_STREAM_CHUNK_BYTES + 71) { (it % 229).toByte() }
            server.createContext("/echo") { exchange ->
                exchange.use {
                    val bytes = it.requestBody.readBytes()
                    it.responseHeaders.add("Content-Type", "application/octet-stream")
                    it.sendResponseHeaders(202, bytes.size.toLong())
                    it.responseBody.use { output -> output.write(bytes) }
                }
            }
            server.start()
            val session = WebUiPlatformSession("outbound-test")
            try {
                val client = post<WebUiHttpClientOpen, WebUiHandle>(session, "/http/clients", WebUiHttpClientOpen()).id
                val transfer = post<WebUiHttpOutgoingRequest, WebUiHandle>(session, "/http/requests",
                    WebUiHttpOutgoingRequest(client, "http://127.0.0.1:${server.address.port}/echo", "POST", emptyList(), content.size.toLong())).id
                val upload = launch {
                    var offset = 0
                    while (offset < content.size) {
                        val end = minOf(offset + 65536, content.size)
                        request(session, "PUT", "/http/upload?handle=$transfer", content.copyOfRange(offset, end))
                        offset = end
                    }
                    request(session, "POST", "/http/upload?handle=$transfer")
                }
                assertEquals(202, request(session, "GET", "/http/response?handle=$transfer").value<WebUiHttpIncomingResponse>().status)
                val downloaded = ByteArrayOutputStream()
                while (true) {
                    val part = request(session, "GET", "/http/download?handle=$transfer")
                    if (part.header("X-FolderSpan-End") == "true") break
                    downloaded.write(part.bytes())
                }
                upload.join()
                assertContentEquals(content, downloaded.toByteArray())
                session.release(transfer)
                val pending = post<WebUiHttpOutgoingRequest, WebUiHandle>(session, "/http/requests",
                    WebUiHttpOutgoingRequest(client, "http://127.0.0.1:${server.address.port}/echo", "POST", emptyList(), 100L)).id
                assertFailsWith<IllegalArgumentException> { request(session, "POST", "/http/upload?handle=$pending") }
                withTimeout(5_000) { session.release(pending) }
                assertFailsWith<IllegalArgumentException> { session.resource(pending) }
            } finally { session.close(); server.stop(0) }
        }
    }

    @Test
    fun shareRequestBodyAndResponseTravelThroughBrowserCallbackAndStopReleasesPort(): Unit = runBlocking {
        withTimeout(20_000) {
            val session = WebUiPlatformSession("sharing-test")
            val port = ServerSocket(0).use { it.localPort }
            val content = ByteArray(WEB_UI_FILE_STREAM_CHUNK_BYTES + 37) { (it % 231).toByte() }
            try {
                post<WebUiListenerOpen, WebUiListenerStarted>(session, "/listeners", WebUiListenerOpen("browser", "link-share", port))
                val caller = async(Dispatchers.IO) {
                    val connection = URI("http://127.0.0.1:$port/probe").toURL().openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 5_000; connection.readTimeout = 10_000
                        connection.requestMethod = "POST"; connection.doOutput = true
                        connection.setFixedLengthStreamingMode(content.size)
                        connection.outputStream.use { it.write(content) }
                        connection.responseCode to connection.inputStream.use { it.readBytes() }
                    } finally { connection.disconnect() }
                }
                val event = session.poll().single()
                assertEquals("http-request", event.type)
                val path = "/listeners/body?handle=${event.data}"
                val metadata = request(session, "GET", "/listeners/requests?handle=${event.data}").value<WebUiIncomingHttp>()
                assertEquals("/probe", metadata.uri)
                assertEquals(content.size.toLong(), metadata.contentLength)
                val uploaded = ByteArrayOutputStream()
                while (true) {
                    val part = request(session, "GET", "$path&length=65536")
                    if (part.header("X-FolderSpan-End") == "true") break
                    uploaded.write(part.bytes())
                }
                assertContentEquals(content, uploaded.toByteArray())
                session.reply(event.id, json.encodeToString(WebUiOutgoingHttp(201, listOf("Content-Type" to "application/octet-stream"), content.size.toLong())).encodeToByteArray())
                request(session, "PUT", path, content.copyOfRange(0, WEB_UI_FILE_STREAM_CHUNK_BYTES))
                request(session, "PUT", path, content.copyOfRange(WEB_UI_FILE_STREAM_CHUNK_BYTES, content.size))
                request(session, "POST", path)
                val result = caller.await()
                assertEquals(201, result.first)
                assertContentEquals(content, result.second)
            } finally { session.close() }
            ServerSocket(port).close()
        }
    }

    @Test
    fun deviceConnectAcceptReadWriteAndSignatureUseOnlySessionResources(): Unit = runBlocking {
        withTimeout(20_000) {
            val session = WebUiPlatformSession("device-test")
            val other = WebUiPlatformSession("other")
            val port = ServerSocket(0).use { it.localPort }
            try {
                val started = post<WebUiListenerOpen, WebUiListenerStarted>(session, "/listeners", WebUiListenerOpen("browser", "device", port))
                val connected = post<WebUiDeviceConnect, WebUiDeviceConnected>(session, "/devices/connect", WebUiDeviceConnect("127.0.0.1", port, started.fingerprint))
                val acceptedEvent = session.poll().single()
                assertEquals("device-accepted", acceptedEvent.type)
                val accepted = json.decodeFromString<WebUiAcceptedDevice>(acceptedEvent.data)
                val content = "device frame payload".encodeToByteArray()
                request(session, "PUT", "/devices/channel?handle=${connected.handle}", content)
                assertContentEquals(content, request(session, "GET", "/devices/channel?handle=${accepted.handle}&length=${content.size}").bytes())
                request(session, "PUT", "/devices/channel?handle=${accepted.handle}", content.reversedArray())
                assertContentEquals(content.reversedArray(), request(session, "GET", "/devices/channel?handle=${connected.handle}&length=${content.size}").bytes())
                assertFailsWith<IllegalArgumentException> { request(other, "GET", "/devices/channel?handle=${connected.handle}&length=1") }
                val host = platform.hostInfo(session)
                val signature = request(session, "POST", "/devices/sign", content).value<String>()
                assertTrue(post<WebUiVerifySignature, Boolean>(session, "/devices/verify", WebUiVerifySignature(host.publicKey, content, signature)))
                assertFalse(post<WebUiVerifySignature, Boolean>(session, "/devices/verify", WebUiVerifySignature(host.publicKey, content + 1, signature)))
            } finally { other.close(); session.close() }
            ServerSocket(port).close()
        }
    }

    @Test
    fun compressionStreamsApplyBackpressureAndRoundTrip(): Unit = runBlocking {
        withTimeout(20_000) {
            val session = WebUiPlatformSession("compression-test")
            val content = ByteArray(WEB_UI_FILE_STREAM_CHUNK_BYTES + 51) { ((it * 17) % 251).toByte() }
            suspend fun transform(bytes: ByteArray, decompress: Boolean): ByteArray = coroutineScope {
                val handle = post<WebUiCompressionOpen, WebUiHandle>(session, "/compression", WebUiCompressionOpen(FolderSpanArchiveCompressionCodec.ZSTD, decompress)).id
                try {
                    val upload = launch {
                        var offset = 0
                        while (offset < bytes.size) {
                            val end = minOf(offset + 65536, bytes.size)
                            request(session, "PUT", "/compression?handle=$handle", bytes.copyOfRange(offset, end))
                            offset = end
                        }
                        request(session, "POST", "/compression/finish?handle=$handle")
                    }
                    val result = ByteArrayOutputStream()
                    while (true) {
                        val part = request(session, "GET", "/compression?handle=$handle")
                        if (part.header("X-FolderSpan-End") == "true") break
                        result.write(part.bytes())
                    }
                    upload.join()
                    result.toByteArray()
                } finally { session.release(handle) }
            }
            try {
                val compressed = transform(content, false)
                assertTrue(compressed.size < content.size)
                assertContentEquals(content, transform(compressed, true))
            } finally { session.close() }
        }
    }
}

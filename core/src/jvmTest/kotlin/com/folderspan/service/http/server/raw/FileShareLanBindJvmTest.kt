package com.folderspan.service.http.server.raw

import com.folderspan.service.http.server.SocketClientIPEnum
import com.folderspan.service.http.server.getAllIPAddresses
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertTrue

class FileShareLanBindJvmTest {
    @Test
    fun fileShareProxyAcceptsLanTcpNotOnlyLoopback() = runBlocking {
        val lanHost = getAllIPAddresses(SocketClientIPEnum.ALL)
            .firstOrNull { address ->
                ':' !in address && address != "0.0.0.0" && !address.startsWith("127.")
            }
            ?: return@runBlocking
        val port = ServerSocket(0).use { socket -> socket.localPort }
        val server = RawTlsHttpServer()
        try {
            server.start(port)
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                assertTrue(socket.isConnected)
            }
            Socket().use { socket ->
                socket.connect(InetSocketAddress(lanHost, port), 2_000)
                assertTrue(socket.isConnected, "file share must listen on LAN, host=$lanHost port=$port")
            }
        } catch (error: ConnectException) {
            throw AssertionError("file share LAN bind failed: $lanHost:$port", error)
        } finally {
            server.stop()
        }
    }
}

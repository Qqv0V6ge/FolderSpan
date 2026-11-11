package com.folderspan.service.network.nfs

import com.folderspan.data.main.network.NfsDriveExtras
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import kotlin.random.Random

internal class NfsRpcConnection private constructor(
    private val selector: SelectorManager,
    private val socket: Socket,
    private val auth: NfsDriveExtras,
) {
    private val input = socket.openReadChannel()
    private val output = socket.openWriteChannel(autoFlush = true)
    private val mutex = Mutex()

    suspend fun call(program: Int, version: Int, procedure: Int, arguments: XdrWriter.() -> Unit): XdrReader =
        mutex.withLock {
            withTimeout(30_000L) {
                val xid = Random.nextInt()
                val credentials = XdrWriter().apply {
                    int(0); string("FolderSpan"); int(auth.uid); int(auth.gid)
                    int(auth.groups.size); auth.groups.forEach(::int)
                }.bytes()
                val call = XdrWriter().apply {
                    int(xid); int(0); int(2); int(program); int(version); int(procedure)
                    int(1); opaque(credentials)
                    int(0); int(0)
                    arguments()
                }.bytes()
                require(call.size <= MAX_RECORD)
                output.writeInt(call.size or Int.MIN_VALUE)
                output.writeFully(call)
                output.flush()
                val record = readRpcRecord { count ->
                    ByteArray(count).also { input.readFully(it) }
                }
                val reply = XdrReader(record)
                check(reply.int() == xid && reply.int() == 1) { "Unexpected RPC response" }
                check(reply.int() == 0) { "RPC authentication or protocol rejected" }
                reply.int()
                reply.opaque(1024)
                val status = reply.int()
                check(status == 0) { "RPC procedure unavailable (status $status)" }
                reply
            }
        }

    fun close() {
        socket.close()
        selector.close()
    }

    companion object {
        private const val MAX_RECORD = 8 * 1024 * 1024
        suspend fun connect(host: String, port: Int, auth: NfsDriveExtras): NfsRpcConnection {
            require(port in 1..65535)
            val selector = SelectorManager(Dispatchers.IO)
            return try {
                val socket = withTimeout(10_000L) { aSocket(selector).tcp().connect(host, port) }
                NfsRpcConnection(selector, socket, auth)
            } catch (error: Throwable) {
                selector.close()
                throw error
            }
        }
    }
}

internal suspend fun readRpcRecord(read: suspend (Int) -> ByteArray): ByteArray {
    val result = Buffer()
    repeat(128) {
        val headerBytes = read(4)
        require(headerBytes.size == 4) { "Truncated RPC fragment header" }
        val marker = XdrReader(headerBytes).int()
        val length = marker and Int.MAX_VALUE
        require(length <= 8 * 1024 * 1024 - result.size) { "RPC record exceeds its limit" }
        val fragment = read(length)
        require(fragment.size == length) { "Truncated RPC fragment" }
        result.write(fragment)
        if (marker < 0) return result.readByteArray()
    }
    error("Too many RPC fragments")
}

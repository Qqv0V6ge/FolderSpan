package com.folderspan.service.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.FilterInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertTrue

class NetworkStreamCancellationTest {
    @Test
    fun cancellationClosesBlockedSocketRead(): Unit = runBlocking {
        ServerSocket(0).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { peer ->
                listener.accept().use { socket ->
                    val started = CompletableDeferred<Unit>()
                    val stream = object : FilterInputStream(socket.getInputStream()) {
                        override fun read(bytes: ByteArray): Int {
                            started.complete(Unit)
                            return super.read(bytes)
                        }
                    }
                    val reader = launch(Dispatchers.IO) {
                        runCatching { relayStreamByChunks(stream, -1) { _, _ -> Result.success(Unit) } }
                    }
                    withTimeout(5_000) {
                        started.await()
                        reader.cancelAndJoin()
                    }
                    assertTrue(socket.isClosed)
                }
            }
        }
    }
}

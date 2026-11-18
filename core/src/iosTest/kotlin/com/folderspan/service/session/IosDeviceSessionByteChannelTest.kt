package com.folderspan.service.session

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.openssl.SSL_CTX_free
import platform.openssl.SSL_new
import platform.openssl.SSL_set_connect_state
import platform.openssl.fm_create_client_ctx
import platform.openssl.fm_ssl_set_socket
import platform.posix.AF_UNIX
import platform.posix.POLLIN
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.socketpair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosDeviceSessionByteChannelTest {
    @Test
    fun closeUnblocksNativeReadAndReleasesTheSessionOnce() = runBlocking {
        memScoped {
            val sockets = allocArray<IntVar>(2)
            assertEquals(0, socketpair(AF_UNIX, SOCK_STREAM, 0, sockets))
            try {
                val context = checkNotNull(fm_create_client_ctx())
                val ssl = SSL_new(context) ?: run {
                    SSL_CTX_free(context)
                    error("SSL_new failed")
                }
                val channel = IosDeviceSessionByteChannel(ssl, context, sockets[0])
                try {
                    assertEquals(1, fm_ssl_set_socket(ssl, sockets[0]))
                    SSL_set_connect_state(ssl)
                    val reader = async(Dispatchers.Default) { channel.read(ByteArray(1), 0, 1) }

                    // A ClientHello on the other socket proves SSL_read entered the handshake.
                    val ready = alloc<pollfd>()
                    ready.fd = sockets[1]
                    ready.events = POLLIN.convert()
                    assertEquals(1, poll(ready.ptr, 1.convert(), 5_000))
                    channel.close()
                    assertTrue(withTimeout(5_000) { reader.await() } <= 0)
                    assertEquals(-1, channel.read(ByteArray(1), 0, 1))
                } finally {
                    channel.close()
                    sockets[0] = -1
                }
            } finally {
                if (sockets[0] >= 0) close(sockets[0])
                close(sockets[1])
            }
        }
    }
}

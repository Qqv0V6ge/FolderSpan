package com.folderspan.service.http.server.raw

import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertTrue

class RawTlsHttpServerClientDisconnectTest {
    @Test
    fun unsupportedSslMessageHandshakeErrorIsExpectedClientDisconnect() {
        assertExpectedClientDisconnect(SSLException("Unsupported or unrecognized SSL message"))
    }

    @Test
    fun plaintextConnectionHandshakeErrorIsExpectedClientDisconnect() {
        assertExpectedClientDisconnect(SSLException("Unrecognized SSL message, plaintext connection?"))
    }

    private fun assertExpectedClientDisconnect(error: Throwable) {
        val server = RawTlsHttpServer()
        val method = RawTlsHttpServer::class.java
            .getDeclaredMethod("isExpectedClientDisconnect", Throwable::class.java)
            .apply { isAccessible = true }

        val result = method.invoke(server, error) as Boolean

        assertTrue(result)
    }
}

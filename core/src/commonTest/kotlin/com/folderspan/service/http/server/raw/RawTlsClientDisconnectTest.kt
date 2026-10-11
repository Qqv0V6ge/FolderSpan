package com.folderspan.service.http.server.raw

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RawTlsClientDisconnectTest {
    @Test
    fun recognizesPlainHttpTlsHandshakeFailures() {
        val messages = listOf(
            "Unsupported or unrecognized SSL message",
            "Unrecognized SSL message, plaintext connection?",
            "SSL routines::http request",
            "WRONG_VERSION_NUMBER",
            "plain HTTP request was sent to HTTPS port",
        )

        messages.forEach { message ->
            assertTrue(
                isExpectedRawTlsClientDisconnectMessage(message),
                "Expected plaintext handshake failure to be ignored: $message",
            )
        }
    }

    @Test
    fun recognizesAndroidSslProtocolReadFailures() {
        val message = """
            Read error: ssl=0xb400007102767b58: Failure in SSL library, usually a protocol error
            error:10000416:SSL
        """.trimIndent()

        assertTrue(
            isExpectedRawTlsClientDisconnectMessage(message),
            "Expected Android SSL protocol read failure to be ignored",
        )
    }

    @Test
    fun ignoresUnrelatedErrors() {
        assertFalse(isExpectedRawTlsClientDisconnectMessage("Content-Type must be application/protobuf"))
    }
}

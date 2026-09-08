package com.folderspan.service.http.tls

import com.folderspan.service.session.DEVICE_SESSION_ALPN
import com.folderspan.service.session.advertiseDeviceSessionAlpn
import com.folderspan.service.session.offerDeviceSessionAlpn
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class TlsProtocolSupportJvmTest {
    @Test
    fun restrictToModernTlsKeepsOnlyTls12AndTls13() {
        val context = SSLContext.getInstance("TLS")
        context.init(null, null, SecureRandom())
        val socket = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        try {
            socket.restrictToModernTls()
            assertTrue(socket.enabledProtocols.isNotEmpty())
            assertTrue(socket.enabledProtocols.all { protocol -> protocol == "TLSv1.2" || protocol == "TLSv1.3" })
        } finally {
            socket.close()
        }
    }

    @Test
    fun advertiseDeviceSessionAlpnSetsFolderspanProtocol() {
        val context = SSLContext.getInstance("TLS")
        context.init(null, null, SecureRandom())
        val socket = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        try {
            socket.advertiseDeviceSessionAlpn()
            assertContentEquals(arrayOf(DEVICE_SESSION_ALPN), socket.sslParameters.applicationProtocols)
        } finally {
            socket.close()
        }
    }

    @Test
    fun offerDeviceSessionAlpnSetsFolderspanProtocolOnClientSocket() {
        val context = SSLContext.getInstance("TLS")
        context.init(null, null, SecureRandom())
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            socket.offerDeviceSessionAlpn()
            assertContentEquals(arrayOf(DEVICE_SESSION_ALPN), socket.sslParameters.applicationProtocols)
        } finally {
            socket.close()
        }
    }

    @Test
    fun advertiseDeviceSessionAlpnSetsFolderspanProtocolOnAcceptedSocket() {
        val context = SSLContext.getInstance("TLS")
        context.init(null, null, SecureRandom())
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            socket.advertiseDeviceSessionAlpn()
            assertContentEquals(arrayOf(DEVICE_SESSION_ALPN), socket.sslParameters.applicationProtocols)
        } finally {
            socket.close()
        }
    }
}

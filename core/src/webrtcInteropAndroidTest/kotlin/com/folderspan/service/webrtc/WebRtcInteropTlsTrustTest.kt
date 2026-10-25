package com.folderspan.service.webrtc

import androidx.test.platform.app.InstrumentationRegistry
import com.folderspan.service.webrtc.models.WebRtcPeerConnection
import org.webrtc.Logging
import org.webrtc.PeerConnectionFactory
import org.webrtc.WebRtcInteropTlsFactory
import org.webrtc.WebRtcInteropTlsTrust
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun configureInteropTlsTrust() {
    val directory = InstrumentationRegistry.getArguments().getString("webrtcInteropTlsFixtures") ?: return
    // Inject through our own factory holder; the fixed native SDK still verifies each certificate.
    val field = WebRtcPeerConnection::class.java.getDeclaredField("factory\$delegate").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val factory = (field.get(null) as Lazy<PeerConnectionFactory>).value
    Logging.enableLogToDebugOutput(Logging.Severity.LS_WARNING)
    if (factory !is WebRtcInteropTlsFactory) {
        field.set(null, lazyOf(WebRtcInteropTlsFactory(factory, WebRtcInteropTlsTrust(File(directory)))))
    }
}

internal class WebRtcInteropTlsTrustTest {
    @Test
    fun validatesCertificateChainIdentityAndExpiry() {
        val directory = File(checkNotNull(InstrumentationRegistry.getArguments().getString("webrtcInteropTlsFixtures")))
        val verifier = WebRtcInteropTlsTrust(directory)
        assertTrue(verifier.verify(directory.resolve("valid.der").readBytes()))
        for (name in listOf("wrong-host", "expired", "untrusted")) {
            assertFalse(verifier.verify(directory.resolve("$name.der").readBytes()), "$name must be rejected")
        }
        println("WEBRTC_TLS_TRUST valid=true wrongHostRejected=true expiredRejected=true untrustedRejected=true")
    }
}

package org.webrtc

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal class WebRtcInteropTlsTrust(directory: File) : SSLCertificateVerifier {
    private val certificates = CertificateFactory.getInstance("X.509")
    private val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("interop-ca", directory.resolve("ca.der").inputStream().use(certificates::generateCertificate))
        })
    }.trustManagers.filterIsInstance<X509TrustManager>().single()

    override fun verify(derCertificate: ByteArray): Boolean {
        val accepted = try {
            val leaf = certificates.generateCertificate(derCertificate.inputStream()) as X509Certificate
            leaf.checkValidity()
            manager.checkServerTrusted(arrayOf(leaf), leaf.publicKey.algorithm)
            if (leaf.subjectAlternativeNames?.none { it[0] == 7 && it[1] == "127.0.0.1" } != false ||
                leaf.subjectAlternativeNames?.none { it[0] == 2 && it[1] == "localhost" } != false) {
                throw CertificateException("Interop TURN certificate must identify localhost and 127.0.0.1")
            }
            true
        } catch (_: CertificateException) { false }
        println("WEBRTC_TLS_VERIFY accepted=$accepted")
        return accepted
    }
}

// The SDK constructor is package-visible; this opt-in bridge borrows the delegate's native handle.
internal class WebRtcInteropTlsFactory(
    private val delegate: PeerConnectionFactory,
    private val verifier: SSLCertificateVerifier,
) : PeerConnectionFactory(delegate.getNativeOwnedFactoryAndThreads()) {
    override fun createPeerConnection(
        configuration: PeerConnection.RTCConfiguration,
        observer: PeerConnection.Observer,
    ): PeerConnection? {
        println("WEBRTC_TLS_FACTORY secureVerifierInjected=true")
        return delegate.createPeerConnection(configuration, PeerConnectionDependencies.builder(observer)
            .setSSLCertificateVerifier(verifier).createPeerConnectionDependencies())
    }
}

package com.folderspan.service.http.client

import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCopyData
import platform.Security.SecCertificateRef
import platform.Security.SecTrustGetCertificateAtIndex
import platform.foundationtls.fm_credential_for_trust
import kotlin.text.padStart
import kotlin.text.toString
import kotlin.text.uppercase

actual fun createNoProxyHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient {
    return createDarwinClient(TlsMode.System, config)
}

actual fun createPinnedNoProxyHttpClient(
    expectedFingerprintSha256: String,
    config: HttpClientConfig<*>.() -> Unit
): HttpClient {
    return createDarwinClient(TlsMode.Pinned(expectedFingerprintSha256), config)
}

internal actual fun createDiscoveryNoProxyHttpClient(
    leafCertificateCapture: LeafCertificateCapture,
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    return createDarwinClient(TlsMode.CaptureHandshake(leafCertificateCapture), config)
}

@OptIn(ExperimentalForeignApi::class)
private fun createDarwinClient(
    tlsMode: TlsMode,
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    return HttpClient(Darwin) {
        engine {
            configureSession {
                // 禁用系统代理，避免请求被系统代理改写或劫持
                connectionProxyDictionary = emptyMap<Any?, Any?>()
                HTTPMaximumConnectionsPerHost = if (tlsMode is TlsMode.CaptureHandshake) 1 else 64
                requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
                timeoutIntervalForRequest = 30.0
                timeoutIntervalForResource = 300.0
            }
            when (tlsMode) {
                TlsMode.System -> Unit
                is TlsMode.CaptureHandshake -> {
                    val capture = tlsMode.capture
                    handleChallenge { _, _, challenge, completionHandler ->
                        val trust = challenge.protectionSpace.serverTrust
                        if (challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust &&
                            trust != null
                        ) {
                            val certificate = SecTrustGetCertificateAtIndex(trust, 0)
                            certificate
                                ?.let { item -> certificateFingerprintSha256(item) }
                                ?.let { fingerprint -> capture.accept(fingerprint) }
                            completionHandler(
                                NSURLSessionAuthChallengeUseCredential.convert(),
                                fm_credential_for_trust(trust)
                            )
                        } else {
                            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling.convert(), null)
                        }
                    }
                }

                is TlsMode.Pinned -> {
                    val expected = normalizeTlsFingerprintSha256(tlsMode.expectedFingerprintSha256)
                    handleChallenge { _, _, challenge, completionHandler ->
                        val trust = challenge.protectionSpace.serverTrust
                        if (trust == null) {
                            completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge.convert(), null)
                            return@handleChallenge
                        }
                        val certificate = SecTrustGetCertificateAtIndex(trust, 0)
                        val matched = certificate
                            ?.let { item -> certificateFingerprintSha256(item) }
                            ?.let { actual -> actual == expected }
                            ?: false
                        if (matched) {
                            completionHandler(
                                NSURLSessionAuthChallengeUseCredential.convert(),
                                fm_credential_for_trust(trust)
                            )
                        } else {
                            completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge.convert(), null)
                        }
                    }
                }
            }
        }
        config()
    }
}

private sealed interface TlsMode {
    data object System : TlsMode
    data class CaptureHandshake(val capture: LeafCertificateCapture) : TlsMode
    data class Pinned(val expectedFingerprintSha256: String) : TlsMode
}

@OptIn(ExperimentalForeignApi::class)
private fun certificateFingerprintSha256(certificate: SecCertificateRef): String? = memScoped {
    val data = SecCertificateCopyData(certificate) ?: return@memScoped null
    val bytes = CFDataGetBytePtr(data) ?: return@memScoped null
    val length = CFDataGetLength(data)
    val digest = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
    CC_SHA256(bytes, length.convert(), digest)
    (0 until CC_SHA256_DIGEST_LENGTH).joinToString("") { index ->
        val value = digest[index].toInt() and 0xFF
        value.toString(16).uppercase().padStart(2, '0')
    }
}

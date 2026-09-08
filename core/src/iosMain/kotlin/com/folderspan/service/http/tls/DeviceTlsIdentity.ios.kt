package com.folderspan.service.http.tls

import strings.AppStrings

import com.folderspan.utils.LogKit
import kotlinx.cinterop.*
import platform.openssl.EVP_PKEY_free
import platform.openssl.SSL_CTX
import platform.openssl.SSL_CTX_free
import platform.openssl.X509_free
import platform.openssl.fm_create_server_ctx
import platform.openssl.fm_ssl_ctx_require_alpn
import platform.openssl.fm_generate_rsa_key
import platform.openssl.fm_generate_self_signed_cert
import platform.openssl.fm_openssl_init
import platform.openssl.fm_private_key_to_pem
import platform.openssl.fm_read_x509_pem
import platform.openssl.fm_sign_sha256_rsa_hex
import platform.openssl.fm_verify_sha256_rsa_hex
import platform.openssl.fm_x509_sha256_fingerprint
import platform.openssl.fm_x509_public_key_to_pem
import platform.openssl.fm_x509_to_pem
import platform.posix.free

@OptIn(ExperimentalForeignApi::class)
actual object DeviceTlsIdentity {
    private var cached: CachedIdentity? = null

    actual fun loadOrCreate(): DeviceTlsIdentityInfo {
        return loadOrCreateCached().info
    }

    actual fun publicKeyPem(): String? {
        val cert = fm_read_x509_pem(loadOrCreateCached().certPem) ?: return null
        return try {
            fm_x509_public_key_to_pem(cert)?.let { pointer ->
                try {
                    pointer.toKString()
                } finally {
                    free(pointer)
                }
            }
        } finally {
            X509_free(cert)
        }
    }

    actual fun signSha256WithRsa(payload: ByteArray): String? = payload.usePinned { pinned ->
        fm_sign_sha256_rsa_hex(
            loadOrCreateCached().privateKeyPem,
            pinned.addressOf(0).reinterpret(),
            payload.size.convert(),
        )?.let { pointer ->
            try {
                pointer.toKString()
            } finally {
                free(pointer)
            }
        }
    }

    actual fun verifySha256WithRsa(
        publicKeyPem: String,
        payload: ByteArray,
        signature: String,
    ): Boolean = payload.usePinned { pinned ->
        fm_verify_sha256_rsa_hex(
            publicKeyPem,
            pinned.addressOf(0).reinterpret(),
            payload.size.convert(),
            signature,
        ) == 1
    }

    internal fun createServerContext(): ServerContext {
        val identity = loadOrCreateCached()
        val ctx = fm_create_server_ctx(identity.certPem, identity.privateKeyPem)
            ?: error(AppStrings.ui_tls_server_context_creation_failed)
        return ServerContext(ctx)
    }

    /** Device session TLS only. LinkShare HTTPS must keep using [createServerContext]. */
    internal fun createSessionServerContext(): ServerContext {
        val context = createServerContext()
        if (fm_ssl_ctx_require_alpn(context.pointer) != 1) {
            context.close()
            error(AppStrings.ui_tls_server_context_creation_failed)
        }
        return context
    }

    private fun loadOrCreateCached(): CachedIdentity {
        cached?.let { return it }
        val identity = loadOrCreateIdentity()
        cached = identity
        return identity
    }

    private fun loadOrCreateIdentity(): CachedIdentity {
        val stored = DeviceTlsIdentityFileStore.readIdentityPayload()
        if (stored == null) return generateAndPersistIdentity()
        return try {
            decodePemPayload(stored)
        } catch (error: Exception) {
            LogKit.e(AppStrings.ui_persisted_tls_identity_parse_failed_reset_rejected, error)
            throw IllegalStateException(AppStrings.ui_persisted_tls_identity_invalid, error)
        }
    }

    private fun generateAndPersistIdentity(): CachedIdentity {
        fm_openssl_init()
        val key = fm_generate_rsa_key() ?: error(AppStrings.ui_tls_private_key_generation_failed)
        try {
            val cert = fm_generate_self_signed_cert(key) ?: error(AppStrings.ui_tls_certificate_generation_failed)
            try {
                val generatedCertPem = fm_x509_to_pem(cert)?.let { pointer ->
                    try {
                        pointer.toKString()
                    } finally {
                        free(pointer)
                    }
                } ?: error(AppStrings.ui_tls_certificate_encoding_failed)
                val generatedPrivateKeyPem = fm_private_key_to_pem(key)?.let { pointer ->
                    try {
                        pointer.toKString()
                    } finally {
                        free(pointer)
                    }
                } ?: error(AppStrings.ui_tls_private_key_encoding_failed)
                val fingerprint = certificateFingerprint(generatedCertPem)
                val identity = CachedIdentity(
                    generatedCertPem,
                    generatedPrivateKeyPem,
                    DeviceTlsIdentityInfo(fingerprint)
                )
                val encodedIdentity = try {
                    encodePemPayload(generatedCertPem, generatedPrivateKeyPem)
                } catch (error: Exception) {
                    LogKit.e(AppStrings.ui_encoding_newly_generated_ios_tls_identity_fails, error)
                    throw IllegalStateException(AppStrings.ui_tls_identity_encoding_failed, error)
                }
                try {
                    DeviceTlsIdentityFileStore.writeIdentityPayload(encodedIdentity)
                } catch (error: Exception) {
                    LogKit.e(AppStrings.ui_failed_persist_newly_generated_ios_tls_identity, error)
                    throw IllegalStateException(AppStrings.ui_tls_identity_persistence_failed, error)
                }
                return identity
            } finally {
                X509_free(cert)
            }
        } finally {
            EVP_PKEY_free(key)
        }
    }

    private fun encodePemPayload(certPem: String, privateKeyPem: String): ByteArray {
        val certBytes = certPem.encodeToByteArray()
        val keyBytes = privateKeyPem.encodeToByteArray()
        return PemPayloadWriter().apply {
            writeBytes(PEM_PAYLOAD_MAGIC)
            writeInt(certBytes.size)
            writeBytes(certBytes)
            writeInt(keyBytes.size)
            writeBytes(keyBytes)
        }.toByteArray()
    }

    private fun decodePemPayload(payload: ByteArray): CachedIdentity {
        val reader = PemPayloadReader(payload)
        require(reader.readBytes(PEM_PAYLOAD_MAGIC.size).contentEquals(PEM_PAYLOAD_MAGIC))
        val certPem = reader.readBytes(reader.readInt()).decodeToString()
        val privateKeyPem = reader.readBytes(reader.readInt()).decodeToString()
        require(reader.isAtEnd())
        val fingerprint = certificateFingerprint(certPem)
        return CachedIdentity(certPem, privateKeyPem, DeviceTlsIdentityInfo(fingerprint))
    }

    private fun certificateFingerprint(certPem: String): String {
        val cert = fm_read_x509_pem(certPem)
            ?: error(AppStrings.ui_tls_certificate_read_failed)
        try {
            return memScoped {
                val digest = allocArray<UByteVar>(SHA256_DIGEST_BYTES)
                val digestLength = alloc<UIntVar>()
                val ok = fm_x509_sha256_fingerprint(cert, digest, digestLength.ptr) == 1
                if (!ok) error(AppStrings.ui_tls_certificate_fingerprint_failed)
                val length = digestLength.value.toInt()
                (0 until length).joinToString("") { index ->
                    val value = digest[index].toInt() and 0xFF
                    value.toString(16).uppercase().padStart(2, '0')
                }
            }
        } finally {
            X509_free(cert)
        }
    }

    internal class ServerContext(private val raw: CPointer<SSL_CTX>) {
        val pointer: CPointer<SSL_CTX>
            get() = raw

        fun close() {
            SSL_CTX_free(raw)
        }
    }

    private data class CachedIdentity(
        val certPem: String,
        val privateKeyPem: String,
        val info: DeviceTlsIdentityInfo
    )
}

private const val SHA256_DIGEST_BYTES = 32
private val PEM_PAYLOAD_MAGIC = byteArrayOf(0x46, 0x53, 0x49, 0x4F, 0x53, 0x54, 0x4C, 0x53, 0x31)

private class PemPayloadWriter {
    private val bytes = mutableListOf<Byte>()

    fun writeInt(value: Int) {
        require(value >= 0) { "Value must be non-negative" }
        writeByte(value ushr 24)
        writeByte(value ushr 16)
        writeByte(value ushr 8)
        writeByte(value)
    }

    fun writeBytes(value: ByteArray) {
        value.forEach { bytes += it }
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()

    private fun writeByte(value: Int) {
        bytes += (value and 0xFF).toByte()
    }
}

private class PemPayloadReader(private val bytes: ByteArray) {
    private var offset = 0

    fun readInt(): Int {
        val b1 = readByteAsInt()
        val b2 = readByteAsInt()
        val b3 = readByteAsInt()
        val b4 = readByteAsInt()
        return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
    }

    fun readBytes(size: Int): ByteArray {
        require(size >= 0) { "Size must be non-negative" }
        require(offset + size <= bytes.size) { "Unexpected end of PEM payload" }
        return bytes.copyOfRange(offset, offset + size).also {
            offset += size
        }
    }

    fun isAtEnd(): Boolean = offset == bytes.size

    private fun readByteAsInt(): Int {
        require(offset < bytes.size) { "Unexpected end of PEM payload" }
        return bytes[offset++].toInt() and 0xFF
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.folderspan.service.network

import kotlinx.cinterop.*
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.openssl.EVP_CIPHER_CTX_ctrl
import platform.openssl.EVP_CIPHER_CTX_free
import platform.openssl.EVP_CIPHER_CTX_new
import platform.openssl.EVP_CTRL_GCM_GET_TAG
import platform.openssl.EVP_CTRL_GCM_SET_IVLEN
import platform.openssl.EVP_CTRL_GCM_SET_TAG
import platform.openssl.EVP_CipherFinal_ex
import platform.openssl.EVP_CipherInit_ex
import platform.openssl.EVP_CipherUpdate
import platform.openssl.EVP_aes_256_gcm

internal actual object StorageContentCrypto {
    actual fun randomBytes(count: Int): ByteArray {
        require(count > 0)
        return ByteArray(count).also { output ->
            output.usePinned { check(SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) == errSecSuccess) {
                "Secure content randomness is unavailable"
            } }
        }
    }

    actual fun sha256(data: ByteArray): ByteArray {
        val output = ByteArray(32)
        val input = if (data.isEmpty()) byteArrayOf(0) else data
        input.usePinned { src -> output.usePinned { dst ->
            check(CC_SHA256(src.addressOf(0), data.size.convert(), dst.addressOf(0).reinterpret()) != null) {
                "Content SHA-256 failed"
            }
        } }
        return output
    }

    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32)
        val output = ByteArray(32)
        val input = if (data.isEmpty()) byteArrayOf(0) else data
        key.usePinned { secret -> input.usePinned { src -> output.usePinned { dst ->
            CCHmac(kCCHmacAlgSHA256, secret.addressOf(0), key.size.convert(), src.addressOf(0),
                data.size.convert(), dst.addressOf(0))
        } } }
        return output
    }

    actual fun pbkdf2Sha256(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(password.size <= 1024 && salt.size == 32 && iterations == 600_000)
        val encoded = password.concatToString().encodeToByteArray()
        val input = if (encoded.isEmpty()) byteArrayOf(0) else encoded
        val output = ByteArray(32)
        var accepted = false
        try {
            val status = input.usePinned { secret -> salt.usePinned { seed -> output.usePinned { dst ->
                CCKeyDerivationPBKDF(kCCPBKDF2, secret.addressOf(0), encoded.size.convert(),
                    seed.addressOf(0).reinterpret(), salt.size.convert(), kCCPRFHmacAlgSHA256,
                    iterations.convert(), dst.addressOf(0).reinterpret(), output.size.convert())
            } } }
            check(status == 0) { "Content recovery key derivation failed" }
            accepted = true
            return output
        } finally { encoded.fill(0); if (!accepted) output.fill(0) }
    }

    actual fun aesGcm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12 && (encrypt || data.size >= 16))
        val context = checkNotNull(EVP_CIPHER_CTX_new()) { "Content cipher allocation failed" }
        val mode = if (encrypt) 1 else 0
        val inputSize = if (encrypt) data.size else data.size - 16
        val output = ByteArray(inputSize + 16)
        val input = if (data.isEmpty()) byteArrayOf(0) else data
        val tag = if (encrypt) ByteArray(16) else data.copyOfRange(inputSize, data.size)
        try {
            check(EVP_CipherInit_ex(context, EVP_aes_256_gcm(), null, null, null, mode) == 1 &&
                EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_IVLEN, nonce.size, null) == 1) {
                "Content cipher initialization failed"
            }
            key.usePinned { secret -> nonce.usePinned { iv ->
                check(EVP_CipherInit_ex(context, null, null, secret.addressOf(0).reinterpret(),
                    iv.addressOf(0).reinterpret(), mode) == 1) { "Content cipher key setup failed" }
            } }
            return memScoped {
                val length = alloc<IntVar>()
                if (aad.isNotEmpty()) aad.usePinned {
                    check(EVP_CipherUpdate(context, null, length.ptr, it.addressOf(0).reinterpret(), aad.size) == 1) {
                        "Content cipher authentication setup failed"
                    }
                }
                var written = 0
                if (inputSize > 0) input.usePinned { src -> output.usePinned { dst ->
                    check(EVP_CipherUpdate(context, dst.addressOf(0).reinterpret(), length.ptr,
                        src.addressOf(0).reinterpret(), inputSize) == 1) { "Content cipher update failed" }
                    written = length.value
                } }
                if (!encrypt) tag.usePinned {
                    check(EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_TAG, 16, it.addressOf(0)) == 1) {
                        "Content cipher authentication setup failed"
                    }
                }
                output.usePinned {
                    val finalized = EVP_CipherFinal_ex(context, it.addressOf(written).reinterpret(), length.ptr)
                    if (!encrypt && finalized != 1) throw StorageContentAuthenticationException()
                    check(finalized == 1) { "Content cipher finalization failed" }
                }
                written += length.value
                check(written == inputSize) { "Unexpected content cipher length" }
                if (encrypt) {
                    tag.usePinned {
                        check(EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_GET_TAG, 16, it.addressOf(0)) == 1) {
                            "Content cipher authentication extraction failed"
                        }
                    }
                    output.copyOf(written) + tag
                } else output.copyOf(written)
            }
        } finally { output.fill(0); tag.fill(0); EVP_CIPHER_CTX_free(context) }
    }
}

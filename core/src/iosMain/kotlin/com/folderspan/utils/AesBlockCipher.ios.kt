@file:OptIn(ExperimentalForeignApi::class)

package com.folderspan.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar

internal actual fun aesEncryptBlock(key: ByteArray, block: ByteArray): ByteArray {
    require(block.size == AesGcm.BLOCK_BYTES) { "AES block must be 16 bytes" }
    return memScoped {
        val output = ByteArray(AesGcm.BLOCK_BYTES)
        val outputLength = alloc<size_tVar>()
        val status = key.usePinned { keyPinned ->
            block.usePinned { blockPinned ->
                output.usePinned { outPinned ->
                    CCCrypt(
                        kCCEncrypt,
                        kCCAlgorithmAES,
                        kCCOptionECBMode,
                        keyPinned.addressOf(0),
                        key.size.convert(),
                        null,
                        blockPinned.addressOf(0),
                        block.size.convert(),
                        outPinned.addressOf(0),
                        output.size.convert(),
                        outputLength.ptr,
                    )
                }
            }
        }
        check(status == kCCSuccess) { "AES block encrypt failed: $status" }
        check(outputLength.value.toInt() == AesGcm.BLOCK_BYTES) { "AES block encrypt wrote an unexpected length" }
        output
    }
}

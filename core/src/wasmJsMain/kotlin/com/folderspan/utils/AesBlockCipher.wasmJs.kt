@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.utils

import kotlin.io.encoding.Base64
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsModule

@JsModule("crypto-js")
private external val cryptoJsModule: JsAny

internal actual fun aesEncryptBlock(key: ByteArray, block: ByteArray): ByteArray {
    require(block.size == AesGcm.BLOCK_BYTES) { "AES block must be 16 bytes" }
    val encrypted = aesEncryptBlockBase64(
        cryptoJs = cryptoJsModule,
        keyBase64 = Base64.encode(key),
        blockBase64 = Base64.encode(block),
    )
    return Base64.decode(encrypted)
}

@JsFun(
    """
    (cryptoJs, keyBase64, blockBase64) => {
      const base64ToBytes = (base64) => {
        const binary = atob(String(base64 ?? ""));
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i += 1) {
          bytes[i] = binary.charCodeAt(i);
        }
        return bytes;
      };
      const bytesToWordArray = (bytes) => {
        const words = [];
        for (let i = 0; i < bytes.length; i += 1) {
          const index = i >>> 2;
          words[index] = (words[index] || 0) | (bytes[i] << (24 - (i % 4) * 8));
        }
        return cryptoJs.lib.WordArray.create(words, bytes.length);
      };
      const wordArrayToBytes = (wordArray) => {
        const sigBytes = wordArray.sigBytes;
        const words = wordArray.words;
        const out = new Uint8Array(sigBytes);
        for (let i = 0; i < sigBytes; i += 1) {
          out[i] = (words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
        }
        return out;
      };
      const bytesToBase64 = (bytes) => {
        let binary = "";
        for (let i = 0; i < bytes.length; i += 1) {
          binary += String.fromCharCode(bytes[i]);
        }
        return btoa(binary);
      };
      const key = bytesToWordArray(base64ToBytes(keyBase64));
      const data = bytesToWordArray(base64ToBytes(blockBase64));
      const encrypted = cryptoJs.AES.encrypt(data, key, {
        mode: cryptoJs.mode.ECB,
        padding: cryptoJs.pad.NoPadding
      });
      return bytesToBase64(wordArrayToBytes(encrypted.ciphertext));
    }
    """
)
private external fun aesEncryptBlockBase64(
    cryptoJs: JsAny,
    keyBase64: String,
    blockBase64: String,
): String

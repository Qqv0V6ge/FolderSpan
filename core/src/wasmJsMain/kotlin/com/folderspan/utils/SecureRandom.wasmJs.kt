@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.utils

import kotlin.io.encoding.Base64
import kotlin.js.ExperimentalWasmJsInterop

actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "size must be non-negative" }
    if (size == 0) return byteArrayOf()
    return Base64.decode(secureRandomBytesBase64(size))
}

@JsFun(
    """
    (size) => {
      const resolvedSize = Math.max(0, Number(size) || 0);
      const bytes = new Uint8Array(resolvedSize);
      if (typeof globalThis === "undefined" || !globalThis.crypto || !globalThis.crypto.getRandomValues) {
        throw new Error("Secure random source is unavailable");
      }
      globalThis.crypto.getRandomValues(bytes);
      let binary = "";
      const chunkSize = 0x8000;
      for (let i = 0; i < bytes.length; i += chunkSize) {
        const chunk = bytes.subarray(i, i + chunkSize);
        binary += String.fromCharCode.apply(null, chunk);
      }
      return btoa(binary);
    }
    """
)
private external fun secureRandomBytesBase64(size: Int): String

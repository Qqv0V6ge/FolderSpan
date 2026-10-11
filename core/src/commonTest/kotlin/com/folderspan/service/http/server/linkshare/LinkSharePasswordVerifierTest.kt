package com.folderspan.service.http.server.linkshare

import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkSharePasswordVerifierTest {
    @Test
    fun matchesOnlyTheExpectedPassword() = runSuspendTest {
        val verifier = LinkSharePasswordVerifier(
            iterations = 2,
            salt = ByteArray(16) { index -> index.toByte() },
        )
        assertTrue(verifier.matches("secret-pass", "secret-pass"))
        assertFalse(verifier.matches("secret-pass", "Secret-pass"))
        assertFalse(verifier.matches("secret-pass", ""))
        assertFalse(verifier.matches("", "secret-pass"))
    }

    @Test
    fun pbkdf2MatchesKnownSha256Vectors() {
        val password = "password".encodeToByteArray()
        val salt = "salt".encodeToByteArray()
        assertContentEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b".hexToByteArray(),
            pbkdf2HmacSha256(password, salt, iterations = 1, derivedKeySize = 32),
        )
        assertContentEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43".hexToByteArray(),
            pbkdf2HmacSha256(password, salt, iterations = 2, derivedKeySize = 32),
        )
    }

    @Test
    fun constantTimeEqualsRejectsLengthAndContentMismatch() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
    }
}

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

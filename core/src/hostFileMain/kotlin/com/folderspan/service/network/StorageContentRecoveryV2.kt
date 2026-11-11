package com.folderspan.service.network

import kotlin.uuid.Uuid
import okio.Buffer

/** Version 2 binds the independent v1 password-wrapped key to its authenticated classification anchor. */
internal object StorageContentRecoveryV2 {
    private val magic = "FSPANCAT".encodeToByteArray()
    private val domain = "FolderSpan classification recovery v2\u0000".encodeToByteArray()
    private const val PREFIX_BYTES = 8 + 4 + 4 + 140 + 16
    internal class Recovered(val key: ByteArray, val anchor: String) {
        override fun toString() = "RecoveredContentClassification()"
    }

    fun exportRecovery(key: ByteArray, scope: String, anchor: String, password: CharArray): ByteArray {
        contentManifestFileName(anchor)
        val wrapped = StorageContentEncryptionV1.exportRecovery(key, scope, password)
        val prefix = Buffer().write(magic).writeInt(2).writeInt(wrapped.size)
            .write(wrapped).write(Uuid.parse(anchor).toByteArray()).readByteArray()
        return try { prefix + authenticate(key, prefix) } finally { wrapped.fill(0) }
    }

    fun importRecovery(backup: ByteArray, scope: String, password: CharArray): Recovered {
        require(backup.size == PREFIX_BYTES + 32) { "Recovery requires the version 2 classification anchor" }
        val input = Buffer().write(backup)
        val header = input.readByteArray(8)
        require(header.contentEquals(magic) && input.readInt() == 2 && input.readInt() == 140) { "Unsupported classification recovery format" }
        val wrapped = input.readByteArray(140)
        val anchor = Uuid.fromByteArray(input.readByteArray(16)).toString()
        val signature = input.readByteArray(32)
        val key = try { StorageContentEncryptionV1.importRecovery(wrapped, scope, password) } finally { wrapped.fill(0) }
        var accepted = false
        try {
            val expected = authenticate(key, backup.copyOf(PREFIX_BYTES))
            try { require(storageContentConstantTimeEquals(signature, expected)) { "Recovery classification anchor authentication failed" } }
            finally { expected.fill(0) }
            accepted = true
            return Recovered(key, anchor)
        } finally { signature.fill(0); if (!accepted) key.fill(0) }
    }

    private fun authenticate(key: ByteArray, prefix: ByteArray): ByteArray {
        require(key.size == 32)
        return StorageContentCrypto.hmacSha256(key, domain + prefix)
    }
}

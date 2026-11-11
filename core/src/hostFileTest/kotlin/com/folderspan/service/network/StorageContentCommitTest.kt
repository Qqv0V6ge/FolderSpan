package com.folderspan.service.network

import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class StorageContentCommitTest {
    private val vaultId = Uuid.random().toString()
    private val entryId = Uuid.random().toString()
    private val objectId = Uuid.random().toString()
    private val key = ByteArray(32) { it.toByte() }
    private val scope = storageContentObjectScope(vaultId, objectId)
    private val record = StorageContentCommit(vaultId = vaultId, operationId = Uuid.random().toString(),
        entryId = entryId, parentId = vaultId, name = "普通名称.txt", kind = StorageContentCommitKind.File,
        objectId = objectId, ciphertextLength = 160, ciphertextDigest = "ab".repeat(32))

    @Test
    fun commitRoundTripsAndBindsEveryFieldAndParentRevision() {
        val bytes = StorageContentCommitCodec.encode(record, key)
        assertEquals(record, StorageContentCommitCodec.decode(bytes, key, vaultId))
        for (offset in listOf(0, 11, 15, 40, bytes.size - 1)) {
            val changed = bytes.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertFails { StorageContentCommitCodec.decode(changed, key, vaultId) }
        }
        assertFails { StorageContentCommitCodec.decode(bytes, ByteArray(32), vaultId) }
        assertFails { StorageContentCommitCodec.decode(bytes, key, Uuid.random().toString()) }
        for (changed in listOf(
            record.copy(entryId = Uuid.random().toString()), record.copy(objectId = Uuid.random().toString()),
            record.copy(parentId = Uuid.random().toString()), record.copy(parents = listOf(Uuid.random().toString())),
        )) {
            val other = StorageContentCommitCodec.encode(changed, key)
            assertFails { StorageContentCommitCodec.decode(other.copyOf(other.size - 32) + bytes.takeLast(32).toByteArray(), key, vaultId) }
        }
    }

    @Test
    fun invalidRecordsAndUnboundedOrTruncatedPayloadsFailClosed() {
        for (invalid in listOf(
            record.copy(version = 2), record.copy(operationId = "invalid"), record.copy(name = "../plain"),
            record.copy(parents = listOf(record.operationId)), record.copy(ciphertextLength = -1),
            record.copy(ciphertextDigest = ""), record.copy(kind = StorageContentCommitKind.Delete),
        )) assertFails { StorageContentCommitCodec.encode(invalid, key) }
        val bytes = StorageContentCommitCodec.encode(record, key)
        for (invalid in listOf(
            bytes.copyOf(0), bytes.copyOf(40), bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0),
            bytes.copyOf().also { it[12] = 0x7f },
        )) assertFails { StorageContentCommitCodec.decode(invalid, key, vaultId) }
    }

    @Test
    fun substitutedEntryObjectOrCompletelyDamagedHeaderNeverPublishesPlaintext() {
        val ciphertext = Buffer()
        StorageContentEncryptionV1.encrypt(Buffer().writeUtf8("authenticated"), ciphertext, key, scope)
        val bytes = ciphertext.readByteArray()
        val commit = record.copy(ciphertextLength = bytes.size.toLong(),
            ciphertextDigest = StorageContentCrypto.sha256(bytes).toByteString().hex(), size = 13)
        assertEquals(commit, StorageContentCommitCodec.decode(StorageContentCommitCodec.encode(commit, key), key, vaultId))
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("content-commit-test-" + Uuid.random())
        StorageContentFileProtection.createPrivateDirectory(directory)
        val destination = directory / "existing"
        val previous = "keep existing".encodeToByteArray()
        try {
            FileSystem.SYSTEM.write(destination) { write(previous) }
            val scopes = listOf(
                storageContentObjectScope(Uuid.random().toString(), objectId),
                storageContentObjectScope(vaultId, Uuid.random().toString()),
            )
            for (other in scopes) {
                assertFails { StorageContentEncryptionV1.decryptAndPublish(Buffer().write(bytes), destination, key, other) }
            }
            val damaged = bytes.copyOf().also { it.fill(0, 0, 124) }
            assertFails { StorageContentEncryptionV1.decryptAndPublish(Buffer().write(damaged), destination, key, scope) }
            assertContentEquals(previous, FileSystem.SYSTEM.read(destination) { readByteArray() })
        } finally { FileSystem.SYSTEM.deleteRecursively(directory) }
    }
}

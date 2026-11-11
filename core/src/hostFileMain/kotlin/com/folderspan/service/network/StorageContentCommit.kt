package com.folderspan.service.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
internal enum class StorageContentCommitKind { Root, File, Directory, Delete }

@Serializable
internal data class StorageContentDirectoryBinding(
    val relativePath: String,
    val reference: StorageReference? = null,
)

@Serializable
@OptIn(ExperimentalUuidApi::class)
internal data class StorageContentCommit(
    val version: Int = 1,
    val vaultId: String,
    val operationId: String,
    val entryId: String,
    val parentId: String,
    val name: String,
    val parents: List<String> = emptyList(),
    val kind: StorageContentCommitKind,
    val objectId: String = "",
    val ciphertextLength: Long = 0,
    val ciphertextDigest: String = "",
    val size: Long = 0,
    val updatedDate: Long = 0,
    val ordinaryDirectory: StorageContentDirectoryBinding? = null,
) {
    fun requireValid() {
        require(version == 1) { "Unsupported content commit version" }
        listOf(vaultId, operationId, entryId).forEach(::requireContentUuid)
        require(parents.size <= 64 && parents.distinct().size == parents.size && operationId !in parents) {
            "Invalid content parent revisions"
        }
        parents.forEach(::requireContentUuid)
        require(size >= 0 && updatedDate >= 0) { "Invalid content metadata" }
        ordinaryDirectory?.let {
            require(kind == StorageContentCommitKind.Directory || kind == StorageContentCommitKind.Delete) { "Invalid directory binding" }
            require(it.relativePath.isNotBlank() && !it.relativePath.startsWith('/') &&
                it.relativePath.split('/').none { name -> name.isBlank() || name == StorageContentVaultAccess.NAMESPACE } &&
                it.relativePath.none(Char::isISOControl)) { "Invalid ordinary directory binding" }
            requireSafeNetworkWritePath("/" + it.relativePath)
            require(it.reference?.content == null && it.reference?.ancestorPath.isNullOrEmpty()) { "Directory binding contains a local context" }
        }
        if (kind == StorageContentCommitKind.Root) {
            require(entryId == vaultId && parentId.isEmpty() && name.isEmpty() && parents.isEmpty() &&
                objectId.isEmpty() && ciphertextLength == 0L && size == 0L) { "Invalid content root record" }
            require(ciphertextDigest.matches(Regex("[0-9a-f]{64}"))) { "Missing password descriptor binding" }
        } else {
            requireContentUuid(parentId)
            require(entryId != vaultId && entryId != parentId && name.isNotBlank() && name.length <= 255 &&
                '/' !in name && '\\' !in name && name.none(Char::isISOControl)) { "Invalid content entry name or parent" }
            requireSafeNetworkWritePath("/" + name)
            if (kind == StorageContentCommitKind.File) {
                requireContentUuid(objectId)
                require(ciphertextLength >= 160 && ciphertextDigest.matches(Regex("[0-9a-f]{64}"))) {
                    "Invalid ciphertext object binding"
                }
            } else {
                require(objectId.isEmpty() && ciphertextLength == 0L && ciphertextDigest.isEmpty() && size == 0L) {
                    "Unexpected content object in metadata record"
                }
            }
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun requireContentUuid(value: String) {
    require(Uuid.parse(value).toString() == value) { "Invalid content identity" }
}

internal object StorageContentCommitCodec {
    private val magic = "FSPANCMT".encodeToByteArray()
    private val domain = "FolderSpan commit authentication v1\u0000".encodeToByteArray()
    private val json = Json { encodeDefaults = true }
    private const val MAX_PAYLOAD_BYTES = 64 * 1024

    fun encode(record: StorageContentCommit, key: ByteArray): ByteArray {
        record.requireValid()
        val payload = json.encodeToString(StorageContentCommit.serializer(), record).encodeToByteArray()
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Content commit is too large" }
        val prefix = Buffer().write(magic).writeInt(1).writeInt(payload.size).write(payload).readByteArray()
        return prefix + authenticate(key, prefix)
    }

    fun decode(encoded: ByteArray, key: ByteArray, vaultId: String): StorageContentCommit = try {
        decodeRecord(encoded, key, vaultId)
    } catch (error: StorageContentException) { throw error }
    catch (_: Exception) {
        throw StorageContentException(StorageContentErrorCode.DamagedLocation, "Authenticated content metadata is damaged")
    }

    private fun decodeRecord(encoded: ByteArray, key: ByteArray, vaultId: String): StorageContentCommit {
        require(encoded.size in 48..(MAX_PAYLOAD_BYTES + 48)) { "Invalid content commit length" }
        val input = Buffer().write(encoded)
        if (!input.readByteArray(8).contentEquals(magic) || input.readInt() != 1) throw StorageContentException(
            StorageContentErrorCode.UnsupportedFormat, "Unsupported content commit format")
        val length = input.readInt()
        require(length in 0..MAX_PAYLOAD_BYTES && encoded.size == length + 48) { "Invalid content commit payload length" }
        val payload = input.readByteArray(length.toLong())
        val signature = input.readByteArray(32)
        val expected = authenticate(key, encoded.copyOf(encoded.size - 32))
        try {
            require(storageContentConstantTimeEquals(signature, expected)) { "Content commit authentication failed" }
        } finally { expected.fill(0) }
        return json.decodeFromString<StorageContentCommit>(payload.decodeToString(throwOnInvalidSequence = true)).also {
            it.requireValid()
            require(it.vaultId == vaultId) { "Content commit belongs to another location" }
        }
    }

    private fun authenticate(key: ByteArray, prefix: ByteArray): ByteArray {
        require(key.size == 32) { "Invalid content key" }
        val authKey = StorageContentCrypto.hmacSha256(key, domain)
        return try { StorageContentCrypto.hmacSha256(authKey, prefix) } finally { authKey.fill(0) }
    }
}

/** The commit authenticates the entry-to-object binding; ciphertext is portable across logical renames. */
internal fun storageContentObjectScope(vaultId: String, objectId: String): String {
    listOf(vaultId, objectId).forEach(::requireContentUuid)
    return "FolderSpan vault object v1\u0000" + Json.encodeToString(listOf(vaultId, objectId))
}

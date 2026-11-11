package com.folderspan.service.network

import kotlin.uuid.Uuid

internal const val STORAGE_CONTENT_MANIFEST_LIMIT = 4 * 1024 * 1024

/** Revision and authenticated bytes remain on the execution host. Null expected revision means create-only. */
data class StorageContentManifestSnapshot(val revision: String, val bytes: ByteArray) {
    init { require(revision.isNotBlank() && revision.length <= 16 * 1024 && bytes.size <= STORAGE_CONTENT_MANIFEST_LIMIT) }
    override fun toString() = "StorageContentManifestSnapshot()"
}

class StorageContentManifestConflictException : Exception("Content manifest changed; read the current revision before retrying")

interface StorageContentManifestStore {
    val contentManifestReadable: Boolean get() = false
    val contentManifestConditionalWrites: Boolean get() = false
    suspend fun readContentManifest(anchor: String): Result<StorageContentManifestSnapshot?> =
        Result.failure(UnsupportedOperationException("This backend does not provide verified conditional content metadata"))
    suspend fun compareAndSetContentManifest(anchor: String, expectedRevision: String?, bytes: ByteArray): Result<StorageContentManifestSnapshot> =
        Result.failure(UnsupportedOperationException("This backend does not provide verified conditional content metadata"))
    suspend fun contentObjectIdentity(path: StoragePath): Result<String?> =
        statContentObjectUnsupported()
}

private fun statContentObjectUnsupported(): Result<String?> =
    Result.failure(UnsupportedOperationException("This backend does not provide stable content identities"))

internal fun contentManifestFileName(anchor: String): String {
    require(Uuid.parse(anchor).toString() == anchor) { "Invalid content manifest anchor" }
    return ".folderspan-content.json"
}

internal fun requireContentManifestBody(bytes: ByteArray) {
    require(bytes.size in 1..STORAGE_CONTENT_MANIFEST_LIMIT) { "Content manifest exceeds its bounded format" }
}

/** Used only when the host has positively established that no local classification key exists. */
internal suspend fun requireNoExistingStorageContentManifest(provider: StorageProvider) {
    if (provider.contentManifestReadable) check(provider.readContentManifest(Uuid.random().toString()).getOrThrow() == null) {
        "This directory has authenticated content classification; import its recovery backup before reading or changing objects"
    }
}

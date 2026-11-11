package com.folderspan.service.network

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
internal data class StorageContentManifest(
    val version: Int = 1,
    val scope: String,
    val anchor: String,
    val generation: Long = 0,
    val records: List<StorageContentManifestRecord> = emptyList(),
) {
    fun requireValid(expectedScope: String, expectedAnchor: String) {
        require(version == 1 && scope == expectedScope && anchor == expectedAnchor && generation >= 0) {
            "Content manifest belongs to another format or encrypted location"
        }
        contentManifestFileName(anchor)
        require(records.size <= 10_000 && records.map { it.operationId }.toSet().size == records.size) {
            "Content manifest exceeds its bounded identity table"
        }
        records.forEach { it.requireValid() }
    }
}

@Serializable
internal data class StorageContentManifestRecord(
    val operationId: String,
    val path: String,
    val objectId: String = "",
    val ciphertextDigest: String,
    val previousPlaintextDigest: String? = null,
    val committed: Boolean = false,
) {
    fun requireValid() {
        require(Uuid.parse(operationId).toString() == operationId) { "Invalid content operation identity" }
        require(path.startsWith('/') && path.length <= 16 * 1024 && path.none(Char::isISOControl)) {
            "Invalid content manifest path"
        }
        requireSafeNetworkWritePath(path)
        require(objectId.length <= 4096 && objectId.none(Char::isISOControl) &&
            (!committed || objectId.isNotBlank())) { "Content metadata requires a stable object identity" }
        require(ciphertextDigest.matches(Regex("[0-9a-f]{64}")) &&
            (previousPlaintextDigest == null || previousPlaintextDigest.matches(Regex("[0-9a-f]{64}")))) {
            "Invalid content manifest digest"
        }
    }
}

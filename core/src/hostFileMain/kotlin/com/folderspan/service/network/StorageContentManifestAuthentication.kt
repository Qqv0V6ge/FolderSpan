package com.folderspan.service.network

import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A separate domain binds classification and scope; the manifest never contains a content key. */
internal object StorageContentManifestAuthentication {
    private val json = Json { encodeDefaults = true }
    private val domain = "FolderSpan content classification v1\u0000".encodeToByteArray()

    @Serializable
    private data class Envelope(val version: Int = 1, val payload: String, val authentication: String)

    fun encode(value: StorageContentManifest, key: ByteArray): ByteArray {
        value.requireValid(value.scope, value.anchor)
        require(key.size == 32)
        val payload = json.encodeToString(value).encodeToByteArray()
        val signature = authenticate(key, payload)
        return try {
            json.encodeToString(Envelope(payload = Base64.encode(payload),
                authentication = Base64.encode(signature))).encodeToByteArray()
                .also(::requireContentManifestBody)
        } finally { payload.fill(0); signature.fill(0) }
    }

    fun decode(bytes: ByteArray, key: ByteArray, scope: String, anchor: String): StorageContentManifest {
        requireContentManifestBody(bytes)
        require(key.size == 32)
        val envelope = json.decodeFromString<Envelope>(bytes.decodeToString())
        require(envelope.version == 1) { "Unknown content manifest envelope" }
        val payload = Base64.decode(envelope.payload)
        val signature = Base64.decode(envelope.authentication)
        val expected = authenticate(key, payload)
        return try {
            require(signature.size == 32 && storageContentConstantTimeEquals(signature, expected)) { "Content manifest authentication failed" }
            json.decodeFromString<StorageContentManifest>(payload.decodeToString())
                .also { it.requireValid(scope, anchor) }
        } finally { payload.fill(0); signature.fill(0); expected.fill(0) }
    }

    private fun authenticate(key: ByteArray, payload: ByteArray): ByteArray =
        StorageContentCrypto.hmacSha256(key, domain + payload)
}

internal class StorageContentManifestAccess(
    private val provider: StorageProvider,
    private val scope: String,
    private val anchor: String,
    private val key: ByteArray,
) {
    suspend fun read(): StorageContentManifest {
        val snapshot = provider.readContentManifest(anchor).getOrThrow()
            ?: error("Content classification manifest is missing; recover its native version before reading")
        return StorageContentManifestAuthentication.decode(snapshot.bytes, key, scope, anchor)
    }

    suspend fun initialize() {
        check(provider.contentManifestConditionalWrites) { "This backend cannot safely update encrypted content metadata" }
        val empty = StorageContentManifestAuthentication.encode(StorageContentManifest(scope = scope, anchor = anchor), key)
        val created = provider.compareAndSetContentManifest(anchor, null, empty)
        if (created.isFailure && created.exceptionOrNull() !is StorageContentManifestConflictException) created.getOrThrow()
        repeat(8) {
            val current = provider.readContentManifest(anchor).getOrThrow()
                ?: error("Content manifest initialization could not be verified")
            val document = StorageContentManifestAuthentication.decode(current.bytes, key, scope, anchor)
            // Retry never replaces an existing authenticated identity table with an empty one.
            val createAgain = provider.compareAndSetContentManifest(anchor, null, current.bytes)
            check(createAgain.exceptionOrNull() is StorageContentManifestConflictException) {
                "Backend ignored create-only encrypted metadata protection"
            }
            check(document.generation < Long.MAX_VALUE) { "Content manifest generation overflow" }
            val updated = provider.compareAndSetContentManifest(anchor, current.revision,
                StorageContentManifestAuthentication.encode(document.copy(generation = document.generation + 1), key))
            if (updated.exceptionOrNull() is StorageContentManifestConflictException) return@repeat
            val saved = updated.getOrThrow()
            check(saved.revision != current.revision) { "Content manifest revision did not advance" }
            val stale = provider.compareAndSetContentManifest(anchor, current.revision, saved.bytes)
            check(stale.exceptionOrNull() is StorageContentManifestConflictException) {
                "Backend ignored stale encrypted metadata protection"
            }
            return
        }
        throw StorageContentManifestConflictException()
    }

    suspend fun update(change: (StorageContentManifest) -> StorageContentManifest): StorageContentManifest {
        repeat(8) {
            val snapshot = provider.readContentManifest(anchor).getOrThrow()
                ?: error("Content classification manifest is missing")
            val current = StorageContentManifestAuthentication.decode(snapshot.bytes, key, scope, anchor)
            check(current.generation < Long.MAX_VALUE) { "Content manifest generation overflow" }
            val changed = change(current).copy(generation = current.generation + 1)
            changed.requireValid(scope, anchor)
            val saved = provider.compareAndSetContentManifest(anchor, snapshot.revision,
                StorageContentManifestAuthentication.encode(changed, key))
            if (saved.isSuccess) return changed
            if (saved.exceptionOrNull() !is StorageContentManifestConflictException) saved.getOrThrow()
        }
        throw StorageContentManifestConflictException()
    }
}

package com.folderspan.service.network

import com.russhwolf.settings.Settings
import com.folderspan.ui.state.main.TaskRuntimeStoreLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.HashingSink
import okio.blackholeSink
import okio.ByteString.Companion.toByteString
import kotlin.io.encoding.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal class StorageContentInitializationConflictException : StorageContentException(
    StorageContentErrorCode.InitializationConflict,
    "Multiple password locations were found; preserve all candidates and resolve initialization before writing")

/** Uses existing list/stream IO only. No rename, shared manifest or conditional-write assumption. */
@OptIn(ExperimentalUuidApi::class)
internal class StorageContentVaultAccess(
    private val provider: StorageProvider,
    private val root: StoragePath,
    private val settings: Settings,
    owner: String,
) {
    init {
        requireSafeNetworkWritePath(root.path)
        if (!root.path.startsWith('/') || root.path.split('/').any { it == NAMESPACE || it == ".folderspan-content.json" } ||
            root.reference?.content != null) {
            throw StorageContentException(StorageContentErrorCode.DamagedLocation,
                "Reserved password location material cannot be used as a location root")
        }
    }

    private val pendingName = "auth.storage.password-location.pending." +
        digest((owner + Json.encodeToString(StoragePath.serializer(), root)).encodeToByteArray())

    private val decodedCache = mutableMapOf<String, Pair<String, StorageContentCommit>>()
    private val cacheLock = TaskRuntimeStoreLock()

    private data class Scan(val directory: StoragePath?, val entries: List<NetworkFileEntry>) {
        val candidates get() = entries.filter { it.name.endsWith(".location") }
        val roots get() = entries.filter { it.name.endsWith(".root") }
    }


    @Serializable
    private data class Checkpoint(val version: Int = 1, val records: Map<String, String>)

    /** A cache can be rebuilt; this protected observed-history checkpoint must never silently move backwards. */
    private data class VerifiedHistory(val history: StorageContentHistory, val scan: Scan)

    suspend fun history(vaultId: String, key: ByteArray): StorageContentHistory =
        readHistory(vaultId, key, verifyContents = true).history

    suspend fun metadataHistory(vaultId: String, key: ByteArray): StorageContentHistory =
        readHistory(vaultId, key, verifyContents = false).history

    private suspend fun readHistory(vaultId: String, key: ByteArray, verifyContents: Boolean): VerifiedHistory {
        val current = scan()
        requireUniqueCandidate(current, vaultId)
        check(current.candidates.size == 1) { "Known password location descriptor is missing" }
        val entries = current.entries.filter { it.name.endsWith(".commit") }
        check(entries.size < StorageContentHistory.MAX_RECORDS) { "Content history exceeds the replay limit" }
        val batches = (current.candidates + current.roots + entries).chunked(32)
        val initial = readMetadataBatch(current, batches.first())
        val rootBytes = authenticatedRoot(current, vaultId, key, initial)
        val tag = StorageContentCrypto.hmacSha256(key, CACHE_DOMAIN)
        val keyTag = try { tag.toByteString().hex() } finally { tag.fill(0) }
        val records = mutableListOf(StorageContentCommitCodec.decode(rootBytes, key, vaultId))
        val digests = mutableMapOf(vaultId to digest(rootBytes))
        for ((index, batch) in batches.withIndex()) {
            val bodies = if (index == 0) initial else readMetadataBatch(current, batch)
            for (entry in batch.filter { it.name.endsWith(".commit") }) {
                currentCoroutineContext().ensureActive()
                val bytes = bodies.getValue(entry.name)
                val hash = digest(bytes)
                val cacheId = vaultId + ":" + entry.name
                val stamp = keyTag + ":" + hash
                cacheLock.lock()
                val record = try {
                    decodedCache[cacheId]?.takeIf { it.first == stamp }?.second
                        ?: StorageContentCommitCodec.decode(bytes, key, vaultId).also { decodedCache[cacheId] = stamp to it }
                } finally { cacheLock.unlock() }
                check(entry.name == record.operationId + ".commit" && record.kind != StorageContentCommitKind.Root) {
                    "Content commit name or root identity changed"
                }
                records.add(record)
                digests[record.operationId] = hash
            }
        }
        val checkpointName = "auth.storage.password-location.checkpoint." + digest(vaultId.encodeToByteArray())
        checkpointLock.lock()
        try { requireObservedHistory(checkpointName, digests) } finally { checkpointLock.unlock() }
        val history = StorageContentHistory.replay(vaultId, records)
        val files = history.heads.values.flatten().filter { it.kind == StorageContentCommitKind.File }
        files.forEach { requireObject(current, it.objectId, it.ciphertextLength) }
        if (verifyContents) files.readInBatches { verifyObject(current, it) }
        checkpointLock.lock()
        try {
            requireObservedHistory(checkpointName, digests)
            settings.putString(checkpointName, Json.encodeToString(Checkpoint(records = digests)))
        } finally { checkpointLock.unlock() }
        return VerifiedHistory(history, current)
    }

    suspend fun authenticateLocation(vaultId: String, key: ByteArray) {
        authenticatedRoot(scan(), vaultId, key)
    }

    private suspend fun authenticatedRoot(current: Scan, vaultId: String, key: ByteArray,
        bodies: Map<String, ByteArray> = emptyMap()): ByteArray {
        requireUniqueCandidate(current, vaultId)
        check(current.candidates.size == 1) { "Known password location descriptor is missing" }
        val candidate = current.candidates.single()
        require(candidate.size in 0..StorageContentPasswordLocation.ENCODED_BYTES.toLong()) {
            "Password location descriptor exceeds its bounded format"
        }
        val descriptor = bodies[candidate.name]
            ?: readBytes(current, candidate, StorageContentPasswordLocation.ENCODED_BYTES)
        check(StorageContentPasswordLocation.vaultId(descriptor) == vaultId) { "Password location identity changed" }
        return verifyRoot(current, descriptor, StorageContentPasswordLocation.Unlocked(vaultId, key), bodies)
    }

    private suspend fun readMetadataBatch(scan: Scan, entries: List<NetworkFileEntry>): Map<String, ByteArray> {
        val reader = provider as? ChunkReadableNetworkClient
            ?: error("This execution host cannot read bounded password location metadata")
        val limit = 64 * 1024 + 48
        entries.forEach { require(it.size in 0..limit.toLong()) { "Password location metadata exceeds its bounded format" } }
        val batch = reader.downloadContentMetadata(checkNotNull(scan.directory), entries, limit).getOrThrow()
        if (batch == null) return entries.readInBatches { it.name to readBytes(scan, it, limit) }.toMap()
        check(batch.keys == entries.map { it.name }.toSet()) { "Incomplete or unexpected password location metadata batch" }
        entries.forEach { check(batch.getValue(it.name).size.toLong() == it.size) { "Password location metadata changed length" } }
        return batch
    }

    suspend fun objectPath(objectId: String): StoragePath {
        require(Uuid.parse(objectId).toString() == objectId) { "Invalid ciphertext object identity" }
        val current = scan()
        val directory = checkNotNull(current.directory) { "Password location directory is missing" }
        val path = directory.path.trimEnd('/') + "/" + objectId + ".object"
        return StoragePath(path, directory.reference?.forPath(directory.path, path, true))
    }

    private fun requireObservedHistory(name: String, current: Map<String, String>) {
        settings.getStringOrNull(name)?.let {
            val previous = Json.decodeFromString<Checkpoint>(it)
            check(previous.version == 1 && previous.records.size <= StorageContentHistory.MAX_RECORDS) {
                "Unsupported content history checkpoint"
            }
            check(previous.records.all { (id, hash) -> current[id] == hash }) {
                "Content history is incomplete or older than this execution host has already observed"
            }
        }
    }

    /** Retries keep their operation/object IDs; only independent objects and append records are ever uploaded. */
    suspend fun publish(record: StorageContentCommit, key: ByteArray, ciphertextPath: String? = null) {
        record.requireValid()
        check(record.kind != StorageContentCommitKind.Root) { "A location root cannot be republished as a file operation" }
        check(provider.effectiveCapabilities().getOrThrow().write) { "This location is read-only" }
        val observed = readHistory(record.vaultId, key, verifyContents = false)
        val before = observed.history
        before.records[record.operationId]?.let {
            check(it == record) { "Content operation identity was reused with different data" }
            before.head(record.entryId)
            if (record.kind == StorageContentCommitKind.File) verifyObject(observed.scan, record)
            return
        }
        StorageContentHistory.replay(record.vaultId, before.records.values.toList() + record)
        record.parents.forEach { id ->
            check(before.records[id]?.entryId == record.entryId) { "Content operation parent revision is missing or belongs to another entry" }
        }
        if (record.kind != StorageContentCommitKind.Delete) {
            val parent = before.head(record.parentId) ?: error("Content target directory is missing")
            check(parent.kind == StorageContentCommitKind.Directory || parent.kind == StorageContentCommitKind.Root) {
                "Content target is not a live directory"
            }
        }
        var current = observed.scan
        val directory = checkNotNull(current.directory)
        if (record.kind == StorageContentCommitKind.File) {
            if (current.entries.none { it.name == record.objectId + ".object" }) {
                checkNotNull(ciphertextPath) { "New content object requires its original ciphertext spool" }
                val target = directory.path + "/" + record.objectId + ".object"
                check(provider.upload(ciphertextPath, StoragePath(target,
                    directory.reference?.forPath(directory.path, target, true)), record.ciphertextLength).getOrThrow()) {
                    "Independent ciphertext object upload failed"
                }
                current = scan()
                requireUniqueCandidate(current, record.vaultId)
            }
            verifyObject(current, record)
        }
        currentCoroutineContext().ensureActive()
        writeImmutable(directory, record.operationId + ".commit", StorageContentCommitCodec.encode(record, key), record.vaultId)
        val publication = readHistory(record.vaultId, key, verifyContents = false)
        val confirmed = publication.history
        check(confirmed.records[record.operationId] == record) { "Content commit publication could not be confirmed" }
        if (record.kind == StorageContentCommitKind.File) verifyObject(publication.scan, record)
        val head = confirmed.head(record.entryId)
        if (head != null && head.kind != StorageContentCommitKind.Delete) confirmed.path(head)
        if (record.kind != StorageContentCommitKind.Delete) check(confirmed.heads.values.flatten().none {
            it.entryId != record.entryId && it.kind != StorageContentCommitKind.Delete &&
                it.parentId == record.parentId && it.name == record.name
        }) { "Concurrent entries have the same logical name; preserve both and resolve the conflict" }
    }

    suspend fun verifyObject(record: StorageContentCommit) {
        record.requireValid()
        check(record.kind == StorageContentCommitKind.File) { "Only file records have ciphertext objects" }
        val current = scan()
        requireUniqueCandidate(current, record.vaultId)
        verifyObject(current, record)
    }

    private fun requireObject(scan: Scan, objectId: String, length: Long): NetworkFileEntry =
        checkNotNull(scan.entries.singleOrNull { it.name == objectId + ".object" && it.size == length }) {
            "Committed ciphertext object is missing or has changed length"
        }

    private suspend fun verifyObject(scan: Scan, record: StorageContentCommit) {
        check(matchesObject(scan, record.objectId, record.ciphertextLength, record.ciphertextDigest)) {
            "Committed ciphertext object is missing, damaged or changed"
        }
    }

    suspend fun objectMatches(objectId: String, length: Long, digest: String): Boolean =
        matchesObject(scan(), objectId, length, digest)

    private suspend fun matchesObject(scan: Scan, objectId: String, length: Long, digest: String): Boolean {
        val entry = scan.entries.singleOrNull { it.name == objectId + ".object" } ?: return false
        if (entry.size != length) return false
        val reader = provider as? ChunkReadableNetworkClient
            ?: error("This execution host cannot verify ciphertext objects")
        val hash = HashingSink.sha256(blackholeSink())
        var size = 0L
        try {
            check(reader.downloadForContentVerification(child(checkNotNull(scan.directory), entry), length) { bytes, _ ->
                storageResult<Unit> {
                    currentCoroutineContext().ensureActive()
                    check(bytes.size.toLong() <= length - size) { "Ciphertext object length changed" }
                    hash.write(Buffer().write(bytes), bytes.size.toLong())
                    size += bytes.size
                }
            }.getOrThrow()) { "Ciphertext object verification failed" }
            return size == length && hash.hash.hex() == digest
        } finally { hash.close() }
    }

    suspend fun inspect(knownVaultId: String = ""): String? {
        val current = scan()
        requireUniqueCandidate(current)
        if (current.candidates.isEmpty()) {
            if (knownVaultId.isNotEmpty()) throw StorageContentException(StorageContentErrorCode.DamagedLocation,
                "Known password location metadata is missing")
            if (current.entries.isNotEmpty()) {
                val pending = settings.getStringOrNull(pendingName)?.let(Base64::decode)
                val pendingId = pending?.let(StorageContentPasswordLocation::vaultId)
                if (pendingId == null || current.entries.any { it.name != pendingId + ".root" }) {
                    throw StorageContentException(StorageContentErrorCode.DamagedLocation,
                        "Incomplete password location material is present")
                }
            }
            return null
        }
        val candidate = current.candidates.single()
        val descriptor = readBytes(current, candidate, StorageContentPasswordLocation.ENCODED_BYTES)
        val id = StorageContentPasswordLocation.vaultId(descriptor)
        val root = current.roots.singleOrNull { it.name == id + ".root" }
        if (candidate.name != id + ".location" || knownVaultId.isNotEmpty() && knownVaultId != id ||
            root == null || root.size !in 48..(64 * 1024L + 48)) {
            throw StorageContentException(StorageContentErrorCode.DamagedLocation,
                "Password location root or identity is missing or changed")
        }
        return id
    }

    suspend fun initializeOrUnlock(password: CharArray, knownVaultId: String = ""): StorageContentPasswordLocation.Unlocked {
        check(provider is ChunkReadableNetworkClient) {
            "This execution host cannot read bounded password location metadata"
        }
        val initial = scan()
        val pending = settings.getStringOrNull(pendingName)?.let(Base64::decode)
        val pendingId = pending?.let(StorageContentPasswordLocation::vaultId)
        requireUniqueCandidate(initial, pendingId)
        if (initial.candidates.isNotEmpty()) {
            val unlocked = unlock(initial, password, knownVaultId)
            if (pendingId == unlocked.vaultId) settings.remove(pendingName)
            return unlocked
        }
        check(knownVaultId.isEmpty()) { "Known password location metadata is missing; do not initialize a replacement" }
        check(initial.entries.isEmpty() || pending != null && initial.entries.all { it.name.endsWith(".root") }) {
            "An incomplete password location already exists; preserve its initialization material"
        }
        val descriptor = pending ?: run {
            check(provider.effectiveCapabilities().getOrThrow().write) { "This location is read-only" }
            val key = StorageContentEncryptionV1.newKey()
            try { StorageContentPasswordLocation.create(Uuid.random().toString(), key, password) }
            finally { key.fill(0) }
        }.also { settings.putString(pendingName, Base64.encode(it)) }
        val unlocked = StorageContentPasswordLocation.unlock(descriptor, password)
        var accepted = false
        try {
            val vaultId = unlocked.vaultId
            requireUniqueCandidate(initial, vaultId)
            check(provider.effectiveCapabilities().getOrThrow().write) { "This location is read-only" }
            val directory = initial.directory ?: createDirectory()
            val before = scan()
            requireUniqueCandidate(before, vaultId)
            val rootRecord = StorageContentCommit(
                vaultId = vaultId, operationId = vaultId, entryId = vaultId, parentId = "", name = "",
                kind = StorageContentCommitKind.Root, ciphertextDigest = digest(descriptor))
            writeImmutable(directory, "$vaultId.root", StorageContentCommitCodec.encode(rootRecord, unlocked.key))
            val beforePublication = scan()
            requireUniqueCandidate(beforePublication, vaultId)
            writeImmutable(directory, "$vaultId.location", descriptor)
            val published = scan()
            requireUniqueCandidate(published, vaultId)
            check(published.candidates.size == 1) { "Password location publication is not yet visible" }
            verifyRoot(published, descriptor, unlocked)
            settings.remove(pendingName)
            accepted = true
            return unlocked
        } finally { if (!accepted) unlocked.key.fill(0) }
    }

    suspend fun unlock(password: CharArray, knownVaultId: String): StorageContentPasswordLocation.Unlocked =
        unlock(scan(), password, knownVaultId)

    private suspend fun unlock(scan: Scan, password: CharArray, knownVaultId: String): StorageContentPasswordLocation.Unlocked {
        requireUniqueCandidate(scan)
        check(scan.candidates.size == 1) { "Password location descriptor is missing" }
        val candidate = scan.candidates.single()
        val descriptor = readBytes(scan, candidate, StorageContentPasswordLocation.ENCODED_BYTES)
        val vaultId = StorageContentPasswordLocation.vaultId(descriptor)
        check(candidate.name == "$vaultId.location" && (knownVaultId.isEmpty() || knownVaultId == vaultId)) {
            "Password location identity changed"
        }
        val unlocked = StorageContentPasswordLocation.unlock(descriptor, password)
        var accepted = false
        try {
            verifyRoot(scan, descriptor, unlocked)
            val verified = scan()
            requireUniqueCandidate(verified, vaultId)
            check(verified.candidates.size == 1) { "Password location metadata disappeared during unlock" }
            accepted = true
            return unlocked
        } finally { if (!accepted) unlocked.key.fill(0) }
    }

    private suspend fun verifyRoot(scan: Scan, descriptor: ByteArray, unlocked: StorageContentPasswordLocation.Unlocked,
        bodies: Map<String, ByteArray> = emptyMap()): ByteArray {
        val rootRecord = scan.roots.singleOrNull { it.name == unlocked.vaultId + ".root" }
            ?: error("Password location root is missing; do not initialize a replacement")
        val bytes = bodies[rootRecord.name] ?: readBytes(scan, rootRecord, 64 * 1024 + 48)
        val record = StorageContentCommitCodec.decode(bytes, unlocked.key, unlocked.vaultId)
        check(record.kind == StorageContentCommitKind.Root && record.operationId == unlocked.vaultId &&
            record.ciphertextDigest == digest(descriptor)) { "Password location root does not authenticate its descriptor" }
        return bytes
    }

    private suspend fun <T, R> List<T>.readInBatches(read: suspend (T) -> R): List<R> = coroutineScope {
        // Bound active streams and child jobs even when the authenticated history is large.
        chunked(4).flatMap { batch -> batch.map { async { read(it) } }.awaitAll() }
    }

    private fun requireUniqueCandidate(scan: Scan, expectedVaultId: String? = null) {
        val identities = (scan.candidates + scan.roots).map { it.name.substringBeforeLast('.') }.toSet()
        if (scan.candidates.size > 1 || scan.roots.size > 1 || identities.size > 1 ||
            expectedVaultId != null && identities.any { it != expectedVaultId }) {
            throw StorageContentInitializationConflictException()
        }
    }

    private suspend fun scan(): Scan {
        currentCoroutineContext().ensureActive()
        val entries = provider.list(root).getOrThrow()
        val rootNames = entries.map { it.name }.toSet()
        if (entries.any { it.name.matches(INTERNAL_NAME) && it.name.endsWith(".location") &&
                it.name.removeSuffix(".location") + ".root" in rootNames }) {
            throw StorageContentException(StorageContentErrorCode.DamagedLocation,
                "This root exposes password location internals; do not initialize a nested location")
        }
        check(entries.none { it.name == ".folderspan-content.json" }) {
            "Unsupported previous encrypted content metadata is present"
        }
        val directories = entries.filter { it.name == NAMESPACE }
        check(directories.size <= 1) { "Ambiguous password location namespace" }
        val directory = directories.singleOrNull() ?: return Scan(null, emptyList())
        check(directory.isDirectory && !directory.isSymbolicLink) { "Password location namespace is not a safe directory" }
        val path = child(root, directory)
        val children = provider.list(path).getOrThrow()
        check(children.map { it.name }.toSet().size == children.size) { "Duplicate password location metadata names" }
        children.forEach {
            check(!it.isDirectory && !it.isSymbolicLink && it.name.matches(INTERNAL_NAME)) { "Unknown password location material" }
            check(Uuid.parse(it.name.substringBeforeLast('.')).toString() == it.name.substringBeforeLast('.')) {
                "Invalid password location material identity"
            }
        }
        return Scan(path, children)
    }

    private suspend fun createDirectory(): StoragePath {
        val path = StoragePath(root.path.trimEnd('/') + "/" + NAMESPACE,
            root.reference?.forPath(root.path, root.path.trimEnd('/') + "/" + NAMESPACE, true))
        val created = provider.createFolder(path)
        val scanned = scan()
        if (scanned.directory != null) return scanned.directory
        created.getOrThrow()
        error("Password location directory creation could not be verified")
    }

    private suspend fun writeImmutable(directory: StoragePath, name: String, bytes: ByteArray, vaultId: String = name.substringBeforeLast('.')) {
        val before = scan()
        requireUniqueCandidate(before, vaultId)
        val existing = before.entries.singleOrNull { it.name == name }
        if (existing != null) {
            check(readBytes(before, existing, bytes.size).contentEquals(bytes)) {
                "Existing initialization material differs; preserve it instead of overwriting"
            }
            return
        }
        var supplied = false
        check(provider.uploadFromSource(StoragePath(directory.path + "/" + name,
            directory.reference?.forPath(directory.path, directory.path + "/" + name, true)), bytes.size.toLong()) {
            if (supplied) null else bytes.copyOf().also { supplied = true }
        }.getOrThrow()) { "Password location material upload failed" }
        val after = scan()
        requireUniqueCandidate(after, vaultId)
        val stored = after.entries.singleOrNull { it.name == name }
            ?: error("Password location publication is not yet visible")
        check(readBytes(after, stored, bytes.size).contentEquals(bytes)) { "Password location publication changed or was truncated" }
    }

    private suspend fun readBytes(scan: Scan, entry: NetworkFileEntry, limit: Int): ByteArray {
        require(entry.size in 0..limit.toLong()) { "Password location metadata exceeds its bounded format" }
        val reader = provider as? ChunkReadableNetworkClient
            ?: error("This execution host cannot read bounded password location metadata")
        val output = Buffer()
        check(reader.downloadForContentVerification(child(checkNotNull(scan.directory), entry), entry.size) { bytes, _ ->
            storageResult<Unit> {
                currentCoroutineContext().ensureActive()
                check(bytes.size.toLong() <= limit - output.size) { "Password location metadata exceeds its bounded format" }
                output.write(bytes)
            }
        }.getOrThrow()) { "Password location metadata download failed" }
        check(output.size == entry.size) { "Password location metadata is truncated or changed" }
        return output.readByteArray()
    }

    private fun child(parent: StoragePath, entry: NetworkFileEntry) =
        StoragePath(parent.path.trimEnd('/') + "/" + entry.name, entry.reference)

    companion object {
        const val NAMESPACE = ".folderspan-vault"
        private val checkpointLock = TaskRuntimeStoreLock()
        private val CACHE_DOMAIN = "FolderSpan authenticated commit cache v1\u0000".encodeToByteArray()
        private val INTERNAL_NAME = Regex("[0-9a-f-]{36}\\.(location|root|commit|object)")
        private fun digest(bytes: ByteArray) = StorageContentCrypto.sha256(bytes).toByteString().hex()
    }
}

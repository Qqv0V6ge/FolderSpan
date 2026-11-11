package com.folderspan.service.network

import com.folderspan.ui.state.main.TaskRuntimeStoreLock
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Explicit in-memory provider fixture; the host still performs real manifest authentication and CAS. */
@OptIn(ExperimentalUuidApi::class)
internal class TestStorageContentProvider : StorageProvider by UnsupportedNetworkClient("content-fixture") {
    override val capabilities = StorageCapabilities.ReadWrite
    override val contentManifestReadable = true
    override val contentManifestConditionalWrites = true
    private val manifests = mutableMapOf<String, StorageContentManifestSnapshot>()
    private val files = mutableMapOf<String, Pair<String, ByteArray>>()
    private var revision = 0L
    private val lock = TaskRuntimeStoreLock()
    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try { block() } finally { lock.unlock() }
    }
    fun put(path: String, bytes: ByteArray, id: String? = null): NetworkFileEntry = locked {
        val selectedId = id ?: files[path]?.first ?: Uuid.random().toString()
        files[path] = selectedId to bytes.copyOf()
        entry(path, selectedId, bytes)
    }
    private fun entry(path: String, id: String, bytes: ByteArray) = NetworkFileEntry(path.substringAfterLast('/'), path,
        false, bytes.size.toLong(), updatedDate = 200, reference = StorageReference(id, "parent-a"))
    fun removeManifest(anchor: String) = locked { manifests.remove(contentManifestFileName(anchor)) }
    fun damageManifest(anchor: String) = locked {
        val current = checkNotNull(manifests[contentManifestFileName(anchor)])
        manifests[contentManifestFileName(anchor)] = current.copy(bytes = "damaged catalog".encodeToByteArray())
    }
    override suspend fun readContentManifest(anchor: String): Result<StorageContentManifestSnapshot?> = locked {
        Result.success(manifests[contentManifestFileName(anchor)]?.let { it.copy(bytes = it.bytes.copyOf()) })
    }
    override suspend fun compareAndSetContentManifest(anchor: String, expectedRevision: String?, bytes: ByteArray): Result<StorageContentManifestSnapshot> = locked {
        val current = manifests[contentManifestFileName(anchor)]
        if (expectedRevision == null && current != null || expectedRevision != null && current?.revision != expectedRevision)
            Result.failure(StorageContentManifestConflictException())
        else Result.success(StorageContentManifestSnapshot((++revision).toString(), bytes.copyOf()).also { manifests[contentManifestFileName(anchor)] = it })
    }
    override suspend fun contentObjectIdentity(path: StoragePath): Result<String?> = locked {
        val id = files[path.path]?.first
        if (!path.reference?.id.isNullOrBlank() && path.reference?.id != id)
            Result.failure(IllegalStateException("Fixture stable identity changed")) else Result.success(id)
    }
    override suspend fun stat(path: StoragePath): Result<NetworkFileEntry> = locked {
        files[path.path]?.let { (id, bytes) -> Result.success(entry(path.path, id, bytes)) }
            ?: Result.failure(StorageEntryNotFoundException())
    }
    override suspend fun download(remotePath: StoragePath, localPath: String, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> {
        val bytes = locked { files[remotePath.path]?.second?.copyOf() }
            ?: return Result.failure(StorageEntryNotFoundException())
        FileSystem.SYSTEM.write(localPath.toPath()) { write(bytes) }
        return Result.success(true)
    }
    override suspend fun rename(path: StoragePath, newPath: String): Result<Boolean> = locked {
        val old = files.remove(path.path) ?: return@locked Result.failure(StorageEntryNotFoundException())
        check(newPath !in files)
        files[newPath] = old
        Result.success(true)
    }
}

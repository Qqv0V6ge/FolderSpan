package com.folderspan.service.network

import kotlinx.coroutines.yield
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/** Ordinary list/stream store with no CAS, rename, native version or stable-ID support. */
internal class TestStorageContentVaultStore {
    val files = mutableMapOf<String, ByteArray>()
    val directories = mutableSetOf("/")
    var writes = 0
    fun snapshot() = files.mapValues { it.value.toList() }

    fun provider(hidden: MutableSet<String> = mutableSetOf(),
        afterWrite: suspend (String) -> Unit = {}, beforeRead: suspend (String) -> Unit = {}): StorageProvider =
        TestStorageContentVaultProvider(this, hidden, afterWrite, beforeRead)
}

private class TestStorageContentVaultProvider(
    private val store: TestStorageContentVaultStore,
    private val hidden: Set<String>,
    private val afterWrite: suspend (String) -> Unit,
    private val beforeRead: suspend (String) -> Unit,
) : StorageProvider by UnsupportedNetworkClient("vault-fixture"), ChunkReadableNetworkClient {
    override val capabilities = StorageCapabilities.ReadWrite
    override suspend fun effectiveCapabilities() = Result.success(capabilities)

    override suspend fun list(path: StoragePath, requestId: String?, batchId: String?) = storageResult {
        check(path.path in store.directories) { "Fixture directory is missing" }
        val prefix = path.path.trimEnd('/') + "/"
        val directories = store.directories.filter { it != path.path && it.startsWith(prefix) &&
            '/' !in it.removePrefix(prefix) }.map { NetworkFileEntry(it.substringAfterLast('/'), it, true) }
        directories + store.files.filterKeys { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) &&
            it !in hidden }.map { (name, bytes) ->
            NetworkFileEntry(name.substringAfterLast('/'), name, false, bytes.size.toLong())
        }
    }

    override suspend fun list(path: String, requestId: String?, batchId: String?) =
        list(StoragePath(path), requestId, batchId)

    override suspend fun stat(path: StoragePath): Result<NetworkFileEntry> = storageResult {
        when {
            path.path in store.directories -> NetworkFileEntry(path.path.substringAfterLast('/'), path.path, true)
            path.path in store.files -> NetworkFileEntry(path.path.substringAfterLast('/'), path.path, false,
                store.files.getValue(path.path).size.toLong())
            else -> throw StorageEntryNotFoundException()
        }
    }

    override suspend fun rename(path: StoragePath, newPath: String): Result<Boolean> = storageResult {
        check(path.path in store.files && newPath !in store.files && newPath !in store.directories)
        check(newPath.substringBeforeLast('/', "/").ifEmpty { "/" } in store.directories)
        store.files[newPath] = store.files.remove(path.path)!!
        store.writes++
        true
    }
    override suspend fun rename(path: String, newPath: String) = rename(StoragePath(path), newPath)
    override suspend fun move(from: StoragePath, to: StoragePath) = rename(from, to.path)

    override suspend fun delete(path: StoragePath, isDirectory: Boolean): Result<Boolean> = storageResult {
        check(!isDirectory) { "Recursive directory deletion is forbidden in this fixture" }
        check(store.files.remove(path.path) != null)
        store.writes++
        true
    }
    override suspend fun delete(path: String, isDirectory: Boolean) = delete(StoragePath(path), isDirectory)
    override suspend fun deleteEmptyDirectory(path: StoragePath): Result<Boolean> = storageResult {
        check(path.path != "/" && path.path in store.directories)
        val prefix = path.path.trimEnd('/') + "/"
        check(store.files.keys.none { it.startsWith(prefix) } &&
            store.directories.none { it != path.path && it.startsWith(prefix) })
        store.directories.remove(path.path)
        store.writes++
        true
    }
    override suspend fun deleteEmptyDirectory(path: String) = deleteEmptyDirectory(StoragePath(path))

    override suspend fun download(remotePath: StoragePath, localPath: String, size: Long,
        onProgress: (Long, Long) -> Unit): Result<Boolean> = storageResult {
        val bytes = store.files[remotePath.path] ?: throw StorageEntryNotFoundException()
        check(bytes.size.toLong() == size)
        FileSystem.SYSTEM.write(localPath.toPath()) { write(bytes) }
        onProgress(size, size)
        true
    }
    override suspend fun download(remotePath: String, localPath: String, size: Long,
        onProgress: (Long, Long) -> Unit) = download(StoragePath(remotePath), localPath, size, onProgress)

    override suspend fun createFolder(path: StoragePath) = storageResult {
        yield()
        check(path.path !in store.files)
        store.directories.add(path.path)
        true
    }

    override suspend fun upload(localPath: String, remotePath: StoragePath, size: Long,
        onProgress: (Long, Long) -> Unit) = FileSystem.SYSTEM.source(localPath.toPath()).buffer().use { source ->
        uploadFromSource(remotePath, size, onProgress) {
            if (source.exhausted()) null else source.readByteArray(minOf(64 * 1024L, source.buffer.size.takeIf { it > 0 } ?: 1))
        }
    }

    override suspend fun uploadFromSource(remotePath: StoragePath, size: Long,
        onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?) = storageResult {
        yield()
        val output = Buffer()
        while (true) {
            val chunk = readChunk() ?: break
            check(chunk.isNotEmpty() && output.size + chunk.size <= size)
            output.write(chunk)
        }
        check(output.size == size)
        store.files[remotePath.path] = output.readByteArray()
        store.writes++
        afterWrite(remotePath.path)
        true
    }

    override suspend fun downloadByChunks(remotePath: StoragePath, size: Long,
        onChunk: suspend (ByteArray, Long) -> Result<Unit>) = read(remotePath.path, onChunk)

    override suspend fun downloadByChunks(remotePath: String, size: Long,
        onChunk: suspend (ByteArray, Long) -> Result<Unit>) = read(remotePath, onChunk)

    private suspend fun read(path: String, onChunk: suspend (ByteArray, Long) -> Result<Unit>) = storageResult {
        beforeRead(path)
        val bytes = checkNotNull(store.files[path])
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + 64, bytes.size)
            onChunk(bytes.copyOfRange(offset, end), end.toLong()).getOrThrow()
            offset = end
        }
        true
    }
}

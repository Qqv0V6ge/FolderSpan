package com.folderspan.service.network.nfs

import com.folderspan.service.network.*
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class NfsNetworkClient(private val network: NetworkConnectionConfig) : StorageProvider, ChunkReadableNetworkClient {
    override val capabilities = StorageCapabilities.ReadWrite.copy(sync = false)
    private val options = network.extras.nfs.also {
        require(it.version == 3 || it.version == 4) { "Supported NFS versions are v3 and v4.0" }
        nfsComponents(it.exportPath)
        require(it.uid >= 0 && it.gid >= 0 && it.groups.size <= 16 && it.groups.all { group -> group >= 0 }) { "Invalid NFS AUTH_SYS identity" }
        require(it.mountPort in 0..65535) { "Invalid NFS mount port" }
    }
    private val address = Url(if (network.host.contains("://")) network.host else "nfs://${network.host}").also {
        require(it.protocol.name == "nfs" && it.user.isNullOrEmpty() && it.password.isNullOrEmpty()) { "Invalid NFS server" }
        require(it.encodedPath in listOf("", "/") && it.parameters.isEmpty() && it.fragment.isEmpty()) { "Set the NFS export separately" }
    }
    private val port = if (address.specifiedPort > 0) address.specifiedPort else 2049
    private val activeMutex = Mutex()
    private val active = mutableSetOf<NfsRpcConnection>()
    private val identity = Nfs4ClientIdentity()
    private var closed = false

    private suspend fun connect(port: Int): NfsRpcConnection {
        val connection = NfsRpcConnection.connect(address.host, port, options)
        activeMutex.withLock {
            if (closed) { connection.close(); error("NFS connection is closed") }
            active += connection
        }
        return connection
    }
    private suspend fun release(connection: NfsRpcConnection) {
        connection.close()
        withContext(NonCancellable) { activeMutex.withLock { active -= connection } }
    }
    override suspend fun close() {
        activeMutex.withLock {
            closed = true
            active.forEach { it.close() }
            active.clear()
        }
    }

    private suspend fun mount(): ByteArray {
        val mountPort = if (options.mountPort > 0) options.mountPort else {
            val portmapper = connect(111)
            try {
                portmapper.call(100000, 2, 3) { int(100005); int(3); int(6); int(0) }.int().also {
                    check(it in 1..65535) { "NFSv3 mount service is unavailable" }
                }
            } finally { release(portmapper) }
        }
        val connection = connect(mountPort)
        return try {
            val result = connection.call(100005, 3, 1) { string(options.exportPath.ifBlank { "/" }) }
            val status = result.int()
            if (status != 0) throw NfsStatusException(status, "v3 mount")
            val handle = result.opaque(64)
            val count = result.int()
            check(count in 1..64) { "Invalid NFS mount authentication list" }
            val flavors = List(count) { result.int() }
            check(1 in flavors) { "NFS export does not support AUTH_SYS" }
            result.end()
            handle
        } finally { release(connection) }
    }

    private suspend fun <T> withFileSystem(block: suspend (NfsFileSystem) -> T): Result<T> = storageResult {
        val handle = if (options.version == 3) mount() else null
        val connection = connect(port)
        try {
            val fileSystem = if (handle != null) Nfs3FileSystem(connection, handle)
            else Nfs4FileSystem(connection, options.exportPath, identity)
            block(fileSystem)
        } finally { release(connection) }
    }

    override suspend fun list(path: String, requestId: String?, batchId: String?): Result<List<NetworkFileEntry>> =
        withFileSystem { it.list(path) }

    override suspend fun downloadByChunks(remotePath: String, size: Long, onChunk: suspend (ByteArray, Long) -> Result<Unit>): Result<Boolean> =
        withFileSystem { it.read(remotePath, size, onChunk); true }

    override suspend fun download(remotePath: String, localPath: String, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> =
        downloadStorageFile(localPath, onProgress) { consume -> downloadByChunks(remotePath, size, consume) }

    override suspend fun upload(localPath: String, remotePath: String, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> = storageResult {
        coroutineScope {
            val chunks = Channel<ByteArray>(1)
            val producer = launch {
                try {
                    FileUtils.readFileChunks(FileAccessPermission.Allowed, localPath, 64 * 1024L).collect { part ->
                        chunks.send(part.getOrThrow().second)
                    }
                } catch (error: Throwable) {
                    chunks.close(error)
                    throw error
                } finally { chunks.close() }
            }
            try {
                uploadFromSource(remotePath, size, onProgress) {
                    val next = chunks.receiveCatching()
                    next.exceptionOrNull()?.let { throw it }
                    next.getOrNull()
                }.getOrThrow()
            } finally {
                chunks.cancel()
                producer.cancelAndJoin()
            }
        }
    }

    override suspend fun uploadFromSource(remotePath: String, size: Long, onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?): Result<Boolean> =
        withFileSystem { it.write(remotePath, size, onProgress, readChunk); true }

    override suspend fun rename(path: String, newPath: String): Result<Boolean> = withFileSystem { it.rename(path, newPath); true }
    override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> = withFileSystem { it.delete(path, isDirectory); true }
    override suspend fun createFolder(path: String): Result<Boolean> = withFileSystem { it.create(path, true); true }
    override suspend fun createFile(path: String): Result<Boolean> = withFileSystem { it.create(path, false); true }
}

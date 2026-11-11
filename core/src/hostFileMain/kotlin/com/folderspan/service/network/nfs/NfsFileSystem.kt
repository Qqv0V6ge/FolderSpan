package com.folderspan.service.network.nfs

import com.folderspan.service.network.NetworkFileEntry
import com.folderspan.service.network.isUnsafeNetworkPathSegment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class NfsAttributes(val type: Int, val size: Long, val modified: Long) {
    fun regular() { check(type == 1) { "NFS source is not a regular file" } }
    fun directory() { check(type == 2) { "NFS source is not a directory" } }
    fun noLink() { check(type != 5) { "NFS symbolic links are not followed" } }
}
internal data class NfsNode(val handle: ByteArray, val attributes: NfsAttributes)

internal fun nfsComponents(path: String): List<String> {
    require(path.length <= 16 * 1024 && path.none(Char::isISOControl)) { "Invalid NFS path" }
    require(path.isEmpty() || path.startsWith('/')) { "NFS path must be absolute within its export" }
    val parts = path.trim('/').takeIf { it.isNotEmpty() }?.split('/') ?: emptyList()
    require(parts.none(::isUnsafeNetworkPathSegment)) { "Invalid NFS path component" }
    return parts
}

internal fun nfsEntry(parent: String, name: String, attributes: NfsAttributes): NetworkFileEntry {
    require(!isUnsafeNetworkPathSegment(name) && name.none(Char::isISOControl)) { "Invalid NFS entry name" }
    return NetworkFileEntry(
        name = name,
        path = parent.trimEnd('/') + "/" + name,
        isDirectory = attributes.type == 2,
        size = if (attributes.type == 2) -1L else attributes.size,
        updatedDate = attributes.modified,
        isHidden = name.startsWith('.'),
        isSymbolicLink = attributes.type == 5,
        isSymbolicLinkKnown = true,
    )
}

internal abstract class NfsFileSystem(protected val rpc: NfsRpcConnection) {
    abstract suspend fun list(path: String): List<NetworkFileEntry>
    abstract suspend fun read(path: String, size: Long, consume: suspend (ByteArray, Long) -> Result<Unit>)
    abstract suspend fun write(path: String, size: Long, progress: (Long, Long) -> Unit, source: suspend () -> ByteArray?)
    abstract suspend fun create(path: String, directory: Boolean)
    abstract suspend fun delete(path: String, directory: Boolean)
    abstract suspend fun rename(path: String, newPath: String)

    protected fun leaf(path: String): Pair<String, String> {
        val components = nfsComponents(path)
        require(components.isNotEmpty()) { "The NFS export root cannot be modified" }
        return components.dropLast(1).joinToString("/", prefix = "/") to components.last()
    }
}

internal suspend fun writeNfsChunks(
    size: Long,
    source: suspend () -> ByteArray?,
    progress: (Long, Long) -> Unit,
    write: suspend (Long, ByteArray) -> Int,
) {
    require(size >= 0) { "NFS upload requires a known size" }
    var offset = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val bytes = source() ?: break
        check(bytes.size <= size - offset) { "NFS upload length exceeded" }
        var start = 0
        while (start < bytes.size) {
            val end = minOf(bytes.size, start + 64 * 1024)
            val count = write(offset, bytes.copyOfRange(start, end))
            check(count in 1..(end - start)) { "Invalid NFS short write" }
            offset += count
            start += count
            progress(offset, size)
        }
    }
    check(offset == size) { "NFS upload ended early" }
    progress(offset, size)
}

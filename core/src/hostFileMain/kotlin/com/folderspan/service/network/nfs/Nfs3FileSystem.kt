package com.folderspan.service.network.nfs

import com.folderspan.service.network.NetworkFileEntry
import com.folderspan.service.network.isUnsafeNetworkPathSegment

internal class Nfs3FileSystem(rpc: NfsRpcConnection, private val root: ByteArray) : NfsFileSystem(rpc) {
    private suspend fun request(procedure: Int, args: XdrWriter.() -> Unit): XdrReader =
        rpc.call(100003, 3, procedure, args).also {
            val status = it.int()
            if (status != 0) throw NfsStatusException(status, "v3 procedure $procedure")
        }

    private fun XdrReader.attributes(): NfsAttributes {
        val type = int()
        skip(16)
        val size = long()
        check(size >= 0) { "NFS file exceeds the supported size" }
        skip(40)
        val modified = (int().toLong() and 0xffffffffL) * 1000 + (int().toLong() and 0xffffffffL) / 1_000_000
        skip(8)
        return NfsAttributes(type, size, modified)
    }
    private fun XdrReader.optionalAttributes(): NfsAttributes? = if (bool()) attributes() else null
    private fun XdrReader.weakCacheData() { if (bool()) skip(24); optionalAttributes() }

    private suspend fun attributes(handle: ByteArray): NfsAttributes = request(1) { opaque(handle) }.attributes()

    private suspend fun resolve(path: String): NfsNode {
        var handle = root
        var attr = attributes(handle)
        for (component in nfsComponents(path)) {
            attr.directory()
            val response = request(3) { opaque(handle); string(component) }
            handle = response.opaque(64)
            attr = response.optionalAttributes() ?: attributes(handle)
            attr.noLink()
        }
        return NfsNode(handle, attr)
    }

    override suspend fun list(path: String): List<NetworkFileEntry> {
        val node = resolve(path)
        node.attributes.directory()
        var cookie = 0L
        var verifier = ByteArray(8)
        val cookies = mutableSetOf<Long>()
        val result = mutableListOf<NetworkFileEntry>()
        do {
            check(cookies.add(cookie)) { "NFS directory cookie cycle" }
            val response = request(17) {
                opaque(node.handle); long(cookie); fixed(verifier); int(64 * 1024); int(128 * 1024)
            }
            response.optionalAttributes()
            verifier = response.fixed(8)
            var count = 0
            while (response.bool()) {
                response.long()
                val name = response.string()
                cookie = response.long()
                var attr = response.optionalAttributes()
                val handle = if (response.bool()) response.opaque(64) else null
                if (name == "." || name == "..") continue
                require(!isUnsafeNetworkPathSegment(name)) { "Invalid NFS directory entry" }
                if (attr == null) {
                    attr = if (handle != null) attributes(handle)
                    else resolve(path.trimEnd('/') + "/" + name).attributes
                }
                result += nfsEntry(path, name, attr)
                count++
                check(result.size <= 500_000) { "NFS directory exceeds the entry limit" }
            }
            val eof = response.bool()
            response.end()
            if (eof) break
            check(count > 0) { "NFS directory page made no progress" }
        } while (true)
        return result
    }

    override suspend fun read(path: String, size: Long, consume: suspend (ByteArray, Long) -> Result<Unit>) {
        val node = resolve(path)
        node.attributes.regular()
        val total = node.attributes.size
        if (size >= 0) check(total == size) { "NFS file size changed" }
        var offset = 0L
        do {
            val response = request(6) { opaque(node.handle); long(offset); int(64 * 1024) }
            response.optionalAttributes()
            val count = response.int()
            val eof = response.bool()
            val bytes = response.opaque(64 * 1024)
            response.end()
            check(count == bytes.size && count <= total - offset) { "Invalid NFS read length" }
            check(count > 0 || eof) { "NFS read made no progress" }
            if (count > 0 || total == 0L) consume(bytes, total).getOrThrow()
            offset += count
            if (eof) break
        } while (true)
        check(offset == total) { "NFS read ended early" }
    }

    private fun XdrWriter.setAttributes(mode: Int? = null, size: Long? = null) {
        bool(mode != null); if (mode != null) int(mode)
        bool(false); bool(false)
        bool(size != null); if (size != null) long(size)
        int(0); int(0)
    }

    private suspend fun createNode(path: String, directory: Boolean): ByteArray? {
        val (parent, name) = leaf(path)
        val directoryNode = resolve(parent)
        directoryNode.attributes.directory()
        val response = request(if (directory) 9 else 8) {
            opaque(directoryNode.handle); string(name)
            if (!directory) int(1)
            setAttributes(mode = if (directory) 493 else 420, size = if (directory) null else 0L)
        }
        return if (response.bool()) response.opaque(64) else null
    }

    override suspend fun create(path: String, directory: Boolean) { createNode(path, directory) }

    override suspend fun write(path: String, size: Long, progress: (Long, Long) -> Unit, source: suspend () -> ByteArray?) {
        require(size >= 0)
        val node = try {
            resolve(path).also { it.attributes.regular() }
        } catch (missing: NfsStatusException) {
            if (missing.status != 2) throw missing
            val handle = createNode(path, false)
            if (handle == null) resolve(path) else NfsNode(handle, NfsAttributes(1, 0L, 0L))
        }
        request(2) { opaque(node.handle); setAttributes(size = 0L); bool(false) }
        var unstableVerifier: ByteArray? = null
        writeNfsChunks(size, source, progress) { offset, bytes ->
            val response = request(7) {
                opaque(node.handle); long(offset); int(bytes.size); int(2); opaque(bytes)
            }
            response.weakCacheData()
            val count = response.int()
            val committed = response.int()
            check(committed in 0..2) { "Invalid NFS write stability" }
            val verifier = response.fixed(8)
            response.end()
            unstableVerifier?.let { check(it.contentEquals(verifier)) { "NFS server restarted during an unstable write" } }
            if (committed != 2) unstableVerifier = verifier
            count
        }
        unstableVerifier?.let { expected ->
            val response = request(21) { opaque(node.handle); long(0L); int(0) }
            response.weakCacheData()
            check(response.fixed(8).contentEquals(expected)) { "NFS write commit verifier changed" }
            response.end()
        }
    }

    override suspend fun delete(path: String, directory: Boolean) {
        val node = resolve(path)
        node.attributes.noLink()
        check((node.attributes.type == 2) == directory) { "NFS entry type changed" }
        val (parent, name) = leaf(path)
        val handle = resolve(parent).handle
        request(if (directory) 13 else 12) { opaque(handle); string(name) }
    }

    override suspend fun rename(path: String, newPath: String) {
        resolve(path).attributes.noLink()
        val (fromParent, fromName) = leaf(path)
        val (toParent, toName) = leaf(newPath)
        val from = resolve(fromParent).handle
        val to = resolve(toParent).handle
        request(14) { opaque(from); string(fromName); opaque(to); string(toName) }
    }
}

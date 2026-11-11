package com.folderspan.service.network.nfs

import com.folderspan.service.network.NetworkFileEntry
import com.folderspan.service.network.isUnsafeNetworkPathSegment
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

internal class Nfs4ClientIdentity {
    val name = Random.nextBytes(32)
    val verifier = Random.nextBytes(8)
    val mutex = Mutex()
}

internal class Nfs4FileSystem(
    rpc: NfsRpcConnection,
    private val exportPath: String,
    private val identity: Nfs4ClientIdentity,
) : NfsFileSystem(rpc) {
    private class Op(val code: Int, val args: XdrWriter.() -> Unit = {}, val result: XdrReader.() -> Unit = {})
    private data class OpenFile(var handle: ByteArray, var state: ByteArray, var sequence: Int, val clientId: Long)

    private suspend fun compound(vararg operations: Op) {
        require(operations.size <= 64)
        val response = rpc.call(100003, 4, 1) {
            string("FolderSpan"); int(0); int(operations.size)
            operations.forEach { op -> int(op.code); op.args(this) }
        }
        val overall = response.int()
        response.string(1024)
        val count = response.int()
        if (count == 0 && overall != 0) throw NfsStatusException(overall, "v4.0 COMPOUND")
        check(count in 1..operations.size) { "Invalid NFSv4 compound length" }
        repeat(count) { index ->
            val op = operations[index]
            check(response.int() == op.code) { "Unexpected NFSv4 operation" }
            val status = response.int()
            if (status != 0) throw NfsStatusException(status, "v4.0 operation ${op.code}")
            op.result(response)
        }
        check(overall == 0 && count == operations.size) { "Incomplete NFSv4 compound" }
        response.end()
    }

    private fun put(handle: ByteArray) = Op(22, { opaque(handle) })
    private fun getHandle(set: (ByteArray) -> Unit) = Op(10, result = { set(opaque(128)) })
    private fun XdrWriter.attributesMask() { int(2); int((1 shl 1) or (1 shl 4) or (1 shl 20)); int(1 shl 21) }
    private fun XdrReader.bitmap(): List<Int> {
        val size = int()
        check(size in 0..8) { "NFSv4 attribute bitmap exceeds its limit" }
        return List(size) { int() }
    }
    private fun XdrReader.attributes(): NfsAttributes {
        val mask = bitmap()
        val data = XdrReader(opaque(4096))
        var type: Int? = null
        var size = 0L
        var modified = 0L
        mask.forEachIndexed { wordIndex, word ->
            repeat(32) { bit ->
                if ((word and (1 shl bit)) != 0) {
                    when (val attribute = wordIndex * 32 + bit) {
                        1 -> type = data.int()
                        4 -> size = data.long().also { check(it >= 0) { "NFS file exceeds the supported size" } }
                        20 -> data.long()
                        53 -> {
                            val seconds = data.long()
                            val nanoseconds = data.int().toLong() and 0xffffffffL
                            check(nanoseconds < 1_000_000_000L && seconds in 0..(Long.MAX_VALUE / 1000 - 1)) { "Invalid NFS timestamp" }
                            modified = seconds * 1000 + nanoseconds / 1_000_000
                        }
                        else -> error("Unexpected NFSv4 attribute $attribute")
                    }
                }
            }
        }
        data.end()
        return NfsAttributes(checkNotNull(type) { "NFSv4 file type is missing" }, size, modified)
    }
    private fun attrs(set: (NfsAttributes) -> Unit) = Op(9, { attributesMask() }, { set(attributes()) })
    private fun XdrReader.changeInfo() { bool(); long(); long() }

    private suspend fun resolve(path: String): NfsNode {
        val components = nfsComponents(exportPath) + nfsComponents(path)
        var handle = byteArrayOf()
        var attributes: NfsAttributes? = null
        compound(Op(24), getHandle { handle = it }, attrs { attributes = it })
        for (component in components) {
            checkNotNull(attributes).directory()
            compound(put(handle), Op(15, { string(component) }), getHandle { handle = it }, attrs { attributes = it })
            checkNotNull(attributes).noLink()
        }
        return NfsNode(handle, checkNotNull(attributes))
    }

    override suspend fun list(path: String): List<NetworkFileEntry> {
        val directory = resolve(path)
        directory.attributes.directory()
        val result = mutableListOf<NetworkFileEntry>()
        val cookies = mutableSetOf<Long>()
        var cookie = 0L
        var verifier = ByteArray(8)
        var eof = false
        while (!eof) {
            check(cookies.add(cookie)) { "NFSv4 directory cookie cycle" }
            var count = 0
            compound(put(directory.handle), Op(26, {
                long(cookie); fixed(verifier); int(64 * 1024); int(128 * 1024); attributesMask()
            }, {
                verifier = fixed(8)
                while (bool()) {
                    cookie = long()
                    val name = string()
                    val attr = attributes()
                    if (name == "." || name == "..") continue
                    require(!isUnsafeNetworkPathSegment(name)) { "Invalid NFSv4 entry name" }
                    result += nfsEntry(path, name, attr)
                    count++
                    check(result.size <= 500_000) { "NFS directory exceeds the entry limit" }
                }
                eof = bool()
            }))
            check(eof || count > 0) { "NFSv4 directory page made no progress" }
        }
        return result
    }

    private suspend fun clientId(): Long = identity.mutex.withLock {
        var id = 0L
        var confirmation = byteArrayOf()
        compound(Op(35, {
            fixed(identity.verifier); opaque(identity.name)
            int(0); string("tcp"); string("0.0.0.0.0.0"); int(0)
        }, { id = long(); confirmation = fixed(8) }))
        compound(Op(36, { long(id); fixed(confirmation) }))
        id
    }

    private fun XdrWriter.createAttributes(mode: Int, size: Long? = null) {
        int(2); int(if (size != null) 1 shl 4 else 0); int(1 shl 1)
        opaque(XdrWriter().apply { if (size != null) long(size); int(mode) }.bytes())
    }
    private fun XdrReader.ace() { int(); int(); int(); string() }

    private suspend fun <T> withOpen(path: String, write: Boolean, create: Boolean, guarded: Boolean = false, block: suspend (OpenFile) -> T): T {
        val (parentPath, name) = leaf(path)
        val parent = resolve(parentPath)
        parent.attributes.directory()
        val id = clientId()
        val file = OpenFile(byteArrayOf(), byteArrayOf(), 1, id)
        var flags = 0
        var delegation: ByteArray? = null
        compound(put(parent.handle), Op(18, {
            int(0); int(if (write) 2 else 1); int(0)
            long(id); opaque(Random.nextBytes(32))
            int(if (create) 1 else 0)
            if (create) { int(if (guarded) 1 else 0); createAttributes(420, 0L) }
            int(0); string(name)
        }, {
            file.state = fixed(16)
            changeInfo()
            flags = int()
            bitmap()
            when (val kind = int()) {
                0 -> Unit
                1 -> { delegation = fixed(16); bool(); ace() }
                2 -> {
                    delegation = fixed(16); bool()
                    when (int()) { 1 -> long(); 2 -> { int(); int() }; else -> error("Invalid NFSv4 space limit") }
                    ace()
                }
                else -> error("Invalid NFSv4 delegation $kind")
            }
        }), getHandle { file.handle = it })
        var failure: Throwable? = null
        try {
            if ((flags and 2) != 0) {
                compound(put(file.handle), Op(20, { fixed(file.state); int(file.sequence) }, { file.state = fixed(16) }))
                file.sequence++
            }
            delegation?.let { state -> compound(put(file.handle), Op(8, { fixed(state) })) }
            return coroutineScope {
                val renewal = launch {
                    while (isActive) {
                        delay(15_000L)
                        compound(Op(30, { long(file.clientId) }))
                    }
                }
                try { block(file) } finally { renewal.cancelAndJoin() }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    withTimeout(5_000L) {
                        compound(put(file.handle), Op(4, { int(file.sequence); fixed(file.state) }, { fixed(16) }))
                    }
                } catch (closeError: Throwable) {
                    val original = failure
                    if (original == null) throw closeError else original.addSuppressed(closeError)
                }
            }
        }
    }

    override suspend fun read(path: String, size: Long, consume: suspend (ByteArray, Long) -> Result<Unit>) {
        withOpen(path, write = false, create = false) { file ->
            var attributes: NfsAttributes? = null
            compound(put(file.handle), attrs { attributes = it })
            val total = checkNotNull(attributes).also { it.regular() }.size
            if (size >= 0) check(total == size) { "NFS file size changed" }
            var offset = 0L
            var eof = false
            while (!eof) {
                var data = byteArrayOf()
                compound(put(file.handle), Op(25, { fixed(file.state); long(offset); int(64 * 1024) }, {
                    eof = bool(); data = opaque(64 * 1024)
                }))
                check(data.size <= total - offset && (data.isNotEmpty() || eof)) { "Invalid NFSv4 read length" }
                if (data.isNotEmpty() || total == 0L) consume(data, total).getOrThrow()
                offset += data.size
            }
            check(offset == total) { "NFSv4 read ended early" }
        }
    }

    override suspend fun write(path: String, size: Long, progress: (Long, Long) -> Unit, source: suspend () -> ByteArray?) {
        require(size >= 0)
        withOpen(path, write = true, create = true) { file ->
            var unstableVerifier: ByteArray? = null
            writeNfsChunks(size, source, progress) { offset, bytes ->
                var count = 0
                compound(put(file.handle), Op(38, { fixed(file.state); long(offset); int(2); opaque(bytes) }, {
                    count = int()
                    val committed = int()
                    check(committed in 0..2) { "Invalid NFSv4 write stability" }
                    val verifier = fixed(8)
                    unstableVerifier?.let { check(it.contentEquals(verifier)) { "NFS server restarted during an unstable write" } }
                    if (committed != 2) unstableVerifier = verifier
                }))
                count
            }
            unstableVerifier?.let { expected ->
                compound(put(file.handle), Op(5, { long(0L); int(0) }, {
                    check(fixed(8).contentEquals(expected)) { "NFSv4 write commit verifier changed" }
                }))
            }
        }
    }

    override suspend fun create(path: String, directory: Boolean) {
        if (!directory) return withOpen(path, write = true, create = true, guarded = true) {}
        val (parent, name) = leaf(path)
        val node = resolve(parent)
        node.attributes.directory()
        compound(put(node.handle), Op(6, { int(2); string(name); createAttributes(493) }, { changeInfo(); bitmap() }))
    }

    override suspend fun delete(path: String, directory: Boolean) {
        val node = resolve(path)
        node.attributes.noLink()
        check((node.attributes.type == 2) == directory) { "NFS entry type changed" }
        val (parent, name) = leaf(path)
        val handle = resolve(parent).handle
        compound(put(handle), Op(28, { string(name) }, { changeInfo() }))
    }

    override suspend fun rename(path: String, newPath: String) {
        resolve(path).attributes.noLink()
        val (fromPath, fromName) = leaf(path)
        val (toPath, toName) = leaf(newPath)
        val from = resolve(fromPath).handle
        val to = resolve(toPath).handle
        compound(put(from), Op(32), put(to), Op(29, { string(fromName); string(toName) }, { changeInfo(); changeInfo() }))
    }
}

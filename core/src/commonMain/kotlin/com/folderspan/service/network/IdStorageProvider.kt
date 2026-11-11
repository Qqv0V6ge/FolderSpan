package com.folderspan.service.network

import com.folderspan.exception.NetworkUnsupportedException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import strings.AppStrings

internal data class CloudStorageNode(
    val id: String,
    val name: String,
    val directory: Boolean,
    val size: Long = -1L,
    val modified: Long = 0L,
    val parents: List<String> = emptyList(),
    val nativePath: String = "",
    val symbolicLink: Boolean = false,
)

internal abstract class IdStorageProvider(
    protected val config: NetworkConnectionConfig,
    protected val api: StorageHttpApi,
) : StorageProvider, ChunkReadableNetworkClient {
    override val capabilities: StorageCapabilities get() = api.capabilities()
    protected abstract val rootId: String
    override val rootLocation: StoragePath get() = StoragePath("/", StorageReference(id = rootId))
    protected abstract suspend fun node(id: String): CloudStorageNode
    protected abstract suspend fun children(directory: CloudStorageNode): List<CloudStorageNode>
    protected abstract suspend fun read(node: CloudStorageNode, size: Long, onChunk: suspend (ByteArray, Long) -> Result<Unit>)
    protected abstract suspend fun write(parent: CloudStorageNode, name: String, existing: CloudStorageNode?, size: Long, progress: (Long, Long) -> Unit, source: suspend () -> ByteArray?)
    protected abstract suspend fun mkdir(parent: CloudStorageNode, name: String)
    protected abstract suspend fun remove(node: CloudStorageNode)
    protected abstract suspend fun move(node: CloudStorageNode, parent: CloudStorageNode, name: String)

    protected open suspend fun withinRoot(value: CloudStorageNode, root: CloudStorageNode): Boolean {
        if (value.id == root.id) return true
        val pending = ArrayDeque(value.parents)
        val visited = mutableSetOf(value.id)
        repeat(256) {
            if (pending.isEmpty()) return false
            val id = pending.removeFirst()
            if (id == root.id) return true
            check(visited.add(id)) { "Cloud storage parent cycle" }
            pending.addAll(node(id).parents)
        }
        error("Cloud storage parent chain exceeds its limit")
    }

    private fun components(path: String): List<String> {
        require(path.isEmpty() || path.startsWith('/')) { "Storage path must be absolute within its root" }
        require(path.length <= 16 * 1024 && path.none(Char::isISOControl)) { "Invalid storage path" }
        val parts = path.trim('/').takeIf { it.isNotEmpty() }?.split('/') ?: emptyList()
        require(parts.none(::isUnsafeNetworkPathSegment)) { "Invalid storage path component" }
        return parts
    }

    protected suspend fun root(): CloudStorageNode = node(rootId).also { check(it.directory && !it.symbolicLink) { "Storage root is not a directory" } }

    private suspend fun child(parent: CloudStorageNode, name: String): CloudStorageNode {
        check(parent.directory && !parent.symbolicLink) { "Storage parent is not a directory" }
        val matches = children(parent).filter { it.name == name }
        if (matches.isEmpty()) throw StorageEntryNotFoundException()
        check(matches.size == 1) { "Storage path is ambiguous; select the item from the file list" }
        return matches.single()
    }

    protected suspend fun resolve(path: StoragePath, root: CloudStorageNode): CloudStorageNode {
        val reference = path.reference
        val id = reference?.id.orEmpty()
        if (id.isNotEmpty()) {
            val result = if (id == rootId || id == root.id) root else node(id)
            check(withinRoot(result, root)) { "Storage item leaves the configured root" }
            return result
        }
        var parent = root
        var parts = components(path.path)
        if (!reference?.ancestorId.isNullOrEmpty()) {
            val ancestorPath = reference.ancestorPath.trimEnd('/')
            require(path.path == ancestorPath || path.path.startsWith("$ancestorPath/")) { "Storage path leaves its selected ancestor" }
            parent = node(reference.ancestorId)
            check(withinRoot(parent, root)) { "Storage ancestor leaves the configured root" }
            parts = if (path.path == ancestorPath) emptyList() else components("/" + path.path.removePrefix("$ancestorPath/"))
        }
        for (part in parts) {
            currentCoroutineContext().ensureActive()
            parent = child(parent, part)
        }
        return parent
    }

    private suspend fun parent(path: StoragePath, root: CloudStorageNode): Pair<CloudStorageNode, String> {
        val parts = components(path.path)
        require(parts.isNotEmpty()) { "Storage root cannot be modified" }
        val parentPath = parts.dropLast(1).joinToString("/", prefix = "/")
        val reference = path.reference
        val parent = if (!reference?.parentId.isNullOrEmpty()) node(reference.parentId)
        else resolve(StoragePath(parentPath, reference?.copy(id = "", parentId = "")), root)
        check(parent.directory && !parent.symbolicLink && withinRoot(parent, root)) { "Invalid storage parent" }
        return parent to parts.last()
    }

    protected fun entry(value: CloudStorageNode, path: String, parentId: String? = null): NetworkFileEntry {
        require(value.id.length in 1..4096 && value.id.none(Char::isISOControl)) { "Invalid storage item ID" }
        return NetworkFileEntry(
            name = value.name, path = path, isDirectory = value.directory, size = value.size,
            updatedDate = value.modified, isHidden = value.name.startsWith('.'),
            isSymbolicLink = value.symbolicLink, isSymbolicLinkKnown = true,
            reference = StorageReference(value.id, parentId ?: value.parents.singleOrNull().orEmpty()),
        )
    }

    override suspend fun stat(path: StoragePath): Result<NetworkFileEntry> = storageResult {
        entry(resolve(path, root()), path.path)
    }

    override suspend fun list(path: StoragePath, requestId: String?, batchId: String?): Result<List<NetworkFileEntry>> = storageResult {
        val directory = resolve(path, root())
        check(directory.directory && !directory.symbolicLink) { "Storage item is not a directory" }
        val ids = mutableSetOf<String>()
        children(directory).map { value ->
            check(ids.add(value.id)) { "Cloud storage returned a duplicate item ID" }
            entry(value, path.path.trimEnd('/') + "/" + value.name, directory.id)
        }
    }

    override suspend fun downloadByChunks(remotePath: StoragePath, size: Long, onChunk: suspend (ByteArray, Long) -> Result<Unit>): Result<Boolean> = storageResult {
        val value = resolve(remotePath, root())
        check(!value.directory && !value.symbolicLink) { "Storage source is not a regular file" }
        read(value, size, onChunk)
        true
    }

    override suspend fun download(remotePath: StoragePath, localPath: String, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> =
        downloadStorageFile(localPath, onProgress) { consume -> downloadByChunks(remotePath, size, consume) }

    override suspend fun upload(localPath: String, remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> = storageResult {
        uploadLocalStorageFile(localPath, size) { length, source -> uploadFromSource(remotePath, length, onProgress, source) }.getOrThrow()
    }

    protected suspend fun uploadTarget(remotePath: StoragePath): Triple<CloudStorageNode, String, CloudStorageNode?> {
        check(capabilities.write) { "Storage account is read-only" }
        val root = root()
        val reference = remotePath.reference
        return if (!reference?.id.isNullOrEmpty()) {
            val existing = resolve(remotePath, root)
            check(!existing.directory && !existing.symbolicLink) { "Storage destination is not a regular file" }
            val currentParentId = existing.parents.singleOrNull() ?: reference.parentId
            val parent = if (currentParentId.isNotEmpty()) node(currentParentId) else parent(StoragePath(remotePath.path, reference), root).first
            check(parent.directory && withinRoot(parent, root)) { "Storage upload parent leaves the configured root" }
            Triple(parent, existing.name, existing)
        } else {
            val (parent, name) = parent(remotePath, root)
            val matches = children(parent).filter { it.name == name }
            check(matches.size <= 1) { "Storage destination is ambiguous; select the item to replace" }
            check(matches.none { it.directory || it.symbolicLink }) { "Storage destination is not a regular file" }
            Triple(parent, name, matches.singleOrNull())
        }
    }

    protected fun uploadBinding(): String = storageConnectionBinding(config, rootId)

    protected val cancelledUploads = mutableSetOf<String>()

    protected fun validateUploadSession(session: StorageUploadSession, backend: String) {
        require(session.secret !in cancelledUploads) { "Upload session has been cancelled" }
        require(session.backend == backend && session.binding == uploadBinding()) { "Upload session belongs to another connection" }
        require(session.status == StorageUploadStatus.UPLOADING || session.status == StorageUploadStatus.UNCERTAIN) {
            "Upload session is no longer writable"
        }
        if (!capabilities.write) throw StorageFeatureException(StorageFeatureAvailability.PERMISSION_DENIED)
    }

    override suspend fun uploadFromSource(remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?): Result<Boolean> = storageResult {
        require(size >= 0) { "Storage upload requires a known size" }
        val (parent, name, existing) = uploadTarget(remotePath)
        write(parent, name, existing, size, onProgress, readChunk)
        true
    }

    override suspend fun rename(path: StoragePath, newPath: String): Result<Boolean> = storageResult {
        check(capabilities.rename) { "Storage account is read-only" }
        val root = root()
        val value = resolve(path, root)
        check(value.id != root.id) { "Storage root cannot be renamed" }
        val reference = path.reference?.forPath(path.path, newPath, value.directory)
        val sameDirectory = path.path.substringBeforeLast('/') == newPath.substringBeforeLast('/')
        val freshParent = value.parents.singleOrNull().takeIf { sameDirectory }
        val target = StoragePath(newPath, freshParent?.let { StorageReference(parentId = it) } ?: reference)
        val (parent, name) = parent(target, root)
        move(value, parent, name)
        true
    }

    override suspend fun move(from: StoragePath, to: StoragePath): Result<Boolean> = storageResult {
        val permissions = effectiveCapabilities().getOrThrow()
        check(permissions.write && permissions.delete && permissions.rename) { "Storage move is unsupported" }
        val sourceParts = components(from.path)
        val targetParts = components(to.path)
        require(sourceParts.isNotEmpty() && targetParts.isNotEmpty() && sourceParts != targetParts) { "Invalid move path" }
        val root = root()
        val value = resolve(from, root)
        check(value.id != root.id && !value.symbolicLink) { "Invalid move source" }
        val (parent, name) = parent(to, root)
        check(!value.directory || !withinRoot(parent, value)) { "Cannot move a directory into itself" }
        check(children(parent).none { it.name == name }) { "Move target already exists" }
        move(value, parent, name)
        true
    }

    override suspend fun delete(path: StoragePath, isDirectory: Boolean): Result<Boolean> = storageResult {
        check(capabilities.delete) { "Storage account is read-only" }
        val root = root()
        val value = resolve(path, root)
        check(value.id != root.id && value.directory == isDirectory) { "Invalid storage deletion target" }
        remove(value)
        true
    }

    override suspend fun createFolder(path: StoragePath): Result<Boolean> = storageResult {
        check(capabilities.write) { "Storage account is read-only" }
        val root = root()
        if (!path.reference?.id.isNullOrEmpty()) {
            val existing = resolve(path, root)
            check(existing.directory && !existing.symbolicLink) { "Storage destination is not a directory" }
        } else {
            val (parent, name) = parent(path, root)
            val matches = children(parent).filter { it.name == name }
            check(matches.size <= 1 && matches.all { it.directory && !it.symbolicLink }) { "Storage directory destination is ambiguous or not a directory" }
            if (matches.isEmpty()) mkdir(parent, name)
        }
        true
    }
    override suspend fun createFile(path: StoragePath): Result<Boolean> =
        uploadFromSource(path, 0L, readChunk = { null })

    override suspend fun copyFile(sourcePath: StoragePath, targetPath: StoragePath): Result<Boolean> =
        Result.failure(NetworkUnsupportedException(AppStrings.network_same_endpoint_directory_copy_requires_file_tasks))

    override suspend fun list(path: String, requestId: String?, batchId: String?) = list(StoragePath(path), requestId, batchId)
    override suspend fun download(remotePath: String, localPath: String, size: Long, onProgress: (Long, Long) -> Unit) = download(StoragePath(remotePath), localPath, size, onProgress)
    override suspend fun upload(localPath: String, remotePath: String, size: Long, onProgress: (Long, Long) -> Unit) = upload(localPath, StoragePath(remotePath), size, onProgress)
    override suspend fun uploadFromSource(remotePath: String, size: Long, onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?) = uploadFromSource(StoragePath(remotePath), size, onProgress, readChunk)
    override suspend fun downloadByChunks(remotePath: String, size: Long, onChunk: suspend (ByteArray, Long) -> Result<Unit>) = downloadByChunks(StoragePath(remotePath), size, onChunk)
    override suspend fun rename(path: String, newPath: String) = rename(StoragePath(path), newPath)
    override suspend fun delete(path: String, isDirectory: Boolean) = delete(StoragePath(path), isDirectory)
    override suspend fun createFolder(path: String) = createFolder(StoragePath(path))
    override suspend fun createFile(path: String) = createFile(StoragePath(path))
    override suspend fun copyFile(sourcePath: String, targetPath: String) = copyFile(StoragePath(sourcePath), StoragePath(targetPath))
    override suspend fun close() = api.close()
}

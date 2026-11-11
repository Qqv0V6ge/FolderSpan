package com.folderspan.service.network

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.StorageContentLocationReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The authenticated hierarchy is independent of physical ciphertext objects and ordinary directory names. */
@OptIn(ExperimentalUuidApi::class)
internal class StorageContentLogicalAccess(
    private val provider: StorageProvider,
    private val reference: StorageContentLocationReference,
    private val access: StorageContentVaultAccess,
    private val key: ByteArray,
) {
    private val root = canonical(reference.root.path)

    private data class View(
        val history: StorageContentHistory,
        val live: List<StorageContentCommit>,
        val bindings: Map<String, StorageContentCommit>,
        val nativeBindings: Map<String, StorageContentCommit>,
    )
    private data class Node(
        val path: String,
        val record: StorageContentCommit? = null,
        val raw: NetworkFileEntry? = null,
        val physical: StoragePath? = null,
        val anchor: StorageContentCommit? = null,
        val ordinaryPath: String = "",
    ) {
        val directory get() = record?.kind in listOf(StorageContentCommitKind.Root, StorageContentCommitKind.Directory) ||
            raw?.isDirectory == true
    }

    fun contains(path: StoragePath): Boolean {
        val value = canonical(path.path)
        return (value == root || value.startsWith(prefix(root))) &&
            (path.reference?.content?.vaultId?.let { it == reference.vaultId } ?: true)
    }

    private suspend fun readView(): View {
        val history = access.metadataHistory(reference.vaultId, key)
        val live = try { history.liveEntries() }
        catch (error: StorageContentException) { throw error }
        catch (_: Exception) { conflict("The authenticated hierarchy has conflicting names or parents") }
        val bindings = mutableMapOf<String, StorageContentCommit>()
        val nativeBindings = mutableMapOf<String, StorageContentCommit>()
        for (id in history.heads.keys) {
            val record = history.head(id) ?: continue
            val binding = record.ordinaryDirectory ?: continue
            val path = physical(binding).path
            if (bindings.put(path, record) != null) conflict("An ordinary directory has multiple authenticated owners")
            val native = binding.reference?.id.orEmpty()
            if (native.isNotEmpty() && (native == reference.root.reference?.id || nativeBindings.put(native, record) != null)) {
                conflict("A native directory has multiple authenticated owners")
            }
        }
        return View(history, live, bindings, nativeBindings)
    }

    suspend fun list(path: StoragePath): List<NetworkFileEntry> {
        val view = readView()
        val node = resolve(path, view) ?: throw StorageEntryNotFoundException()
        if (!node.directory) conflict("The selected entry is not a directory")
        return children(node, view).map { entry(it) }.sortedBy { it.name }
    }

    suspend fun stat(path: StoragePath): NetworkFileEntry? {
        val view = readView()
        return resolve(path, view)?.let(::entry)
    }

    suspend fun resolveDownload(file: FileSimpleInfo): StorageContentDownload {
        val view = readView()
        val node = resolve(StoragePath(file.path, file.storageReference), view) ?: throw StorageEntryNotFoundException()
        if (node.directory || node.raw?.isSymbolicLink == true) conflict("The selected entry is not an ordinary file")
        val metadata = entry(node)
        if (node.raw != null && (file.size != metadata.size ||
                file.updatedDate > 0 && metadata.updatedDate > 0 && file.updatedDate != metadata.updatedDate ||
                file.createdDate > 0 && metadata.createdDate > 0 && file.createdDate != metadata.createdDate)) {
            changed("The ordinary source metadata changed")
        }
        val logical = file.copy(name = metadata.name, path = metadata.path, size = metadata.size,
            updatedDate = metadata.updatedDate, storageReference = metadata.reference)
        return if (node.record != null) StorageContentDownload(logical,
            access.objectPath(node.record.objectId), node.record.ciphertextLength)
        else StorageContentDownload(logical, checkNotNull(node.physical), metadata.size)
    }

    suspend fun prepareFile(target: StoragePath, operationId: String, objectId: String, cipherLength: Long,
        digest: String, plainSize: Long, updatedDate: Long): StorageContentCommit {
        var view = readView()
        val path = logicalPath(target, view)
        val existing = resolve(StoragePath(path), view)
        if (existing?.raw != null || existing?.directory == true) conflict("An ordinary file or directory already occupies the encrypted target")
        existing?.record?.let { access.verifyObject(it) }
        val parent = authenticatedDirectory(parent(path), view)
        view = readView()
        val currentPath = logicalPath(target, view)
        if (currentPath != path || resolve(StoragePath(parent(path)), view)?.record?.entryId != parent.entryId) {
            changed("The upload parent changed during preparation")
        }
        val current = resolve(StoragePath(path), view)
        if (current?.raw != null || current?.directory == true) conflict("The encrypted target changed")
        if (current?.record != existing?.record) changed("The upload target changed during preparation")
        val previous = current?.record
        return StorageContentCommit(vaultId = reference.vaultId, operationId = operationId,
            entryId = previous?.entryId ?: Uuid.random().toString(), parentId = parent.entryId, name = name(path),
            parents = previous?.let { listOf(it.operationId) }.orEmpty(), kind = StorageContentCommitKind.File,
            objectId = objectId, ciphertextLength = cipherLength, ciphertextDigest = digest,
            size = plainSize, updatedDate = updatedDate).also { it.requireValid() }
    }

    suspend fun resolveOrdinaryUpload(target: StoragePath): StoragePath {
        val view = readView()
        val path = logicalPath(target, view)
        val existing = resolve(StoragePath(path), view)
        if (existing?.record != null || existing?.directory == true) conflict("An authenticated entry already occupies the ordinary target")
        val directory = resolve(StoragePath(parent(path)), view) ?: throw StorageEntryNotFoundException()
        if (!directory.directory) conflict("The upload parent is not a directory")
        val backing = backing(directory, materialize = true) ?: error("Ordinary upload has no backing directory")
        return child(backing, name(path))
    }

    suspend fun createDirectory(path: StoragePath, encrypted: Boolean): Boolean {
        val view = readView()
        val logical = logicalPath(path, view)
        if (resolve(StoragePath(logical), view) != null) conflict("The directory target already exists")
        if (encrypted) {
            val parent = authenticatedDirectory(parent(logical))
            access.publish(StorageContentCommit(vaultId = reference.vaultId, operationId = Uuid.random().toString(),
                entryId = Uuid.random().toString(), parentId = parent.entryId, name = name(logical),
                kind = StorageContentCommitKind.Directory, updatedDate = now()), key)
        } else {
            val parent = resolve(StoragePath(parent(logical)), view) ?: throw StorageEntryNotFoundException()
            if (!parent.directory) conflict("The directory parent is not a directory")
            val physical = child(checkNotNull(backing(parent, materialize = true)), name(logical))
            if (!provider.createFolder(physical).getOrThrow()) uncertain("Ordinary directory creation was not confirmed")
        }
        return true
    }

    suspend fun rename(source: StoragePath, target: StoragePath): Boolean {
        var view = readView()
        var selected = resolve(source, view) ?: throw StorageEntryNotFoundException()
        val destination = logicalPath(target, view)
        if (selected.path == destination) return true
        if (selected.path == root || selected.directory && destination.startsWith(prefix(selected.path))) {
            conflict("A directory cannot move into itself or replace the location root")
        }
        if (resolve(StoragePath(destination), view) != null) conflict("The rename target already exists")
        verifyManagedObjects(selected, view)
        if (selected.directory && selected.record == null) {
            authenticatedDirectory(selected.path)
            view = readView()
            selected = checkNotNull(resolve(StoragePath(selected.path), view))
        }
        val record = selected.record
        if (record != null) {
            val parent = authenticatedDirectory(parent(destination))
            val currentView = readView()
            val current = currentView.history.head(record.entryId)
            if (current != record) changed("The rename source changed")
            if (selected.directory) verifyManagedObjects(managed(record, currentView), currentView)
            access.publish(record.copy(operationId = Uuid.random().toString(), parentId = parent.entryId,
                name = name(destination), parents = listOf(record.operationId), updatedDate = now()), key)
        } else {
            if (selected.raw?.isSymbolicLink == true) conflict("Symbolic links cannot be moved through a password location")
            val targetRaw = resolveOrdinaryUpload(StoragePath(destination))
            if (!provider.move(checkNotNull(selected.physical), targetRaw).getOrThrow()) uncertain("Ordinary file rename was not confirmed")
        }
        return true
    }

    suspend fun delete(path: StoragePath, isDirectory: Boolean): Boolean {
        var view = readView()
        var selected = resolve(path, view) ?: throw StorageEntryNotFoundException()
        if (selected.path == root || selected.directory != isDirectory) conflict("The deletion target or type is invalid")
        // Read the complete visible subtree before any destructive operation.
        val plan = mutableMapOf<String, Node>()
        suspend fun collect(node: Node) {
            currentCoroutineContext().ensureActive()
            if (plan.size >= StorageContentHistory.MAX_RECORDS || node.path.length > 16 * 1024 || depth(node.path) > 256) {
                conflict("The deletion subtree exceeds its bounded traversal limit")
            }
            if (plan.put(node.path, node) != null || node.raw?.isSymbolicLink == true) conflict("Unsafe or repeated deletion entry")
            node.record?.takeIf { it.kind == StorageContentCommitKind.File }?.let { access.verifyObject(it) }
            if (node.directory) children(node, view).forEach { collect(it) }
        }
        collect(selected)
        if (selected.directory && selected.record == null) {
            authenticatedDirectory(selected.path)
            view = readView()
            selected = checkNotNull(resolve(StoragePath(selected.path), view))
            plan[selected.path] = selected
        }
        suspend fun remove(expected: Node) {
            currentCoroutineContext().ensureActive()
            val currentView = readView()
            val node = resolve(StoragePath(expected.path), currentView) ?: changed("A deletion entry disappeared")
            sameObserved(expected, node)
            if (node.directory) {
                val visible = children(node, currentView)
                val planned = plan.values.filter { parent(it.path) == node.path }
                if (visible.map { it.path }.toSet() != planned.map { it.path }.toSet()) changed("The deletion subtree changed")
                planned.forEach { remove(it) }
                var latestView = readView()
                var latest = resolve(StoragePath(node.path), latestView) ?: changed("The deletion directory disappeared")
                if (children(latest, latestView).isNotEmpty()) changed("New data appeared during directory deletion")
                if (latest.record == null) {
                    val physical = checkNotNull(latest.physical)
                    val raw = provider.list(physical).getOrThrow()
                    if (raw.isEmpty()) {
                        if (!provider.deleteEmptyDirectory(physical).getOrThrow()) uncertain("Empty directory deletion was not confirmed")
                        return
                    }
                    // A physical child owned by another logical directory must survive this deletion.
                    authenticatedDirectory(latest.path)
                    latestView = readView()
                    latest = checkNotNull(resolve(StoragePath(latest.path), latestView))
                    if (children(latest, latestView).isNotEmpty()) changed("The bound deletion directory changed")
                }
                tombstone(checkNotNull(latest.record))
            } else if (node.record != null) {
                tombstone(node.record)
            } else {
                if (!provider.delete(checkNotNull(node.physical), false).getOrThrow()) uncertain("Ordinary file deletion was not confirmed")
            }
        }
        remove(selected)
        return true
    }

    private suspend fun verifyManagedObjects(selected: Node, view: View) {
        for (record in view.live) {
            currentCoroutineContext().ensureActive()
            if (record.kind != StorageContentCommitKind.File) continue
            val path = managed(record, view).path
            if (path == selected.path || selected.directory && path.startsWith(prefix(selected.path))) {
                access.verifyObject(record)
            }
        }
    }

    private suspend fun tombstone(record: StorageContentCommit) {
        if (record.kind == StorageContentCommitKind.File) access.verifyObject(record)
        val latest = readView().history.head(record.entryId)
        if (latest != record) changed("An authenticated entry changed before deletion")
        access.publish(StorageContentCommit(vaultId = reference.vaultId, operationId = Uuid.random().toString(),
            entryId = record.entryId, parentId = record.parentId, name = record.name,
            parents = listOf(record.operationId), kind = StorageContentCommitKind.Delete,
            updatedDate = now(), ordinaryDirectory = record.ordinaryDirectory), key)
    }

    private fun sameObserved(expected: Node, actual: Node) {
        if (expected.record != null) {
            if (expected.record != actual.record) changed("An authenticated deletion entry changed")
        } else if (expected.raw?.copy(path = "", reference = null) != actual.raw?.copy(path = "", reference = null) ||
            expected.raw?.reference?.id != actual.raw?.reference?.id) changed("An ordinary deletion entry changed")
    }

    private suspend fun authenticatedDirectory(path: String, observed: View? = null): StorageContentCommit {
        var view = observed ?: readView()
        val node = resolve(StoragePath(path), view) ?: throw StorageEntryNotFoundException()
        if (!node.directory || node.raw?.isSymbolicLink == true) conflict("The parent is not a safe directory")
        node.record?.let { return it }
        val parent = authenticatedDirectory(parent(path))
        view = readView()
        val latest = resolve(StoragePath(path), view) ?: changed("The parent directory disappeared")
        latest.record?.let { return it }
        sameObserved(node, latest)
        val raw = checkNotNull(latest.physical)
        val native = raw.reference?.takeIf { it.id.isNotEmpty() }?.let { StorageReference(id = it.id, parentId = it.parentId) }
        val record = StorageContentCommit(vaultId = reference.vaultId, operationId = Uuid.random().toString(),
            entryId = Uuid.random().toString(), parentId = parent.entryId, name = name(path),
            kind = StorageContentCommitKind.Directory, updatedDate = latest.raw?.updatedDate ?: 0,
            ordinaryDirectory = StorageContentDirectoryBinding(relativePhysical(raw.path), native))
        requireAvailableBinding(readView(), raw.path, native?.id.orEmpty(), record.entryId)
        access.publish(record, key)
        return checkNotNull(readView().history.head(record.entryId))
    }

    private suspend fun backing(node: Node, materialize: Boolean = false): StoragePath? {
        node.physical?.let { return it }
        val record = checkNotNull(node.record)
        if (record.kind == StorageContentCommitKind.Root) return reference.root
        record.ordinaryDirectory?.let { return physical(it) }
        if (!materialize) return null
        val view = readView()
        val parent = view.history.head(record.parentId) ?: changed("The backing parent disappeared")
        val parentNode = managed(parent, view)
        val parentRaw = checkNotNull(backing(parentNode, materialize = true))
        val target = child(parentRaw, record.name)
        val existing = provider.list(parentRaw).getOrThrow()
        if (existing.any { it.name == record.name } || target.path in view.bindings) {
            conflict("The virtual directory backing target is already occupied")
        }
        if (!provider.createFolder(target).getOrThrow()) uncertain("Backing directory creation was not confirmed")
        val created = provider.stat(target).getOrThrow()
        if (!created.isDirectory || created.isSymbolicLink) conflict("The directory backing changed")
        val native = created.reference?.takeIf { it.id.isNotEmpty() }?.let { StorageReference(id = it.id, parentId = it.parentId) }
        val createdPath = StoragePath(target.path, created.reference ?: target.reference)
        if (provider.list(createdPath).getOrThrow().isNotEmpty()) conflict("The backing directory contains unobserved ordinary data")
        val latestView = readView()
        val latest = latestView.history.head(record.entryId)
        if (latest != record) changed("The virtual directory changed during backing creation")
        requireAvailableBinding(latestView, target.path, native?.id.orEmpty(), record.entryId)
        val bound = record.copy(operationId = Uuid.random().toString(), parents = listOf(record.operationId),
            ordinaryDirectory = StorageContentDirectoryBinding(relativePhysical(target.path), native))
        access.publish(bound, key)
        return physical(checkNotNull(bound.ordinaryDirectory))
    }

    private suspend fun resolve(path: StoragePath, view: View): Node? {
        val logical = logicalPath(path, view)
        val base = view.history.head(reference.vaultId) ?: conflict("The authenticated root is missing")
        var node = managed(base, view)
        if (logical == root) return verifyNative(path, node)
        for (part in logical.removePrefix(prefix(root)).split('/')) {
            if (!node.directory) return null
            node = children(node, view).singleOrNull { name(it.path) == part } ?: return null
        }
        return verifyNative(path, node)
    }

    private fun verifyNative(path: StoragePath, node: Node): Node {
        val expected = path.reference?.id.orEmpty()
        if (expected.isNotEmpty() && !expected.startsWith("fsp-content:") && !expected.startsWith("fsp-ordinary:")) {
            val actual = node.raw?.reference?.id ?: when (node.record?.kind) {
                StorageContentCommitKind.Root -> reference.root.reference?.id
                StorageContentCommitKind.Directory -> node.record?.ordinaryDirectory?.reference?.id
                else -> null
            }
            if (expected != actual) changed("The ordinary source identity changed")
        }
        return node
    }

    private suspend fun children(directory: Node, view: View): List<Node> {
        if (directory.raw?.isSymbolicLink == true) conflict("Symbolic directory traversal is unsupported")
        val authenticated = directory.record?.let { parent -> view.live.filter { it.parentId == parent.entryId } }.orEmpty()
        val nodes = mutableMapOf<String, Node>()
        val backing = backing(directory)
        if (backing != null) {
            val entries = provider.list(backing).getOrThrow()
            if (entries.map { it.name }.distinct().size != entries.size) conflict("The ordinary directory listing is ambiguous")
            for (raw in entries) {
                validateName(raw.name)
                if (raw.name == StorageContentVaultAccess.NAMESPACE) {
                    if (canonical(backing.path) != root || !raw.isDirectory || raw.isSymbolicLink) {
                        conflict("An unknown or replaced password location namespace exists inside this directory")
                    }
                    continue
                }
                if (raw.name == ".folderspan-content.json") conflict("An unsupported content namespace exists in this directory")
                val physical = StoragePath(join(backing.path, raw.name), raw.reference?.copy(content = null))
                val owner = bindingOwner(view, physical.path, raw)
                if (owner != null) {
                    if (!raw.isDirectory || raw.isSymbolicLink) conflict("An authenticated directory backing was replaced")
                    requireBindingIdentity(owner, raw)
                    if (owner.kind == StorageContentCommitKind.Delete) requireDeletedBackingEmpty(physical, view, mutableSetOf())
                    continue
                }
                val anchor = directory.record ?: checkNotNull(directory.anchor)
                val suffix = listOf(directory.ordinaryPath, raw.name).filter(String::isNotEmpty).joinToString("/")
                nodes[raw.name] = Node(join(directory.path, raw.name), raw = raw, physical = physical,
                    anchor = anchor, ordinaryPath = suffix)
            }
        }
        for (record in authenticated) {
            if (nodes.put(record.name, managed(record, view)) != null) conflict("An ordinary and authenticated entry have the same logical name")
        }
        return nodes.values.toList()
    }

    private suspend fun requireDeletedBackingEmpty(path: StoragePath, view: View, visited: MutableSet<String>, depth: Int = 0) {
        if (depth > 256 || !visited.add(path.path) || visited.size > StorageContentHistory.MAX_RECORDS) conflict("Repeated deleted backing directory")
        val entries = provider.list(path).getOrThrow()
        if (entries.map { it.name }.distinct().size != entries.size) conflict("An ordinary directory listing is ambiguous")
        for (entry in entries) {
            validateName(entry.name)
            val raw = StoragePath(join(path.path, entry.name), entry.reference?.copy(content = null))
            val owner = bindingOwner(view, raw.path, entry)
            if (owner == null || !entry.isDirectory || entry.isSymbolicLink) {
                conflict("New ordinary data appeared in a deleted authenticated directory")
            }
            requireBindingIdentity(owner, entry)
            if (owner.kind == StorageContentCommitKind.Delete) requireDeletedBackingEmpty(raw, view, visited, depth + 1)
        }
    }

    private fun bindingOwner(view: View, path: String, raw: NetworkFileEntry): StorageContentCommit? {
        val native = raw.reference?.id.orEmpty()
        if (native.isNotEmpty() && native == reference.root.reference?.id) conflict("An ordinary child aliases the location root")
        return view.bindings[path] ?: native.takeIf(String::isNotEmpty)?.let { view.nativeBindings[it] }
    }

    private fun requireAvailableBinding(view: View, path: String, nativeId: String, entryId: String) {
        val owners = listOfNotNull(view.bindings[path], nativeId.takeIf(String::isNotEmpty)?.let { view.nativeBindings[it] })
        if (owners.any { it.entryId != entryId } || nativeId.isNotEmpty() && nativeId == reference.root.reference?.id) {
            conflict("The ordinary directory is already bound to another authenticated entry")
        }
    }

    private fun requireBindingIdentity(owner: StorageContentCommit, raw: NetworkFileEntry) {
        val expected = owner.ordinaryDirectory?.reference?.id.orEmpty()
        if (expected.isNotEmpty() && expected != raw.reference?.id) {
            conflict("An authenticated directory backing identity was replaced")
        }
    }

    private fun managed(record: StorageContentCommit, view: View) = Node(
        path = if (record.kind == StorageContentCommitKind.Root) root else join(root, view.history.path(record).trimStart('/')),
        record = record)

    private fun entry(node: Node): NetworkFileEntry {
        val record = node.record
        if (record != null) return NetworkFileEntry(name(node.path), node.path, node.directory,
            size = record.size, updatedDate = record.updatedDate, reference = StorageReference(
                id = opaque(record.entryId), parentId = record.parentId.takeIf(String::isNotEmpty)?.let(::opaque).orEmpty(),
                content = StorageContentEntryReference(reference.vaultId, record.entryId, record.operationId)))
        val raw = checkNotNull(node.raw)
        val anchor = checkNotNull(node.anchor)
        val native = raw.reference ?: StorageReference()
        val parentSuffix = node.ordinaryPath.substringBeforeLast('/', "")
        return raw.copy(path = node.path, reference = native.copy(
            id = native.id.ifEmpty { ordinaryOpaque(anchor.entryId, node.ordinaryPath) },
            parentId = native.parentId.ifEmpty {
                if (parentSuffix.isEmpty()) opaque(anchor.entryId) else ordinaryOpaque(anchor.entryId, parentSuffix)
            },
            content = StorageContentEntryReference(reference.vaultId, anchor.entryId, anchor.operationId, node.ordinaryPath)))
    }

    private fun logicalPath(path: StoragePath, view: View): String {
        val identity = path.reference?.content
        val result = if (identity == null) canonical(path.path) else {
            if (identity.vaultId != reference.vaultId) changed("The content reference belongs to another location")
            val anchor = view.history.head(identity.entryId) ?: changed("The authenticated entry disappeared")
            if (anchor.kind == StorageContentCommitKind.Delete) changed("The authenticated entry was deleted")
            if (identity.revisionId.isNotEmpty() && identity.revisionId != anchor.operationId) {
                val prior = view.history.records[identity.revisionId]
                if (prior == null || prior.entryId != anchor.entryId || prior.kind != anchor.kind ||
                    when (anchor.kind) {
                        StorageContentCommitKind.File -> prior.objectId != anchor.objectId ||
                            prior.ciphertextDigest != anchor.ciphertextDigest || prior.ciphertextLength != anchor.ciphertextLength ||
                            prior.size != anchor.size
                        StorageContentCommitKind.Directory, StorageContentCommitKind.Root ->
                            prior.ordinaryDirectory != anchor.ordinaryDirectory
                        StorageContentCommitKind.Delete -> true
                    }) changed("The authenticated content body or directory binding changed")
            }
            val base = managed(anchor, view).path
            if (identity.ordinaryPath.isEmpty()) base else {
                if (anchor.kind !in listOf(StorageContentCommitKind.Root, StorageContentCommitKind.Directory)) {
                    conflict("An ordinary child has no authenticated directory")
                }
                identity.ordinaryPath.split('/').forEach(::validateName)
                val physicalAnchor = when (anchor.kind) {
                    StorageContentCommitKind.Root -> reference.root
                    StorageContentCommitKind.Directory -> anchor.ordinaryDirectory?.let(::physical)
                    else -> null
                }
                val candidate = physicalAnchor?.let { join(it.path, identity.ordinaryPath) }
                val binding = if (identity.revisionId.isNotEmpty() && candidate != null) view.bindings.entries
                    .filter { candidate == it.key || candidate.startsWith(prefix(it.key)) }
                    .maxByOrNull { it.key.length } else null
                if (binding != null) {
                    if (binding.value.kind == StorageContentCommitKind.Delete) changed("The ordinary source directory was deleted")
                    val relocated = managed(binding.value, view).path
                    if (candidate == binding.key) relocated else join(relocated, checkNotNull(candidate).removePrefix(prefix(binding.key)))
                } else join(base, identity.ordinaryPath)
            }
        }
        if (!(result == root || result.startsWith(prefix(root)))) conflict("The path is outside this password location")
        val suffix = if (result == root) "" else result.removePrefix(prefix(root))
        if (suffix.split('/').size > 256) conflict("The logical directory depth exceeds the traversal limit")
        suffix.split('/').filter(String::isNotEmpty).forEach(::validateName)
        if (suffix.split('/').any { it == StorageContentVaultAccess.NAMESPACE || it == ".folderspan-content.json" }) {
            conflict("Reserved password location material cannot be accessed as ordinary content")
        }
        return result
    }

    private fun physical(binding: StorageContentDirectoryBinding) =
        StoragePath(join(root, binding.relativePath), binding.reference ?: reference.root.reference?.forPath(root, join(root, binding.relativePath), true))
    private fun relativePhysical(path: String): String {
        if (!canonical(path).startsWith(prefix(root))) conflict("A directory backing escapes its password location")
        return canonical(path).removePrefix(prefix(root))
    }
    private fun child(parent: StoragePath, name: String): StoragePath {
        validateName(name)
        val path = join(parent.path, name)
        return StoragePath(path, parent.reference?.copy(content = null)?.forPath(parent.path, path, true))
    }
    private fun validateName(name: String) {
        if (name.isBlank() || '/' in name || '\\' in name || name.any(Char::isISOControl) || name == "." || name == "..") {
            conflict("An unsafe ordinary entry name was returned")
        }
        requireSafeNetworkWritePath("/" + name)
    }
    private fun canonical(path: String) = path.replace('\\', '/').trimEnd('/').ifEmpty { "/" }
    private fun depth(path: String) = if (path == root) 0 else path.removePrefix(prefix(root)).split('/').size
    private fun prefix(path: String) = path.trimEnd('/') + "/"
    private fun join(parent: String, child: String) = parent.trimEnd('/') + "/" + child.trimStart('/')
    private fun parent(path: String) = path.substringBeforeLast('/', "/").ifEmpty { "/" }
    private fun name(path: String) = path.substringAfterLast('/').ifEmpty { "/" }
    private fun opaque(entryId: String) = "fsp-content:" + reference.vaultId + ":" + entryId
    private fun ordinaryOpaque(entryId: String, path: String) = "fsp-ordinary:" + reference.vaultId + ":" + entryId + ":" + path
    private fun now() = Clock.System.now().toEpochMilliseconds()
    private fun conflict(message: String): Nothing = throw StorageContentException(StorageContentErrorCode.Conflict, message)
    private fun changed(message: String): Nothing = throw StorageContentException(StorageContentErrorCode.SourceChanged, message)
    private fun uncertain(message: String): Nothing = throw StorageContentException(StorageContentErrorCode.UncertainResult, message)
}

package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException

/** Recovery uses frozen endpoint bindings, including the original root after a task is edited or deleted. */
internal class SyncRecoveryEndpointOperations(
    private val files: SyncEndpointFileOperations,
    private val task: Task,
    private val endpoints: Map<SyncRecoveryEndpoint, DiskBase>,
    private val copyOnDevice: (suspend (DiskBase, FileSimpleInfo, FileSimpleInfo) -> Result<Boolean>)? = null,
) : SyncRecoveryFileOperations {
    private fun desk(endpoint: SyncRecoveryEndpoint): DiskBase =
        checkNotNull(endpoints[endpoint]) { "Recovery endpoint binding is unavailable" }

    private fun relative(endpoint: SyncRecoveryEndpoint, path: String): String {
        val separator = files.endpointSeparator(endpoint.type, desk(endpoint))
        require(path.isNotBlank() && '\u0000' !in path)
        require(path.split('/', '\\').none { it == "." || it == ".." }) { "Invalid recovery path" }
        if (separator != "\\") require('\\' !in path) { "Invalid recovery separator" }
        val root = normalizePathForCompare(endpoint.rootPath, separator)
        val normalized = normalizePathForCompare(path, separator)
        if (normalized == root) return ""
        val prefix = root.trimEnd('/', '\\') + separator
        require(normalized.startsWith(prefix)) { "Recovery path is outside its frozen root" }
        return normalized.removePrefix(prefix).replace(separator, "/").also {
            require(it.split('/').all { part -> part.isNotBlank() }) { "Invalid recovery path" }
        }
    }

    private fun root(endpoint: SyncRecoveryEndpoint) = FileSimpleInfo.pathFileSimpleInfo(endpoint.rootPath).withCopy(
        protocol = toProtocol(endpoint.type), protocolId = endpoint.ref, isDirectory = true,
        storageReference = endpoint.storageReference,
    )

    private suspend fun location(endpoint: SyncRecoveryEndpoint, path: String, createParents: Boolean = false): FileSimpleInfo? {
        currentCoroutineContext().ensureActive()
        val relative = relative(endpoint, path)
        val desk = desk(endpoint)
        val separator = files.endpointSeparator(endpoint.type, desk)
        var current = files.readPathInfo(endpoint.type, endpoint.ref, desk, endpoint.rootPath, root(endpoint)).getOrThrow()
        check(current?.isDirectory == true && !current.isSymbolicLink) { "Recovery root is unavailable" }
        if (relative.isEmpty()) return current
        val parts = relative.split('/')
        for ((index, name) in parts.withIndex()) {
            currentCoroutineContext().ensureActive()
            val parent = checkNotNull(current)
            val childPath = joinPath(parent.path, name, separator)
            val candidate = parent.withCopy(name = name, path = childPath, isDirectory = false)
            current = files.readPathInfo(endpoint.type, endpoint.ref, desk, childPath, candidate).getOrThrow()
            check(current?.isSymbolicLink != true) { "Recovery path contains a symbolic link" }
            if (index == parts.lastIndex) return current
            if (current == null && createParents) {
                check(files.capabilities(desk).getOrThrow().write) { "Directory creation is not permitted" }
                val created = try { files.createDirectory(endpoint.type, desk, childPath, candidate) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Result.failure(error) }
                created.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                // Other writers may create a shared parent first, or its creation response may be lost.
                current = files.readPathInfo(endpoint.type, endpoint.ref, desk, childPath, candidate).getOrThrow()
                if (current == null) throw created.exceptionOrNull() ?: IllegalStateException("Recovery parent creation failed")
                check(!current.isSymbolicLink) { "Recovery path contains a symbolic link" }
            }
            if (current == null) return null
            check(current.isDirectory && !current.isSymbolicLink) { "Recovery parent is not a directory" }
        }
        return current
    }

    private suspend fun state(endpoint: SyncRecoveryEndpoint, file: FileSimpleInfo, compareContents: Boolean): SyncEntryState {
        val desk = desk(endpoint)
        val logical = if (file.isDirectory) file else syncComparisonTargetFile(desk, file)
        return if (!logical.isDirectory && compareContents) {
            readVerifiedSyncEndpointState(files, endpoint.type, endpoint.ref, desk, logical, task).getOrThrow()
        } else logical.syncEntryState()
    }

    override suspend fun read(endpoint: SyncRecoveryEndpoint, path: String, expected: SyncEntryState?): SyncEntryState? =
        location(endpoint, path)?.let { state(endpoint, it, !expected?.contentSha256.isNullOrEmpty()) }

    suspend fun verifiedState(endpoint: SyncRecoveryEndpoint, path: String): SyncEntryState? =
        location(endpoint, path)?.let { state(endpoint, it, compareContents = true) }

    suspend fun children(endpoint: SyncRecoveryEndpoint, path: String): List<FileSimpleInfo> {
        val parent = location(endpoint, path) ?: return emptyList()
        check(parent.isDirectory) { "Recovery namespace is not a directory" }
        val separator = files.endpointSeparator(endpoint.type, desk(endpoint))
        val children = files.listChildren(endpoint.type, endpoint.ref, desk(endpoint), path, parent).getOrThrow()
        check(children.map { it.name }.toSet().size == children.size) { "Recovery namespace has ambiguous names" }
        for (child in children) {
            require(child.name.isNotBlank() && child.name !in listOf(".", "..") && child.name.none { it in "/\\\u0000" })
            check(normalizePathForCompare(child.path, separator) == normalizePathForCompare(joinPath(path, child.name, separator), separator)) {
                "Recovery entry escaped its parent"
            }
        }
        return children
    }

    override suspend fun tree(endpoint: SyncRecoveryEndpoint, path: String, expected: Map<String, SyncEntryState>): Map<String, SyncEntryState> =
        readTree(endpoint, path, expected, verifyContents = false)

    suspend fun verifiedTree(endpoint: SyncRecoveryEndpoint, path: String): Map<String, SyncEntryState> =
        readTree(endpoint, path, emptyMap(), verifyContents = true)

    private suspend fun readTree(endpoint: SyncRecoveryEndpoint, path: String, expected: Map<String, SyncEntryState>, verifyContents: Boolean): Map<String, SyncEntryState> {
        val root = location(endpoint, path) ?: error("Recovery tree disappeared")
        val result = linkedMapOf<String, SyncEntryState>()
        suspend fun collect(file: FileSimpleInfo, relative: String) {
            currentCoroutineContext().ensureActive()
            check(!file.isSymbolicLink) { "Recovery tree contains a symbolic link" }
            check(relative !in result) { "Recovery tree has ambiguous names" }
            result[relative] = state(endpoint, file, verifyContents || !expected[relative]?.contentSha256.isNullOrEmpty())
            if (file.isDirectory) for (child in files.listChildren(endpoint.type, endpoint.ref, desk(endpoint), file.path, file).getOrThrow()) {
                require(child.name.isNotBlank() && child.name !in listOf(".", "..") && child.name.none { it in "/\\\u0000" })
                val childPath = joinPath(file.path, child.name, files.endpointSeparator(endpoint.type, desk(endpoint)))
                check(normalizePathForCompare(child.path, files.endpointSeparator(endpoint.type, desk(endpoint))) ==
                    normalizePathForCompare(childPath, files.endpointSeparator(endpoint.type, desk(endpoint)))) { "Recovery tree escaped its parent" }
                collect(child, if (relative.isEmpty()) child.name else "$relative/${child.name}")
            }
        }
        collect(root, "")
        return result
    }

    override suspend fun ensureParent(endpoint: SyncRecoveryEndpoint, path: String) {
        require(relative(endpoint, path).isNotEmpty()) { "Cannot replace a recovery root" }
        location(endpoint, path, createParents = true)
    }

    override suspend fun createDirectory(endpoint: SyncRecoveryEndpoint, path: String) {
        require(relative(endpoint, path).isNotEmpty()) { "Cannot replace a recovery root" }
        check(location(endpoint, path) == null) { "Recovery directory destination is occupied" }
        val desk = desk(endpoint)
        check(files.capabilities(desk).getOrThrow().write) { "Directory creation is not permitted" }
        val parentPath = parentPathOf(path, files.endpointSeparator(endpoint.type, desk))
        val parent = checkNotNull(location(endpoint, parentPath))
        check(files.createDirectory(endpoint.type, desk, path, parent.withCopy(path = path, isDirectory = true)).getOrThrow())
    }

    override suspend fun move(endpoint: SyncRecoveryEndpoint, from: String, to: String) {
        require(relative(endpoint, from).isNotEmpty() && relative(endpoint, to).isNotEmpty()) { "Cannot move a recovery root" }
        val source = checkNotNull(location(endpoint, from)) { "Recovery move source disappeared" }
        check(location(endpoint, to) == null) { "Recovery move destination is occupied" }
        val desk = desk(endpoint)
        val parent = checkNotNull(location(endpoint, parentPathOf(to, files.endpointSeparator(endpoint.type, desk)))) {
            "Recovery move parent disappeared"
        }
        if (endpoint.type == SyncEndpointType.Device) {
            val copy = checkNotNull(copyOnDevice) { "Shared device copy is unavailable" }
            verifiedSyncDeviceMove(
                readSource = { if (read(endpoint, from, null) == null) null else tree(endpoint, from, emptyMap()) },
                readTarget = { if (read(endpoint, to, null) == null) null else tree(endpoint, to, emptyMap()) },
                copy = { copy(desk, source, root(endpoint).withCopy(path = to, name = fileNameOf(to, desk.pathSeparator),
                    isDirectory = source.isDirectory, size = source.size)).getOrThrow() },
                delete = {
                    val snapshot = tree(endpoint, from, emptyMap())
                    remove(endpoint, from, snapshot.getValue(""), snapshot)
                    true
                },
            ).getOrThrow()
        } else check(files.move(endpoint.type, endpoint.ref, desk, from, to, source,
            parent.withCopy(path = to, isDirectory = source.isDirectory)).getOrThrow()) { "Recovery move failed" }
    }

    override suspend fun remove(endpoint: SyncRecoveryEndpoint, path: String, expected: SyncEntryState, tree: Map<String, SyncEntryState>) {
        require(relative(endpoint, path).isNotEmpty()) { "Cannot remove a recovery root" }
        check(syncEntryMatchesSnapshot(expected, read(endpoint, path, expected))) { "Recovery cleanup state changed" }
        if (expected.isDirectory) {
            require(tree[""]?.isDirectory == true) { "Recovery directory has no frozen tree" }
            val actual = this.tree(endpoint, path, tree)
            check(actual.keys == tree.keys && tree.all { (name, state) -> syncEntryMatchesSnapshot(state, actual[name]) }) {
                "Recovery directory changed before cleanup"
            }
            val separator = files.endpointSeparator(endpoint.type, desk(endpoint))
            for ((name, state) in tree.entries.sortedByDescending { it.key.count { c -> c == '/' } * 2 + if (it.key.isEmpty()) 0 else 1 }) {
                val child = if (name.isEmpty()) path else joinPath(path, name, separator)
                removeOne(endpoint, child, state)
            }
        } else removeOne(endpoint, path, expected)
    }

    private suspend fun removeOne(endpoint: SyncRecoveryEndpoint, path: String, expected: SyncEntryState) {
        val file = checkNotNull(location(endpoint, path)) { "Recovery cleanup path disappeared" }
        check(syncEntryMatchesSnapshot(expected, state(endpoint, file, expected.contentSha256.isNotEmpty()))) { "Recovery cleanup path changed" }
        val result = if (file.isDirectory) files.deleteEmptyDirectory(endpoint.type, endpoint.ref, desk(endpoint), path, file)
            else files.deletePath(endpoint.type, desk(endpoint), path, false, file)
        check(result.getOrThrow()) { "Recovery cleanup failed" }
    }
}

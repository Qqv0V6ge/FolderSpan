package com.folderspan.utils

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.exception.AuthorityException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.min
import kotlin.time.Clock
import strings.AppStrings

interface WebExternalFileSource {
    suspend fun readRange(start: Long, endExclusive: Long): Result<ByteArray>
}

object WebInMemoryFileStore {
    private const val ROOT_PATH = "/"

    private sealed interface Node {
        var name: String
        var createdAt: Long
        var updatedAt: Long
    }

    private data class DirectoryNode(
        override var name: String,
        override var createdAt: Long,
        override var updatedAt: Long,
        val children: MutableMap<String, Node> = mutableMapOf(),
    ) : Node

    private data class FileNode(
        override var name: String,
        override var createdAt: Long,
        override var updatedAt: Long,
        var size: Long,
        var mineType: String,
        var content: ByteArray? = null,
        var source: WebExternalFileSource? = null,
    ) : Node

    private val root = DirectoryNode(name = "", createdAt = now(), updatedAt = now())

    fun list(path: String): Result<List<FileSimpleInfo>> {
        val normalized = normalizePath(path)
        val node = getNode(normalized) ?: return Result.failure(AuthorityException(AppStrings.ui_the_directory_does_not_exist))
        if (node !is DirectoryNode) {
            return Result.failure(AuthorityException(AppStrings.ui_the_directory_does_not_exist))
        }
        val children = node.children.values.map { child ->
            toSimpleInfo(child, joinPath(normalized, child.name))
        }
        return Result.success(children)
    }

    fun traverse(path: String): Flow<Result<List<FileSimpleInfo>>> = flow {
        val normalized = normalizePath(path)
        val node = getNode(normalized)
        if (node == null) {
            emit(Result.failure(AuthorityException(AppStrings.ui_the_directory_does_not_exist)))
            return@flow
        }
        if (node is FileNode) {
            emit(Result.success(listOf(toSimpleInfo(node, normalized))))
            return@flow
        }
        val dir = node as DirectoryNode
        emit(Result.success(dir.children.values.map { child ->
            toSimpleInfo(child, joinPath(normalized, child.name))
        }))
        for (child in dir.children.values) {
            if (child is DirectoryNode) {
                traverse(joinPath(normalized, child.name)).collect { item ->  emit(item) }
            }
        }
    }

    fun exists(path: String): Boolean = getNode(normalizePath(path)) != null

    fun get(path: String): Result<FileSimpleInfo> {
        val normalized = normalizePath(path)
        val node = getNode(normalized) ?: return Result.failure(Exception(AppStrings.ui_not_found_file))
        return Result.success(toSimpleInfo(node, normalized))
    }

    fun getInfo(path: String): Result<FileInfo> {
        val simpleInfo = get(path).getOrElse { item ->  return Result.failure(item) }
        return Result.success(
            FileInfo(
                name = simpleInfo.name,
                description = simpleInfo.description,
                isDirectory = simpleInfo.isDirectory,
                isHidden = simpleInfo.isHidden,
                path = simpleInfo.path,
                mineType = simpleInfo.mineType,
                size = simpleInfo.size,
                permissions = 0,
                user = "",
                userGroup = "",
                createdDate = simpleInfo.createdDate,
                updatedDate = simpleInfo.updatedDate,
                protocol = simpleInfo.protocol,
                protocolId = simpleInfo.protocolId,
            )
        )
    }

    fun ensureDirectory(path: String): Result<Boolean> {
        if (path.trim().isEmpty()) return Result.failure(Exception(AppStrings.ui_directory_error))
        val normalized = normalizePath(path)
        if (normalized == ROOT_PATH) return Result.success(true)
        val segments = normalized.trim('/').split('/').filter { item ->  item.isNotBlank() }
        var current = root
        for (segment in segments) {
            val existing = current.children[segment]
            val next = if (existing is DirectoryNode) {
                existing
            } else {
                val created = DirectoryNode(segment, now(), now())
                current.children[segment] = created
                created
            }
            current = next
        }
        current.updatedAt = now()
        return Result.success(true)
    }

    fun createDirectory(path: String): Result<Boolean> {
        val normalized = normalizePath(path)
        if (normalized == ROOT_PATH) return Result.success(true)
        val (parent, name) = getParentAndName(normalized)
            ?: return Result.failure(Exception(AppStrings.ui_path_does_not_exist))
        val existing = parent.children[name]
        if (existing is DirectoryNode) return Result.success(true)
        if (existing != null) return Result.failure(Exception(AppStrings.ui_creation_failed))
        parent.children[name] = DirectoryNode(name = name, createdAt = now(), updatedAt = now())
        parent.updatedAt = now()
        return Result.success(true)
    }

    fun createFile(path: String): Result<Boolean> {
        val normalized = normalizePath(path)
        if (normalized == ROOT_PATH) return Result.failure(Exception(AppStrings.ui_creation_failed))
        val (parent, name) = getParentAndName(normalized)
            ?: return Result.failure(Exception(AppStrings.ui_path_does_not_exist))
        val existing = parent.children[name]
        if (existing is FileNode) return Result.success(true)
        if (existing != null) return Result.failure(Exception(AppStrings.ui_creation_failed))
        parent.children[name] = FileNode(
            name = name,
            createdAt = now(),
            updatedAt = now(),
            size = 0L,
            mineType = mineTypeFromName(name),
            content = ByteArray(0),
        )
        parent.updatedAt = now()
        return Result.success(true)
    }

    fun deleteDirectory(path: String): Result<Boolean> {
        if (path.trim().isEmpty()) return Result.failure(Exception(AppStrings.ui_directory_error))
        val normalized = normalizePath(path)
        if (normalized == ROOT_PATH) {
            root.children.clear()
            root.updatedAt = now()
            return Result.success(true)
        }
        if (!exists(normalized)) return Result.success(true)
        val removed = removeEntry(normalized)
        return if (removed || !exists(normalized)) {
            Result.success(true)
        } else {
            Result.failure(Exception(AppStrings.ui_failed_delete_directory))
        }
    }

    fun delete(path: String): Result<Boolean> {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return Result.failure(Exception(AppStrings.ui_not_found_file))
        val normalized = normalizePath(trimmed)
        if (!exists(normalized)) {
            return Result.failure(Exception(AppStrings.ui_not_found_file))
        }
        if (normalized == ROOT_PATH) {
            root.children.clear()
            root.updatedAt = now()
            return Result.success(true)
        }
        val removed = removeEntry(normalized)
        return if (removed) Result.success(true) else Result.failure(Exception(AppStrings.ui_delete_failed))
    }

    fun rename(path: String, oldName: String, newName: String): Result<Boolean> {
        val normalizedBasePath = normalizePath(path)
        val baseNode = getNode(normalizedBasePath) as? DirectoryNode
            ?: return Result.failure(Exception(AppStrings.ui_the_directory_does_not_exist))
        val normalizedOldName = normalizeRelativePath(oldName)
        val normalizedNewName = normalizeRelativePath(newName)
        if (normalizedOldName.isEmpty() || normalizedNewName.isEmpty()) {
            return Result.failure(Exception(AppStrings.error_web_file_name_empty))
        }
        if (normalizedOldName.contains('/') || normalizedNewName.contains('/')) {
            return Result.failure(Exception(AppStrings.ui_file_name_invalid))
        }
        if (normalizedOldName == normalizedNewName) return Result.success(true)
        val oldNode = baseNode.children[normalizedOldName]
            ?: return Result.failure(Exception(AppStrings.ui_file_does_not_exist_cannot_rename))
        if (baseNode.children.containsKey(normalizedNewName)) {
            return Result.failure(Exception(AppStrings.ui_the_file_name_is_already_present_and_cannot_be_renamed))
        }
        baseNode.children.remove(normalizedOldName)
        baseNode.children[normalizedNewName] = cloneWithNewName(oldNode, normalizedNewName)
        baseNode.updatedAt = now()
        return Result.success(true)
    }

    fun readFile(path: String): Result<ByteArray> {
        val normalized = normalizePath(path)
        val fileNode = getFileNode(normalized)
            ?: return Result.failure(Exception(AppStrings.ui_file_does_not_exist))
        return getFileContent(fileNode)
    }

    fun hasExternalFileSource(path: String): Boolean {
        val normalized = normalizePath(path)
        return getFileNode(normalized)?.source != null
    }

    fun readFileRange(path: String, start: Long, end: Long): Result<ByteArray> {
        if (start !in 0..end) return Result.failure(Exception(AppStrings.ui_invalid_range))
        val normalized = normalizePath(path)
        val fileNode = getFileNode(normalized)
            ?: return Result.failure(Exception(AppStrings.ui_file_does_not_exist))
        val fileSize = fileNode.size
        if (end > fileSize) return Result.failure(Exception(AppStrings.ui_invalid_range))
        if (start == end) return Result.success(byteArrayOf())
        val bytes = getFileContent(fileNode).getOrElse { item ->  return Result.failure(item) }
        val startInt = start.toInt()
        val endInt = end.toInt()
        return Result.success(bytes.copyOfRange(startInt, endInt))
    }

    fun writeBytes(path: String, fileSize: Long, data: ByteArray, offset: Long): Result<Boolean> {
        if (fileSize < 0 || offset < 0 || fileSize > Int.MAX_VALUE.toLong() || offset > Int.MAX_VALUE.toLong()) {
            return Result.failure(Exception(AppStrings.ui_invalid_range))
        }
        if (!isWriteRangeWithinFile(fileSize, offset, data.size.toLong())) {
            return Result.failure(Exception(AppStrings.ui_invalid_range))
        }
        val normalized = normalizePath(path)
        if (normalized == ROOT_PATH) return Result.failure(Exception(AppStrings.ui_write_failed))
        val (parent, name) = getParentAndName(normalized)
            ?: return Result.failure(Exception(AppStrings.ui_path_does_not_exist))

        val existing = parent.children[name]
        if (existing is DirectoryNode) return Result.failure(Exception(AppStrings.ui_write_failed))
        val node = existing as? FileNode
            ?: FileNode(
                name = name,
                createdAt = now(),
                updatedAt = now(),
                size = 0L,
                mineType = mineTypeFromName(name),
                content = ByteArray(0),
            ).also { created ->
                parent.children[name] = created
            }

        if (fileSize == 0L) {
            node.size = 0L
            node.content = ByteArray(0)
            node.mineType = mineTypeFromName(name)
            node.updatedAt = now()
            parent.updatedAt = now()
            return Result.success(true)
        }

        val fileSizeInt = fileSize.toInt()
        val offsetInt = offset.toInt()
        val targetBuffer = when (val existingBytes = node.content) {
            null -> ByteArray(fileSizeInt)
            else -> {
                if (existingBytes.size == fileSizeInt) {
                    existingBytes
                } else {
                    ByteArray(fileSizeInt).also { resized ->
                        val copyLength = min(existingBytes.size, resized.size)
                        existingBytes.copyInto(resized, endIndex = copyLength)
                    }
                }
            }
        }
        data.copyInto(targetBuffer, destinationOffset = offsetInt)
        node.content = targetBuffer
        node.size = fileSize
        node.mineType = mineTypeFromName(name)
        node.updatedAt = now()
        parent.updatedAt = now()
        return Result.success(true)
    }

    fun writeBytesMetadataOnly(path: String, fileSize: Long, dataSize: Int, offset: Long): Result<Boolean> {
        if (fileSize < 0 || offset < 0 || fileSize > Int.MAX_VALUE.toLong() || offset > Int.MAX_VALUE.toLong()) {
            return Result.failure(Exception(AppStrings.ui_invalid_range))
        }
        if (dataSize < 0) {
            return Result.failure(Exception(AppStrings.ui_invalid_range))
        }
        if (!isWriteRangeWithinFile(fileSize, offset, dataSize.toLong())) {
            return Result.failure(Exception(AppStrings.ui_invalid_range))
        }
        val normalized = normalizePath(path)
        if (normalized == ROOT_PATH) return Result.failure(Exception(AppStrings.ui_write_failed))
        val (parent, name) = getParentAndName(normalized)
            ?: return Result.failure(Exception(AppStrings.ui_path_does_not_exist))

        val existing = parent.children[name]
        if (existing is DirectoryNode) return Result.failure(Exception(AppStrings.ui_write_failed))
        val node = existing as? FileNode
            ?: FileNode(
                name = name,
                createdAt = now(),
                updatedAt = now(),
                size = 0L,
                mineType = mineTypeFromName(name),
                content = null,
            ).also { created ->
                parent.children[name] = created
            }

        node.size = fileSize
        node.content = if (fileSize == 0L) byteArrayOf() else null
        node.mineType = mineTypeFromName(name)
        node.updatedAt = now()
        parent.updatedAt = now()
        return Result.success(true)
    }

    fun readFileLines(path: String): List<String> {
        val bytes = readFile(path).getOrNull() ?: return emptyList()
        val content = runCatching { bytes.decodeToString() }.getOrNull() ?: return emptyList()
        return content.split("\n")
    }

    fun appendToFile(path: String, content: String): Result<Boolean> {
        if (path.trim().isEmpty()) return Result.failure(Exception(AppStrings.ui_path_error))
        val normalized = normalizePath(path)
        if (normalized == ROOT_PATH) return Result.failure(Exception(AppStrings.ui_append_failed))
        val payload = content.encodeToByteArray()
        if (!exists(normalized)) {
            createFile(normalized).getOrElse { item ->  return Result.failure(item) }
        }
        val (parent, name) = getParentAndName(normalized)
            ?: return Result.failure(Exception(AppStrings.ui_path_does_not_exist))
        val existing = parent.children[name]
            ?: return Result.failure(Exception(AppStrings.ui_file_does_not_exist))
        if (existing !is FileNode) return Result.failure(Exception(AppStrings.ui_the_goal_is_not_a_file))

        val baseBytes = when {
            existing.size <= 0L -> ByteArray(0)
            existing.size > Int.MAX_VALUE.toLong() -> return Result.failure(Exception(AppStrings.ui_file_too_large))
            else -> {
                val buffer = ByteArray(existing.size.toInt())
                existing.content?.let { current ->
                    current.copyInto(buffer, endIndex = min(current.size, buffer.size))
                }
                buffer
            }
        }

        val nextSize = baseBytes.size.toLong() + payload.size
        if (nextSize > Int.MAX_VALUE.toLong()) return Result.failure(Exception(AppStrings.ui_file_too_large))
        val merged = ByteArray(nextSize.toInt())
        if (baseBytes.isNotEmpty()) {
            baseBytes.copyInto(merged, endIndex = baseBytes.size)
        }
        if (payload.isNotEmpty()) {
            payload.copyInto(merged, destinationOffset = baseBytes.size)
        }
        existing.content = merged
        existing.size = merged.size.toLong()
        existing.mineType = mineTypeFromName(name)
        existing.updatedAt = now()
        parent.updatedAt = now()
        return Result.success(true)
    }

    fun putFile(
        basePath: String,
        relativePath: String,
        size: Long,
        lastModified: Long?,
        source: WebExternalFileSource? = null,
    ) {
        val normalizedBase = normalizePath(basePath)
        val normalizedRelative = normalizeRelativePath(relativePath)
        if (normalizedRelative.isEmpty()) return
        val fullPath = joinPath(normalizedBase, normalizedRelative)
        val (parentPath, name) = splitPath(fullPath)
        val parent = parentPath?.let { item ->  getOrCreateDirectory(item) } ?: root
        parent.children.remove(name)
        val timestamp = lastModified ?: now()
        parent.children[name] = FileNode(
            name = name,
            createdAt = timestamp,
            updatedAt = timestamp,
            size = size,
            mineType = mineTypeFromName(name),
            content = null,
            source = source,
        )
        parent.updatedAt = now()
    }

    suspend fun readExternalFileRange(path: String, start: Long, endExclusive: Long): Result<ByteArray> {
        if (start !in 0..endExclusive) return Result.failure(Exception(AppStrings.ui_invalid_range))
        val normalized = normalizePath(path)
        val fileNode = getFileNode(normalized)
            ?: return Result.failure(Exception(AppStrings.ui_file_does_not_exist))
        if (endExclusive > fileNode.size) return Result.failure(Exception(AppStrings.ui_invalid_range))
        val source = fileNode.source
            ?: return Result.failure(Exception(AppStrings.ui_the_current_web_file_contains_only_metadata_which_cannot_be_read))
        return source.readRange(start, endExclusive).mapCatching { data ->
            val expectedSize = endExclusive - start
            if (data.size.toLong() != expectedSize) {
                throw IllegalStateException(AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range)
            }
            data
        }
    }

    fun overwriteDirectory(basePath: String, relativePath: String) {
        val normalizedBase = normalizePath(basePath)
        val normalizedRelative = normalizeRelativePath(relativePath)
        if (normalizedRelative.isEmpty()) return
        val fullPath = joinPath(normalizedBase, normalizedRelative)
        removeEntry(fullPath)
        ensureDirectory(fullPath)
    }

    private fun removeEntry(path: String): Boolean {
        val (parentPath, name) = splitPath(path)
        val parent = parentPath?.let { item ->  getNode(item) } as? DirectoryNode ?: return false
        val removed = parent.children.remove(name) != null
        if (removed) {
            parent.updatedAt = now()
        }
        return removed
    }

    private fun getNode(path: String): Node? {
        val normalized = normalizePath(path)
        if (normalized == ROOT_PATH) return root
        val segments = normalized.trim('/').split('/').filter { item ->  item.isNotBlank() }
        var current: DirectoryNode = root
        for ((index, segment) in segments.withIndex()) {
            val child = current.children[segment] ?: return null
            if (index == segments.lastIndex) return child
            if (child !is DirectoryNode) return null
            current = child
        }
        return null
    }

    private fun getOrCreateDirectory(path: String): DirectoryNode {
        val normalized = normalizePath(path)
        if (normalized == ROOT_PATH) return root
        val segments = normalized.trim('/').split('/').filter { item ->  item.isNotBlank() }
        var current = root
        for (segment in segments) {
            val existing = current.children[segment]
            val next = if (existing is DirectoryNode) {
                existing
            } else {
                val created = DirectoryNode(segment, now(), now())
                current.children[segment] = created
                created
            }
            current = next
        }
        current.updatedAt = now()
        return current
    }

    private fun getParentAndName(path: String): Pair<DirectoryNode, String>? {
        val normalized = normalizePath(path)
        if (normalized == ROOT_PATH) return null
        val (parentPath, name) = splitPath(normalized)
        if (name.isBlank()) return null
        val parent = parentPath?.let { item ->  getNode(item) } as? DirectoryNode ?: return null
        return parent to name
    }

    private fun getFileNode(path: String): FileNode? {
        return getNode(path) as? FileNode
    }

    private fun toSimpleInfo(node: Node, path: String): FileSimpleInfo {
        val isRoot = path == ROOT_PATH
        val displayName = if (isRoot) ROOT_PATH else node.name
        val isHidden = !isRoot && node.name.startsWith(".")
        return when (node) {
            is DirectoryNode -> FileSimpleInfo(
                name = displayName,
                description = "",
                isDirectory = true,
                isHidden = isHidden,
                path = path,
                mineType = "",
                size = node.children.size.toLong(),
                createdDate = node.createdAt,
                updatedDate = node.updatedAt,
                protocol = FileProtocol.Local,
                protocolId = "",
                isSymbolicLink = false,
                isSymbolicLinkKnown = true,
            )
            is FileNode -> FileSimpleInfo(
                name = displayName,
                description = "",
                isDirectory = false,
                isHidden = isHidden,
                path = path,
                mineType = node.mineType,
                size = node.size,
                createdDate = node.createdAt,
                updatedDate = node.updatedAt,
                protocol = FileProtocol.Local,
                protocolId = "",
                isSymbolicLink = false,
                isSymbolicLinkKnown = true,
            )
        }
    }

    private fun cloneWithNewName(node: Node, newName: String): Node {
        return when (node) {
            is DirectoryNode -> node.copy(name = newName, updatedAt = now())
            is FileNode -> node.copy(name = newName, updatedAt = now())
        }
    }

    private fun getFileContent(fileNode: FileNode): Result<ByteArray> {
        if (fileNode.size < 0L) return Result.failure(Exception(AppStrings.ui_file_does_not_exist))
        if (fileNode.size == 0L) return Result.success(byteArrayOf())
        if (fileNode.size > Int.MAX_VALUE.toLong()) {
            return Result.failure(Exception(AppStrings.ui_file_too_large))
        }
        val sizeInt = fileNode.size.toInt()
        val content = fileNode.content
            ?: return Result.failure(Exception(AppStrings.ui_the_current_web_file_contains_only_metadata_which_cannot_be_read))
        if (content.size == sizeInt) {
            return Result.success(content.copyOf())
        }
        val resized = ByteArray(sizeInt)
        content.copyInto(resized, endIndex = min(content.size, resized.size))
        return Result.success(resized)
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim().replace('\\', '/')
        if (trimmed.isEmpty() || trimmed == ROOT_PATH) return ROOT_PATH
        var normalized = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        normalized = normalized.replace(Regex("/{2,}"), "/")
        if (normalized.length > 1 && normalized.endsWith("/")) {
            normalized = normalized.dropLast(1)
        }
        return normalized
    }

    private fun normalizeRelativePath(path: String): String {
        val trimmed = path.trim().replace('\\', '/')
        if (trimmed.isEmpty()) return ""
        var normalized = trimmed.trimStart('/')
        normalized = normalized.replace(Regex("/{2,}"), "/")
        if (normalized.length > 1 && normalized.endsWith("/")) {
            normalized = normalized.dropLast(1)
        }
        return normalized
    }

    private fun joinPath(parent: String, child: String): String {
        val normalizedParent = normalizePath(parent)
        val normalizedChild = normalizeRelativePath(child)
        if (normalizedChild.isEmpty()) return normalizedParent
        return if (normalizedParent == ROOT_PATH) "/$normalizedChild" else "$normalizedParent/$normalizedChild"
    }

    private fun splitPath(path: String): Pair<String?, String> {
        val normalized = normalizePath(path)
        if (normalized == ROOT_PATH) return null to ""
        val trimmed = normalized.removePrefix("/")
        val lastSlash = trimmed.lastIndexOf('/')
        return if (lastSlash == -1) {
            ROOT_PATH to trimmed
        } else {
            "/${trimmed.substring(0, lastSlash)}" to trimmed.substring(lastSlash + 1)
        }
    }

    private fun mineTypeFromName(name: String): String {
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == name.length - 1) return ""
        return name.substring(dotIndex).lowercase()
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
}

package com.folderspan.service.webui

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.http.server.linkshare.LinkShareHttpHeader
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.mcp.file.FileEndpointErrorCode
import com.folderspan.service.mcp.file.FileEndpointException
import com.folderspan.service.mcp.file.FileEndpointDirectory
import com.folderspan.service.mcp.file.FileEndpointEntry
import com.folderspan.service.mcp.file.LocalFileEndpointGateway
import com.folderspan.service.mcp.file.normalizeEndpointPath
import com.folderspan.service.mcp.file.toFileSimpleInfo
import com.folderspan.service.mcp.file.validateEndpointLeafName
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileSensitivity
import com.folderspan.utils.FileUtils
import com.folderspan.utils.NoFollowFileChannels
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SensitiveFileAccessPolicy
import com.folderspan.utils.isDropDirectoryWritable
import com.folderspan.utils.isRestrictedAliasFilesystemPath
import com.folderspan.utils.isWriteRangeWithinFile
import com.folderspan.utils.restrictOwnerOnlyPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

actual fun createWebUiPlatform(): WebUiPlatform = JvmWebUiPlatform()

internal val platformJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }
internal inline fun <reified T> platformResponse(value: T): LinkShareHttpResponse =
    LinkShareHttpResponse.text(text = platformJson.encodeToString(value), contentType = "application/json")

internal suspend fun LinkShareHttpRequest.boundedBytes(limit: Int = WEB_UI_FILE_STREAM_CHUNK_BYTES): ByteArray {
    require(body.contentLength == null || body.contentLength!! in 0..limit.toLong()) { "request body too large" }
    val bytes = ByteArray(limit + 1)
    var size = 0
    while (size <= limit) {
        val count = body.read(bytes, size, bytes.size - size)
        if (count < 0) return bytes.copyOf(size)
        if (count == 0) continue
        size += count
    }
    throw IllegalArgumentException("request body too large")
}
internal suspend inline fun <reified T> LinkShareHttpRequest.platformBody(): T =
    platformJson.decodeFromString(boundedBytes().decodeToString())

internal suspend fun checkedPlatformPath(
    path: String,
    allowMissing: Boolean = false,
    rejectSymbolicLinks: Boolean = false,
): String {
    require(path.isNotBlank()) { "path is required" }
    SensitiveFileAccessPolicy.deniedException(path)?.let { throw it }
    require(!isRestrictedAliasFilesystemPath(path, PathUtils.getPathSeparator())) { "restricted filesystem path" }
    val absolute = Path.of(path).toAbsolutePath()
    if (rejectSymbolicLinks) {
        // Inspect before normalization: a link followed by ".." must not disappear from validation.
        // Also inspect the normalized representation when missing components could hide a later link.
        for (representation in listOf(absolute, absolute.normalize()).distinct()) {
            var current = requireNotNull(representation.root)
            for (component in representation) {
                current = current.resolve(component)
                val attributes = try {
                    Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (error: NoSuchFileException) {
                    if (!allowMissing) throw error
                    continue
                }
                if (attributes.isSymbolicLink || attributes.isOther ||
                    (current != representation && !attributes.isDirectory)) {
                    throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "path is not accessible")
                }
            }
        }
    }
    val target = absolute.normalize()
    var existing = target
    if (allowMissing) {
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.parent ?: throw IllegalArgumentException("path cannot be resolved")
        }
    }
    val resolved = existing.toRealPath().resolve(existing.relativize(target)).normalize().toString()
    SensitiveFileAccessPolicy.deniedException(resolved)?.let { throw it }
    require(!isRestrictedAliasFilesystemPath(resolved, PathUtils.getPathSeparator())) { "restricted filesystem path" }
    return if (rejectSymbolicLinks) target.toString() else path
}

class JvmWebUiPlatform : WebUiPlatform {
    private val files = LocalFileEndpointGateway()
    private suspend fun checkedFileEndpointPath(path: String): String = checkedPlatformPath(
        normalizeEndpointPath(path, files.pathSeparator), allowMissing = true, rejectSymbolicLinks = true,
    )

    private class Directory(val cursor: FileEndpointDirectory, val includeDirectoryEntryCount: Boolean) {
        val mutex = Mutex()
    }

    private suspend fun listingEntry(entry: FileEndpointEntry): FileSimpleInfo {
        val file = entry.toFileSimpleInfo()
        if (!file.isDirectory || file.isSymbolicLink || file.sensitivity != FileSensitivity.None) return file
        // Native Desktop/Android lists report 0 when child enumeration fails.
        val count = platformResult {
            val path = Path.of(file.path)
            // The entry comes from an authorized parent listing. Count metadata for roots such as
            // /dev without granting info/content access to those restricted filesystem paths.
            checkedFileEndpointPath(requireNotNull(path.parent).toString())
            SensitiveFileAccessPolicy.deniedException(file.path)?.let { throw it }
            withContext(Dispatchers.IO) {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                require(attributes.isDirectory && !attributes.isSymbolicLink) { "not an ordinary directory" }
                Files.newDirectoryStream(path).use { children ->
                    var count = 0L
                    for (child in children) {
                        currentCoroutineContext().ensureActive()
                        count++
                    }
                    count
                }
            }
        }.getOrDefault(0L)
        return file.withCopy(size = count)
    }

    override suspend fun hostInfo(session: WebUiPlatformSession): WebUiHostInfo {
        val cache = Files.createTempDirectory("folderspan-platform-").toRealPath()
        restrictOwnerOnlyPath(cache, directory = true)
        session.own(cache) { PathUtils.deleteDirectory(FileAccessPermission.Allowed, cache.toString()) }
        return WebUiHostInfo(
            separator = PathUtils.getPathSeparator(), home = PathUtils.getHomePath(),
            cache = cache.toString(), app = PathUtils.getAppPath(),
            roots = PathUtils.getRootPaths(FileAccessPermission.Allowed).getOrThrow(),
            interfaces = com.folderspan.service.http.getNetworkInterfacesInfo(),
            fingerprint = com.folderspan.service.http.tls.currentDeviceTlsFingerprint(),
            publicKey = com.folderspan.service.http.tls.DeviceTlsIdentity.publicKeyPem().orEmpty(),
            compressionCodecs = com.folderspan.service.http.archive.FolderSpanArchivePlatformCompression.supportedCodecs.toList(),
            caseInsensitive = com.folderspan.utils.securityBoundaryCaseInsensitive,
            sensitivePaths = com.folderspan.utils.platformSensitivePathRules().map { WebUiSensitivePath(it.path, it.sensitivity, it.category, it.includeDescendants) },
        )
    }

    override suspend fun handle(request: LinkShareHttpRequest, session: WebUiPlatformSession): LinkShareHttpResponse = try {
        handleOperation(request, session)
    } catch (error: java.nio.file.NoSuchFileException) {
        throw FileEndpointException(FileEndpointErrorCode.NotFound, "path does not exist", error)
    } catch (error: java.io.FileNotFoundException) {
        throw FileEndpointException(FileEndpointErrorCode.NotFound, "path does not exist", error)
    } catch (error: java.nio.file.AccessDeniedException) {
        throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "path access denied", error)
    } catch (error: java.nio.file.FileAlreadyExistsException) {
        throw FileEndpointException(FileEndpointErrorCode.Conflict, "path already exists", error)
    }

    private suspend fun handleOperation(request: LinkShareHttpRequest, session: WebUiPlatformSession): LinkShareHttpResponse {
        if (request.path == "$WEB_UI_PLATFORM_PATH/networks" || request.path.startsWith("$WEB_UI_PLATFORM_PATH/networks/")) return networkPlatformRequest(request, session)
        if (request.path == "$WEB_UI_PLATFORM_PATH/listeners" || request.path.startsWith("$WEB_UI_PLATFORM_PATH/listeners/")) return httpListenerRequest(request, session)
        if (request.path.startsWith("$WEB_UI_PLATFORM_PATH/devices/")) return devicePlatformRequest(request, session)
        if (request.path.startsWith("$WEB_UI_PLATFORM_PATH/http/")) return outboundHttpRequest(request, session)
        if (request.path == "$WEB_UI_PLATFORM_PATH/compression" || request.path == "$WEB_UI_PLATFORM_PATH/compression/finish") return compressionRequest(request, session)
        val operation = request.path.removePrefix(WEB_UI_PLATFORM_PATH)
        val path by lazy { request.query("path").orEmpty() }
        return when (request.method to operation) {
            "GET" to "/files/info" -> {
                val checked = checkedPlatformPath(path, rejectSymbolicLinks = true)
                platformResponse(FileUtils.getFile(FileAccessPermission.Allowed, checked).getOrThrow())
            }
            "GET" to "/files/details" -> platformResponse(files.details(checkedFileEndpointPath(path)).getOrThrow())
            "GET" to "/files/roots" -> platformResponse(files.roots().getOrThrow())
            "GET" to "/files/directory-writable" -> {
                val checked = checkedPlatformPath(path, rejectSymbolicLinks = true)
                platformResponse(isDropDirectoryWritable(checked))
            }
            "GET" to "/files/space" -> {
                checkedPlatformPath(path)
                platformResponse(listOf(FileUtils.totalSpace(FileAccessPermission.Allowed, path), FileUtils.freeSpace(FileAccessPermission.Allowed, path)))
            }
            "GET" to "/files/exists" -> {
                checkedPlatformPath(path, true)
                platformResponse(PathUtils.exists(FileAccessPermission.Allowed, path))
            }
            "GET" to "/files/symlink" -> {
                checkedPlatformPath(path, true)
                platformResponse(PathUtils.isSymbolicLink(FileAccessPermission.Allowed, path))
            }
            "GET" to "/files/canonical" -> {
                checkedPlatformPath(path, true)
                platformResponse(PathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, path, request.query("allowMissing") == "true"))
            }
            "POST" to "/files/boundary" -> {
                val input = request.platformBody<WebUiPathBoundaryRequest>()
                checkedPlatformPath(input.root)
                checkedPlatformPath(input.path, input.allowMissing)
                platformResponse(PathUtils.isPathWithinRoot(FileAccessPermission.Allowed, input.root, input.path, input.allowMissing))
            }
            "POST" to "/files/directories" -> {
                val input = request.platformBody<WebUiPathRequest>()
                val includeDirectoryEntryCount = request.query("includeDirectoryEntryCount")?.toBooleanStrict() ?: true
                val directory = Directory(files.openDirectory(checkedFileEndpointPath(input.path)), includeDirectoryEntryCount)
                platformResponse(WebUiHandle(session.own(directory) { directory.cursor.close() }))
            }
            "GET" to "/files/directories" -> {
                val id = requireNotNull(request.query("handle"))
                val directory = session.resource(id) as? Directory ?: error("not a directory handle")
                val batch = directory.mutex.withLock {
                    val entries = mutableListOf<FileSimpleInfo>()
                    val issues = mutableListOf<String>()
                    var finished = false
                    repeat(128) {
                        if (!finished) {
                            val item = directory.cursor.next()
                            if (item == null) finished = true
                            else item.fold({
                                entries += if (directory.includeDirectoryEntryCount) listingEntry(it) else it.toFileSimpleInfo()
                            }, { issues += it.message ?: "directory entry failed" })
                        }
                    }
                    WebUiDirectoryBatch(entries, finished, issues)
                }
                if (batch.finished) session.release(id)
                platformResponse(batch)
            }
            "POST" to "/files/append" -> {
                val bytes = request.boundedBytes()
                val checked = checkedPlatformPath(path, allowMissing = true, rejectSymbolicLinks = true)
                NoFollowFileChannels.openAppend(checked).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
                platformResponse(true)
            }
            "POST" to "/files/mkdirs" -> {
                val input = request.platformBody<WebUiPathRequest>()
                val checked = checkedPlatformPath(input.path, allowMissing = true, rejectSymbolicLinks = true)
                PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, checked)
                platformResponse(true)
            }
            "POST" to "/files/mkdir" -> {
                val input = request.platformBody<WebUiPathRequest>()
                files.createDirectory(checkedFileEndpointPath(input.path)).getOrThrow()
                platformResponse(true)
            }
            "POST" to "/files/create" -> {
                val input = request.platformBody<WebUiPathRequest>()
                files.createFile(checkedFileEndpointPath(input.path)).getOrThrow()
                platformResponse(true)
            }
            "POST" to "/files/rename" -> {
                val input = request.platformBody<WebUiRenameRequest>()
                validateEndpointLeafName(input.oldName)
                validateEndpointLeafName(input.newName)
                val parent = input.path.trimEnd('/', '\\') + PathUtils.getPathSeparator()
                val source = checkedFileEndpointPath(parent + input.oldName)
                checkedFileEndpointPath(parent + input.newName)
                files.rename(source, input.newName).getOrThrow()
                platformResponse(true)
            }
            "DELETE" to "/files" -> platformResponse(files.delete(checkedFileEndpointPath(path)).getOrThrow())
            "DELETE" to "/files/tree" -> {
                val checked = checkedPlatformPath(path, allowMissing = true, rejectSymbolicLinks = true)
                require(Path.of(checked).parent != null) { "cannot delete filesystem root" }
                PathUtils.deleteDirectory(FileAccessPermission.Allowed, checked)
                platformResponse(true)
            }
            "GET" to "/files/content" -> {
                val checked = checkedFileEndpointPath(path)
                val info = files.info(checked).getOrThrow()
                require(!info.isDirectory)
                val offset = request.query("offset")?.toLongOrNull() ?: 0L
                val length = request.query("length")?.toIntOrNull() ?: WEB_UI_FILE_STREAM_CHUNK_BYTES
                require(offset in 0..info.size && length in 1..WEB_UI_FILE_STREAM_CHUNK_BYTES) { "invalid file range" }
                val bytes = if (offset == info.size) byteArrayOf() else files.readRange(checked, offset, length).getOrThrow().bytes
                LinkShareHttpResponse.bytes(bytes = bytes, contentType = "application/octet-stream", headers = listOf(
                    LinkShareHttpHeader("Accept-Ranges", "bytes"),
                    LinkShareHttpHeader("Content-Range", if (bytes.isEmpty()) "bytes */${info.size}" else "bytes $offset-${offset + bytes.size - 1}/${info.size}"),
                ))
            }
            "PUT" to "/files/content" -> {
                val size = requireNotNull(request.query("size")?.toLongOrNull())
                val offset = requireNotNull(request.query("offset")?.toLongOrNull())
                val bytes = request.boundedBytes()
                require(isWriteRangeWithinFile(size, offset, bytes.size.toLong())) { "invalid write range" }
                platformResponse(files.writeRange(checkedFileEndpointPath(path), size, offset, bytes).getOrThrow())
            }
            "DELETE" to "/resources" -> {
                session.release(requireNotNull(request.query("handle")))
                platformResponse(true)
            }
            else -> LinkShareHttpResponse.text(404, "unknown platform operation")
        }
    }
}

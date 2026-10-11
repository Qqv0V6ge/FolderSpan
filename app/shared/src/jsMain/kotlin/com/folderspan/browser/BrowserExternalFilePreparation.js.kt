@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.browser

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.FileProtocol
import com.folderspan.service.mcp.file.FileEndpointRef
import com.folderspan.service.mcp.file.FileLocator
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import com.folderspan.utils.FileAccessPermission
import com.folderspan.service.webui.WebUiPlatformClient
import com.folderspan.service.webui.WEB_UI_FILE_STREAM_CHUNK_BYTES
import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.service.webui.joinWebUiRelative
import com.folderspan.ui.state.file.ExternalFileSkip
import com.folderspan.ui.state.file.ExternalFileSkipReason
import kotlinx.coroutines.await
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import strings.AppStrings

internal external interface BrowserExternalItem : JsAny {
    val relativePath: String?
    val isDirectory: Boolean?
    val size: Double?
    val lastModified: Double?
    val source: JsAny?
}

internal data class BrowserExternalStageResult(
    val files: List<FileSimpleInfo>,
    val skipped: List<ExternalFileSkip>,
)

internal suspend fun readBrowserExternalItemBytes(item: BrowserExternalItem): ByteArray? {
    val source = item.source ?: return null
    val size = item.size?.toLong()?.coerceAtLeast(0L) ?: 0L
    val base64 = runCatching {
        readBrowserExternalFileRangeBase64(source, 0.0, size.toDouble()).await()
    }.getOrNull() ?: return null
    return runCatching { Base64.decode(base64) }.getOrNull()
}

internal suspend fun stageBrowserExternalItems(
    basePath: String,
    items: List<BrowserExternalItem>,
): BrowserExternalStageResult {
    check(WebUiAttachment.hasSession) { "Runtime login required" }
    val endpoint = FileEndpointRef(FileProtocol.Local)
    val separator = WebUiAttachment.pathSeparator.ifBlank { "/" }
    val skipped = mutableListOf<ExternalFileSkip>()
    val relativeFiles = items.filter { item -> item.isDirectory != true }
        .mapNotNull { item -> normalizeBrowserExternalRelativePath(item.relativePath) }
    val explicitDirectories = items
        .asSequence()
        .filter { item -> item.isDirectory == true }
        .mapNotNull { item -> normalizeBrowserExternalRelativePath(item.relativePath) }
        .toSet()
    val directories = (explicitDirectories +
        (explicitDirectories + relativeFiles).flatMap(::browserExternalParentPaths))
        .sortedWith(compareBy<String> { path -> path.count { char -> char == '/' } }.thenBy { path -> path })
    return try {
        val rootIndex = basePath.lastIndexOfAny(charArrayOf('/', '\\'))
        val rootParent = basePath.substring(0, rootIndex).ifBlank { separator }
        val rootName = basePath.substring(rootIndex + 1)
        FileUtils.createFolder(FileAccessPermission.Allowed, basePath).getOrThrow()
        directories.forEach { relativePath ->
            val parent = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
            FileUtils.createFolder(FileAccessPermission.Allowed, joinWebUiRelative(basePath, relativePath, separator)).getOrThrow()
        }

        val usedPaths = directories.toMutableSet()
        val seenFiles = mutableSetOf<String>()
        items.forEach { item ->
            if (item.isDirectory == true) return@forEach
            val relativePath = normalizeBrowserExternalRelativePath(item.relativePath)
            if (relativePath == null) {
                skipped += ExternalFileSkip(ExternalFileSkipReason.InvalidName)
                return@forEach
            }
            if (item.source == null) {
                skipped += ExternalFileSkip(ExternalFileSkipReason.Unreadable, relativePath.substringAfterLast('/'))
                return@forEach
            }
            val size = item.size?.toLong()?.coerceAtLeast(0L) ?: 0L
            val lastModified = item.lastModified?.toLong()?.takeIf { value -> value > 0L }
            val identity = "$relativePath\u0000$size\u0000${lastModified ?: 0L}"
            if (!seenFiles.add(identity)) {
                skipped += ExternalFileSkip(ExternalFileSkipReason.Duplicate, relativePath.substringAfterLast('/'))
                return@forEach
            }
            val reservedPath = reserveBrowserExternalRelativePath(relativePath, usedPaths)
            val targetPath = joinWebUiRelative(basePath, reservedPath, separator)
            FileUtils.createFile(FileAccessPermission.Allowed, targetPath).getOrThrow()
            var offset = 0L
            while (offset < size) {
                val end = minOf(size - offset, WEB_UI_FILE_STREAM_CHUNK_BYTES.toLong()) + offset
                val bytes = Base64.decode(readBrowserExternalFileRangeBase64(checkNotNull(item.source), offset.toDouble(), end.toDouble()).await())
                check(bytes.size.toLong() == end - offset) { "unexpected end of browser file" }
                FileUtils.writeBytes(FileAccessPermission.Allowed, targetPath, size, bytes, offset).getOrThrow()
                offset = end
            }
        }
        BrowserExternalStageResult(
            files = PathUtils.getFileAndFolder(FileAccessPermission.Allowed, basePath).getOrThrow(),
            skipped = skipped,
        )
    } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
        WebUiPlatformClient.releaseTemporary(basePath)
        throw error
    }
}

private fun browserExternalParentPaths(path: String): List<String> = buildList {
    var parent = path.substringBeforeLast('/', missingDelimiterValue = "")
    while (parent.isNotBlank()) {
        add(parent)
        parent = parent.substringBeforeLast('/', missingDelimiterValue = "")
    }
}

private fun reserveBrowserExternalRelativePath(path: String, usedPaths: MutableSet<String>): String {
    if (usedPaths.add(path)) return path
    val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
    val name = path.substringAfterLast('/')
    val dotIndex = name.lastIndexOf('.').takeIf { index -> index > 0 } ?: name.length
    val base = name.substring(0, dotIndex)
    val extension = name.substring(dotIndex)
    var index = 2
    while (true) {
        val candidateName = "$base($index)$extension"
        val candidate = if (parent.isBlank()) candidateName else "$parent/$candidateName"
        if (usedPaths.add(candidate)) return candidate
        index++
    }
}

private fun readBrowserExternalFileRangeBase64(
    source: JsAny,
    start: Double,
    endExclusive: Double,
): Promise<String> {
    val reader = js(
        """
        (function (source, start, endExclusive) {
          var safeStart = Math.max(0, Math.floor(Number(start) || 0));
          var safeEnd = Math.max(safeStart, Math.floor(Number(endExclusive) || safeStart));
          if (!source || !source.slice || !source.arrayBuffer) return Promise.reject(new Error("unreadable"));
          return source.slice(safeStart, safeEnd).arrayBuffer().then(function (buffer) {
            var bytes = new Uint8Array(buffer);
            var binary = "";
            var chunkSize = 0x8000;
            for (var i = 0; i < bytes.length; i += chunkSize) {
              binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
            }
            return btoa(binary);
          });
        })
        """
    )
    return reader(source, start, endExclusive).unsafeCast<Promise<String>>()
}

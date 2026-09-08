@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.browser

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ui.state.file.ExternalFileSkip
import com.folderspan.ui.state.file.ExternalFileSkipReason
import com.folderspan.utils.WebExternalFileSource
import com.folderspan.utils.WebInMemoryFileStore
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

internal fun stageBrowserExternalItems(
    basePath: String,
    items: List<BrowserExternalItem>,
): BrowserExternalStageResult {
    val skipped = mutableListOf<ExternalFileSkip>()
    val directories = items
        .asSequence()
        .filter { item -> item.isDirectory == true }
        .mapNotNull { item -> normalizeBrowserExternalRelativePath(item.relativePath) }
        .toSet()
        .sortedWith(compareBy<String> { path -> path.count { char -> char == '/' } }.thenBy { path -> path })
    directories.forEach { relativePath ->
        WebInMemoryFileStore.overwriteDirectory(basePath, relativePath)
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
        val source = item.source
        if (source == null) {
            skipped += ExternalFileSkip(
                ExternalFileSkipReason.Unreadable,
                relativePath.substringAfterLast('/'),
            )
            return@forEach
        }
        val size = item.size?.toLong()?.coerceAtLeast(0L) ?: 0L
        val lastModified = item.lastModified?.toLong()?.takeIf { value -> value > 0L }
        val identity = "$relativePath\u0000$size\u0000${lastModified ?: 0L}"
        if (!seenFiles.add(identity)) {
            skipped += ExternalFileSkip(
                ExternalFileSkipReason.Duplicate,
                relativePath.substringAfterLast('/'),
            )
            return@forEach
        }
        val reservedPath = reserveBrowserExternalRelativePath(relativePath, usedPaths)
        WebInMemoryFileStore.putFile(
            basePath = basePath,
            relativePath = reservedPath,
            size = size,
            lastModified = lastModified,
            source = BrowserExternalFileSource(source),
        )
    }
    return BrowserExternalStageResult(
        files = WebInMemoryFileStore.list(basePath).getOrDefault(emptyList()),
        skipped = skipped,
    )
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

private class BrowserExternalFileSource(
    private val source: JsAny,
) : WebExternalFileSource {
    override suspend fun readRange(start: Long, endExclusive: Long): Result<ByteArray> {
        if (start !in 0L..endExclusive) return Result.failure(Exception(AppStrings.ui_invalid_range))
        val base64 = runCatching {
            readBrowserExternalFileRangeBase64(source, start.toDouble(), endExclusive.toDouble()).await()
        }.getOrElse { error -> return Result.failure(error) }
        return runCatching { Base64.decode(base64) }
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

package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.service.http.clipboard.ClipboardStagedDownload
import com.folderspan.service.http.clipboard.ClipboardUrlShareInspectionResult
import com.folderspan.service.http.clipboard.ClipboardUrlShareInspector
import com.folderspan.ui.state.main.TaskRuntimeStoreLock
import com.folderspan.utils.LogKit
import kotlin.time.Clock

/** 侧栏系统分享来源中的 URL 文件。实际地址和资源引用仅保存在内存。 */
object ClipboardUrlShareFiles {
    private data class Entry(
        val file: FileSimpleInfo,
        val url: String?,
        val release: () -> Unit,
    )

    private val lock = TaskRuntimeStoreLock()
    private val entries = linkedMapOf<String, Entry>()

    fun add(file: FileSimpleInfo, url: String? = null, release: () -> Unit): FileSimpleInfo {
        val sharedFile = file.withCopy(protocol = FileProtocol.Share, protocolId = SYSTEM_SHARE_DESK_ID)
        val previous = withLock { entries.put(file.path, Entry(sharedFile, url, release)) }
        previous?.release?.invoke()
        return sharedFile
    }

    fun add(staged: ClipboardStagedDownload): Boolean {
        val path = staged.localPath?.takeIf(String::isNotBlank) ?: return false
        val now = Clock.System.now().toEpochMilliseconds()
        add(
            FileSimpleInfo(
                name = staged.displayName,
                isDirectory = false,
                isHidden = false,
                path = path,
                mineType = staged.contentType.orEmpty(),
                size = staged.size,
                createdDate = now,
                updatedDate = now,
            ),
            release = staged.release,
        )
        return true
    }

    suspend fun inspectAndAdd(url: String, inspector: ClipboardUrlShareInspector): Boolean {
        LogKit.i("Clipboard URL metadata inspection started")
        return when (val result = inspector.inspect(url)) {
            is ClipboardUrlShareInspectionResult.Failure -> {
                LogKit.w("Clipboard URL inspection failed: error=${result.error}")
                false
            }
            is ClipboardUrlShareInspectionResult.Success -> {
                add(result.file, url, result.release)
                LogKit.i("Clipboard URL metadata added: size=${result.file.size}, entries=${list().size}")
                true
            }
        }
    }

    fun list(): List<FileSimpleInfo> = withLock { entries.values.map { it.file } }

    fun find(path: String): FileSimpleInfo? = withLock { entries[path]?.file }

    internal fun downloadUrl(file: FileSimpleInfo): String? = withLock {
        if (file.protocol != FileProtocol.Share || file.protocolId != SYSTEM_SHARE_DESK_ID) null
        else entries[file.path]?.url
    }

    fun clear() {
        val releases = withLock { entries.values.map { it.release }.also { entries.clear() } }
        releases.forEach { release -> runCatching(release) }
    }

    private inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try { block() } finally { lock.unlock() }
    }
}

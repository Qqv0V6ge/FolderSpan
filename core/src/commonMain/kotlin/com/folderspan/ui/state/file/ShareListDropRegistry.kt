package com.folderspan.ui.state.file

import com.folderspan.data.file.FileSimpleInfo

typealias ShareListDropReceiver = (List<FileSimpleInfo>) -> Unit

/**
 * 将平台拖入入口与分享页的 Compose 生命周期解耦。
 *
 * 分享页可见时注册接收器；平台入口只需尝试投递，未命中时继续执行原有文件浏览器逻辑。
 */
object ShareListDropRegistry {
    private var receiver: ShareListDropReceiver? = null

    fun register(receiver: ShareListDropReceiver) {
        this.receiver = receiver
    }

    fun unregister(receiver: ShareListDropReceiver): Boolean {
        if (this.receiver !== receiver) {
            return false
        }
        this.receiver = null
        return true
    }

    fun hasReceiver(): Boolean = receiver != null

    fun deliver(files: List<FileSimpleInfo>): Boolean {
        val activeReceiver = receiver ?: return false
        activeReceiver(normalizeShareListDropFiles(files))
        return true
    }

    internal fun clearForTest() {
        receiver = null
    }
}

/**
 * 清理无效路径并按平台可寻址路径去重，同时保留输入顺序与目录层级信息。
 */
fun normalizeShareListDropFiles(files: Iterable<FileSimpleInfo>): List<FileSimpleInfo> {
    return files
        .filter { item -> item.path.isNotBlank() }
        .distinctBy { item -> item.path }
}

/**
 * 记录拖入时获得的临时资源，并在分享列表不再引用对应批次时释放。
 */
object ShareListDropResourceRegistry {
    private data class ResourceBatch(
        val referencedPaths: Set<String>,
        val release: () -> Unit,
    )

    private val batches = mutableListOf<ResourceBatch>()

    fun register(referencedPaths: Iterable<String>, release: () -> Unit): Boolean {
        val paths = referencedPaths.filter { item -> item.isNotBlank() }.toSet()
        if (paths.isEmpty()) {
            release()
            return false
        }
        batches += ResourceBatch(paths, release)
        return true
    }

    fun retainReferencedPaths(referencedPaths: Iterable<String>) {
        val retainedPaths = referencedPaths.filter { item -> item.isNotBlank() }.toSet()
        val expired = batches.filter { batch -> batch.referencedPaths.none(retainedPaths::contains) }
        batches.removeAll(expired)
        expired.forEach { batch -> runCatching { batch.release() } }
    }

    fun releaseAll() {
        val pending = batches.toList()
        batches.clear()
        pending.forEach { batch -> runCatching { batch.release() } }
    }
}

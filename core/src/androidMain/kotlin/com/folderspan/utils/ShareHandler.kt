package com.folderspan.utils

import strings.AppStrings

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.buildSystemShareDesk
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.ExternalFileResourceLease
import com.folderspan.ui.state.file.ExternalFileResourceLeaseRegistry
import com.folderspan.ui.state.file.ExternalFileSkip
import com.folderspan.ui.state.file.ExternalFileSkipReason
import com.folderspan.ui.state.file.PreparedExternalFileBatch
import com.folderspan.ui.state.file.ShareListDropRegistry
import com.folderspan.ui.state.file.buildPreparedExternalFileBatch
import com.folderspan.ui.state.file.clipboardImageFileName
import com.folderspan.ui.state.file.externalFileStagingRootPath
import com.folderspan.ui.state.file.newExternalFileLeaseId
import com.folderspan.ui.state.file.normalizeShareListDropFiles
import com.folderspan.ui.state.main.DeviceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/**
 * 共享 Uri 的临时缓存注册表
 *
 * 用于存储通过系统分享接收到的文件信息，供系统分享桌面的内部 content 路径读取和展示使用。
 * 使用 LinkedHashMap 保持插入顺序，便于按时间顺序显示分享历史。
 */
object SharedUriFileRegistry {
    // 使用 LinkedHashMap 存储 URI 到文件列表的映射
    private val cache = LinkedHashMap<Uri, List<FileSimpleInfo>>()

    /**
     * 将 URI 及其对应的文件列表添加到缓存
     *
     * @param uri 共享文件的 URI
     * @param files 与该 URI 关联的文件信息列表
     */
    fun put(uri: Uri, files: List<FileSimpleInfo>) {
        // 根据路径去重，避免同一文件重复出现
        val uniqueFiles = files.distinctBy { item -> item.path }
        synchronized(cache) {
            cache[uri] = uniqueFiles
        }
    }

    /**
     * 获取所有缓存的 URI 和文件列表条目
     *
     * @return 所有缓存条目的列表，每个条目包含 URI 和对应的文件列表
     */
    fun entries(): List<Pair<Uri, List<FileSimpleInfo>>> = synchronized(cache) {
        cache.entries.map { item -> item.key to item.value }
    }

    fun clear() = synchronized(cache) {
        cache.clear()
    }

    fun remove(paths: Iterable<String>) = synchronized(cache) {
        val pathSet = paths.toSet()
        cache.keys.removeAll { uri -> uri.toString() in pathSet }
    }

    /**
     * 根据路径查找对应的文件列表
     *
     * @param path 要查找的路径（URI 字符串形式）
     * @return 匹配的文件列表，如果未找到则返回 null
     */
    fun findByPath(path: String): List<FileSimpleInfo>? = synchronized(cache) {
        cache.entries.firstOrNull { item -> item.key.toString() == path }?.value
    }
}

/**
 * 分享处理工具类
 *
 * 负责处理来自系统分享的各种类型内容（文本、单文件、多文件），
 * 并提供打开、保存等操作功能。
 */
object ShareHandler : KoinComponent {
    // 互斥锁，用于串行化分享处理，避免并发更新导致列表出现重复 key
    private val shareMutex = Mutex()
    private val deviceState: DeviceState by inject()

    internal data class AndroidExternalFileEntry(
        val sourcePath: String,
        val relativeName: String,
        val size: Long = 0L,
        val mimeType: String? = null,
        val isDirectory: Boolean = false
    )

    internal data class AndroidClipboardSource(
        val identity: String,
        val entries: List<AndroidExternalFileEntry>,
    )

    data class DroppedFilesResult(
        val deliveredToShareList: Boolean,
        val retainPermission: Boolean,
        val referencedPaths: Set<String>,
        val registryPaths: Set<String>,
    )

    private fun ensureSystemShareDesk(): Share {
        val existing = deviceState.shares.firstOrNull { item -> item.id == SYSTEM_SHARE_DESK_ID }
        if (existing != null) return existing
        return buildSystemShareDesk().also { share -> deviceState.shares.add(share) }
    }

    /**
     * 处理打开单个文件
     *
     * 接收系统分享的单个文件 URI，将其注册到缓存并更新文件状态。
     * 使用互斥锁确保线程安全。
     *
     * @param activity 当前活动上下文
     * @param fileState 文件状态管理对象
     * @param uri 要打开的文件 URI
     */
    fun handleOpenFile(
        activity: Activity,
        fileState: FileState,
        uri: Uri,
        onComplete: () -> Unit = {},
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                shareMutex.withLock {
                    handleOpenFileLocked(activity, fileState, uri)
                }
            } finally {
                onComplete()
            }
        }
    }

    /**
     * 获取文件信息的辅助数据类
     */
    private data class FileInfo(
        val name: String,
        val size: Long,
        val mimeType: String?
    )

    private data class QueriedDocumentInfo(
        val documentId: String,
        val name: String,
        val size: Long,
        val mimeType: String?,
        val isDirectory: Boolean
    )

    /**
     * 从 URI 获取文件信息
     *
     * 通过 ContentResolver 查询 URI 的元数据，包括文件名、大小和 MIME 类型。
     *
     * @param contentResolver 内容解析器
     * @param uri 要查询的 URI
     * @return 文件信息对象，如果查询失败则返回 null
     */
    private fun getFileInfoFromUri(contentResolver: ContentResolver, uri: Uri): FileInfo? {
        return try {
            val mimeType = contentResolver.getType(uri)
            val fallbackImageName = mimeType
                ?.takeIf { item -> item.startsWith("image/", ignoreCase = true) }
                ?.let { item -> clipboardImageFileName(System.currentTimeMillis(), mimeType = item) }
            val queried = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex != -1) cursor.getString(nameIndex) else null
                val size = if (sizeIndex != -1) cursor.getLong(sizeIndex) else 0L
                val resolvedName = name?.takeIf { item -> item.isNotBlank() } ?: fallbackImageName
                resolvedName?.let { item -> FileInfo(item, size, mimeType) }
            }
            queried ?: fallbackImageName?.let { name -> FileInfo(name, 0L, mimeType) }
        } catch (e: Exception) {
            e.printStackTrace()
            val mimeType = runCatching { contentResolver.getType(uri) }.getOrNull()
            mimeType
                ?.takeIf { item -> item.startsWith("image/", ignoreCase = true) }
                ?.let { item -> clipboardImageFileName(System.currentTimeMillis(), mimeType = item) }
                ?.let { name -> FileInfo(name, 0L, mimeType) }
        }
    }

    /**
     * 处理分享的文本内容
     *
     * 显示一个原生对话框，展示接收到的文本内容，
     * 并允许用户将其保存为文本文件到当前目录。
     *
     * @param activity 当前活动上下文
     * @param fileState 文件状态管理对象
     * @param text 接收到的文本内容
     */
    fun handleSharedText(
        activity: Activity,
        fileState: FileState,
        text: String,
        onComplete: () -> Unit = {},
    ) {
        // 创建对话框布局容器
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        // 文本内容标签
        val textView = TextView(activity).apply {
            this.text = AppStrings.ui_shared_text_content
            setPadding(0, 0, 0, 16)
        }
        layout.addView(textView)

        // 文本内容显示框（只读）
        val contentView = EditText(activity).apply {
            setText(text)
            minLines = 3
            maxLines = 8
            isEnabled = false  // 禁用编辑
        }
        layout.addView(contentView)

        // 文件名标签
        val fileNameLabel = TextView(activity).apply {
            this.text = AppStrings.ui_save_file_name
            setPadding(0, 32, 0, 8)
        }
        layout.addView(fileNameLabel)

        // 生成默认文件名（包含时间戳）
        val fileName = "shared_text_${System.currentTimeMillis()}.txt"
        // 文件名输入框
        val fileNameEdit = EditText(activity).apply {
            setText(fileName)
            setSingleLine()
        }
        layout.addView(fileNameEdit)

        // 显示对话框
        AlertDialog.Builder(activity)
            .setTitle(AppStrings.ui_receive_text_share)
            .setView(layout)
            .setPositiveButton(AppStrings.android_action_save) { dialog, _ ->
                // 获取用户输入的文件名，如果为空则使用默认名称
                val targetFileName = fileNameEdit.text.toString().takeIf { item -> item.isNotBlank() } ?: fileName
                val currentPath = fileState.path.value

                // 在 IO 线程中执行文件写入
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val targetFile = File(currentPath, targetFileName)
                        targetFile.writeText(text)

                        // 切换到主线程显示成功提示
                        activity.runOnUiThread {
                            Toast.makeText(activity, AppStrings.ui_text_saved_arg0.format(arg0 = targetFileName), Toast.LENGTH_SHORT).show()
                        }

                        // 刷新文件列表
                        fileState.updateFileAndFolder()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // 切换到主线程显示错误提示
                        activity.runOnUiThread {
                            Toast.makeText(activity, AppStrings.ui_failed_save_arg0.format(arg0 = (e.message).toString()), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(AppStrings.ui_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
        onComplete()
    }

    /**
     * 处理分享的文件列表
     *
     * 接收系统分享的一个或多个文件 URI。
     * - 单个文件：直接调用 handleOpenFile 处理
     * - 多个文件：创建虚拟目录（bundle）来展示文件列表
     *
     * @param activity 当前活动上下文
     * @param fileState 文件状态管理对象
     * @param uris 分享的文件 URI 列表
     */
    fun handleSharedFiles(
        activity: Activity,
        fileState: FileState,
        uris: List<Uri>,
        onComplete: () -> Unit = {},
    ) {
        // 去除重复的 URI
        val uniqueUris = uris.distinctBy { item -> item.toString() }
        if (uniqueUris.isEmpty()) {
            Toast.makeText(activity, AppStrings.ui_no_files_process, Toast.LENGTH_SHORT).show()
            onComplete()
            return
        }

        // 单文件直接打开
        if (uniqueUris.size == 1) {
            handleOpenFile(activity, fileState, uniqueUris.first(), onComplete)
            return
        }

        // 在主线程协程中处理
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 使用互斥锁避免并发问题
                shareMutex.withLock {
                    // 在 IO 线程中读取所有文件的元数据
                    val sharedFiles = withContext(Dispatchers.IO) {
                        uniqueUris.mapNotNull { uri ->
                            val info = getFileInfoFromUri(activity.contentResolver, uri) ?: return@mapNotNull null
                            val now = System.currentTimeMillis()
                            // 构建 FileSimpleInfo 对象
                            FileSimpleInfo(
                                name = info.name,
                                description = "",
                                isDirectory = false,
                                isHidden = false,
                                path = uri.toString(),
                                mineType = info.mimeType ?: "",
                                size = info.size,
                                createdDate = now,
                                updatedDate = now,
                                protocol = FileProtocol.Share,
                                protocolId = SYSTEM_SHARE_DESK_ID,
                            )
                        }
                    }

                    if (sharedFiles.isEmpty()) {
                        Toast.makeText(activity, AppStrings.ui_unable_read_shared_file, Toast.LENGTH_SHORT).show()
                        return@withLock
                    }

                    // 创建虚拟目录 URI（使用时间戳确保唯一性）
                    // 多文件分享用虚拟目录承载，便于在列表中展示
                    val bundleUri = "content://bundle/${System.currentTimeMillis()}".toUri()
                    SharedUriFileRegistry.put(bundleUri, sharedFiles)
                    fileState.updateDesk(FileProtocol.Share, ensureSystemShareDesk(), pathOverride = bundleUri.toString())
                }
            } finally {
                onComplete()
            }
        }
    }

    /**
     * 把 ACTION_SEND / ACTION_SEND_MULTIPLE 的文件 URI 归一化为分享列表条目。
     */
    fun handleSharedFilesForShare(
        activity: Activity,
        uris: List<Uri>,
        onComplete: (List<FileSimpleInfo>) -> Unit,
    ) {
        val uniqueUris = uris.distinctBy { item -> item.toString() }
        if (uniqueUris.isEmpty()) {
            Toast.makeText(activity, AppStrings.ui_no_files_process, Toast.LENGTH_SHORT).show()
            onComplete(emptyList())
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            var sharedFiles = emptyList<FileSimpleInfo>()
            try {
                shareMutex.withLock {
                    sharedFiles = withContext(Dispatchers.IO) {
                        val now = System.currentTimeMillis()
                        uniqueUris.mapNotNull { uri ->
                            val info = getFileInfoFromUri(activity.contentResolver, uri)
                                ?: return@mapNotNull null
                            FileSimpleInfo(
                                name = info.name,
                                description = "",
                                isDirectory = false,
                                isHidden = false,
                                path = uri.toString(),
                                mineType = info.mimeType ?: "",
                                size = info.size,
                                createdDate = now,
                                updatedDate = now,
                                protocol = FileProtocol.Share,
                                protocolId = SYSTEM_SHARE_DESK_ID,
                            )
                        }
                    }
                    if (sharedFiles.isEmpty()) {
                        Toast.makeText(activity, AppStrings.ui_unable_read_shared_file, Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                onComplete(normalizeShareListDropFiles(sharedFiles))
            }
        }
    }

    /**
     * 处理跨应用拖拽导入：
     * - 文件：直接登记为可复制条目
     * - 文件夹：递归展开为文件条目（保留相对路径）
     */
    fun handleDroppedFiles(
        activity: Activity,
        fileState: FileState,
        uris: List<Uri>,
        onComplete: () -> Unit = {}
    ) {
        val uniqueUris = uris.distinctBy { item -> item.toString() }
        if (uniqueUris.isEmpty()) {
            onComplete()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                shareMutex.withLock {
                    val droppedEntries = withContext(Dispatchers.IO) {
                        uniqueUris.flatMap { uri ->
                            collectDroppedEntries(activity, uri)
                        }
                    }

                    if (droppedEntries.isEmpty()) {
                        Toast.makeText(activity, AppStrings.ui_no_importable_files_detected, Toast.LENGTH_SHORT).show()
                        return@withLock
                    }

                    val now = System.currentTimeMillis()
                    val bundleUri = "content://bundle/${System.currentTimeMillis()}".toUri()
                    val registryEntries = buildDroppedRegistryEntries(
                        bundlePath = bundleUri.toString(),
                        droppedEntries = droppedEntries,
                        now = now
                    )
                    registryEntries.forEach { (path, files) ->
                        SharedUriFileRegistry.put(path, files)
                    }
                    fileState.updateDesk(
                        FileProtocol.Share,
                        ensureSystemShareDesk(),
                        pathOverride = bundleUri.toString()
                    )
                }
            } finally {
                onComplete()
            }
        }
    }

    /**
     * 将跨应用拖入条目登记为系统分享文件，并把本次拖入的顶层条目投递到分享列表。
     */
    fun handleDroppedFilesForShare(
        activity: Activity,
        fileState: FileState,
        uris: List<Uri>,
        onComplete: (DroppedFilesResult) -> Unit,
    ) {
        val uniqueUris = uris.distinctBy { item -> item.toString() }
        if (uniqueUris.isEmpty()) {
            onComplete(DroppedFilesResult(false, false, emptySet(), emptySet()))
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            var result = DroppedFilesResult(false, false, emptySet(), emptySet())
            try {
                shareMutex.withLock {
                    val droppedEntries = withContext(Dispatchers.IO) {
                        uniqueUris.flatMap { uri -> collectDroppedEntries(activity, uri) }
                    }
                    if (droppedEntries.isEmpty()) {
                        Toast.makeText(activity, AppStrings.ui_share_drop_no_importable_content, Toast.LENGTH_SHORT).show()
                        return@withLock
                    }

                    val bundleUri = "content://bundle/${System.currentTimeMillis()}".toUri()
                    val registryEntries = buildDroppedRegistryEntries(
                        bundlePath = bundleUri.toString(),
                        droppedEntries = droppedEntries,
                        now = System.currentTimeMillis(),
                    )
                    registryEntries.forEach { (path, files) ->
                        SharedUriFileRegistry.put(path, files)
                    }

                    val topLevelFiles = normalizeShareListDropFiles(
                        registryEntries[bundleUri].orEmpty()
                    )
                    if (topLevelFiles.isEmpty()) {
                        Toast.makeText(activity, AppStrings.ui_share_drop_no_importable_content, Toast.LENGTH_SHORT).show()
                        return@withLock
                    }

                    val delivered = ShareListDropRegistry.deliver(topLevelFiles)
                    if (!delivered) {
                        fileState.updateDesk(
                            FileProtocol.Share,
                            ensureSystemShareDesk(),
                            pathOverride = bundleUri.toString(),
                        )
                    }
                    result = DroppedFilesResult(
                        deliveredToShareList = delivered,
                        retainPermission = true,
                        referencedPaths = topLevelFiles.map { item -> item.path }.toSet(),
                        registryPaths = registryEntries.keys.map { uri -> uri.toString() }.toSet(),
                    )
                }
            } finally {
                onComplete(result)
            }
        }
    }

    fun releaseDroppedRegistry(paths: Iterable<String>) {
        SharedUriFileRegistry.remove(paths)
    }

    /**
     * 将剪贴板 URI 解析并流式暂存为本地文件批次。
     * 与拖拽入口共用 URI/文档树枚举，暂存后不再依赖剪贴板授权的生命周期。
     */
    fun prepareClipboardFiles(context: Context, uris: List<Uri>): PreparedExternalFileBatch {
        val uniqueUris = uris.distinctBy { item -> item.toString() }
        val skipped = mutableListOf<ExternalFileSkip>()
        val sources = uniqueUris.mapNotNull { uri ->
            if (!isClipboardSourceAllowed(context, uri)) {
                skipped += ExternalFileSkip(ExternalFileSkipReason.Unreadable)
                return@mapNotNull null
            }
            AndroidClipboardSource(
                identity = uri.toString(),
                entries = runCatching { collectDroppedEntries(context, uri) }
                    .getOrDefault(emptyList()),
            )
        }
        if (sources.isEmpty()) {
            return PreparedExternalFileBatch(
                skipped = skipped.ifEmpty {
                    uniqueUris.map { ExternalFileSkip(ExternalFileSkipReason.Unreadable) }
                },
                representedItemCount = uniqueUris.size,
            )
        }
        val staged = prepareAndroidClipboardSources(
            sources = sources,
            stageFile = { sourcePath, destination ->
                stageClipboardEntry(context, sourcePath, destination)
            },
        )
        return staged.copy(
            skipped = skipped + staged.skipped,
            representedItemCount = uniqueUris.size,
        )
    }

    internal fun prepareAndroidClipboardSources(
        sources: List<AndroidClipboardSource>,
        stageFile: (sourcePath: String, destination: File) -> Boolean,
        stagingRootPath: (leaseId: String) -> String = ::externalFileStagingRootPath,
        registerLease: (ExternalFileResourceLease) -> Unit = { lease ->
            ExternalFileResourceLeaseRegistry.register(lease)
        },
    ): PreparedExternalFileBatch {
        val uniqueSources = sources.distinctBy { source -> source.identity }
        if (uniqueSources.isEmpty()) return PreparedExternalFileBatch()

        val leaseId = newExternalFileLeaseId()
        val rootPath = stagingRootPath(leaseId)
        val rootDirectory = File(rootPath)
        if (!rootDirectory.mkdirs() && !rootDirectory.isDirectory) {
            return PreparedExternalFileBatch(
                skipped = uniqueSources.map {
                    ExternalFileSkip(ExternalFileSkipReason.Unreadable)
                },
                representedItemCount = uniqueSources.size,
            )
        }

        val skipped = mutableListOf<ExternalFileSkip>()
        val usedTopLevelNames = mutableSetOf<String>()
        uniqueSources.forEach sourceLoop@ { source ->
            val entries = source.entries
            if (entries.isEmpty()) {
                skipped += ExternalFileSkip(ExternalFileSkipReason.Unsupported)
                return@sourceLoop
            }

            val originalTopName = entries.firstNotNullOfOrNull { entry ->
                sanitizeClipboardRelativePath(entry.relativeName)
                    ?.substringBefore('/')
                    ?.takeIf { item -> item.isNotBlank() }
            } ?: "clipboard-file"
            val stagedTopName = reserveClipboardTopLevelName(originalTopName, usedTopLevelNames)
            var uriProducedEntry = false
            entries.forEach entryLoop@ { entry ->
                val normalizedRelative = sanitizeClipboardRelativePath(entry.relativeName)
                if (normalizedRelative == null) {
                    skipped += ExternalFileSkip(ExternalFileSkipReason.InvalidName)
                    return@entryLoop
                }
                val adjustedRelative = normalizedRelative
                    .split('/')
                    .toMutableList()
                    .also { segments -> segments[0] = stagedTopName }
                    .joinToString("/")
                val destination = File(rootDirectory, adjustedRelative)
                val staged = if (entry.isDirectory) {
                    destination.mkdirs() || destination.isDirectory
                } else {
                    stageFile(entry.sourcePath, destination)
                }
                if (staged) {
                    uriProducedEntry = true
                } else {
                    skipped += ExternalFileSkip(
                        reason = if (entry.isDirectory) {
                            ExternalFileSkipReason.Unsupported
                        } else {
                            ExternalFileSkipReason.Unreadable
                        },
                        displayName = normalizedRelative.substringAfterLast('/'),
                    )
                }
            }
            if (!uriProducedEntry) {
                skipped += ExternalFileSkip(
                    reason = ExternalFileSkipReason.Unreadable,
                    displayName = originalTopName,
                )
            }
        }

        val preparedFiles = rootDirectory.listFiles().orEmpty().mapNotNull { file ->
            FileUtils.getFile(FileAccessPermission.Allowed, file.absolutePath)
                .getOrNull()
                ?.withCopy(protocol = FileProtocol.Local, protocolId = "")
        }
        if (preparedFiles.isEmpty()) {
            runCatching { PathUtils.deleteDirectory(FileAccessPermission.Allowed, rootPath) }
            return PreparedExternalFileBatch(
                skipped = skipped.ifEmpty {
                    uniqueSources.map { ExternalFileSkip(ExternalFileSkipReason.Unreadable) }
                },
                representedItemCount = uniqueSources.size,
            )
        }

        val lease = ExternalFileResourceLease(id = leaseId, rootPath = rootPath)
        registerLease(lease)
        return buildPreparedExternalFileBatch(
            files = preparedFiles,
            skipped = skipped,
            lease = lease,
            representedItemCount = uniqueSources.size,
        )
    }

    private fun stageClipboardEntry(context: Context, sourcePath: String, destination: File): Boolean {
        val parent = destination.parentFile ?: return false
        return !(!parent.mkdirs() && !parent.isDirectory) && runCatching {
            val sourceStream = if (sourcePath.startsWith("content://", ignoreCase = true)) {
                val uri = Uri.parse(sourcePath)
                if (!hasClipboardUriGrant(context, uri)) return@runCatching false
                context.contentResolver.openInputStream(uri)
            } else {
                if (SensitiveFileAccessPolicy.deniedException(sourcePath) != null) return@runCatching false
                if (PathUtils.isSymbolicLink(FileAccessPermission.Allowed, sourcePath)) return@runCatching false
                File(sourcePath).takeIf { file -> file.isFile }?.inputStream()
            } ?: return@runCatching false
            sourceStream.use { input ->
                destination.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            true
        }.getOrElse {
            runCatching { destination.delete() }
            false
        }
    }

    private fun isClipboardSourceAllowed(context: Context, uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            ContentResolver.SCHEME_CONTENT -> hasClipboardUriGrant(context, uri)
            ContentResolver.SCHEME_FILE, null -> {
                val path = uri.path ?: return false
                SensitiveFileAccessPolicy.deniedException(path) == null &&
                    !PathUtils.isSymbolicLink(FileAccessPermission.Allowed, path)
            }
            else -> false
        }
    }

    private fun hasClipboardUriGrant(context: Context, uri: Uri): Boolean {
        return uri.scheme == ContentResolver.SCHEME_CONTENT && context.checkUriPermission(
            uri,
            android.os.Process.myPid(),
            android.os.Process.myUid(),
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun sanitizeClipboardRelativePath(rawPath: String): String? {
        val segments = rawPath
            .replace('\\', '/')
            .split('/')
            .map { item -> item.trim() }
            .filter { item -> item.isNotBlank() }
        if (segments.any { item -> item == "." || item == ".." || '\u0000' in item }) return null
        val sanitizedSegments = segments
            .map { item -> item.replace('/', '-').replace('\\', '-') }
        return sanitizedSegments.joinToString("/").takeIf { item -> item.isNotBlank() }
    }

    private fun reserveClipboardTopLevelName(name: String, usedNames: MutableSet<String>): String {
        if (usedNames.add(name)) return name
        val dotIndex = name.lastIndexOf('.').takeIf { index -> index > 0 } ?: name.length
        val base = name.substring(0, dotIndex)
        val extension = name.substring(dotIndex)
        var index = 2
        while (true) {
            val candidate = "$base($index)$extension"
            if (usedNames.add(candidate)) return candidate
            index++
        }
    }

    private fun collectDroppedEntries(context: Context, uri: Uri): List<AndroidExternalFileEntry> {
        val resolver = context.contentResolver

        if (DocumentsContract.isTreeUri(uri)) {
            val treeEntries = collectTreeUriEntries(resolver, uri)
            if (treeEntries.isNotEmpty()) return treeEntries
        }

        if (DocumentsContract.isDocumentUri(context, uri)) {
            val documentEntries = collectDocumentUriEntries(context, resolver, uri)
            if (documentEntries.isNotEmpty()) return documentEntries
        }

        val info = getFileInfoFromUri(resolver, uri) ?: return emptyList()
        val isDirectory = info.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        val resolvedLocalPath = resolveExternalStorageDocumentPath(context, uri)

        if (isDirectory && resolvedLocalPath != null) {
            val localEntries = collectLocalDirectoryEntries(
                rootAbsolutePath = resolvedLocalPath,
                rootRelativeName = info.name
            )
            if (localEntries.isNotEmpty()) return localEntries
        }
        val fallbackSourcePath = if (!isDirectory && resolvedLocalPath != null && File(resolvedLocalPath).isFile) {
            resolvedLocalPath
        } else {
            uri.toString()
        }
        if (isDirectory) return emptyList()
        return listOf(
            AndroidExternalFileEntry(
                sourcePath = fallbackSourcePath,
                relativeName = info.name,
                size = info.size.coerceAtLeast(0L),
                mimeType = info.mimeType,
                isDirectory = isDirectory
            )
        )
    }

    private fun collectTreeUriEntries(resolver: ContentResolver, treeUri: Uri): List<AndroidExternalFileEntry> {
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return emptyList()
        val authority = treeUri.authority ?: return emptyList()
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        val rootInfo = queryDocumentInfo(resolver, rootDocumentUri)
        val rootName = rootInfo?.name?.takeIf { item -> item.isNotBlank() } ?: "dropped"

        if (rootInfo != null && !rootInfo.isDirectory) {
            return listOf(
                AndroidExternalFileEntry(
                    sourcePath = rootDocumentUri.toString(),
                    relativeName = rootName,
                    size = rootInfo.size,
                    mimeType = rootInfo.mimeType,
                    isDirectory = false
                )
            )
        }

        return collectDocumentTreeEntries(
            resolver = resolver,
            rootUriForBuild = treeUri,
            authority = authority,
            rootDocumentId = treeDocumentId,
            rootRelativePath = rootName,
            useTreeContext = true
        )
    }

    private fun buildDroppedRegistryEntries(
        bundlePath: String,
        droppedEntries: List<AndroidExternalFileEntry>,
        now: Long
    ): Map<Uri, List<FileSimpleInfo>> {
        val directoryChildren = linkedMapOf<String, MutableList<FileSimpleInfo>>()

        fun children(path: String): MutableList<FileSimpleInfo> {
            return directoryChildren.getOrPut(path) { mutableListOf() }
        }

        fun createDirectory(parentPath: String, name: String): String {
            val encodedName = Uri.encode(name)
            val normalizedParent = parentPath.trimEnd('/')
            val directoryPath = "$normalizedParent/$encodedName"
            val parentChildren = children(parentPath)
            if (parentChildren.none { item ->
                    item.isDirectory && item.name == name && item.path == directoryPath
                }
            ) {
                parentChildren += FileSimpleInfo(
                    name = name,
                    description = "",
                    isDirectory = true,
                    isHidden = false,
                    path = directoryPath,
                    mineType = "",
                    size = 0,
                    createdDate = now,
                    updatedDate = now,
                    protocol = FileProtocol.Share,
                    protocolId = SYSTEM_SHARE_DESK_ID,
                )
            }
            children(directoryPath)
            return directoryPath
        }

        children(bundlePath)

        droppedEntries.forEach { entry ->
            val segments = entry.relativeName
                .split('/')
                .map { item -> item.trim() }
                .filter { item -> item.isNotEmpty() }
            if (segments.isEmpty()) return@forEach

            var currentDirectoryPath = bundlePath
            val directoryDepth = if (entry.isDirectory) segments.size else segments.lastIndex
            for (index in 0 until directoryDepth) {
                currentDirectoryPath = createDirectory(currentDirectoryPath, segments[index])
            }
            if (entry.isDirectory) return@forEach

            val fileName = segments.last()
            val targetChildren = children(currentDirectoryPath)
            val sourcePath = entry.sourcePath
            if (targetChildren.none { item ->
                    !item.isDirectory && item.name == fileName && item.path == sourcePath
                }
            ) {
                targetChildren += FileSimpleInfo(
                    name = fileName,
                    description = "",
                    isDirectory = false,
                    isHidden = false,
                    path = sourcePath,
                    mineType = entry.mimeType ?: "",
                    size = entry.size,
                    createdDate = now,
                    updatedDate = now,
                    protocol = FileProtocol.Share,
                    protocolId = SYSTEM_SHARE_DESK_ID,
                )
            }
        }

        return directoryChildren.mapKeys { (path, _) -> path.toUri() }
            .mapValues { (_, files) -> files.toList() }
    }

    private fun collectDocumentUriEntries(
        context: Context,
        resolver: ContentResolver,
        documentUri: Uri
    ): List<AndroidExternalFileEntry> {
        if (!DocumentsContract.isDocumentUri(context, documentUri)) return emptyList()

        val authority = documentUri.authority ?: return emptyList()
        val info = queryDocumentInfo(resolver, documentUri) ?: return emptyList()
        val rootName = info.name.takeIf { item -> item.isNotBlank() } ?: "dropped"
        if (!info.isDirectory) {
            return listOf(
                AndroidExternalFileEntry(
                    sourcePath = documentUri.toString(),
                    relativeName = rootName,
                    size = info.size,
                    mimeType = info.mimeType,
                    isDirectory = false
                )
            )
        }

        return collectDocumentTreeEntries(
            resolver = resolver,
            rootUriForBuild = documentUri,
            authority = authority,
            rootDocumentId = info.documentId,
            rootRelativePath = rootName,
            useTreeContext = false
        )
    }

    private fun collectDocumentTreeEntries(
        resolver: ContentResolver,
        rootUriForBuild: Uri,
        authority: String,
        rootDocumentId: String,
        rootRelativePath: String,
        useTreeContext: Boolean
    ): List<AndroidExternalFileEntry> {
        val output = mutableListOf<AndroidExternalFileEntry>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        fun buildDocumentUri(documentId: String): Uri {
            return if (useTreeContext) {
                DocumentsContract.buildDocumentUriUsingTree(rootUriForBuild, documentId)
            } else {
                DocumentsContract.buildDocumentUri(authority, documentId)
            }
        }

        fun buildChildrenUri(documentId: String): Uri {
            return if (useTreeContext) {
                DocumentsContract.buildChildDocumentsUriUsingTree(rootUriForBuild, documentId)
            } else {
                DocumentsContract.buildChildDocumentsUri(authority, documentId)
            }
        }

        val pendingDirectories = ArrayDeque<Pair<String, String>>()
        val visitedDocumentIds = mutableSetOf<String>()
        pendingDirectories.add(rootDocumentId to rootRelativePath)
        while (pendingDirectories.isNotEmpty()) {
            val (documentId, relativePath) = pendingDirectories.removeFirst()
            if (!visitedDocumentIds.add(documentId)) continue
            val childrenUri = runCatching { buildChildrenUri(documentId) }.getOrNull() ?: continue

            val listedDirectory = runCatching {
                val cursor = resolver.query(childrenUri, projection, null, null, null)
                    ?: return@runCatching false
                cursor.use {
                    val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    if (idIndex == -1 || mimeTypeIndex == -1) return@runCatching false

                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(idIndex) ?: continue
                        val childName = if (nameIndex != -1 && !cursor.isNull(nameIndex)) {
                            cursor.getString(nameIndex)
                        } else {
                            null
                        }?.takeIf { item -> item.isNotBlank() } ?: "unknown"

                        val childRelative = "$relativePath/$childName"
                        val childMimeType = if (!cursor.isNull(mimeTypeIndex)) {
                            cursor.getString(mimeTypeIndex)
                        } else {
                            null
                        }

                        if (childMimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (childId !in visitedDocumentIds) {
                                pendingDirectories.add(childId to childRelative)
                            }
                            continue
                        }

                        val childSize = if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                            cursor.getLong(sizeIndex).coerceAtLeast(0L)
                        } else {
                            0L
                        }
                        output += AndroidExternalFileEntry(
                            sourcePath = buildDocumentUri(childId).toString(),
                            relativeName = childRelative,
                            size = childSize,
                            mimeType = childMimeType,
                            isDirectory = false
                        )
                    }
                }
                true
            }.getOrDefault(false)
            if (listedDirectory) {
                output += AndroidExternalFileEntry(
                    sourcePath = buildDocumentUri(documentId).toString(),
                    relativeName = relativePath,
                    size = 0,
                    mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                    isDirectory = true,
                )
            }
        }
        return output
    }

    private fun queryDocumentInfo(resolver: ContentResolver, documentUri: Uri): QueriedDocumentInfo? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        return runCatching {
            resolver.query(documentUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null

                val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (documentIdIndex == -1 || mimeTypeIndex == -1) return@use null

                val documentId = cursor.getString(documentIdIndex) ?: return@use null
                val name = if (nameIndex != -1 && !cursor.isNull(nameIndex)) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }?.takeIf { item -> item.isNotBlank() } ?: "unknown"
                val size = if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex).coerceAtLeast(0L)
                } else {
                    0L
                }
                val mimeType = if (!cursor.isNull(mimeTypeIndex)) {
                    cursor.getString(mimeTypeIndex)
                } else {
                    null
                }

                QueriedDocumentInfo(
                    documentId = documentId,
                    name = name,
                    size = size,
                    mimeType = mimeType,
                    isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                )
            }
        }.getOrNull()
    }

    private fun resolveExternalStorageDocumentPath(context: Context, uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        if (uri.authority != "com.android.externalstorage.documents") return null
        if (!DocumentsContract.isDocumentUri(context, uri)) return null

        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        if (documentId.startsWith("raw:")) {
            return documentId.removePrefix("raw:")
        }

        val split = documentId.split(":", limit = 2)
        if (split.isEmpty()) return null

        val volumeId = split[0]
        val relativePath = if (split.size > 1) split[1] else ""
        val basePath = if (volumeId.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volumeId"
        }

        return if (relativePath.isBlank()) {
            basePath
        } else {
            "$basePath/$relativePath"
        }
    }

    private fun collectLocalDirectoryEntries(
        rootAbsolutePath: String,
        rootRelativeName: String
    ): List<AndroidExternalFileEntry> {
        val root = File(rootAbsolutePath)
        if (!root.exists() || !root.isDirectory || PathUtils.isSymbolicLink(FileAccessPermission.Allowed, root.absolutePath)) return emptyList()

        val entries = mutableListOf<AndroidExternalFileEntry>()
        val pendingDirectories = ArrayDeque<Pair<File, String>>()
        val visitedDirectories = mutableSetOf<String>()
        pendingDirectories.add(root to rootRelativeName)

        while (pendingDirectories.isNotEmpty()) {
            val (directory, relativePath) = pendingDirectories.removeFirst()
            val directoryKey = directory.absolutePath
            if (!visitedDirectories.add(directoryKey) || PathUtils.isSymbolicLink(FileAccessPermission.Allowed, directoryKey)) continue
            val children = directory.listFiles() ?: continue

            if (children.isEmpty()) {
                entries += AndroidExternalFileEntry(
                    sourcePath = directory.absolutePath,
                    relativeName = relativePath,
                    size = 0,
                    mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                    isDirectory = true
                )
                continue
            }

            children.forEach { child ->
                if (PathUtils.isSymbolicLink(FileAccessPermission.Allowed, child.absolutePath)) return@forEach
                val childRelativePath = "$relativePath/${child.name}"
                if (child.isDirectory) {
                    pendingDirectories.add(child to childRelativePath)
                } else {
                    entries += AndroidExternalFileEntry(
                        sourcePath = child.absolutePath,
                        relativeName = childRelativePath,
                        size = child.length().coerceAtLeast(0L),
                        mimeType = null,
                        isDirectory = false
                    )
                }
            }
        }

        if (entries.isEmpty()) {
            entries += AndroidExternalFileEntry(
                sourcePath = root.absolutePath,
                relativeName = rootRelativeName,
                size = 0,
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                isDirectory = true
            )
        }
        return entries
    }

    /**
     * 处理打开单个文件的内部实现（已加锁）
     *
     * 从 URI 读取文件信息，将其注册到缓存，并更新当前路径。
     * 此函数在互斥锁保护下执行，确保线程安全。
     *
     * @param activity 当前活动上下文
     * @param fileState 文件状态管理对象
     * @param uri 要打开的文件 URI
     */
    private suspend fun handleOpenFileLocked(activity: Activity, fileState: FileState, uri: Uri) {
        try {
            // 在 IO 线程中获取文件信息
            val fileInfo = withContext(Dispatchers.IO) {
                getFileInfoFromUri(activity.contentResolver, uri)
            }

            if (fileInfo == null) {
                Toast.makeText(activity, AppStrings.ui_unable_read_file_information, Toast.LENGTH_SHORT).show()
                return
            }

            // 构建文件信息对象
            val now = System.currentTimeMillis()
            val sharedFile = FileSimpleInfo(
                name = fileInfo.name,
                description = "",
                isDirectory = false,
                isHidden = false,
                path = uri.toString(),
                mineType = fileInfo.mimeType ?: "",
                size = fileInfo.size,
                createdDate = now,
                updatedDate = now,
                protocol = FileProtocol.Share,
                protocolId = SYSTEM_SHARE_DESK_ID,
            )
            SharedUriFileRegistry.put(uri, listOf(sharedFile))

            fileState.updateDesk(FileProtocol.Share, ensureSystemShareDesk(), pathOverride = uri.toString())


            // 以下是注释掉的旧实现代码，保留用于参考
            // 旧版本使用对话框提供AppStrings.ui_open和AppStrings.ui_save_current_directory两个选项
//                val currentPath = fileState.path.value
//
//                // 显示对话框
//                AlertDialog.Builder(activity)
//                    .setTitle(AppStrings.ui_open_file)
//                    .setMessage(
//                        AppStrings.ui_file_arg0_arg1.format(arg0 = fileInfo.name, arg1 = fileInfo.size.formatFileSize()) +
//                            AppStrings.ui_select_open_view_file_directly_select_save_current_directory +
//                            AppStrings.ui_current_location_arg0.format(arg0 = currentPath)
//                    )
//                    .setPositiveButton(AppStrings.ui_open) { dialog, _ ->
//                        val type = fileInfo.mimeType ?: activity.contentResolver.getType(uri)
//                        try {
//                            openUri(activity, uri, type)
//                        } catch (e: ActivityNotFoundException) {
//                            Toast.makeText(activity, AppStrings.ui_there_no_application_available_open_file, Toast.LENGTH_SHORT).show()
//                        } catch (e: Exception) {
//                            Toast.makeText(activity, AppStrings.ui_unable_open_file_arg0.format(arg0 = (e.message).toString()), Toast.LENGTH_SHORT).show()
//                        }
//                        dialog.dismiss()
//                    }
//                    .setNeutralButton(AppStrings.ui_save_current_directory) { dialog, _ ->
//                        CoroutineScope(Dispatchers.IO).launch {
//                            try {
//                                val targetFile = File(currentPath, fileInfo.name)
//
//                                // 复制文件
//                                activity.contentResolver.openInputStream(uri)?.use { input ->
//                                    targetFile.outputStream().use { output ->
//                                        input.copyTo(output)
//                                    }
//                                }
//
//                                activity.runOnUiThread {
//                                    Toast.makeText(activity, AppStrings.ui_file_saved, Toast.LENGTH_SHORT).show()
//
//                                    // 打开文件
//                                    try {
//                                        MainActivity.openFile(targetFile.absolutePath)
//                                    } catch (e: Exception) {
//                                        Toast.makeText(activity, AppStrings.ui_unable_open_file_arg0.format(arg0 = (e.message).toString()), Toast.LENGTH_SHORT).show()
//                                    }
//                                }
//
//                                // 刷新文件列表
//                                fileState.updateFileAndFolder()
//                            } catch (e: Exception) {
//                                e.printStackTrace()
//                                activity.runOnUiThread {
//                                    Toast.makeText(activity, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
//                                }
//                            }
//                        }
//                        dialog.dismiss()
//                    }
//                    .setNegativeButton(AppStrings.ui_cancel) { dialog, _ ->
//                        dialog.dismiss()
//                    }
//                    .setCancelable(true)
//                    .show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                activity,
                AppStrings.ui_processing_file_failed_arg0.format(arg0 = e.message.orEmpty()),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

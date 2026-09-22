package com.folderspan.ui.state.file

import androidx.compose.runtime.mutableStateListOf
import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.data.file.ShareHistoryStore
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.data.main.share.buildSystemShareDesk
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.editor.FileEditorContentSource
import com.folderspan.exception.EmptyDataException
import com.folderspan.extensions.*
import com.folderspan.service.operation.TraversalScanProgress
import com.folderspan.ui.state.main.*
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.PathUtils
import com.folderspan.utils.PlatformMemoryManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import strings.AppStrings

private data class FileListRequestContext(
    val token: Any,
    val desk: DiskBase,
    val path: String,
    val location: FileScrollLocation,
    val previousContentLocation: FileScrollLocation?,
    val previousException: Throwable?,
)

internal fun shouldFallbackToLocalAfterFileListFailure(
    requestDesk: DiskBase,
    activeDesk: DiskBase,
    error: Throwable?,
): Boolean {
    return requestDesk is Device &&
        activeDesk === requestDesk &&
        error?.isConnectionException() == true
}

class FileState : KoinComponent {
    val taskState: TaskState by inject()
    private val database: FolderSpanDatabase by inject()
    val fileBookmarkState: FileBookmarkState by inject()
    val fileFavoriteState: FileFavoriteState by inject()
    private val fileRecentState: FileRecentState by inject()
    private val shareHistoryStore: ShareHistoryStore by inject()
    val deviceState: DeviceState by inject()
    private val networkState: NetworkState by inject()
    private val mainState: MainState by inject()
    val mainScope = MainScope()
    private val webRtcBrowserZipDownloader by lazy {
        FileStateWebRtcBrowserZipDownloader(
            taskState = taskState,
            deviceForProtocolId = { protocolId -> resolveDevice(_deskType.value, protocolId) },
            collectDirectoryEntries = { root, task, onScanProgress ->
                collectDirectoryEntries(
                    root = root,
                    ensureRunning = { ensureQueuedTaskRunning(task) },
                    requestBatchId = buildTaskScanRequestBatchId(task),
                    onScanProgress = onScanProgress,
                    rejectSymbolicLinkEntries = true,
                    onRejectedEntry = { entry, error ->
                        taskState.recordRetryFailure(
                            task = task,
                            entry = buildCopyRetryEntry(task.taskType, entry, entry),
                            message = error.message.orEmpty(),
                            fallback = AppStrings.file_symbolic_link_copy_not_supported,
                        )
                    },
                )
            },
            ensureTaskRunning = { task -> ensureQueuedTaskRunning(task) },
            finishSuccessfulTask = { task -> finishSuccessfulTask(task) },
        )
    }
    private val pasteOperationPlanner by lazy {
        FilePasteOperationPlanner(::resolveProtocolPathSeparator)
    }
    private val directoryCollector by lazy {
        FileStateDirectoryCollector(
            currentDesk = { _deskType.value },
            deviceState = deviceState,
            networkState = networkState,
        )
    }
    private val entryReader by lazy {
        FileStateEntryReader(
            currentDesk = { _deskType.value },
            directoryCollector = directoryCollector,
        )
    }
    private val basicOperations by lazy {
        FileStateBasicOperations(
            currentDesk = { _deskType.value },
            updateFileAndFolder = { updateFileAndFolder() },
        )
    }
    private val deskSwitcher by lazy {
        FileStateDeskSwitcher(
            mainScope = mainScope,
            fileBookmarkState = fileBookmarkState,
            deviceState = deviceState,
            mainState = mainState,
            currentDesk = { _deskType.value },
            currentPath = { _path.value },
            setDesk = { desk -> _deskType.value = desk },
            setPathDirectly = { path -> _path.value = path },
            setRootPath = { path -> _rootPath.value = path },
            rememberPathForDesk = { desk, path -> rememberPathForDesk(desk, path) },
            rememberedPathForDesk = { desk -> rememberedPathForDesk(desk) },
            cancelListRequestsForDesk = { desk -> cancelListRequestsForDesk(desk) },
            getRootPaths = { getRootPaths() },
            updatePath = { path -> updatePath(path) },
            updateFileAndFolder = { updateFileAndFolder() },
        )
    }
    private val propertySummarizer by lazy {
        FileStatePropertySummarizer(
            collectDirectoryEntries = { root, ensureRunning, onScanIssues, onEntriesDiscovered ->
                directoryCollector.collectDirectoryEntries(
                    root = root,
                    ensureRunning = ensureRunning,
                    maxParallelismLimit = FILE_PROPERTY_TRAVERSAL_MAX_PARALLELISM,
                    retainDiscoveredEntries = false,
                    deduplicateDirectories = root.protocol != FileProtocol.Local,
                    depthFirst = root.protocol == FileProtocol.Local,
                    continueOnDirectoryError = true,
                    onScanIssues = onScanIssues,
                    onEntriesDiscovered = onEntriesDiscovered,
                )
            },
            resolveParallelism = { endpointKind, protocolId ->
                runtimeTaskExecutor.resolveOperationParallelismForEndpoint(endpointKind, protocolId)
            },
            resolveRuntimeMax = { endpointKind, protocolId ->
                runtimeTaskExecutor.resolveOperationRuntimeMaxForEndpoint(endpointKind, protocolId)
            },
            releaseTemporaryMemory = {
                mainScope.launch {
                    withContext(Dispatchers.Default) {
                        runCatching { PlatformMemoryManager.releaseUnusedMemory() }
                    }
                }
            },
        )
    }
    private val copyCoordinator by lazy {
        FileStateCopyCoordinator(
            taskState = taskState,
            deviceState = deviceState,
            networkState = networkState,
            currentDesk = { _deskType.value },
            fileRecentState = fileRecentState,
        )
    }
    private val operationIgnoreResolver by lazy {
        FileOperationIgnoreResolver(
            database = database,
            deviceState = deviceState,
            currentDesk = { _deskType.value },
            resolveDevice = { preferredDesk, deviceId -> resolveDevice(preferredDesk, deviceId) },
            resolveNetworkAccess = { preferredDesk, protocolId -> resolveNetworkAccess(preferredDesk, protocolId) },
        )
    }
    private val runtimeTaskExecutor by lazy {
        FileRuntimeTaskExecutor(
            taskState = taskState,
            deviceState = deviceState,
            directoryCollector = directoryCollector,
            operationIgnoreResolver = operationIgnoreResolver,
            currentDesk = { _deskType.value },
            runCopyAcrossEndpoints = { task, src, dest, sourceDesk, targetDesk ->
                runCopyAcrossEndpoints(task, src, dest, sourceDesk, targetDesk)
            },
            updateFileAndFolder = { updateFileAndFolder() },
            resolveDevice = { preferredDesk, deviceId -> resolveDevice(preferredDesk, deviceId) },
            resolveNetworkAccess = { preferredDesk, protocolId -> resolveNetworkAccess(preferredDesk, protocolId) },
        )
    }
    private val pasteTaskExecutor by lazy {
        FilePasteTaskExecutor(
            taskState = taskState,
            webRtcBrowserZipDownloader = webRtcBrowserZipDownloader,
            pasteOperationPlanner = pasteOperationPlanner,
            getFileAndFolder = { path -> getFileAndFolder(path) },
            getFileAndFolderForDesk = { desk, path -> entryReader.getFileAndFolder(desk, path) },
            getFile = { path -> getFile(path) },
            executeCopyTask = { task, src, dest -> executeCopyTask(task, src, dest) },
            executeMoveTask = { task, src, dest -> executeMoveTask(task, src, dest) },
            finishSuccessfulTask = { task -> finishSuccessfulTask(task) },
            updateFileAndFolder = { updateFileAndFolder() },
            currentDesk = { _deskType.value },
            resolveDevice = { preferredDesk, deviceId -> resolveDevice(preferredDesk, deviceId) },
            resolveNetworkAccess = { preferredDesk, protocolId -> resolveNetworkAccess(preferredDesk, protocolId) },
            downloadUrlForCopy = { url, copy ->
                inject<ClipboardUrlDownloadCoordinator>().value.downloadForCopy(url, copy)
            },
        )
    }
    private val externalFileImportCoordinator by lazy {
        ExternalFileImportCoordinator { target, files, operationState, lease ->
            pasteTaskExecutor.pasteExternalFiles(
                target = target,
                srcFiles = files,
                fileOperationState = operationState,
                lease = lease,
            )
        }
    }
    private val taskSubmissionCoordinator by lazy {
        FileStateTaskSubmissionCoordinator(
            taskState = taskState,
            webRtcBrowserZipDownloader = webRtcBrowserZipDownloader,
            executeCopyTask = { task, src, dest -> executeCopyTask(task, src, dest) },
            executeDeleteTask = { task, target -> executeDeleteTask(task, target) },
            finishSuccessfulTask = { task -> finishSuccessfulTask(task) },
            updateFileAndFolder = { updateFileAndFolder() },
        )
    }
    private var externalShareHandlingDepth = 0

    private val _isExternalShareHandling: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isExternalShareHandling: StateFlow<Boolean> = _isExternalShareHandling

    private val homePath = PathUtils.getHomePath().ifBlank { PathUtils.getAppPath() }

    // 每个磁盘在当前会话中的最后路径缓存。
    private val lastPathsByDisk = mutableMapOf<String, String>()

    val fileAndFolder = mutableStateListOf<FileSimpleInfo>()
    private val scrollPositions = mutableMapOf<FileScrollLocation, FileScrollPosition>()
    private val _contentLocation = MutableStateFlow<FileScrollLocation?>(null)
    val contentLocation: StateFlow<FileScrollLocation?> = _contentLocation
    private val fileListRequestMutex = Mutex()
    private var activeFileListRequest: Any? = null

    private val _rootPath: MutableStateFlow<PathInfo> = MutableStateFlow(PathInfo("", 0, 0))
    val rootPath: StateFlow<PathInfo> = _rootPath
    private val _rootPaths: MutableStateFlow<List<PathInfo>> = MutableStateFlow(listOf(PathInfo("", 0, 0)))
    val rootPaths: StateFlow<List<PathInfo>> = _rootPaths

    init {
        PathUtils.getRootPaths(FileAccessPermission.Allowed).onSuccess {
            _rootPaths.value = it
            _rootPath.value = it.firstOrNull() ?: PathInfo("", 0, 0)
        }
    }

    // 更新根路径并刷新文件列表
    suspend fun updateRootPath(value: PathInfo) {
        _rootPath.value = value
        updateFileAndFolder()
    }

    private val _path: MutableStateFlow<String> = MutableStateFlow(homePath)
    val path: StateFlow<String> = _path

    // 更新当前路径
    suspend fun updatePath(value: String) {
        cancelListRequestsForDesk(_deskType.value)
        _path.value = value
        val pathInfo =
            _rootPaths.value
                .sortedByDescending { it.path.pathLevel() }
                .firstOrNull { value.indexOf(it.path) == 0 }
        if (pathInfo != null) {
            _rootPath.value = pathInfo
        }

        rememberPathForDesk(_deskType.value, value)
        updateFileAndFolder()
    }

    // 记录磁盘在当前会话中的最后访问路径。
    private fun rememberPathForDesk(desk: DiskBase, path: String) {
        if (path.isBlank()) return
        lastPathsByDisk[buildDiskPathKey(desk)] = path
    }

    // 获取磁盘在当前会话中的最后访问路径。
    private fun rememberedPathForDesk(desk: DiskBase): String? {
        return lastPathsByDisk[buildDiskPathKey(desk)]
    }

    private suspend fun cancelListRequestsForDesk(desk: DiskBase) = entryReader.cancelListRequestsForDesk(desk)

    fun scrollLocation(desk: DiskBase, path: String): FileScrollLocation {
        return buildFileScrollLocation(desk, path)
    }

    // 记录目录滚动位置
    fun updateScrollPosition(location: FileScrollLocation, index: Int, offset: Int) {
        val position = FileScrollPosition(index, offset)
        if (scrollPositions[location] != position) {
            scrollPositions[location] = position
        }
    }

    // 获取目录滚动位置
    fun getScrollPosition(location: FileScrollLocation): FileScrollPosition? = scrollPositions[location]

    private val _deskType: MutableStateFlow<DiskBase> = MutableStateFlow(Local())
    val deskType: StateFlow<DiskBase> = _deskType

    fun beginExternalShareHandling() {
        externalShareHandlingDepth += 1
        _isExternalShareHandling.value = true
    }

    fun endExternalShareHandling() {
        externalShareHandlingDepth = (externalShareHandlingDepth - 1).coerceAtLeast(0)
        _isExternalShareHandling.value = externalShareHandlingDepth > 0
    }

    suspend fun openSystemShareFiles() {
        val desk = deviceState.shares.firstOrNull { it.id == SYSTEM_SHARE_DESK_ID }
            ?: buildSystemShareDesk().also { deviceState.shares.add(it) }
        if (deskType.value === desk && path.value == "/") {
            updateFileAndFolder()
        } else {
            updateDesk(FileProtocol.Share, desk, pathOverride = "/")
        }
    }

    // 切换桌面类型并刷新路径与主题
    fun updateDesk(
        protocol: FileProtocol,
        type: DiskBase,
        isRefresh: Boolean = true,
        pathOverride: String? = null,
    ) = deskSwitcher.updateDesk(
        protocol = protocol,
        type = type,
        isRefresh = isRefresh,
        pathOverride = pathOverride,
    )

    // 加载状态
    private val _isLoading: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // 错误信息
    private val _exception: MutableStateFlow<Throwable?> = MutableStateFlow(null)
    val exception: StateFlow<Throwable?> = _exception

    // 获取指定路径下的文件与文件夹
    suspend fun getFileAndFolder(path: String): Result<List<FileSimpleInfo>> = entryReader.getFileAndFolder(path)

    // 刷新当前路径的文件列表
    suspend fun updateFileAndFolder() {
        val request = fileListRequestMutex.withLock {
            val desk = _deskType.value
            val path = _path.value
            FileListRequestContext(
                token = Any(),
                desk = desk,
                path = path,
                location = scrollLocation(desk, path),
                previousContentLocation = _contentLocation.value,
                previousException = _exception.value,
            ).also { context ->
                activeFileListRequest = context.token
                _isLoading.value = true
                _contentLocation.value = null
                _exception.value = null
            }
        }

        var wasCancelled = false
        try {
            cancelListRequestsForDesk(request.desk)
            val canLoad = fileListRequestMutex.withLock {
                isCurrentFileListRequest(request)
            }
            if (!canLoad) return

            val fileAndFolderResult = getFileAndFolder(request.path)
            val fileListError = fileAndFolderResult.exceptionOrNull()
            if (fallbackToLocalAfterFileListFailure(request, fileListError)) return

            var committedFiles: List<FileSimpleInfo>? = null
            fileListRequestMutex.withLock {
                if (isCurrentFileListRequest(request)) {
                    fileAndFolder.clear()
                    if (fileAndFolderResult.isSuccess) {
                        val files = fileAndFolderResult.getOrNull() ?: emptyList()
                        fileAndFolder.addAll(files)
                        committedFiles = files
                        if (files.isEmpty()) {
                            _exception.value = EmptyDataException()
                        }
                    } else {
                        val error = fileAndFolderResult.exceptionOrNull()
                        if (error !is CancellationException) {
                            _exception.value = error
                        }
                    }
                    _contentLocation.value = request.location
                }
            }

            committedFiles?.let { files ->
                mainScope.launch {
                    val (protocol, protocolId) = resolveFavoriteContext(request.desk)
                    runCatching {
                        fileFavoriteState.loadFavoritesForCurrentFiles(
                            protocol,
                            protocolId,
                            files.map { item -> item.path },
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            wasCancelled = true
            throw e
        } catch (e: Throwable) {
            if (fallbackToLocalAfterFileListFailure(request, e)) return

            fileListRequestMutex.withLock {
                if (isCurrentFileListRequest(request)) {
                    fileAndFolder.clear()
                    if (e !is CancellationException) {
                        _exception.value = e
                    }
                    _contentLocation.value = request.location
                }
            }
        } finally {
            withContext(NonCancellable) {
                fileListRequestMutex.withLock {
                    if (activeFileListRequest === request.token) {
                        if (wasCancelled) {
                            _contentLocation.value = request.previousContentLocation
                            _exception.value = request.previousException
                        }
                        activeFileListRequest = null
                        _isLoading.value = false
                    }
                }
            }
        }
    }

    private suspend fun fallbackToLocalAfterFileListFailure(
        request: FileListRequestContext,
        error: Throwable?,
    ): Boolean = withContext(Dispatchers.Main) {
        fileListRequestMutex.withLock {
            if (
                !isCurrentFileListRequest(request) ||
                !shouldFallbackToLocalAfterFileListFailure(request.desk, _deskType.value, error)
            ) {
                return@withLock false
            }

            updateDesk(FileProtocol.Local, Local())
            true
        }
    }

    private fun isCurrentFileListRequest(request: FileListRequestContext): Boolean {
        return activeFileListRequest === request.token &&
            scrollLocation(_deskType.value, _path.value) == request.location
    }

    // 获取当前桌面的根路径列表
    suspend fun getRootPaths(): List<PathInfo> {
        val roots = entryReader.getRootPaths()
        _rootPaths.value = roots
        return roots
    }

    // 获取文件详细信息
    suspend fun getFileInfo(path: String): Result<FileInfo> = entryReader.getFileInfo(path)

    // 重命名文件或文件夹
    suspend fun rename(path: String, oldName: String, newName: String): Result<Boolean> =
        basicOperations.rename(path, oldName, newName)

    // 创建文件夹
    suspend fun createFolder(path: String, name: String): Result<Boolean> =
        basicOperations.createFolder(path, name)

    // 创建文件
    suspend fun createFile(path: String, name: String): Result<Boolean> =
        basicOperations.createFile(path, name)

    // 提交并执行删除任务
    fun deleteFile(task: Task, deleteFileSimpleInfo: FileSimpleInfo) =
        taskSubmissionCoordinator.deleteFile(task, deleteFileSimpleInfo)

    // 提交并执行复制任务
    fun enqueueCopyFile(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
    ) = taskSubmissionCoordinator.enqueueCopyFile(task, srcFileSimpleInfo, destFileSimpleInfo)

    init {
        mainScope.launch {
            withContext(Dispatchers.Default) {
                // 预热根路径，避免依赖 AppBar 首项进入可见区后才初始化 rootPath。
                getRootPaths()
                updateFileAndFolder()
            }
        }
    }

    // 执行复制任务（本地/远程/共享）
    suspend fun copyTo(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
    ): Result<Boolean> = copyCoordinator.copyTo(
        task = task,
        srcFileSimpleInfo = srcFileSimpleInfo,
        destFileSimpleInfo = destFileSimpleInfo,
    )

    /**
     * 在不切换当前 UI 磁盘上下文的情况下，执行跨端复制。
     * 用于同步任务场景：来源/目标端点由调用方显式提供。
     */
    suspend fun runCopyAcrossEndpoints(
        task: Task,
        src: FileSimpleInfo,
        dest: FileSimpleInfo,
        sourceDesk: DiskBase,
        targetDesk: DiskBase,
    ): Result<Boolean> = copyCoordinator.runCopyAcrossEndpoints(
        task = task,
        src = src,
        dest = dest,
        sourceDesk = sourceDesk,
        targetDesk = targetDesk,
    )

    fun continueTask(task: Task) = runtimeTaskExecutor.continueTask(task)

    internal suspend fun executeCopyTask(
        task: Task,
        src: FileSimpleInfo,
        dest: FileSimpleInfo,
    ): Result<Boolean> {
        val result = runtimeTaskExecutor.executeCopyTask(task, src, dest)
        buildIncomingShareHistoryInput(
            source = src,
            destination = dest,
            sourceShare = deviceState.shares.firstOrNull { share -> share.id == src.protocolId },
            result = result,
        )?.let { input ->
            withContext(Dispatchers.Default) {
                shareHistoryStore.add(input)
            }
        }
        return result
    }

    internal suspend fun executeMoveTask(
        task: Task,
        src: FileSimpleInfo,
        dest: FileSimpleInfo,
    ): Result<Boolean> = runtimeTaskExecutor.executeMoveTask(task, src, dest)

    internal suspend fun executeDeleteTask(
        task: Task,
        target: FileSimpleInfo,
    ): Result<Boolean> = runtimeTaskExecutor.executeDeleteTask(task, target)

    private fun finishSuccessfulTask(task: Task) = runtimeTaskExecutor.finishSuccessfulTask(task)

    private suspend fun ensureQueuedTaskRunning(task: Task) = runtimeTaskExecutor.ensureQueuedTaskRunning(task)

    private fun buildTaskScanRequestBatchId(task: Task): String = runtimeTaskExecutor.buildTaskScanRequestBatchId(task)

    private fun resolveProtocolPathSeparator(
        protocol: FileProtocol,
        protocolId: String,
    ): String = runtimeTaskExecutor.resolveProtocolPathSeparator(protocol, protocolId)

    suspend fun summarizeFileProperties(
        file: FileSimpleInfo,
        onProgress: suspend (FilePropertySummary) -> Unit = {},
    ): Result<FilePropertySummary> = propertySummarizer.summarizeFileProperties(file, onProgress)

    suspend fun summarizeFileProperties(
        files: List<FileSimpleInfo>,
        onProgress: suspend (FilePropertySummary) -> Unit = {},
    ): Result<FilePropertySummary> = propertySummarizer.summarizeFileProperties(files, onProgress)

    /**
     * 为文件编辑器创建协议无关、内存有界的内容源。
     *
     * 本地、设备和远端共享直接按范围读取；常规网络存储先流式落盘到缓存，
     * 后续分页读取与保存均不会把完整文件聚合到内存。
     */
    suspend fun openEditorContent(
        file: FileSimpleInfo,
        canWrite: Boolean,
    ): Result<FileEditorContentSource> = when (file.protocol) {
        FileProtocol.Local -> createFileEditorContentSource(
            file = file,
            requestedCanWrite = canWrite,
        )

        FileProtocol.Device -> createFileEditorContentSource(
            file = file,
            requestedCanWrite = canWrite,
            device = resolveDevice(_deskType.value, file.protocolId),
        )

        FileProtocol.Share -> {
            if (file.protocolId == SYSTEM_SHARE_DESK_ID) {
                createFileEditorContentSource(
                    file = file.withCopy(protocol = FileProtocol.Local, protocolId = ""),
                    requestedCanWrite = false,
                )
            } else {
                createFileEditorContentSource(
                    file = file,
                    requestedCanWrite = false,
                    share = deviceState.shares.firstOrNull { item -> item.id == file.protocolId },
                )
            }
        }

        FileProtocol.Network -> createFileEditorContentSource(
            file = file,
            requestedCanWrite = canWrite,
            networkAccess = resolveNetworkAccess(_deskType.value, file.protocolId),
        )
    }

    private suspend fun collectDirectoryEntries(
        root: FileSimpleInfo,
        ensureRunning: suspend () -> Unit = {},
        requestBatchId: String? = null,
        onScanProgress: suspend (TraversalScanProgress) -> Unit = {},
        onEntriesDiscovered: suspend (List<FileSimpleInfo>) -> Unit = {},
        rejectSymbolicLinkEntries: Boolean = false,
        onRejectedEntry: suspend (FileSimpleInfo, Throwable) -> Unit = { _, _ -> },
    ): List<FileSimpleInfo> = entryReader.collectDirectoryEntries(
        root = root,
        ensureRunning = ensureRunning,
        requestBatchId = requestBatchId,
        onScanProgress = onScanProgress,
        onEntriesDiscovered = onEntriesDiscovered,
        rejectSymbolicLinkEntries = rejectSymbolicLinkEntries,
        onRejectedEntry = onRejectedEntry,
    )

    private fun resolveNetworkAccess(
        preferredDesk: DiskBase?,
        protocolId: String,
    ): NetworkAccess? {
        if (preferredDesk is NetworkAccess && (protocolId.isBlank() || preferredDesk.protocolId == protocolId)) {
            return preferredDesk
        }
        return networkState.networks.firstOrNull { item -> item.protocolId == protocolId }
    }

    private fun resolveDevice(
        preferredDesk: DiskBase?,
        deviceId: String,
    ): Device? {
        return deviceState.resolveConnectedDevice(
            deviceId = deviceId,
            preferredDevice = preferredDesk as? Device,
        )
    }

    // 执行复制粘贴并处理冲突
    suspend fun pasteCopyFile(
        destFileInfo: FileSimpleInfo,
        srcFiles: List<FileSimpleInfo>,
        fileOperationState: FileOperationState,
    ) = pasteTaskExecutor.pasteCopyFile(
        destFileInfo = destFileInfo,
        srcFiles = srcFiles,
        fileOperationState = fileOperationState,
    )

    /**
     * 将全部来源一次性按粘贴规划入队（默认覆盖、不弹冲突框）。
     * 保存共享条目时与查看后粘贴走同一条复制链路。
     */
    internal suspend fun pasteCopyFilesWithReplace(
        destFileInfo: FileSimpleInfo,
        srcFiles: List<FileSimpleInfo>,
        listDestinationChildren: suspend () -> Result<List<FileSimpleInfo>> = {
            getFileAndFolder(destFileInfo.path)
        },
        awaitCompletion: Boolean = false,
    ): List<Long> = pasteTaskExecutor.pasteCopyFilesWithReplace(
        destFileInfo = destFileInfo,
        srcFiles = srcFiles,
        listDestinationChildren = listDestinationChildren,
        awaitCompletion = awaitCompletion,
    )

    suspend fun captureExternalFileImportTarget(): ExternalFileImportTargetCapture {
        val desk = _deskType.value
        if (desk.menuPermission?.paste != true) {
            return ExternalFileImportTargetCapture(failure = ExternalFileImportFailure.PermissionDenied)
        }
        val capturedPath = _path.value.ifBlank { _rootPath.value.path }
        if (capturedPath.isBlank()) {
            return ExternalFileImportTargetCapture(failure = ExternalFileImportFailure.TargetUnavailable)
        }
        val destination = getFile(capturedPath).getOrNull()
        if (destination == null || !destination.isDirectory) {
            return ExternalFileImportTargetCapture(failure = ExternalFileImportFailure.TargetUnavailable)
        }
        return ExternalFileImportTargetCapture(
            target = ExternalFileImportTarget(
                desk = desk,
                destination = destination,
                capturedPath = destination.path,
            )
        )
    }

    suspend fun pasteExternalFiles(
        target: ExternalFileImportTarget,
        batch: PreparedExternalFileBatch,
        fileOperationState: FileOperationState,
    ): ExternalFileImportResult {
        val currentDestination = entryReader.getFile(target.desk, target.capturedPath).getOrNull()
        if (
            target.desk.menuPermission?.paste != true ||
            currentDestination == null ||
            !currentDestination.isDirectory ||
            currentDestination.protocol != target.destination.protocol ||
            currentDestination.protocolId != target.destination.protocolId
        ) {
            batch.lease?.let { lease -> ExternalFileResourceLeaseRegistry.releaseProducer(lease.id) }
            return ExternalFileImportResult(
                skipped = batch.skipped,
                failure = ExternalFileImportFailure.TargetUnavailable,
            )
        }
        return externalFileImportCoordinator.import(
            target = target.copy(destination = currentDestination),
            batch = batch,
            fileOperationState = fileOperationState,
        )
    }

    // 执行移动粘贴并处理冲突
    suspend fun pasteMoveFile(
        destPath: String,
        srcFiles: List<FileSimpleInfo>,
        fileOperationState: FileOperationState,
    ) = pasteTaskExecutor.pasteMoveFile(
        destPath = destPath,
        srcFiles = srcFiles,
        fileOperationState = fileOperationState,
    )

    // 下载远程文件到目标目录并在完成后打开
    fun downloadRemoteFile(file: FileSimpleInfo, destDirectory: String) =
        taskSubmissionCoordinator.downloadRemoteFile(file, destDirectory)

    // 遍历路径并返回文件流
    fun traversePath(path: String): Flow<Result<List<FileSimpleInfo>>> = entryReader.traversePath(path)

    // 获取指定路径的文件信息
    suspend fun getFile(path: String): Result<FileSimpleInfo> = entryReader.getFile(path)

}

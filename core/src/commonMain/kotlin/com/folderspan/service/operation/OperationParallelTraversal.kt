package com.folderspan.service.operation

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_MAX_LENGTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import strings.AppStrings

private const val LOCAL_TRAVERSAL_INITIAL_PARALLELISM = 8
private const val DEVICE_TRAVERSAL_INITIAL_PARALLELISM = 4
private const val REMOTE_TRAVERSAL_INITIAL_PARALLELISM = 4
private const val LOCAL_TRAVERSAL_MAX_PARALLELISM = 64
private const val DEVICE_TRAVERSAL_MAX_PARALLELISM = 12
private const val SHARE_TRAVERSAL_MAX_PARALLELISM = 32
private const val NETWORK_TRAVERSAL_MAX_PARALLELISM = 16
private const val TRAVERSAL_BUSY_MAX_PARALLELISM = 2
private const val TRAVERSAL_MIN_QUEUE_CAPACITY = 4
private const val TRAVERSAL_QUEUE_CAPACITY_FACTOR = 4
private const val TRAVERSAL_FAST_LISTING_MILLIS = 250L
private const val TRAVERSAL_SLOW_LISTING_MILLIS = 1_500L
private const val TRAVERSAL_CANCEL_POLL_INTERVAL_MS = 250L
private const val TRAVERSAL_EWMA_NEW_SAMPLE_PERCENT = 35

internal data class TraversalParallelismConfig(
    val initialParallelism: Int,
    val maxParallelism: Int,
    val queueCapacity: Int,
    val hardMaxParallelism: Int = maxParallelism,
) {
    /**
     * 将外部传入的遍历并发配置收敛到安全范围，避免 0 或超过硬上限的值进入 worker 调度。
     */
    fun normalized(): TraversalParallelismConfig {
        val safeHardMaxParallelism = hardMaxParallelism.coerceAtLeast(1)
        val safeMaxParallelism = maxParallelism.coerceIn(1, safeHardMaxParallelism)
        val safeInitialParallelism = initialParallelism.coerceIn(1, safeMaxParallelism)
        return copy(
            initialParallelism = safeInitialParallelism,
            maxParallelism = safeMaxParallelism,
            queueCapacity = queueCapacity.coerceAtLeast(TRAVERSAL_MIN_QUEUE_CAPACITY),
            hardMaxParallelism = safeHardMaxParallelism,
        )
    }
}

internal data class TraversalScanProgress(
    val discoveredEntries: Int,
    val currentParallelism: Int,
    val maxParallelism: Int,
    val activeRequests: Int,
    val scannedDirectories: Int,
)

private data class TraversalWorkerResult(
    val directory: FileSimpleInfo,
    val entries: Result<List<FileSimpleInfo>>,
    val durationMillis: Long,
)

/**
 * 读取当前本机运行状态，并结合可选 Device 远端状态计算遍历并发配置。
 *
 * Local、Share、Network 不会使用远端推荐并发；remoteBusy 也只对 Device 生效。
 */
internal fun resolveTraversalParallelism(
    endpointKind: TraversalEndpointKind,
    remoteRecommendedParallelism: Int? = null,
    remoteBusy: Boolean = false,
): TraversalParallelismConfig {
    val runtimePlan = currentTraversalRuntimePlan(endpointKind)
    val endpointRemoteBusy = endpointKind == TraversalEndpointKind.Device && remoteBusy
    return resolveTraversalParallelism(
        endpointKind = endpointKind,
        remoteRecommendedParallelism = remoteRecommendedParallelism,
        runtimeRecommendedParallelism = runtimePlan.recommendedParallelRequests,
        runtimeBusy = runtimePlan.busy || endpointRemoteBusy,
    )
}

/**
 * 根据显式 runtime 参数计算遍历初始并发与最大并发。
 *
 * 只有 Device 会读取 remoteRecommendedParallelism；Local、Share、Network 会忽略远端推荐值。
 */
internal fun resolveTraversalParallelism(
    endpointKind: TraversalEndpointKind,
    remoteRecommendedParallelism: Int?,
    runtimeRecommendedParallelism: Int,
    runtimeBusy: Boolean,
): TraversalParallelismConfig {
    val endpointMax = endpointKind.maxParallelism()
    val endpointInitial = endpointKind.initialParallelism()
    val deviceRemoteRecommendedParallelism = remoteRecommendedParallelism
        .takeIf { endpointKind == TraversalEndpointKind.Device }
    val remoteInitial = deviceRemoteRecommendedParallelism
        ?.coerceIn(1, endpointMax)
        ?: endpointInitial
    val maxParallelism = resolveTraversalRuntimeMaxParallelism(
        endpointKind = endpointKind,
        remoteRecommendedParallelism = deviceRemoteRecommendedParallelism,
        runtimeRecommendedParallelism = runtimeRecommendedParallelism,
        runtimeBusy = runtimeBusy,
    )
    val initialParallelism = minOf(endpointInitial, remoteInitial, maxParallelism).coerceAtLeast(1)
    return TraversalParallelismConfig(
        initialParallelism = initialParallelism,
        maxParallelism = maxParallelism,
        queueCapacity = maxOf(TRAVERSAL_MIN_QUEUE_CAPACITY, maxParallelism * TRAVERSAL_QUEUE_CAPACITY_FACTOR),
        hardMaxParallelism = endpointMax,
    )
}

/**
 * 重新读取当前运行状态并计算遍历阶段此刻允许的最大并发。
 *
 * 用于长时间扫描时动态收缩或放大并发窗口，而不是只在扫描开始时计算一次。
 */
internal fun resolveTraversalRuntimeMaxParallelism(
    endpointKind: TraversalEndpointKind,
    remoteRecommendedParallelism: Int? = null,
    remoteBusy: Boolean = false,
): Int {
    val runtimePlan = currentTraversalRuntimePlan(endpointKind)
    val endpointRemoteBusy = endpointKind == TraversalEndpointKind.Device && remoteBusy
    return resolveTraversalRuntimeMaxParallelism(
        endpointKind = endpointKind,
        remoteRecommendedParallelism = remoteRecommendedParallelism,
        runtimeRecommendedParallelism = runtimePlan.recommendedParallelRequests,
        runtimeBusy = runtimePlan.busy || endpointRemoteBusy,
    )
}

/**
 * 根据显式 runtime 参数计算遍历阶段最大并发，便于测试和复用确定性策略。
 */
internal fun resolveTraversalRuntimeMaxParallelism(
    endpointKind: TraversalEndpointKind,
    remoteRecommendedParallelism: Int?,
    runtimeRecommendedParallelism: Int,
    runtimeBusy: Boolean,
): Int {
    val endpointMax = endpointKind.maxParallelism()
    val runtimeBound = runtimeRecommendedParallelism.coerceIn(1, endpointMax)
    val deviceRemoteRecommendedParallelism = remoteRecommendedParallelism
        .takeIf { endpointKind == TraversalEndpointKind.Device }
    val remoteBound = deviceRemoteRecommendedParallelism
        ?.coerceIn(1, endpointMax)
        ?: endpointMax
    val busyBound = if (runtimeBusy) TRAVERSAL_BUSY_MAX_PARALLELISM else endpointMax
    return minOf(endpointMax, runtimeBound, remoteBound, busyBound).coerceAtLeast(1)
}

/**
 * 并行 BFS 收集目录树条目。
 *
 * 该方法只负责扫描和清单生成阶段：目录会去重，默认在列表失败时 fail-fast，取消时会调用 onCancel。
 * continueOnDirectoryError=true 时会报告失败目录并继续处理其余队列，适用于允许部分结果的统计任务。
 * onDirectoryListed 在协调协程中回调成功列举的目录，供调用方安全地汇总 worker 产生的附加信息。
 * rejectSymbolicLinkEntries=true 时会通过 onRejectedEntry 逐项报告并跳过链接，其余条目继续遍历。
 * retainDiscoveredEntries=false 时仅通过 onEntriesDiscovered 流式消费条目，避免为统计任务保留完整清单。
 * deduplicateDirectories=false 只适用于不会产生目录环的本地、非符号链接遍历，可避免保留全量目录路径。
 * depthFirst=true 会优先处理刚发现的子目录，以缩小宽目录树的待处理队列。
 * 调用方负责传入对应端点的一层列表接口，例如本地列表、设备列表、分享列表或网络列表。
 */
internal suspend fun collectDirectoryEntriesAdaptive(
    root: FileSimpleInfo,
    config: TraversalParallelismConfig,
    pathSeparator: String,
    ensureRunning: suspend () -> Unit = {},
    onCancel: suspend () -> Unit = {},
    onScanProgress: suspend (TraversalScanProgress) -> Unit = {},
    onDirectoryListed: suspend (FileSimpleInfo) -> Unit = {},
    onEntriesDiscovered: suspend (List<FileSimpleInfo>) -> Unit = {},
    dynamicMaxParallelismProvider: (() -> Int)? = null,
    maxParallelismLimit: Int? = null,
    retainDiscoveredEntries: Boolean = true,
    deduplicateDirectories: Boolean = true,
    depthFirst: Boolean = false,
    requireKnownSymbolicLinkMetadata: Boolean = false,
    rejectSymbolicLinkEntries: Boolean = false,
    onRejectedEntry: suspend (FileSimpleInfo, Throwable) -> Unit = { _, _ -> },
    continueOnDirectoryError: Boolean = false,
    onDirectoryError: suspend (FileSimpleInfo, Throwable) -> Unit = { _, _ -> },
    listChildren: suspend (FileSimpleInfo) -> Result<List<FileSimpleInfo>>,
): List<FileSimpleInfo> {
    if (!root.isDirectory || root.isSymbolicLink) return emptyList()
    val normalizedConfig = config.normalized()
    val safeConfig = maxParallelismLimit?.coerceAtLeast(1)?.let { limit ->
        normalizedConfig.copy(
            initialParallelism = minOf(normalizedConfig.initialParallelism, limit),
            maxParallelism = minOf(normalizedConfig.maxParallelism, limit),
            queueCapacity = minOf(
                normalizedConfig.queueCapacity,
                maxOf(TRAVERSAL_MIN_QUEUE_CAPACITY, limit * TRAVERSAL_QUEUE_CAPACITY_FACTOR),
            ),
            hardMaxParallelism = minOf(normalizedConfig.hardMaxParallelism, limit),
        ).normalized()
    } ?: normalizedConfig
    val normalizedSeparator = pathSeparator.ifBlank { "/" }
    val normalizedRootPath = if (retainDiscoveredEntries || deduplicateDirectories) {
        normalizeTraversalPath(root.path, normalizedSeparator)
    } else {
        ""
    }

    return coroutineScope {
        val workQueue = Channel<FileSimpleInfo>(safeConfig.queueCapacity)
        val resultQueue = Channel<TraversalWorkerResult>(safeConfig.queueCapacity)
        val parallelismController = TraversalParallelismController(
            config = safeConfig,
            dynamicMaxParallelismProvider = dynamicMaxParallelismProvider ?: { safeConfig.maxParallelism },
        )
        val workers = List(safeConfig.hardMaxParallelism) {
            launch(Dispatchers.Default) {
                for (directory in workQueue) {
                    val startMillis = Clock.System.now().toEpochMilliseconds()
                    val entries = runCatching {
                        ensureRunning()
                        listChildren(directory).getOrElse { failure -> throw failure }
                    }
                    val durationMillis = Clock.System.now().toEpochMilliseconds() - startMillis
                    resultQueue.send(TraversalWorkerResult(directory, entries, durationMillis))
                }
            }
        }

        val pendingDirectories = ArrayDeque<FileSimpleInfo>()
        val visitedDirectories = if (deduplicateDirectories) mutableSetOf(normalizedRootPath) else null
        val emittedEntries = if (retainDiscoveredEntries) mutableSetOf(normalizedRootPath) else null
        val collectedEntries = if (retainDiscoveredEntries) mutableListOf<FileSimpleInfo>() else null
        var discoveredEntryCount = 0
        var activeRequests = 0
        var scannedDirectories = 0

        try {
            pendingDirectories.add(root)
            while (pendingDirectories.isNotEmpty() || activeRequests > 0) {
                currentCoroutineContext().ensureActive()
                ensureRunning()

                while (pendingDirectories.isNotEmpty() && parallelismController.canDispatch(activeRequests)) {
                    workQueue.send(pendingDirectories.removeFirst())
                    activeRequests++
                }

                if (activeRequests == 0) continue

                val workerResult = receiveWorkerResult(
                    resultQueue = resultQueue,
                    ensureRunning = ensureRunning,
                )
                activeRequests--
                val listFailure = workerResult.entries.exceptionOrNull()
                if (listFailure != null) {
                    parallelismController.recordResult(
                        durationMillis = workerResult.durationMillis,
                        success = false,
                        hasBacklog = pendingDirectories.isNotEmpty() || activeRequests > 0,
                    )
                    if (listFailure is CancellationException || !continueOnDirectoryError) {
                        throw listFailure
                    }
                    scannedDirectories++
                    onDirectoryError(workerResult.directory, listFailure)
                    onScanProgress(
                        TraversalScanProgress(
                            discoveredEntries = discoveredEntryCount,
                            currentParallelism = parallelismController.currentParallelism,
                            maxParallelism = parallelismController.currentMaxParallelism,
                            activeRequests = activeRequests,
                            scannedDirectories = scannedDirectories,
                        )
                    )
                    continue
                }
                val listedEntries = workerResult.entries.getOrThrow()
                if (
                    requireKnownSymbolicLinkMetadata &&
                    listedEntries.any { entry -> entry.isDirectory && !entry.isSymbolicLinkKnown }
                ) {
                    throw IllegalStateException(AppStrings.ui_no_trustworthy_metadata_for_remote_links_please_upgrade_the_remote_and_retry)
                }
                scannedDirectories++
                onDirectoryListed(workerResult.directory)
                val directoriesQueuedDuringFiltering = emittedEntries == null && visitedDirectories != null
                val uniqueEntries = when {
                    emittedEntries != null -> listedEntries.filter { entry ->
                        emittedEntries.add(normalizeTraversalPath(entry.path, normalizedSeparator))
                    }

                    visitedDirectories != null -> {
                        val batchPaths = mutableSetOf<String>()
                        listedEntries.filter { entry ->
                            val key = normalizeTraversalPath(entry.path, normalizedSeparator)
                            val traversableDirectory =
                                entry.isDirectory &&
                                    !entry.isSymbolicLink &&
                                    (!requireKnownSymbolicLinkMetadata || entry.isSymbolicLinkKnown)
                            batchPaths.add(key) && (!traversableDirectory || visitedDirectories.add(key))
                        }
                    }

                    else -> listedEntries
                }
                val rejectedEntries = if (rejectSymbolicLinkEntries) {
                    uniqueEntries.filter { entry -> entry.isSymbolicLink }
                } else {
                    emptyList()
                }
                rejectedEntries.forEach { entry ->
                    onRejectedEntry(
                        entry,
                        IllegalStateException(AppStrings.file_symbolic_link_copy_not_supported),
                    )
                }
                val acceptedEntries = if (rejectedEntries.isEmpty()) {
                    uniqueEntries
                } else {
                    uniqueEntries.filterNot { entry -> entry.isSymbolicLink }
                }
                if (uniqueEntries.isNotEmpty()) {
                    discoveredEntryCount += uniqueEntries.size
                }
                if (acceptedEntries.isNotEmpty()) {
                    collectedEntries?.addAll(acceptedEntries)
                    onEntriesDiscovered(acceptedEntries)
                }
                if (acceptedEntries.isNotEmpty()) {
                    val entriesToQueue = if (depthFirst) acceptedEntries.asReversed() else acceptedEntries
                    entriesToQueue
                        .asSequence()
                        .filter { entry ->
                            entry.isDirectory &&
                                !entry.isSymbolicLink &&
                                (!requireKnownSymbolicLinkMetadata || entry.isSymbolicLinkKnown)
                        }
                        .forEach { entry ->
                            val shouldQueue = when {
                                directoriesQueuedDuringFiltering -> true
                                visitedDirectories == null -> true
                                else -> {
                                    val key = normalizeTraversalPath(entry.path, normalizedSeparator)
                                    visitedDirectories.add(key)
                                }
                            }
                            if (shouldQueue) {
                                if (depthFirst) pendingDirectories.addFirst(entry) else pendingDirectories.addLast(entry)
                            }
                        }
                }
                parallelismController.recordResult(
                    durationMillis = workerResult.durationMillis,
                    success = true,
                    hasBacklog = pendingDirectories.isNotEmpty() || activeRequests > 0,
                )
                onScanProgress(
                    TraversalScanProgress(
                        discoveredEntries = discoveredEntryCount,
                        currentParallelism = parallelismController.currentParallelism,
                        maxParallelism = parallelismController.currentMaxParallelism,
                        activeRequests = activeRequests,
                        scannedDirectories = scannedDirectories,
                    )
                )
            }
            workQueue.close()
            workers.joinAll()
            collectedEntries?.toList().orEmpty()
        } catch (failure: Throwable) {
            runCatching { onCancel() }
            workQueue.close(failure)
            resultQueue.close(failure)
            workers.forEach { worker -> worker.cancelAndJoin() }
            throw failure
        } finally {
            workQueue.close()
            resultQueue.close()
        }
    }
}

/**
 * 等待一个 worker 返回结果，同时周期性调用 ensureRunning，避免暂停/取消时被长时间挂在 receive 上。
 */
private suspend fun receiveWorkerResult(
    resultQueue: Channel<TraversalWorkerResult>,
    ensureRunning: suspend () -> Unit,
): TraversalWorkerResult {
    while (true) {
        currentCoroutineContext().ensureActive()
        ensureRunning()
        withTimeoutOrNull(TRAVERSAL_CANCEL_POLL_INTERVAL_MS.milliseconds) {
            resultQueue.receive()
        }?.let { result -> return result }
    }
}

private class TraversalParallelismController(
    config: TraversalParallelismConfig,
    private val dynamicMaxParallelismProvider: () -> Int,
) {
    var currentParallelism: Int = config.initialParallelism
        private set

    var currentMaxParallelism: Int = config.maxParallelism
        private set

    private val hardMaxParallelism = config.hardMaxParallelism
    private var fastSuccesses = 0
    private var latencyEwmaMillis: Double? = null

    fun canDispatch(activeRequests: Int): Boolean {
        refreshMaxParallelism()
        return activeRequests < currentParallelism
    }

    /**
     * 根据单次列表耗时和队列积压情况调整遍历并发窗口。
     *
     * 快速且有积压时逐步加并发；慢请求或失败时收缩并发。
     */
    fun recordResult(durationMillis: Long, success: Boolean, hasBacklog: Boolean) {
        val maxParallelism = refreshMaxParallelism()
        if (!success) {
            fastSuccesses = 0
            currentParallelism = maxOf(1, currentParallelism / 2).coerceAtMost(maxParallelism)
            return
        }

        val previousEwmaMillis = latencyEwmaMillis
        val nextEwmaMillis = if (previousEwmaMillis == null) {
            durationMillis.toDouble()
        } else {
            (
                previousEwmaMillis * (100 - TRAVERSAL_EWMA_NEW_SAMPLE_PERCENT) +
                    durationMillis * TRAVERSAL_EWMA_NEW_SAMPLE_PERCENT
                ) / 100.0
        }
        latencyEwmaMillis = nextEwmaMillis

        when {
            durationMillis >= TRAVERSAL_SLOW_LISTING_MILLIS ||
                nextEwmaMillis >= TRAVERSAL_SLOW_LISTING_MILLIS -> {
                fastSuccesses = 0
                currentParallelism = maxOf(1, currentParallelism - maxOf(1, currentParallelism / 4))
            }

            hasBacklog &&
                durationMillis <= TRAVERSAL_FAST_LISTING_MILLIS &&
                nextEwmaMillis <= TRAVERSAL_FAST_LISTING_MILLIS -> {
                fastSuccesses++
                if (fastSuccesses >= currentParallelism && currentParallelism < maxParallelism) {
                    currentParallelism += maxOf(1, currentParallelism / 4)
                    currentParallelism = currentParallelism.coerceAtMost(maxParallelism)
                    fastSuccesses = 0
                }
            }

            else -> fastSuccesses = 0
        }
    }

    private fun refreshMaxParallelism(): Int {
        currentMaxParallelism = dynamicMaxParallelismProvider()
            .coerceIn(1, hardMaxParallelism)
        currentParallelism = currentParallelism.coerceIn(1, currentMaxParallelism)
        return currentMaxParallelism
    }
}

private fun currentTraversalRuntimePlan(endpointKind: TraversalEndpointKind) = HttpTransferRuntimeTuning.plan(
    maxChunkBytes = DEVICE_DIRECT_MAX_LENGTH,
    maxParallelRequests = endpointKind.maxParallelism(),
)

private fun TraversalEndpointKind.initialParallelism(): Int {
    return when (this) {
        TraversalEndpointKind.Local -> LOCAL_TRAVERSAL_INITIAL_PARALLELISM
        TraversalEndpointKind.Device -> DEVICE_TRAVERSAL_INITIAL_PARALLELISM
        TraversalEndpointKind.Share,
        TraversalEndpointKind.Network -> REMOTE_TRAVERSAL_INITIAL_PARALLELISM
    }
}

private fun TraversalEndpointKind.maxParallelism(): Int {
    return when (this) {
        TraversalEndpointKind.Local -> LOCAL_TRAVERSAL_MAX_PARALLELISM
        TraversalEndpointKind.Device -> DEVICE_TRAVERSAL_MAX_PARALLELISM
        TraversalEndpointKind.Share -> SHARE_TRAVERSAL_MAX_PARALLELISM
        TraversalEndpointKind.Network -> NETWORK_TRAVERSAL_MAX_PARALLELISM
    }
}

private fun normalizeTraversalPath(path: String, separator: String): String {
    val normalizedSeparator = separator.ifBlank { "/" }
    var normalized = path.trim()
    normalized = if (normalizedSeparator == "/") {
        normalized.replace('\\', '/')
    } else {
        normalized.replace("/", normalizedSeparator)
    }
    while (normalized.length > normalizedSeparator.length && normalized.endsWith(normalizedSeparator)) {
        normalized = normalized.dropLast(normalizedSeparator.length)
    }
    return normalized.ifBlank { normalizedSeparator }
}

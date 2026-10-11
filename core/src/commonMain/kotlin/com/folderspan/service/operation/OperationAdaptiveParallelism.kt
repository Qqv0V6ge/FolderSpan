package com.folderspan.service.operation

import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_MAX_LENGTH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

private const val LOCAL_OPERATION_INITIAL_PARALLELISM = 6
private const val DEVICE_OPERATION_INITIAL_PARALLELISM = 8
private const val REMOTE_OPERATION_INITIAL_PARALLELISM = 4
private const val LOCAL_OPERATION_MAX_PARALLELISM = 24
private const val DEVICE_OPERATION_MAX_PARALLELISM = 24
private const val SHARE_OPERATION_MAX_PARALLELISM = 16
private const val NETWORK_OPERATION_MAX_PARALLELISM = 12
private const val OPERATION_BUSY_MAX_PARALLELISM = 2
private const val OPERATION_FAST_MILLIS = 500L
private const val OPERATION_SLOW_MILLIS = 4_000L
private const val OPERATION_ACQUIRE_POLL_MILLIS = 10L
private const val OPERATION_QUEUE_CAPACITY_FACTOR = 4
private const val OPERATION_MIN_QUEUE_CAPACITY = 4
private const val OPERATION_EWMA_NEW_SAMPLE_PERCENT = 35

internal data class OperationParallelismConfig(
    val initialParallelism: Int,
    val maxParallelism: Int,
    val queueCapacity: Int,
    val hardMaxParallelism: Int = maxParallelism,
) {
    /**
     * 将执行阶段并发配置收敛到安全范围，避免非法值进入 worker 池或限流器。
     */
    fun normalized(): OperationParallelismConfig {
        val safeHardMax = hardMaxParallelism.coerceAtLeast(1)
        val safeMax = maxParallelism.coerceIn(1, safeHardMax)
        return copy(
            initialParallelism = initialParallelism.coerceIn(1, safeMax),
            maxParallelism = safeMax,
            queueCapacity = queueCapacity.coerceAtLeast(OPERATION_MIN_QUEUE_CAPACITY),
            hardMaxParallelism = safeHardMax,
        )
    }
}

/**
 * 读取当前本机运行状态，并结合可选 Device 远端状态计算执行阶段并发配置。
 *
 * Local、Share、Network 不会使用远端推荐并发；remoteBusy 也只对 Device 生效。
 */
internal fun resolveOperationParallelism(
    endpointKind: TraversalEndpointKind,
    remoteRecommendedParallelism: Int? = null,
    remoteBusy: Boolean = false,
): OperationParallelismConfig {
    val runtimePlan = currentOperationRuntimePlan(endpointKind)
    val endpointRemoteBusy = endpointKind == TraversalEndpointKind.Device && remoteBusy
    return resolveOperationParallelism(
        endpointKind = endpointKind,
        remoteRecommendedParallelism = remoteRecommendedParallelism,
        runtimeRecommendedParallelism = runtimePlan.recommendedParallelRequests,
        runtimeBusy = runtimePlan.busy || endpointRemoteBusy,
    )
}

/**
 * 根据显式 runtime 参数计算执行阶段并发配置。
 *
 * 执行阶段用于复制文件、批量处理文件和分片传输；Device 会叠加远端状态，其他端点忽略远端推荐值。
 */
internal fun resolveOperationParallelism(
    endpointKind: TraversalEndpointKind,
    remoteRecommendedParallelism: Int?,
    runtimeRecommendedParallelism: Int,
    runtimeBusy: Boolean,
): OperationParallelismConfig {
    val endpointMax = endpointKind.operationMaxParallelism()
    val endpointInitial = endpointKind.operationInitialParallelism()
    val deviceRemoteRecommended = remoteRecommendedParallelism
        .takeIf { endpointKind == TraversalEndpointKind.Device }
    val maxParallelism = resolveOperationRuntimeMaxParallelism(
        endpointKind = endpointKind,
        remoteRecommendedParallelism = deviceRemoteRecommended,
        runtimeRecommendedParallelism = runtimeRecommendedParallelism,
        runtimeBusy = runtimeBusy,
    )
    val initialParallelism = minOf(
        endpointInitial,
        deviceRemoteRecommended?.coerceIn(1, endpointMax) ?: endpointInitial,
        maxParallelism,
    ).coerceAtLeast(1)
    return OperationParallelismConfig(
        initialParallelism = initialParallelism,
        maxParallelism = maxParallelism,
        queueCapacity = maxOf(OPERATION_MIN_QUEUE_CAPACITY, maxParallelism * OPERATION_QUEUE_CAPACITY_FACTOR),
        hardMaxParallelism = endpointMax,
    )
}

/**
 * 重新读取当前运行状态并计算执行阶段此刻允许的最大并发。
 *
 * 用于正在执行的 worker 池动态调整窗口。
 */
internal fun resolveOperationRuntimeMaxParallelism(
    endpointKind: TraversalEndpointKind,
    remoteRecommendedParallelism: Int? = null,
    remoteBusy: Boolean = false,
): Int {
    val runtimePlan = currentOperationRuntimePlan(endpointKind)
    val endpointRemoteBusy = endpointKind == TraversalEndpointKind.Device && remoteBusy
    return resolveOperationRuntimeMaxParallelism(
        endpointKind = endpointKind,
        remoteRecommendedParallelism = remoteRecommendedParallelism,
        runtimeRecommendedParallelism = runtimePlan.recommendedParallelRequests,
        runtimeBusy = runtimePlan.busy || endpointRemoteBusy,
    )
}

/**
 * 根据显式 runtime 参数计算执行阶段最大并发，便于测试动态并发策略。
 */
internal fun resolveOperationRuntimeMaxParallelism(
    endpointKind: TraversalEndpointKind,
    remoteRecommendedParallelism: Int?,
    runtimeRecommendedParallelism: Int,
    runtimeBusy: Boolean,
): Int {
    val endpointMax = endpointKind.operationMaxParallelism()
    val runtimeBound = runtimeRecommendedParallelism.coerceIn(1, endpointMax)
    val remoteBound = remoteRecommendedParallelism
        .takeIf { endpointKind == TraversalEndpointKind.Device }
        ?.coerceIn(1, endpointMax)
        ?: endpointMax
    val busyBound = if (runtimeBusy) OPERATION_BUSY_MAX_PARALLELISM else endpointMax
    return minOf(endpointMax, runtimeBound, remoteBound, busyBound).coerceAtLeast(1)
}

/**
 * 使用自适应限流处理一组条目。
 *
 * worker 数按 hardMaxParallelism 创建，但真正同时执行的数量由 AdaptiveOperationLimiter 动态控制。
 * processItem 抛出异常会向外传播，调用方可以依赖协程取消停止后续派发。
 */
internal suspend fun <T> processItemsAdaptive(
    items: List<T>,
    config: OperationParallelismConfig,
    ensureRunning: suspend () -> Unit = {},
    dynamicMaxParallelismProvider: (() -> Int)? = null,
    onActiveParallelismChanged: suspend (Int) -> Unit = {},
    processItem: suspend (T) -> Unit,
) {
    if (items.isEmpty()) return
    val safeConfig = config.normalized()
    val limiter = AdaptiveOperationLimiter(
        config = safeConfig,
        dynamicMaxParallelismProvider = dynamicMaxParallelismProvider ?: { safeConfig.maxParallelism },
        onActiveParallelismChanged = onActiveParallelismChanged,
    )
    coroutineScope {
        val queue = Channel<T>(safeConfig.queueCapacity)
        val workers = List(minOf(safeConfig.hardMaxParallelism, items.size).coerceAtLeast(1)) {
            launch(Dispatchers.Default) {
                for (item in queue) {
                    limiter.withPermit {
                        ensureRunning()
                        processItem(item)
                    }
                }
            }
        }

        try {
            for (item in items) {
                currentCoroutineContext().ensureActive()
                ensureRunning()
                queue.send(item)
            }
        } finally {
            queue.close()
        }
        workers.joinAll()
    }
}

private class AdaptiveOperationLimiter(
    config: OperationParallelismConfig,
    private val dynamicMaxParallelismProvider: () -> Int,
    private val onActiveParallelismChanged: suspend (Int) -> Unit,
) {
    private val mutex = Mutex()
    private val minLimit = 1
    private val hardMax = config.hardMaxParallelism
    private var currentLimit = config.initialParallelism
    private var activeRequests = 0
    private var fastSuccesses = 0
    private var latencyEwmaMillis: Double? = null

    /**
     * 获取动态许可并执行一个操作，结束时根据耗时和成败反馈调整并发窗口。
     */
    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        val startedAt = Clock.System.now().toEpochMilliseconds()
        var success = false
        try {
            val result = block()
            success = true
            return result
        } finally {
            val duration = Clock.System.now().toEpochMilliseconds() - startedAt
            withContext(NonCancellable) {
                release(durationMillis = duration, success = success)
            }
        }
    }

    private suspend fun acquire() {
        while (true) {
            var activeCount: Int? = null
            mutex.withLock {
                clampLimitLocked()
                if (activeRequests < currentLimit) {
                    activeRequests++
                    activeCount = activeRequests
                }
            }
            activeCount?.let { count ->
                onActiveParallelismChanged(count)
                return
            }
            delay(OPERATION_ACQUIRE_POLL_MILLIS.milliseconds)
        }
    }

    private suspend fun release(durationMillis: Long, success: Boolean) {
        var activeCount = 0
        mutex.withLock {
            activeRequests = (activeRequests - 1).coerceAtLeast(0)
            activeCount = activeRequests
            val runtimeMax = clampLimitLocked()
            if (!success) {
                fastSuccesses = 0
                currentLimit = maxOf(minLimit, currentLimit / 2).coerceAtMost(runtimeMax)
                return@withLock
            }

            val previousEwma = latencyEwmaMillis
            val nextEwma = if (previousEwma == null) {
                durationMillis.toDouble()
            } else {
                (
                    previousEwma * (100 - OPERATION_EWMA_NEW_SAMPLE_PERCENT) +
                        durationMillis * OPERATION_EWMA_NEW_SAMPLE_PERCENT
                    ) / 100.0
            }
            latencyEwmaMillis = nextEwma

            when {
                durationMillis >= OPERATION_SLOW_MILLIS || nextEwma >= OPERATION_SLOW_MILLIS -> {
                    fastSuccesses = 0
                    currentLimit = maxOf(minLimit, currentLimit - maxOf(1, currentLimit / 4))
                }

                durationMillis <= OPERATION_FAST_MILLIS && nextEwma <= OPERATION_FAST_MILLIS -> {
                    fastSuccesses++
                    if (fastSuccesses >= currentLimit && currentLimit < runtimeMax) {
                        currentLimit = (currentLimit + maxOf(1, currentLimit / 4)).coerceAtMost(runtimeMax)
                        fastSuccesses = 0
                    }
                }

                else -> fastSuccesses = 0
            }
        }
        onActiveParallelismChanged(activeCount)
    }

    private fun clampLimitLocked(): Int {
        val runtimeMax = dynamicMaxParallelismProvider().coerceIn(minLimit, hardMax)
        currentLimit = currentLimit.coerceIn(minLimit, runtimeMax)
        return runtimeMax
    }
}

private fun currentOperationRuntimePlan(endpointKind: TraversalEndpointKind) = HttpTransferRuntimeTuning.plan(
    maxChunkBytes = DEVICE_DIRECT_MAX_LENGTH,
    maxParallelRequests = endpointKind.operationMaxParallelism(),
)

private fun TraversalEndpointKind.operationInitialParallelism(): Int {
    return when (this) {
        TraversalEndpointKind.Local -> LOCAL_OPERATION_INITIAL_PARALLELISM
        TraversalEndpointKind.Device -> DEVICE_OPERATION_INITIAL_PARALLELISM
        TraversalEndpointKind.Share,
        TraversalEndpointKind.Network -> REMOTE_OPERATION_INITIAL_PARALLELISM
    }
}

private fun TraversalEndpointKind.operationMaxParallelism(): Int {
    return when (this) {
        TraversalEndpointKind.Local -> LOCAL_OPERATION_MAX_PARALLELISM
        TraversalEndpointKind.Device -> DEVICE_OPERATION_MAX_PARALLELISM
        TraversalEndpointKind.Share -> SHARE_OPERATION_MAX_PARALLELISM
        TraversalEndpointKind.Network -> NETWORK_OPERATION_MAX_PARALLELISM
    }
}

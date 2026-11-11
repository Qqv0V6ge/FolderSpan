package com.folderspan.service.session

import com.folderspan.service.file.DeviceFileClient
import com.folderspan.service.operation.AdaptiveOperationLimiter
import com.folderspan.service.operation.HTTP_TRANSFER_STATUS_FRESH_MILLIS
import com.folderspan.service.operation.HttpTransferRuntimeTuning
import com.folderspan.service.operation.HttpTransferStatus
import com.folderspan.service.operation.OperationParallelismConfig
import com.folderspan.service.operation.ensureOperationTransferRunning
import com.folderspan.service.operation.TransferWaitReason
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

internal interface DeviceTransferBudgetOwner {
    val transferBudget: DeviceSessionTransferBudget
}

private val nextBudgetOrder = MutableStateFlow(0L)
private class HeldDeviceTransferBudgets(val budgets: Set<DeviceSessionTransferBudget>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HeldDeviceTransferBudgets>
}

internal class DeviceSessionTransferBudget(
    private val hardMax: Int,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    internal val order = nextBudgetOrder.updateAndGet { it + 1 }
    private val closed = MutableStateFlow(false)
    private var negotiatedMax = hardMax
    private var statusProvider: (() -> HttpTransferStatus)? = null
    private val limiter = AdaptiveOperationLimiter(
        OperationParallelismConfig(1, hardMax, 4, hardMax).normalized(),
        dynamicMaxParallelismProvider = {
            val remote = status()
            minOf(negotiatedMax, remote?.recommendedParallelRequests ?: 1,
                HttpTransferRuntimeTuning.plan(64 * 1024, hardMax).recommendedParallelRequests).coerceAtLeast(1)
        },
        onActiveParallelismChanged = {},
        canDispatch = {
            checkOpen()
            val remote = status()
            remote == null || (!remote.busy && remote.retryAfterMillis <= 0)
        },
        reuseParentFeedback = true,
        waitingReason = {
            status()?.let { remote -> when {
                remote.retryAfterMillis > 0 -> TransferWaitReason.Backoff
                remote.busy -> TransferWaitReason.DeviceBusy
                else -> null
            } }
        },
    )

    fun bind(maxParallelism: Int, provider: () -> HttpTransferStatus) {
        negotiatedMax = minOf(negotiatedMax, maxParallelism.coerceAtLeast(1))
        statusProvider = provider
    }

    private fun status(): HttpTransferStatus? {
        val sample = statusProvider?.invoke()?.clamped() ?: return null
        val now = nowMillis()
        return sample.takeIf { it.sampledAtMillis > 0 && now >= it.sampledAtMillis &&
            now - it.sampledAtMillis < HTTP_TRANSFER_STATUS_FRESH_MILLIS }
    }

    fun close() { closed.value = true }
    private fun checkOpen() { if (closed.value) throw DeviceSessionClosedException() }
    internal suspend fun parallelismLimit(): Int = limiter.parallelismLimit()

    suspend fun <T> withPermit(block: suspend () -> T): T {
        val held = currentCoroutineContext()[HeldDeviceTransferBudgets]?.budgets.orEmpty()
        if (this in held) { checkOpen(); return block() }
        return limiter.withPermit(ensureRunning = { checkOpen(); ensureOperationTransferRunning() }) {
            withContext(HeldDeviceTransferBudgets(held + this@DeviceSessionTransferBudget)) {
                checkOpen(); ensureOperationTransferRunning(); block()
            }
        }
    }
}

internal suspend fun <T> withDeviceTransferBudgets(vararg clients: DeviceFileClient?, block: suspend () -> T): T {
    val budgets = clients.mapNotNull { (it as? DeviceTransferBudgetOwner)?.transferBudget }.distinct().sortedBy { it.order }
    // Acquire both endpoints in one order, including opposite-direction relays with a one-file budget.
    suspend fun acquire(index: Int): T = if (index == budgets.size) block()
        else budgets[index].withPermit { acquire(index + 1) }
    return acquire(0)
}

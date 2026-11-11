package com.folderspan.service.operation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private class OperationTransferControl(val ensureRunning: suspend () -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OperationTransferControl>
}

/** Uses the caller's task control; failures propagate and this function adds no concurrency allowance. */
internal suspend fun ensureOperationTransferRunning() {
    currentCoroutineContext().ensureActive()
    currentCoroutineContext()[OperationTransferControl]?.ensureRunning?.invoke()
}

/** Polls only this operation's supplied control; pause waits and cancellation ends this scope without closing a session. */
internal suspend fun <T> withOperationTransferControl(ensureRunning: suspend () -> Unit, block: suspend () -> T): T {
    if (currentCoroutineContext()[OperationTransferControl] != null) {
        ensureRunning()
        return block()
    }
    return coroutineScope {
        val monitor = launch {
            try {
                while (isActive) { ensureRunning(); delay(50) }
            } catch (cancelled: CancellationException) {
                if (currentCoroutineContext().isActive) this@coroutineScope.cancel(cancelled)
                throw cancelled
            }
        }
        try { withContext(OperationTransferControl(ensureRunning)) { ensureRunning(); block() } }
        finally { monitor.cancel() }
    }
}

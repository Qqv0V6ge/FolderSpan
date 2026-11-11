package com.folderspan.service.operation

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Local observations of an active transfer wait; these values are never part of a wire message. */
enum class TransferWaitReason { DeviceBusy, Receiver, Backoff }

internal class OperationTransferWaitObserver(
    val changed: (TransferWaitReason, Boolean) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OperationTransferWaitObserver>
}

/** The caller must close this observation in finally; cancellation does not change transfer outcome. */
internal suspend fun observeOperationTransferWait(reason: TransferWaitReason): () -> Unit {
    val observer = currentCoroutineContext()[OperationTransferWaitObserver] ?: return {}
    observer.changed(reason, true)
    var closed = false
    return {
        if (!closed) {
            closed = true
            observer.changed(reason, false)
        }
    }
}

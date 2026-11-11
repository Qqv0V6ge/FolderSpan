package com.folderspan.ui.state.file

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import strings.AppStrings

internal data class PreparedCopyDestination(
    val destination: CopyDestination,
    val operations: List<PendingFileOperation>,
    val requestId: String = "",
    val planId: String = newExternalFileLeaseId(),
    val validate: suspend () -> Unit,
)

data class CopyDestinationReceipt(val destination: CopyDestination, val taskKeys: List<Long>)

data class CopyBatchAdmissionResult(
    val accepted: List<CopyDestinationReceipt> = emptyList(),
    val remaining: List<CopyDestination> = emptyList(),
    val error: String? = null,
) {
    val fullyAccepted: Boolean get() = error == null && remaining.isEmpty() && accepted.isNotEmpty()
}

/** One coordinator per file runtime; a request keeps its receipts across failed submissions. */
internal class CopyBatchAdmission(
    private val runtimeId: String,
    private val prepare: suspend (CopyDestinationState, List<CopyDestination>) -> List<PreparedCopyDestination>,
    private val enqueue: suspend (PreparedCopyDestination) -> List<Long>,
) {
    private class Request(val owner: CopyDestinationState) {
        val mutex = Mutex()
        val accepted = mutableListOf<CopyDestinationReceipt>()
    }
    private val requests = mutableMapOf<String, Request>()
    private val requestMutex = Mutex()

    suspend fun submit(draft: CopyDestinationState): CopyBatchAdmissionResult {
        val request = requestMutex.withLock {
            requests.getOrPut(draft.requestId) { Request(draft) }.also {
                require(it.owner === draft && draft.runtimeId == runtimeId) { AppStrings.ui_target_endpoint_unavailable }
            }
        }
        return request.mutex.withLock {
            draft.sourceMutationMutex.withLock { draft.lockSources(true) }
            try {
                val pending = draft.selected.value.filter { target ->
                    request.accepted.none { it.destination.directory.sameLocation(target.directory) }
                }
                fun result(error: String? = null) = CopyBatchAdmissionResult(
                    accepted = request.accepted.toList(),
                    remaining = draft.selected.value.filter { target ->
                        request.accepted.none { it.destination.directory.sameLocation(target.directory) }
                    },
                    error = error,
                )
                if (pending.isEmpty()) return@withLock result()
                val prepared = try {
                    require(draft.sources.isNotEmpty()) { AppStrings.ui_no_files_process }
                    require(pending.all { it.unavailableReason == null }) { AppStrings.ui_target_unavailable }
                    pending.forEach { requireWritableCopyDestination(it.directory.endpoint.endpoint.protocol) }
                    prepare(draft, pending).also { plans ->
                        require(plans.size == pending.size && pending.all { target ->
                            plans.count { it.destination.directory.sameLocation(target.directory) } == 1
                        }) { AppStrings.ui_target_unavailable }
                        plans.forEach { it.validate() }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { return@withLock result(error.message ?: AppStrings.ui_file_copy_failed) }
                for (plan in prepared) {
                    try {
                        val keys = enqueue(plan)
                        check(keys.isNotEmpty()) { AppStrings.ui_file_copy_failed }
                        request.accepted += CopyDestinationReceipt(plan.destination, keys)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { return@withLock result(error.message ?: AppStrings.ui_file_copy_failed) }
                }
                result()
            } finally {
                draft.lockSources(request.accepted.isNotEmpty())
            }
        }
    }

    suspend fun release(requestId: String) {
        requestMutex.withLock { requests.remove(requestId) }
    }
}

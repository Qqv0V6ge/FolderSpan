package com.folderspan.service.http.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

typealias HttpClientRequestId = String
typealias HttpClientBatchId = String

class HttpClientRequestCancelledException(
    val requestId: HttpClientRequestId,
    val batchId: HttpClientBatchId?,
    message: String = "HTTP request cancelled",
) : CancellationException(message)

internal class HttpClientRequestRegistry {
    private data class Entry(
        val token: Long,
        val job: Job,
        val batchId: HttpClientBatchId?,
    )

    private data class Registration(
        val requestId: HttpClientRequestId,
        val token: Long,
    )

    private data class RegisterResult(
        val registration: Registration,
        val replacedJob: Job?,
        val replacedCancellation: HttpClientRequestCancelledException?,
    )

    private val mutex = Mutex()
    private val requestsById = mutableMapOf<HttpClientRequestId, Entry>()
    private val requestIdsByBatch = mutableMapOf<HttpClientBatchId, MutableSet<HttpClientRequestId>>()
    private var nextAutoId = 0L
    private var nextToken = 0L

    suspend fun <T> track(
        requestId: HttpClientRequestId? = null,
        batchId: HttpClientBatchId? = null,
        block: suspend CoroutineScope.() -> T,
    ): T {
        return supervisorScope {
            val deferred = async(start = CoroutineStart.LAZY) { block() }
            val registerResult = register(deferred, requestId, batchId)

            registerResult.replacedJob?.cancel(registerResult.replacedCancellation)
            deferred.start()

            try {
                deferred.await()
            } finally {
                unregister(registerResult.registration)
            }
        }
    }

    suspend fun <T> trackResult(
        requestId: HttpClientRequestId? = null,
        batchId: HttpClientBatchId? = null,
        block: suspend CoroutineScope.() -> Result<T>,
    ): Result<T> {
        return try {
            track(requestId, batchId, block)
        } catch (e: CancellationException) {
            Result.failure(e)
        }
    }

    suspend fun cancelRequest(
        requestId: HttpClientRequestId,
        reason: String = "HTTP request cancelled",
    ): Boolean {
        val trimmedId = requestId.trim()
        if (trimmedId.isEmpty()) return false

        val (job, batchId) = mutex.withLock {
            val entry = requestsById.remove(trimmedId) ?: return false
            entry.batchId?.let { item ->  removeFromBatch(item, trimmedId) }
            entry.job to entry.batchId
        }

        job.cancel(HttpClientRequestCancelledException(trimmedId, batchId, reason))
        return true
    }

    suspend fun cancelBatch(
        batchId: HttpClientBatchId,
        reason: String = "HTTP request batch cancelled",
    ): Int {
        val trimmedBatchId = batchId.trim()
        if (trimmedBatchId.isEmpty()) return 0

        val cancellations = mutex.withLock {
            val requestIds = requestIdsByBatch.remove(trimmedBatchId).orEmpty()
            requestIds.mapNotNull { requestId ->
                val entry = requestsById.remove(requestId) ?: return@mapNotNull null
                entry.job to HttpClientRequestCancelledException(requestId, trimmedBatchId, reason)
            }
        }

        cancellations.forEach { (job, cancellation) -> job.cancel(cancellation) }
        return cancellations.size
    }

    suspend fun cancelAll(reason: String = "All HTTP requests cancelled"): Int {
        val jobsToCancel = mutex.withLock {
            val entries = requestsById.toList()
            requestsById.clear()
            requestIdsByBatch.clear()
            entries.map { (id, entry) ->
                entry.job to HttpClientRequestCancelledException(id, entry.batchId, reason)
            }
        }

        jobsToCancel.forEach { (job, cancellation) -> job.cancel(cancellation) }
        return jobsToCancel.size
    }

    private suspend fun register(
        job: Deferred<*>,
        requestId: HttpClientRequestId?,
        batchId: HttpClientBatchId?,
    ): RegisterResult {
        val trimmedRequestId = requestId?.trim().orEmpty()
        return mutex.withLock {
            val idToUse = trimmedRequestId.ifEmpty { nextAutoRequestId() }
            val token = ++nextToken

            val existing = requestsById.remove(idToUse)
            existing?.batchId?.let { item ->  removeFromBatch(item, idToUse) }

            requestsById[idToUse] = Entry(token = token, job = job, batchId = batchId)
            batchId?.trim()?.takeIf { item ->  item.isNotEmpty() }?.let { trimmedBatchId ->
                requestIdsByBatch.getOrPut(trimmedBatchId) { mutableSetOf() }.add(idToUse)
            }

            RegisterResult(
                registration = Registration(requestId = idToUse, token = token),
                replacedJob = existing?.job,
                replacedCancellation = existing?.let { item ->
                    HttpClientRequestCancelledException(idToUse, item.batchId, "Replaced by a new request")
                },
            )
        }
    }

    private suspend fun unregister(registration: Registration) {
        mutex.withLock {
            val entry = requestsById[registration.requestId] ?: return
            if (entry.token != registration.token) return

            requestsById.remove(registration.requestId)
            entry.batchId?.let { item ->  removeFromBatch(item, registration.requestId) }
        }
    }

    private fun removeFromBatch(batchId: HttpClientBatchId, requestId: HttpClientRequestId) {
        val trimmedBatchId = batchId.trim()
        if (trimmedBatchId.isEmpty()) return

        val set = requestIdsByBatch[trimmedBatchId] ?: return
        set.remove(requestId)
        if (set.isEmpty()) {
            requestIdsByBatch.remove(trimmedBatchId)
        }
    }

    private fun nextAutoRequestId(): HttpClientRequestId = "__auto__${nextAutoId++}"
}

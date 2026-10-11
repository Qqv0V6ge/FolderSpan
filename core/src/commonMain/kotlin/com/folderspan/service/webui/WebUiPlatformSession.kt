package com.folderspan.service.webui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import korlibs.crypto.SecureRandom

class WebUiPlatformSession(
    val token: String,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Default)
    private val mutex = Mutex()
    private data class Resource(val value: Any, val close: suspend () -> Unit)
    private val resources = mutableMapOf<String, Resource>()
    private val replies = mutableMapOf<Long, CompletableDeferred<ByteArray>>()
    private val events = Channel<WebUiPlatformEvent>(WEB_UI_EVENT_LIMIT)
    private val eventSignal = Channel<Unit>(Channel.CONFLATED)
    private val latest = linkedMapOf<String, WebUiPlatformEvent>()
    private var nextId = 0L
    private var closed = false
    private val shutdown = CompletableDeferred<Unit>()
    private var polling = false
    private var lastPoll = now()
    private var socketAttached = false

    internal suspend fun attachSocket() = mutex.withLock {
        check(!closed && !socketAttached) { "management connection already attached" }
        socketAttached = true
        lastPoll = now()
    }

    internal suspend fun touchLease() = mutex.withLock { check(!closed); lastPoll = now() }
    internal suspend fun nextEvent(): WebUiPlatformEvent {
        while (true) {
            val event = mutex.withLock {
                check(!closed) { "platform session closed" }
                events.tryReceive().getOrNull() ?: latest.keys.firstOrNull()?.let { latest.remove(it) }
            }
            if (event != null) return event
            eventSignal.receive()
        }
    }

    internal suspend fun emitLatest(handle: String, type: String, data: String) {
        require(data.encodeToByteArray().size <= WEB_UI_WIRE_BODY_LIMIT)
        mutex.withLock {
            check(!closed)
            check(handle in latest || latest.size < WEB_UI_RESOURCE_LIMIT)
            latest[handle] = WebUiPlatformEvent(++nextId, handle, type, data)
        }
        eventSignal.trySend(Unit)
    }

    internal suspend fun finishLatest(handle: String, type: String, data: String) {
        mutex.withLock { latest.remove(handle) }
        emit(handle, type, data)
    }

    internal suspend fun emitBytes(handle: String, type: String, bytes: ByteArray) {
        publish(handle, type, "", null, bytes)
    }

    suspend fun <T> runOperation(block: suspend () -> T): T {
        val pending = scope.async { block() }
        try { return pending.await() } finally { pending.cancel() }
    }

    suspend fun expired(): Boolean = mutex.withLock { closed || now() - lastPoll >= WEB_UI_RESOURCE_LEASE_MS }

    suspend fun <T : Any> own(value: T, close: suspend () -> Unit): String {
        try {
            return mutex.withLock {
                check(!closed) { "platform session closed" }
                check(resources.size < WEB_UI_RESOURCE_LIMIT) { "platform resource capacity reached" }
                ByteArray(16).also(SecureRandom::nextBytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }.also { resources[it] = Resource(value, close) }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) { close() }
            throw error
        }
    }

    suspend fun resource(id: String): Any = mutex.withLock {
        check(!closed) { "platform session closed" }
        requireNotNull(resources[id]) { "unknown session resource" }.value
    }

    suspend fun release(id: String) {
        val resource = mutex.withLock { resources.remove(id) } ?: return
        withContext(NonCancellable) { resource.close() }
    }

    suspend fun emit(handle: String, type: String, data: String = "") {
        publish(handle, type, data, null)
    }

    suspend fun callback(handle: String, type: String, data: String): ByteArray {
        val reply = CompletableDeferred<ByteArray>()
        val id = publish(handle, type, data, reply)
        try {
            return reply.await()
        } finally {
            mutex.withLock { replies.remove(id) }
        }
    }

    private suspend fun publish(handle: String, type: String, data: String, reply: CompletableDeferred<ByteArray>?, bytes: ByteArray = byteArrayOf()): Long {
        require(data.encodeToByteArray().size <= WEB_UI_WIRE_BODY_LIMIT && bytes.size <= WEB_UI_WIRE_BODY_LIMIT) { "platform event too large" }
        var overflow = false
        val id = mutex.withLock {
            check(!closed) { "platform session closed" }
            val id = ++nextId
            if (replies.size >= WEB_UI_EVENT_LIMIT || !events.trySend(WebUiPlatformEvent(id, handle, type, data, reply != null, bytes)).isSuccess) {
                overflow = true
            } else if (reply != null) replies[id] = reply
            id
        }
        eventSignal.trySend(Unit)
        if (overflow) {
            close(awaitRelease = false)
            error("platform event queue overflow; resources released")
        }
        return id
    }

    suspend fun reply(id: Long, bytes: ByteArray, failed: Boolean = false) {
        require(bytes.size <= WEB_UI_FILE_STREAM_CHUNK_BYTES) { "platform reply too large" }
        val pending = mutex.withLock { replies.remove(id) }
        requireNotNull(pending) { "callback expired or already answered" }
        if (failed) pending.completeExceptionally(IllegalStateException("browser callback failed")) else pending.complete(bytes)
    }

    suspend fun poll(): List<WebUiPlatformEvent> {
        mutex.withLock {
            check(!closed) { "platform session closed" }
            check(!polling) { "a poll is already active" }
            polling = true
            lastPoll = now()
        }
        try {
            val event = withTimeoutOrNull(WEB_UI_POLL_TIMEOUT_MS) { events.receive() } ?: return emptyList()
            return buildList {
                add(event)
                repeat(WEB_UI_EVENT_LIMIT - 1) { events.tryReceive().getOrNull()?.let(::add) }
            }
        } finally {
            mutex.withLock { polling = false }
        }
    }

    suspend fun close(awaitRelease: Boolean = true) {
        val caller = currentCoroutineContext()[Job]
        fun contains(parent: Job): Boolean = parent == caller || parent.children.any(::contains)
        val calledFromWorker = contains(job)
        val owned = mutex.withLock {
            if (closed) null else {
                closed = true
                events.close(IllegalStateException("platform session closed"))
                eventSignal.close()
                latest.clear()
                replies.values.forEach { it.completeExceptionally(IllegalStateException("platform session closed")) }
                replies.clear()
                resources.values.toList().also { resources.clear() }
            }
        }
        if (owned != null) {
            // A worker can trigger overflow. A separate closer avoids joining that worker from itself.
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    job.cancel()
                    owned.asReversed().forEach { runCatching { it.close() } }
                    job.cancelAndJoin()
                } finally {
                    shutdown.complete(Unit)
                }
            }
        }
        if (awaitRelease && !calledFromWorker) withContext(NonCancellable) { shutdown.await() }
    }
}

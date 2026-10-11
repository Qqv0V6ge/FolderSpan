package com.folderspan.service.webui

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object WebUiPlatformClient {
    private val mutex = Mutex()
    private class Listener(val event: suspend (WebUiPlatformEvent) -> ByteArray, val failed: (Throwable) -> Unit) {
        val queue = Channel<WebUiPlatformEvent>(WEB_UI_EVENT_LIMIT)
        lateinit var worker: Job
    }
    internal var connection: WebUiManagementConnection? = null
    private val listeners = mutableMapOf<String, Listener>()

    suspend fun register(handle: String, failed: (Throwable) -> Unit, event: suspend (WebUiPlatformEvent) -> ByteArray) {
        val listener = Listener(event, failed)
        mutex.withLock {
            check(handle !in listeners)
            listeners[handle] = listener
            listener.worker = CoroutineScope(Dispatchers.Default).launch {
                try { for (next in listener.queue) dispatch(next) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { listener.failed(error); connection?.close() }
            }
        }
    }
    suspend fun unregister(handle: String) {
        val listener = mutex.withLock { listeners.remove(handle) } ?: return
        listener.queue.cancel()
        if (listener.worker != currentCoroutineContext()[Job]) listener.worker.cancelAndJoin()
    }
    internal suspend fun enqueue(event: WebUiPlatformEvent) {
        val listener = mutex.withLock { listeners[event.handle] }
        if (listener == null) {
            if (event.requiresReply) connection?.reply(event.id, byteArrayOf(), true)
            return
        }
        check(listener.queue.trySend(event).isSuccess) { "browser event capacity reached" }
    }
    suspend fun invalidate(error: Throwable) {
        val pending = mutex.withLock { listeners.values.toList().also { listeners.clear() } }
        pending.forEach {
            it.queue.cancel()
            it.worker.cancel()
            it.failed(error)
        }
        pending.forEach { if (it.worker != currentCoroutineContext()[Job]) it.worker.join() }
    }
    suspend fun dispatch(event: WebUiPlatformEvent) {
        val listener = mutex.withLock { listeners[event.handle] }
        if (listener == null) {
            // Notifications already queued at stop time are no longer relevant to their closed listener.
            if (event.requiresReply) checkNotNull(connection).reply(event.id, byteArrayOf(), true)
            return
        }
        val result = try { listener.event(event) } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (event.requiresReply) checkNotNull(connection).reply(event.id, byteArrayOf(), true)
            if (!event.requiresReply) listener.failed(error)
            return
        }
        // A failed reply is never replayed: the server may already have accepted it.
        if (event.requiresReply) checkNotNull(connection).reply(event.id, result, false)
    }

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun exchange(method: String, path: String, body: ByteArray? = null, contentType: String? = null): WebUiHttpExchange {
        val target = checkNotNull(WebUiAttachment.target) { "Select a Runtime before using host services" }
        val token = WebUiAttachment.sessionToken
        check(token.isNotBlank()) { "Runtime login required" }
        val result = if (isWebUiBodyRoute(method, path)) WebUiBrowserHttp.exchange(method, target.httpUrl(path), mapOf(
            WEB_UI_SESSION_HEADER to token, WEB_UI_PROTOCOL_HEADER to WEB_UI_PROTOCOL_VERSION.toString(),
        ), body, contentType)
        else checkNotNull(connection) { "Runtime management connection required" }.request(method, path, body, contentType)
        check(WebUiAttachment.target == target && WebUiAttachment.sessionToken == token) { "Runtime session changed" }
        if (result.status == 401) {
            WebUiAttachment.hasSession = false
            WebUiAttachment.connected = false
            WebUiAttachment.stale = true
        }
        if (result.status !in 200..299) {
            val failure = runCatching { json.decodeFromString<WebUiJsonError>(result.body.decodeToString()) }.getOrNull()
            val message = failure?.error ?: "Runtime HTTP ${result.status}"
            if (result.status == 403) throw com.folderspan.exception.AuthorityException(message)
            val code = com.folderspan.service.mcp.file.FileEndpointErrorCode.entries.firstOrNull { it.wireValue == failure?.code }
            if (code != null) throw com.folderspan.service.mcp.file.FileEndpointException(code, message)
            error(message)
        }
        return result
    }

    suspend inline fun <reified T> get(path: String): T =
        json.decodeFromString(exchange("GET", WEB_UI_PLATFORM_PATH + path).body.decodeToString())

    suspend inline fun <reified T, reified R> post(path: String, value: T): R =
        json.decodeFromString(exchange("POST", WEB_UI_PLATFORM_PATH + path,
            json.encodeToString(value).encodeToByteArray(), "application/json").body.decodeToString())

    fun releaseTemporary(path: String) {
        val token = WebUiAttachment.sessionToken
        val cache = WebUiAttachment.cachePath.trimEnd('/', '\\') + WebUiAttachment.pathSeparator
        require(path.startsWith(cache)) { "not a session temporary path" }
        CoroutineScope(Dispatchers.Default).launch {
            if (token != WebUiAttachment.sessionToken) return@launch
            // The session lease also deletes this tree if the browser disappears before cleanup completes.
            runCatching { withTimeout(5_000) {
                exchange("DELETE", "$WEB_UI_PLATFORM_PATH/files/tree?path=${encodeWebUiQuery(path)}")
            } }
        }
    }

    suspend fun release(handle: String) {
        withTimeout(5_000) { exchange("DELETE", "$WEB_UI_PLATFORM_PATH/resources?handle=${encodeWebUiQuery(handle)}") }
    }
}

internal suspend inline fun <T> platformResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

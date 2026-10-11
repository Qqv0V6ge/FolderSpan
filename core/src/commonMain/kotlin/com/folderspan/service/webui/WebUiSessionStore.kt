package com.folderspan.service.webui

import korlibs.crypto.SecureRandom
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

data class WebUiSession(
    val id: String,
    val createdAt: Long,
    var lastAccessAt: Long,
)

class WebUiSessionCapacityException : RuntimeException("session capacity reached")

class WebUiSessionStore(
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val randomBytes: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom::nextBytes) },
    private val idleTimeoutMs: Long = WEB_UI_SESSION_IDLE_TIMEOUT_MS,
    private val maxSessions: Int = WEB_UI_MAX_SESSIONS,
) {
    private val mutex = Mutex()
    private val sessions = LinkedHashMap<String, WebUiSession>()
    private val activeTokens = MutableStateFlow<Set<String>>(emptySet())
    internal val activeSessions = activeTokens.asStateFlow()

    private fun newId(): String = randomBytes(16).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    suspend fun create(): WebUiSession = mutex.withLock {
        evictExpiredLocked(nowMillis())
        if (sessions.size >= maxSessions) {
            throw WebUiSessionCapacityException()
        }
        val session = WebUiSession(
            id = newId(),
            createdAt = nowMillis(),
            lastAccessAt = nowMillis(),
        )
        sessions[session.id] = session
        activeTokens.value = sessions.keys.toSet()
        session
    }

    suspend fun get(id: String?, touch: Boolean = true): WebUiSession? = mutex.withLock {
        if (id.isNullOrBlank()) return@withLock null
        evictExpiredLocked(nowMillis())
        val session = sessions[id] ?: return@withLock null
        if (touch) session.lastAccessAt = nowMillis()
        session
    }

    suspend fun remove(id: String?): Boolean = mutex.withLock {
        if (id.isNullOrBlank()) return@withLock false
        val removed = sessions.remove(id) != null
        activeTokens.value = sessions.keys.toSet()
        removed
    }

    suspend fun clear() = mutex.withLock {
        sessions.clear()
        activeTokens.value = emptySet()
    }

    suspend fun snapshot(): List<WebUiSession> = mutex.withLock { evictExpiredLocked(nowMillis()); sessions.values.toList() }

    private fun evictExpiredLocked(now: Long) {
        val expired = sessions.filterValues { session -> now - session.lastAccessAt >= idleTimeoutMs }.keys
        expired.forEach(sessions::remove)
        if (expired.isNotEmpty()) {
            activeTokens.value = sessions.keys.toSet()
        }
    }
}

package com.folderspan.service.mcp.http

import com.folderspan.service.mcp.protocol.McpProtocolSession
import korlibs.crypto.SecureRandom
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

class McpHttpSessionStore(
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, McpProtocolSession>()

    suspend fun create(tokenLookupId: String): McpProtocolSession = mutex.withLock {
        pruneLocked()
        val tokenCount = sessions.values.count { item -> item.tokenLookupId == tokenLookupId }
        if (tokenCount >= MAX_SESSIONS_PER_TOKEN) throw McpHttpCapacityException("session limit reached")
        if (sessions.size >= MAX_GLOBAL_SESSIONS) throw McpHttpCapacityException("server session limit reached")
        var id: String
        do {
            id = ByteArray(24).also(SecureRandom::nextBytes).toHex()
        } while (sessions.containsKey(id))
        McpProtocolSession(
            id = id,
            tokenLookupId = tokenLookupId,
            lastAccessAt = nowMillis(),
        ).also { session -> sessions[id] = session }
    }

    suspend fun get(id: String, tokenLookupId: String, touch: Boolean = true): McpProtocolSession? = mutex.withLock {
        pruneLocked()
        sessions[id]?.takeIf { item -> item.tokenLookupId == tokenLookupId }?.also { item ->
            if (touch) item.lastAccessAt = nowMillis()
        }
    }

    suspend fun remove(id: String, tokenLookupId: String): Boolean = mutex.withLock {
        val session = sessions[id] ?: return@withLock false
        if (session.tokenLookupId != tokenLookupId) return@withLock false
        sessions.remove(id) != null
    }

    suspend fun contains(id: String, tokenLookupId: String): Boolean = mutex.withLock {
        pruneLocked()
        sessions[id]?.tokenLookupId == tokenLookupId
    }

    suspend fun clear() = mutex.withLock { sessions.clear() }

    private fun pruneLocked() {
        val cutoff = nowMillis() - SESSION_IDLE_TIMEOUT_MS
        sessions.entries.removeAll { item -> item.value.lastAccessAt < cutoff }
    }

    companion object {
        const val SESSION_IDLE_TIMEOUT_MS = 30L * 60L * 1000L
        const val MAX_SESSIONS_PER_TOKEN = 32
        const val MAX_GLOBAL_SESSIONS = 256
    }
}

class McpHttpCapacityException(message: String) : Exception(message)

private fun ByteArray.toHex(): String = joinToString("") { value -> value.toUByte().toString(16).padStart(2, '0') }

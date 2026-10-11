package com.folderspan.service.http.server.linkshare

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock

data class LinkSharePasswordLockout(
    val retryAfterSeconds: Int,
)

class LinkSharePasswordAttemptLimiter(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val lockoutDurationMillis: Long = DEFAULT_LOCKOUT_DURATION_MILLIS,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val buckets = mutableMapOf<String, Bucket>()
    private val inFlight = mutableMapOf<String, Semaphore>()

    suspend fun lockoutOrNull(clientKey: String): LinkSharePasswordLockout? = mutex.withLock {
        pruneExpiredLocked(nowMillis())
        lockoutOf(normalizedKey(clientKey), nowMillis())
    }

    /**
     * 在昂贵校验之前占用一次失败名额。已锁定则立刻返回锁定信息，不进入 PBKDF2。
     */
    suspend fun tryAcquireAttempt(clientKey: String): LinkSharePasswordLockout? = mutex.withLock {
        val now = nowMillis()
        pruneExpiredLocked(now)
        val key = normalizedKey(clientKey)
        lockoutOf(key, now)?.let { lockout -> return@withLock lockout }
        val failureCount = (buckets[key]?.failureCount ?: 0) + 1
        val lockedUntil = if (failureCount >= maxFailures) now + lockoutDurationMillis else 0L
        buckets[key] = Bucket(failureCount = failureCount, lockedUntilMillis = lockedUntil)
        null
    }

    suspend fun registerFailure(clientKey: String): LinkSharePasswordLockout? = mutex.withLock {
        val now = nowMillis()
        pruneExpiredLocked(now)
        val key = normalizedKey(clientKey)
        lockoutOf(key, now)?.let { lockout -> return@withLock lockout }
        val failureCount = (buckets[key]?.failureCount ?: 0) + 1
        val lockedUntil = if (failureCount >= maxFailures) now + lockoutDurationMillis else 0L
        buckets[key] = Bucket(failureCount = failureCount, lockedUntilMillis = lockedUntil)
        lockoutOf(key, now)
    }

    suspend fun registerSuccess(clientKey: String) {
        mutex.withLock {
            buckets.remove(normalizedKey(clientKey))
            pruneExpiredLocked(nowMillis())
        }
    }

    suspend fun <T> withAttemptSlot(clientKey: String, block: suspend () -> T): T {
        val key = normalizedKey(clientKey)
        val semaphore = mutex.withLock {
            pruneInFlight()
            inFlight.getOrPut(key) { Semaphore(1) }
        }
        return semaphore.withPermit { block() }
    }

    private fun lockoutOf(clientKey: String, now: Long): LinkSharePasswordLockout? {
        val lockedUntil = buckets[clientKey]?.lockedUntilMillis ?: return null
        if (lockedUntil <= now) return null
        val retryAfterSeconds = ((lockedUntil - now + 999L) / 1000L).toInt().coerceAtLeast(1)
        return LinkSharePasswordLockout(retryAfterSeconds)
    }

    private fun pruneExpiredLocked(now: Long) {
        val expiredKeys = buckets.mapNotNull { (key, bucket) ->
            key.takeIf { bucket.lockedUntilMillis in 1..now || (bucket.lockedUntilMillis == 0L && bucket.failureCount <= 0) }
        }
        expiredKeys.forEach { key -> buckets.remove(key) }
        if (buckets.size <= MAX_TRACKED_CLIENTS) return
        val overflow = buckets.size - MAX_TRACKED_CLIENTS
        buckets.keys.take(overflow).forEach { key -> buckets.remove(key) }
        pruneInFlight()
    }

    private fun pruneInFlight() {
        if (inFlight.size <= MAX_TRACKED_CLIENTS) return
        val overflow = inFlight.size - MAX_TRACKED_CLIENTS
        inFlight.keys.filter { key -> key !in buckets }.take(overflow).forEach { key ->
            inFlight.remove(key)
        }
    }

    private fun normalizedKey(clientKey: String): String = clientKey.trim().ifBlank { UNKNOWN_CLIENT_KEY }

    private data class Bucket(
        val failureCount: Int,
        val lockedUntilMillis: Long,
    )

    companion object {
        const val DEFAULT_MAX_FAILURES = 5
        const val DEFAULT_LOCKOUT_DURATION_MILLIS = 15 * 60 * 1000L
        private const val MAX_TRACKED_CLIENTS = 4_096
        private const val UNKNOWN_CLIENT_KEY = "unknown"
    }
}

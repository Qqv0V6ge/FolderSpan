package com.folderspan.service.network

import com.folderspan.data.main.network.SftpDriveExtras
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkSessionCacheTest {
    private val key = NetworkSessionKey("SFTP", "localhost", 22, "test", "password", SftpDriveExtras(), SftpHostKeyTrust(MapSettings()))

    private class Connection {
        var open = true
        var closes = 0
        fun close() { open = false; closes++ }
    }

    @Test
    fun concurrentAndNestedUsesShareAuthenticationWithoutBlockingOtherServers() = runTest {
        val created = mutableListOf<Connection>()
        val ready = CompletableDeferred<Unit>()
        val cache = NetworkSessionCache<Connection>({ it.open }, { it.close() }, idleTimeoutMillis = 100, scope = this)
        val connect: suspend () -> Connection = {
            Connection().also { created += it; ready.await() }
        }
        val requests = List(24) { async { cache.use(key, connect) { it } } }
        runCurrent()
        assertEquals(1, created.size)
        requests.last().cancelAndJoin()
        val other = cache.use(key.copy(port = 2220), { Connection().also { created += it } }) { it }
        assertEquals(2, created.size)
        ready.complete(Unit)
        requests.dropLast(1).awaitAll().forEach { assertSame(created.first(), it) }
        cache.use(key, connect) { first ->
            cache.use(key, connect) { second -> assertSame(first, second) }
        }
        advanceTimeBy(99)
        runCurrent()
        assertTrue(created.all { it.open })
        // 再次使用重置空闲计时；另一服务器的连接按原定时间释放。
        cache.use(key, connect) { }
        advanceTimeBy(1)
        runCurrent()
        assertFalse(other.open)
        assertTrue(created.first().open)
        advanceTimeBy(99)
        runCurrent()
        assertTrue(created.all { it.closes == 1 })
    }

    @Test
    fun exclusiveSessionsAreReusedWithoutSharingBusyConnectionsOrBlockingNestedOperations() = runTest {
        val created = mutableListOf<Connection>()
        val connect: suspend () -> Connection = { Connection().also { created += it } }
        val cache = NetworkSessionCache<Connection>(
            { it.open }, { it.close() }, idleTimeoutMillis = 100, exclusive = true, scope = this,
        )
        val ready = CompletableDeferred<Unit>()
        val requests = List(8) { async { cache.use(key, connect) { ready.await(); it } } }
        runCurrent()
        assertEquals(8, created.size)
        ready.complete(Unit)
        assertEquals(8, requests.awaitAll().toSet().size)
        repeat(24) { cache.use(key, connect) { } }
        assertEquals(8, created.size)
        cache.use(key, connect) { first ->
            assertFailsWith<IllegalStateException> {
                cache.use(key, connect) { second ->
                    assertNotSame(first, second)
                    second.open = false
                    error("connection reset")
                }
            }
            cache.use(key, connect) { assertNotSame(first, it) }
            assertTrue(first.open)
        }
        assertEquals(8, created.size)
        advanceTimeBy(100)
        runCurrent()
        assertTrue(created.all { it.closes == 1 })
    }

    @Test
    fun cancellingAuthenticationDoesNotCancelOtherWaitingOperations() = runTest {
        val cache = NetworkSessionCache<Connection>({ it.open }, { it.close() }, idleTimeoutMillis = 100, scope = this)
        val first = async { cache.use(key, { awaitCancellation() }) { error("must not execute") } }
        runCurrent()
        val second = async { cache.use(key, { Connection() }) { it } }
        runCurrent()
        assertFalse(second.isCompleted)
        first.cancelAndJoin()
        val connection = second.await()
        assertTrue(connection.open)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, connection.closes)
    }

    @Test
    fun failuresAndCancellationReleaseUsersWithoutClosingAnotherActiveOperationOrReplayingWrites() = runTest {
        val created = mutableListOf<Connection>()
        val connect: suspend () -> Connection = { Connection().also { created += it } }
        val cache = NetworkSessionCache<Connection>({ it.open }, { it.close() }, idleTimeoutMillis = 100, scope = this)
        val authenticationError = IllegalArgumentException("authentication failed")
        assertSame(authenticationError, assertFailsWith<IllegalArgumentException> {
            cache.use(key, { throw authenticationError }) { error("must not execute") }
        })
        val active = async { cache.use(key, connect) { awaitCancellation() } }
        runCurrent()
        advanceTimeBy(200)
        assertTrue(created.single().open)
        var writes = 0
        val transferError = IllegalStateException("connection reset")
        assertSame(transferError, assertFailsWith<IllegalStateException> {
            cache.use(key, connect) {
                writes++
                it.open = false
                throw transferError
            }
        })
        assertEquals(1, writes)
        assertEquals(0, created.single().closes)
        cache.use(key, connect) { assertTrue(it.open) }
        assertEquals(2, created.size)
        active.cancelAndJoin()
        assertEquals(1, created.first().closes)
        advanceTimeBy(100)
        runCurrent()
        assertTrue(created.all { it.closes == 1 })
    }
}

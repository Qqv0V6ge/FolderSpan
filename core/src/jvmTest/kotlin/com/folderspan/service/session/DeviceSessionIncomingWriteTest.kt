package com.folderspan.service.session

import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceSessionIncomingWriteTest {
    @Test
    fun queueIsBoundedAndCreditIsReturnedOnlyAfterPersistence() = runSuspendTest {
        val acknowledged = mutableListOf<Int>()
        val write = IncomingWrite(queueCapacity = 1) { bytes -> acknowledged += bytes }
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5)

        assertTrue(write.enqueue(first))
        assertTrue(acknowledged.isEmpty())

        coroutineScope {
            val secondEnqueue = async { write.enqueue(second) }
            delay(10)
            assertFalse(secondEnqueue.isCompleted)

            assertTrue(first.contentEquals(write.data.receive()))
            assertTrue(secondEnqueue.await())
        }

        assertTrue(acknowledged.isEmpty())
        write.acknowledgePersisted(first.size)
        assertEquals(listOf(first.size), acknowledged)
        assertTrue(second.contentEquals(write.data.receive()))
    }

    @Test
    fun failedWriteReleasesBlockedEnqueueWithoutLeavingAnOrphanQueue() = runSuspendTest {
        val write = IncomingWrite(queueCapacity = 1) {}
        assertTrue(write.enqueue(byteArrayOf(1)))

        coroutineScope {
            val blocked = async { write.enqueue(byteArrayOf(2)) }
            delay(10)
            assertFalse(blocked.isCompleted)

            write.fail(IllegalStateException("disk failed"))

            assertFalse(blocked.await())
            assertTrue(write.completed.isCompleted)
        }
    }
}

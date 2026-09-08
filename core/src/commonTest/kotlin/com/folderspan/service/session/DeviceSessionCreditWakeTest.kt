package com.folderspan.service.session

import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceSessionCreditWakeTest {
    @Test
    fun closingAStreamOrSessionFailsAWriterWaitingOnAnotherStreamsCredit() = runSuspendTest {
        for (closeSession in listOf(false, true)) {
            coroutineScope {
                val credit = DeviceSessionCredit(DeviceSessionWindowPlan(8, 8, 2))
                val outgoing = Channel<DeviceSessionFrame>(Channel.UNLIMITED)
                val incoming = Channel<DeviceSessionFrame>(Channel.UNLIMITED)
                val connection = DeviceSessionConnection(outgoing, incoming, credit, isClient = true)
                val first = connection.openStream()
                val second = connection.openStream()
                connection.sendData(first, ByteArray(8))
                val dispatcher = StandardTestDispatcher()
                val waiting = async(dispatcher, CoroutineStart.UNDISPATCHED) {
                    runCatching { connection.sendData(second, byteArrayOf(1)) }
                }
                try {
                    if (closeSession) connection.close() else connection.sendRst(second)
                    dispatcher.scheduler.runCurrent()
                    assertTrue(waiting.isCompleted, "Closing must wake a writer with no in-flight bytes of its own")
                    assertIs<DeviceSessionClosedException>(waiting.await().exceptionOrNull())
                    if (!closeSession) {
                        credit.grant(first, 1)
                        connection.sendData(first, byteArrayOf(2))
                        assertEquals(8, credit.inFlightBytes())
                    }
                } finally {
                    waiting.cancel()
                    dispatcher.scheduler.runCurrent()
                    waiting.join()
                    connection.close()
                    outgoing.cancel()
                    incoming.cancel()
                }
            }
        }
    }

    @Test
    fun grantingAnotherStreamWakesItsWriterWithoutWaitingForAnUnrelatedGrant() = runSuspendTest {
        coroutineScope {
            val credit = DeviceSessionCredit(DeviceSessionWindowPlan(16, 8, 2))
            credit.awaitAndConsume(1, 8)
            credit.awaitAndConsume(2, 8)
            val dispatcher = StandardTestDispatcher()
            val first = async(dispatcher, CoroutineStart.UNDISPATCHED) { credit.awaitAndConsume(1, 1) }
            val second = async(dispatcher, CoroutineStart.UNDISPATCHED) { credit.awaitAndConsume(2, 1) }
            try {
                credit.grant(2, 1)
                dispatcher.scheduler.runCurrent()
                assertTrue(second.isCompleted, "Stream 2 has credit and must wake even though stream 1 waited first")
                assertFalse(first.isCompleted, "Stream 1 still has no credit")
                assertEquals(16, credit.inFlightBytes())
            } finally {
                first.cancel()
                second.cancel()
                dispatcher.scheduler.runCurrent()
                joinAll(first, second)
            }
        }
    }

    @Test
    fun closingAStreamWakesAllWritersThatFitTheReleasedSessionCredit() = runSuspendTest {
        coroutineScope {
            val credit = DeviceSessionCredit(DeviceSessionWindowPlan(16, 8, 4))
            credit.awaitAndConsume(1, 8)
            credit.awaitAndConsume(2, 8)
            val dispatcher = StandardTestDispatcher()
            val blocked = async(dispatcher, CoroutineStart.UNDISPATCHED) { credit.awaitAndConsume(1, 1) }
            val third = async(dispatcher, CoroutineStart.UNDISPATCHED) { credit.awaitAndConsume(3, 4) }
            val fourth = async(dispatcher, CoroutineStart.UNDISPATCHED) { credit.awaitAndConsume(4, 4) }
            try {
                credit.closeStream(2)
                dispatcher.scheduler.runCurrent()
                assertTrue(third.isCompleted)
                assertTrue(fourth.isCompleted)
                assertFalse(blocked.isCompleted)
                assertEquals(16, credit.inFlightBytes())
                assertEquals(0, credit.streamInFlightBytes(2))
            } finally {
                listOf(blocked, third, fourth).forEach { it.cancel() }
                dispatcher.scheduler.runCurrent()
                joinAll(blocked, third, fourth)
            }
        }
    }
}

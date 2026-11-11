@file:OptIn(ExperimentalCoroutinesApi::class)

package com.folderspan.service.session

import com.folderspan.service.operation.HttpTransferRuntimeMemoryStatus
import com.folderspan.service.operation.HttpTransferRuntimeTuning
import com.folderspan.service.operation.HttpTransferStatus
import com.folderspan.utils.ProtoBufCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceSessionTransferFeedbackTest {
    @Test fun delayedUnknownAndFutureOriginsCannotBecomeFreshAtHandlerDispatch() = runTest {
        var now = 10_000L
        val feedback = DeviceSessionTransferFeedback(backgroundScope, limits(), MutableStateFlow(0),
            rpc = { _, _ -> okSubscription() }, nowMillis = { now })
        try {
            feedback.handle(subscription())
            val status = limits().copy(sampledAtMillis = Long.MAX_VALUE)
            for ((sequence, origin) in listOf(1L to 4_999L, 2L to 0L, 3L to Long.MAX_VALUE)) {
                feedback.handle(update(sequence, status).also { it.receivedAtMillis = origin })
                assertEquals(0L, feedback.current().sampledAtMillis)
            }
            now = Long.MAX_VALUE
            assertEquals(0L, feedback.current().sampledAtMillis)
            now = 10_000
            feedback.handle(update(4, status).also { it.receivedAtMillis = 9_000 })
            assertTrue(feedback.current().sampledAtMillis > 0)
            now = 14_000
            assertEquals(0L, feedback.current().sampledAtMillis)
        } finally { feedback.close() }
    }

    private fun limits() = HttpTransferStatus(maxParallelRequests = 4, recommendedParallelRequests = 4,
        maxChunkBytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES, recommendedChunkBytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES)

    private fun request(method: String, payload: ByteArray) = DeviceSessionControlRequest(1, method, payload)
    private fun subscription(enabled: Boolean = true) = request(DEVICE_SESSION_RPC_SUBSCRIBE_TRANSFER_FEEDBACK,
        ProtoBufCodec.encode(DeviceSessionTransferFeedbackSubscription(enabled)))
    private fun update(sequence: Long, status: HttpTransferStatus) = request(DEVICE_SESSION_RPC_TRANSFER_FEEDBACK,
        ProtoBufCodec.encode(DeviceSessionTransferFeedbackUpdate(sequence, status)))
    private fun okSubscription() = DeviceSessionControlResponse(1, DEVICE_SESSION_RPC_OK,
        ProtoBufCodec.encode(DeviceSessionTransferFeedbackSubscription()))

    @Test fun explicitSubscriptionEnablesBothDirectionsAndIdleStopsSampling() = runTest {
        val leftActivity = MutableStateFlow(0)
        val rightActivity = MutableStateFlow(0)
        lateinit var left: DeviceSessionTransferFeedback
        lateinit var right: DeviceSessionTransferFeedback
        var leftUpdates = 0
        var rightUpdates = 0
        left = DeviceSessionTransferFeedback(backgroundScope, limits(), leftActivity, rpc = { method, payload ->
            if (method == DEVICE_SESSION_RPC_TRANSFER_FEEDBACK) leftUpdates++
            right.handle(request(method, payload))!!
        }, nowMillis = { testScheduler.currentTime })
        right = DeviceSessionTransferFeedback(backgroundScope, limits(), rightActivity, rpc = { method, payload ->
            if (method == DEVICE_SESSION_RPC_TRANSFER_FEEDBACK) rightUpdates++
            left.handle(request(method, payload))!!
        }, nowMillis = { testScheduler.currentTime })
        try {
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(HttpTransferRuntimeMemoryStatus.unknown())
            runCurrent()
            assertEquals(0, leftUpdates + rightUpdates)
            assertEquals(DEVICE_SESSION_RPC_FORBIDDEN, left.handle(update(1, limits().copy(sampledAtMillis = 1)))!!.status)
            left.subscribe()
            runCurrent()
            assertEquals(4, left.current().recommendedParallelRequests)
            assertEquals(4, right.current().recommendedParallelRequests)
            assertEquals(1, leftUpdates)
            assertEquals(1, rightUpdates)
            leftActivity.value = 1
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            val activeUpdates = leftUpdates
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(HttpTransferRuntimeMemoryStatus(4_000_000, 128_000_000, true))
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(leftUpdates > activeUpdates)
            assertEquals(1, right.current().recommendedParallelRequests)
            assertEquals(1, rightUpdates)
            leftActivity.value = 0
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            val idleUpdates = leftUpdates + rightUpdates
            advanceTimeBy(6_000)
            runCurrent()
            assertEquals(idleUpdates, leftUpdates + rightUpdates)
            assertEquals(0L, right.current().sampledAtMillis)
        } finally {
            left.close(); right.close()
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(null)
        }
    }

    @Test fun sequenceBoundsAndActualReceptionTimeCannotBeRefreshedByReading() = runTest {
        var now = 1_000L
        val feedback = DeviceSessionTransferFeedback(backgroundScope, limits(), MutableStateFlow(0),
            rpc = { _, _ -> okSubscription() }, nowMillis = { now })
        try {
            assertEquals(DEVICE_SESSION_RPC_OK, feedback.handle(subscription())!!.status)
            val excessive = limits().copy(recommendedParallelRequests = Int.MAX_VALUE, maxParallelRequests = Int.MAX_VALUE,
                recommendedChunkBytes = Int.MAX_VALUE, maxChunkBytes = Int.MAX_VALUE,
                activeRequests = Int.MAX_VALUE, retryAfterMillis = Long.MAX_VALUE, sampledAtMillis = Long.MAX_VALUE)
            assertEquals(DEVICE_SESSION_RPC_OK, feedback.handle(update(10, excessive))!!.status)
            assertEquals(4, feedback.current().maxParallelRequests)
            assertEquals(4, feedback.current().recommendedParallelRequests)
            assertEquals(4, feedback.current().activeRequests)
            assertEquals(DEVICE_SESSION_MAX_PAYLOAD_BYTES, feedback.current().maxChunkBytes)
            assertEquals(5_000L, feedback.current().retryAfterMillis)
            now = 2_000
            feedback.handle(update(10, limits().copy(recommendedParallelRequests = 1, sampledAtMillis = 2)))
            feedback.handle(update(9, limits().copy(recommendedParallelRequests = 1, sampledAtMillis = 2)))
            assertEquals(4, feedback.current().recommendedParallelRequests)
            assertEquals(4_000L, feedback.current().retryAfterMillis)
            now = 5_999
            assertTrue(feedback.current().sampledAtMillis > 0)
            now = 6_000
            assertEquals(0L, feedback.current().sampledAtMillis)
            assertEquals(1, feedback.current().recommendedParallelRequests)
            feedback.handle(update(11, limits().copy(recommendedParallelRequests = 2, sampledAtMillis = 1)))
            assertEquals(1L, feedback.current().sampledAtMillis)
            assertEquals(2, feedback.current().recommendedParallelRequests)
            now = 5_999
            assertEquals(0L, feedback.current().sampledAtMillis)
            now = 6_001
            feedback.handle(subscription(false))
            assertEquals(DEVICE_SESSION_RPC_FORBIDDEN, feedback.handle(update(12, excessive))!!.status)
            assertEquals(0L, feedback.current().sampledAtMillis)
            feedback.handle(subscription())
            feedback.handle(update(10, excessive))
            assertEquals(0L, feedback.current().sampledAtMillis)
        } finally { feedback.close() }
        assertEquals(DEVICE_SESSION_RPC_FORBIDDEN, feedback.handle(subscription())!!.status)
    }

    @Test fun malformedAndUnsubscribedUpdatesDoNotBecomeLiveStatus() = runTest {
        val feedback = DeviceSessionTransferFeedback(backgroundScope, limits(), MutableStateFlow(0), rpc = { _, _ -> okSubscription() })
        try {
            assertEquals(DEVICE_SESSION_RPC_FORBIDDEN, feedback.handle(update(1, limits().copy(sampledAtMillis = 1)))!!.status)
            feedback.handle(subscription())
            for (bad in listOf(update(0, limits().copy(sampledAtMillis = 1)), update(1, limits().copy(version = 2, sampledAtMillis = 1)),
                update(1, limits().copy(sampledAtMillis = 0)), request(DEVICE_SESSION_RPC_TRANSFER_FEEDBACK, byteArrayOf(-1)))) {
                assertEquals(DEVICE_SESSION_RPC_BAD_REQUEST, feedback.handle(bad)!!.status)
            }
            assertEquals(0L, feedback.current().sampledAtMillis)
            assertNull(feedback.handle(request(DEVICE_SESSION_RPC_ROOT_PATHS, byteArrayOf())))
        } finally { feedback.close() }
    }

    @Test fun blockedUpdateKeepsOnePendingLatestStateAndOneInFlightRequest() = runTest {
        val activity = MutableStateFlow(1)
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var outstanding = 0
        var peak = 0
        val feedback = DeviceSessionTransferFeedback(backgroundScope, limits(), activity, rpc = { method, _ ->
            if (method == DEVICE_SESSION_RPC_SUBSCRIBE_TRANSFER_FEEDBACK) okSubscription() else {
                outstanding++; peak = maxOf(peak, outstanding)
                try {
                    if (++calls == 1) release.await()
                    DeviceSessionControlResponse(1, DEVICE_SESSION_RPC_OK)
                } finally { outstanding-- }
            }
        }, nowMillis = { testScheduler.currentTime })
        try {
            feedback.subscribe()
            runCurrent()
            assertEquals(1, calls)
            repeat(100) {
                activity.value = it % 2
                runCurrent()
                advanceTimeBy(25)
                runCurrent()
            }
            assertEquals(1, calls)
            assertEquals(1, outstanding)
            release.complete(Unit)
            runCurrent()
            assertEquals(2, calls)
            assertEquals(1, peak)
        } finally { feedback.close() }
        assertEquals(0, outstanding)
    }

    @Test fun closingCancelsAnUnfinishedUpdateAndReleasesPeriodicSampling() = runTest {
        var outstanding = 0
        val blocked = CompletableDeferred<Unit>()
        val feedback = DeviceSessionTransferFeedback(backgroundScope, limits(), MutableStateFlow(1), rpc = { method, _ ->
            if (method == DEVICE_SESSION_RPC_SUBSCRIBE_TRANSFER_FEEDBACK) okSubscription() else {
                outstanding++
                try { blocked.await(); DeviceSessionControlResponse(1, DEVICE_SESSION_RPC_OK) }
                finally { outstanding-- }
            }
        }, nowMillis = { testScheduler.currentTime })
        feedback.subscribe()
        runCurrent()
        assertEquals(1, outstanding)
        feedback.close()
        assertEquals(0, outstanding)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, outstanding)
    }
}

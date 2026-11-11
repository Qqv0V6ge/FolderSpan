package com.folderspan.service.operation

import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OperationAdaptiveFeedbackTest {
    private class Fixture {
        var now = 0L
        var runtimeMax = 8
        val limiter = AdaptiveOperationLimiter(OperationParallelismConfig(4, 8, 16, 8),
            dynamicMaxParallelismProvider = { runtimeMax }, onActiveParallelismChanged = {}, nowMillis = { now })

        suspend fun request(millis: Long) = limiter.withPermit { now += millis }
    }

    @Test fun stableRequestsGrowAndOneLatencySpikeDoesNotShrinkTheWindow() = runSuspendTest {
        val fixture = Fixture()
        repeat(8) { fixture.request(100) }
        val stable = fixture.limiter.parallelismLimit()
        assertTrue(stable > 4)
        fixture.request(5_000)
        assertEquals(stable, fixture.limiter.parallelismLimit())
        fixture.request(100)
        assertEquals(stable, fixture.limiter.parallelismLimit())
    }

    @Test fun sustainedDeteriorationCapturedFailureAndTimeoutReduceTheWindow() = runSuspendTest {
        val fixture = Fixture()
        repeat(4) { fixture.request(100) }
        val stable = fixture.limiter.parallelismLimit()
        repeat(2) { fixture.request(5_000) }
        assertEquals(stable, fixture.limiter.parallelismLimit())
        fixture.request(5_000)
        val slowed = fixture.limiter.parallelismLimit()
        assertTrue(slowed < stable)
        fixture.limiter.withPermit {
            fixture.now += 1
            try { error("caller keeps going after this item") }
            catch (_: IllegalStateException) { reportOperationFailure() }
        }
        assertEquals(maxOf(1, slowed / 2), fixture.limiter.parallelismLimit())
        assertFailsWith<TimeoutCancellationException> {
            fixture.limiter.withPermit { withTimeout(1) { awaitCancellation() } }
        }
        assertEquals(1, fixture.limiter.parallelismLimit())
    }

    @Test fun continuouslyProgressingLargeFilesAreNotClassifiedByTheirFullDuration() = runSuspendTest {
        val fixture = Fixture()
        repeat(12) {
            fixture.limiter.withPermit {
                repeat(40) {
                    fixture.now += 250
                    reportOperationBytes(65_536)
                }
            }
        }
        assertTrue(fixture.limiter.parallelismLimit() > 4)
    }

    @Test fun skippedItemsDoNotPromoteAndResourceRecoveryIsGradual() = runSuspendTest {
        val fixture = Fixture()
        repeat(40) { fixture.limiter.withPermit { fixture.now++; reportOperationSkipped() } }
        assertEquals(4, fixture.limiter.parallelismLimit())
        fixture.runtimeMax = 1
        fixture.request(100)
        assertEquals(1, fixture.limiter.parallelismLimit())
        fixture.runtimeMax = 8
        fixture.request(100)
        assertEquals(2, fixture.limiter.parallelismLimit())
        repeat(32) { fixture.request(100) }
        assertEquals(8, fixture.limiter.parallelismLimit())
    }

    @Test fun repeatedEmptyProgressDoesNotHideAStall() {
        var now = 0L
        val feedback = OperationRequestFeedback { now }
        now = 100
        feedback.bytes(10)
        now = 10_000
        feedback.bytes(0)
        feedback.bytes(-1)
        assertEquals(9_900L, feedback.latencyMillis())
    }
}

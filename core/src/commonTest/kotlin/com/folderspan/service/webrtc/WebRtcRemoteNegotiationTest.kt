package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.controller.core.WEB_RTC_MAX_CANDIDATE_BYTES
import com.folderspan.service.webrtc.controller.core.WEB_RTC_MAX_PENDING_CANDIDATES
import com.folderspan.service.webrtc.controller.core.WebRtcRemoteNegotiation
import com.folderspan.service.webrtc.models.WebRtcIceCandidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WebRtcRemoteNegotiationTest {
    @Test
    fun fullCandidateQueueStillAllowsDescriptionAndRestartDiscardsRetiredCredentials() = runTest {
        val submitted = mutableListOf<String>()
        val queue = WebRtcRemoteNegotiation(backgroundScope, { true }, { submitted += it.candidate }, { throw it })
        repeat(WEB_RTC_MAX_PENDING_CANDIDATES) { assertTrue(queue.candidate(candidate("candidate-$it ufrag first"))) }
        assertTrue(queue.description(setOf("first")) {})
        runCurrent()
        assertEquals(WEB_RTC_MAX_PENDING_CANDIDATES, submitted.size)
        queue.candidate(candidate("early-restart ufrag second"))
        runCurrent()
        assertEquals(WEB_RTC_MAX_PENDING_CANDIDATES, submitted.size)
        queue.description(setOf("second")) {}
        queue.candidate(candidate("late-old ufrag first"))
        queue.candidate(candidate("unidentified-old"))
        queue.candidate(candidate("new ufrag second"))
        runCurrent()
        assertEquals(listOf("early-restart ufrag second", "new ufrag second"), submitted.drop(WEB_RTC_MAX_PENDING_CANDIDATES))
        queue.close()
    }

    @Test
    fun earlyAndConcurrentCandidatesWaitForDescriptionAndKeepTheirOrder() = runTest {
        val submitted = mutableListOf<String>()
        val ready = CompletableDeferred<Unit>()
        val errors = mutableListOf<Throwable>()
        val queue = WebRtcRemoteNegotiation(backgroundScope, { true }, { submitted += it.candidate }, errors::add)
        queue.candidate(candidate("first"))
        runCurrent()
        assertTrue(submitted.isEmpty())
        queue.description { ready.await() }
        runCurrent()
        queue.candidate(candidate("second"))
        runCurrent()
        assertTrue(submitted.isEmpty())
        ready.complete(Unit)
        runCurrent()
        queue.candidate(candidate("third"))
        runCurrent()
        assertEquals(listOf("first", "second", "third"), submitted)
        assertTrue(errors.isEmpty())
        queue.close()
    }

    @Test
    fun failedDescriptionAndFailedCandidateTerminateOnlyTheirQueue() = runTest {
        for (failDescription in listOf(true, false)) {
            val errors = mutableListOf<Throwable>()
            var submitted = 0
            val queue = WebRtcRemoteNegotiation(backgroundScope, { true }, {
                submitted++
                error("candidate rejected")
            }, errors::add)
            queue.candidate(candidate("early"))
            queue.description { if (failDescription) error("description rejected") }
            runCurrent()
            assertEquals(1, errors.size)
            assertEquals(if (failDescription) 0 else 1, submitted)
            assertFalse(queue.candidate(candidate("late")))
            assertFalse(queue.description {})
        }
        val healthy = mutableListOf<String>()
        val other = WebRtcRemoteNegotiation(backgroundScope, { true }, { healthy += it.candidate }, { throw it })
        other.description {}
        other.candidate(candidate("other peer"))
        runCurrent()
        assertEquals(listOf("other peer"), healthy)
        other.close()
    }

    @Test
    fun countAndByteLimitsIncludeCandidatesWaitingForDescription() = runTest {
        for (size in listOf(16, WEB_RTC_MAX_CANDIDATE_BYTES)) {
            val errors = mutableListOf<Throwable>()
            val queue = WebRtcRemoteNegotiation(backgroundScope, { true }, { error("SDP not ready") }, errors::add)
            val count = if (size == 16) WEB_RTC_MAX_PENDING_CANDIDATES else 32
            repeat(count) {
                assertTrue(queue.candidate(candidate("x".repeat(size))))
                runCurrent()
            }
            assertTrue(errors.isEmpty())
            assertFalse(queue.candidate(candidate("x".repeat(size))))
            assertEquals(1, errors.size)
        }
    }

    @Test
    fun limitsAlsoApplyWhileNativeDescriptionIsSuspended() = runTest {
        val errors = mutableListOf<Throwable>()
        val queue = WebRtcRemoteNegotiation(backgroundScope, { true }, {}, errors::add)
        queue.description { awaitCancellation() }
        runCurrent()
        repeat(WEB_RTC_MAX_PENDING_CANDIDATES) { assertTrue(queue.candidate(candidate("pending"))) }
        assertFalse(queue.candidate(candidate("overflow")))
        runCurrent()
        assertEquals(1, errors.size)
    }

    @Test
    fun utf8SizeAndSingleCandidateLimitAreEnforcedBeforeSubmission() = runTest {
        for (text in listOf("x".repeat(WEB_RTC_MAX_CANDIDATE_BYTES + 1), "中".repeat(2000))) {
            val errors = mutableListOf<Throwable>()
            val queue = WebRtcRemoteNegotiation(backgroundScope, { true }, {}, errors::add)
            assertFalse(queue.candidate(candidate(text)))
            assertEquals(1, errors.size)
        }
    }

    @Test
    fun closeAndObsoleteGenerationDiscardQueuedWorkWithoutFailure() = runTest {
        for (explicitClose in listOf(true, false)) {
            var current = true
            val submitted = mutableListOf<String>()
            val errors = mutableListOf<Throwable>()
            val queue = WebRtcRemoteNegotiation(backgroundScope, { current }, { submitted += it.candidate }, errors::add)
            queue.candidate(candidate("old"))
            queue.description {}
            if (explicitClose) queue.close() else current = false
            runCurrent()
            assertTrue(submitted.isEmpty())
            assertTrue(errors.isEmpty())
            assertFalse(queue.candidate(candidate("late")))
        }
    }

    private fun candidate(value: String) = WebRtcIceCandidate("data", 0, value)
}

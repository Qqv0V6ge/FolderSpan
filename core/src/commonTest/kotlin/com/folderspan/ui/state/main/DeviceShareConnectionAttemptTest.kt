package com.folderspan.ui.state.main

import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeviceShareConnectionAttemptTest {
    @Test
    fun cancellationPropagatesUnchangedWithoutReportingFailure() = runSuspendTest {
        val cancellation = CancellationException("User cancelled device sharing")
        val reportedFailures = mutableListOf<Exception>()
        var cancellationCleanupCalls = 0

        val thrown = assertFailsWith<CancellationException> {
            runDeviceShareConnectionAttempt(
                block = { throw cancellation },
                onFailure = { reportedFailures += it },
                onCancelled = { cancellationCleanupCalls++ },
            )
        }

        assertSame(cancellation, thrown)
        assertTrue(reportedFailures.isEmpty(), "Cancellation must not publish ERROR or a failure notification")
        assertEquals(1, cancellationCleanupCalls)
    }

    @Test
    fun ordinaryFailureIsReportedOnceWithoutCancellationCleanup() = runSuspendTest {
        val failure = IllegalStateException("Device rejected the transfer")
        val reportedFailures = mutableListOf<Exception>()
        var cancellationCleanupCalls = 0

        runDeviceShareConnectionAttempt(
            block = { throw failure },
            onFailure = { reportedFailures += it },
            onCancelled = { cancellationCleanupCalls++ },
        )

        assertEquals(1, reportedFailures.size)
        assertSame(failure, reportedFailures.single())
        assertEquals(0, cancellationCleanupCalls)
    }

    @Test
    fun successfulAttemptDoesNotReportFailureOrRunCancellationCleanup() = runSuspendTest {
        var completed = false
        var failureCalls = 0
        var cancellationCleanupCalls = 0

        runDeviceShareConnectionAttempt(
            block = { completed = true },
            onFailure = { failureCalls++ },
            onCancelled = { cancellationCleanupCalls++ },
        )

        assertTrue(completed)
        assertEquals(0, failureCalls)
        assertEquals(0, cancellationCleanupCalls)
    }
}

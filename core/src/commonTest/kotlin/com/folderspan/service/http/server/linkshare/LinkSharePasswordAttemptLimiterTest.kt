package com.folderspan.service.http.server.linkshare

import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinkSharePasswordAttemptLimiterTest {
    @Test
    fun locksAfterConsecutiveFailuresAndClearsOnSuccess() = runSuspendTest {
        var now = 1_000_000L
        val limiter = LinkSharePasswordAttemptLimiter(
            maxFailures = 3,
            lockoutDurationMillis = 60_000L,
            nowMillis = { now },
        )

        assertNull(limiter.registerFailure("192.0.2.10"))
        assertNull(limiter.registerFailure("192.0.2.10"))
        val lockout = assertNotNull(limiter.registerFailure("192.0.2.10"))
        assertEquals(60, lockout.retryAfterSeconds)
        assertEquals(60, limiter.lockoutOrNull("192.0.2.10")?.retryAfterSeconds)

        now += 30_000L
        assertEquals(30, limiter.lockoutOrNull("192.0.2.10")?.retryAfterSeconds)

        now += 30_000L
        assertNull(limiter.lockoutOrNull("192.0.2.10"))
        assertNull(limiter.registerFailure("192.0.2.10"))

        limiter.registerSuccess("192.0.2.10")
        assertNull(limiter.lockoutOrNull("192.0.2.10"))
        assertNull(limiter.registerFailure("192.0.2.10"))
        assertNull(limiter.registerFailure("192.0.2.10"))
    }

    @Test
    fun isolatesClientsByKey() = runSuspendTest {
        val limiter = LinkSharePasswordAttemptLimiter(
            maxFailures = 2,
            lockoutDurationMillis = 10_000L,
            nowMillis = { 5_000L },
        )
        assertNull(limiter.registerFailure("192.0.2.10"))
        assertNotNull(limiter.registerFailure("192.0.2.10"))
        assertNull(limiter.lockoutOrNull("192.0.2.11"))
    }
}

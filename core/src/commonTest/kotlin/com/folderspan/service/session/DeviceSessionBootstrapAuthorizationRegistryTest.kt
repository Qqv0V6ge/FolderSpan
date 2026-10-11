package com.folderspan.service.session

import com.folderspan.service.data.DeviceSessionBootstrapAuthorizationType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeviceSessionBootstrapAuthorizationRegistryTest {
    @Test
    fun validAuthorizationIsConsumedExactlyOnce() = runTest {
        val registry = registry()
        val authorization = registry.issue("initiator", "target", "attempt-1", 2L)

        assertEquals(DeviceSessionBootstrapAuthorizationType.WEB_RTC_PREAPPROVED, authorization.type)
        assertIs<DeviceSessionBootstrapAuthorizationConsumeResult.Granted>(
            registry.consume(authorization, "initiator", "target", "attempt-1", 2L),
        )
        assertDenied(
            DeviceSessionBootstrapAuthorizationDenial.MissingOrConsumed,
            registry.consume(authorization, "initiator", "target", "attempt-1", 2L),
        )
    }

    @Test
    fun mismatchedIdentityTargetAttemptAndRoleAreRejectedWithoutConsuming() = runTest {
        val registry = registry()
        val authorization = registry.issue("initiator", "target", "attempt-1", 2L)

        assertDenied(
            DeviceSessionBootstrapAuthorizationDenial.InitiatorMismatch,
            registry.consume(authorization, "other", "target", "attempt-1", 2L),
        )
        assertDenied(
            DeviceSessionBootstrapAuthorizationDenial.TargetMismatch,
            registry.consume(authorization, "initiator", "other", "attempt-1", 2L),
        )
        assertDenied(
            DeviceSessionBootstrapAuthorizationDenial.ConnectionAttemptMismatch,
            registry.consume(authorization, "initiator", "target", "attempt-2", 2L),
        )
        assertDenied(
            DeviceSessionBootstrapAuthorizationDenial.RoleScopeMismatch,
            registry.consume(authorization, "initiator", "target", "attempt-1", 3L),
        )
        assertIs<DeviceSessionBootstrapAuthorizationConsumeResult.Granted>(
            registry.consume(authorization, "initiator", "target", "attempt-1", 2L),
        )
    }

    @Test
    fun expiredAuthorizationIsRejectedAndRemoved() = runTest {
        var now = 1_000L
        val registry = registry(nowMillis = { now }, ttlMillis = 50L)
        val authorization = registry.issue("initiator", "target", "attempt-1", 2L)
        now += 50L

        assertDenied(
            DeviceSessionBootstrapAuthorizationDenial.Expired,
            registry.consume(authorization, "initiator", "target", "attempt-1", 2L),
        )
        assertEquals(0, registry.pendingCount())
    }

    @Test
    fun concurrentConsumptionHasSingleWinner() = runTest {
        val registry = registry()
        val authorization = registry.issue("initiator", "target", "attempt-1", 2L)

        val results = List(16) {
            async {
                registry.consume(authorization, "initiator", "target", "attempt-1", 2L)
            }
        }.awaitAll()

        assertEquals(1, results.count { it is DeviceSessionBootstrapAuthorizationConsumeResult.Granted })
        assertEquals(15, results.count { it is DeviceSessionBootstrapAuthorizationConsumeResult.Denied })
    }

    private fun registry(
        nowMillis: () -> Long = { 1_000L },
        ttlMillis: Long = 60_000L,
    ) = DeviceSessionBootstrapAuthorizationRegistry(
        ttlMillis = ttlMillis,
        nowMillis = nowMillis,
        opaqueAuthorizationFactory = tokenSequence().iterator()::next,
    )

    private fun tokenSequence(): Sequence<String> = generateSequence(1) { it + 1 }
        .map { index -> "opaque-authorization-$index" }

    private fun assertDenied(
        expected: DeviceSessionBootstrapAuthorizationDenial,
        actual: DeviceSessionBootstrapAuthorizationConsumeResult,
    ) {
        assertEquals(
            expected,
            assertIs<DeviceSessionBootstrapAuthorizationConsumeResult.Denied>(actual).reason,
        )
    }
}

package com.folderspan.service.session

import com.folderspan.data.main.webrtc.WebRtcDirectInvite
import com.folderspan.service.data.DeviceSessionBootstrapAuthorizationType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun directInviteHasOneWinnerAndRetriesOnlyTheOriginalOffer() = runTest {
        val registry = registry()
        val invite = registry.issueInvite()
        val bindings = listOf(binding(), binding().copy(initiatorDeviceId = "device-c"))
        val results = bindings.map { candidate ->
            async { runCatching { registry.claimDirectInvite(ISSUER, invite.credential, candidate) } }
        }.awaitAll()

        assertEquals(1, results.count { it.isSuccess })
        val winner = results.indexOfFirst { it.isSuccess }
        val original = results[winner].getOrThrow()
        val ownBinding = bindings[winner]
        assertEquals(DirectInviteDenial.AlreadyClaimed, assertIs<DirectInviteException>(results[1 - winner].exceptionOrNull()).reason)
        assertEquals(original, registry.claimDirectInvite(ISSUER, invite.credential, ownBinding))
        assertEquals("stun:ice.example.test:3478", original.stunUrl)
        for (changed in listOf(
            ownBinding.copy(connectionAttemptId = "new-attempt"),
            ownBinding.copy(offerSha256 = "changed-offer"),
            ownBinding.copy(initiatorCertificateSha256 = "different-identity"),
        )) {
            assertInviteDenied(DirectInviteDenial.AlreadyClaimed) { registry.claimDirectInvite(ISSUER, invite.credential, changed) }
        }
        assertDenied(
            DeviceSessionBootstrapAuthorizationDenial.DirectInviteMismatch,
            registry.consume(original.authorization, ownBinding.initiatorDeviceId, "device-a", ownBinding.connectionAttemptId, 2L),
        )
        assertDenied(
            DeviceSessionBootstrapAuthorizationDenial.DirectInviteMismatch,
            registry.consumeDirect(original, ownBinding.copy(initiatorCertificateSha256 = "different-identity")),
        )
        val second = registry.issueInvite()
        val secondClaim = registry.claimDirectInvite(ISSUER, second.credential, ownBinding)
        assertDenied(
            DeviceSessionBootstrapAuthorizationDenial.DirectInviteMismatch,
            registry.consumeDirect(secondClaim.copy(authorization = original.authorization), ownBinding),
        )
        registry.revokeDirectInvite(ISSUER, second.credential)
        assertIs<DeviceSessionBootstrapAuthorizationConsumeResult.Granted>(registry.consumeDirect(original, ownBinding))
        assertInviteDenied(DirectInviteDenial.AlreadyClaimed) { registry.claimDirectInvite(ISSUER, invite.credential, ownBinding) }
        assertEquals(0, registry.pendingCount())
    }

    @Test
    fun canceledExpiredAndRestartedInvitesCannotGrantAuthority() = runTest {
        var now = 1_000L
        val registry = registry(nowMillis = { now })
        val invite = registry.issueInvite()
        val binding = binding()
        assertEquals(301_000L, invite.expiresAtEpochMillis)
        assertInviteDenied(DirectInviteDenial.Missing) { registry().claimDirectInvite(ISSUER, invite.credential, binding) }
        assertInviteDenied(DirectInviteDenial.Missing) { registry.claimDirectInvite("new-session", invite.credential, binding) }

        val claim = registry.claimDirectInvite(ISSUER, invite.credential, binding)
        registry.revokeDirectInvite("different-session", invite.credential)
        assertEquals(claim, registry.claimDirectInvite(ISSUER, invite.credential, binding))
        registry.revokeDirectInvites(ISSUER)
        assertInviteDenied(DirectInviteDenial.Missing) { registry.claimDirectInvite(ISSUER, invite.credential, binding) }
        assertDenied(DeviceSessionBootstrapAuthorizationDenial.MissingOrConsumed, registry.consumeDirect(claim, binding))

        val canceled = registry.issueInvite()
        registry.revokeDirectInvite(ISSUER, canceled.credential)
        assertInviteDenied(DirectInviteDenial.Missing) { registry.claimDirectInvite(ISSUER, canceled.credential, binding) }

        val expired = registry.issueInvite()
        now = expired.expiresAtEpochMillis
        assertInviteDenied(DirectInviteDenial.Expired) { registry.claimDirectInvite(ISSUER, expired.credential, binding) }
        assertEquals(0, registry.pendingCount())
    }

    @Test
    fun permanentDenialAndRoleRevocationOverrideDirectInviteApproval() = runTest {
        var rejectedDevice: String? = "device-b"
        var roleActive = true
        val registry = registry(isRoleActive = { roleActive }, isPermanentlyRejected = { it == rejectedDevice })
        val binding = binding()
        val invite = registry.issueInvite()
        assertInviteDenied(DirectInviteDenial.DeviceDenied) { registry.claimDirectInvite(ISSUER, invite.credential, binding) }

        rejectedDevice = null
        val claim = registry.claimDirectInvite(ISSUER, invite.credential, binding)
        rejectedDevice = binding.initiatorDeviceId
        assertDenied(DeviceSessionBootstrapAuthorizationDenial.RoleScopeMismatch, registry.consumeDirect(claim, binding))

        rejectedDevice = null
        val renewal = registry.issueInvite()
        val renewedClaim = registry.claimDirectInvite(ISSUER, renewal.credential, binding)
        roleActive = false
        assertDenied(DeviceSessionBootstrapAuthorizationDenial.RoleScopeMismatch, registry.consumeDirect(renewedClaim, binding))
        assertInviteDenied(DirectInviteDenial.RoleRevoked) { registry.issueInvite() }
        assertEquals(0, registry.pendingCount())
    }

    @Test
    fun expiredBootstrapCannotBeReissuedUsingSameInvite() = runTest {
        var now = 1_000L
        val registry = registry(nowMillis = { now }, ttlMillis = 50L)
        val binding = binding()
        val invite = registry.issueInvite()
        val claim = registry.claimDirectInvite(ISSUER, invite.credential, binding)
        now += 50L
        assertInviteDenied(DirectInviteDenial.AlreadyClaimed) { registry.claimDirectInvite(ISSUER, invite.credential, binding) }
        assertDenied(DeviceSessionBootstrapAuthorizationDenial.Expired, registry.consumeDirect(claim, binding))
    }

    private fun registry(
        nowMillis: () -> Long = { 1_000L },
        ttlMillis: Long = 60_000L,
        isRoleActive: suspend (Long) -> Boolean = { true },
        isPermanentlyRejected: suspend (String) -> Boolean = { false },
    ) = DeviceSessionBootstrapAuthorizationRegistry(
        ttlMillis = ttlMillis,
        nowMillis = nowMillis,
        opaqueAuthorizationFactory = tokenSequence().iterator()::next,
        isDirectInviteRoleActive = isRoleActive,
        isPermanentlyRejected = isPermanentlyRejected,
    )

    private suspend fun DeviceSessionBootstrapAuthorizationRegistry.issueInvite(): WebRtcDirectInvite =
        issueDirectInvite(ISSUER, "device-a", "2001:db8::1", 48484, "a".repeat(64), 2L, "stun:ice.example.test:3478")

    private fun binding() = DirectInviteBinding("device-b", "verified-identity", "attempt-1", "offer")

    private suspend fun DeviceSessionBootstrapAuthorizationRegistry.consumeDirect(
        claim: DirectInviteClaim,
        binding: DirectInviteBinding,
    ): DeviceSessionBootstrapAuthorizationConsumeResult = consume(
        claim.authorization, binding.initiatorDeviceId, "device-a", binding.connectionAttemptId, claim.roleId,
        claim.sessionBinding.copy(peer = binding),
    )

    private inline fun assertInviteDenied(reason: DirectInviteDenial, block: () -> Unit) {
        assertEquals(reason, assertFailsWith<DirectInviteException>(block = block).reason)
    }

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

    companion object {
        private const val ISSUER = "issuing-session"
    }
}

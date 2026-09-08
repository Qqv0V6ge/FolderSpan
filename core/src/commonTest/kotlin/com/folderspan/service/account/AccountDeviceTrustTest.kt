package com.folderspan.service.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AccountDeviceTrustTest {
    @Test
    fun registryExcludesLocalAndRejectsOldSessionOrStaleResult() {
        val registry = InMemoryAccountDeviceTrustRegistry()
        registry.beginSession("session-a", "local")

        val first = registry.replaceSnapshot(
            sessionGeneration = "session-a",
            fetchedAtEpochMillis = 1_000L,
            expiresAtEpochMillis = 301_000L,
            devices = listOf(identity("local"), identity("peer"), identity("missing", publicKey = "")),
        )

        assertEquals(AccountDeviceTrustUpdateResult.Applied, first.result)
        assertEquals(setOf("peer"), registry.state.value.devices.keys)
        assertTrue(registry.state.value.isFresh(2_000L))
        assertTrue(registry.state.value.isNearExpiry(250_000L))
        assertEquals(
            AccountDeviceTrustUpdateResult.Stale,
            registry.replaceSnapshot("session-a", 999L, 10_000L, emptyList()).result,
        )
        assertEquals(
            AccountDeviceTrustUpdateResult.WrongSession,
            registry.replaceSnapshot("session-b", 2_000L, 10_000L, emptyList()).result,
        )
    }

    @Test
    fun replacementReportsRemovedDevicesAndAccountSwitchClearsTrust() {
        val registry = InMemoryAccountDeviceTrustRegistry()
        registry.beginSession("session-a", "local")
        registry.replaceSnapshot("session-a", 1_000L, 301_000L, listOf(identity("a"), identity("b")))

        val update = registry.replaceSnapshot("session-a", 2_000L, 302_000L, listOf(identity("b")))
        assertEquals(setOf("a"), update.removedDeviceKeys)

        registry.beginSession("session-b", "local")
        assertTrue(registry.state.value.devices.isEmpty())
        assertFalse(registry.state.value.isFresh(2_000L))
    }

    @Test
    fun proofVerificationBindsPurposeTargetBodyAndRejectsReplay() = runTest {
        val now = 120_000L
        val snapshot = freshSnapshot(now)
        val replayCache = AccountDeviceNonceReplayCache(maxEntries = 4)
        val context = AccountDeviceProofContext(
            purpose = ACCOUNT_DEVICE_CONNECT_PURPOSE,
            signerDeviceKey = "peer",
            targetDeviceKey = "local",
            targetPath = "/api/devices/connect",
            nonce = "nonce-1",
            issuedAtEpochSeconds = now / 1_000L,
            payloadSha256 = "ab".repeat(32),
        )
        val proof = createAccountDeviceProof(context, FakeSigner)

        assertEquals(
            AccountDeviceProofVerification.Valid,
            verifyAccountDeviceProof(proof, snapshot, context, now, replayCache, FakeVerifier),
        )
        assertEquals(
            AccountDeviceProofVerification.Replay,
            verifyAccountDeviceProof(proof, snapshot, context, now, replayCache, FakeVerifier),
        )
        assertEquals(
            AccountDeviceProofVerification.InvalidSignature,
            verifyAccountDeviceProof(
                proof?.copy(nonce = "nonce-2"),
                snapshot,
                context.copy(nonce = "nonce-2", targetDeviceKey = "other"),
                now,
                replayCache,
                FakeVerifier,
            ),
        )
        assertEquals(
            AccountDeviceProofVerification.WrongPurpose,
            verifyAccountDeviceProof(
                proof,
                snapshot,
                context.copy(purpose = ACCOUNT_DEVICE_DISCOVERY_PURPOSE),
                now,
                AccountDeviceNonceReplayCache(),
                FakeVerifier,
            ),
        )
    }

    @Test
    fun proofExpirationAndDecisionFailClosed() = runTest {
        val now = 300_000L
        val snapshot = freshSnapshot(now)
        val context = AccountDeviceProofContext(
            purpose = ACCOUNT_DEVICE_DISCOVERY_PURPOSE,
            signerDeviceKey = "peer",
            targetDeviceKey = "local",
            targetPath = "/ping",
            nonce = "challenge",
            issuedAtEpochSeconds = 1L,
            payloadSha256 = "cd".repeat(32),
        )
        val proof = createAccountDeviceProof(context, FakeSigner)
        val verification = verifyAccountDeviceProof(
            proof,
            snapshot,
            context,
            now,
            AccountDeviceNonceReplayCache(),
            FakeVerifier,
        )
        assertEquals(AccountDeviceProofVerification.Expired, verification)
        assertEquals(
            AccountDeviceLanDecision.Reject,
            resolveAccountDeviceLanDecision(true, snapshot, now, "peer", verification, false, false),
        )
        assertEquals(
            AccountDeviceLanDecision.DeferToExistingFlow,
            resolveAccountDeviceLanDecision(
                true,
                snapshot,
                now,
                "peer",
                AccountDeviceProofVerification.Missing,
                false,
                false,
            ),
        )
        assertEquals(
            AccountDeviceLanDecision.Reject,
            resolveAccountDeviceLanDecision(false, snapshot, now, "peer", verification, true, false),
        )
    }

    @Test
    fun discoveryAndConnectDecisionMatrixPreservesExistingFallbacks() {
        val now = 120_000L
        val snapshot = freshSnapshot(now)

        assertEquals(
            AccountDeviceLanDecision.AutoConnect,
            resolveAccountDeviceLanDecision(
                true,
                snapshot,
                now,
                "peer",
                AccountDeviceProofVerification.Valid,
                false,
                false,
            ),
        )
        assertEquals(
            AccountDeviceLanDecision.DeferToExistingFlow,
            resolveAccountDeviceLanDecision(
                true,
                snapshot,
                now,
                "peer",
                AccountDeviceProofVerification.Missing,
                false,
                false,
            ),
        )
        assertEquals(
            AccountDeviceLanDecision.DeferToExistingFlow,
            resolveAccountDeviceLanDecision(
                true,
                snapshot,
                now,
                "unknown",
                AccountDeviceProofVerification.NotTrusted,
                false,
                false,
            ),
        )
        assertEquals(
            AccountDeviceLanDecision.Reject,
            resolveAccountDeviceLanDecision(
                true,
                snapshot,
                now,
                "peer",
                AccountDeviceProofVerification.InvalidSignature,
                false,
                false,
            ),
        )
        assertEquals(
            AccountDeviceLanDecision.Reject,
            resolveAccountDeviceLanDecision(
                true,
                snapshot,
                now,
                "peer",
                AccountDeviceProofVerification.Valid,
                true,
                false,
            ),
        )
        assertEquals(
            AccountDeviceLanDecision.DeferToExistingFlow,
            resolveAccountDeviceLanDecision(
                true,
                snapshot,
                now,
                "peer",
                AccountDeviceProofVerification.Valid,
                false,
                true,
            ),
        )
        assertEquals(ACCOUNT_DEVICE_MINIMUM_ROLE_ID, resolveAccountDevicePreferredRoleId(null))
        assertEquals(ACCOUNT_DEVICE_MINIMUM_ROLE_ID, resolveAccountDevicePreferredRoleId(0L))
        assertEquals(8L, resolveAccountDevicePreferredRoleId(8L))
    }

    @Test
    fun proofRejectsChangedRequestBodyWrongTargetAndForgedIdentity() = runTest {
        val now = 120_000L
        val snapshot = freshSnapshot(now)
        val context = AccountDeviceProofContext(
            purpose = ACCOUNT_DEVICE_CONNECT_PURPOSE,
            signerDeviceKey = "peer",
            targetDeviceKey = "local",
            targetPath = "/api/devices/connect",
            nonce = "nonce-body",
            issuedAtEpochSeconds = now / 1_000L,
            payloadSha256 = "ab".repeat(32),
        )
        val proof = createAccountDeviceProof(context, FakeSigner)

        assertEquals(
            AccountDeviceProofVerification.InvalidSignature,
            verifyAccountDeviceProof(
                proof,
                snapshot,
                context.copy(payloadSha256 = "cd".repeat(32)),
                now,
                AccountDeviceNonceReplayCache(),
                FakeVerifier,
            ),
        )
        assertEquals(
            AccountDeviceProofVerification.InvalidSignature,
            verifyAccountDeviceProof(
                proof,
                snapshot,
                context.copy(targetDeviceKey = "other"),
                now,
                AccountDeviceNonceReplayCache(),
                FakeVerifier,
            ),
        )
        assertEquals(
            AccountDeviceProofVerification.IdentityConflict,
            verifyAccountDeviceProof(
                proof?.copy(deviceKey = "forged"),
                snapshot,
                context,
                now,
                AccountDeviceNonceReplayCache(),
                FakeVerifier,
            ),
        )
    }

    private fun freshSnapshot(now: Long) = AccountDeviceTrustSnapshot(
        sessionGeneration = "session",
        localDeviceKey = "local",
        fetchedAtEpochMillis = now - 1_000L,
        expiresAtEpochMillis = now + ACCOUNT_DEVICE_TRUST_TTL_MILLIS,
        devices = mapOf("peer" to identity("peer")),
    )

    private fun identity(deviceKey: String, publicKey: String = "test-public") = AccountDeviceTrustedIdentity(
        deviceKey = deviceKey,
        identityPublicKey = publicKey,
        identityAlgorithm = ACCOUNT_DEVICE_IDENTITY_ALGORITHM,
        identityKeyId = "test-key",
    )

    private object FakeSigner : AccountDeviceIdentitySigner {
        override val algorithm = ACCOUNT_DEVICE_IDENTITY_ALGORITHM
        override val keyId = "test-key"
        override val publicKey = "test-public"
        override fun sign(payload: ByteArray): String = payload.accountDeviceSha256()
    }

    private object FakeVerifier : AccountDeviceIdentityVerifier {
        override fun verify(
            identity: AccountDeviceTrustedIdentity,
            payload: ByteArray,
            signature: String,
        ): Boolean = identity.identityPublicKey == "test-public" && signature == payload.accountDeviceSha256()
    }
}

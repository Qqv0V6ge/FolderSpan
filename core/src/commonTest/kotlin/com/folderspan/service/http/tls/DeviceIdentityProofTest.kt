package com.folderspan.service.http.tls

import com.folderspan.service.account.AccountDeviceNonceReplayCache
import com.folderspan.service.account.accountDeviceSha256
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DeviceIdentityProofTest {
    @Test
    fun verifyAcceptsValidProofAndRejectsReplayIdentityConflictAndExpiry() = runTest {
        val nowMillis = 120_000L
        val replayCache = AccountDeviceNonceReplayCache(maxEntries = 4)
        val proof = requireNotNull(
            createDeviceIdentityProof(
                targetDeviceId = "target",
                targetPath = "/api/devices/connect",
                payloadSha256 = "ab".repeat(32),
                nowEpochSeconds = nowMillis / 1_000L,
                nonce = "nonce-1",
                sign = { payload -> payload.accountDeviceSha256() },
                publicKeyPem = TRUSTED_PEM,
                fingerprintSha256 = TRUSTED_FINGERPRINT,
                deviceId = "peer",
            ),
        )

        assertEquals(
            DeviceIdentityProofVerification.Valid,
            verify(proof, replayCache = replayCache, nowEpochMillis = nowMillis),
        )
        assertEquals(
            DeviceIdentityProofVerification.Replay,
            verify(proof, replayCache = replayCache, nowEpochMillis = nowMillis),
        )
        assertEquals(
            DeviceIdentityProofVerification.IdentityConflict,
            verify(
                proof.copy(deviceId = "other"),
                expectedDeviceId = "peer",
                replayCache = AccountDeviceNonceReplayCache(),
                nowEpochMillis = nowMillis,
            ),
        )
        assertEquals(
            DeviceIdentityProofVerification.IdentityConflict,
            verify(
                proof.copy(fingerprintSha256 = "DEADBEEF"),
                replayCache = AccountDeviceNonceReplayCache(),
                nowEpochMillis = nowMillis,
            ),
        )
        assertEquals(
            DeviceIdentityProofVerification.Expired,
            verify(
                proof.copy(nonce = "nonce-expired"),
                replayCache = AccountDeviceNonceReplayCache(),
                nowEpochMillis = nowMillis + (DEVICE_IDENTITY_PROOF_MAX_AGE_SECONDS + 1) * 1_000L,
            ),
        )
        assertEquals(
            DeviceIdentityProofVerification.Missing,
            verify(null, replayCache = AccountDeviceNonceReplayCache(), nowEpochMillis = nowMillis),
        )
        assertEquals(
            DeviceIdentityProofVerification.InvalidSignature,
            verify(
                proof.copy(nonce = "nonce-bad-signature", signature = "invalid"),
                replayCache = AccountDeviceNonceReplayCache(),
                nowEpochMillis = nowMillis,
            ),
        )
        assertNotNull(proof.signature)
    }

    private suspend fun verify(
        proof: DeviceIdentityProof?,
        expectedDeviceId: String = "peer",
        replayCache: AccountDeviceNonceReplayCache,
        nowEpochMillis: Long,
    ): DeviceIdentityProofVerification = verifyDeviceIdentityProof(
        proof = proof,
        expectedDeviceId = expectedDeviceId,
        targetDeviceId = "target",
        targetPath = "/api/devices/connect",
        payloadSha256 = "ab".repeat(32),
        nowEpochMillis = nowEpochMillis,
        replayCache = replayCache,
        trustedFingerprint = TRUSTED_FINGERPRINT,
        trustedPublicKeyPem = TRUSTED_PEM,
        verifier = { publicKeyPem, payload, signature ->
            publicKeyPem == TRUSTED_PEM && signature == payload.accountDeviceSha256()
        },
    )

    private companion object {
        const val TRUSTED_FINGERPRINT = "AABBCC"
        const val TRUSTED_PEM = "-----BEGIN PUBLIC KEY-----\ntest\n-----END PUBLIC KEY-----"
    }
}

package com.folderspan.service.account

import com.folderspan.service.http.tls.DeviceTlsIdentity
import com.folderspan.utils.secureRandomBytes
import korlibs.crypto.sha256
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

const val ACCOUNT_DEVICE_IDENTITY_ALGORITHM = "SHA256withRSA"
const val ACCOUNT_DEVICE_PROOF_VERSION = 1
const val ACCOUNT_DEVICE_DISCOVERY_PURPOSE = "lan-discovery"
const val ACCOUNT_DEVICE_CONNECT_PURPOSE = "lan-connect"
const val ACCOUNT_DEVICE_PROOF_MAX_AGE_SECONDS = 60L
const val ACCOUNT_DEVICE_TRUST_TTL_MILLIS = 5L * 60L * 1_000L
const val ACCOUNT_DEVICE_TRUST_NEAR_EXPIRY_MILLIS = 60L * 1_000L
const val ACCOUNT_DEVICE_MINIMUM_ROLE_ID = 2L

const val ACCOUNT_DEVICE_CHALLENGE_HEADER = "x-folderspan-account-challenge"
const val ACCOUNT_DEVICE_PROOF_VERSION_HEADER = "x-folderspan-account-proof-version"
const val ACCOUNT_DEVICE_PROOF_PURPOSE_HEADER = "x-folderspan-account-proof-purpose"
const val ACCOUNT_DEVICE_PROOF_DEVICE_HEADER = "x-folderspan-account-proof-device"
const val ACCOUNT_DEVICE_PROOF_KEY_HEADER = "x-folderspan-account-proof-key"
const val ACCOUNT_DEVICE_PROOF_TIME_HEADER = "x-folderspan-account-proof-time"
const val ACCOUNT_DEVICE_PROOF_NONCE_HEADER = "x-folderspan-account-proof-nonce"
const val ACCOUNT_DEVICE_PROOF_SIGNATURE_HEADER = "x-folderspan-account-proof-signature"

data class AccountDeviceTrustedIdentity(
    val deviceKey: String,
    val name: String = "",
    val type: String = "",
    val identityPublicKey: String,
    val identityAlgorithm: String,
    val identityKeyId: String,
) {
    fun normalized(): AccountDeviceTrustedIdentity = copy(
        deviceKey = deviceKey.trim(),
        name = name.trim(),
        type = type.trim(),
        identityPublicKey = identityPublicKey.trim(),
        identityAlgorithm = identityAlgorithm.trim(),
        identityKeyId = identityKeyId.trim(),
    )

    fun isVerifiable(): Boolean =
        deviceKey.isNotBlank() &&
            identityPublicKey.isNotBlank() &&
            identityKeyId.isNotBlank() &&
            identityAlgorithm.equals(ACCOUNT_DEVICE_IDENTITY_ALGORITHM, ignoreCase = true)
}

data class AccountDeviceTrustSnapshot(
    val sessionGeneration: String? = null,
    val localDeviceKey: String? = null,
    val fetchedAtEpochMillis: Long? = null,
    val expiresAtEpochMillis: Long? = null,
    val devices: Map<String, AccountDeviceTrustedIdentity> = emptyMap(),
) {
    fun isFresh(nowEpochMillis: Long): Boolean =
        sessionGeneration != null &&
            fetchedAtEpochMillis?.let { it <= nowEpochMillis + ACCOUNT_DEVICE_PROOF_MAX_AGE_SECONDS * 1_000L } == true &&
            expiresAtEpochMillis?.let { it > nowEpochMillis } == true

    fun isNearExpiry(nowEpochMillis: Long): Boolean =
        !isFresh(nowEpochMillis) ||
            expiresAtEpochMillis?.let { it - nowEpochMillis <= ACCOUNT_DEVICE_TRUST_NEAR_EXPIRY_MILLIS } != false
}

enum class AccountDeviceTrustUpdateResult {
    Applied,
    WrongSession,
    Stale,
}

data class AccountDeviceTrustUpdate(
    val result: AccountDeviceTrustUpdateResult,
    val removedDeviceKeys: Set<String> = emptySet(),
)

interface AccountDeviceTrustRegistry {
    val state: StateFlow<AccountDeviceTrustSnapshot>

    fun beginSession(sessionGeneration: String, localDeviceKey: String)

    fun replaceSnapshot(
        sessionGeneration: String,
        fetchedAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
        devices: List<AccountDeviceTrustedIdentity>,
    ): AccountDeviceTrustUpdate

    fun clear(sessionGeneration: String? = null)
}

class InMemoryAccountDeviceTrustRegistry : AccountDeviceTrustRegistry {
    private val mutableState = MutableStateFlow(AccountDeviceTrustSnapshot())
    override val state: StateFlow<AccountDeviceTrustSnapshot> = mutableState.asStateFlow()

    override fun beginSession(sessionGeneration: String, localDeviceKey: String) {
        mutableState.value = AccountDeviceTrustSnapshot(
            sessionGeneration = sessionGeneration.trim(),
            localDeviceKey = localDeviceKey.trim(),
        )
    }

    override fun replaceSnapshot(
        sessionGeneration: String,
        fetchedAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
        devices: List<AccountDeviceTrustedIdentity>,
    ): AccountDeviceTrustUpdate {
        var update = AccountDeviceTrustUpdate(AccountDeviceTrustUpdateResult.WrongSession)
        mutableState.update { current ->
            if (current.sessionGeneration != sessionGeneration) return@update current
            if (current.fetchedAtEpochMillis?.let { fetchedAtEpochMillis < it } == true) {
                update = AccountDeviceTrustUpdate(AccountDeviceTrustUpdateResult.Stale)
                return@update current
            }
            val normalizedDevices = devices
                .asSequence()
                .map(AccountDeviceTrustedIdentity::normalized)
                .filter(AccountDeviceTrustedIdentity::isVerifiable)
                .filterNot { it.deviceKey == current.localDeviceKey }
                .associateBy(AccountDeviceTrustedIdentity::deviceKey)
            update = AccountDeviceTrustUpdate(
                result = AccountDeviceTrustUpdateResult.Applied,
                removedDeviceKeys = current.devices.keys - normalizedDevices.keys,
            )
            current.copy(
                fetchedAtEpochMillis = fetchedAtEpochMillis,
                expiresAtEpochMillis = expiresAtEpochMillis,
                devices = normalizedDevices,
            )
        }
        return update
    }

    override fun clear(sessionGeneration: String?) {
        mutableState.update { current ->
            if (sessionGeneration == null || current.sessionGeneration == sessionGeneration) {
                AccountDeviceTrustSnapshot()
            } else {
                current
            }
        }
    }
}

fun interface AccountDeviceTrustRefreshTrigger {
    suspend fun refreshIfNeeded(force: Boolean): Boolean
}

object NoOpAccountDeviceTrustRefreshTrigger : AccountDeviceTrustRefreshTrigger {
    override suspend fun refreshIfNeeded(force: Boolean): Boolean = false
}

interface AccountDeviceAutomation {
    suspend fun isPermanentlyRejected(deviceKey: String): Boolean
    suspend fun isConnectedOrConnecting(deviceKey: String): Boolean
    suspend fun revokeTemporaryTrust(deviceKey: String, disconnect: Boolean)
    suspend fun clearTemporaryTrust(disconnect: Boolean)
}

object NoOpAccountDeviceAutomation : AccountDeviceAutomation {
    override suspend fun isPermanentlyRejected(deviceKey: String): Boolean = false
    override suspend fun isConnectedOrConnecting(deviceKey: String): Boolean = false
    override suspend fun revokeTemporaryTrust(deviceKey: String, disconnect: Boolean) = Unit
    override suspend fun clearTemporaryTrust(disconnect: Boolean) = Unit
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AccountDeviceProof(
    @ProtoNumber(1) val version: Int,
    @ProtoNumber(2) val purpose: String,
    @ProtoNumber(3) val deviceKey: String,
    @ProtoNumber(4) val keyId: String,
    @ProtoNumber(5) val issuedAtEpochSeconds: Long,
    @ProtoNumber(6) val nonce: String,
    @ProtoNumber(7) val signature: String,
)

data class AccountDeviceProofContext(
    val purpose: String,
    val signerDeviceKey: String,
    val targetDeviceKey: String,
    val targetPath: String,
    val nonce: String,
    val issuedAtEpochSeconds: Long,
    val payloadSha256: String,
)

interface AccountDeviceIdentitySigner {
    val algorithm: String
    val keyId: String
    val publicKey: String
    fun sign(payload: ByteArray): String?
}

interface AccountDeviceIdentityVerifier {
    fun verify(identity: AccountDeviceTrustedIdentity, payload: ByteArray, signature: String): Boolean
}

object CurrentAccountDeviceIdentitySigner : AccountDeviceIdentitySigner {
    override val algorithm: String
        get() = ACCOUNT_DEVICE_IDENTITY_ALGORITHM
    override val keyId: String
        get() = DeviceTlsIdentity.loadOrCreate().fingerprintSha256.normalizedIdentityKeyId()
    override val publicKey: String
        get() = DeviceTlsIdentity.publicKeyPem().orEmpty()

    override fun sign(payload: ByteArray): String? = DeviceTlsIdentity.signSha256WithRsa(payload)
}

object CurrentAccountDeviceIdentityVerifier : AccountDeviceIdentityVerifier {
    override fun verify(
        identity: AccountDeviceTrustedIdentity,
        payload: ByteArray,
        signature: String,
    ): Boolean = identity.identityAlgorithm.equals(ACCOUNT_DEVICE_IDENTITY_ALGORITHM, ignoreCase = true) &&
        DeviceTlsIdentity.verifySha256WithRsa(identity.identityPublicKey, payload, signature)
}

fun createAccountDeviceProof(
    context: AccountDeviceProofContext,
    signer: AccountDeviceIdentitySigner = CurrentAccountDeviceIdentitySigner,
): AccountDeviceProof? {
    if (context.purpose !in setOf(ACCOUNT_DEVICE_DISCOVERY_PURPOSE, ACCOUNT_DEVICE_CONNECT_PURPOSE)) return null
    if (context.signerDeviceKey.isBlank() || context.targetDeviceKey.isBlank() || context.nonce.isBlank()) return null
    if (signer.publicKey.isBlank() || signer.keyId.isBlank()) return null
    val signature = signer.sign(context.canonicalPayload())?.takeIf(String::isNotBlank) ?: return null
    return AccountDeviceProof(
        version = ACCOUNT_DEVICE_PROOF_VERSION,
        purpose = context.purpose,
        deviceKey = context.signerDeviceKey,
        keyId = signer.keyId,
        issuedAtEpochSeconds = context.issuedAtEpochSeconds,
        nonce = context.nonce,
        signature = signature,
    )
}

fun AccountDeviceProofContext.canonicalPayload(): ByteArray = buildString {
    append("folderspan-account-device-proof-v1\n")
    append("purpose=").append(purpose).append('\n')
    append("signer=").append(signerDeviceKey.trim()).append('\n')
    append("target=").append(targetDeviceKey.trim()).append('\n')
    append("path=").append(targetPath).append('\n')
    append("issuedAt=").append(issuedAtEpochSeconds).append('\n')
    append("nonce=").append(nonce).append('\n')
    append("payloadSha256=").append(payloadSha256.lowercase()).append('\n')
}.encodeToByteArray()

enum class AccountDeviceProofVerification {
    Valid,
    Missing,
    StaleTrust,
    NotTrusted,
    InvalidVersion,
    WrongPurpose,
    IdentityConflict,
    Expired,
    WrongNonce,
    InvalidSignature,
    Replay,
}

class AccountDeviceNonceReplayCache(
    private val maxEntries: Int = 1_024,
) {
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, Long>()

    suspend fun consume(
        deviceKey: String,
        purpose: String,
        nonce: String,
        expiresAtEpochSeconds: Long,
        nowEpochSeconds: Long,
    ): Boolean = mutex.withLock {
        entries.entries.removeAll { it.value < nowEpochSeconds }
        val cacheKey = "$purpose\u0000${deviceKey.trim()}\u0000$nonce"
        if (cacheKey in entries) return@withLock false
        while (entries.size >= maxEntries) {
            entries.remove(entries.keys.firstOrNull() ?: break)
        }
        entries[cacheKey] = expiresAtEpochSeconds
        true
    }

    suspend fun clear() = mutex.withLock { entries.clear() }
}

suspend fun verifyAccountDeviceProof(
    proof: AccountDeviceProof?,
    snapshot: AccountDeviceTrustSnapshot,
    context: AccountDeviceProofContext,
    nowEpochMillis: Long,
    replayCache: AccountDeviceNonceReplayCache,
    verifier: AccountDeviceIdentityVerifier = CurrentAccountDeviceIdentityVerifier,
): AccountDeviceProofVerification {
    proof ?: return AccountDeviceProofVerification.Missing
    if (!snapshot.isFresh(nowEpochMillis)) return AccountDeviceProofVerification.StaleTrust
    if (proof.version != ACCOUNT_DEVICE_PROOF_VERSION) return AccountDeviceProofVerification.InvalidVersion
    if (proof.purpose != context.purpose) return AccountDeviceProofVerification.WrongPurpose
    if (proof.deviceKey.trim() != context.signerDeviceKey.trim()) return AccountDeviceProofVerification.IdentityConflict
    val identity = snapshot.devices[proof.deviceKey.trim()] ?: return AccountDeviceProofVerification.NotTrusted
    if (proof.keyId.trim() != identity.identityKeyId.trim()) return AccountDeviceProofVerification.IdentityConflict
    val nowEpochSeconds = nowEpochMillis / 1_000L
    if (abs(nowEpochSeconds - proof.issuedAtEpochSeconds) > ACCOUNT_DEVICE_PROOF_MAX_AGE_SECONDS) {
        return AccountDeviceProofVerification.Expired
    }
    if (context.nonce.isNotBlank() && proof.nonce != context.nonce) return AccountDeviceProofVerification.WrongNonce
    val canonicalContext = context.copy(
        signerDeviceKey = proof.deviceKey,
        nonce = proof.nonce,
        issuedAtEpochSeconds = proof.issuedAtEpochSeconds,
    )
    if (!verifier.verify(identity, canonicalContext.canonicalPayload(), proof.signature)) {
        return AccountDeviceProofVerification.InvalidSignature
    }
    if (!replayCache.consume(
            deviceKey = proof.deviceKey,
            purpose = proof.purpose,
            nonce = proof.nonce,
            expiresAtEpochSeconds = proof.issuedAtEpochSeconds + ACCOUNT_DEVICE_PROOF_MAX_AGE_SECONDS,
            nowEpochSeconds = nowEpochSeconds,
        )
    ) {
        return AccountDeviceProofVerification.Replay
    }
    return AccountDeviceProofVerification.Valid
}

enum class AccountDeviceLanDecision {
    AutoConnect,
    DeferToExistingFlow,
    Reject,
}

fun resolveAccountDevicePreferredRoleId(existingRoleId: Long?): Long =
    existingRoleId?.takeIf { it > 0L } ?: ACCOUNT_DEVICE_MINIMUM_ROLE_ID

fun resolveAccountDeviceLanDecision(
    featureEnabled: Boolean,
    snapshot: AccountDeviceTrustSnapshot,
    nowEpochMillis: Long,
    deviceKey: String,
    proofVerification: AccountDeviceProofVerification,
    permanentlyRejected: Boolean,
    connectedOrConnecting: Boolean,
): AccountDeviceLanDecision {
    if (permanentlyRejected) return AccountDeviceLanDecision.Reject
    if (!featureEnabled || !snapshot.isFresh(nowEpochMillis) || connectedOrConnecting) {
        return AccountDeviceLanDecision.DeferToExistingFlow
    }
    if (snapshot.devices[deviceKey.trim()] == null) return AccountDeviceLanDecision.DeferToExistingFlow
    return when (proofVerification) {
        AccountDeviceProofVerification.Valid -> AccountDeviceLanDecision.AutoConnect
        AccountDeviceProofVerification.Missing,
        AccountDeviceProofVerification.StaleTrust,
        AccountDeviceProofVerification.NotTrusted -> AccountDeviceLanDecision.DeferToExistingFlow
        else -> AccountDeviceLanDecision.Reject
    }
}

fun newAccountDeviceNonce(): String = secureRandomBytes(24).toHexLower()

fun ByteArray.accountDeviceSha256(): String = sha256().hexLower

fun AccountDeviceProof.toHeaders(): Map<String, String> = mapOf(
    ACCOUNT_DEVICE_PROOF_VERSION_HEADER to version.toString(),
    ACCOUNT_DEVICE_PROOF_PURPOSE_HEADER to purpose,
    ACCOUNT_DEVICE_PROOF_DEVICE_HEADER to deviceKey,
    ACCOUNT_DEVICE_PROOF_KEY_HEADER to keyId,
    ACCOUNT_DEVICE_PROOF_TIME_HEADER to issuedAtEpochSeconds.toString(),
    ACCOUNT_DEVICE_PROOF_NONCE_HEADER to nonce,
    ACCOUNT_DEVICE_PROOF_SIGNATURE_HEADER to signature,
)

fun accountDeviceProofFromHeaders(header: (String) -> String?): AccountDeviceProof? {
    val version = header(ACCOUNT_DEVICE_PROOF_VERSION_HEADER)?.toIntOrNull() ?: return null
    val purpose = header(ACCOUNT_DEVICE_PROOF_PURPOSE_HEADER)?.takeIf(String::isNotBlank) ?: return null
    val deviceKey = header(ACCOUNT_DEVICE_PROOF_DEVICE_HEADER)?.takeIf(String::isNotBlank) ?: return null
    val keyId = header(ACCOUNT_DEVICE_PROOF_KEY_HEADER)?.takeIf(String::isNotBlank) ?: return null
    val issuedAt = header(ACCOUNT_DEVICE_PROOF_TIME_HEADER)?.toLongOrNull() ?: return null
    val nonce = header(ACCOUNT_DEVICE_PROOF_NONCE_HEADER)?.takeIf(String::isNotBlank) ?: return null
    val signature = header(ACCOUNT_DEVICE_PROOF_SIGNATURE_HEADER)?.takeIf(String::isNotBlank) ?: return null
    return AccountDeviceProof(version, purpose, deviceKey, keyId, issuedAt, nonce, signature)
}

private fun ByteArray.toHexLower(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private fun String.normalizedIdentityKeyId(): String =
    filterNot { it == ':' || it.isWhitespace() }.lowercase()

package com.folderspan.service.session

import com.folderspan.data.main.webrtc.WebRtcDirectInvite
import com.folderspan.data.main.webrtc.WebRtcShareCode
import com.folderspan.service.data.DeviceSessionBootstrapAuthorization
import com.folderspan.service.data.DeviceSessionBootstrapAuthorizationType
import com.folderspan.ui.state.device.DeviceSharePathScope
import com.folderspan.utils.secureRandomBytes
import korlibs.crypto.sha256
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.time.Clock

internal const val DEVICE_SESSION_BOOTSTRAP_AUTHORIZATION_TTL_MILLIS = 60_000L
private const val DIRECT_INVITE_TTL_MILLIS = 300_000L
private const val MAX_PENDING_DIRECT_INVITES = 32

internal data class DirectInviteBinding(
    val initiatorDeviceId: String,
    val initiatorCertificateSha256: String,
    val connectionAttemptId: String,
    val offerSha256: String,
)

internal data class DirectInviteSessionBinding(val inviteDigest: String, val peer: DirectInviteBinding)

internal data class DirectInviteClaim(
    val authorization: DeviceSessionBootstrapAuthorization,
    val roleId: Long,
    val stunUrl: String,
    val sessionBinding: DirectInviteSessionBinding,
    val turnUrl: String = "",
    val turnUsername: String = "",
    val turnPassword: String = "",
) {
    override fun toString(): String = "DirectInviteClaim(<redacted>)"
}

internal enum class DirectInviteDenial {
    Missing, Expired, AlreadyClaimed, DeviceDenied, RoleRevoked, Capacity,
}

internal class DirectInviteException(val reason: DirectInviteDenial) : IllegalStateException(reason.name)

internal data class DeviceSessionBootstrapAuthorizationGrant(
    val initiatorDeviceId: String,
    val targetDeviceId: String,
    val connectionAttemptId: String,
    val roleId: Long,
    val sharePathScope: DeviceSharePathScope?,
)

internal enum class DeviceSessionBootstrapAuthorizationDenial {
    MissingOrConsumed,
    Expired,
    UnsupportedType,
    InitiatorMismatch,
    TargetMismatch,
    ConnectionAttemptMismatch,
    RoleScopeMismatch,
    DirectInviteMismatch,
}

internal sealed interface DeviceSessionBootstrapAuthorizationConsumeResult {
    data class Granted(
        val grant: DeviceSessionBootstrapAuthorizationGrant,
    ) : DeviceSessionBootstrapAuthorizationConsumeResult

    data class Denied(
        val reason: DeviceSessionBootstrapAuthorizationDenial,
    ) : DeviceSessionBootstrapAuthorizationConsumeResult
}

/**
 * 目标端内存中的短时一次性 WebRTC Session 启动授权。
 *
 * 不透明授权不会写入设备 token 状态；只有验证并原子消费成功后，调用方才创建普通
 * Device Session token。失败的设备、目标、连接尝试或角色检查不会消费合法授权。
 */
internal class DeviceSessionBootstrapAuthorizationRegistry(
    private val ttlMillis: Long = DEVICE_SESSION_BOOTSTRAP_AUTHORIZATION_TTL_MILLIS,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val opaqueAuthorizationFactory: () -> String = ::newCredential,
    private val isDirectInviteRoleActive: suspend (Long) -> Boolean = { false },
    private val isPermanentlyRejected: suspend (String) -> Boolean = { true },
) {
    private data class Entry(
        val grant: DeviceSessionBootstrapAuthorizationGrant,
        val expiresAtMillis: Long,
        val directInvite: DirectInviteSessionBinding? = null,
    )

    private data class PendingInvite(
        val issuerSessionId: String,
        val targetDeviceId: String,
        val roleId: Long,
        val stunUrl: String,
        val turnUrl: String,
        val turnUsername: String,
        val turnPassword: String,
        val expiresAtMillis: Long,
        var claim: DirectInviteClaim? = null,
    )

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private val directInvites = mutableMapOf<String, PendingInvite>()

    init {
        require(ttlMillis > 0L) { "bootstrap authorization TTL must be positive" }
    }

    suspend fun issue(
        initiatorDeviceId: String,
        targetDeviceId: String,
        connectionAttemptId: String,
        roleId: Long,
        sharePathScope: DeviceSharePathScope? = null,
    ): DeviceSessionBootstrapAuthorization {
        require(initiatorDeviceId.isNotBlank()) { "initiator device id must not be blank" }
        require(targetDeviceId.isNotBlank()) { "target device id must not be blank" }
        require(connectionAttemptId.isNotBlank()) { "connection attempt id must not be blank" }
        require(roleId > 0L) { "role id must be positive" }

        return mutex.withLock {
            val now = nowMillis()
            removeExpired(now)
            issueLocked(Entry(
                grant = DeviceSessionBootstrapAuthorizationGrant(
                    initiatorDeviceId = initiatorDeviceId,
                    targetDeviceId = targetDeviceId,
                    connectionAttemptId = connectionAttemptId,
                    roleId = roleId,
                    sharePathScope = sharePathScope,
                ),
                expiresAtMillis = now + ttlMillis,
            ))
        }
    }

    suspend fun issueDirectInvite(
        issuerSessionId: String,
        targetDeviceId: String,
        host: String,
        port: Int,
        fingerprintSha256: String,
        roleId: Long,
        stunUrl: String = "",
        turnUrl: String = "",
        turnUsername: String = "",
        turnPassword: String = "",
    ): WebRtcDirectInvite = mutex.withLock {
        require(issuerSessionId.isNotBlank())
        if (roleId <= 0 || !isDirectInviteRoleActive(roleId)) reject(DirectInviteDenial.RoleRevoked)
        val now = nowMillis()
        removeExpired(now)
        if (directInvites.size >= MAX_PENDING_DIRECT_INVITES) reject(DirectInviteDenial.Capacity)
        val credential = if (host.isEmpty()) WebRtcShareCode.newTemporaryDeviceId(fingerprintSha256) else newCredential()
        val digest = credential.inviteDigest()
        check(digest !in directInvites)
        val invite = WebRtcDirectInvite(
            targetDeviceId, host, port, fingerprintSha256, credential, now + DIRECT_INVITE_TTL_MILLIS, stunUrl,
            turnUrl, turnUsername, turnPassword,
        )
        WebRtcShareCode.validateDirectInvite(invite)
        directInvites[digest] = PendingInvite(
            issuerSessionId, targetDeviceId, roleId, stunUrl, turnUrl, turnUsername, turnPassword, invite.expiresAtEpochMillis,
        )
        invite
    }

    suspend fun claimDirectInvite(
        issuerSessionId: String,
        credential: String,
        binding: DirectInviteBinding,
    ): DirectInviteClaim = mutex.withLock {
        if (credential.length != 43) reject(DirectInviteDenial.Missing)
        val digest = credential.inviteDigest()
        val invite = directInvites[digest]?.takeIf { it.issuerSessionId == issuerSessionId }
            ?: reject(DirectInviteDenial.Missing)
        ensureUnexpired(digest, invite)
        if (isPermanentlyRejected(binding.initiatorDeviceId)) reject(DirectInviteDenial.DeviceDenied)
        if (!isDirectInviteRoleActive(invite.roleId)) {
            revokeDirectInviteLocked(digest)
            reject(DirectInviteDenial.RoleRevoked)
        }
        val now = ensureUnexpired(digest, invite)
        invite.claim?.let { previousClaim ->
            val entry = entries[previousClaim.authorization.opaqueAuthorization]
            if (previousClaim.sessionBinding.peer != binding || entry == null || now >= entry.expiresAtMillis) {
                reject(DirectInviteDenial.AlreadyClaimed)
            }
            return@withLock previousClaim
        }
        val sessionBinding = DirectInviteSessionBinding(digest, binding)
        val authorization = issueLocked(Entry(
            grant = DeviceSessionBootstrapAuthorizationGrant(
                binding.initiatorDeviceId, invite.targetDeviceId, binding.connectionAttemptId, invite.roleId, null,
            ),
            expiresAtMillis = minOf(now + ttlMillis, invite.expiresAtMillis),
            directInvite = sessionBinding,
        ))
        DirectInviteClaim(
            authorization, invite.roleId, invite.stunUrl, sessionBinding,
            invite.turnUrl, invite.turnUsername, invite.turnPassword,
        ).also { invite.claim = it }
    }

    suspend fun consume(
        authorization: DeviceSessionBootstrapAuthorization,
        initiatorDeviceId: String,
        targetDeviceId: String,
        connectionAttemptId: String,
        currentRoleId: Long,
        directInviteBinding: DirectInviteSessionBinding? = null,
    ): DeviceSessionBootstrapAuthorizationConsumeResult = mutex.withLock {
        if (authorization.type != DeviceSessionBootstrapAuthorizationType.WEB_RTC_PREAPPROVED) {
            return@withLock denied(DeviceSessionBootstrapAuthorizationDenial.UnsupportedType)
        }
        val entry = entries[authorization.opaqueAuthorization]
            ?: return@withLock denied(DeviceSessionBootstrapAuthorizationDenial.MissingOrConsumed)
        val now = nowMillis()
        if (now >= entry.expiresAtMillis) {
            entries.remove(authorization.opaqueAuthorization)
            return@withLock denied(DeviceSessionBootstrapAuthorizationDenial.Expired)
        }
        val grant = entry.grant
        if (grant.initiatorDeviceId != initiatorDeviceId) {
            return@withLock denied(DeviceSessionBootstrapAuthorizationDenial.InitiatorMismatch)
        }
        if (grant.targetDeviceId != targetDeviceId) {
            return@withLock denied(DeviceSessionBootstrapAuthorizationDenial.TargetMismatch)
        }
        if (
            authorization.connectionAttemptId != connectionAttemptId ||
            grant.connectionAttemptId != connectionAttemptId
        ) {
            return@withLock denied(DeviceSessionBootstrapAuthorizationDenial.ConnectionAttemptMismatch)
        }
        if (grant.roleId != currentRoleId) {
            return@withLock denied(DeviceSessionBootstrapAuthorizationDenial.RoleScopeMismatch)
        }
        if (entry.directInvite != directInviteBinding) {
            return@withLock denied(DeviceSessionBootstrapAuthorizationDenial.DirectInviteMismatch)
        }
        entry.directInvite?.let { invite ->
            if (isPermanentlyRejected(initiatorDeviceId) || !isDirectInviteRoleActive(grant.roleId)) {
                revokeDirectInviteLocked(invite.inviteDigest)
                return@withLock denied(DeviceSessionBootstrapAuthorizationDenial.RoleScopeMismatch)
            }
            if (nowMillis() >= entry.expiresAtMillis) {
                entries.remove(authorization.opaqueAuthorization)
                return@withLock denied(DeviceSessionBootstrapAuthorizationDenial.Expired)
            }
        }
        entries.remove(authorization.opaqueAuthorization)
        DeviceSessionBootstrapAuthorizationConsumeResult.Granted(grant)
    }

    suspend fun revoke(opaqueAuthorization: String) {
        mutex.withLock { entries.remove(opaqueAuthorization) }
    }

    suspend fun revokeDirectInvite(issuerSessionId: String, credential: String) = mutex.withLock {
        val digest = credential.inviteDigest()
        if (directInvites[digest]?.issuerSessionId == issuerSessionId) revokeDirectInviteLocked(digest)
    }

    suspend fun revokeDirectInvites(issuerSessionId: String) = mutex.withLock {
        directInvites.filterValues { it.issuerSessionId == issuerSessionId }.keys.forEach(::revokeDirectInviteLocked)
    }

    internal suspend fun pendingCount(): Int = mutex.withLock {
        removeExpired(nowMillis())
        entries.size
    }

    private fun removeExpired(now: Long) {
        entries.entries.removeAll { (_, entry) -> now >= entry.expiresAtMillis }
        directInvites.entries.removeAll { (_, invite) -> now >= invite.expiresAtMillis }
    }

    private fun ensureUnexpired(digest: String, invite: PendingInvite): Long = nowMillis().also { now ->
        if (now >= invite.expiresAtMillis) {
            revokeDirectInviteLocked(digest)
            reject(DirectInviteDenial.Expired)
        }
    }

    private fun revokeDirectInviteLocked(digest: String) {
        directInvites.remove(digest)?.claim?.authorization?.let { entries.remove(it.opaqueAuthorization) }
    }

    private fun issueLocked(entry: Entry): DeviceSessionBootstrapAuthorization {
        val opaqueAuthorization = generateUniqueOpaqueAuthorization()
        entries[opaqueAuthorization] = entry
        return DeviceSessionBootstrapAuthorization(
            type = DeviceSessionBootstrapAuthorizationType.WEB_RTC_PREAPPROVED,
            opaqueAuthorization = opaqueAuthorization,
            connectionAttemptId = entry.grant.connectionAttemptId,
        )
    }

    private fun generateUniqueOpaqueAuthorization(): String {
        repeat(8) {
            val candidate = opaqueAuthorizationFactory()
            require(candidate.isNotBlank()) { "opaque authorization must not be blank" }
            if (candidate !in entries) return candidate
        }
        error("could not generate a unique bootstrap authorization")
    }

    private fun denied(
        reason: DeviceSessionBootstrapAuthorizationDenial,
    ): DeviceSessionBootstrapAuthorizationConsumeResult =
        DeviceSessionBootstrapAuthorizationConsumeResult.Denied(reason)

    private fun reject(reason: DirectInviteDenial): Nothing = throw DirectInviteException(reason)
}

private fun newCredential(): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(secureRandomBytes(32))

private fun String.inviteDigest(): String = encodeToByteArray().sha256().hexLower

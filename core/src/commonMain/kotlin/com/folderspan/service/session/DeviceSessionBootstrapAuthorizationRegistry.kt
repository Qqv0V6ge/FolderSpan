package com.folderspan.service.session

import com.folderspan.extensions.randomString
import com.folderspan.service.data.DeviceSessionBootstrapAuthorization
import com.folderspan.service.data.DeviceSessionBootstrapAuthorizationType
import com.folderspan.ui.state.device.DeviceSharePathScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

internal const val DEVICE_SESSION_BOOTSTRAP_AUTHORIZATION_TTL_MILLIS = 60_000L

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
    private val opaqueAuthorizationFactory: () -> String = {
        48.randomString(includeSpecial = false)
    },
) {
    private data class Entry(
        val grant: DeviceSessionBootstrapAuthorizationGrant,
        val expiresAtMillis: Long,
    )

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

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
            val opaqueAuthorization = generateUniqueOpaqueAuthorization()
            entries[opaqueAuthorization] = Entry(
                grant = DeviceSessionBootstrapAuthorizationGrant(
                    initiatorDeviceId = initiatorDeviceId,
                    targetDeviceId = targetDeviceId,
                    connectionAttemptId = connectionAttemptId,
                    roleId = roleId,
                    sharePathScope = sharePathScope,
                ),
                expiresAtMillis = now + ttlMillis,
            )
            DeviceSessionBootstrapAuthorization(
                type = DeviceSessionBootstrapAuthorizationType.WEB_RTC_PREAPPROVED,
                opaqueAuthorization = opaqueAuthorization,
                connectionAttemptId = connectionAttemptId,
            )
        }
    }

    suspend fun consume(
        authorization: DeviceSessionBootstrapAuthorization,
        initiatorDeviceId: String,
        targetDeviceId: String,
        connectionAttemptId: String,
        currentRoleId: Long,
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
        entries.remove(authorization.opaqueAuthorization)
        DeviceSessionBootstrapAuthorizationConsumeResult.Granted(grant)
    }

    suspend fun revoke(opaqueAuthorization: String) {
        mutex.withLock { entries.remove(opaqueAuthorization) }
    }

    internal suspend fun pendingCount(): Int = mutex.withLock {
        removeExpired(nowMillis())
        entries.size
    }

    private fun removeExpired(now: Long) {
        entries.entries.removeAll { (_, entry) -> now >= entry.expiresAtMillis }
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
}

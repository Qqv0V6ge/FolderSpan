@file:OptIn(ExperimentalSerializationApi::class)

package com.folderspan.routes

import strings.AppStrings

import com.folderspan.service.account.ACCOUNT_DEVICE_CHALLENGE_HEADER
import com.folderspan.service.account.ACCOUNT_DEVICE_CONNECT_PURPOSE
import com.folderspan.service.account.ACCOUNT_DEVICE_DISCOVERY_PURPOSE
import com.folderspan.service.account.AccountDeviceLanDecision
import com.folderspan.service.account.AccountDeviceProofContext
import com.folderspan.service.account.AccountDeviceProofVerification
import com.folderspan.service.account.accountDeviceSha256
import com.folderspan.service.account.createAccountDeviceProof
import com.folderspan.service.account.resolveAccountDevicePreferredRoleId
import com.folderspan.service.account.resolveAccountDeviceLanDecision
import com.folderspan.service.account.toHeaders
import com.folderspan.service.account.verifyAccountDeviceProof

import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType.*
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.exception.AuthorityException
import com.folderspan.exception.EmptyDataException
import com.folderspan.extensions.randomString
import com.folderspan.getSocketDevice
import com.folderspan.service.data.*
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.CONNECT_TIMEOUT
import com.folderspan.service.http.client.httpsPortOrFallback
import com.folderspan.service.http.client.normalizedTlsFingerprint
import com.folderspan.service.http.server.resolveDiscoveredHttpDeviceHost
import com.folderspan.service.http.tls.DeviceIdentityProof
import com.folderspan.service.http.tls.DeviceIdentityProofVerification
import com.folderspan.service.http.tls.TrustedDeviceCertificateStore
import com.folderspan.service.http.tls.verifyDeviceIdentityProof
import com.folderspan.ui.state.device.DeviceTokenFingerprint
import com.folderspan.ui.theme.getDefaultColorScheme
import com.folderspan.ui.theme.toSerializable
import com.folderspan.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

internal suspend fun RawHttpApiDispatcher.handlePing(request: RawHttpRequest): RawHttpResponse {
    requireProtobuf(request)?.let { return it }
    if (request.header(DISCOVERY_PING_HEADER) != "true") {
        return protobufFailure(403, AuthorityException(AppStrings.ui_discovery_request_invalid))
    }
    val device = ProtoBuf.decodeFromByteArray(SocketDevice.serializer(), request.body)
    ensureDiscoveryAccepted(request, device)?.let { return it }
    val selfDevice = getSocketDevice()
    if (device.id != selfDevice.id) {
        val discoveredDevice = device.withCopy(
            host = resolveDiscoveredHttpDeviceHost(
                advertisedHost = device.host,
                remoteHost = request.remoteHost,
            ),
            httpsPort = device.httpsPortOrFallback()
        )
        LogKit.d(
            "${AppStrings.ui_device_https_ping_received}: id=${device.id}, name=${device.name}, " +
                "host=${discoveredDevice.host}:${discoveredDevice.httpsPort}"
        )
        deviceState.markHttpDeviceSeen(discoveredDevice.id)
        insertUnknownDeviceIfNeeded(
            device = discoveredDevice,
            connectType = ConnectType.New,
            allowExistingUpdates = false,
        )
    }
    val publicSelfDevice = selfDevice.withCopy(tlsFingerprintSha256 = "")
    val responseBody = ProtoBuf.encodeToByteArray(SocketDevice.serializer(), publicSelfDevice)
    val challenge = request.header(ACCOUNT_DEVICE_CHALLENGE_HEADER).orEmpty()
    val proof = challenge.takeIf(String::isNotBlank)?.let {
        createAccountDeviceProof(
            AccountDeviceProofContext(
                purpose = ACCOUNT_DEVICE_DISCOVERY_PURPOSE,
                signerDeviceKey = selfDevice.id,
                targetDeviceKey = device.id,
                targetPath = "/ping",
                nonce = challenge,
                issuedAtEpochSeconds = Clock.System.now().epochSeconds,
                payloadSha256 = responseBody.accountDeviceSha256(),
            )
        )
    }
    return rawBytes(
        responseBody,
        contentType = PROTOBUF_CONTENT_TYPE,
        headers = commonHeaders() + proof?.toHeaders().orEmpty(),
    )
}

internal suspend fun RawHttpApiDispatcher.handleDevice(request: RawHttpRequest): RawHttpResponse {
    requireProtobuf(request)?.let { return it }
    return when (request.path) {
        "/api/devices/theme" -> {
            requireAuth(request)?.let { return it }
            val body = request.decodeProtobuf<DeviceThemeRequest>()
            val colorScheme = mainState.currentColorSchemes.value?.let { item ->
                if (body.isDark) item.dark else item.light
            } ?: getDefaultColorScheme(body.isDark)
            protobuf(DeviceThemeResponse(colorScheme.toSerializable()))
        }

        "/api/devices/heartbeat" -> {
            requireAuth(request)?.let { return it }
            request.decodeProtobuf<DeviceHeartbeatRequest>()
            protobuf(DeviceHeartbeatResponse(serverTimeMillis = Clock.System.now().toEpochMilliseconds()))
        }

        "/api/devices/connect" -> handleDeviceConnect(request)
        else -> protobufFailure(404, EmptyDataException())
    }
}

internal suspend fun RawHttpApiDispatcher.handleDeviceConnect(request: RawHttpRequest): RawHttpResponse {
    val body = request.decodeProtobuf<DeviceConnectRequest>()
    ensureDiscoveryAccepted(request, body.device)?.let { return it }
    return protobuf(
        evaluateDeviceConnect(
            body = body,
            fingerprint = request.buildDeviceFingerprint(body.device.id),
            allowShareSessionAuthorization = false,
        )
    )
}

internal suspend fun RawHttpApiDispatcher.evaluateDeviceConnect(
    body: DeviceConnectRequest,
    fingerprint: DeviceTokenFingerprint,
    allowShareSessionAuthorization: Boolean,
): DeviceConnectResponse {
    val device = body.device
    val requestFingerprint = fingerprint
    val autoAuthorizeDeviceConnect = settings.getBoolean(
        SettingsUtils.KEY_FILE_SHARE_AUTO_AUTHORIZE_DEVICE_CONNECT,
        false
    )
    LogKit.i(
        "${AppStrings.ui_https_device_connection_request_received}: " +
            "id=${device.id}, name=${device.name}, host=${device.host}:${device.httpsPort}"
    )

    val queriedDevice = database.deviceConnectQueries
        .queryByIdAndCategory(device.id, DeviceCategory.SERVER)
        .executeAsOneOrNullAwait()
    val token = 32.randomString()

    if (queriedDevice?.connectionType == PERMANENTLY_BANNED) {
        return DeviceConnectResponse(REJECTED, "", DeviceConnectAuthorizationMode.STANDARD)
    }

    val accountFeatureEnabled = settings.getBoolean(
        SettingsUtils.KEY_FILE_SHARE_ACCOUNT_DEVICE_AUTO_CONNECT_ENABLED,
        false,
    )
    val trustSnapshot = accountDeviceTrustRegistry.state.value
    val nowEpochMillis = Clock.System.now().toEpochMilliseconds()
    val accountProof = body.accountDeviceProof
    if (body.authorizationMode == DeviceConnectAuthorizationMode.UNSPECIFIED) {
        LogKit.w(AppStrings.ui_device_connection_rejected_authorization_mode_missing)
        return DeviceConnectResponse(REJECTED, "", DeviceConnectAuthorizationMode.STANDARD)
    }
    val accountAuthorizationRequested =
        body.authorizationMode == DeviceConnectAuthorizationMode.ACCOUNT_DEVICE
    if (accountAuthorizationRequested != (accountProof != null)) {
        LogKit.w(AppStrings.ui_device_connection_rejected_authorization_mode_mismatch)
        return DeviceConnectResponse(REJECTED, "", DeviceConnectAuthorizationMode.STANDARD)
    }
    if (body.shareNonce.isNotBlank()) {
        if (!allowShareSessionAuthorization) {
            return DeviceConnectResponse(REJECTED, "", DeviceConnectAuthorizationMode.STANDARD)
        }
        val roleId = resolveAutoAuthorizeRoleId(
            settings.getLong(SettingsUtils.KEY_FILE_SHARE_AUTO_AUTHORIZE_ROLE_ID, 2L)
        ) ?: return DeviceConnectResponse(REJECTED, "", DeviceConnectAuthorizationMode.STANDARD)
        val grant = deviceState.consumeAllowedDeviceShareConnection(device, body.shareNonce)
            ?: return DeviceConnectResponse(REJECTED, "", DeviceConnectAuthorizationMode.STANDARD)
        issueDeviceToken(device, token, roleId, requestFingerprint, body.identityProof)
        deviceCertificateState.setDeviceSharePathScope(token, grant.pathScope)
        return DeviceConnectResponse(APPROVED, token, DeviceConnectAuthorizationMode.STANDARD)
    }
    val proofVerification = if (!accountAuthorizationRequested || !accountFeatureEnabled) {
        AccountDeviceProofVerification.Missing
    } else {
        val verifiedAccountProof = accountProof
            ?: return DeviceConnectResponse(REJECTED, "", DeviceConnectAuthorizationMode.STANDARD)
        val unsignedBody = body.copy(accountDeviceProof = null, identityProof = null)
        verifyAccountDeviceProof(
            proof = verifiedAccountProof,
            snapshot = trustSnapshot,
            context = AccountDeviceProofContext(
                purpose = ACCOUNT_DEVICE_CONNECT_PURPOSE,
                signerDeviceKey = device.id,
                targetDeviceKey = getSocketDevice().id,
                targetPath = "/api/devices/connect",
                nonce = verifiedAccountProof.nonce,
                issuedAtEpochSeconds = verifiedAccountProof.issuedAtEpochSeconds,
                payloadSha256 = ProtoBufCodec.encode(unsignedBody).accountDeviceSha256(),
            ),
            nowEpochMillis = nowEpochMillis,
            replayCache = accountDeviceNonceReplayCache,
        )
    }
    val unsignedConnectBody = body.copy(accountDeviceProof = null, identityProof = null)
    val identityPayloadSha256 = ProtoBufCodec.encode(unsignedConnectBody).accountDeviceSha256()
    val accountDecision = if (accountAuthorizationRequested) {
        resolveAccountDeviceLanDecision(
            featureEnabled = accountFeatureEnabled,
            snapshot = trustSnapshot,
            nowEpochMillis = nowEpochMillis,
            deviceKey = device.id,
            proofVerification = proofVerification,
            permanentlyRejected = false,
            connectedOrConnecting = false,
        )
    } else {
        AccountDeviceLanDecision.DeferToExistingFlow
    }

    if (accountAuthorizationRequested && accountDecision != AccountDeviceLanDecision.AutoConnect) {
        LogKit.w(
            "${AppStrings.ui_account_device_connection_rejected_identity_proof_failed}, " +
                "category=${proofVerification.name}"
        )
        return DeviceConnectResponse(REJECTED, "", DeviceConnectAuthorizationMode.STANDARD)
    }

    if (
        accountDecision == AccountDeviceLanDecision.AutoConnect &&
        queriedDevice?.connectionType != AUTO_CONNECT
    ) {
        val roleId = resolveAutoAuthorizeRoleId(
            resolveAccountDevicePreferredRoleId(queriedDevice?.roleId)
        )
        if (roleId == null) {
            LogKit.w(AppStrings.ui_account_device_connection_rejected_no_minimum_role)
            return DeviceConnectResponse(REJECTED, "", DeviceConnectAuthorizationMode.STANDARD)
        }
        insertUnknownDeviceIfNeeded(device, ConnectType.UnConnect)
        issueDeviceToken(device, token, roleId, requestFingerprint, body.identityProof)
        deviceState.markAccountDeviceAutoAuthorized(device.id)
        if (queriedDevice != null) {
            database.deviceConnectQueries.updateLastConnectionByCategoryAndId(
                device.id,
                DeviceCategory.SERVER,
            ).awaitDatabaseReady()
        }
        return DeviceConnectResponse(
                connectType = APPROVED,
                token = token,
                authorizationMode = DeviceConnectAuthorizationMode.ACCOUNT_DEVICE,
            )
    }

    if (queriedDevice != null) {
        val response = when (queriedDevice.connectionType) {
            PERMANENTLY_BANNED -> DeviceConnectResponse(REJECTED, "", DeviceConnectAuthorizationMode.STANDARD)
            AUTO_CONNECT -> approveAutoConnectedDevice(
                device = device,
                token = token,
                roleId = queriedDevice.roleId,
                fingerprint = requestFingerprint,
                identityProof = body.identityProof,
                payloadSha256 = identityPayloadSha256,
            )

            APPROVED, REJECTED -> waitForDeviceApproval(
                device = device,
                token = token,
                roleId = queriedDevice.roleId,
                fingerprint = requestFingerprint,
                identityProof = body.identityProof,
            )
            else -> DeviceConnectResponse(REJECTED, "", DeviceConnectAuthorizationMode.STANDARD)
        }
        database.deviceConnectQueries.updateLastConnectionByCategoryAndId(
            device.id,
            DeviceCategory.SERVER
        ).awaitDatabaseReady()
        return response
    }

    if (autoAuthorizeDeviceConnect) {
        LogKit.w(
            "${AppStrings.ui_device_auto_connection_saved_tls_fingerprint_missing_manual_review}: id=${device.id}"
        )
    }

    insertUnknownDeviceIfNeeded(device, ConnectType.New)
    return waitForFirstDeviceApproval(device, token, requestFingerprint, body.identityProof)
}

internal suspend fun RawHttpApiDispatcher.approveAutoConnectedDevice(
    device: SocketDevice,
    token: String,
    roleId: Long,
    fingerprint: DeviceTokenFingerprint,
    identityProof: DeviceIdentityProof?,
    payloadSha256: String,
): DeviceConnectResponse {
    val trustedFingerprint = TrustedDeviceCertificateStore.expectedFingerprint(device.id)
    val trustedPublicKeyPem = TrustedDeviceCertificateStore.expectedPublicKeyPem(device.id)
    if (trustedFingerprint.isBlank() || trustedPublicKeyPem.isBlank()) {
        LogKit.w(
            "${AppStrings.ui_device_auto_connection_saved_tls_fingerprint_missing_manual_review}: id=${device.id}"
        )
        return waitForDeviceApproval(device, token, roleId, fingerprint, identityProof)
    }
    val verification = verifyDeviceIdentityProof(
        proof = identityProof,
        expectedDeviceId = device.id,
        targetDeviceId = getSocketDevice().id,
        targetPath = "/api/devices/connect",
        payloadSha256 = payloadSha256,
        nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
        replayCache = accountDeviceNonceReplayCache,
        trustedFingerprint = trustedFingerprint,
        trustedPublicKeyPem = trustedPublicKeyPem,
    )
    return when (verification) {
        DeviceIdentityProofVerification.Valid -> {
            issueDeviceToken(device, token, roleId, fingerprint, identityProof)
            DeviceConnectResponse(APPROVED, token, DeviceConnectAuthorizationMode.STANDARD)
        }

        DeviceIdentityProofVerification.Missing,
        DeviceIdentityProofVerification.NotTrusted -> {
            LogKit.w(
                "${AppStrings.ui_device_auto_connection_tls_fingerprint_missing_manual_review}: id=${device.id}"
            )
            waitForDeviceApproval(device, token, roleId, fingerprint, identityProof)
        }

        else -> {
            LogKit.w("${AppStrings.ui_device_auto_connection_tls_fingerprint_mismatch}: id=${device.id}")
            DeviceConnectResponse(REJECTED, "", DeviceConnectAuthorizationMode.STANDARD)
        }
    }
}

internal suspend fun RawHttpApiDispatcher.issueDeviceToken(
    device: SocketDevice,
    token: String,
    roleId: Long,
    fingerprint: DeviceTokenFingerprint,
    identityProof: DeviceIdentityProof? = null,
) {
    deviceState.registerRemoteDeviceConnection(device.id, token, roleId, fingerprint)
    trustDeviceTlsFingerprint(device, identityProof)
}

internal fun trustDeviceTlsFingerprint(
    device: SocketDevice,
    identityProof: DeviceIdentityProof? = null,
) {
    val tlsFingerprint = identityProof?.fingerprintSha256
        ?.takeIf(String::isNotBlank)
        ?: device.normalizedTlsFingerprint()
    val publicKeyPem = identityProof?.publicKeyPem?.trim().orEmpty().ifBlank {
        TrustedDeviceCertificateStore.expectedPublicKeyPem(device.id)
    }
    if (tlsFingerprint.isBlank() || publicKeyPem.isBlank()) return
    TrustedDeviceCertificateStore.save(device.id, tlsFingerprint, publicKeyPem)
}

internal suspend fun RawHttpApiDispatcher.waitForDeviceApproval(
    device: SocketDevice,
    token: String,
    roleId: Long,
    fingerprint: DeviceTokenFingerprint,
    identityProof: DeviceIdentityProof? = null,
): DeviceConnectResponse {
    deviceState.updateConnectionRequest(
        deviceId = device.id,
        connectionType = WAITING,
        requestedAt = Clock.System.now().toEpochMilliseconds(),
        deviceName = device.name
    )
    withContext(Dispatchers.Main) {
        if (deviceState.socketDevices.none { item -> item.id == device.id }) {
            deviceState.socketDevices.add(device.withCopy(connectType = ConnectType.UnConnect))
        }
    }
    return runCatching {
        withTimeout((CONNECT_TIMEOUT * 1000).milliseconds) {
            while (deviceState.connectionRequest[device.id]!!.first == WAITING) {
                delay(1000.milliseconds)
            }
            when (deviceState.connectionRequest[device.id]!!.first) {
                AUTO_CONNECT, APPROVED -> {
                    issueDeviceToken(device, token, roleId, fingerprint, identityProof)
                    DeviceConnectResponse(APPROVED, token, DeviceConnectAuthorizationMode.STANDARD)
                }

                else -> throw Exception("Connection rejected")
            }
        }
    }.getOrElse {
        deviceState.removeConnectionRequest(device.id)
        DeviceConnectResponse(REJECTED, "", DeviceConnectAuthorizationMode.STANDARD)
    }
}

internal suspend fun RawHttpApiDispatcher.waitForFirstDeviceApproval(
    device: SocketDevice,
    token: String,
    fingerprint: DeviceTokenFingerprint,
    identityProof: DeviceIdentityProof? = null,
): DeviceConnectResponse {
    deviceState.updateConnectionRequest(
        deviceId = device.id,
        connectionType = WAITING,
        requestedAt = Clock.System.now().toEpochMilliseconds(),
        deviceName = device.name
    )
    return runCatching {
        withTimeout((CONNECT_TIMEOUT * 1000).milliseconds) {
            while (deviceState.connectionRequest[device.id]!!.first == WAITING) {
                delay(300.milliseconds)
            }
            val roleId = when (deviceState.connectionRequest[device.id]!!.first) {
                AUTO_CONNECT, APPROVED -> {
                    database.deviceConnectQueries.queryRoleIdByIdAndCategory(
                        device.id,
                        DeviceCategory.SERVER,
                    ).executeAsOneOrNullAwait() ?: -1L
                }

                else -> throw Exception("Connection rejected")
            }
            issueDeviceToken(device, token, roleId, fingerprint, identityProof)
            DeviceConnectResponse(APPROVED, token, DeviceConnectAuthorizationMode.STANDARD)
        }
    }.getOrElse {
        deviceState.removeConnectionRequest(device.id)
        DeviceConnectResponse(REJECTED, "", DeviceConnectAuthorizationMode.STANDARD)
    }
}

internal suspend fun RawHttpApiDispatcher.insertUnknownDeviceIfNeeded(
    device: SocketDevice,
    connectType: ConnectType,
    allowExistingUpdates: Boolean = true,
) {
    withContext(Dispatchers.Main) {
        val index = deviceState.socketDevices.indexOfFirst { item -> item.id == device.id }
        if (index == -1) {
            deviceState.socketDevices.add(device.withCopy(connectType = connectType))
        } else if (allowExistingUpdates) {
            val existing = deviceState.socketDevices[index]
            if (!existing.hasActiveConnection()) {
                deviceState.socketDevices[index] = device.withCopy(connectType = existing.connectType)
            }
        }
    }
    if (persistDiscoveredDevice(database, device, allowExistingUpdates)) {
        SyncSnapshotChangeNotifier.onDeviceConfigurationChanged()
    }
}

internal suspend fun persistDiscoveredDevice(
    database: FolderSpanDatabase,
    device: SocketDevice,
    allowExistingUpdates: Boolean,
): Boolean {
    if (discoveryDeviceFieldError(device) != null) return false
    val deviceData = database.deviceQueries.queryById(device.id).executeAsOneOrNullAwait()
    if (deviceData == null) {
        val storedCount = database.deviceQueries.queryAll().executeAsListAwait().size
        if (storedCount >= MAX_DISCOVERED_DEVICES) return false
        database.deviceQueries.insert(
            id = device.id,
            name = device.name,
            host = device.host,
            port = device.httpsPort.toLong(),
            type = device.type
        ).awaitDatabaseReady()
        return false
    }
    if (!allowExistingUpdates) return false

    var configurationChanged = false
    if (
        deviceData.host != device.host ||
        deviceData.port != device.httpsPort.toLong() ||
        deviceData.type != device.type
    ) {
        database.deviceQueries.updateEndpointById(
            host = device.host,
            port = device.httpsPort.toLong(),
            type = device.type,
            id = device.id,
        ).awaitDatabaseReady()
        configurationChanged = true
    }
    if (deviceData.name != device.name && deviceData.hasRemarks == false) {
        database.deviceQueries.updateNameAndTypeById(device.name, device.type, device.id)
            .awaitDatabaseReady()
        configurationChanged = true
    }
    return configurationChanged
}

internal suspend fun RawHttpApiDispatcher.resolveAutoAuthorizeRoleId(preferredRoleId: Long): Long? {
    if (database.deviceRoleQueries.selectById(preferredRoleId).executeAsOneOrNullAwait() != null) {
        return preferredRoleId
    }
    if (preferredRoleId != 2L && database.deviceRoleQueries.selectById(2L).executeAsOneOrNullAwait() != null) {
        return 2L
    }
    return database.deviceRoleQueries.selectAll().executeAsListAwait().firstOrNull()?.id
}

internal suspend fun RawHttpApiDispatcher.ensureDiscoveryAccepted(
    request: RawHttpRequest,
    device: SocketDevice,
): RawHttpResponse? {
    discoveryDeviceFieldError(device)?.let { message ->
        return protobufFailure(400, IllegalArgumentException(message))
    }
    val existing = database.deviceQueries.queryById(device.id).executeAsOneOrNullAwait()
    if (existing != null) return null
    val storedCount = database.deviceQueries.queryAll().executeAsListAwait().size
    if (storedCount >= MAX_DISCOVERED_DEVICES) {
        return protobufFailure(429, IllegalStateException(AppStrings.ui_discovery_request_invalid))
    }
    val pendingUnknown = deviceState.socketDevices.count { item -> item.connectType == ConnectType.New }
    if (pendingUnknown >= MAX_PENDING_UNKNOWN_DEVICES) {
        return protobufFailure(429, IllegalStateException(AppStrings.ui_discovery_request_invalid))
    }
    if (!DiscoveryPendingQuota.tryReserve(request.remoteHost.orEmpty(), device.id)) {
        return protobufFailure(429, IllegalStateException(AppStrings.ui_discovery_request_invalid))
    }
    return null
}

internal fun discoveryDeviceFieldError(device: SocketDevice): String? {
    if (device.id.isBlank() || device.id.length > MAX_DISCOVERY_DEVICE_ID_LENGTH) {
        return AppStrings.ui_invalid_discovery_device_id
    }
    if (device.name.length > MAX_DISCOVERY_DEVICE_NAME_LENGTH) {
        return AppStrings.ui_invalid_discovery_device_id
    }
    if (device.host.length > MAX_DISCOVERY_DEVICE_HOST_LENGTH) {
        return AppStrings.ui_invalid_discovery_device_id
    }
    if (device.pathSeparator.length > MAX_DISCOVERY_PATH_SEPARATOR_LENGTH) {
        return AppStrings.ui_invalid_discovery_device_id
    }
    return null
}

internal object DiscoveryPendingQuota {
    private val mutex = Mutex()
    private val pendingIdsBySource = mutableMapOf<String, MutableSet<String>>()

    suspend fun tryReserve(source: String, deviceId: String): Boolean = mutex.withLock {
        val key = source.ifBlank { "unknown" }
        if (pendingIdsBySource.values.any { ids -> deviceId in ids }) return@withLock true
        val total = pendingIdsBySource.values.sumOf { ids -> ids.size }
        if (total >= MAX_PENDING_UNKNOWN_DEVICES) return@withLock false
        val current = pendingIdsBySource.getOrPut(key) { mutableSetOf() }
        if (current.size >= MAX_PENDING_UNKNOWN_DEVICES_PER_SOURCE) return@withLock false
        current += deviceId
        true
    }

    suspend fun clearForTests() = mutex.withLock {
        pendingIdsBySource.clear()
    }
}

internal const val MAX_DISCOVERY_DEVICE_ID_LENGTH = 256
internal const val MAX_DISCOVERY_DEVICE_NAME_LENGTH = 256
internal const val MAX_DISCOVERY_DEVICE_HOST_LENGTH = 253
internal const val MAX_DISCOVERY_PATH_SEPARATOR_LENGTH = 8
internal const val MAX_DISCOVERED_DEVICES = 256
internal const val MAX_PENDING_UNKNOWN_DEVICES = 64
internal const val MAX_PENDING_UNKNOWN_DEVICES_PER_SOURCE = 8

package com.folderspan.ui.state.file

import androidx.compose.runtime.*
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.extensions.randomString
import com.folderspan.notification.NotificationFactoryConfig
import com.folderspan.notification.RequestNotificationDispatcher
import com.folderspan.notification.RequestNotificationFactory
import com.folderspan.notification.RequestNotificationKind
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.NetworkState
import com.folderspan.ui.state.main.NotificationState
import com.folderspan.utils.DeviceRequestLogUtils
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.SettingsUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock

class FileShareState : KoinComponent {
    private val notificationState by inject<NotificationState>()
    private val deviceState by inject<DeviceState>()
    private val networkState by inject<NetworkState>()
    private val notificationConfig = NotificationFactoryConfig(
        showInBell = true,
        showInBanner = true,
        sendSystemNotification = true
    )
    val files = mutableStateListOf<FileSimpleInfo>()

    val checkedFiles = mutableStateListOf<FileSimpleInfo>()

    var incomingFiles by mutableStateOf<List<FileSimpleInfo>>(emptyList())
        private set

    fun updateIncomingFiles(files: List<FileSimpleInfo>) {
        incomingFiles = filterShareableFiles(files)
    }

    fun clearIncomingFiles() {
        incomingFiles = emptyList()
    }

    val sendFile = mutableStateMapOf<String, FileShareStatus>()
    val sendFileMessage = mutableStateMapOf<String, String>()

    private val _connectPassword: MutableStateFlow<String> = MutableStateFlow("")
    val connectPassword: StateFlow<String> = _connectPassword

    fun updateConnectPassword(
        value: String,
        clearRuntimeAuthorizations: Boolean = value.isNotEmpty(),
    ) {
        if (_connectPassword.value != value && clearRuntimeAuthorizations) {
            clearLinkShareRuntimeAuthorizations()
        }
        _connectPassword.value = value
    }

    // 已授权通过链接访问文件的设备
    // Map<设备, 授权访问快照>
    val authorizedLinkShareDevices = mutableStateMapOf<Device, LinkShareDeviceAccess>()
    private val authorizedLinkShareDeviceFingerprints = mutableMapOf<String, ShareTokenFingerprint>()
    val linkShareSessions = mutableStateMapOf<String, LinkShareSession>()
    val linkShareTickets = mutableStateMapOf<String, LinkShareTicket>()

    fun authorizeLinkShareDevice(
        device: Device,
        allowHidden: Boolean,
        allowUpload: Boolean = _allowUpload.value,
        files: List<FileSimpleInfo>,
        revokeExistingSessions: Boolean = true,
        clearUploadRequestState: Boolean = true,
        fingerprint: ShareTokenFingerprint? = null,
    ) {
        authorizeLinkShareDevice(
            device = device,
            access = LinkShareDeviceAccess(
                allowHidden = allowHidden,
                allowUpload = allowUpload,
                files = files,
            ),
            revokeExistingSessions = revokeExistingSessions,
            clearUploadRequestState = clearUploadRequestState,
            fingerprint = fingerprint,
        )
    }

    fun authorizeLinkShareDevice(
        device: Device,
        access: LinkShareDeviceAccess,
        revokeExistingSessions: Boolean = true,
        clearUploadRequestState: Boolean = true,
        fingerprint: ShareTokenFingerprint? = null,
    ) {
        val resolvedFingerprint = fingerprint ?: authorizedLinkShareDeviceFingerprints[device.id]
        removeAuthorizedLinkShareDevice(
            deviceId = device.id,
            revokeExistingSessions = revokeExistingSessions,
            clearUploadRequestState = clearUploadRequestState,
        )
        if (authorizedLinkShareDevices.size >= MAX_AUTHORIZED_LINK_SHARE_DEVICES) {
            trimOldestAuthorizedLinkShareDevices(
                authorizedLinkShareDevices.size - MAX_AUTHORIZED_LINK_SHARE_DEVICES + 1,
            )
        }
        authorizedLinkShareDevices[device] = access.withFiles(filterShareableFiles(access.files))
        if (resolvedFingerprint != null) {
            authorizedLinkShareDeviceFingerprints[device.id] = resolvedFingerprint
        }
        if (clearUploadRequestState) {
            clearLinkShareUploadRequestState(device.id)
        }
    }

    fun getAuthorizedLinkShareDevice(deviceId: String): LinkShareDeviceAccess? {
        return authorizedLinkShareDevices.entries
            .firstOrNull { item -> item.key.id == deviceId }
            ?.value
    }

    fun resolveAuthorizedLinkShareDevice(
        deviceId: String,
        fingerprint: ShareTokenFingerprint,
    ): LinkShareDeviceAccess? {
        val approvedFingerprint = authorizedLinkShareDeviceFingerprints[deviceId] ?: return null
        if (!approvedFingerprint.matches(fingerprint)) return null
        return getAuthorizedLinkShareDevice(deviceId)
    }

    fun issueLinkShareSession(
        clientId: String,
        fingerprint: ShareTokenFingerprint,
        allowHidden: Boolean,
        allowUpload: Boolean = _allowUpload.value,
        files: List<FileSimpleInfo>,
        nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): LinkShareSession {
        pruneExpiredLinkShareAccess(nowMillis)
        trimOldestLinkShareSessions()
        val session = LinkShareSession(
            token = 32.randomString(includeSpecial = false),
            clientId = clientId,
            fingerprint = fingerprint,
            access = LinkShareDeviceAccess(
                allowHidden = allowHidden,
                allowUpload = allowUpload,
                files = filterShareableFiles(files),
            ),
            expiresAtMillis = nowMillis + LINK_SHARE_SESSION_TTL_MILLIS
        )
        linkShareSessions[session.token] = session
        return session
    }

    fun resolveLinkShareSession(
        token: String?,
        clientId: String?,
        fingerprint: ShareTokenFingerprint,
        nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): LinkShareSession? {
        if (token.isNullOrBlank()) return null
        val session = linkShareSessions[token] ?: return null
        if (!session.matches(clientId, fingerprint, nowMillis)) {
            linkShareSessions.remove(token)
            return null
        }
        return session
    }

    fun issueLinkShareTicket(
        allowHidden: Boolean,
        allowUpload: Boolean = _allowUpload.value,
        files: List<FileSimpleInfo>,
        nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): LinkShareTicket {
        pruneExpiredLinkShareAccess(nowMillis)
        trimOldestLinkShareTickets()
        val ticket = LinkShareTicket(
            token = 32.randomString(includeSpecial = false),
            access = LinkShareDeviceAccess(
                allowHidden = allowHidden,
                allowUpload = allowUpload,
                files = filterShareableFiles(files),
            ),
            expiresAtMillis = nowMillis + LINK_SHARE_TICKET_TTL_MILLIS
        )
        linkShareTickets[ticket.token] = ticket
        return ticket
    }

    fun consumeLinkShareTicket(
        token: String?,
        nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): LinkShareTicket? {
        if (token.isNullOrBlank()) return null
        val ticket = linkShareTickets.remove(token) ?: return null
        return ticket.takeIf { item -> nowMillis <= item.expiresAtMillis }
    }

    fun isRejectedLinkShareDevice(device: Device): Boolean {
        return rejectedLinkShareDevices.any { item -> item.id == device.id }
    }

    fun removeAuthorizedLinkShareDevice(
        deviceId: String,
        revokeExistingSessions: Boolean = true,
        clearUploadRequestState: Boolean = true,
    ): Boolean {
        val devices = authorizedLinkShareDevices.keys.filter { item -> item.id == deviceId }
        devices.forEach { item -> authorizedLinkShareDevices.remove(item) }
        authorizedLinkShareDeviceFingerprints.remove(deviceId)
        if (clearUploadRequestState) {
            clearLinkShareUploadRequestState(deviceId)
        }
        if (revokeExistingSessions) {
            removeLinkShareSessionsForClient(deviceId)
        }
        return devices.isNotEmpty()
    }

    fun rejectAuthorizedLinkShareDevice(device: Device): Boolean {
        val removedAuthorized = removeAuthorizedLinkShareDevice(device.id)
        addRejectedLinkShareDevice(device)
        return removedAuthorized
    }

    fun addRejectedLinkShareDevice(device: Device) {
        if (rejectedLinkShareDevices.any { item -> item.id == device.id }) return
        if (rejectedLinkShareDevices.size >= MAX_REJECTED_LINK_SHARE_DEVICES) {
            rejectedLinkShareDevices.removeAt(0)
        }
        rejectedLinkShareDevices.add(device)
    }

    fun removeRejectedLinkShareDevice(deviceId: String) {
        rejectedLinkShareDevices.removeAll { item -> item.id == deviceId }
    }

    // 请求单独允许网页上传的链接访问设备
    val pendingLinkShareUploadDevices = mutableStateListOf<Device>()

    // 被拒绝单独网页上传的链接访问设备
    val rejectedLinkShareUploadDevices = mutableStateListOf<Device>()

    fun requestLinkShareUploadPermission(device: Device): LinkShareUploadPermissionRequestResult {
        val access = getAuthorizedLinkShareDevice(device.id)
            ?: return LinkShareUploadPermissionRequestResult.NotAuthorized
        if (access.allowUpload) {
            return LinkShareUploadPermissionRequestResult.AlreadyAllowed
        }
        if (rejectedLinkShareUploadDevices.any { item -> item.id == device.id }) {
            return LinkShareUploadPermissionRequestResult.Rejected
        }
        if (pendingLinkShareUploadDevices.any { item -> item.id == device.id }) {
            return LinkShareUploadPermissionRequestResult.AlreadyPending
        }
        if (pendingLinkShareUploadDevices.size >= MAX_PENDING_LINK_SHARE_UPLOAD_DEVICES) {
            return LinkShareUploadPermissionRequestResult.Rejected
        }
        pendingLinkShareUploadDevices.add(device)
        val bundle = RequestNotificationFactory.buildLinkShareUploadNotification(
            deviceId = device.id,
            deviceName = device.name,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            config = notificationConfig
        )
        RequestNotificationDispatcher.post(notificationState, bundle)
        pruneLinkShareNotifications()
        return LinkShareUploadPermissionRequestResult.Accepted
    }

    fun approveLinkShareUploadDevice(deviceId: String): Boolean {
        val changed = updateAuthorizedLinkShareUpload(deviceId, allowUpload = true)
        pendingLinkShareUploadDevices.removeAll { item -> item.id == deviceId }
        rejectedLinkShareUploadDevices.removeAll { item -> item.id == deviceId }
        clearLinkShareUploadRequestNotification(deviceId)
        return changed
    }

    fun rejectLinkShareUploadDevice(deviceId: String): Boolean {
        val device = pendingLinkShareUploadDevices.firstOrNull { item -> item.id == deviceId }
            ?: authorizedLinkShareDevices.keys.firstOrNull { item -> item.id == deviceId }
            ?: return false
        updateAuthorizedLinkShareUpload(deviceId, allowUpload = false)
        pendingLinkShareUploadDevices.removeAll { item -> item.id == deviceId }
        if (rejectedLinkShareUploadDevices.none { item -> item.id == deviceId }) {
            if (rejectedLinkShareUploadDevices.size >= MAX_REJECTED_LINK_SHARE_DEVICES) {
                rejectedLinkShareUploadDevices.removeAt(0)
            }
            rejectedLinkShareUploadDevices.add(device)
        }
        clearLinkShareUploadRequestNotification(deviceId)
        return true
    }

    fun removeRejectedLinkShareUploadDevice(deviceId: String) {
        rejectedLinkShareUploadDevices.removeAll { item -> item.id == deviceId }
        clearLinkShareUploadRequestNotification(deviceId)
    }

    fun updateAuthorizedLinkShareUpload(deviceId: String, allowUpload: Boolean): Boolean {
        val entry = authorizedLinkShareDevices.entries.firstOrNull { item -> item.key.id == deviceId }
            ?: return false
        val currentAccess = entry.value
        if (currentAccess.allowUpload == allowUpload) return false
        authorizedLinkShareDevices[entry.key] = currentAccess.copy(allowUpload = allowUpload)
        updateLinkShareSessionsUpload(deviceId, allowUpload)
        return true
    }

    private fun clearLinkShareUploadRequestState(deviceId: String) {
        pendingLinkShareUploadDevices.removeAll { item -> item.id == deviceId }
        rejectedLinkShareUploadDevices.removeAll { item -> item.id == deviceId }
        clearLinkShareUploadRequestNotification(deviceId)
    }

    fun filterShareableFiles(files: Iterable<FileSimpleInfo>): List<FileSimpleInfo> {
        return filterLinkShareableFiles(files, ::resolveLinkShareDisk)
    }

    fun isLinkShareFileAllowed(file: FileSimpleInfo): Boolean {
        return isLinkShareDiskAllowed(resolveLinkShareDisk(file))
    }

    fun resolveLinkShareDisk(file: FileSimpleInfo): DiskBase? {
        return when (file.protocol) {
            FileProtocol.Local -> Local()
            FileProtocol.Device -> deviceState.resolveConnectedDevice(file.protocolId)
            FileProtocol.Share -> {
                if (file.protocolId == SYSTEM_SHARE_DESK_ID) {
                    Local()
                } else {
                    deviceState.shares.firstOrNull { item -> item.id == file.protocolId }
                }
            }

            FileProtocol.Network -> networkState.networks
                .firstOrNull { item -> item.protocolId == file.protocolId }
        }
    }

    // 等待授权通过链接访问文件的设备
    val pendingLinkShareDevices = mutableStateListOf<Device>()

    // 被拒绝通过链接访问文件的设备
    val rejectedLinkShareDevices = mutableStateListOf<Device>()

    fun addPendingLinkShareDevice(
        device: Device,
        sourceHost: String? = null,
        fingerprint: ShareTokenFingerprint? = null,
        config: NotificationFactoryConfig = notificationConfig
    ) {
        if (pendingLinkShareDevices.any { item -> item.id == device.id }) return
        val normalizedSource = sourceHost?.trim().orEmpty()
        if (normalizedSource.isNotEmpty()) {
            val fromSource = pendingLinkShareDevices.count { item ->
                pendingLinkShareDeviceSources[item.id] == normalizedSource
            }
            if (fromSource >= MAX_PENDING_LINK_SHARE_DEVICES_PER_SOURCE) return
        }
        if (pendingLinkShareDevices.size >= MAX_PENDING_LINK_SHARE_DEVICES) {
            trimOldestPendingLinkShareDevices(
                pendingLinkShareDevices.size - MAX_PENDING_LINK_SHARE_DEVICES + 1,
            )
        }
        pendingLinkShareDevices.add(device)
        if (normalizedSource.isNotEmpty()) {
            pendingLinkShareDeviceSources[device.id] = normalizedSource
        }
        if (fingerprint != null) {
            pendingLinkShareDeviceFingerprints[device.id] = fingerprint
        }
        val bundle = RequestNotificationFactory.buildLinkShareNotification(
            deviceId = device.id,
            deviceName = device.name,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            config = config
        )
        RequestNotificationDispatcher.post(notificationState, bundle)
        pruneLinkShareNotifications()
    }

    fun approveLinkShareDevice(deviceId: String) {
        val device = pendingLinkShareDevices.firstOrNull { item -> item.id == deviceId } ?: return
        val defaults = resolveLinkShareDefaults()
        val fingerprint = pendingLinkShareDeviceFingerprints[deviceId]
        pendingLinkShareDevices.removeAll { item -> item.id == deviceId }
        pendingLinkShareDeviceSources.remove(deviceId)
        pendingLinkShareDeviceFingerprints.remove(deviceId)
        authorizeLinkShareDevice(
            device = device,
            allowHidden = defaults.allowHidden,
            allowUpload = defaults.allowUpload,
            files = defaults.files,
            fingerprint = fingerprint,
        )
        clearLinkShareRequestNotification(deviceId)
    }

    fun rejectLinkShareDevice(deviceId: String) {
        val device = pendingLinkShareDevices.firstOrNull { item -> item.id == deviceId }
        removeAuthorizedLinkShareDevice(deviceId)
        pendingLinkShareDevices.removeAll { item -> item.id == deviceId }
        pendingLinkShareDeviceSources.remove(deviceId)
        pendingLinkShareDeviceFingerprints.remove(deviceId)
        removeLinkShareSessionsForClient(deviceId)
        clearLinkShareUploadRequestState(deviceId)
        if (device != null) {
            addRejectedLinkShareDevice(device)
        }
        clearLinkShareRequestNotification(deviceId)
    }

    fun removePendingLinkShareDevice(deviceId: String) {
        pendingLinkShareDevices.removeAll { item -> item.id == deviceId }
        pendingLinkShareDeviceSources.remove(deviceId)
        pendingLinkShareDeviceFingerprints.remove(deviceId)
        clearLinkShareRequestNotification(deviceId)
    }

    fun clearPendingLinkShareDevices() {
        val ids = pendingLinkShareDevices.map { item -> item.id }
        pendingLinkShareDevices.clear()
        pendingLinkShareDeviceSources.clear()
        pendingLinkShareDeviceFingerprints.clear()
        ids.forEach { item -> clearLinkShareRequestNotification(item) }
    }

    fun removeLinkShareSessionsForClient(clientId: String) {
        linkShareSessions.entries.removeAll { item -> item.value.clientId == clientId }
    }

    private fun updateLinkShareSessionsUpload(clientId: String, allowUpload: Boolean) {
        linkShareSessions.entries
            .filter { item -> item.value.clientId == clientId }
            .forEach { item ->
                linkShareSessions[item.key] = item.value.copy(
                    access = item.value.access.copy(allowUpload = allowUpload),
                )
            }
    }

    fun clearLinkShareRuntimeAuthorizations() {
        val uploadRequestIds = pendingLinkShareUploadDevices.map { item -> item.id }
        authorizedLinkShareDevices.clear()
        authorizedLinkShareDeviceFingerprints.clear()
        clearPendingLinkShareDevices()
        rejectedLinkShareDevices.clear()
        pendingLinkShareUploadDevices.clear()
        rejectedLinkShareUploadDevices.clear()
        linkShareSessions.clear()
        linkShareTickets.clear()
        uploadRequestIds.forEach { item -> clearLinkShareUploadRequestNotification(item) }
    }

    fun hasLinkShareRuntimeAuthorizations(): Boolean {
        return authorizedLinkShareDevices.isNotEmpty() ||
            pendingLinkShareDevices.isNotEmpty() ||
            rejectedLinkShareDevices.isNotEmpty() ||
            pendingLinkShareUploadDevices.isNotEmpty() ||
            rejectedLinkShareUploadDevices.isNotEmpty() ||
            linkShareSessions.isNotEmpty() ||
            linkShareTickets.isNotEmpty()
    }

    private fun pruneExpiredLinkShareAccess(nowMillis: Long) {
        linkShareSessions.entries.removeAll { item -> nowMillis > item.value.expiresAtMillis }
        linkShareTickets.entries.removeAll { item -> nowMillis > item.value.expiresAtMillis }
    }

    private fun trimOldestLinkShareSessions() {
        if (linkShareSessions.size < MAX_LINK_SHARE_SESSIONS) return
        val removeCount = linkShareSessions.size - MAX_LINK_SHARE_SESSIONS + 1
        linkShareSessions.entries
            .sortedBy { item -> item.value.expiresAtMillis }
            .take(removeCount)
            .forEach { item -> linkShareSessions.remove(item.key) }
    }

    private fun trimOldestLinkShareTickets() {
        if (linkShareTickets.size < MAX_LINK_SHARE_TICKETS) return
        val removeCount = linkShareTickets.size - MAX_LINK_SHARE_TICKETS + 1
        linkShareTickets.entries
            .sortedBy { item -> item.value.expiresAtMillis }
            .take(removeCount)
            .forEach { item -> linkShareTickets.remove(item.key) }
    }

    private fun trimOldestPendingLinkShareDevices(count: Int) {
        if (count <= 0) return
        val removed = pendingLinkShareDevices.take(count)
        pendingLinkShareDevices.removeAll(removed)
        removed.forEach { device ->
            pendingLinkShareDeviceSources.remove(device.id)
            pendingLinkShareDeviceFingerprints.remove(device.id)
            clearLinkShareRequestNotification(device.id)
        }
    }

    private fun trimOldestAuthorizedLinkShareDevices(count: Int) {
        if (count <= 0) return
        authorizedLinkShareDevices.keys
            .take(count)
            .forEach { device ->
                authorizedLinkShareDevices.remove(device)
                authorizedLinkShareDeviceFingerprints.remove(device.id)
                removeLinkShareSessionsForClient(device.id)
            }
    }

    private fun pruneLinkShareNotifications() {
        val kinds = setOf(
            RequestNotificationKind.LinkShare.name,
            RequestNotificationKind.LinkShareUpload.name,
        )
        val linkShareNotifications = notificationState.notifications.filter { item ->
            item.metadata[RequestNotificationFactory.META_REQUEST_KIND] in kinds
        }
        val overflow = linkShareNotifications.size - MAX_LINK_SHARE_NOTIFICATIONS
        if (overflow <= 0) return
        linkShareNotifications
            .sortedBy { item -> item.timestamp }
            .take(overflow)
            .forEach { item ->
                val requestId = item.metadata[RequestNotificationFactory.META_REQUEST_ID] ?: return@forEach
                RequestNotificationDispatcher.remove(notificationState, requestId)
            }
    }

    // 已授权通过设备访问文件的设备
    // Map<设备id, Pair<是否允许访问隐藏文件, 分享文件列表>>
    val shareToDevices = mutableStateMapOf<String, Pair<Boolean, List<FileSimpleInfo>>>()

    fun syncAuthorizedLinkShareFiles(files: List<FileSimpleInfo>) {
        val filteredFiles = filterShareableFiles(files)

        authorizedLinkShareDevices.entries.toList().forEach { (device, access) ->
            authorizedLinkShareDevices[device] = access.withFiles(filteredFiles)
        }
        linkShareSessions.entries.toList().forEach { (token, session) ->
            linkShareSessions[token] = session.copy(
                access = session.access.withFiles(filteredFiles)
            )
        }

        val defaults = linkShareDefaults ?: resolveLinkShareDefaults()
        linkShareDefaults = defaults.copy(files = filteredFiles)
    }

    fun syncShareToDeviceFiles(files: List<FileSimpleInfo>) {
        val fileSnapshot = files.toList()
        shareToDevices.entries.toList().forEach { (deviceId, access) ->
            shareToDevices[deviceId] = access.copy(second = fileSnapshot)
        }
    }

    fun clearShareToDevice(deviceId: String) {
        sendFileMessage.remove(deviceId)
        shareToDevices.remove(deviceId)
        sendFile.remove(deviceId)
    }

    // 链接分享默认授权配置只在新授权时快照，避免设置变更覆盖已授权客户端。
    var linkShareDefaults by mutableStateOf<LinkShareDefaults?>(null)
        private set

    fun updateLinkShareDefaults(
        allowHidden: Boolean,
        files: List<FileSimpleInfo>,
        allowUpload: Boolean = _allowUpload.value,
    ) {
        linkShareDefaults = LinkShareDefaults(allowHidden, allowUpload, filterShareableFiles(files))
    }

    fun resolveLinkShareDefaults(): LinkShareDefaults {
        val current = linkShareDefaults
        return if (current != null) {
            LinkShareDefaults(current.allowHidden, current.allowUpload, filterShareableFiles(current.files))
        } else {
            LinkShareDefaults(
                SettingsUtils.easyFileShare.getHideFile(),
                _allowUpload.value,
                filterShareableFiles(files)
            )
        }
    }

    // Map<设备id, 请求日志管理器>
    val deviceRequestLog = mutableStateMapOf<String, DeviceRequestLogUtils>()

    private val pendingLinkShareDeviceSources = mutableMapOf<String, String>()
    private val pendingLinkShareDeviceFingerprints = mutableMapOf<String, ShareTokenFingerprint>()

    fun deviceRequestLogFor(device: Device): DeviceRequestLogUtils {
        deviceRequestLog[device.id]?.let { existing -> return existing }
        if (deviceRequestLog.size >= MAX_LINK_SHARE_DEVICE_REQUEST_LOGS) {
            val overflow = deviceRequestLog.size - MAX_LINK_SHARE_DEVICE_REQUEST_LOGS + 1
            deviceRequestLog.keys.take(overflow).forEach { deviceId ->
                deviceRequestLog.remove(deviceId)?.deleteLogFile()
            }
        }
        return deviceRequestLog.getOrPut(device.id) { DeviceRequestLogUtils(device) }
    }

    private val _autoApprove: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val autoApprove: StateFlow<Boolean> = _autoApprove
    fun updateAutoApprove(value: Boolean) {
        _autoApprove.value = value
    }

    private val _allowUpload: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val allowUpload: StateFlow<Boolean> = _allowUpload
    fun updateAllowUpload(value: Boolean) {
        _allowUpload.value = value
    }

    // HTTP服务运行状态
    private val _isHttpServerRunning: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isHttpServerRunning: StateFlow<Boolean> = _isHttpServerRunning
    fun updateHttpServerRunning(isRunning: Boolean) {
        _isHttpServerRunning.value = isRunning
    }

    suspend fun initializeEasyShareIfNeeded(): Boolean {
        if (!SettingsUtils.easyFileShare.isAutoStart() || SettingsUtils.easyFileShare.getSharePaths().isEmpty()) {
            updateHttpServerRunning(false)
            files.clear()
            checkedFiles.clear()
            updateConnectPassword("")
            return false
        }

        val defaultShareFiles = withContext(Dispatchers.Default) {
            SettingsUtils.easyFileShare.getSharePaths()
                .mapNotNull { path ->
                    FileUtils.getFile(FileAccessPermission.Allowed, path).getOrNull()
                }
                .distinctBy { item -> item.path }
        }
        files.apply {
            clear()
            addAll(defaultShareFiles)
        }
        checkedFiles.apply {
            clear()
            addAll(defaultShareFiles)
        }

        updateAutoApprove(SettingsUtils.easyFileShare.getAutoApprove())
        updateAllowUpload(SettingsUtils.easyFileShare.getAllowUpload())

        if (SettingsUtils.easyFileShare.getPasswordAccess()) {
            updateConnectPassword(6.randomString(includeSpecial = false))
        } else {
            updateConnectPassword("")
        }

        val hasFiles = defaultShareFiles.isNotEmpty()
        if (!hasFiles) {
            updateHttpServerRunning(false)
        }
        return hasFiles
    }

    private fun clearLinkShareRequestNotification(deviceId: String) {
        val requestId = RequestNotificationFactory.requestId(RequestNotificationKind.LinkShare, deviceId)
        RequestNotificationDispatcher.remove(notificationState, requestId)
    }

    private fun clearLinkShareUploadRequestNotification(deviceId: String) {
        val requestId = RequestNotificationFactory.requestId(RequestNotificationKind.LinkShareUpload, deviceId)
        RequestNotificationDispatcher.remove(notificationState, requestId)
        RequestNotificationDispatcher.remove(notificationState, legacyLinkShareUploadRequestNotificationId(deviceId))
    }
}

data class LinkShareDeviceAccess(
    val allowHidden: Boolean,
    val allowUpload: Boolean,
    val files: List<FileSimpleInfo>
) {
    fun withFiles(files: List<FileSimpleInfo>): LinkShareDeviceAccess {
        return copy(files = files)
    }

    fun authorization(): LinkShareAuthorization {
        return LinkShareAuthorization(allowHidden, allowUpload, files)
    }
}

data class LinkShareAuthorization(
    val allowHidden: Boolean,
    val allowUpload: Boolean = false,
    val files: List<FileSimpleInfo>
)

data class LinkShareSession(
    val token: String,
    val clientId: String,
    val fingerprint: ShareTokenFingerprint,
    val access: LinkShareDeviceAccess,
    val expiresAtMillis: Long,
) {
    val allowUpload: Boolean get() = access.allowUpload
    val files: List<FileSimpleInfo> get() = access.files

    fun authorization(): LinkShareAuthorization {
        return access.authorization()
    }

    fun matches(clientId: String?, fingerprint: ShareTokenFingerprint, nowMillis: Long): Boolean {
        if (nowMillis > expiresAtMillis) return false
        if (clientId != null && clientId != this.clientId) return false
        return this.fingerprint.matches(fingerprint)
    }
}

data class LinkShareTicket(
    val token: String,
    val access: LinkShareDeviceAccess,
    val expiresAtMillis: Long,
) {
    val allowHidden: Boolean get() = access.allowHidden
    val allowUpload: Boolean get() = access.allowUpload
    val files: List<FileSimpleInfo> get() = access.files

    fun authorization(): LinkShareAuthorization {
        return access.authorization()
    }
}

data class ShareTokenFingerprint(
    val clientIp: String?,
    val userAgent: String?
) {
    fun matches(other: ShareTokenFingerprint?): Boolean {
        if (other == null) return false
        val expectedIp = clientIp.normalizedClientIp()
        val actualIp = other.clientIp.normalizedClientIp()
        if (expectedIp.isNullOrBlank() || actualIp.isNullOrBlank() || expectedIp != actualIp) {
            return false
        }
        val expectedUserAgent = userAgent?.trim()
        val actualUserAgent = other.userAgent?.trim()
        return !(expectedUserAgent.isNullOrBlank() || actualUserAgent.isNullOrBlank() || expectedUserAgent != actualUserAgent)
    }

    private fun String?.normalizedClientIp(): String? {
        val value = this
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.substringBefore('%')
            ?.lowercase()
            ?: return null
        return value.removePrefix("::ffff:")
    }
}

data class LinkShareDefaults(
    val allowHidden: Boolean,
    val allowUpload: Boolean,
    val files: List<FileSimpleInfo>
)

enum class LinkShareUploadPermissionRequestResult {
    Accepted,
    AlreadyPending,
    AlreadyAllowed,
    Rejected,
    NotAuthorized
}

private const val LINK_SHARE_UPLOAD_REQUEST_NOTIFICATION_KIND = "link_share_upload_request"

private fun legacyLinkShareUploadRequestNotificationId(deviceId: String): String {
    return "$LINK_SHARE_UPLOAD_REQUEST_NOTIFICATION_KIND:$deviceId"
}

private const val LINK_SHARE_SESSION_TTL_MILLIS = 12L * 60L * 60L * 1000L
private const val LINK_SHARE_TICKET_TTL_MILLIS = 10L * 60L * 1000L
private const val MAX_LINK_SHARE_SESSIONS = 64
private const val MAX_LINK_SHARE_TICKETS = 64
internal const val MAX_PENDING_LINK_SHARE_DEVICES = 64
internal const val MAX_PENDING_LINK_SHARE_DEVICES_PER_SOURCE = 8
internal const val MAX_AUTHORIZED_LINK_SHARE_DEVICES = 64
internal const val MAX_REJECTED_LINK_SHARE_DEVICES = 64
internal const val MAX_PENDING_LINK_SHARE_UPLOAD_DEVICES = 64
internal const val MAX_LINK_SHARE_NOTIFICATIONS = 64
internal const val MAX_LINK_SHARE_DEVICE_REQUEST_LOGS = 64

internal fun filterLinkShareableFiles(
    files: Iterable<FileSimpleInfo>,
    resolveDisk: (FileSimpleInfo) -> DiskBase?,
): List<FileSimpleInfo> {
    return files.filter { file -> isLinkShareDiskAllowed(resolveDisk(file)) }
}

internal fun isLinkShareDiskAllowed(disk: DiskBase?): Boolean {
    return disk?.menuPermission?.share == true
}


/**
 * 表示文件共享的状态。
 */
@Serializable
enum class FileShareStatus {
    /** 文件正在传输中 */
    SENDING,

    /** 文件共享被拒绝 */
    REJECTED,

    /** 文件共享发生错误 */
    ERROR,

    /** 文件共享已同意 */
    COMPLETED,

    /** 文件共享等待中 */
    WAITING
}

/**
 * 表示文件共享的状态类型。
 * 用于定义文件共享操作的当前状态。
 */
enum class FileShareLikeCategory {
    /**
     * 表示文件共享过程中的等待状态。
     * 此状态下操作可能尚未开始或正在排队中。
     */
    WAITING,

    /**
     * 表示文件分享操作正在进行的状态。
     */
    RUNNING,

    /**
     * 表示文件共享中被拒绝的状态。
     *
     * 此状态表明共享操作已经被明确拒绝，无法继续进行。
     */
    REJECTED
}

/**
 * 表示文件共享的类型。
 */
enum class FileShareType {
    NONE,

    /**
     * 表示一种文件共享方式，通过链接进行共享。
     */
    LINK,

    /**
     * 表示设备共享类型的枚举值，DEVICE表示通过设备进行文件共享。
     */
    DEVICE,

    /**
     * 表示系统分享方式，通过平台系统分享面板进行共享。
     */
    SYSTEM,
}

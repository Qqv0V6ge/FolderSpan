package com.folderspan.utils

import com.russhwolf.settings.Settings
import kotlinx.serialization.json.Json

/**
 * 设置工具类，用于快速读取设置值
 */
object SettingsUtils {
    const val KEY_DEVICE_ID = "settings.deviceId"
    const val KEY_FILE_SHARE_ENABLED = "settings.fileShare.enabled"
    const val KEY_FILE_SHARE_PORT = "settings.fileShare.port"
    const val KEY_FILE_SHARE_ACCESS_KEY = "settings.fileShare.accessKey"
    const val KEY_FILE_SHARE_AUTO_AUTHORIZE_DEVICE_CONNECT = "settings.fileShare.autoAuthorizeDeviceConnect"
    const val KEY_FILE_SHARE_AUTO_AUTHORIZE_ROLE_ID = "settings.fileShare.autoAuthorizeRoleId"
    const val KEY_FILE_SHARE_ACCOUNT_DEVICE_AUTO_CONNECT_ENABLED =
        "settings.fileShare.accountDeviceAutoConnectEnabled"
    const val KEY_MCP_ENABLED = "settings.mcp.enabled"
    const val KEY_MCP_PORT = "settings.mcp.port"
    const val KEY_MCP_ALLOWED_ORIGINS = "settings.mcp.allowedOrigins"
    const val KEY_MCP_LAN_ACCESS = "settings.mcp.lanAccess"
    const val KEY_EASY_FILE_SHARE_PORT = "settings.easyFileShare.port"
    const val KEY_EASY_FILE_SHARE_AUTO_START = "settings.easyFileShare.autoStart"
    const val KEY_EASY_FILE_SHARE_AUTO_START_ON_OPEN = "settings.easyFileShare.autoStartOnOpen"
    const val KEY_EASY_FILE_SHARE_SHARE_PATHS = "settings.easyFileShare.sharePaths"
    const val KEY_EASY_FILE_SHARE_AUTO_STOP_ON_EXIT = "settings.easyFileShare.autoStopOnExit"
    const val KEY_EASY_FILE_SHARE_AUTO_APPROVE = "settings.easyFileShare.autoApprove"
    const val KEY_EASY_FILE_SHARE_PASSWORD_ACCESS = "settings.easyFileShare.passwordAccess"
    const val KEY_EASY_FILE_SHARE_ENCRYPTION = "settings.easyFileShare.encryption"
    const val KEY_EASY_FILE_SHARE_HIDE_FILE = "settings.easyFileShare.hideFile"
    const val KEY_EASY_FILE_SHARE_TAP_TO_SEND = "settings.easyFileShare.tapToSendOnDevice"
    const val KEY_EASY_FILE_SHARE_DEVICE_HIDE_FILE = "settings.easyFileShare.deviceHideFile"
    const val KEY_EASY_FILE_SHARE_ALLOW_DEVICE_SHARE = "settings.easyFileShare.allowDeviceShare"
    const val KEY_EASY_FILE_SHARE_ALLOW_UPLOAD = "settings.easyFileShare.allowUpload"
    const val KEY_EASY_FILE_SHARE_AUTO_UPDATE_LINK_SHARE_FILES =
        "settings.easyFileShare.autoUpdateLinkShareFiles"
    const val KEY_EASY_FILE_SHARE_AUTO_UPDATE_DEVICE_SHARE_FILES =
        "settings.easyFileShare.autoUpdateDeviceShareFiles"
    const val KEY_REMOTE_OPEN_CONFIRM = "settings.file.remoteOpenConfirm"
    const val KEY_REMOTE_OPEN_DOWNLOAD_DIR = "settings.file.remoteOpenDownloadDir"
    const val KEY_ROOT_REQUEST_ON_STARTUP = "settings.root.requestOnStartup"
    const val KEY_FILE_FILTER_SHOW_HIDDEN = "settings.file.filter.showHidden"
    const val KEY_FILE_VIEW_GRID = "settings.file.view.grid"
    const val KEY_APPEARANCE_THEME_MODE = "settings.appearance.themeMode"
    const val KEY_APPEARANCE_LANGUAGE = "settings.appearance.language"
    const val KEY_APPEARANCE_DYNAMIC_COLOR = "settings.appearance.dynamicColor"
    const val KEY_APPEARANCE_CUSTOM_COLOR_ENABLED = "settings.appearance.customColor.enabled"
    const val KEY_APPEARANCE_CUSTOM_PRIMARY = "settings.appearance.customColor.seed"
    const val KEY_DEVICE_TLS_PKCS12_PASSWORD = "settings.tls.device.pkcs12.password"
    const val KEY_CRYPTO_KEY = "settings.crypto.key"
    const val KEY_DATA_ENCRYPTION_KEY_SYNC = "app.secrets.dataEncryptionKey"
    const val KEY_LAST_CRASH = "settings.app.lastCrash"
    const val KEY_LAST_SESSION_FOREGROUND = "settings.app.lastSessionForeground"
    const val KEY_NETWORK_FILTER_PROTOCOL = "settings.network.filter.protocol"
    const val KEY_DRAWER_EXPAND_BOOKMARK = "settings.drawer.expand.bookmark"
    const val KEY_DRAWER_EXPAND_DEVICE = "settings.drawer.expand.device"
    const val KEY_DRAWER_EXPAND_WEBRTC = "settings.drawer.expand.webrtc"
    const val KEY_DRAWER_EXPAND_SHARE = "settings.drawer.expand.share"
    const val KEY_DRAWER_EXPAND_NETWORK = "settings.drawer.expand.network"
    const val KEY_DRAWER_EXPAND_SYNC = "settings.drawer.expand.sync"
    const val KEY_DRAWER_SHOW_DEVICE = "settings.drawer.show.device"
    const val KEY_DRAWER_SHOW_WEBRTC = "settings.drawer.show.webrtc"
    const val KEY_DRAWER_SHOW_NETWORK = "settings.drawer.show.network"
    const val KEY_DRAWER_SHOW_SYNC = "settings.drawer.show.sync"
    const val KEY_BOOKMARK_DEFAULT_INITIALIZED = "settings.bookmark.defaultInitialized"
    const val KEY_STARTUP_PERMISSION_REMINDER_HANDLED = "settings.notification.startupPermissionReminderHandled"
    const val KEY_ANNOUNCEMENT_READ_CUTOFF = "settings.notification.announcementReadCutoff"
    const val KEY_APP_UPDATE_CHANNEL = "settings.app.updateChannel"
    const val DEFAULT_APP_UPDATE_CHANNEL = "release"
    /** Device-local diagnostic capture; intentionally absent from syncableSettings. */
    const val KEY_AUTO_CAPTURE_LOGS = "settings.app.autoCaptureLogs"
    const val KEY_WELCOME_AGREEMENT_ACCEPTED = "settings.app.welcomeAgreement.accepted"
    const val KEY_ONBOARDING_COMPLETED = "settings.app.onboarding.completed"
    const val KEY_SETTINGS_SYNC_LAST_TIMESTAMP = "settings.sync.lastTimestamp"
    const val KEY_SETTINGS_SYNC_ENTRY_TIMESTAMPS = "settings.sync.entryTimestamps"
    const val KEY_SETTINGS_SYNC_PENDING_SETTING_KEYS = "settings.sync.pendingSettingKeys"
    const val KEY_SETTINGS_SYNC_PENDING_SNAPSHOT_KEYS = "settings.sync.pendingSnapshotKeys"
    const val KEY_MANUAL_DATA_SYNC_SELECTED_CATEGORIES = "settings.sync.manualData.selectedCategories"
    const val KEY_EDITOR_SEARCH_HISTORY = "settings.editor.searchHistory"
    const val KEY_EDITOR_SHOW_LINE_NUMBERS = "settings.editor.showLineNumbers"
    const val KEY_EDITOR_AUTOMATIC_WRAP = "settings.editor.automaticWrap"
    const val KEY_BOOKMARKS_SYNC_SNAPSHOT = "app.bookmarks.snapshot"
    const val KEY_EDITOR_SEARCH_HISTORY_SYNC_SNAPSHOT = "app.editorSearchHistory.snapshot"
    const val KEY_FAVORITES_SYNC_SNAPSHOT = "app.favorites.snapshot"

    enum class SettingValueType {
        String,
        Boolean,
        Int,
        Long,
        StringList,
    }

    data class SyncableSetting(
        val key: String,
        val valueType: SettingValueType,
        val defaultValue: String,
    )

    val syncableSettings: List<SyncableSetting> = listOf(
        SyncableSetting(KEY_FILE_SHARE_ENABLED, SettingValueType.Boolean, "true"),
        SyncableSetting(KEY_FILE_SHARE_PORT, SettingValueType.Int, "12040"),
        SyncableSetting(
            KEY_FILE_SHARE_ACCESS_KEY,
            SettingValueType.String,
            "{\"enabled\":false,\"value\":\"\"}",
        ),
        SyncableSetting(KEY_FILE_SHARE_AUTO_AUTHORIZE_DEVICE_CONNECT, SettingValueType.Boolean, "false"),
        SyncableSetting(KEY_FILE_SHARE_AUTO_AUTHORIZE_ROLE_ID, SettingValueType.Long, "2"),
        SyncableSetting(KEY_EASY_FILE_SHARE_PORT, SettingValueType.Int, "1204"),
        SyncableSetting(KEY_EASY_FILE_SHARE_AUTO_START, SettingValueType.Boolean, "false"),
        SyncableSetting(KEY_EASY_FILE_SHARE_AUTO_START_ON_OPEN, SettingValueType.Boolean, "true"),
        SyncableSetting(KEY_EASY_FILE_SHARE_SHARE_PATHS, SettingValueType.StringList, "[]"),
        SyncableSetting(KEY_EASY_FILE_SHARE_AUTO_STOP_ON_EXIT, SettingValueType.Boolean, "false"),
        SyncableSetting(KEY_EASY_FILE_SHARE_AUTO_APPROVE, SettingValueType.Boolean, "true"),
        SyncableSetting(KEY_EASY_FILE_SHARE_PASSWORD_ACCESS, SettingValueType.Boolean, "false"),
        SyncableSetting(KEY_EASY_FILE_SHARE_ENCRYPTION, SettingValueType.Boolean, "false"),
        SyncableSetting(KEY_EASY_FILE_SHARE_HIDE_FILE, SettingValueType.Boolean, "false"),
        SyncableSetting(KEY_EASY_FILE_SHARE_TAP_TO_SEND, SettingValueType.Boolean, "true"),
        SyncableSetting(KEY_EASY_FILE_SHARE_DEVICE_HIDE_FILE, SettingValueType.Boolean, "false"),
        SyncableSetting(KEY_EASY_FILE_SHARE_ALLOW_DEVICE_SHARE, SettingValueType.Boolean, "true"),
        SyncableSetting(KEY_EASY_FILE_SHARE_ALLOW_UPLOAD, SettingValueType.Boolean, "false"),
        SyncableSetting(KEY_EASY_FILE_SHARE_AUTO_UPDATE_LINK_SHARE_FILES, SettingValueType.Boolean, "false"),
        SyncableSetting(KEY_EASY_FILE_SHARE_AUTO_UPDATE_DEVICE_SHARE_FILES, SettingValueType.Boolean, "false"),
        SyncableSetting(KEY_REMOTE_OPEN_CONFIRM, SettingValueType.Boolean, "true"),
        SyncableSetting(KEY_ROOT_REQUEST_ON_STARTUP, SettingValueType.Boolean, "false"),
        SyncableSetting(KEY_FILE_FILTER_SHOW_HIDDEN, SettingValueType.Boolean, "false"),
        SyncableSetting(KEY_FILE_VIEW_GRID, SettingValueType.Boolean, "false"),
        SyncableSetting(KEY_APPEARANCE_THEME_MODE, SettingValueType.String, "System"),
        SyncableSetting(KEY_APPEARANCE_DYNAMIC_COLOR, SettingValueType.Boolean, "true"),
        SyncableSetting(KEY_APPEARANCE_CUSTOM_COLOR_ENABLED, SettingValueType.Boolean, "false"),
        SyncableSetting(KEY_APPEARANCE_CUSTOM_PRIMARY, SettingValueType.String, "#6750A4"),
        SyncableSetting(KEY_NETWORK_FILTER_PROTOCOL, SettingValueType.String, "ALL"),
        SyncableSetting(KEY_DRAWER_EXPAND_BOOKMARK, SettingValueType.Boolean, "true"),
        SyncableSetting(KEY_DRAWER_EXPAND_DEVICE, SettingValueType.Boolean, "true"),
        SyncableSetting(KEY_DRAWER_EXPAND_SHARE, SettingValueType.Boolean, "true"),
        SyncableSetting(KEY_DRAWER_EXPAND_NETWORK, SettingValueType.Boolean, "true"),
        SyncableSetting(KEY_DRAWER_EXPAND_SYNC, SettingValueType.Boolean, "true"),
        SyncableSetting(KEY_DRAWER_SHOW_DEVICE, SettingValueType.Boolean, "true"),
        SyncableSetting(KEY_DRAWER_SHOW_NETWORK, SettingValueType.Boolean, "true"),
        SyncableSetting(KEY_DRAWER_SHOW_SYNC, SettingValueType.Boolean, "true"),
    )

    private val syncableSettingsByKey: Map<String, SyncableSetting> =
        syncableSettings.associateBy { it.key }

    fun syncableSettingOrNull(key: String): SyncableSetting? =
        syncableSettingsByKey[key]

    fun notifySettingChanged(key: String) {
        SettingsChangeSync.onSettingChanged(key)
    }

    private lateinit var settings: Settings

    /**
     * 文件共享设置
     */
    val fileShare = FileShareSettings

    /** MCP listener settings are device-local and intentionally absent from syncableSettings. */
    val mcp = McpSettings

    /**
     * EasyFileShare 设置
     */
    val easyFileShare = EasyFileShareSettings

    /**
     * 加密设置
     */
    val crypto = CryptoSettings

    /**
     * 初始化设置
     */
    fun init(settings: Settings) {
        this.settings = settings
        fileShare.init(settings)
        mcp.init(settings)
        easyFileShare.init(settings)
        crypto.init(settings)
    }

    /**
     * 获取字符串设置
     */
    fun getString(key: String, defaultValue: String? = null): String? {
        return settings.getStringOrNull(key) ?: defaultValue
    }

    /**
     * 获取布尔设置
     */
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return settings.getBoolean(key, defaultValue)
    }

    /**
     * 获取整数设置
     */
    fun getInt(key: String, defaultValue: Int = 0): Int {
        return settings.getInt(key, defaultValue)
    }

    /**
     * 获取长整数设置
     */
    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return settings.getLong(key, defaultValue)
    }

    /**
     * 设置布尔值
     */
    fun putBoolean(key: String, value: Boolean) {
        settings.putBoolean(key, value)
        notifySettingChanged(key)
    }

    /**
     * 启动时是否请求 Root 权限。
     */
    fun isRootRequestOnStartupEnabled(): Boolean {
        return settings.getBoolean(KEY_ROOT_REQUEST_ON_STARTUP, false)
    }

    /**
     * 启动权限提醒是否已处理
     */
    fun isStartupPermissionReminderHandled(): Boolean {
        return settings.getBoolean(KEY_STARTUP_PERMISSION_REMINDER_HANDLED, false)
    }

    /**
     * 更新启动权限提醒处理状态
     */
    fun setStartupPermissionReminderHandled(handled: Boolean) {
        settings.putBoolean(KEY_STARTUP_PERMISSION_REMINDER_HANDLED, handled)
    }

    fun hasAnnouncementReadCutoff(): Boolean = settings.hasKey(KEY_ANNOUNCEMENT_READ_CUTOFF)

    fun announcementReadCutoffOrNull(): Long? =
        if (hasAnnouncementReadCutoff()) settings.getLong(KEY_ANNOUNCEMENT_READ_CUTOFF, 0L) else null

    fun persistAnnouncementReadCutoff(value: Long) {
        settings.putLong(KEY_ANNOUNCEMENT_READ_CUTOFF, value)
    }

    fun appUpdateChannel(): String =
        settings.getString(KEY_APP_UPDATE_CHANNEL, DEFAULT_APP_UPDATE_CHANNEL)

    fun setAppUpdateChannel(token: String) {
        settings.putString(KEY_APP_UPDATE_CHANNEL, token)
    }

    /**
     * 首次欢迎页用户协议与隐私政策是否已同意。
     */
    fun isWelcomeAgreementAccepted(): Boolean {
        return settings.getBoolean(KEY_WELCOME_AGREEMENT_ACCEPTED, false)
    }

    /**
     * 更新首次欢迎页协议同意状态。
     */
    fun setWelcomeAgreementAccepted(accepted: Boolean) {
        settings.putBoolean(KEY_WELCOME_AGREEMENT_ACCEPTED, accepted)
    }

    /**
     * 首次引导是否已完成。
     */
    fun isOnboardingCompleted(): Boolean {
        return settings.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    /**
     * 更新首次引导完成状态。
     */
    fun setOnboardingCompleted(completed: Boolean) {
        settings.putBoolean(KEY_ONBOARDING_COMPLETED, completed)
    }
}

object McpSettings {
    private lateinit var settings: Settings

    fun init(settings: Settings) {
        this.settings = settings
    }

    fun isEnabled(): Boolean = settings.getBoolean(SettingsUtils.KEY_MCP_ENABLED, false)

    fun getPort(defaultPort: Int): Int = settings.getInt(SettingsUtils.KEY_MCP_PORT, defaultPort)

    fun isLanAccessEnabled(): Boolean = settings.getBoolean(SettingsUtils.KEY_MCP_LAN_ACCESS, false)

    fun putEnabled(enabled: Boolean) {
        settings.putBoolean(SettingsUtils.KEY_MCP_ENABLED, enabled)
    }

    fun putPort(port: Int) {
        settings.putInt(SettingsUtils.KEY_MCP_PORT, port)
    }

    fun putLanAccessEnabled(enabled: Boolean) {
        settings.putBoolean(SettingsUtils.KEY_MCP_LAN_ACCESS, enabled)
    }

    fun clearAllowedOrigins() {
        settings.remove(SettingsUtils.KEY_MCP_ALLOWED_ORIGINS)
    }
}

/**
 * 文件共享设置
 */
object FileShareSettings {

    private lateinit var settings: Settings

    /**
     * 初始化设置
     */
    fun init(settings: Settings) {
        this.settings = settings
    }

    /**
     * 检查文件共享是否启用
     */
    fun isEnabled(): Boolean {
        return settings.getBoolean(SettingsUtils.KEY_FILE_SHARE_ENABLED, true)
    }

    /**
     * 获取文件共享端口
     */
    fun getPort(): Int {
        return settings.getInt(SettingsUtils.KEY_FILE_SHARE_PORT, 12040)
    }

    /**
     * 同账号设备自动连接与授权只在本机生效，默认关闭。
     */
    fun isAccountDeviceAutoConnectEnabled(): Boolean {
        return settings.getBoolean(SettingsUtils.KEY_FILE_SHARE_ACCOUNT_DEVICE_AUTO_CONNECT_ENABLED, false)
    }

    fun putAccountDeviceAutoConnectEnabled(enabled: Boolean) {
        settings.putBoolean(SettingsUtils.KEY_FILE_SHARE_ACCOUNT_DEVICE_AUTO_CONNECT_ENABLED, enabled)
    }

}

/**
 * EasyFileShare 设置
 */
object EasyFileShareSettings {

    private lateinit var settings: Settings
    private val json by lazy { Json { ignoreUnknownKeys = true } }

    /**
     * 初始化设置
     */
    fun init(settings: Settings) {
        this.settings = settings
    }

    /**
     * 获取 EasyFileShare 端口
     */
    fun getPort(): Int {
        return settings.getInt(SettingsUtils.KEY_EASY_FILE_SHARE_PORT, 1204)
    }

    /**
     * 检查是否自动启动
     */
    fun isAutoStart(): Boolean {
        return settings.getBoolean(SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_START, false)
    }

    /**
     * 检查打开页面时是否自动启动
     */
    fun isAutoStartOnOpen(): Boolean {
        return settings.getBoolean(SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_START_ON_OPEN, true)
    }

    /**
     * 获取默认分享路径列表
     */
    fun getSharePaths(): List<String> {
        val raw = settings.getString(SettingsUtils.KEY_EASY_FILE_SHARE_SHARE_PATHS, "[]")
        return runCatching {
            if (raw.isBlank()) emptyList() else json.decodeFromString<List<String>>(raw)
        }.getOrElse { emptyList() }
    }

    /**
     * 获取自动允许设置
     */
    fun getAutoApprove(): Boolean {
        return settings.getBoolean(SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_APPROVE, true)
    }

    /**
     * 获取离开页面时是否自动关闭服务
     */
    fun getAutoStopOnExit(): Boolean {
        return settings.getBoolean(SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_STOP_ON_EXIT, false)
    }

    /**
     * 获取密码访问设置
     */
    fun getPasswordAccess(): Boolean {
        return settings.getBoolean(SettingsUtils.KEY_EASY_FILE_SHARE_PASSWORD_ACCESS, false)
    }

    /**
     * 获取隐藏文件设置
     */
    fun getHideFile(): Boolean {
        return settings.getBoolean(SettingsUtils.KEY_EASY_FILE_SHARE_HIDE_FILE, false)
    }

    /**
     * 获取点击设备立即发送的设置
     */
    fun getTapToSendOnDevice(): Boolean {
        return settings.getBoolean(SettingsUtils.KEY_EASY_FILE_SHARE_TAP_TO_SEND, true)
    }

    /**
     * 获取分享到设备时是否默认隐藏文件
     */
    fun getDeviceHideFile(): Boolean {
        return settings.getBoolean(SettingsUtils.KEY_EASY_FILE_SHARE_DEVICE_HIDE_FILE, false)
    }

    /**
     * 获取链接分享页面是否允许上传
     */
    fun getAllowUpload(): Boolean {
        return settings.getBoolean(SettingsUtils.KEY_EASY_FILE_SHARE_ALLOW_UPLOAD, false)
    }

    /**
     * 获取是否自动更新已授权链接分享设备的文件列表
     */
    fun getAutoUpdateLinkShareFiles(): Boolean {
        return settings.getBoolean(SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_UPDATE_LINK_SHARE_FILES, false)
    }

    /**
     * 获取是否自动更新已分享设备的文件列表
     */
    fun getAutoUpdateDeviceShareFiles(): Boolean {
        return settings.getBoolean(SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_UPDATE_DEVICE_SHARE_FILES, false)
    }
}

/**
 * 本地凭据加密使用配置目标级 DEK，见 [DataEncryptionKey]。
 */
object CryptoSettings {
    fun init(settings: Settings) {
        DataEncryptionKey.init(settings)
    }
}

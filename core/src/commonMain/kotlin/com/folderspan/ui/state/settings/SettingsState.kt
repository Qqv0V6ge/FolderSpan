package com.folderspan.ui.state.settings

import com.folderspan.PlatformType
import com.folderspan.localization.AppLanguageController
import com.folderspan.localization.AppLanguageMode
import com.folderspan.service.http.FileShareAccessKeyConfig
import com.folderspan.service.http.readFileShareAccessKeyConfig
import com.folderspan.service.http.writeFileShareAccessKeyConfig
import com.folderspan.utils.SettingsUtils.KEY_APPEARANCE_CUSTOM_COLOR_ENABLED
import com.folderspan.utils.SettingsUtils.KEY_APPEARANCE_CUSTOM_PRIMARY
import com.folderspan.utils.SettingsUtils.KEY_APPEARANCE_DYNAMIC_COLOR
import com.folderspan.utils.SettingsUtils.KEY_APPEARANCE_THEME_MODE
import com.folderspan.utils.SettingsUtils.KEY_AUTO_CAPTURE_LOGS
import com.folderspan.utils.SettingsUtils.KEY_DEVICE_ID
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_ALLOW_DEVICE_SHARE
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_ALLOW_UPLOAD
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_APPROVE
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_START
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_START_ON_OPEN
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_STOP_ON_EXIT
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_UPDATE_DEVICE_SHARE_FILES
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_UPDATE_LINK_SHARE_FILES
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_DEVICE_HIDE_FILE
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_ENCRYPTION
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_HIDE_FILE
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_PASSWORD_ACCESS
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_PORT
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_SHARE_PATHS
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_TAP_TO_SEND
import com.folderspan.utils.SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY
import com.folderspan.utils.SettingsUtils.KEY_FILE_SHARE_ACCOUNT_DEVICE_AUTO_CONNECT_ENABLED
import com.folderspan.utils.SettingsUtils.KEY_FILE_SHARE_AUTO_AUTHORIZE_DEVICE_CONNECT
import com.folderspan.utils.SettingsUtils.KEY_FILE_SHARE_AUTO_AUTHORIZE_ROLE_ID
import com.folderspan.utils.SettingsUtils.KEY_FILE_SHARE_ENABLED
import com.folderspan.utils.SettingsUtils.KEY_FILE_SHARE_PORT
import com.folderspan.utils.SettingsUtils.KEY_REMOTE_OPEN_CONFIRM
import com.folderspan.utils.SettingsUtils.KEY_REMOTE_OPEN_DOWNLOAD_DIR
import com.folderspan.utils.SettingsUtils.KEY_ROOT_REQUEST_ON_STARTUP
import com.folderspan.utils.LogCapture
import com.folderspan.utils.LogKit
import com.folderspan.utils.SettingsUtils.notifySettingChanged
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import strings.AppStrings

/**
 * 设置页状态：设备信息
 */
class SettingsState(
    private val settings: Settings,
    private val appLanguageController: AppLanguageController = AppLanguageController(settings),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _deviceId = MutableStateFlow(settings.getString(KEY_DEVICE_ID, ""))
    val deviceId = _deviceId.asStateFlow()

    private val _deviceType = MutableStateFlow(PlatformType.name)
    val deviceType = _deviceType.asStateFlow()

    private val _fileShareEnabled = MutableStateFlow(settings.getBoolean(KEY_FILE_SHARE_ENABLED, true))
    val fileShareEnabled = _fileShareEnabled.asStateFlow()

    private val _fileSharePort = MutableStateFlow(settings.getInt(KEY_FILE_SHARE_PORT, 12040))
    val fileSharePort = _fileSharePort.asStateFlow()

    private val _fileShareAccessKeyConfig = MutableStateFlow(settings.readFileShareAccessKeyConfig())
    val fileShareAccessKeyConfig = _fileShareAccessKeyConfig.asStateFlow()

    private val _fileShareAutoAuthorizeDeviceConnect =
        MutableStateFlow(settings.getBoolean(KEY_FILE_SHARE_AUTO_AUTHORIZE_DEVICE_CONNECT, false))
    val fileShareAutoAuthorizeDeviceConnect = _fileShareAutoAuthorizeDeviceConnect.asStateFlow()

    private val _fileShareAutoAuthorizeRoleId =
        MutableStateFlow(settings.getLong(KEY_FILE_SHARE_AUTO_AUTHORIZE_ROLE_ID, 2L))
    val fileShareAutoAuthorizeRoleId = _fileShareAutoAuthorizeRoleId.asStateFlow()

    private val _fileShareAccountDeviceAutoConnectEnabled =
        MutableStateFlow(settings.getBoolean(KEY_FILE_SHARE_ACCOUNT_DEVICE_AUTO_CONNECT_ENABLED, false))
    val fileShareAccountDeviceAutoConnectEnabled = _fileShareAccountDeviceAutoConnectEnabled.asStateFlow()

    // 远程文件打开前提示开关（true=提示）
    private val _remoteOpenConfirmEnabled =
        MutableStateFlow(settings.getBoolean(KEY_REMOTE_OPEN_CONFIRM, true))
    val remoteOpenConfirmEnabled = _remoteOpenConfirmEnabled.asStateFlow()

    // 远程文件下载目录（空值时走缓存目录）
    private val _remoteOpenDownloadDirectory =
        MutableStateFlow(settings.getString(KEY_REMOTE_OPEN_DOWNLOAD_DIR, ""))
    val remoteOpenDownloadDirectory = _remoteOpenDownloadDirectory.asStateFlow()

    private val _rootStartupRequestEnabled =
        MutableStateFlow(settings.getBoolean(KEY_ROOT_REQUEST_ON_STARTUP, false))
    val rootStartupRequestEnabled = _rootStartupRequestEnabled.asStateFlow()

    // 自动捕获日志是本机诊断设置，不触发 Pro 配置同步。
    private val _autoCaptureLogs =
        MutableStateFlow(settings.getBoolean(KEY_AUTO_CAPTURE_LOGS, false))
    val autoCaptureLogs = _autoCaptureLogs.asStateFlow()

    // EasyFileShare 设置状态
    private val _easyFileSharePort = MutableStateFlow(settings.getInt(KEY_EASY_FILE_SHARE_PORT, 1204))
    val easyFileSharePort = _easyFileSharePort.asStateFlow()

    private val _easyFileShareAutoStart = MutableStateFlow(settings.getBoolean(KEY_EASY_FILE_SHARE_AUTO_START, false))
    val easyFileShareAutoStart = _easyFileShareAutoStart.asStateFlow()

    private val _easyFileShareAutoStartOnOpen =
        MutableStateFlow(settings.getBoolean(KEY_EASY_FILE_SHARE_AUTO_START_ON_OPEN, true))
    val easyFileShareAutoStartOnOpen = _easyFileShareAutoStartOnOpen.asStateFlow()

    private val _easyFileShareSharePaths =
        MutableStateFlow(parseSharePaths(settings.getString(KEY_EASY_FILE_SHARE_SHARE_PATHS, "[]")))
    val easyFileShareSharePaths = _easyFileShareSharePaths.asStateFlow()

    private val _easyFileShareAutoStopOnExit =
        MutableStateFlow(settings.getBoolean(KEY_EASY_FILE_SHARE_AUTO_STOP_ON_EXIT, false))
    val easyFileShareAutoStopOnExit = _easyFileShareAutoStopOnExit.asStateFlow()

    private val _easyFileShareAutoApprove =
        MutableStateFlow(settings.getBoolean(KEY_EASY_FILE_SHARE_AUTO_APPROVE, true))
    val easyFileShareAutoApprove = _easyFileShareAutoApprove.asStateFlow()

    private val _easyFileSharePasswordAccess =
        MutableStateFlow(settings.getBoolean(KEY_EASY_FILE_SHARE_PASSWORD_ACCESS, false))
    val easyFileSharePasswordAccess = _easyFileSharePasswordAccess.asStateFlow()

    private val _easyFileShareEncryption = MutableStateFlow(settings.getBoolean(KEY_EASY_FILE_SHARE_ENCRYPTION, false))
    val easyFileShareEncryption = _easyFileShareEncryption.asStateFlow()

    private val _easyFileShareHideFile = MutableStateFlow(settings.getBoolean(KEY_EASY_FILE_SHARE_HIDE_FILE, false))
    val easyFileShareHideFile = _easyFileShareHideFile.asStateFlow()

    private val _easyFileShareTapToSend =
        MutableStateFlow(settings.getBoolean(KEY_EASY_FILE_SHARE_TAP_TO_SEND, true))
    val easyFileShareTapToSend = _easyFileShareTapToSend.asStateFlow()

    private val _easyFileShareDeviceHideFile =
        MutableStateFlow(settings.getBoolean(KEY_EASY_FILE_SHARE_DEVICE_HIDE_FILE, false))
    val easyFileShareDeviceHideFile = _easyFileShareDeviceHideFile.asStateFlow()

    private val _easyFileShareAllowDeviceShare =
        MutableStateFlow(settings.getBoolean(KEY_EASY_FILE_SHARE_ALLOW_DEVICE_SHARE, true))
    val easyFileShareAllowDeviceShare = _easyFileShareAllowDeviceShare.asStateFlow()

    private val _easyFileShareAllowUpload =
        MutableStateFlow(settings.getBoolean(KEY_EASY_FILE_SHARE_ALLOW_UPLOAD, false))
    val easyFileShareAllowUpload = _easyFileShareAllowUpload.asStateFlow()

    private val _easyFileShareAutoUpdateLinkShareFiles =
        MutableStateFlow(settings.getBoolean(KEY_EASY_FILE_SHARE_AUTO_UPDATE_LINK_SHARE_FILES, false))
    val easyFileShareAutoUpdateLinkShareFiles = _easyFileShareAutoUpdateLinkShareFiles.asStateFlow()

    private val _easyFileShareAutoUpdateDeviceShareFiles =
        MutableStateFlow(settings.getBoolean(KEY_EASY_FILE_SHARE_AUTO_UPDATE_DEVICE_SHARE_FILES, false))
    val easyFileShareAutoUpdateDeviceShareFiles = _easyFileShareAutoUpdateDeviceShareFiles.asStateFlow()

    // 外观设置状态
    private val _themeMode = MutableStateFlow(
        settings.getString(KEY_APPEARANCE_THEME_MODE, ThemeMode.System.name).toThemeMode()
    )
    val themeMode = _themeMode.asStateFlow()

    val appLanguageMode = appLanguageController.mode
    val resolvedAppLanguage = appLanguageController.resolvedLanguage

    private val _dynamicColorEnabled =
        MutableStateFlow(settings.getBoolean(KEY_APPEARANCE_DYNAMIC_COLOR, true))
    val dynamicColorEnabled = _dynamicColorEnabled.asStateFlow()

    private val _customColorEnabled =
        MutableStateFlow(settings.getBoolean(KEY_APPEARANCE_CUSTOM_COLOR_ENABLED, false))
    val customColorEnabled = _customColorEnabled.asStateFlow()

    private val initialSeedColor = settings.getStringOrNull(KEY_APPEARANCE_CUSTOM_PRIMARY)
        ?: "#6750A4"

    private val _customSeedColor = MutableStateFlow(initialSeedColor)
    val customSeedColor = _customSeedColor.asStateFlow()

    init {
        LogCapture.setEnabled(_autoCaptureLogs.value)
    }

    fun reloadFromSettings() {
        _deviceId.value = settings.getString(KEY_DEVICE_ID, "")
        _fileShareEnabled.value = settings.getBoolean(KEY_FILE_SHARE_ENABLED, true)
        _fileSharePort.value = settings.getInt(KEY_FILE_SHARE_PORT, 12040)
        _fileShareAccessKeyConfig.value = settings.readFileShareAccessKeyConfig()
        _fileShareAutoAuthorizeDeviceConnect.value =
            settings.getBoolean(KEY_FILE_SHARE_AUTO_AUTHORIZE_DEVICE_CONNECT, false)
        _fileShareAutoAuthorizeRoleId.value = settings.getLong(KEY_FILE_SHARE_AUTO_AUTHORIZE_ROLE_ID, 2L)
        _fileShareAccountDeviceAutoConnectEnabled.value =
            settings.getBoolean(KEY_FILE_SHARE_ACCOUNT_DEVICE_AUTO_CONNECT_ENABLED, false)
        _remoteOpenConfirmEnabled.value = settings.getBoolean(KEY_REMOTE_OPEN_CONFIRM, true)
        _remoteOpenDownloadDirectory.value = settings.getString(KEY_REMOTE_OPEN_DOWNLOAD_DIR, "")
        _rootStartupRequestEnabled.value = settings.getBoolean(KEY_ROOT_REQUEST_ON_STARTUP, false)
        _autoCaptureLogs.value = settings.getBoolean(KEY_AUTO_CAPTURE_LOGS, false)
        LogCapture.setEnabled(_autoCaptureLogs.value)
        _easyFileSharePort.value = settings.getInt(KEY_EASY_FILE_SHARE_PORT, 1204)
        _easyFileShareAutoStart.value = settings.getBoolean(KEY_EASY_FILE_SHARE_AUTO_START, false)
        _easyFileShareAutoStartOnOpen.value = settings.getBoolean(KEY_EASY_FILE_SHARE_AUTO_START_ON_OPEN, true)
        _easyFileShareSharePaths.value = parseSharePaths(settings.getString(KEY_EASY_FILE_SHARE_SHARE_PATHS, "[]"))
        _easyFileShareAutoStopOnExit.value = settings.getBoolean(KEY_EASY_FILE_SHARE_AUTO_STOP_ON_EXIT, false)
        _easyFileShareAutoApprove.value = settings.getBoolean(KEY_EASY_FILE_SHARE_AUTO_APPROVE, true)
        _easyFileSharePasswordAccess.value = settings.getBoolean(KEY_EASY_FILE_SHARE_PASSWORD_ACCESS, false)
        _easyFileShareEncryption.value = settings.getBoolean(KEY_EASY_FILE_SHARE_ENCRYPTION, false)
        _easyFileShareHideFile.value = settings.getBoolean(KEY_EASY_FILE_SHARE_HIDE_FILE, false)
        _easyFileShareTapToSend.value = settings.getBoolean(KEY_EASY_FILE_SHARE_TAP_TO_SEND, true)
        _easyFileShareDeviceHideFile.value = settings.getBoolean(KEY_EASY_FILE_SHARE_DEVICE_HIDE_FILE, false)
        _easyFileShareAllowDeviceShare.value = settings.getBoolean(KEY_EASY_FILE_SHARE_ALLOW_DEVICE_SHARE, true)
        _easyFileShareAllowUpload.value = settings.getBoolean(KEY_EASY_FILE_SHARE_ALLOW_UPLOAD, false)
        _easyFileShareAutoUpdateLinkShareFiles.value =
            settings.getBoolean(KEY_EASY_FILE_SHARE_AUTO_UPDATE_LINK_SHARE_FILES, false)
        _easyFileShareAutoUpdateDeviceShareFiles.value =
            settings.getBoolean(KEY_EASY_FILE_SHARE_AUTO_UPDATE_DEVICE_SHARE_FILES, false)
        _themeMode.value = settings.getString(KEY_APPEARANCE_THEME_MODE, ThemeMode.System.name).toThemeMode()
        appLanguageController.reloadFromSettings()
        _dynamicColorEnabled.value = settings.getBoolean(KEY_APPEARANCE_DYNAMIC_COLOR, true)
        _customColorEnabled.value = settings.getBoolean(KEY_APPEARANCE_CUSTOM_COLOR_ENABLED, false)
        _customSeedColor.value = settings.getStringOrNull(KEY_APPEARANCE_CUSTOM_PRIMARY) ?: "#6750A4"
    }

    // 保存文件共享开关
    fun setFileShareEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_FILE_SHARE_ENABLED, enabled)
        _fileShareEnabled.value = enabled
        notifySettingChanged(KEY_FILE_SHARE_ENABLED)
    }

    // 保存文件共享端口
    fun setFileSharePort(port: Int) {
        settings.putInt(KEY_FILE_SHARE_PORT, port)
        _fileSharePort.value = port
        notifySettingChanged(KEY_FILE_SHARE_PORT)
    }

    fun setFileShareAccessKeyConfig(config: FileShareAccessKeyConfig) {
        val normalized = config.normalized()
        settings.writeFileShareAccessKeyConfig(normalized)
        _fileShareAccessKeyConfig.value = normalized
        notifySettingChanged(KEY_FILE_SHARE_ACCESS_KEY)
    }

    // 保存自动授权设备连接开关
    fun setFileShareAutoAuthorizeDeviceConnect(enabled: Boolean) {
        settings.putBoolean(KEY_FILE_SHARE_AUTO_AUTHORIZE_DEVICE_CONNECT, enabled)
        _fileShareAutoAuthorizeDeviceConnect.value = enabled
        notifySettingChanged(KEY_FILE_SHARE_AUTO_AUTHORIZE_DEVICE_CONNECT)
    }

    // 保存自动授权设备连接默认角色ID
    fun setFileShareAutoAuthorizeRoleId(roleId: Long) {
        settings.putLong(KEY_FILE_SHARE_AUTO_AUTHORIZE_ROLE_ID, roleId)
        _fileShareAutoAuthorizeRoleId.value = roleId
        notifySettingChanged(KEY_FILE_SHARE_AUTO_AUTHORIZE_ROLE_ID)
    }

    // 同账号设备自动连接与授权是本机设置，不触发 Pro 配置同步。
    fun setFileShareAccountDeviceAutoConnectEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_FILE_SHARE_ACCOUNT_DEVICE_AUTO_CONNECT_ENABLED, enabled)
        _fileShareAccountDeviceAutoConnectEnabled.value = enabled
    }

    // 保存远程打开提示开关
    fun setRemoteOpenConfirmEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_REMOTE_OPEN_CONFIRM, enabled)
        _remoteOpenConfirmEnabled.value = enabled
        notifySettingChanged(KEY_REMOTE_OPEN_CONFIRM)
    }

    // 保存远程下载目录
    fun setRemoteOpenDownloadDirectory(path: String) {
        settings.putString(KEY_REMOTE_OPEN_DOWNLOAD_DIR, path)
        _remoteOpenDownloadDirectory.value = path
    }

    fun setRootStartupRequestEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_ROOT_REQUEST_ON_STARTUP, enabled)
        _rootStartupRequestEnabled.value = enabled
        notifySettingChanged(KEY_ROOT_REQUEST_ON_STARTUP)
    }

    fun setAutoCaptureLogs(enabled: Boolean) {
        settings.putBoolean(KEY_AUTO_CAPTURE_LOGS, enabled)
        _autoCaptureLogs.value = enabled
        LogCapture.setEnabled(enabled)
        LogKit.i(AppStrings.ui_auto_capture_logs_arg0.format(arg0 = (enabled).toString()))
    }

    // EasyFileShare 设置方法
    // 保存快捷分享端口
    fun setEasyFileSharePort(port: Int) {
        settings.putInt(KEY_EASY_FILE_SHARE_PORT, port)
        _easyFileSharePort.value = port
        notifySettingChanged(KEY_EASY_FILE_SHARE_PORT)
    }

    // 保存快捷分享自动启动开关
    fun setEasyFileShareAutoStart(autoStart: Boolean) {
        if (autoStart && _easyFileShareAutoStartOnOpen.value) {
            setEasyFileShareAutoStartOnOpen(false)
        }
        settings.putBoolean(KEY_EASY_FILE_SHARE_AUTO_START, autoStart)
        _easyFileShareAutoStart.value = autoStart
        notifySettingChanged(KEY_EASY_FILE_SHARE_AUTO_START)
    }

    // 保存打开页面自动启动开关
    fun setEasyFileShareAutoStartOnOpen(autoStart: Boolean) {
        if (autoStart && _easyFileShareAutoStart.value) {
            setEasyFileShareAutoStart(false)
        }
        settings.putBoolean(KEY_EASY_FILE_SHARE_AUTO_START_ON_OPEN, autoStart)
        _easyFileShareAutoStartOnOpen.value = autoStart
        notifySettingChanged(KEY_EASY_FILE_SHARE_AUTO_START_ON_OPEN)
    }

    // 保存快捷分享默认路径
    fun setEasyFileShareSharePaths(paths: List<String>) {
        val distinctPaths = paths.distinct()
        settings.putString(KEY_EASY_FILE_SHARE_SHARE_PATHS, json.encodeToString(distinctPaths))
        _easyFileShareSharePaths.value = distinctPaths
        notifySettingChanged(KEY_EASY_FILE_SHARE_SHARE_PATHS)
    }

    // 保存退出自动停止开关
    fun setEasyFileShareAutoStopOnExit(autoStop: Boolean) {
        settings.putBoolean(KEY_EASY_FILE_SHARE_AUTO_STOP_ON_EXIT, autoStop)
        _easyFileShareAutoStopOnExit.value = autoStop
        notifySettingChanged(KEY_EASY_FILE_SHARE_AUTO_STOP_ON_EXIT)
    }

    // 保存自动同意开关
    fun setEasyFileShareAutoApprove(autoApprove: Boolean) {
        settings.putBoolean(KEY_EASY_FILE_SHARE_AUTO_APPROVE, autoApprove)
        _easyFileShareAutoApprove.value = autoApprove
        notifySettingChanged(KEY_EASY_FILE_SHARE_AUTO_APPROVE)
    }

    // 保存密码访问开关
    fun setEasyFileSharePasswordAccess(passwordAccess: Boolean) {
        settings.putBoolean(KEY_EASY_FILE_SHARE_PASSWORD_ACCESS, passwordAccess)
        _easyFileSharePasswordAccess.value = passwordAccess
        notifySettingChanged(KEY_EASY_FILE_SHARE_PASSWORD_ACCESS)
    }

    // 保存加密开关
    fun setEasyFileShareEncryption(encryption: Boolean) {
        settings.putBoolean(KEY_EASY_FILE_SHARE_ENCRYPTION, encryption)
        _easyFileShareEncryption.value = encryption
        notifySettingChanged(KEY_EASY_FILE_SHARE_ENCRYPTION)
    }

    // 保存隐藏文件开关
    fun setEasyFileShareHideFile(hideFile: Boolean) {
        settings.putBoolean(KEY_EASY_FILE_SHARE_HIDE_FILE, hideFile)
        _easyFileShareHideFile.value = hideFile
        notifySettingChanged(KEY_EASY_FILE_SHARE_HIDE_FILE)
    }

    // 保存点按发送开关
    fun setEasyFileShareTapToSend(enabled: Boolean) {
        settings.putBoolean(KEY_EASY_FILE_SHARE_TAP_TO_SEND, enabled)
        _easyFileShareTapToSend.value = enabled
        notifySettingChanged(KEY_EASY_FILE_SHARE_TAP_TO_SEND)
    }

    // 保存设备端隐藏文件开关
    fun setEasyFileShareDeviceHideFile(enabled: Boolean) {
        settings.putBoolean(KEY_EASY_FILE_SHARE_DEVICE_HIDE_FILE, enabled)
        _easyFileShareDeviceHideFile.value = enabled
        notifySettingChanged(KEY_EASY_FILE_SHARE_DEVICE_HIDE_FILE)
    }

    // 保存允许其他设备分享给我开关
    fun setEasyFileShareAllowDeviceShare(enabled: Boolean) {
        settings.putBoolean(KEY_EASY_FILE_SHARE_ALLOW_DEVICE_SHARE, enabled)
        _easyFileShareAllowDeviceShare.value = enabled
        notifySettingChanged(KEY_EASY_FILE_SHARE_ALLOW_DEVICE_SHARE)
    }

    // 保存链接分享上传开关
    fun setEasyFileShareAllowUpload(enabled: Boolean) {
        settings.putBoolean(KEY_EASY_FILE_SHARE_ALLOW_UPLOAD, enabled)
        _easyFileShareAllowUpload.value = enabled
        notifySettingChanged(KEY_EASY_FILE_SHARE_ALLOW_UPLOAD)
    }

    // 保存自动更新已授权链接分享设备文件列表开关
    fun setEasyFileShareAutoUpdateLinkShareFiles(enabled: Boolean) {
        settings.putBoolean(KEY_EASY_FILE_SHARE_AUTO_UPDATE_LINK_SHARE_FILES, enabled)
        _easyFileShareAutoUpdateLinkShareFiles.value = enabled
        notifySettingChanged(KEY_EASY_FILE_SHARE_AUTO_UPDATE_LINK_SHARE_FILES)
    }

    // 保存自动更新已分享设备文件列表开关
    fun setEasyFileShareAutoUpdateDeviceShareFiles(enabled: Boolean) {
        settings.putBoolean(KEY_EASY_FILE_SHARE_AUTO_UPDATE_DEVICE_SHARE_FILES, enabled)
        _easyFileShareAutoUpdateDeviceShareFiles.value = enabled
        notifySettingChanged(KEY_EASY_FILE_SHARE_AUTO_UPDATE_DEVICE_SHARE_FILES)
    }

    // 保存主题模式
    fun setThemeMode(mode: ThemeMode) {
        settings.putString(KEY_APPEARANCE_THEME_MODE, mode.name)
        _themeMode.value = mode
        notifySettingChanged(KEY_APPEARANCE_THEME_MODE)
    }

    fun setAppLanguageMode(mode: AppLanguageMode) {
        appLanguageController.setMode(mode)
    }

    fun synchronizePlatformLanguageMode(mode: AppLanguageMode) {
        appLanguageController.synchronizePlatformMode(mode)
    }

    fun refreshSystemLanguage() {
        appLanguageController.refreshSystemLanguage()
    }

    // 保存动态取色开关
    fun setDynamicColorEnabled(enabled: Boolean, sync: Boolean = true) {
        settings.putBoolean(KEY_APPEARANCE_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
        if (sync) {
            notifySettingChanged(KEY_APPEARANCE_DYNAMIC_COLOR)
        }
    }

    // 保存自定义颜色开关
    fun setCustomColorEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_APPEARANCE_CUSTOM_COLOR_ENABLED, enabled)
        _customColorEnabled.value = enabled
        notifySettingChanged(KEY_APPEARANCE_CUSTOM_COLOR_ENABLED)
        if (enabled) {
            setDynamicColorEnabled(false)
        }
    }

    // 保存自定义种子色
    fun setCustomSeedColor(value: String) {
        settings.putString(KEY_APPEARANCE_CUSTOM_PRIMARY, value)
        _customSeedColor.value = value
        notifySettingChanged(KEY_APPEARANCE_CUSTOM_PRIMARY)
    }

    // 解析分享路径配置
    private fun parseSharePaths(raw: String): List<String> {
        if (raw.isBlank()) {
            return emptyList()
        }
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrElse { emptyList() }
    }
}

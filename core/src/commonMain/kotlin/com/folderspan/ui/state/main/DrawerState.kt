package com.folderspan.ui.state.main

import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.component.KoinComponent

class DrawerState(private val settings: Settings) : KoinComponent {
    private val _isExpandBookmark: MutableStateFlow<Boolean> =
        MutableStateFlow(settings.getBoolean(SettingsUtils.KEY_DRAWER_EXPAND_BOOKMARK, true))
    val isExpandBookmark: StateFlow<Boolean> = _isExpandBookmark
    fun updateExpandBookmark(value: Boolean) {
        if (_isExpandBookmark.value == value) return
        _isExpandBookmark.value = value
        settings.putBoolean(SettingsUtils.KEY_DRAWER_EXPAND_BOOKMARK, value)
        SettingsUtils.notifySettingChanged(SettingsUtils.KEY_DRAWER_EXPAND_BOOKMARK)
    }

    private val _isExpandDevice: MutableStateFlow<Boolean> =
        MutableStateFlow(settings.getBoolean(SettingsUtils.KEY_DRAWER_EXPAND_DEVICE, true))
    val isExpandDevice: StateFlow<Boolean> = _isExpandDevice
    fun updateExpandDevice(value: Boolean) {
        if (_isExpandDevice.value == value) return
        _isExpandDevice.value = value
        settings.putBoolean(SettingsUtils.KEY_DRAWER_EXPAND_DEVICE, value)
        SettingsUtils.notifySettingChanged(SettingsUtils.KEY_DRAWER_EXPAND_DEVICE)
    }

    private val _isExpandWebRtc: MutableStateFlow<Boolean> =
        MutableStateFlow(settings.getBoolean(SettingsUtils.KEY_DRAWER_EXPAND_WEBRTC, true))
    val isExpandWebRtc: StateFlow<Boolean> = _isExpandWebRtc
    fun updateExpandWebRtc(value: Boolean) {
        if (_isExpandWebRtc.value == value) return
        _isExpandWebRtc.value = value
        settings.putBoolean(SettingsUtils.KEY_DRAWER_EXPAND_WEBRTC, value)
    }

    private val _isExpandShare: MutableStateFlow<Boolean> =
        MutableStateFlow(settings.getBoolean(SettingsUtils.KEY_DRAWER_EXPAND_SHARE, true))
    val isExpandShare: StateFlow<Boolean> = _isExpandShare
    fun updateExpandShare(value: Boolean) {
        if (_isExpandShare.value == value) return
        _isExpandShare.value = value
        settings.putBoolean(SettingsUtils.KEY_DRAWER_EXPAND_SHARE, value)
        SettingsUtils.notifySettingChanged(SettingsUtils.KEY_DRAWER_EXPAND_SHARE)
    }

    private val _isExpandNetwork: MutableStateFlow<Boolean> =
        MutableStateFlow(settings.getBoolean(SettingsUtils.KEY_DRAWER_EXPAND_NETWORK, true))
    val isExpandNetwork: StateFlow<Boolean> = _isExpandNetwork
    fun updateExpandNetwork(value: Boolean) {
        if (_isExpandNetwork.value == value) return
        _isExpandNetwork.value = value
        settings.putBoolean(SettingsUtils.KEY_DRAWER_EXPAND_NETWORK, value)
        SettingsUtils.notifySettingChanged(SettingsUtils.KEY_DRAWER_EXPAND_NETWORK)
    }

    private val _isExpandSync: MutableStateFlow<Boolean> =
        MutableStateFlow(settings.getBoolean(SettingsUtils.KEY_DRAWER_EXPAND_SYNC, true))
    val isExpandSync: StateFlow<Boolean> = _isExpandSync
    fun updateExpandSync(value: Boolean) {
        if (_isExpandSync.value == value) return
        _isExpandSync.value = value
        settings.putBoolean(SettingsUtils.KEY_DRAWER_EXPAND_SYNC, value)
        SettingsUtils.notifySettingChanged(SettingsUtils.KEY_DRAWER_EXPAND_SYNC)
    }

    private val _isShowDevice: MutableStateFlow<Boolean> =
        MutableStateFlow(settings.getBoolean(SettingsUtils.KEY_DRAWER_SHOW_DEVICE, true))
    val isShowDevice: StateFlow<Boolean> = _isShowDevice
    fun updateShowDevice(value: Boolean) {
        if (_isShowDevice.value == value) return
        _isShowDevice.value = value
        settings.putBoolean(SettingsUtils.KEY_DRAWER_SHOW_DEVICE, value)
        SettingsUtils.notifySettingChanged(SettingsUtils.KEY_DRAWER_SHOW_DEVICE)
    }

    private val _isShowWebRtc: MutableStateFlow<Boolean> =
        MutableStateFlow(settings.getBoolean(SettingsUtils.KEY_DRAWER_SHOW_WEBRTC, false))
    val isShowWebRtc: StateFlow<Boolean> = _isShowWebRtc
    fun updateShowWebRtc(value: Boolean) {
        if (_isShowWebRtc.value == value) return
        _isShowWebRtc.value = value
        settings.putBoolean(SettingsUtils.KEY_DRAWER_SHOW_WEBRTC, value)
    }

    private val _isShowNetwork: MutableStateFlow<Boolean> =
        MutableStateFlow(settings.getBoolean(SettingsUtils.KEY_DRAWER_SHOW_NETWORK, true))
    val isShowNetwork: StateFlow<Boolean> = _isShowNetwork
    fun updateShowNetwork(value: Boolean) {
        if (_isShowNetwork.value == value) return
        _isShowNetwork.value = value
        settings.putBoolean(SettingsUtils.KEY_DRAWER_SHOW_NETWORK, value)
        SettingsUtils.notifySettingChanged(SettingsUtils.KEY_DRAWER_SHOW_NETWORK)
    }

    private val _isShowSync: MutableStateFlow<Boolean> =
        MutableStateFlow(settings.getBoolean(SettingsUtils.KEY_DRAWER_SHOW_SYNC, true))
    val isShowSync: StateFlow<Boolean> = _isShowSync
    fun updateShowSync(value: Boolean) {
        if (_isShowSync.value == value) return
        _isShowSync.value = value
        settings.putBoolean(SettingsUtils.KEY_DRAWER_SHOW_SYNC, value)
        SettingsUtils.notifySettingChanged(SettingsUtils.KEY_DRAWER_SHOW_SYNC)
    }

    private val _isFileGridView: MutableStateFlow<Boolean> =
        MutableStateFlow(settings.getBoolean(SettingsUtils.KEY_FILE_VIEW_GRID, false))
    val isFileGridView: StateFlow<Boolean> = _isFileGridView
    fun updateFileGridView(value: Boolean) {
        if (_isFileGridView.value == value) return
        _isFileGridView.value = value
        settings.putBoolean(SettingsUtils.KEY_FILE_VIEW_GRID, value)
        SettingsUtils.notifySettingChanged(SettingsUtils.KEY_FILE_VIEW_GRID)
    }

    fun reloadFromSettings() {
        _isExpandBookmark.value = settings.getBoolean(SettingsUtils.KEY_DRAWER_EXPAND_BOOKMARK, true)
        _isExpandDevice.value = settings.getBoolean(SettingsUtils.KEY_DRAWER_EXPAND_DEVICE, true)
        _isExpandWebRtc.value = settings.getBoolean(SettingsUtils.KEY_DRAWER_EXPAND_WEBRTC, true)
        _isExpandShare.value = settings.getBoolean(SettingsUtils.KEY_DRAWER_EXPAND_SHARE, true)
        _isExpandNetwork.value = settings.getBoolean(SettingsUtils.KEY_DRAWER_EXPAND_NETWORK, true)
        _isExpandSync.value = settings.getBoolean(SettingsUtils.KEY_DRAWER_EXPAND_SYNC, true)
        _isShowDevice.value = settings.getBoolean(SettingsUtils.KEY_DRAWER_SHOW_DEVICE, true)
        _isShowWebRtc.value = settings.getBoolean(SettingsUtils.KEY_DRAWER_SHOW_WEBRTC, false)
        _isShowNetwork.value = settings.getBoolean(SettingsUtils.KEY_DRAWER_SHOW_NETWORK, true)
        _isShowSync.value = settings.getBoolean(SettingsUtils.KEY_DRAWER_SHOW_SYNC, true)
        _isFileGridView.value = settings.getBoolean(SettingsUtils.KEY_FILE_VIEW_GRID, false)
    }
}

package com.folderspan.utils

object SettingsChangeSync {
    private var handler: ((String) -> Unit)? = null

    fun setHandler(handler: ((String) -> Unit)?) {
        this.handler = handler
    }

    fun onSettingChanged(key: String) {
        if (SettingsUtils.syncableSettingOrNull(key) == null) return
        handler?.invoke(key)
    }
}

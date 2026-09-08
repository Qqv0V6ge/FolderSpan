package com.folderspan.utils

object SyncSnapshotChangeNotifier {
    private var bookmarkHandler: (() -> Unit)? = null
    private var favoriteHandler: (() -> Unit)? = null
    private var editorSearchHistoryHandler: (() -> Unit)? = null
    private var deviceConfigurationHandler: (() -> Unit)? = null
    private var roleConfigurationHandler: (() -> Unit)? = null
    private var networkConfigurationHandler: (() -> Unit)? = null
    private var webRtcConfigurationHandler: (() -> Unit)? = null
    private var syncTasksConfigurationHandler: (() -> Unit)? = null

    fun setBookmarkHandler(handler: (() -> Unit)?) {
        bookmarkHandler = handler
    }

    fun setFavoriteHandler(handler: (() -> Unit)?) {
        favoriteHandler = handler
    }

    fun setEditorSearchHistoryHandler(handler: (() -> Unit)?) {
        editorSearchHistoryHandler = handler
    }

    fun setDeviceConfigurationHandler(handler: (() -> Unit)?) {
        deviceConfigurationHandler = handler
    }

    fun setRoleConfigurationHandler(handler: (() -> Unit)?) {
        roleConfigurationHandler = handler
    }

    fun setNetworkConfigurationHandler(handler: (() -> Unit)?) {
        networkConfigurationHandler = handler
    }

    fun setWebRtcConfigurationHandler(handler: (() -> Unit)?) {
        webRtcConfigurationHandler = handler
    }

    fun setSyncTasksConfigurationHandler(handler: (() -> Unit)?) {
        syncTasksConfigurationHandler = handler
    }

    fun onBookmarksChanged() {
        bookmarkHandler?.invoke()
    }

    fun onFavoritesChanged() {
        favoriteHandler?.invoke()
    }

    fun onEditorSearchHistoryChanged() {
        editorSearchHistoryHandler?.invoke()
    }

    fun onDeviceConfigurationChanged() {
        deviceConfigurationHandler?.invoke()
    }

    fun onRoleConfigurationChanged() {
        roleConfigurationHandler?.invoke()
    }

    fun onNetworkConfigurationChanged() {
        networkConfigurationHandler?.invoke()
    }

    fun onWebRtcConfigurationChanged() {
        webRtcConfigurationHandler?.invoke()
    }

    fun onSyncTasksConfigurationChanged() {
        syncTasksConfigurationHandler?.invoke()
    }
}

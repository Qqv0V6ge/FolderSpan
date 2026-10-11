package com.folderspan.notification

object IosNotificationBootstrap {
    fun initializeEventBridge() {
        LocalNotifier.initialize(askPermissionOnStart = false)
    }
}

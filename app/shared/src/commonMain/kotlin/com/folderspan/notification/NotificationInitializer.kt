package com.folderspan.notification

fun initializeNotifications() {
    LocalNotifier.initialize(askPermissionOnStart = false)
}

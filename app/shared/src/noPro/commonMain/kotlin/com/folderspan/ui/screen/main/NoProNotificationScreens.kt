package com.folderspan.ui.screen.main

import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.state.main.Notification

class NotificationScreen internal constructor(initialTab: NotificationTab = NotificationTab.Local) : AppScreenRoute {
    internal var selectedTab by mutableStateOf(initialTab)

    @Composable
    override fun Content() { LocalNotificationScreen().Content() }
}

data class NotificationDetailScreen(val notification: Notification) : AppScreenRoute {
    @Composable
    override fun Content() { DeviceNotificationDetailContent(notification) }
}

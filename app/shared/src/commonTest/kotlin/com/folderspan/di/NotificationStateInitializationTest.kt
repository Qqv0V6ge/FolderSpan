package com.folderspan.di

import androidx.compose.runtime.snapshots.Snapshot
import com.folderspan.notification.NotificationFactoryConfig
import com.folderspan.notification.RequestNotificationDispatcher
import com.folderspan.notification.RequestNotificationFactory
import com.folderspan.ui.state.main.NotificationState
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationStateInitializationTest {
    @Test
    fun connectionRequestBeforeFirstPageReadPreservesSnapshotVisibility() {
        val application = koinApplication { modules(commonScreenModule) }
        val pageSnapshot = Snapshot.takeMutableSnapshot()
        try {
            val state = application.koin.get<NotificationState>()
            val bundle = RequestNotificationFactory.buildDeviceConnectNotification(
                deviceId = "startup-device",
                deviceName = "Startup device",
                timestamp = 1000L,
                config = NotificationFactoryConfig(sendSystemNotification = false),
            )

            val result = RequestNotificationDispatcher.post(state, bundle)

            assertTrue(result.inAppStored)
            pageSnapshot.enter {
                assertEquals(0L, state.revision)
                assertTrue(state.notifications.isEmpty())
                assertTrue(state.ignoredBannerIds.isEmpty())
                assertNull(state.pendingOpenRequestId)
            }
            assertEquals(1L, state.revision)
            assertEquals(listOf(bundle.notification), state.notifications.toList())
        } finally {
            pageSnapshot.dispose()
            application.close()
        }
    }
}

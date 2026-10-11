package com.folderspan.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLocalNotificationBackendTest {
    @Test
    fun unavailableSystemNotifierReportsFailureWithoutPosting() {
        val gateway = FakeDesktopSystemNotificationGateway(isAvailable = false)
        val backend = DesktopLocalNotificationBackend(gateway)

        val posted = backend.notify(
            id = 7,
            title = "Title",
            body = "Body",
            payloadData = mapOf("request_id" to "request-7"),
            onClick = {},
        )

        assertFalse(posted)
        assertEquals(0, gateway.posts.size)
    }

    @Test
    fun nativeNotificationClickBringsAppForwardAndDispatchesPayload() {
        val gateway = FakeDesktopSystemNotificationGateway()
        var foregroundRequests = 0
        val clickedPayloads = mutableListOf<NotificationPayload>()
        val payload = mapOf("request_id" to "request-8")
        val backend = DesktopLocalNotificationBackend(gateway) { onClick ->
            foregroundRequests++
            onClick()
        }

        assertTrue(
            backend.notify(
                id = 8,
                title = "Incoming request",
                body = "Open FolderSpan",
                payloadData = payload,
                onClick = clickedPayloads::add,
            )
        )

        gateway.posts.single().onActivated()
        gateway.posts.single().onActivated()

        assertEquals(1, foregroundRequests)
        assertEquals(listOf(payload), clickedPayloads)
        backend.remove(8)
        assertEquals(0, gateway.handles.single().dismissCount)
    }

    @Test
    fun nativeNotificationActionBringsAppForwardAndDispatchesActionPayloadOnce() {
        val gateway = FakeDesktopSystemNotificationGateway()
        var foregroundRequests = 0
        val clickedPayloads = mutableListOf<NotificationPayload>()
        val payload = mapOf(
            "request_id" to "request-action",
            RequestNotificationFactory.META_REQUEST_KIND to RequestNotificationKind.DeviceShare.name,
        )
        val expectedActions = notificationActionsFor(payload)
        val backend = DesktopLocalNotificationBackend(gateway) { onClick ->
            foregroundRequests++
            onClick()
        }

        assertTrue(
            backend.notify(
                id = 14,
                title = "Incoming files",
                body = "Choose an action",
                payloadData = payload,
                actions = expectedActions,
                onClick = {},
                onAction = { actionId, actionPayload ->
                    clickedPayloads += actionPayload + (NotificationActionKeys.ACTION_ID to actionId)
                },
            )
        )

        val nativeActions = gateway.posts.single().actions
        assertEquals(
            expectedActions.map { action -> action.title },
            nativeActions.map { action -> action.title },
        )

        nativeActions.first().onClick()
        nativeActions.first().onClick()
        gateway.posts.single().onActivated()

        assertEquals(1, foregroundRequests)
        assertEquals(
            listOf(payload + (NotificationActionKeys.ACTION_ID to NotificationActionKeys.ACTION_SAVE)),
            clickedPayloads,
        )
    }

    @Test
    fun reusingIdDismissesPreviousNativeNotification() {
        val gateway = FakeDesktopSystemNotificationGateway()
        val backend = DesktopLocalNotificationBackend(gateway)

        assertTrue(backend.notify(9, "First", "Body", emptyMap()) {})
        assertTrue(backend.notify(9, "Second", "Body", emptyMap()) {})

        assertEquals(1, gateway.handles.first().dismissCount)
        assertEquals(0, gateway.handles.last().dismissCount)
        assertEquals(listOf("First", "Second"), gateway.posts.map { it.title })
    }

    @Test
    fun explicitRemoveDismissesNativeNotification() {
        val gateway = FakeDesktopSystemNotificationGateway()
        val backend = DesktopLocalNotificationBackend(gateway)

        assertTrue(backend.notify(10, "Title", "Body", emptyMap()) {})
        backend.remove(10)
        backend.remove(10)

        assertEquals(1, gateway.handles.single().dismissCount)
    }

    @Test
    fun failedNativePostReportsFailure() {
        val gateway = FakeDesktopSystemNotificationGateway(postSucceeds = false)
        val backend = DesktopLocalNotificationBackend(gateway)

        assertFalse(backend.notify(11, "Title", "Body", emptyMap()) {})
        assertEquals(1, gateway.posts.size)
    }

    @Test
    fun nativeDismissAndAsyncFailureReleaseTrackedHandle() {
        val gateway = FakeDesktopSystemNotificationGateway()
        val backend = DesktopLocalNotificationBackend(gateway)

        assertTrue(backend.notify(12, "Dismissed", "Body", emptyMap()) {})
        gateway.posts.last().onDismissed()
        backend.remove(12)

        assertTrue(backend.notify(13, "Failed later", "Body", emptyMap()) {})
        gateway.posts.last().onFailed()
        backend.remove(13)

        assertTrue(gateway.handles.all { handle -> handle.dismissCount == 0 })
    }
}

private class FakeDesktopSystemNotificationGateway(
    private val isAvailable: Boolean = true,
    private val postSucceeds: Boolean = true,
) : DesktopSystemNotificationGateway {
    val posts = mutableListOf<DesktopSystemNotification>()
    val handles = mutableListOf<FakeDesktopSystemNotificationHandle>()

    override fun initialize() = Unit

    override fun isAvailable(): Boolean = isAvailable

    override fun post(notification: DesktopSystemNotification): DesktopSystemNotificationHandle? {
        posts += notification
        if (!postSucceeds) return null
        return FakeDesktopSystemNotificationHandle().also(handles::add)
    }
}

private class FakeDesktopSystemNotificationHandle : DesktopSystemNotificationHandle {
    var dismissCount = 0

    override fun dismiss() {
        dismissCount++
    }
}

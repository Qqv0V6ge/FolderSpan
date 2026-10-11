package com.folderspan.ui.state.file

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.notification.RequestNotificationFactory
import com.folderspan.notification.RequestNotificationKind
import com.folderspan.ui.state.main.NotificationState
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileShareStateJvmTest {
    @Test
    fun approvingUploadRequestPreservesBrowseAuthorization() = runBlocking {
        withTestKoin { notificationState ->
            val state = FileShareState()
            val device = device()
            state.authorizeLinkShareDevice(
                device = device,
                allowHidden = false,
                allowUpload = false,
                files = listOf(file())
            )

            assertEquals(LinkShareUploadPermissionRequestResult.Accepted, state.requestLinkShareUploadPermission(device))
            assertTrue(state.pendingLinkShareUploadDevices.any { item -> item.id == device.id })
            val notification = notificationState.notifications.single { item ->
                item.metadata[RequestNotificationFactory.META_REQUEST_ID] ==
                    RequestNotificationFactory.requestId(RequestNotificationKind.LinkShareUpload, device.id)
            }
            assertEquals(
                RequestNotificationKind.LinkShareUpload.name,
                notification.metadata[RequestNotificationFactory.META_REQUEST_KIND]
            )

            val fingerprint = ShareTokenFingerprint(clientIp = "127.0.0.1", userAgent = "FolderSpan Test")
            val session = state.issueLinkShareSession(
                clientId = device.id,
                fingerprint = fingerprint,
                allowHidden = false,
                allowUpload = false,
                files = listOf(file()),
            )
            assertTrue(state.approveLinkShareUploadDevice(device.id))

            val access = assertNotNull(state.getAuthorizedLinkShareDevice(device.id))
            assertTrue(access.allowUpload)
            val resolved = assertNotNull(
                state.resolveLinkShareSession(
                    token = session.token,
                    clientId = device.id,
                    fingerprint = fingerprint,
                )
            )
            assertTrue(resolved.access.allowUpload)
            assertTrue(state.pendingLinkShareUploadDevices.none { item -> item.id == device.id })
            assertTrue(state.rejectedLinkShareUploadDevices.none { item -> item.id == device.id })
        }
    }

    @Test
    fun rejectingUploadRequestDoesNotRemoveBrowseAuthorization() = runBlocking {
        withTestKoin {
            val state = FileShareState()
            val device = device()
            state.authorizeLinkShareDevice(
                device = device,
                allowHidden = false,
                allowUpload = false,
                files = listOf(file())
            )

            assertEquals(LinkShareUploadPermissionRequestResult.Accepted, state.requestLinkShareUploadPermission(device))
            val fingerprint = ShareTokenFingerprint(clientIp = "127.0.0.1", userAgent = "FolderSpan Test")
            val session = state.issueLinkShareSession(
                clientId = device.id,
                fingerprint = fingerprint,
                allowHidden = false,
                allowUpload = false,
                files = listOf(file()),
            )
            assertTrue(state.rejectLinkShareUploadDevice(device.id))

            val access = assertNotNull(state.getAuthorizedLinkShareDevice(device.id))
            assertFalse(access.allowUpload)
            val resolved = assertNotNull(
                state.resolveLinkShareSession(
                    token = session.token,
                    clientId = device.id,
                    fingerprint = fingerprint,
                )
            )
            assertFalse(resolved.access.allowUpload)
            assertTrue(state.pendingLinkShareUploadDevices.none { item -> item.id == device.id })
            assertTrue(state.rejectedLinkShareUploadDevices.any { item -> item.id == device.id })

            state.removeRejectedLinkShareUploadDevice(device.id)

            assertNotNull(state.getAuthorizedLinkShareDevice(device.id))
            assertTrue(state.rejectedLinkShareUploadDevices.none { item -> item.id == device.id })
        }
    }

    @Test
    fun syncingAuthorizedLinkShareFilesUpdatesCurrentSessionsAndPreservesPermissions() = runBlocking {
        withTestKoin {
            val state = FileShareState()
            val device = device()
            val originalFile = file(name = "original.txt")
            val updatedFile = file(name = "updated.txt")
            val fingerprint = ShareTokenFingerprint(
                clientIp = "127.0.0.1",
                userAgent = "FolderSpan Test"
            )
            state.updateLinkShareDefaults(
                allowHidden = false,
                allowUpload = false,
                files = listOf(originalFile)
            )
            state.authorizeLinkShareDevice(
                device = device,
                allowHidden = true,
                allowUpload = true,
                files = listOf(originalFile)
            )
            val session = state.issueLinkShareSession(
                clientId = device.id,
                fingerprint = fingerprint,
                allowHidden = true,
                allowUpload = true,
                files = listOf(originalFile)
            )

            state.syncAuthorizedLinkShareFiles(listOf(updatedFile))

            val access = assertNotNull(state.getAuthorizedLinkShareDevice(device.id))
            assertTrue(access.allowHidden)
            assertTrue(access.allowUpload)
            assertEquals(listOf(updatedFile), access.files)

            val resolvedSession = assertNotNull(
                state.resolveLinkShareSession(
                    token = session.token,
                    clientId = device.id,
                    fingerprint = fingerprint
                )
            )
            assertEquals(session.token, resolvedSession.token)
            assertTrue(resolvedSession.access.allowHidden)
            assertTrue(resolvedSession.access.allowUpload)
            assertEquals(listOf(updatedFile), resolvedSession.files)

            val defaults = state.resolveLinkShareDefaults()
            assertFalse(defaults.allowHidden)
            assertFalse(defaults.allowUpload)
            assertEquals(listOf(updatedFile), defaults.files)
        }
    }

    @Test
    fun syncingShareToDeviceFilesUpdatesEveryDeviceAndCanClearAccess() {
        val state = FileShareState()
        val originalFile = file(name = "original.txt")
        val updatedFile = file(name = "updated.txt")
        state.shareToDevices["device-a"] = true to listOf(originalFile)
        state.shareToDevices["device-b"] = false to listOf(originalFile)

        state.syncShareToDeviceFiles(listOf(updatedFile))

        assertEquals(true, state.shareToDevices.getValue("device-a").first)
        assertEquals(false, state.shareToDevices.getValue("device-b").first)
        assertEquals(listOf(updatedFile), state.shareToDevices.getValue("device-a").second)
        assertEquals(listOf(updatedFile), state.shareToDevices.getValue("device-b").second)

        state.syncShareToDeviceFiles(emptyList())

        assertTrue(state.shareToDevices.getValue("device-a").second.isEmpty())
        assertTrue(state.shareToDevices.getValue("device-b").second.isEmpty())
    }

    @Test
    fun clearingShareToDeviceRemovesFilesStatusAndMessage() {
        val state = FileShareState()
        state.shareToDevices["device-a"] = true to listOf(file())
        state.sendFile["device-a"] = FileShareStatus.ERROR
        state.sendFileMessage["device-a"] = "disconnected"
        state.shareToDevices["device-b"] = false to listOf(file())
        state.sendFile["device-b"] = FileShareStatus.COMPLETED

        state.clearShareToDevice("device-a")

        assertFalse(state.shareToDevices.containsKey("device-a"))
        assertFalse(state.sendFile.containsKey("device-a"))
        assertFalse(state.sendFileMessage.containsKey("device-a"))
        assertEquals(false, state.shareToDevices.getValue("device-b").first)
        assertEquals(FileShareStatus.COMPLETED, state.sendFile["device-b"])
    }

    @Test
    fun manualApprovalCanOnlyBeResolvedByTheOriginalRequestFingerprint() = runBlocking {
        withTestKoin {
            val state = FileShareState()
            val device = device(id = "manual-client")
            val approvedFingerprint = ShareTokenFingerprint(
                clientIp = "192.168.1.20",
                userAgent = "FolderSpan Browser",
            )
            state.updateLinkShareDefaults(allowHidden = false, files = emptyList())
            state.addPendingLinkShareDevice(
                device = device,
                sourceHost = approvedFingerprint.clientIp,
                fingerprint = approvedFingerprint,
            )

            state.approveLinkShareDevice(device.id)

            assertNotNull(state.resolveAuthorizedLinkShareDevice(device.id, approvedFingerprint))
            assertEquals(
                null,
                state.resolveAuthorizedLinkShareDevice(
                    deviceId = device.id,
                    fingerprint = approvedFingerprint.copy(userAgent = "Different Browser"),
                ),
            )
        }
    }

    @Test
    fun pendingLinkShareDevicesCapAtMaxAndDedupeById() = runBlocking {
        withTestKoin {
            val state = FileShareState()
            repeat(MAX_PENDING_LINK_SHARE_DEVICES + 16) { index ->
                state.addPendingLinkShareDevice(device(id = "client-$index"))
            }
            assertTrue(state.pendingLinkShareDevices.size <= MAX_PENDING_LINK_SHARE_DEVICES)
        }
    }

    @Test
    fun pendingLinkShareDevicesDedupeSameClientId() = runBlocking {
        withTestKoin {
            val state = FileShareState()
            val device = device(id = "same-id")
            repeat(8) { state.addPendingLinkShareDevice(device.copy()) }
            assertEquals(1, state.pendingLinkShareDevices.count { item -> item.id == "same-id" })
        }
    }

    @Test
    fun pendingLinkShareDevicesCapPerSource() = runBlocking {
        withTestKoin {
            val state = FileShareState()
            repeat(MAX_PENDING_LINK_SHARE_DEVICES_PER_SOURCE + 4) { index ->
                state.addPendingLinkShareDevice(
                    device = device(id = "src-$index"),
                    sourceHost = "192.168.1.20",
                )
            }
            assertEquals(MAX_PENDING_LINK_SHARE_DEVICES_PER_SOURCE, state.pendingLinkShareDevices.size)
        }
    }

    @Test
    fun authorizeLinkShareDeviceTrimsToMaxDistinctIds() = runBlocking {
        withTestKoin {
            val state = FileShareState()
            repeat(MAX_AUTHORIZED_LINK_SHARE_DEVICES + 8) { index ->
                state.authorizeLinkShareDevice(
                    device = device(id = "auto-$index"),
                    allowHidden = false,
                    files = emptyList(),
                    revokeExistingSessions = false,
                )
            }
            assertTrue(state.authorizedLinkShareDevices.size <= MAX_AUTHORIZED_LINK_SHARE_DEVICES)
        }
    }

    @Test
    fun linkShareNotificationUpsertDoesNotGrowWithoutBound() = runBlocking {
        withTestKoin { notificationState ->
            val state = FileShareState()
            repeat(MAX_LINK_SHARE_NOTIFICATIONS + 16) { index ->
                state.addPendingLinkShareDevice(device(id = "notify-$index"))
            }
            val linkShareCount = notificationState.notifications.count { item ->
                item.metadata[RequestNotificationFactory.META_REQUEST_KIND] ==
                    RequestNotificationKind.LinkShare.name
            }
            assertTrue(linkShareCount <= MAX_LINK_SHARE_NOTIFICATIONS)
            assertTrue(state.pendingLinkShareDevices.size <= MAX_PENDING_LINK_SHARE_DEVICES)
        }
    }

    @Test
    fun deviceRequestLogIsIndexedByIdAndCapped() = runBlocking {
        withTestKoin {
            val state = FileShareState()
            repeat(MAX_LINK_SHARE_DEVICE_REQUEST_LOGS + 8) { index ->
                state.deviceRequestLogFor(device(id = "log-$index"))
            }
            assertTrue(state.deviceRequestLog.size <= MAX_LINK_SHARE_DEVICE_REQUEST_LOGS)
            assertTrue(state.deviceRequestLog.keys.all { item -> item.startsWith("log-") })
        }
    }

    private suspend fun withTestKoin(block: suspend (NotificationState) -> Unit) {
        stopKoin()
        val notificationState = NotificationState()
        startKoin {
            modules(
                module {
                    single { notificationState }
                }
            )
        }
        try {
            block(notificationState)
        } finally {
            stopKoin()
        }
    }

    private fun device(id: String = "client-state-test"): Device {
        return Device(id, "Browser", "/", mutableMapOf(), DeviceType.JS, "")
    }

    private fun file(name: String = "shared.txt"): FileSimpleInfo {
        return FileSimpleInfo(
            name = name,
            isDirectory = false,
            isHidden = false,
            path = "/$name",
            mineType = "text/plain",
            size = 1L,
            createdDate = 0L,
            updatedDate = 0L,
        )
    }
}

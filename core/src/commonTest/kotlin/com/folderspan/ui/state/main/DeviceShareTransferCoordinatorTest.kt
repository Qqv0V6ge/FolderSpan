package com.folderspan.ui.state.main

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareProtocol
import com.folderspan.data.main.share.ShareSession
import com.folderspan.notification.DeviceShareRequestAction
import com.folderspan.service.data.SocketDevice
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceShareTransferCoordinatorTest {
    @Test
    fun saveCopiesThroughRuntimeShareSessionAndDisconnectsWithoutOpeningDesk() = runTest {
        val connected = connectedShare()
        val copiedProtocols = mutableListOf<FileProtocol>()
        var disconnected = false
        val coordinator = DeviceShareTransferCoordinator(
            connectShare = { connected },
            loadSharedEntries = {
                listOf(file("/shared/report.txt", FileProtocol.Share, connected.id))
            },
            copyEntriesToLocal = { share, entries, savePath ->
                assertEquals(connected.id, share.id)
                assertEquals("/downloads", savePath)
                copiedProtocols += entries.map { entry -> entry.protocol }
            },
            openShareDesk = { _, _ -> error("Save must not open a desk") },
            disconnectShare = { disconnected = true },
        )

        coordinator.execute(socketDevice(), DeviceShareRequestAction.Save, "/downloads")

        assertEquals(listOf(FileProtocol.Share), copiedProtocols)
        assertTrue(disconnected)
    }

    @Test
    fun savePastesAllSharedEntriesInOneBatchThenDisconnects() = runTest {
        val connected = connectedShare()
        val copiedPaths = mutableListOf<List<String>>()
        var disconnected = false
        val coordinator = DeviceShareTransferCoordinator(
            connectShare = { connected },
            loadSharedEntries = {
                listOf(
                    file("/shared/report.txt", FileProtocol.Share, connected.id),
                    file("/shared/photos", FileProtocol.Share, connected.id, isDirectory = true),
                )
            },
            copyEntriesToLocal = { share, entries, savePath ->
                assertEquals(connected.id, share.id)
                assertEquals("/downloads", savePath)
                copiedPaths += entries.map { entry -> entry.path }
            },
            openShareDesk = { _, _ -> error("Save must not open a desk") },
            disconnectShare = { disconnected = true },
        )

        coordinator.execute(socketDevice(), DeviceShareRequestAction.Save, "/downloads")

        assertEquals(listOf(listOf("/shared/report.txt", "/shared/photos")), copiedPaths)
        assertTrue(disconnected)
    }

    @Test
    fun viewOpensShareDeskAndKeepsSessionInDrawer() = runTest {
        val connected = connectedShare()
        var openedProtocol: FileProtocol? = null
        var openedEntries: List<FileSimpleInfo> = emptyList()
        var disconnected = false
        val coordinator = DeviceShareTransferCoordinator(
            connectShare = { connected },
            loadSharedEntries = { listOf(file("/shared/report.txt", FileProtocol.Share, connected.id)) },
            copyEntriesToLocal = { _, _, _ -> error("View must not copy") },
            openShareDesk = { _, entries ->
                openedProtocol = FileProtocol.Share
                openedEntries = entries
            },
            disconnectShare = { disconnected = true },
        )

        coordinator.execute(socketDevice(), DeviceShareRequestAction.View, "")

        assertEquals(FileProtocol.Share, openedProtocol)
        assertEquals(listOf("/shared/report.txt"), openedEntries.map { entry -> entry.path })
        assertFalse(disconnected)
    }

    @Test
    fun failedSaveStillDisconnectsShareSession() = runTest {
        var disconnected = false
        val coordinator = DeviceShareTransferCoordinator(
            connectShare = { connectedShare() },
            loadSharedEntries = { listOf(file("/shared/report.txt", FileProtocol.Share, it.id)) },
            copyEntriesToLocal = { _, _, _ -> error("copy failed") },
            openShareDesk = { _, _ -> },
            disconnectShare = { disconnected = true },
        )

        val result = runCatching {
            coordinator.execute(socketDevice(), DeviceShareRequestAction.AutoSave, "/downloads")
        }

        assertTrue(result.isFailure)
        assertTrue(disconnected)
    }

    private fun connectedShare(): Share {
        return Share(
            id = "sender",
            name = "Sender",
            pathSeparator = "/",
            type = DeviceType.JVM,
            protocol = ShareProtocol.Remote,
            session = object : ShareSession {
                override val isActive = true

                override suspend fun list(path: String): Result<List<FileSimpleInfo>> =
                    Result.success(emptyList())

                override suspend fun readBytes(
                    path: String,
                    startOffset: Long,
                    endOffset: Long,
                ): Result<ByteArray> = Result.success(ByteArray(0))

                override suspend fun readStream(
                    path: String,
                    startOffset: Long,
                    endOffset: Long,
                    onChunk: suspend (ByteArray) -> Unit,
                ): Result<Boolean> = Result.success(true)

                override fun disconnect(): Boolean = true
            },
        )
    }

    private fun socketDevice(): SocketDevice {
        return SocketDevice(
            id = "sender",
            name = "Sender",
            pathSeparator = "/",
            host = "10.0.0.2",
            port = 12040,
            type = DeviceType.JVM,
            httpsPort = 12040,
            tlsFingerprintSha256 = "AA:BB:CC",
            shareConnectNonce = "share-nonce",
        )
    }

    private fun file(
        path: String,
        protocol: FileProtocol,
        protocolId: String,
        isDirectory: Boolean = false,
    ): FileSimpleInfo {
        return FileSimpleInfo(
            name = path.substringAfterLast('/'),
            isDirectory = isDirectory,
            isHidden = false,
            path = path,
            mineType = "text/plain",
            size = 1L,
            createdDate = 0L,
            updatedDate = 0L,
            protocol = protocol,
            protocolId = protocolId,
        )
    }
}

package com.folderspan.ui.state.file

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkProtocol
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareProtocol
import com.folderspan.data.main.share.ShareSession
import com.folderspan.data.main.share.buildSystemShareDesk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileFilterIgnoreMenuTest {
    @Test
    fun currentDirectoryIgnoreFileCreatesMenuItem() {
        val items = buildIgnoreFileMenuItems(configuredIgnoreFiles = emptyList()) { fileName ->
            fileName == ".gitignore"
        }

        assertEquals(listOf(".gitignore"), items.map { item -> item.fileName })
        assertTrue(items.single().exists)
        assertFalse(items.single().enabled)
    }

    @Test
    fun configuredIgnoreFileCreatesEnabledMenuItemWhenFileIsMissing() {
        val items = buildIgnoreFileMenuItems(configuredIgnoreFiles = listOf(".gitignore")) {
            false
        }

        assertEquals(listOf(".gitignore"), items.map { item -> item.fileName })
        assertFalse(items.single().exists)
        assertTrue(items.single().enabled)
    }

    @Test
    fun ignoreMenuSupportsFileOperationSourceProtocols() {
        assertTrue(supportsIgnoreFileUi(Local(pathSeparator = "/")))
        assertTrue(
            supportsIgnoreFileUi(
                Device(
                    id = "device",
                    name = "Device",
                    pathSeparator = "/",
                    host = mutableMapOf(),
                    type = DeviceType.JVM,
                    token = "",
                )
            )
        )
        assertTrue(
            supportsIgnoreFileUi(
                Share(
                    id = "share",
                    name = "Share",
                    pathSeparator = "/",
                    session = TestShareSession,
                    protocol = ShareProtocol.Remote,
                    type = DeviceType.JVM,
                )
            )
        )
        assertTrue(
            supportsIgnoreFileUi(
                Network(
                    name = "Network",
                    pathSeparator = "/",
                    protocol = NetworkProtocol.SMB.name,
                    host = "localhost",
                    username = "",
                    password = "",
                )
            )
        )
    }

    @Test
    fun remoteShareDoesNotExposeFavorite() {
        val share = Share(
            id = "share",
            name = "Share",
            pathSeparator = "/",
            session = TestShareSession,
            protocol = ShareProtocol.Remote,
            type = DeviceType.JVM,
        )

        assertFalse(share.menuPermission.favorite)
        assertTrue(share.menuPermission.copy)
        assertTrue(share.menuPermission.read)
        assertTrue(share.menuPermission.share)
        assertTrue(share.menuPermission.info)
        assertTrue(buildSystemShareDesk().menuPermission.favorite)
    }
}

private object TestShareSession : ShareSession {
    override val isActive: Boolean = true

    override suspend fun list(path: String): Result<List<FileSimpleInfo>> = Result.success(emptyList())

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
}

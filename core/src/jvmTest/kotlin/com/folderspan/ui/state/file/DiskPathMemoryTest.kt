package com.folderspan.ui.state.file

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.S3DriveExtras
import com.folderspan.data.main.network.SmbDriveExtras
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareProtocol
import com.folderspan.data.main.share.ShareSession
import com.folderspan.service.http.client.HttpRouteClientManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DiskPathMemoryTest {
    @Test
    fun buildDiskPathKeyUsesStableIdentifiers() {
        val device = Device(
            id = "dev-1",
            name = "Device",
            pathSeparator = "/",
            host = mutableMapOf("default" to HttpRouteClientManager()),
            type = DeviceType.Android,
            token = "token"
        )
        val share = Share(
            id = "share-1",
            name = "Share",
            pathSeparator = "/",
            session = DiskPathTestShareSession,
            protocol = ShareProtocol.Remote,
            type = DeviceType.Android
        )
        val network = Network(
            name = "NAS",
            pathSeparator = "/",
            protocol = "SMB",
            host = "192.168.0.2:445",
            username = "user",
            password = "secret",
            extras = NetworkDriveExtras(
                smb = SmbDriveExtras(
                    share = "public"
                )
            )
        )

        // 本地、设备、分享键包含可识别标识
        assertEquals("local", buildDiskPathKey(Local()))
        assertEquals("device:dev-1", buildDiskPathKey(device))
        assertEquals("share:Remote:share-1", buildDiskPathKey(share))
        val networkKey = buildDiskPathKey(network)
        // 网络键不包含密码
        assertEquals("network:SMB:192.168.0.2:445:user:public", networkKey)
        assertFalse(networkKey.contains("secret"))

        val s3 = Network(
            name = "S3",
            pathSeparator = "/",
            protocol = "S3",
            host = "https://s3.us-east-1.amazonaws.com",
            username = "AKIA",
            password = "secret",
            extras = NetworkDriveExtras(
                s3 = S3DriveExtras(
                    bucket = "bucket-a"
                )
            )
        )
        val s3Key = buildDiskPathKey(s3)
        assertEquals("network:S3:https://s3.us-east-1.amazonaws.com:AKIA:bucket-a", s3Key)
        assertFalse(s3Key.contains("secret"))
    }

    @Test
    fun resolveRememberedPathPrefersOverrides() {
        // 显式覆盖优先，其次使用记忆路径；空白视为无效
        assertEquals("/override", resolveRememberedPath("/override", "/remembered"))
        assertEquals("/remembered", resolveRememberedPath("  ", "/remembered"))
        assertNull(resolveRememberedPath(null, "  "))
    }

    @Test
    fun resolveSameDeskPathOverrideOnlyReturnsNewOverride() {
        assertEquals(
            "content://bundle/2",
            resolveSameDeskPathOverride("content://bundle/1", "content://bundle/2")
        )
        assertNull(resolveSameDeskPathOverride("content://bundle/1", "content://bundle/1"))
        assertNull(resolveSameDeskPathOverride("content://bundle/1", "   "))
    }

    @Test
    fun fileScrollLocationSeparatesParentAndChildPaths() {
        val local = Local(pathSeparator = "/")

        val parent = buildFileScrollLocation(local, "/storage")
        val child = buildFileScrollLocation(local, "/storage/photos")

        assertNotEquals(parent, child)
    }

    @Test
    fun fileScrollLocationSeparatesSamePathOnDifferentDisks() {
        val local = Local(pathSeparator = "/")
        val device = Device(
            id = "dev-1",
            name = "Device",
            pathSeparator = "/",
            host = mutableMapOf("default" to HttpRouteClientManager()),
            type = DeviceType.Android,
            token = "token"
        )

        assertNotEquals(
            buildFileScrollLocation(local, "/storage"),
            buildFileScrollLocation(device, "/storage"),
        )
    }

    @Test
    fun fileScrollLocationNormalizesTrailingSeparators() {
        val local = Local(pathSeparator = "/")

        assertEquals(
            buildFileScrollLocation(local, "/storage/photos"),
            buildFileScrollLocation(local, "/storage/photos///"),
        )
    }
}

private object DiskPathTestShareSession : ShareSession {
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

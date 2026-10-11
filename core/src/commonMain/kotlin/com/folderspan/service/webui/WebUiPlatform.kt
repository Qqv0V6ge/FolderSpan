package com.folderspan.service.webui

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import kotlinx.serialization.Serializable

const val WEB_UI_PLATFORM_PATH = "/api/web-ui/platform"
const val WEB_UI_EVENTS_PATH = "/api/web-ui/events"
const val WEB_UI_PROTOCOL_HEADER = "X-FolderSpan-WebUi-Version"
const val WEB_UI_RESOURCE_LIMIT = 64
const val WEB_UI_EVENT_LIMIT = 32
const val WEB_UI_RESOURCE_LEASE_MS = 60_000L
const val WEB_UI_POLL_TIMEOUT_MS = 25_000L

@Serializable
data class WebUiHostInfo(
    val separator: String,
    val home: String,
    val cache: String,
    val app: String,
    val roots: List<PathInfo>,
    val interfaces: List<com.folderspan.service.http.NetworkInterfaceInfo> = emptyList(),
    val fingerprint: String = "",
    val publicKey: String = "",
    val compressionCodecs: List<Int> = emptyList(),
    val caseInsensitive: Boolean = false,
    val sensitivePaths: List<WebUiSensitivePath> = emptyList(),

)

@Serializable
data class WebUiPathRequest(val path: String)

@Serializable
data class WebUiRenameRequest(val path: String, val oldName: String, val newName: String)

@Serializable
data class WebUiPathBoundaryRequest(val root: String, val path: String, val allowMissing: Boolean = false)

@Serializable
data class WebUiDirectoryBatch(
    val entries: List<FileSimpleInfo>,
    val finished: Boolean,
    val issues: List<String> = emptyList(),
)

@Serializable
data class WebUiHandle(val id: String)

@Serializable
data class WebUiPlatformEvent(
    val id: Long,
    val handle: String,
    val type: String,
    val data: String = "",
    val requiresReply: Boolean = false,
    val bytes: ByteArray = byteArrayOf(),
)

/** Only platform operations cross this boundary. No business State is owned by the server. */
interface WebUiPlatform {
    suspend fun hostInfo(session: WebUiPlatformSession): WebUiHostInfo
    suspend fun handle(request: LinkShareHttpRequest, session: WebUiPlatformSession): LinkShareHttpResponse
}

expect fun createWebUiPlatform(): WebUiPlatform

@Serializable
data class WebUiNetworkOpen(val callbackId: String, val config: com.folderspan.service.network.NetworkConnectionConfig)

@Serializable
data class WebUiNetworkPath(
    val handle: String,
    val path: String,
    val newPath: String = "",
    val isDirectory: Boolean = false,
    val size: Long = -1,
)

@Serializable
data class WebUiNetworkBatch(
    val entries: List<com.folderspan.service.network.NetworkFileEntry>,
    val finished: Boolean,
)

@Serializable
internal data class WebUiDeviceConnect(val host: String, val port: Int, val fingerprint: String? = null)
@Serializable
internal data class WebUiDeviceConnected(val handle: String, val fingerprint: String)
@Serializable
internal data class WebUiUdpListen(val callbackId: String, val port: Int)
@Serializable
internal data class WebUiUdpPacket(val host: String, val payload: ByteArray)
@Serializable
internal data class WebUiVerifySignature(val publicKey: String, val payload: ByteArray, val signature: String)

@Serializable
internal data class WebUiCompressionOpen(val codec: Int, val decompress: Boolean)

@Serializable
data class WebUiSensitivePath(val path: String, val sensitivity: com.folderspan.utils.FileSensitivity, val category: String, val descendants: Boolean)

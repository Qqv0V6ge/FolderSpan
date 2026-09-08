package com.folderspan.service.data

import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareProtocol
import com.folderspan.data.main.share.ShareSession
import com.folderspan.service.bookmark.DeviceBookmarkClient
import com.folderspan.service.file.DeviceFileClient
import com.folderspan.service.http.client.HttpRouteClientManager
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.PORT
import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import com.folderspan.service.path.DevicePathClient
import com.folderspan.service.session.DeviceSessionClientManager
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber


@Serializable
enum class ConnectType(type: String) {
    New("New"),
    Connect("Connect"),
    Fail("Fail"),
    UnConnect("UnConnect"),
    Loading("Loading"),
    Rejected("Rejected"),
}

@Serializable
enum class DeviceTransportType(type: String) {
    Session("Session"),
    WebRtc("WebRtc"),
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SocketDevice(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var pathSeparator: String,
    @ProtoNumber(4) var host: String = "",
    @ProtoNumber(5) var port: Int = PORT,
    @ProtoNumber(6) val type: DeviceType,
    @ProtoNumber(7) var connectType: ConnectType = ConnectType.New,
    @ProtoNumber(8) val transportType: DeviceTransportType = DeviceTransportType.Session,
    @ProtoNumber(9) var httpsPort: Int = port,
    @ProtoNumber(10) var tlsFingerprintSha256: String = "",
    @Transient
    var token: String = "",
    @Transient
    var shareConnectNonce: String = "",
) {
    @Transient
    var httpClient: HttpRouteClientManager? = null

    @Transient
    var sessionClient: DeviceSessionClientManager? = null

    fun matchesRecord(other: SocketDevice): Boolean {
        return id == other.id && transportType == other.transportType
    }

    fun toDevice(
        includeHost: Boolean = true,
        pathClient: DevicePathClient? = sessionClient?.pathRouteClient ?: httpClient?.pathRouteClient,
        bookmarkClient: DeviceBookmarkClient? = sessionClient?.bookmarkRouteClient ?: httpClient?.bookmarkRouteClient,
        fileClient: DeviceFileClient? = sessionClient?.fileRouteClient ?: httpClient?.fileRouteClient,
    ): Device {
        return Device(
            id = id,
            name = name,
            pathSeparator = pathSeparator,
            host = if (includeHost && httpClient != null) {
                mutableMapOf(host to httpClient!!)
            } else {
                mutableMapOf()
            },
            type = type,
            token = token,
            pathClient = pathClient,
            bookmarkClient = bookmarkClient,
            fileClient = fileClient,
            transportType = transportType,
        )
    }

    fun toShare(session: ShareSession): Share {
        return Share(
            id = id,
            name = name,
            pathSeparator = "/",
            protocol = ShareProtocol.Remote,
            type = type,
            session = session,
        )
    }

    fun withCopy(
        id: String = this.id,
        name: String = this.name,
        pathSeparator: String = this.pathSeparator,
        host: String = this.host,
        port: Int = this.port,
        type: DeviceType = this.type,
        connectType: ConnectType = this.connectType,
        transportType: DeviceTransportType = this.transportType,
        httpsPort: Int = this.httpsPort,
        tlsFingerprintSha256: String = this.tlsFingerprintSha256,
        token: String = this.token,
        shareConnectNonce: String = this.shareConnectNonce,
        httpClient: HttpRouteClientManager? = this.httpClient,
        sessionClient: DeviceSessionClientManager? = this.sessionClient,
    ): SocketDevice {
        return SocketDevice(
            id = id,
            name = name,
            pathSeparator = pathSeparator,
            host = host,
            port = port,
            type = type,
            connectType = connectType,
            transportType = transportType,
            httpsPort = httpsPort,
            tlsFingerprintSha256 = normalizeTlsFingerprintSha256(tlsFingerprintSha256),
            token = token,
            shareConnectNonce = shareConnectNonce,
        ).apply {
            this.httpClient = httpClient
            this.sessionClient = sessionClient
        }
    }

    fun hasActiveConnection(): Boolean {
        return when (transportType) {
            DeviceTransportType.Session -> sessionClient != null || httpClient != null
            DeviceTransportType.WebRtc -> connectType == ConnectType.Connect || connectType == ConnectType.Loading
        }
    }
}

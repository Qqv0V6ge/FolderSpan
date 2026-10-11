package com.folderspan.data.main.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NetworkDriveExtras(
    @ProtoNumber(1) val ftp: FtpDriveExtras = FtpDriveExtras(),
    @ProtoNumber(2) val sftp: SftpDriveExtras = SftpDriveExtras(),
    @ProtoNumber(3) val smb: SmbDriveExtras = SmbDriveExtras(),
    @ProtoNumber(4) val webdav: WebDavDriveExtras = WebDavDriveExtras(),
    @ProtoNumber(5) val s3: S3DriveExtras = S3DriveExtras(),
)

@Serializable
enum class FtpPathEncoding {
    Auto,
    Utf8,
    Gb18030,
    ShiftJis,
    Cp949,
    Windows1251,
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FtpDriveExtras(
    @ProtoNumber(1) val passiveMode: Boolean = true,
    @ProtoNumber(2) val ftpsEnabled: Boolean = false,
    @ProtoNumber(3) val pathEncoding: FtpPathEncoding = FtpPathEncoding.Auto,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SftpDriveExtras(
    @ProtoNumber(1) val privateKey: String = "",
    @ProtoNumber(2) val knownHosts: String = "",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SmbDriveExtras(
    @ProtoNumber(1) val share: String = "",
    @ProtoNumber(2) val domain: String = "",
)

@Serializable
enum class WebDavAuthType {
    Basic,
    Digest,
    Token,
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WebDavDriveExtras(
    @ProtoNumber(1) val authType: WebDavAuthType = WebDavAuthType.Basic,
    @ProtoNumber(2) val token: String = "",
    @ProtoNumber(3) val tokenHeaderName: String = "Authorization",
    @ProtoNumber(4) val tokenPrefix: String = "Bearer",
    @ProtoNumber(5) val headers: Map<String, String> = emptyMap(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class S3DriveExtras(
    @ProtoNumber(1) val bucket: String = "",
    @ProtoNumber(2) val region: String = "us-east-1",
    @ProtoNumber(3) val endpoint: String = "",
    @ProtoNumber(4) val sessionToken: String = "",
    @ProtoNumber(5) val forcePathStyle: Boolean = false,
)

package com.folderspan.data.file

import com.folderspan.data.main.device.DeviceType
import com.folderspan.currentOsName
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PathInfo(
    @ProtoNumber(1) val path: String,
    @ProtoNumber(2) val totalSpace: Long,
    @ProtoNumber(3) val freeSpace: Long,
)

// Apple 平台磁盘容量仅用于本机显示和写入检查，不经远程文件接口传播。
internal fun shouldHideRemoteDiskSpace(hostPlatform: DeviceType, hostOsName: String = currentOsName()): Boolean =
    hostPlatform == DeviceType.IOS ||
        (hostPlatform == DeviceType.JVM && hostOsName.startsWith("Mac", ignoreCase = true))

internal fun PathInfo.forRemoteAccess(hostPlatform: DeviceType, hostOsName: String = currentOsName()): PathInfo =
    if (shouldHideRemoteDiskSpace(hostPlatform, hostOsName)) copy(totalSpace = 0L, freeSpace = 0L) else this

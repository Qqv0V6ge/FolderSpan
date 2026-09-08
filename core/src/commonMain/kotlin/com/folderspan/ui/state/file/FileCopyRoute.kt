package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID

internal enum class CopyRoute {
    // 本地文件复制到本地。
    LocalToLocal,

    // 本地文件上传到网络存储。
    LocalToNetwork,

    // 其他来源保存到本地时，走通用本地复制逻辑。
    DirectToLocal,

    // 任一端为设备时，交给设备复制实现处理。
    DeviceRoute,

    // 设备同步到网络存储，按块流式上传，不经过本地完整落地。
    DeviceToNetwork,

    // 远程分享内容保存到本地，走 Share 专用下载逻辑。
    ShareToLocal,

    // 分享内容同步到网络存储，按块流式上传，不经过本地完整落地。
    ShareToNetwork,

    // 系统分享内容保存到本地，直接复用本地文件复制。
    SystemShareToLocal,

    // 网络存储同步到设备，需要经过本地临时文件中转。
    NetworkToDevice,

    // 网络存储之间复制；同一端点优先服务端复制，明确不支持或跨端点时使用本机中转。
    NetworkToNetwork,

    // 网络存储保存到本地，走 Network 专用下载逻辑。
    NetworkToLocal,

    // 当前未支持的复制组合。
    Unsupported,
}

// 统一解析复制路径，避免条件分支顺序导致远程来源误落入通用 Local 分支。
internal fun resolveCopyRoute(
    srcProtocol: FileProtocol,
    destProtocol: FileProtocol,
    srcProtocolId: String = "",
): CopyRoute {
    return when {
        srcProtocol == FileProtocol.Local && destProtocol == FileProtocol.Local -> CopyRoute.LocalToLocal
        srcProtocol == FileProtocol.Network && destProtocol == FileProtocol.Device -> CopyRoute.NetworkToDevice
        srcProtocol == FileProtocol.Device && destProtocol == FileProtocol.Network -> CopyRoute.DeviceToNetwork
        srcProtocol == FileProtocol.Network && destProtocol == FileProtocol.Network -> CopyRoute.NetworkToNetwork
        srcProtocol == FileProtocol.Device || destProtocol == FileProtocol.Device -> CopyRoute.DeviceRoute
        srcProtocol == FileProtocol.Share &&
                destProtocol == FileProtocol.Local &&
                srcProtocolId == SYSTEM_SHARE_DESK_ID -> CopyRoute.SystemShareToLocal
        srcProtocol == FileProtocol.Share && destProtocol == FileProtocol.Local -> CopyRoute.ShareToLocal
        srcProtocol == FileProtocol.Share && destProtocol == FileProtocol.Network -> CopyRoute.ShareToNetwork
        srcProtocol == FileProtocol.Network && destProtocol == FileProtocol.Local -> CopyRoute.NetworkToLocal
        srcProtocol == FileProtocol.Local && destProtocol == FileProtocol.Network -> CopyRoute.LocalToNetwork
        destProtocol == FileProtocol.Local -> CopyRoute.DirectToLocal
        else -> CopyRoute.Unsupported
    }
}

internal fun isSameNetworkEndpoint(
    sourceProtocolId: String,
    targetProtocolId: String,
): Boolean = sourceProtocolId.isNotBlank() && sourceProtocolId == targetProtocolId

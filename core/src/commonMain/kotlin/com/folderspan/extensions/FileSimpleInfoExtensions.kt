package com.folderspan.extensions

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ui.state.main.TaskRuntimeEndpointRef

fun FileSimpleInfo.fileOperationKey(): String {
    return "${protocol.name}:$protocolId:$path"
}

internal fun FileSimpleInfo.toCopyDestination(
    sourceRoot: FileSimpleInfo,
    targetRoot: FileSimpleInfo,
): FileSimpleInfo {
    return withCopy(
        path = path.replaceFirst(sourceRoot.path, targetRoot.path),
        protocol = targetRoot.protocol,
        protocolId = targetRoot.protocolId,
    )
}

internal fun FileSimpleInfo.toEndpointRef(): TaskRuntimeEndpointRef {
    return TaskRuntimeEndpointRef(
        protocol = protocol,
        protocolId = protocolId,
        path = path,
    )
}

internal fun FileSimpleInfo.withSharedProtocol(root: FileSimpleInfo): FileSimpleInfo {
    return withCopy(
        protocol = root.protocol,
        protocolId = root.protocolId,
    )
}

internal fun FileSimpleInfo.normalizeWebRtcRemoteProtocolId(remoteId: String): FileSimpleInfo {
    if (protocol != FileProtocol.Device || remoteId.isBlank() || protocolId == remoteId) {
        return this
    }
    return withCopy(protocolId = remoteId)
}

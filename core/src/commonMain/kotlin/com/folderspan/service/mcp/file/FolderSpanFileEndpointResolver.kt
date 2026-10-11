package com.folderspan.service.mcp.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.NetworkState

class FolderSpanFileEndpointResolver(
    private val deviceState: DeviceState,
    private val networkState: NetworkState,
    private val localGateway: LocalFileEndpointGateway = LocalFileEndpointGateway(),
) {
    val resolver: FileEndpointResolver = FileEndpointResolver().apply {
        register(FileProtocol.Local) { sourceId -> localGateway.takeIf { sourceId.isBlank() } }
        register(FileProtocol.Device) { sourceId ->
            deviceState.resolveConnectedDevice(sourceId)?.let(::DeviceFileEndpointGateway)
        }
        register(FileProtocol.Share) { sourceId ->
            deviceState.shares.firstOrNull { share -> share.id == sourceId }?.let(::ShareFileEndpointGateway)
        }
        register(FileProtocol.Network) { sourceId ->
            networkState.connectedNetworks.firstOrNull { network -> network.protocolId == sourceId }
                ?.let(::NetworkFileEndpointGateway)
        }
    }
}

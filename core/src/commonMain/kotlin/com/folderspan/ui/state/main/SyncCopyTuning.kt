package com.folderspan.ui.state.main

import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.Network
import com.folderspan.service.operation.CopyOperationTuning
import com.folderspan.service.operation.TraversalEndpointKind

internal fun syncCopyOperationTuning(desks: List<DiskBase>, parallelism: Int) = CopyOperationTuning(
    endpointKinds = desks.mapTo(mutableSetOf()) { desk ->
        when (desk) {
            is Device -> TraversalEndpointKind.Device
            is Network -> TraversalEndpointKind.Network
            else -> TraversalEndpointKind.Local
        }
    },
    remoteStatuses = {
        desks.filterIsInstance<Device>().mapNotNull { device ->
            (device.fileClient ?: device.host.values.firstOrNull()?.fileRouteClient)?.transferStatus()
        }
    },
    maxFileParallelism = parallelism.coerceIn(1, 8),
)

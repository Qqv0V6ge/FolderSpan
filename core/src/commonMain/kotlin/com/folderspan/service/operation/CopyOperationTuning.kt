package com.folderspan.service.operation

/** Copy queues and synchronization share the same local and endpoint bounds. */
internal class CopyOperationTuning(
    endpointKinds: Set<TraversalEndpointKind>,
    private val remoteStatuses: () -> List<HttpTransferStatus>,
    maxFileParallelism: Int = Int.MAX_VALUE,
) {
    private val fileLimit = maxFileParallelism.coerceAtLeast(1)
    private val endpointKind = when {
        TraversalEndpointKind.Device in endpointKinds || TraversalEndpointKind.Share in endpointKinds -> TraversalEndpointKind.Device
        TraversalEndpointKind.Network in endpointKinds -> TraversalEndpointKind.Network
        else -> TraversalEndpointKind.Local
    }

    private fun statuses() = remoteStatuses().map { it.clamped() }
    private fun List<HttpTransferStatus>.busy() = any { it.sampledAtMillis > 0 && (it.busy || it.activeRequests >= it.maxParallelRequests) }
    private fun List<HttpTransferStatus>.recommendation(): Int? = minOfOrNull {
        if (it.sampledAtMillis > 0) it.recommendedParallelRequests else 1
    } ?: if (endpointKind == TraversalEndpointKind.Device) 1 else null

    fun config(): OperationParallelismConfig {
        val statuses = statuses()
        val config = resolveOperationParallelism(endpointKind, statuses.recommendation(), statuses.busy())
        return config.copy(
            initialParallelism = minOf(config.initialParallelism, fileLimit),
            maxParallelism = minOf(config.maxParallelism, fileLimit),
            hardMaxParallelism = minOf(config.hardMaxParallelism, fileLimit),
        ).normalized()
    }

    fun currentMaxParallelism(): Int {
        val statuses = statuses()
        return minOf(fileLimit, resolveOperationRuntimeMaxParallelism(endpointKind,
            statuses.recommendation(), statuses.busy()))
    }
}

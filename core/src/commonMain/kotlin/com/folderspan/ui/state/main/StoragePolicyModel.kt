package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.NetworkEntry
import com.folderspan.data.main.network.buildProtocolId
import com.folderspan.service.network.StorageReference
import com.folderspan.service.network.storageConnectionBinding
import kotlinx.serialization.Serializable
import strings.AppStrings

@Serializable
data class StorageDestination(
    val type: SyncEndpointType = SyncEndpointType.Network,
    val ref: String,
    val path: String,
    val storageReference: StorageReference? = null,
    val networkEntryId: Long? = null,
    val connectionBinding: String = "",
) {
    fun applyTo(task: SyncTask) = task.copy(
        targetType = type, targetRef = ref, targetPath = path,
        targetStorageReference = storageReference,
    )
}

fun NetworkEntry.storageDestination(path: String, reference: StorageReference? = null) = StorageDestination(
    ref = network.buildProtocolId(), path = path, storageReference = reference,
    networkEntryId = id, connectionBinding = storageConnectionBinding(network.connectionConfig()),
)

internal fun StorageDestination.matches(entry: NetworkEntry): Boolean =
    networkEntryId == entry.id && ref == entry.network.buildProtocolId() &&
        connectionBinding == storageConnectionBinding(entry.network.connectionConfig())

enum class StorageGroupControl { Pause, Resume, Cancel }

enum class StorageRecommendationReason {
    Preferred, Eligible, Unavailable, ReadOnly, FileTooLarge, InsufficientSpace, UnknownCapacity, Stale,
}

data class StorageTargetMetadata(
    val destination: StorageDestination,
    val reachable: Boolean,
    val writable: Boolean,
    val maxFileSize: Long? = null,
    val availableBytes: Long? = null,
    val refreshedAt: Long,
    val source: String = "provider",
)

data class StorageRecommendation(
    val metadata: StorageTargetMetadata,
    val eligible: Boolean,
    val reasons: List<StorageRecommendationReason>,
)

fun recommendStorageTargets(
    candidates: List<StorageTargetMetadata>,
    sizes: List<Long>,
    preferredEntryIds: List<Long> = emptyList(),
    now: Long = nowMillis(),
): List<StorageRecommendation> {
    val largest = sizes.maxOrNull() ?: 0
    val total = sizes.fold(0L) { sum, size ->
        if (size < 0 || sum > Long.MAX_VALUE - size) Long.MAX_VALUE else sum + size
    }
    return candidates.map { candidate ->
        val reasons = buildList {
            if (!candidate.reachable) add(StorageRecommendationReason.Unavailable)
            if (!candidate.writable) add(StorageRecommendationReason.ReadOnly)
            if (candidate.maxFileSize?.let { largest > it } == true) add(StorageRecommendationReason.FileTooLarge)
            if (candidate.availableBytes?.let { total > it } == true) add(StorageRecommendationReason.InsufficientSpace)
        }
        val eligible = reasons.isEmpty()
        StorageRecommendation(candidate, eligible, reasons + buildList {
            if (candidate.availableBytes == null) add(StorageRecommendationReason.UnknownCapacity)
            if (now - candidate.refreshedAt > 60_000) add(StorageRecommendationReason.Stale)
            if (eligible) add(if (candidate.destination.networkEntryId in preferredEntryIds)
                StorageRecommendationReason.Preferred else StorageRecommendationReason.Eligible)
        })
    }.sortedWith(compareByDescending<StorageRecommendation> { it.eligible }
        .thenBy { preferredEntryIds.indexOf(it.metadata.destination.networkEntryId).takeIf { it >= 0 } ?: Int.MAX_VALUE }
        .thenBy { it.metadata.destination.ref }
        .thenBy { it.metadata.destination.networkEntryId ?: 0L }
        .thenBy { it.metadata.destination.path })
}

@Serializable
data class StorageSourceEntry(val relativePath: String, val file: FileSimpleInfo)

@Serializable
data class StorageTargetRun(
    val destination: StorageDestination,
    val operationTaskKey: Long,
    val status: SyncRunStatus = SyncRunStatus.Queued,
    val items: List<SyncRunItem> = emptyList(),
    val message: String = "",
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val uncertain: Boolean = false,
)

internal fun storageGroupStatus(targets: List<StorageTargetRun>): SyncRunStatus = when {
    targets.any { it.status == SyncRunStatus.Running } -> SyncRunStatus.Running
    targets.any { it.status == SyncRunStatus.Queued } -> SyncRunStatus.Queued
    targets.isNotEmpty() && targets.all { it.status == SyncRunStatus.Success } -> SyncRunStatus.Success
    targets.any { it.status == SyncRunStatus.Success || it.status == SyncRunStatus.PartialSuccess } -> SyncRunStatus.PartialSuccess
    targets.any { it.status == SyncRunStatus.Canceled } -> SyncRunStatus.Canceled
    else -> SyncRunStatus.Failure
}

internal fun requireDistinctStorageDestinations(destinations: List<StorageDestination>) {
    val identities = destinations.map {
        listOf(it.type.name, it.ref, it.connectionBinding,
            it.storageReference?.id?.takeIf(String::isNotBlank) ?: normalizePathForCompare(it.path, "/"))
    }
    require(identities.distinct().size == identities.size) { AppStrings.ui_storage_duplicate_target }
    destinations.forEachIndexed { index, first ->
        destinations.drop(index + 1).filter { it.type == first.type && it.ref == first.ref &&
            it.connectionBinding == first.connectionBinding }.forEach { second ->
            require(!syncTargetInsideSourcePath(first.path, second.path, "/") &&
                !syncTargetInsideSourcePath(second.path, first.path, "/")) { AppStrings.ui_storage_duplicate_target }
        }
    }
}

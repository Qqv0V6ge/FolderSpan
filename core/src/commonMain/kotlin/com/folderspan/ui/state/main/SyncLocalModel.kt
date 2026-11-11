package com.folderspan.ui.state.main

import com.folderspan.service.network.StorageReference
import korlibs.crypto.sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class SyncSide { Source, Target }

@Serializable
data class SyncEntryState(
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
    val storageReference: StorageReference? = null,
    val contentSha256: String = "",
)

internal fun syncEntryUnchanged(expected: SyncEntryState?, actual: SyncEntryState?): Boolean {
    if (expected == null || actual == null) return expected == actual
    if (expected.isDirectory != actual.isDirectory || expected.storageReference != actual.storageReference) return false
    if (expected.isDirectory) return true
    if (expected.size < 0 || actual.size < 0 || expected.size != actual.size) return false
    if (expected.contentSha256.isNotEmpty() && actual.contentSha256.isNotEmpty()) return expected.contentSha256 == actual.contentSha256
    return if (expected.modifiedAt > 0 && actual.modifiedAt > 0) kotlin.math.abs(expected.modifiedAt - actual.modifiedAt) <= 2_000
        else expected.contentSha256.isNotEmpty() && expected.contentSha256 == actual.contentSha256
}

internal fun syncEntryMatchesSnapshot(expected: SyncEntryState?, actual: SyncEntryState?): Boolean {
    if (expected == null || actual == null) return expected == actual
    if (expected.isDirectory != actual.isDirectory || expected.storageReference != actual.storageReference) return false
    if (expected.isDirectory) return true
    if (expected.size < 0 || expected.size != actual.size) return false
    if (expected.modifiedAt > 0 && expected.modifiedAt != actual.modifiedAt) return false
    return if (expected.contentSha256.isNotEmpty()) expected.contentSha256 == actual.contentSha256
        else expected.modifiedAt > 0
}

@Serializable
data class SyncBackupCopy(
    val source: SyncEntryState,
    val relativePath: String,
    val target: SyncEntryState,
)

@Serializable
data class SyncBaseline(
    val relativePath: String,
    val participants: Map<String, SyncEntryState?>,
    val backupCopies: Map<String, SyncBackupCopy> = emptyMap(),
    val selectedSourceReference: StorageReference? = null,
)

@Serializable
data class SyncPendingDelete(
    val participant: String,
    val relativePath: String,
    val expected: Map<String, SyncEntryState?>,
)

@Serializable
data class SyncRecoveryEndpoint(
    val type: SyncEndpointType,
    val ref: String,
    val rootPath: String,
    val storageReference: StorageReference? = null,
    val connectionBinding: String = "",
)

@Serializable
enum class SyncRecoveryAction { Copy, Overwrite, CreateDirectory, Delete }

@Serializable
enum class SyncRecoveryPhase { Prepared, OldPreserved, Placed, Verified }

@Serializable
data class SyncRecoveryLocation(val endpoint: SyncRecoveryEndpoint, val path: String)

@Serializable
data class SyncFileRecovery(
    val id: String,
    val taskId: Long,
    val fingerprint: String,
    val ownerDeviceId: String,
    val groupId: String,
    val participant: String,
    val endpoint: SyncRecoveryEndpoint,
    val relativePath: String,
    val finalPath: String,
    val temporaryPath: String,
    val oldPath: String,
    val oldState: SyncEntryState?,
    val newState: SyncEntryState?,
    val recycleEnabled: Boolean,
    val logicalPath: String = relativePath,
    val action: SyncRecoveryAction = SyncRecoveryAction.Overwrite,
    val conflictCopy: Boolean = false,
    val phase: SyncRecoveryPhase = SyncRecoveryPhase.Prepared,
    val attempt: Int = 0,
    val sourceEndpoint: SyncRecoveryEndpoint? = null,
    val sourcePath: String = "",
    val stagedState: SyncEntryState? = null,
    val oldStoredState: SyncEntryState? = null,
    val placedState: SyncEntryState? = null,
    val restoredState: SyncEntryState? = null,
    val oldTree: Map<String, SyncEntryState> = emptyMap(),
    val oldStoredTree: Map<String, SyncEntryState> = emptyMap(),
    val restoredTree: Map<String, SyncEntryState> = emptyMap(),
    val expectedParticipants: Map<String, SyncEntryState?> = emptyMap(),
    val participantLocations: Map<String, SyncRecoveryLocation> = emptyMap(),
    val previewFingerprint: String = "",
    val backupPaths: Map<String, String> = emptyMap(),
    val expectedBackupStates: Map<String, SyncEntryState> = emptyMap(),
    val selectedSourceReference: StorageReference? = null,
)

internal fun SyncFileRecovery.sameRecoveryOperation(other: SyncFileRecovery): Boolean =
    copy(phase = SyncRecoveryPhase.Prepared, attempt = 0, oldStoredState = null, placedState = null, restoredState = null,
        oldStoredTree = emptyMap(), restoredTree = emptyMap()) ==
        other.copy(phase = SyncRecoveryPhase.Prepared, attempt = 0, oldStoredState = null, placedState = null, restoredState = null,
            oldStoredTree = emptyMap(), restoredTree = emptyMap())

data class SyncTaskLocalState(
    val fingerprint: String,
    val confirmedFingerprint: String = "",
    val baselineEstablished: Boolean = false,
    val pendingRunId: Long = 0,
    val confirmationNotified: Boolean = false,
    val scheduleTimeZone: String = "",
    val fixedTargets: List<StorageDestination> = emptyList(),
    val fixedSourceBinding: String = "",
) {
    val previewConfirmed: Boolean get() = confirmedFingerprint == fingerprint
}

fun syncDefinitionFingerprint(task: SyncTask): String = Json.encodeToString(listOf(
    task.mode.name, task.sourceType.name, task.sourceRef, task.sourcePath,
    if (task.selectedSources.isEmpty()) Json.encodeToString(task.sourceStorageReference) else "", task.targetType.name, task.targetRef, task.targetPath,
    Json.encodeToString(task.targetStorageReference), task.ownerDeviceId,
    Json.encodeToString(task.destinations), task.selectRecommended.toString(),
    Json.encodeToString(task.selectedSources.map { listOf(it.protocol.name, it.protocolId, it.path, it.name, it.isDirectory.toString(), Json.encodeToString(it.storageReference)) }),
)).encodeToByteArray().sha256().hexLower

internal fun syncPreviewFingerprint(task: SyncTask): String = Json.encodeToString(task.copy(
    lastRunAt = 0, nextRunAt = 0, lastStatus = SyncRunStatus.Idle, lastMessage = "", createdAt = 0, updatedAt = 0,
    currentSourceReferences = emptyMap(), sourceStorageReference = task.sourceStorageReference.takeIf { task.selectedSources.isEmpty() },
)).encodeToByteArray().sha256().hexLower

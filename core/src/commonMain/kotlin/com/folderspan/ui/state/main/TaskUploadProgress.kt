package com.folderspan.ui.state.main

data class TaskUploadProgress(
    val path: String,
    val confirmedOffset: Long,
    val fileSize: Long,
)

/** A bounded view of ordinary checkpoint metadata; protected upload sessions are never read. */
fun TaskState.loadRuntimeUploadProgress(taskKey: Long): List<TaskUploadProgress> =
    listOf(TaskRuntimeQueueCategory.FILES, TaskRuntimeQueueCategory.EMPTY_FILES)
        .flatMap { category ->
            loadPendingRuntimeQueueEntries(taskKey, TaskRuntimeStage.COPY, category, limit = 64)
        }
        .mapNotNull { queued ->
            val entry = queued.entry
            val checkpointKey = buildTaskRetryEntryKey(
                TaskRetryStage.COPY, entry.src.path, entry.src.protocol, entry.src.protocolId,
                entry.dest.path, entry.dest.protocol, entry.dest.protocolId,
                entry.src.storageReference, entry.dest.storageReference,
            )
            (loadRuntimeTransferCheckpoint(taskKey, checkpointKey)
                ?: loadRuntimeTransferCheckpoint(taskKey, entry.entryId))
                ?.takeIf { it.uploadSession != null }
        }
        .distinctBy { it.entryId }
        .sortedByDescending { it.updatedAt }
        .take(3)
        .map { checkpoint ->
            TaskUploadProgress(checkpoint.destPath, checkpoint.uploadSession!!.confirmedOffset, checkpoint.fileSize)
        }

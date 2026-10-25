package com.folderspan.ui.state.main

import com.folderspan.data.file.FileProtocol
import com.folderspan.utils.PathUtils
import com.folderspan.utils.isAndroidContentUriPath

/** Incomplete outputs are separate from the successfully delivered task results. */
data class TaskResidualFile(val path: String, val sizeBytes: Long? = null)

data class TaskResidualSummary(
    val taskKey: Long,
    val files: List<TaskResidualFile>,
    val unknown: Boolean = false,
    val recovered: Boolean = false,
)

internal fun taskCleanupCandidates(artifacts: List<TaskArtifact>): List<TaskArtifact> {
    val protectedPaths = artifacts.filter { it.completed }.map { it.finalPath }
    return artifacts.filter { artifact ->
        !artifact.completed && !artifact.cleaned &&
            !(artifact.purpose == TaskArtifactPurpose.STRUCTURE_DIRECTORY && protectedPaths.any {
                it.startsWith(artifact.endpoint.path.trimEnd('/', '\\') + PathUtils.getPathSeparator())
            })
    }.sortedByDescending { it.endpoint.path.length }
}

/** Describe only journaled paths, without hashing file contents or changing ownership receipts. */
internal fun describeTaskResiduals(
    taskKey: Long,
    artifacts: List<TaskArtifact>,
    recovered: Boolean,
): TaskResidualSummary? {
    var unknown = false
    val files = taskCleanupCandidates(artifacts).mapNotNull { artifact ->
        val path = taskCleanupDisplayPath(artifact.endpoint.path)
        if (artifact.endpoint.protocol != FileProtocol.Local || isAndroidContentUriPath(path) || !LocalTaskArtifacts.supported) {
            unknown = true
            TaskResidualFile(path)
        } else {
            try {
                LocalTaskArtifacts.describe(path, artifact.cacheArtifact)?.let { TaskResidualFile(path, it.sizeBytes) }
            } catch (_: Exception) {
                unknown = true
                TaskResidualFile(path)
            }
        }
    }.distinctBy { it.path }
    return if (files.isEmpty() && !unknown) null else TaskResidualSummary(taskKey, files, unknown, recovered)
}

/** Reuse the persisted receipt exactly; a restart must never adopt a changed fingerprint. */
internal fun cleanupOwnedTaskArtifacts(
    taskKey: Long,
    artifacts: List<TaskArtifact>,
    store: TaskCleanupStore,
): TaskCleanupResult {
    val failures = mutableListOf<TaskCleanupFailure>()
    for (artifact in taskCleanupCandidates(artifacts)) {
        try {
            check(artifact.endpoint.protocol == FileProtocol.Local &&
                !isAndroidContentUriPath(artifact.endpoint.path)) { artifact.limitation }
            LocalTaskArtifacts.delete(artifact.endpoint.path, artifact.identity, artifact.fingerprint,
                artifact.purpose == TaskArtifactPurpose.STRUCTURE_DIRECTORY, artifact.cacheArtifact)
            store.save(taskKey, artifact.copy(cleaned = true))
        } catch (_: Exception) {
            failures += TaskCleanupFailure(taskKey, taskCleanupDisplayPath(artifact.endpoint.path),
                if (artifact.identity.isBlank()) artifact.limitation else "cleanup_failed")
        }
    }
    return TaskCleanupResult(failures)
}

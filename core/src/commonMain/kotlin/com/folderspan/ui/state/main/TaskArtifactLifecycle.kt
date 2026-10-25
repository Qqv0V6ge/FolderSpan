package com.folderspan.ui.state.main

import com.folderspan.data.file.FileProtocol
import com.folderspan.utils.isAndroidContentUriPath
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Persist intent before touching an output; only an exclusively created local temporary is owned. */
internal suspend fun TaskState.withTaskArtifact(
    task: Task,
    entryId: String,
    destination: TaskRuntimeEndpointRef,
    purpose: TaskArtifactPurpose = TaskArtifactPurpose.FILE,
    work: suspend (String) -> Result<Boolean>,
): Result<Boolean> {
    val previous = findTaskArtifact(task.key, entryId)?.takeUnless { it.cleaned }
    if (previous?.completed == true) return Result.success(true)
    val localFile = destination.protocol == FileProtocol.Local && LocalTaskArtifacts.supported &&
        !isAndroidContentUriPath(destination.path) &&
        purpose in setOf(TaskArtifactPurpose.FILE, TaskArtifactPurpose.EMPTY_FILE)
    val artifact = previous ?: TaskArtifact(
        entryId = entryId,
        endpoint = if (localFile) destination.copy(path = destination.path.take(destination.path.lastIndexOfAny(charArrayOf('/', '\\')) + 1) +
            ".folderspan-" + Uuid.random() + ".part") else destination.copy(path = taskCleanupDisplayPath(destination.path)),
        finalPath = taskCleanupDisplayPath(destination.path),
        purpose = if (localFile) TaskArtifactPurpose.TEMPORARY_FILE else purpose,
        limitation = if (localFile) "creation_unconfirmed" else "ownership_unverified",
    ).also { saveTaskArtifact(task.key, it) }
    var owned = artifact
    val localDirectory = destination.protocol == FileProtocol.Local && LocalTaskArtifacts.supported &&
        !isAndroidContentUriPath(destination.path) &&
        purpose in setOf(TaskArtifactPurpose.EMPTY_DIRECTORY, TaskArtifactPurpose.STRUCTURE_DIRECTORY, TaskArtifactPurpose.PROTECTED_DIRECTORY)
    if (localDirectory && previous == null) {
        if (LocalTaskArtifacts.inspect(destination.path) != null) {
            saveTaskArtifact(task.key, owned.copy(completed = true))
            return work(destination.path)
        }
        val created = LocalTaskArtifacts.create(destination.path, directory = true)
        owned = owned.copy(identity = created.identity, fingerprint = created.fingerprint, limitation = "",
            completed = purpose == TaskArtifactPurpose.EMPTY_DIRECTORY || purpose == TaskArtifactPurpose.PROTECTED_DIRECTORY)
        saveTaskArtifact(task.key, owned)
        if (owned.completed) return Result.success(true)
    }
    if (localFile) {
        if (owned.identity.isBlank()) {
            val created = LocalTaskArtifacts.create(owned.endpoint.path)
            owned = owned.copy(identity = created.identity, fingerprint = created.fingerprint, limitation = "")
            saveTaskArtifact(task.key, owned)
        } else {
            val current = LocalTaskArtifacts.inspect(owned.endpoint.path)
            check(current?.identity == owned.identity && current.fingerprint == owned.fingerprint) { "ownership_changed" }
        }
    }
    saveTaskArtifact(task.key, owned)
    try {
        val result = if (localFile && purpose == TaskArtifactPurpose.EMPTY_FILE) Result.success(true) else work(if (localFile) owned.endpoint.path else destination.path)
        if (result.getOrDefault(false)) {
            // A delivered final path is never a cleanup candidate, even if its receipt is interrupted.
            if (localFile) LocalTaskArtifacts.deliver(owned.endpoint.path, owned.finalPath, owned.identity)
            saveTaskArtifact(task.key, owned.copy(completed = purpose != TaskArtifactPurpose.STRUCTURE_DIRECTORY))
        }
        return result
    } finally {
        withContext(NonCancellable) {
            if (localFile) {
                val current = LocalTaskArtifacts.inspect(owned.endpoint.path)
                if (current != null && current.identity == owned.identity) {
                    saveTaskArtifact(task.key, owned.copy(fingerprint = current.fingerprint))
                }
            }
        }
    }
}

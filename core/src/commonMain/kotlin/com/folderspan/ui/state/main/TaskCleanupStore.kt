package com.folderspan.ui.state.main

import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.PrivateFileStorage
import com.folderspan.utils.ProtoBufCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.io.encoding.Base64
import korlibs.crypto.sha256

@Serializable
enum class TaskArtifactPurpose { TEMPORARY_FILE, FILE, EMPTY_FILE, EMPTY_DIRECTORY, STRUCTURE_DIRECTORY, PROTECTED_DIRECTORY }

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TaskArtifact(
    @ProtoNumber(1) val entryId: String,
    @ProtoNumber(2) val endpoint: TaskRuntimeEndpointRef,
    @ProtoNumber(3) val finalPath: String = endpoint.path,
    @ProtoNumber(4) val purpose: TaskArtifactPurpose = TaskArtifactPurpose.FILE,
    @ProtoNumber(5) val identity: String = "",
    @ProtoNumber(6) val fingerprint: String = "",
    @ProtoNumber(7) val completed: Boolean = false,
    @ProtoNumber(8) val cleaned: Boolean = false,
    @ProtoNumber(9) val limitation: String = "ownership_unverified",
    @ProtoNumber(10) val schemaVersion: Int = 1,
    @ProtoNumber(11) val cacheArtifact: Boolean = false,
)

data class TaskCleanupFailure(val taskKey: Long, val path: String?, val reason: String)
data class TaskCleanupResult(val failures: List<TaskCleanupFailure> = emptyList()) {
    val succeeded: Boolean get() = failures.isEmpty()
}

interface TaskCleanupStore {
    /** null means a legacy task which never created a journal; read errors must throw. */
    fun load(taskKey: Long): List<TaskArtifact>?
    fun find(taskKey: Long, entryId: String): TaskArtifact? = load(taskKey)?.firstOrNull { it.entryId == entryId }
    fun save(taskKey: Long, artifact: TaskArtifact)
    fun remove(taskKey: Long)
}

/** One protobuf file per artifact bounds update cost and preserves successful cleanup receipts. */
class TempTaskCleanupStore(
    private val basePath: String = PrivateFileStorage.getCachePath().trimEnd('/', '\\') +
        PrivateFileStorage.getPathSeparator() + "task-cleanup",
) : TaskCleanupStore {
    private val lock = TaskRuntimeStoreLock()

    override fun load(taskKey: Long): List<TaskArtifact>? = locked {
        val directory = directory(taskKey)
        if (!taskCleanupPathExists(directory)) return@locked null
        val files = PrivateFileStorage.getFileAndFolder(FileAccessPermission.Allowed, directory).getOrThrow()
        // A leftover replacement means an interrupted/failed journal update, never an empty journal.
        check(files.none { it.name.endsWith(".tmp") }) { "journal_unreadable" }
        files.filter { it.name.endsWith(".pb64") }.map { file ->
            val artifact = ProtoBufCodec.decode<TaskArtifact>(Base64.decode(
                PrivateFileStorage.readFile(FileAccessPermission.Allowed, file.path).getOrThrow().decodeToString(),
            ))
            check(artifact.schemaVersion == 1) { "journal_version_unsupported" }
            artifact
        }
    }

    override fun find(taskKey: Long, entryId: String): TaskArtifact? = locked {
        val path = directory(taskKey) + PrivateFileStorage.getPathSeparator() + entryId.encodeToByteArray().sha256().hexLower + ".pb64"
        check(!taskCleanupPathExists("$path.tmp")) { "journal_unreadable" }
        if (!taskCleanupPathExists(path)) return@locked null
        val artifact = ProtoBufCodec.decode<TaskArtifact>(Base64.decode(
            PrivateFileStorage.readFile(FileAccessPermission.Allowed, path).getOrThrow().decodeToString()))
        check(artifact.schemaVersion == 1 && artifact.entryId == entryId) { "journal_unreadable" }
        artifact
    }

    override fun save(taskKey: Long, artifact: TaskArtifact) = locked {
        val directory = directory(taskKey)
        PrivateFileStorage.createDirectoryIfNotExists(FileAccessPermission.Allowed, directory)
        val name = artifact.entryId.encodeToByteArray().sha256().hexLower + ".pb64"
        val path = directory + PrivateFileStorage.getPathSeparator() + name
        val text = Base64.encode(ProtoBufCodec.encode(artifact))
        replaceTaskCleanupFile(path, text)
    }

    override fun remove(taskKey: Long) = locked {
        val path = directory(taskKey)
        if (taskCleanupPathExists(path)) {
            PrivateFileStorage.deleteDirectory(FileAccessPermission.Allowed, path)
        }
    }

    private fun directory(taskKey: Long) = basePath + PrivateFileStorage.getPathSeparator() + taskKey
    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}

internal data class LocalTaskArtifactIdentity(val identity: String, val fingerprint: String)

/** Platforms without exclusive creation and stable identity explicitly fall back to retention. */
internal expect object LocalTaskArtifacts {
    val supported: Boolean
    fun create(path: String, directory: Boolean = false, cacheArtifact: Boolean = false): LocalTaskArtifactIdentity
    fun inspect(path: String, cacheArtifact: Boolean = false): LocalTaskArtifactIdentity?
    fun deliver(path: String, finalPath: String, identity: String)
    fun delete(path: String, identity: String, fingerprint: String, directory: Boolean, cacheArtifact: Boolean = false)
}

internal expect fun replaceTaskCleanupFile(path: String, content: String)

internal fun taskCleanupDisplayPath(path: String): String = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(path)) {
    path.replace(Regex("(://)[^/]*@"), "$1").substringBefore('?').substringBefore('#')
} else path

internal fun taskCleanupArtifactId(entryId: String): String = if ("://" in entryId) {
    entryId.encodeToByteArray().sha256().hexLower
} else entryId

/** Missing paths differ from inaccessible cleanup records. */
internal expect fun taskCleanupPathExists(path: String): Boolean

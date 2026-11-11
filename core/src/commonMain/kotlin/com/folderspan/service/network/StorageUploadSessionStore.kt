package com.folderspan.service.network

import com.folderspan.createSettings
import com.folderspan.ui.state.main.TaskRuntimeStoreLock
import com.folderspan.ui.state.main.TaskRuntimeUploadSessionReference
import com.folderspan.utils.ProtoBufCodec
import com.russhwolf.settings.Settings
import io.ktor.util.PlatformUtils
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

interface StorageUploadPersistence {
    suspend fun uploadSessionOwnerId(): Result<String> = storageResult { StorageUploadSessions.store.ownerRuntimeId }

    suspend fun saveUploadSession(
        taskKey: Long,
        reference: TaskRuntimeUploadSessionReference,
        session: StorageUploadSession,
    ): Result<Unit> = storageResult { StorageUploadSessions.store.save(taskKey, reference, session) }

    suspend fun loadUploadSession(
        taskKey: Long,
        reference: TaskRuntimeUploadSessionReference,
    ): Result<StorageUploadSession?> = storageResult { StorageUploadSessions.store.load(taskKey, reference) }

    suspend fun deleteUploadSession(
        taskKey: Long,
        reference: TaskRuntimeUploadSessionReference,
    ): Result<Unit> = storageResult { StorageUploadSessions.store.remove(taskKey, reference) }
}

internal object StorageUploadSessions {
    val store by lazy {
        check(!PlatformUtils.IS_JS && !PlatformUtils.IS_WASM_JS) { "Upload sessions belong to the selected Runtime" }
        StorageUploadSessionStore(createSettings())
    }

    fun removeTask(taskKey: Long) {
        if (!PlatformUtils.IS_JS && !PlatformUtils.IS_WASM_JS) store.removeTask(taskKey)
    }
}

internal class StorageUploadSessionStore(private val settings: Settings) {
    val ownerRuntimeId: String get() = locked {
        settings.getStringOrNull(OWNER_KEY) ?: Uuid.random().toString().also { settings.putString(OWNER_KEY, it) }
    }

    fun save(taskKey: Long, reference: TaskRuntimeUploadSessionReference, session: StorageUploadSession) = locked {
        validateReference(reference, session.size)
        require(reference.confirmedOffset == session.confirmedOffset) { "Upload progress was not confirmed by the backend" }
        require(reference.target.path == session.target.path) { "Upload target changed" }
        val key = PREFIX + reference.sessionId
        settings.getStringOrNull(key)?.let { value ->
            val old = decode(value)
            requireBinding(old, taskKey, reference)
            check(old.backend == session.backend && old.binding == session.binding) { "Upload account changed" }
            check(old.target == session.target && old.size == session.size) { "Upload target changed" }
        }
        val record = StoredSession(
            taskKey = taskKey, reference = reference, backend = session.backend, target = session.target,
            size = session.size, confirmedOffset = session.confirmedOffset, status = session.status,
            entry = session.entry, secret = session.secret, binding = session.binding,
        )
        settings.putString(key, Base64.encode(ProtoBufCodec.encode(record)))
    }

    fun load(taskKey: Long, reference: TaskRuntimeUploadSessionReference): StorageUploadSession? = locked {
        reference.requireSupportedFormat(Long.MAX_VALUE)
        check(reference.ownerRuntimeId == ownerRuntimeId) { "Upload session belongs to another Runtime" }
        val record = settings.getStringOrNull(PREFIX + reference.sessionId)?.let(::decode) ?: return@locked null
        validateReference(reference, record.size)
        requireBinding(record, taskKey, reference)
        StorageUploadSession(
            backend = record.backend, target = record.target, size = record.size,
            confirmedOffset = record.confirmedOffset, status = record.status, entry = record.entry,
            secret = record.secret, binding = record.binding,
        )
    }

    fun remove(taskKey: Long, reference: TaskRuntimeUploadSessionReference) = locked {
        load(taskKey, reference)
        settings.remove(PREFIX + reference.sessionId)
    }

    fun hasUncertainTask(taskKey: Long): Boolean = locked {
        settings.keys.filter { it.startsWith(PREFIX) && it != OWNER_KEY }.any { key ->
            val record = settings.getStringOrNull(key)?.let(::decode)
            record?.taskKey == taskKey && record.reference.ownerRuntimeId == ownerRuntimeId &&
                record.status == StorageUploadStatus.UNCERTAIN
        }
    }

    fun removeTask(taskKey: Long) = locked {
        settings.keys.filter { it.startsWith(PREFIX) && it != OWNER_KEY }.forEach { key ->
            val record = settings.getStringOrNull(key)?.let { runCatching { decode(it) }.getOrNull() }
            if (record?.taskKey == taskKey && record.reference.ownerRuntimeId == ownerRuntimeId &&
                record.status != StorageUploadStatus.UNCERTAIN) settings.remove(key)
        }
    }

    private fun validateReference(reference: TaskRuntimeUploadSessionReference, fileSize: Long) {
        reference.requireSupportedFormat(fileSize)
        check(reference.ownerRuntimeId == ownerRuntimeId) { "Upload session belongs to another Runtime" }
    }

    private fun requireBinding(record: StoredSession, taskKey: Long, reference: TaskRuntimeUploadSessionReference) {
        check(record.taskKey == taskKey &&
            record.reference.copy(confirmedOffset = 0, expiresAt = 0) == reference.copy(confirmedOffset = 0, expiresAt = 0)) {
            "Upload session identity changed"
        }
    }

    private fun decode(value: String): StoredSession {
        val record = ProtoBufCodec.decode<StoredSession>(Base64.decode(value))
        check(record.schemaVersion == 1) { "Unsupported protected upload session version" }
        return record
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }

    private companion object {
        const val PREFIX = "auth.storage.upload."
        const val OWNER_KEY = "auth.storage.upload.owner"
        val lock = TaskRuntimeStoreLock()
    }
}

// Only this private wire model enters the execution host's protected settings.
@Serializable
private class StoredSession(
    val schemaVersion: Int = 1,
    val taskKey: Long,
    val reference: TaskRuntimeUploadSessionReference,
    val backend: String,
    val target: StoragePath,
    val size: Long,
    val confirmedOffset: Long,
    val status: StorageUploadStatus,
    val entry: NetworkFileEntry? = null,
    val secret: String,
    val binding: String,
)

package com.folderspan.service.network

import com.folderspan.ui.state.main.TaskRuntimeStoreLock
import com.russhwolf.settings.Settings
import kotlin.io.encoding.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Content keys have their own vault namespace and survive OAuth account removal. */
@OptIn(ExperimentalUuidApi::class)
internal class StorageContentKeyStore(private val settings: Settings = createStorageContentSettings()) : AutoCloseable {
    @Serializable
    private data class Record(val version: Int = 2, val key: String, val anchor: String,
        val manifestInitialized: Boolean = false, val verified: Boolean = false, val enabled: Boolean = false)
    @Serializable
    private data class PasswordLocationRecord(val version: Int = 1, val key: String) {
        override fun toString() = "PasswordLocationKeyRecord()"
    }
    private val passwordUnlocked = mutableMapOf<Pair<String, String>, ByteArray>()
    private val unlocked = mutableMapOf<String, ByteArray>()
    private val json = Json { encodeDefaults = true }
    private val stateLock = TaskRuntimeStoreLock()

    fun unlockPasswordLocation(scope: String, owner: String, key: ByteArray, authorizationOwner: String? = null) = locked {
        require(key.size == 32 && owner.isNotBlank()) { "Invalid password location key ownership" }
        val name = passwordKeyName(scope)
        settings.getStringOrNull(name)?.let {
            val record = try { json.decodeFromString<PasswordLocationRecord>(it) }
            catch (_: Exception) { error("Password location key record is damaged") }
            require(record.version == 1) { "Unsupported password location key record" }
            val existing = try { Base64.decode(record.key) }
            catch (_: Exception) { error("Password location key record is damaged") }
            try {
                require(storageContentConstantTimeEquals(existing, key)) {
                    "Password location recovery would replace a different content key"
                }
            } finally { existing.fill(0) }
        }
        settings.putString(name, json.encodeToString(PasswordLocationRecord(key = Base64.encode(key))))
        if (authorizationOwner != null) {
            require(owner == authorizationOwner || owner.startsWith(authorizationOwner + "|")) { "Invalid password authorization owner" }
            retirePasswordAuthorization(scope, authorizationOwner)
        } else passwordUnlocked.remove(scope to owner)?.fill(0)
        passwordUnlocked[scope to owner] = key.copyOf()
    }

    fun passwordLocationStatus(scope: String, owner: String): Pair<Boolean, Boolean> = locked {
        (settings.getStringOrNull(passwordKeyName(scope)) != null) to ((scope to owner) in passwordUnlocked)
    }

    fun keyForPasswordOperation(scope: String, owner: String): ByteArray = locked {
        (passwordUnlocked[scope to owner] ?: throw StorageContentException(StorageContentErrorCode.Locked,
            "Password location is locked; enter its content password")).copyOf()
    }

    fun lockPasswordLocation(scope: String, owner: String) = locked {
        passwordUnlocked.remove(scope to owner)?.fill(0)
        Unit
    }

    fun lockPasswordAuthorization(scope: String, owner: String) = locked {
        retirePasswordAuthorization(scope, owner)
    }

    private fun retirePasswordAuthorization(scope: String, owner: String) {
        passwordUnlocked.keys.filter { (vault, value) ->
            vault == scope && (value == owner || value.startsWith(owner + "|"))
        }.forEach { passwordUnlocked.remove(it)?.fill(0) }
    }

    private fun passwordKeyName(scope: String) =
        "auth.storage.password-location.key." + keyName(scope).substringAfterLast('.')

    fun create(scope: String) = locked {
        val name = keyName(scope)
        check(settings.getStringOrNull(name) == null) { "Content key already exists; unlock or recover it" }
        val key = StorageContentEncryptionV1.newKey()
        try { save(scope, Record(key = Base64.encode(key), anchor = Uuid.random().toString())) }
        finally { key.fill(0) }
    }

    fun exportRecovery(scope: String, password: CharArray): ByteArray = locked {
        val record = load(scope)
        check(record.manifestInitialized) { "Initialize authenticated content classification before exporting recovery" }
        val key = decoded(record)
        try { StorageContentRecoveryV2.exportRecovery(key, scope, record.anchor, password) } finally { key.fill(0) }
    }

    fun verifyBackupAndEnable(scope: String, backup: ByteArray, password: CharArray) = locked {
        val record = load(scope)
        val recovery = StorageContentRecoveryV2.importRecovery(backup, scope, password)
        val recovered = recovery.key
        val original = decoded(record)
        try {
            require(record.manifestInitialized && record.anchor == recovery.anchor &&
                storageContentConstantTimeEquals(original, recovered)) { "Backup does not recover this content key and classification" }
            save(scope, record.copy(verified = true, enabled = true))
            lock(scope)
            unlocked[scope] = recovered.copyOf()
        } finally { recovered.fill(0); original.fill(0) }
    }

    fun importRecovery(scope: String, backup: ByteArray, password: CharArray) = locked {
        val recovery = StorageContentRecoveryV2.importRecovery(backup, scope, password)
        val recovered = recovery.key
        try {
            val existing = settings.getStringOrNull(keyName(scope))?.let { load(scope) }
            existing?.takeIf { it.manifestInitialized || it.verified }?.let {
                val original = decoded(it)
                try { require(it.anchor == recovery.anchor && storageContentConstantTimeEquals(original, recovered)) {
                    "Recovery would replace an existing content key or classification"
                } } finally { original.fill(0) }
            }
            save(scope, Record(key = Base64.encode(recovered), anchor = recovery.anchor,
                manifestInitialized = true, verified = true, enabled = existing?.enabled ?: false))
        } finally { recovered.fill(0) }
    }

    fun manifestAnchor(scope: String): String = locked { load(scope).anchor.also(::contentManifestFileName) }

    fun manifestInitialized(scope: String): Boolean = locked { load(scope).manifestInitialized }

    fun keyForManifest(scope: String): ByteArray = locked { decoded(load(scope)) }

    fun markManifestInitialized(scope: String) = locked {
        val record = load(scope)
        save(scope, record.copy(manifestInitialized = true))
    }

    fun unlock(scope: String) = locked {
        val record = load(scope)
        check(record.verified) { "Verify a recovery backup before enabling content encryption" }
        lock(scope)
        unlocked[scope] = decoded(record)
    }

    fun keyForOperation(scope: String): ByteArray = locked {
        check(load(scope).enabled) { "Content encryption is disabled" }
        checkNotNull(unlocked[scope]) { "Content encryption is locked; unlock or recover it" }.copyOf()
    }

    fun keyForReadOperation(scope: String): ByteArray = locked {
        check(load(scope).verified) { "Content key requires backup verification" }
        checkNotNull(unlocked[scope]) { "Content encryption is locked; unlock or recover it" }.copyOf()
    }

    fun hasKey(scope: String): Boolean = locked { settings.getStringOrNull(keyName(scope)) != null }

    fun backupVerified(scope: String): Boolean = locked {
        settings.getStringOrNull(keyName(scope))?.let { load(scope).verified } ?: false
    }

    fun setEnabled(scope: String, enabled: Boolean) = locked {
        val record = load(scope)
        check(!enabled || record.verified) { "Verify a recovery backup before enabling content encryption" }
        save(scope, record.copy(enabled = enabled))
        if (!enabled) lock(scope)
    }

    fun status(scope: String): Pair<Boolean, Boolean> = locked {
        val stored = settings.getStringOrNull(keyName(scope)) ?: return@locked false to false
        val record = json.decodeFromString<Record>(stored)
        require(record.version == 2) { "Unsupported content key format" }
        record.enabled to (scope in unlocked)
    }

    fun lock(scope: String) = locked { unlocked.remove(scope)?.fill(0); Unit }

    override fun close() = locked {
        unlocked.values.forEach { it.fill(0) }
        unlocked.clear()
        passwordUnlocked.values.forEach { it.fill(0) }
        passwordUnlocked.clear()
    }

    private fun keyName(scope: String): String {
        val bytes = scope.encodeToByteArray()
        require(bytes.size in 1..4096) { "Invalid encrypted location" }
        return "auth.storage.content." + StorageContentCrypto.sha256(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    private fun load(scope: String): Record = json.decodeFromString<Record>(
        checkNotNull(settings.getStringOrNull(keyName(scope))) { "Content key is unavailable; import recovery material" }
    ).also { require(it.version == 2) { "Unsupported content key format" } }

    private fun decoded(record: Record): ByteArray = Base64.decode(record.key)
        .also { require(it.size == 32) { "Invalid content key" } }

    private fun save(scope: String, record: Record) { settings.putString(keyName(scope), json.encodeToString(record)) }

    private inline fun <T> locked(block: () -> T): T {
        stateLock.lock()
        return try { block() } finally { stateLock.unlock() }
    }
}

internal expect fun createStorageContentSettings(): Settings

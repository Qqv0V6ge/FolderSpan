package com.folderspan.service.network

import com.folderspan.createSettings
import strings.AppStrings
import com.russhwolf.settings.Settings
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.PlatformUtils
import com.folderspan.service.http.client.createNoProxyHttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

@Serializable
enum class StorageAccountKind { GoogleDrive, Dropbox, Nextcloud, Seafile }

@Serializable
data class StorageAccount(
    val id: String,
    val kind: StorageAccountKind,
    val label: String,
    val username: String = "",
    val server: String = "",
    val needsAuthorization: Boolean = false,
    val readOnly: Boolean = false,
    val libraryId: String = "",
)

@Serializable
internal data class StorageCredential(
    val id: String,
    val kind: StorageAccountKind,
    val providerAccountId: String,
    val label: String,
    val username: String = "",
    val server: String = "",
    val accessToken: String,
    val expiresAt: Long = Long.MAX_VALUE,
    val brokerUrl: String = "",
    val brokerGrant: String = "",
    val readOnly: Boolean = false,
    val libraryId: String = "",
    val requiresAuthorization: Boolean = false,
) {
    override fun toString() = "StorageCredential(id=$id, kind=$kind)"
    fun metadata() = StorageAccount(id, kind, label, username, server, requiresAuthorization || accessToken.isEmpty() && brokerGrant.isEmpty(), readOnly, libraryId)
}

internal fun storageAccountKind(protocol: String): StorageAccountKind = when (protocol) {
    "GoogleDrive" -> StorageAccountKind.GoogleDrive
    "Dropbox" -> StorageAccountKind.Dropbox
    "Nextcloud" -> StorageAccountKind.Nextcloud
    "Seafile" -> StorageAccountKind.Seafile
    else -> error("This storage provider does not use an account")
}

internal fun canonicalStorageServer(server: String): String {
    val url = Url(server)
    require(url.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) && url.host.isNotBlank())
    require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.parameters.isEmpty() && url.fragment.isEmpty())
    return URLBuilder(url).apply { encodedPath = url.encodedPath.trimEnd('/') }.buildString().trimEnd('/')
}

internal fun checkedStorageBrokerUrl(value: String): String {
    val url = Url(value)
    require(url.protocol == URLProtocol.HTTPS ||
        (url.protocol == URLProtocol.HTTP && url.host in setOf("127.0.0.1", "::1", "localhost"))) {
        "Storage authorization requires an HTTPS broker URL"
    }
    require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.parameters.isEmpty() && url.fragment.isEmpty())
    return value.trimEnd('/')
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
internal class StorageAccountStore(
    private val settings: Settings,
    private val refresh: suspend (StorageCredential) -> StorageCredential,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val mutex = Mutex()
    private val accountMutexes = mutableMapOf<String, Mutex>()
    private fun key(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{16,64}"))) { "Invalid storage account reference" }
        return "auth.storage.account.$id"
    }
    private fun load(id: String): StorageCredential =
        settings.getStringOrNull(key(id))?.let { encoded ->
            try { json.decodeFromString<StorageCredential>(encoded) } catch (_: SerializationException) { error("Stored storage account requires authorization") }
        }
            ?: error("Storage account requires authorization")
    private fun save(value: StorageCredential) { settings.putString(key(value.id), json.encodeToString(value)) }

    fun metadata(id: String): StorageAccount = load(id).metadata()

    fun authorizationGeneration(id: String): String {
        key(id)
        return settings.getStringOrNull("auth.storage.account-generation." + id).orEmpty()
    }

    private fun retireUnlocks(id: String) {
        settings.putString("auth.storage.account-generation." + id, kotlin.uuid.Uuid.random().toString())
    }

    fun accounts(): List<StorageAccount> = settings.keys
        .filter { it.startsWith("auth.storage.account.") }
        .map { load(it.removePrefix("auth.storage.account.")).metadata() }

    private suspend fun accountMutex(id: String) = mutex.withLock { accountMutexes.getOrPut(id) { Mutex() } }

    suspend fun install(value: StorageCredential, replaceId: String? = null): StorageAccount {
        require(value.providerAccountId.length in 1..4096 && value.label.length <= 4096 && value.accessToken.length in 1..64 * 1024) { "Invalid storage account credentials" }
        val id = replaceId ?: value.id
        return accountMutex(id).withLock {
            if (replaceId != null) {
                val previous = load(replaceId)
                check(previous.kind == value.kind && previous.providerAccountId == value.providerAccountId && previous.server == value.server) {
                    "Reauthorize with the original storage account, or create a new connection"
                }
            }
            val stored = value.copy(id = id)
            retireUnlocks(id)
            save(stored)
            stored.metadata()
        }
    }

    suspend fun credential(config: NetworkConnectionConfig): StorageCredential {
        val id = config.extras.cloud.accountId
        return accountMutex(id).withLock {
            var value = load(id)
            check(value.kind == storageAccountKind(config.protocol)) { "Storage account belongs to another provider" }
            if (value.kind in setOf(StorageAccountKind.Nextcloud, StorageAccountKind.Seafile)) {
                check(canonicalStorageServer(config.host) == value.server) { "Storage account belongs to another server" }
            }
            if (value.libraryId.isNotEmpty()) check(config.extras.cloud.libraryId == value.libraryId) { "Storage token belongs to another library" }
            check(!value.requiresAuthorization) { AppStrings.storage_reauthorize_required }
            if (value.expiresAt <= now() + 60_000L) {
                check(value.brokerGrant.isNotEmpty()) { "Storage account requires authorization" }
                val updated = try { refresh(value) } catch (error: StorageAuthorizationRequiredException) {
                    retireUnlocks(id)
                    save(value.copy(requiresAuthorization = true))
                    throw error
                }
                check(updated.id == value.id && updated.kind == value.kind && updated.providerAccountId == value.providerAccountId && updated.server == value.server) {
                    "Storage token refresh changed the account identity"
                }
                check(updated.accessToken.isNotEmpty() && updated.expiresAt > now()) { "Storage token refresh returned no usable token" }
                save(updated)
                value = updated
            }
            check(value.accessToken.isNotEmpty()) { "Storage account requires authorization" }
            value
        }
    }

    suspend fun remove(id: String): StorageCredential = accountMutex(id).withLock {
        load(id).also { retireUnlocks(id); settings.remove(key(id)) }
    }
}

internal object StorageAccounts {
    private val client by lazy { createNoProxyHttpClient { expectSuccess = false; followRedirects = false } }
    private val json = Json { ignoreUnknownKeys = false }
    internal val store by lazy {
        check(!PlatformUtils.IS_JS && !PlatformUtils.IS_WASM_JS) { "Storage credentials belong to the selected Runtime" }
        StorageAccountStore(createSettings(), ::refresh)
    }
    fun accounts() = store.accounts()
    fun authorizationGeneration(config: NetworkConnectionConfig): String {
        if (config.protocol !in setOf("GoogleDrive", "Dropbox", "Nextcloud", "Seafile")) return ""
        val id = config.extras.cloud.accountId
        if (!id.matches(Regex("[A-Za-z0-9_-]{16,64}"))) return ""
        return store.authorizationGeneration(id)
    }
    fun capabilities(config: NetworkConnectionConfig): StorageCapabilities {
        val account = runCatching { store.metadata(config.extras.cloud.accountId) }.getOrNull() ?: return StorageCapabilities.Unavailable
        if (account.kind != storageAccountKind(config.protocol) || account.needsAuthorization) return StorageCapabilities.Unavailable
        return if (account.readOnly || config.extras.cloud.readOnly) StorageCapabilities.ReadOnly else StorageCapabilities.ReadWrite
    }
    suspend fun install(value: StorageCredential, replaceId: String? = null) = store.install(value, replaceId)
    suspend fun credential(config: NetworkConnectionConfig) = store.credential(config)
    suspend fun token(config: NetworkConnectionConfig) = credential(config).accessToken

    private suspend fun refresh(value: StorageCredential): StorageCredential {
        val base = checkedStorageBrokerUrl(value.brokerUrl)
        return client.preparePost("$base/refresh") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("grant", value.brokerGrant) }.toString())
        }.execute { response ->
            if (response.status == HttpStatusCode.Unauthorized) throw StorageAuthorizationRequiredException()
            check(response.status.isSuccess()) { "Storage authorization refresh failed (HTTP ${response.status.value})" }
            val envelope = parseStorageJson(response.readStorageBody(1024 * 1024)).storageObject()
            check(envelope.storageLong("code") == 0L) { "Storage authorization refresh failed" }
            val data = envelope["data"]!!.storageObject()
            val refreshed = StorageBrokerAccess(data.storageString("accessToken"), data.storageLong("expiresAt") ?: error("Missing access expiry"), data.storageString("providerAccountId"))
            check(refreshed.providerAccountId == value.providerAccountId) { "Storage account changed while refreshing" }
            value.copy(accessToken = refreshed.accessToken, expiresAt = refreshed.expiresAt)
        }
    }
    suspend fun remove(id: String) {
        val value = store.remove(id)
        if (value.kind == StorageAccountKind.Nextcloud) {
            client.prepareRequest(value.server + "/ocs/v2.php/core/apppassword?format=json") {
                method = HttpMethod.Delete
                basicAuth(value.username, value.accessToken)
                header("OCS-APIRequest", "true")
            }.execute { response -> check(response.status.isSuccess()) { "Local credentials removed; Nextcloud revocation failed" } }
        } else if (value.brokerGrant.isNotEmpty()) {
            val base = checkedStorageBrokerUrl(value.brokerUrl)
            client.preparePost("$base/revoke") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("grant", value.brokerGrant) }.toString())
            }.execute { response -> check(response.status.isSuccess()) { "Local credentials removed; remote revocation failed" } }
        }
    }
}

@Serializable
internal data class StorageBrokerAccess(val accessToken: String, val expiresAt: Long, val providerAccountId: String) {
    override fun toString() = "StorageBrokerAccess(expiresAt=$expiresAt)"
}

internal class StorageAuthorizationRequiredException : Exception(AppStrings.storage_reauthorize_required)

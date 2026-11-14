package com.folderspan.service.network

import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import strings.AppStrings
import com.folderspan.service.http.client.createNoProxyHttpClient
import com.folderspan.utils.secureRandomBytes
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.*
import io.ktor.http.*
import korlibs.crypto.sha256
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.time.Clock

actual object StorageAccountPlatform {
    private val manager by lazy { StorageAuthorizationManager() }
    actual suspend fun accounts() = manager.accounts()
    actual suspend fun begin(request: StorageAuthorizationRequest) = manager.begin(request)
    actual suspend fun poll(handle: String) = manager.poll(handle)
    actual suspend fun cancel(handle: String) = manager.cancel(handle)
    actual suspend fun remove(id: String) = StorageAccounts.remove(id)
    actual suspend fun installSeafile(request: SeafileAuthorizationRequest) = manager.installSeafile(request)
    actual suspend fun libraries(id: String) = manager.libraries(id)
}

internal class StorageAuthorizationManager(
    private val client: HttpClient = createNoProxyHttpClient { expectSuccess = false; followRedirects = false },
    private val store: StorageAccountStore = StorageAccounts.store,
    brokerUrl: String? = OfficialStorageAuthorization.brokerUrl,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val broker = brokerUrl?.let(::checkedStorageBrokerUrl)
    private fun requireBroker() = checkNotNull(broker) { AppStrings.storage_official_authorization_pro_only }
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private class Pending(
        val request: StorageAuthorizationRequest, val expires: Long, val ticket: String = "", val verifier: String = "",
        val server: String = "", val pollUrl: String = "", val pollToken: String = "",
    ) { val mutex = Mutex() }
    private val pending = mutableMapOf<String, Pending>()
    private fun token() = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(secureRandomBytes(32))

    fun accounts() = store.accounts()
    private suspend fun broker(operation: String, body: JsonObject): JsonObject =
        client.preparePost(requireBroker() + "/" + operation) {
            contentType(ContentType.Application.Json); setBody(body.toString())
        }.execute { response ->
            if (response.status.value in setOf(404, 503)) error(AppStrings.storage_auth_not_configured)
            val value = parseStorageJson(response.readStorageBody(1024 * 1024)).storageObject()
            check(response.status.isSuccess() && value.storageLong("code") == 0L) {
                value.storageOptionalString("msg") ?: AppStrings.ui_connection_failed
            }
            value["data"].let { it as? JsonObject ?: error("Invalid storage authorization response") }
        }
    private fun nextcloudUrl(server: String, value: String): String {
        val base = Url(server)
        val target = Url(value)
        require(sameStorageOrigin(base, target) && target.user.isNullOrEmpty() && target.password.isNullOrEmpty() &&
            (base.encodedPath.trimEnd('/').let { it.isEmpty() || target.encodedPath.startsWith(it + "/") })) {
            "Nextcloud authorization URL leaves the configured instance"
        }
        return value
    }

    suspend fun begin(request: StorageAuthorizationRequest): StorageAuthorizationStart {
        request.validatePermissions()
        val kind = storageAccountKind(request.protocol)
        require(kind != StorageAccountKind.Seafile)
        request.replaceAccountId?.let { id -> check(store.metadata(id).kind == kind) { "Storage account belongs to another provider" } }
        val start: StorageAuthorizationStart
        val record: Pending
        val handle = token()
        if (kind == StorageAccountKind.Nextcloud) {
            val server = canonicalStorageServer(request.server)
            val result = client.preparePost(server + "/index.php/login/v2") {
                header(HttpHeaders.UserAgent, "FolderSpan")
            }.execute { response ->
                if (!response.status.isSuccess()) throw StorageApiException(response.status.value)
                parseStorageJson(response.readStorageBody(1024 * 1024)).storageObject()
            }
            val poll = result["poll"]!!.storageObject()
            val expiry = now() + 20 * 60_000L
            record = Pending(request, expiry, server = server, pollUrl = nextcloudUrl(server, poll.storageString("endpoint")), pollToken = poll.storageString("token"))
            start = StorageAuthorizationStart(handle, nextcloudUrl(server, result.storageString("login")), expiresAt = expiry)
        } else {
            val broker = requireBroker()
            val provider = when (kind) { StorageAccountKind.GoogleDrive -> "google"; StorageAccountKind.Dropbox -> "dropbox" }
            val verifier = token()
            val challenge = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(verifier.encodeToByteArray().sha256().bytes)
            val response = broker("start", buildJsonObject {
                put("provider", provider); put("challenge", challenge)
                if (request.permissions.isNotEmpty()) putJsonArray("permissions") {
                    request.permissions.forEach { add(it.name) }
                }
            })
            val expires = response.storageLong("expiresAt") ?: error("Authorization expiry is missing")
            require(expires > now() && expires <= now() + 20 * 60_000L)
            val login = response.storageString("loginUrl")
            val url = Url(login)
            require(sameStorageOrigin(Url(broker), url) && url.encodedPath == Url(broker).encodedPath.trimEnd('/') + "/authorize") { "Authorization URL leaves the configured broker" }
            record = Pending(request, expires, response.storageString("ticket"), verifier)
            val accepted = (response["acceptedPermissions"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
            if (!accepted.containsAll(request.permissions.map { it.name })) {
                cleanup(record)
                error(AppStrings.storage_auth_permissions_unconfirmed)
            }
            start = StorageAuthorizationStart(handle, login, response.storageString("userCode"), expires, request.permissions)
        }
        try {
            mutex.withLock {
                check(pending.size < 32) { "Too many storage authorizations; cancel an existing one" }
                pending[handle] = record
            }
            return start
        } catch (error: Throwable) {
            cleanup(record); throw error
        }
    }

    suspend fun poll(handle: String): StorageAuthorizationPoll {
        val record = mutex.withLock { checkNotNull(pending[handle]) { "Storage authorization expired or cancelled" } }
        return record.mutex.withLock {
            check(mutex.withLock { pending[handle] === record }) { "Storage authorization cancelled" }
            if (now() >= record.expires) {
                cancelRecord(handle, record)
                return@withLock StorageAuthorizationPoll("expired")
            }
            val credential: StorageCredential
            var grantedScopes = emptySet<String>()
            if (record.server.isNotEmpty()) {
                val result = client.preparePost(record.pollUrl) {
                    setBody(FormDataContent(Parameters.build { append("token", record.pollToken) }))
                }.execute { response ->
                    if (response.status == HttpStatusCode.NotFound) null else {
                        if (!response.status.isSuccess()) throw StorageApiException(response.status.value)
                        parseStorageJson(response.readStorageBody(1024 * 1024)).storageObject()
                    }
                } ?: return@withLock StorageAuthorizationPoll("pending")
                check(canonicalStorageServer(result.storageString("server")) == record.server) { "Nextcloud returned another server" }
                val username = result.storageString("loginName")
                val password = result.storageString("appPassword")
                require(username.isNotEmpty() && password.isNotEmpty())
                val user = try {
                    client.prepareGet(record.server + "/ocs/v2.php/cloud/user?format=json") {
                        basicAuth(username, password); header("OCS-APIRequest", "true")
                    }.execute { response ->
                        if (!response.status.isSuccess()) throw StorageApiException(response.status.value)
                        parseStorageJson(response.readStorageBody(1024 * 1024)).storageObject()["ocs"]!!.storageObject()["data"]!!.storageObject()
                    }
                } catch (error: Throwable) {
                    withContext(NonCancellable) { withTimeoutOrNull(3_000L) { runCatching { revokeNextcloud(record.server, username, password) } } }
                    cancelRecord(handle, record)
                    throw error
                }
                credential = StorageCredential(token(), StorageAccountKind.Nextcloud, user.storageString("id"), user.storageOptionalString("display-name") ?: username,
                    username = username, server = record.server, accessToken = password)
            } else {
                val result = broker("poll", buildJsonObject { put("ticket", record.ticket); put("verifier", record.verifier) })
                val status = result.storageString("status")
                if (status != "authorized") {
                    if (status in setOf("denied", "failed", "cancelled", "expired")) cancelRecord(handle, record)
                    return@withLock StorageAuthorizationPoll(status)
                }
                grantedScopes = (result["grantedScopes"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                credential = StorageCredential(token(), storageAccountKind(record.request.protocol), result.storageString("providerAccountId"),
                    result.storageString("label"), accessToken = result.storageString("accessToken"),
                    expiresAt = result.storageLong("expiresAt") ?: error("Authorization expiry is missing"),
                    brokerUrl = requireBroker(), brokerGrant = result.storageString("grant"))
            }
            val installed = try {
                check(record.request.hasGrantedPermissions(grantedScopes)) { AppStrings.storage_auth_permissions_unconfirmed }
                currentCoroutineContext().ensureActive()
                store.install(credential, record.request.replaceAccountId)
            }
            catch (error: Throwable) {
                withContext(NonCancellable) { withTimeoutOrNull(3_000L) { runCatching {
                    if (credential.kind == StorageAccountKind.Nextcloud) revokeNextcloud(credential.server, credential.username, credential.accessToken)
                    else if (credential.brokerGrant.isNotEmpty()) broker("revoke", buildJsonObject {
                        put("grant", credential.brokerGrant)
                        if (credential.kind == StorageAccountKind.GoogleDrive) put("forgetOnly", true)
                    })
                } } }
                throw error
            }
            mutex.withLock { pending.remove(handle) }
            StorageAuthorizationPoll("authorized", installed)
        }
    }

    private suspend fun revokeNextcloud(server: String, username: String, password: String) {
        client.prepareRequest(server + "/ocs/v2.php/core/apppassword?format=json") {
            method = HttpMethod.Delete
            basicAuth(username, password)
            header("OCS-APIRequest", "true")
        }.execute {}
    }

    private suspend fun cleanup(record: Pending) {
        if (record.ticket.isNotEmpty()) withContext(NonCancellable) { withTimeoutOrNull(3_000L) {
            runCatching { broker("cancel", buildJsonObject { put("ticket", record.ticket); put("verifier", record.verifier) }) }
        } }
    }
    private suspend fun cancelRecord(handle: String, record: Pending) {
        mutex.withLock { pending.remove(handle) }
        cleanup(record)
    }
    suspend fun cancel(handle: String) {
        val record = mutex.withLock { pending[handle] } ?: return
        record.mutex.withLock { cancelRecord(handle, record) }
    }

    suspend fun installSeafile(request: SeafileAuthorizationRequest): StorageAccount {
        val server = canonicalStorageServer(request.server)
        require(request.token.isNotBlank() && request.token.length <= 4096 && request.token.none(Char::isISOControl))
        val api = StorageHttpApi({ request.token }, client)
        val identity = api.json(HttpMethod.Get, server + if (request.repoToken) "/api/v2.1/via-repo-token/repo-info/" else "/api2/account/info/").storageObject()
        val libraryId = if (request.repoToken) identity.storageString("repo_id") else ""
        require(request.libraryId.isEmpty() || libraryId.isEmpty() || libraryId == request.libraryId) { "Seafile token belongs to another library" }
        val readOnly = if (request.repoToken) {
            val directory = api.json(HttpMethod.Get, server + "/api/v2.1/via-repo-token/dir/?path=%2F").storageObject()
            directory.storageOptionalString("user_perm") != "rw"
        } else false
        val account = StorageCredential(token(), StorageAccountKind.Seafile,
            if (request.repoToken) "repo:" + libraryId else identity.storageString("email"),
            identity.storageOptionalString(if (request.repoToken) "repo_name" else "email") ?: "Seafile",
            server = server, accessToken = request.token, libraryId = libraryId, readOnly = readOnly)
        return store.install(account, request.replaceAccountId)
    }

    suspend fun libraries(id: String): List<StorageLibrary> {
        val account = store.metadata(id)
        require(account.kind == StorageAccountKind.Seafile)
        val config = NetworkConnectionConfig("Seafile", account.server, "", "", extras =
            NetworkDriveExtras(cloud =
                CloudDriveExtras(accountId = id, libraryId = account.libraryId)))
        val value = store.credential(config)
        val api = StorageHttpApi({ value.accessToken }, client)
        if (value.libraryId.isNotEmpty()) {
            val result = api.json(HttpMethod.Get, value.server + "/api/v2.1/via-repo-token/repo-info/").storageObject()
            check(result.storageString("repo_id") == value.libraryId)
            return listOf(StorageLibrary(value.libraryId, result.storageString("repo_name"), value.readOnly))
        }
        val result = api.json(HttpMethod.Get, value.server + "/api2/repos/") as? JsonArray ?: error("Invalid Seafile library list")
        check(result.size <= 500_000)
        return result.map { item -> item.storageObject().let { StorageLibrary(it.storageString("id"), it.storageString("name"), it.storageOptionalString("permission") != "rw") } }
    }
}

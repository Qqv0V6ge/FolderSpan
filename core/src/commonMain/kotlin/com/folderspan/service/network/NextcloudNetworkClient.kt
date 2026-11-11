package com.folderspan.service.network

import com.folderspan.data.main.network.WebDavDriveExtras
import io.ktor.http.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.*
import io.ktor.utils.io.readAvailable
import okio.Buffer
import com.folderspan.service.http.client.createNoProxyHttpClient
import kotlinx.serialization.json.*
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.io.encoding.Base64

internal fun nextcloudExpiryDate(deadline: Long, timeZone: TimeZone, now: Long): String {
    var date = kotlin.time.Instant.fromEpochMilliseconds(deadline).toLocalDateTime(timeZone).date
    fun endOfDay() = LocalDateTime(date, LocalTime(23, 59, 59)).toInstant(timeZone).toEpochMilliseconds()
    if (endOfDay() > deadline) date = date.minus(1, DateTimeUnit.DAY)
    val selectedEnd = endOfDay()
    require(selectedEnd <= deadline && selectedEnd > now) { "Nextcloud cannot represent this deadline as a future whole-day expiration" }
    return date.toString()
}

internal class NextcloudNetworkClient(
    private val config: NetworkConnectionConfig,
    private val credential: suspend () -> StorageCredential = { StorageAccounts.credential(config) },
    private val capability: () -> StorageCapabilities = { StorageAccounts.capabilities(config) },
    private val overrideClient: HttpClient? = null,
) : StorageProvider, ChunkReadableNetworkClient {
    override val capabilities get() = capability()
    private val advancedHttpDelegate = lazy { overrideClient ?: createNoProxyHttpClient { expectSuccess = false; followRedirects = false } }
    private val advancedHttp by advancedHttpDelegate

    private suspend fun <T> use(block: suspend (WebDavNetworkClient) -> Result<T>): Result<T> = storageResult {
        val account = credential()
        check(account.kind == StorageAccountKind.Nextcloud && account.server == canonicalStorageServer(config.host)) {
            "Nextcloud account belongs to another server"
        }
        val root = config.extras.cloud.rootId
        require(root.isEmpty() || root.startsWith('/')) { "Nextcloud root must be an absolute path" }
        requireSafeNetworkWritePath(root.ifBlank { "/" })
        require(account.providerAccountId.isNotEmpty() && !isUnsafeNetworkPathSegment(account.providerAccountId)) { "Invalid Nextcloud user ID" }
        val host = storageUrl(account.server, listOf("remote.php", "dav", "files", account.providerAccountId) +
            root.trim('/').split('/').filter { it.isNotEmpty() })
        val delegate = WebDavNetworkClient(config.copy(
            protocol = "WebDav", host = host, username = account.username, password = account.accessToken,
            extras = config.extras.copy(webdav = WebDavDriveExtras(headers = mapOf(
                HttpHeaders.Authorization to "Basic " + Base64.encode(
                    (account.username + ":" + account.accessToken).encodeToByteArray()),
            ))),
        ), advancedHttp)
        // The provider owns the shared transport; this wrapper only supplies the current credentials.
        block(delegate).getOrThrow()
    }

    override suspend fun list(path: String, requestId: String?, batchId: String?) = use { delegate ->
        storageResult {
            val account = account()
            val remote = checkedRemote(path)
            val target = dav(account, "files", account.providerAccountId,
                *remote.trim('/').split('/').filter { it.isNotEmpty() }.toTypedArray())
            delegate.parsePropfindResponse(advancedRequest(account, HttpMethod("PROPFIND"), target, "1"),
                path, includeNextcloudIds = true)
        }
    }
    override suspend fun download(remotePath: String, localPath: String, size: Long, onProgress: (Long, Long) -> Unit) =
        use { it.download(remotePath, localPath, size, onProgress) }
    override suspend fun downloadByChunks(remotePath: String, size: Long, onChunk: suspend (ByteArray, Long) -> Result<Unit>) =
        use { it.downloadByChunks(remotePath, size, onChunk) }
    override suspend fun upload(localPath: String, remotePath: String, size: Long, onProgress: (Long, Long) -> Unit) =
        use { check(capabilities.write) { "Nextcloud account is read-only" }; it.upload(localPath, remotePath, size, onProgress) }
    override suspend fun uploadFromSource(remotePath: String, size: Long, onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?) =
        use { check(capabilities.write) { "Nextcloud account is read-only" }; it.uploadFromSource(remotePath, size, onProgress, readChunk) }
    override suspend fun copyFile(sourcePath: String, targetPath: String) =
        use { check(capabilities.write) { "Nextcloud account is read-only" }; it.copyFile(sourcePath, targetPath) }
    override suspend fun rename(path: String, newPath: String) =
        use { check(capabilities.rename) { "Nextcloud account is read-only" }; it.rename(path, newPath) }
    override suspend fun delete(path: String, isDirectory: Boolean) =
        use { check(capabilities.delete) { "Nextcloud account is read-only" }; it.delete(path, isDirectory) }
    override suspend fun createFolder(path: String) =
        use { check(capabilities.write) { "Nextcloud account is read-only" }; it.createFolder(path) }
    override suspend fun createFile(path: String) =
        use { check(capabilities.write) { "Nextcloud account is read-only" }; it.createFile(path) }

    private suspend fun validateContainerIdentity(path: StoragePath) {
        val reference = path.reference ?: return
        reference.ancestorId.takeIf { it.isNotBlank() }?.let { ancestorId ->
            require(reference.ancestorPath.isNotBlank() &&
                (path.path == reference.ancestorPath || path.path.startsWith(reference.ancestorPath.trimEnd('/') + "/"))) {
                "Nextcloud path is outside its selected ancestor"
            }
            metadata(StoragePath(reference.ancestorPath, StorageReference(ancestorId)))
        }
        reference.parentId.takeIf { it.isNotBlank() }?.let { parentId ->
            metadata(StoragePath(path.path.trimEnd('/').substringBeforeLast('/', "/").ifEmpty { "/" },
                StorageReference(parentId)))
        }
    }

    private suspend fun <T> withIdentity(path: StoragePath, block: suspend () -> Result<T>): Result<T> = storageResult {
        if (!path.reference?.id.isNullOrBlank()) metadata(path) else validateContainerIdentity(path)
        block().getOrThrow()
    }

    override suspend fun stat(path: StoragePath): Result<NetworkFileEntry> = storageResult {
        val (_, id, properties) = try { metadata(path) } catch (error: StorageApiException) {
            if (error.status == 404) throw StorageEntryNotFoundException()
            throw error
        }
        val directory = Regex("<(?:[A-Za-z0-9_]+:)?collection\\b").containsMatchIn(properties)
        NetworkFileEntry(path.path.trimEnd('/').substringAfterLast('/'), path.path, directory,
            size = if (directory) -1 else property(properties, "getcontentlength")?.toLongOrNull() ?: -1,
            updatedDate = property(properties, "getlastmodified")?.let {
                runCatching { it.fromHttpToGmtDate().timestamp }.getOrDefault(0L)
            } ?: 0L, reference = StorageReference(id))
    }

    override suspend fun list(path: StoragePath, requestId: String?, batchId: String?) =
        withIdentity(path) { list(path.path, requestId, batchId) }
    override suspend fun download(remotePath: StoragePath, localPath: String, size: Long, onProgress: (Long, Long) -> Unit) =
        withIdentity(remotePath) { download(remotePath.path, localPath, size, onProgress) }
    override suspend fun downloadByChunks(remotePath: StoragePath, size: Long, onChunk: suspend (ByteArray, Long) -> Result<Unit>) =
        withIdentity(remotePath) { downloadByChunks(remotePath.path, size, onChunk) }
    override suspend fun downloadForContentVerification(remotePath: StoragePath, size: Long,
        onChunk: suspend (ByteArray, Long) -> Result<Unit>): Result<Boolean> = storageResult {
        validateContainerIdentity(remotePath)
        downloadByChunks(remotePath.path, size, onChunk).getOrThrow()
    }
    override suspend fun downloadContentMetadata(directory: StoragePath, entries: List<NetworkFileEntry>,
        maxEntryBytes: Int): Result<Map<String, ByteArray>?> = storageResult {
        require(maxEntryBytes in 1..65584 && entries.size in 1..NEXTCLOUD_METADATA_BATCH_MAX_FILES) {
            "Invalid metadata batch limits"
        }
        val prefix = directory.path.trimEnd('/') + "/"
        require(entries.all { !it.isDirectory && !it.isSymbolicLink &&
            it.path == prefix + it.name && it.size in 0..maxEntryBytes.toLong() } &&
            entries.map { it.name }.toSet().size == entries.size) { "Invalid metadata batch entries" }
        val expected = entries.associate { it.name to it.size }
        val limit = maxNextcloudMetadataTarBytes(expected)
        withIdentity(directory) {
            storageResult {
                val account = account()
                val remote = checkedRemote(directory.path)
                val target = dav(account, "files", account.providerAccountId,
                    *remote.trim('/').split('/').filter { it.isNotEmpty() }.toTypedArray())
                advancedHttp.prepareGet(target) {
                    basicAuth(account.username, account.accessToken)
                    header(HttpHeaders.Accept, "application/x-tar")
                    header(HttpHeaders.AcceptEncoding, "identity")
                    url { parameters.append("files", JsonArray(entries.map { JsonPrimitive(it.name) }).toString()) }
                }.execute { response ->
                    if (response.status.value in setOf(404, 405, 406, 415, 501)) return@execute null
                    if (!response.status.isSuccess()) throw StorageApiException(response.status.value)
                    val type = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
                    if (type != null && type !in setOf("application/x-tar", "application/tar")) return@execute null
                    require(type != null) { "Metadata archive has no content type" }
                    require(response.contentLength()?.let { it in 0..limit } != false) { "Metadata archive exceeds its limit" }
                    val channel = response.bodyAsChannel()
                    val body = Buffer()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = channel.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (count == 0) continue
                        require(body.size + count <= limit) { "Metadata archive exceeds its limit" }
                        body.write(buffer, 0, count)
                    }
                    parseNextcloudMetadataTar(body.readByteArray(), expected)
                }
            }
        }.getOrThrow()
    }
    override suspend fun upload(localPath: String, remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit) =
        withIdentity(remotePath) { upload(localPath, remotePath.path, size, onProgress) }
    override suspend fun uploadFromSource(remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit,
        readChunk: suspend () -> ByteArray?) =
        withIdentity(remotePath) { uploadFromSource(remotePath.path, size, onProgress, readChunk) }
    override suspend fun copyFile(sourcePath: StoragePath, targetPath: StoragePath) =
        withIdentity(sourcePath) { withIdentity(targetPath) { copyFile(sourcePath.path, targetPath.path) } }
    override suspend fun rename(path: StoragePath, newPath: String) = withIdentity(path) { rename(path.path, newPath) }
    override suspend fun delete(path: StoragePath, isDirectory: Boolean) = withIdentity(path) { delete(path.path, isDirectory) }
    override suspend fun createFolder(path: StoragePath) = withIdentity(path) { createFolder(path.path) }
    override suspend fun createFile(path: StoragePath) = withIdentity(path) { createFile(path.path) }

    private suspend fun account(): StorageCredential = credential().also {
        check(it.kind == StorageAccountKind.Nextcloud && it.server == canonicalStorageServer(config.host)) { "Nextcloud account belongs to another server" }
        require(it.providerAccountId.isNotBlank() && !isUnsafeNetworkPathSegment(it.providerAccountId)) { "Invalid Nextcloud user ID" }
    }

    private fun checkedRemote(path: String): String {
        require(path.startsWith('/') && path.length <= 16 * 1024 && path.none(Char::isISOControl)) { "Invalid Nextcloud path" }
        requireSafeNetworkWritePath(path)
        val root = config.extras.cloud.rootId.ifBlank { "/" }.trimEnd('/')
        requireSafeNetworkWritePath(root.ifBlank { "/" })
        return root + path
    }

    override val contentManifestReadable: Boolean get() = true
    // The verified Nextcloud 34 instance accepts concurrent stale ETags; reads remain necessary for recovery detection.
    override val contentManifestConditionalWrites: Boolean get() = false

    private suspend fun manifestUrl(anchor: String): Pair<StorageCredential, String> {
        val account = account()
        val remote = checkedRemote("/" + contentManifestFileName(anchor))
        return account to dav(account, "files", account.providerAccountId,
            *remote.trim('/').split('/').filter { it.isNotEmpty() }.toTypedArray())
    }

    override suspend fun readContentManifest(anchor: String): Result<StorageContentManifestSnapshot?> = storageResult {
        val (account, target) = manifestUrl(anchor)
        advancedHttp.prepareGet(target) { basicAuth(account.username, account.accessToken) }.execute { response ->
            if (response.status == HttpStatusCode.NotFound) null
            else {
                if (!response.status.isSuccess()) throw StorageApiException(response.status.value)
                val revision = response.headers[HttpHeaders.ETag].orEmpty()
                require(revision.startsWith('\"') && revision.endsWith('\"') && !revision.startsWith("W/")) {
                    "Nextcloud did not return a strong content manifest revision"
                }
                StorageContentManifestSnapshot(revision, response.readStorageBody(STORAGE_CONTENT_MANIFEST_LIMIT.toLong()).encodeToByteArray())
            }
        }
    }

    override suspend fun compareAndSetContentManifest(
        anchor: String, expectedRevision: String?, bytes: ByteArray,
    ): Result<StorageContentManifestSnapshot> = Result.failure(UnsupportedOperationException(
        "Nextcloud has no verified atomic content metadata update; encrypted writes are unavailable"))

    override suspend fun contentObjectIdentity(path: StoragePath): Result<String?> = storageResult {
        try { metadata(path).second } catch (error: StorageApiException) {
            if (error.status == 404) null else throw error
        }
    }

    private val connectionBinding get() = storageConnectionBinding(config, config.extras.cloud.rootId.ifBlank { "/" }.trimEnd('/').ifBlank { "/" })

    private fun requireBinding(binding: String) {
        require(binding.isNotBlank() && binding == connectionBinding) { "Nextcloud operation belongs to another connection" }
    }

    private fun dav(account: StorageCredential, vararg parts: String) =
        storageUrl(account.server, listOf("remote.php", "dav") + parts)

    private suspend fun advancedRequest(account: StorageCredential, method: HttpMethod, target: String,
        depth: String? = null, destination: String? = null, form: Map<String, String>? = null): String =
        advancedHttp.prepareRequest(target) {
            this.method = method
            basicAuth(account.username, account.accessToken)
            header("OCS-APIRequest", "true")
            if (depth != null) {
                header("Depth", depth)
                contentType(ContentType.Application.Xml)
                setBody("""<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><oc:fileid/><oc:permissions/><d:displayname/><d:getcontentlength/><d:resourcetype/><d:getlastmodified/></d:prop></d:propfind>""")
            }
            if (destination != null) header("Destination", destination)
            if (form != null) setBody(FormDataContent(Parameters.build { form.forEach { (key, value) -> append(key, value) } }))
        }.execute { response ->
            if (!response.status.isSuccess()) throw StorageApiException(response.status.value)
            response.readStorageBody(8L * 1024 * 1024)
        }

    private fun property(block: String, name: String): String? =
        Regex("<(?:[A-Za-z0-9_]+:)?$name(?:\\s[^>]*)?>([^<]*)</(?:[A-Za-z0-9_]+:)?$name>").find(block)?.groupValues?.get(1)?.trim()

    private fun successfulProperties(xml: String): String {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) { "Invalid Nextcloud XML" }
        return Regex("<(?:[A-Za-z0-9_]+:)?propstat\\b[^>]*>([\\s\\S]*?)</(?:[A-Za-z0-9_]+:)?propstat>")
            .findAll(xml).map { it.groupValues[1] }.filter { property(it, "status")?.contains(" 200 ") == true }.joinToString("")
    }

    private suspend fun metadata(path: StoragePath): Triple<StorageCredential, String, String> {
        validateContainerIdentity(path)
        val account = account()
        val remote = checkedRemote(path.path)
        val target = dav(account, "files", account.providerAccountId, *remote.trim('/').split('/').filter { it.isNotEmpty() }.toTypedArray())
        val properties = successfulProperties(advancedRequest(account, HttpMethod("PROPFIND"), target, "0"))
        val id = property(properties, "fileid").orEmpty()
        require(id.matches(Regex("[0-9]+"))) { "Nextcloud instance does not expose a stable file ID" }
        require(path.reference?.id.isNullOrEmpty() || path.reference.id == id) { "Nextcloud file identity changed" }
        return Triple(account, id, properties)
    }

    private fun versionsUrl(account: StorageCredential, id: String, revision: String? = null): String {
        require(id.matches(Regex("[0-9]+")) && (revision == null || revision.matches(Regex("[0-9]+")))) { "Invalid Nextcloud revision" }
        return dav(account, "versions", account.providerAccountId, "versions", id, *listOfNotNull(revision).toTypedArray())
    }

    private fun sharesUrl(account: StorageCredential, id: String? = null, path: String? = null): String {
        require(id == null || id.matches(Regex("[0-9]+"))) { "Invalid Nextcloud share" }
        return storageUrl(account.server, listOf("ocs", "v2.php", "apps", "files_sharing", "api", "v1", "shares") + listOfNotNull(id),
            buildMap { put("format", "json"); if (path != null) put("path", checkedRemote(path)) })
    }

    private suspend fun ocs(account: StorageCredential, method: HttpMethod, target: String, form: Map<String, String>? = null): JsonElement {
        val body = parseStorageJson(advancedRequest(account, method, target, form = form)).storageObject()["ocs"]?.storageObject()
            ?: error("Invalid Nextcloud share response")
        val status = body["meta"]?.storageObject()?.storageLong("statuscode")
        check(status == 100L || status == 200L) { "Nextcloud sharing operation was rejected" }
        return body["data"] ?: JsonNull
    }

    private suspend fun sharingTimeZone(account: StorageCredential): TimeZone {
        val target = storageUrl(account.server, listOf("ocs", "v2.php", "cloud", "capabilities"), mapOf("format" to "json"))
        val capabilities = ocs(account, HttpMethod.Get, target).storageObject()["capabilities"]?.storageObject()
        val core = capabilities?.get("core")?.storageObject()
        val user = core?.get("user")?.storageObject()
        val name = user?.storageOptionalString("timezone")?.takeIf { it.isNotBlank() }
            ?: error("Nextcloud did not confirm the timezone required for share expiration")
        return TimeZone.of(name)
    }

    private fun sharingTimeZoneForOperation(account: StorageCredential): suspend () -> TimeZone {
        var resolved: TimeZone? = null
        return { resolved ?: sharingTimeZone(account).also { resolved = it } }
    }

    private suspend fun featureProbe(block: suspend () -> Unit): StorageFeatureAvailability = try {
        block(); StorageFeatureAvailability.AVAILABLE
    } catch (error: StorageApiException) {
        when (error.status) {
            401 -> StorageFeatureAvailability.AUTHORIZATION_REQUIRED
            403 -> StorageFeatureAvailability.PERMISSION_DENIED
            404, 405, 501 -> StorageFeatureAvailability.UNSUPPORTED
            else -> throw error
        }
    }

    override suspend fun advancedCapabilities(path: StoragePath): Result<StorageAdvancedCapabilities> = storageResult {
        val (account, id, properties) = try { metadata(path) } catch (error: StorageApiException) {
            if (error.status == 404) return@storageResult StorageAdvancedCapabilities()
            throw error
        }
        val permission = property(properties, "permissions").orEmpty()
        val directory = Regex("<(?:[A-Za-z0-9_]+:)?collection\\b").containsMatchIn(properties)
        val versions = if (directory) StorageFeatureAvailability.UNSUPPORTED else featureProbe {
            advancedRequest(account, HttpMethod("PROPFIND"), versionsUrl(account, id), "0")
        }
        val shares = featureProbe { ocs(account, HttpMethod.Get, sharesUrl(account, path = path.path)) }
        val canShare = if (capabilities.write && 'R' in permission) shares else StorageFeatureAvailability.PERMISSION_DENIED
        val canRestore = if (capabilities.write && 'W' in permission) versions else StorageFeatureAvailability.PERMISSION_DENIED
        StorageAdvancedCapabilities(
            features = mapOf(
                StorageAdvancedFeature.RESUMABLE_UPLOAD to StorageFeatureAvailability.UNSUPPORTED,
                StorageAdvancedFeature.LIST_VERSIONS to versions, StorageAdvancedFeature.READ_VERSION to versions,
                StorageAdvancedFeature.RESTORE_VERSION to canRestore, StorageAdvancedFeature.LIST_SHARES to shares,
                StorageAdvancedFeature.CREATE_LINK to canShare, StorageAdvancedFeature.INVITE_USER to canShare,
                StorageAdvancedFeature.REVOKE_SHARE to canShare,
            ), shareRoles = setOf("reader", "writer", "reader+reshare", "writer+reshare"), shareExpiry = true,
        )
    }

    override suspend fun listVersions(path: StoragePath, cursor: String?): Result<StoragePage<StorageVersion>> = storageResult {
        require(cursor == null) { "Nextcloud versions do not use a page cursor" }
        advancedCapabilities(path).getOrThrow().require(StorageAdvancedFeature.LIST_VERSIONS)
        val (account, id) = metadata(path)
        val xml = advancedRequest(account, HttpMethod("PROPFIND"), versionsUrl(account, id), "1")
        val blocks = Regex("<(?:[A-Za-z0-9_]+:)?response\\b[^>]*>([\\s\\S]*?)</(?:[A-Za-z0-9_]+:)?response>").findAll(xml)
        val versions = blocks.mapNotNull { match ->
            val block = match.groupValues[1]
            val revision = property(block, "href")?.trimEnd('/')?.substringAfterLast('/') ?: return@mapNotNull null
            if (!revision.matches(Regex("[0-9]+")) || revision == id && Regex("<(?:[A-Za-z0-9_]+:)?collection\\b").containsMatchIn(block)) return@mapNotNull null
            val properties = successfulProperties(block)
            if (properties.isEmpty()) return@mapNotNull null
            StorageVersion(path.copy(reference = StorageReference(id)), revision,
                property(properties, "getcontentlength")?.toLongOrNull()?.takeIf { it >= 0 },
                revision.toLongOrNull()?.takeIf { it <= Long.MAX_VALUE / 1000 }?.times(1000), connectionBinding)
        }.toList()
        check(versions.size <= 100_000 && versions.map { it.id }.toSet().size == versions.size) { "Invalid Nextcloud history response" }
        StoragePage(versions)
    }

    override suspend fun readVersion(version: StorageVersion, onChunk: suspend (ByteArray, Long) -> Result<Unit>): Result<Unit> = storageResult {
        requireBinding(version.connectionBinding)
        advancedCapabilities(version.file).getOrThrow().require(StorageAdvancedFeature.READ_VERSION)
        val (account, id) = metadata(version.file)
        val target = Url(versionsUrl(account, id, version.id))
        advancedHttp.readStorageFile(target, version.size ?: -1L,
            mapOf(HttpHeaders.Authorization to "Basic " + kotlin.io.encoding.Base64.encode((account.username + ":" + account.accessToken).encodeToByteArray())),
            { sameStorageOrigin(it, target) }, onChunk = onChunk).getOrThrow()
    }

    override suspend fun restoreVersion(version: StorageVersion, confirmed: Boolean): Result<NetworkFileEntry> = storageResult {
        require(confirmed) { "Confirm the selected version before restoring" }
        requireBinding(version.connectionBinding)
        advancedCapabilities(version.file).getOrThrow().require(StorageAdvancedFeature.RESTORE_VERSION)
        val (account, id) = metadata(version.file)
        val source = versionsUrl(account, id, version.id)
        advancedRequest(account, HttpMethod("PROPFIND"), source, "0")
        advancedRequest(account, HttpMethod("MOVE"), source, destination = dav(account, "versions", account.providerAccountId, "restore", id))
        val (_, restoredId, properties) = metadata(version.file)
        check(restoredId == id) { "Nextcloud restored a different file" }
        val size = property(properties, "getcontentlength")?.toLongOrNull() ?: -1L
        check(version.size == null || version.size == size) { "Nextcloud restore result is uncertain" }
        NetworkFileEntry(version.file.path.substringAfterLast('/'), version.file.path, false, size, reference = StorageReference(id))
    }

    private suspend fun share(account: StorageCredential, file: StoragePath, value: JsonElement,
        timeZone: suspend () -> TimeZone): StorageShare {
        val objectValue = value.storageObject()
        check(objectValue.storageString("path").trimEnd('/') == checkedRemote(file.path).trimEnd('/')) { "Nextcloud share belongs to another file" }
        val type = objectValue.storageLong("share_type")
        require(type == 0L || type == 3L) { "Nextcloud share type is unsupported" }
        val permissions = objectValue.storageLong("permissions") ?: error("Nextcloud did not confirm share permissions")
        val role = when (permissions) { 1L -> "reader"; 3L -> "writer"; 17L -> "reader+reshare"; 19L -> "writer+reshare"; else -> "permissions:$permissions" }
        val expiry = objectValue.storageOptionalString("expiration")?.takeIf { it.isNotBlank() }?.let {
            val timestamp = it.replace(' ', 'T')
            val instant = runCatching { kotlin.time.Instant.parse(timestamp) }.getOrNull()
                ?: LocalDateTime.parse(timestamp).toInstant(timeZone())
            instant.toEpochMilliseconds().also { date -> require(date > 0) { "Nextcloud returned an unknown share expiration" } }
        }
        return StorageShare(file, objectValue.storageString("id"), if (type == 3L) StorageShareKind.LINK else StorageShareKind.INVITATION, role,
            if (type == 0L) objectValue.storageOptionalString("share_with") else null, expiry,
            if (type == 3L) objectValue.storageOptionalString("url") else null,
            objectValue.storageOptionalString("uid_initiator") == account.providerAccountId || objectValue.storageOptionalString("uid_owner") == account.providerAccountId, connectionBinding)
    }

    override suspend fun listShares(path: StoragePath, cursor: String?): Result<StoragePage<StorageShare>> = storageResult {
        require(cursor == null) { "Nextcloud shares do not use a page cursor" }
        val (account, id) = metadata(path)
        val selected = path.copy(reference = StorageReference(id))
        val timeZone = sharingTimeZoneForOperation(account)
        val values = ocs(account, HttpMethod.Get, sharesUrl(account, path = path.path)) as? JsonArray ?: error("Invalid Nextcloud share list")
        StoragePage(values.filter { it.storageObject().storageLong("share_type") in listOf(0L, 3L) }.map { share(account, selected, it, timeZone) })
    }

    override suspend fun createShare(path: StoragePath, request: StorageShareRequest, confirmed: Boolean): Result<StorageShare> = storageResult {
        require(confirmed) { "Confirm the selected file, recipient and permissions before sharing" }
        advancedCapabilities(path).getOrThrow().validateShare(request)
        require(request.kind == StorageShareKind.LINK || request.expiresAt == null) { "Nextcloud invitation expiration is unsupported" }
        require(request.expiresAt == null || request.expiresAt > kotlin.time.Clock.System.now().toEpochMilliseconds()) { "Share expiration must be in the future" }
        val (account, fileId) = metadata(path)
        val selected = path.copy(reference = StorageReference(fileId))
        val timeZone = sharingTimeZoneForOperation(account)
        val expiryDate = request.expiresAt?.let {
            nextcloudExpiryDate(it, timeZone(), kotlin.time.Clock.System.now().toEpochMilliseconds())
        }
        val form = buildMap {
            put("path", checkedRemote(path.path)); put("shareType", if (request.kind == StorageShareKind.LINK) "3" else "0")
            put("permissions", when (request.role) {
                "reader" -> "1"; "writer" -> "3"; "reader+reshare" -> "17"; "writer+reshare" -> "19"
                else -> error("Unsupported Nextcloud role")
            }); put("sendMail", "false")
            request.recipient?.let { put("shareWith", it) }; expiryDate?.let { put("expireDate", it) }
        }
        val created = ocs(account, HttpMethod.Post, sharesUrl(account), form)
        val createdId = created.storageObject().storageOptionalString("id")
        check(createdId != null && createdId.matches(Regex("[0-9]+"))) {
            "Nextcloud share result is uncertain; no new grant identity was confirmed"
        }
        try {
            val result = share(account, selected, created, timeZone)
            check(result.role == request.role && result.kind == request.kind && result.recipient == request.recipient &&
                (request.expiresAt == null || result.expiresAt != null && result.expiresAt <= request.expiresAt)) {
                "Nextcloud returned different sharing restrictions"
            }
            currentCoroutineContext().ensureActive()
            result
        } catch (error: Throwable) {
            compensateStorageShare(error) {
                ocs(account, HttpMethod.Delete, sharesUrl(account, createdId))
            }
        }
    }

    override suspend fun revokeShare(share: StorageShare): Result<Unit> = storageResult {
        requireBinding(share.connectionBinding)
        val (account) = metadata(share.file)
        val response = ocs(account, HttpMethod.Get, sharesUrl(account, share.id))
        val selected = if (response is JsonArray) response.singleOrNull()
            ?: error("Nextcloud did not return exactly one selected share") else response
        val current = share(account, share.file, selected, sharingTimeZoneForOperation(account))
        require(current.id == share.id && current.canRevoke) { "Nextcloud share belongs to another account" }
        ocs(account, HttpMethod.Delete, sharesUrl(account, share.id))
    }

    override suspend fun close() {
        if (advancedHttpDelegate.isInitialized() && overrideClient == null) advancedHttp.close()
    }
}

package com.folderspan.service.network

import com.folderspan.utils.secureRandomBytes
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SeafileNetworkClient(
    private val config: NetworkConnectionConfig,
    private val api: StorageHttpApi = storageHttpApi(config),
) : StorageProvider, ChunkReadableNetworkClient {
    override val capabilities get() = api.capabilities()
    private val server = canonicalStorageServer(config.host)
    private val repo = config.extras.cloud.libraryId.also {
        require(it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) { "Select a Seafile library" }
    }
    private val repoToken = config.extras.cloud.seafileRepoToken
    private val root = checkedPath(config.extras.cloud.rootId.ifBlank { "/" }).trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    private fun checkedPath(path: String): String {
        require(path.startsWith('/') && path.length <= 16 * 1024 && path.none(Char::isISOControl)) { "Invalid Seafile path" }
        requireSafeNetworkWritePath(path)
        return path.trimEnd('/').ifBlank { "/" }
    }
    private fun remote(path: String) = root + checkedPath(path)
    private fun url(operation: String, path: String? = null, fileOperation: Boolean = false): String {
        val segments = if (repoToken) listOf("api", "v2.1", "via-repo-token", operation)
        else if (fileOperation) listOf("api", "v2.1", "repos", repo, operation)
        else listOf("api2", "repos", repo, operation)
        return storageUrl(server, segments + "", path?.let { mapOf((if (repoToken) "path" else "p") to it) } ?: emptyMap())
    }
    private suspend fun request(method: HttpMethod, url: String, form: Map<String, String>? = null): JsonElement =
        api.client.prepareRequest(url) {
            this.method = method
            api.bearerHeaders().forEach { (key, value) -> header(key, value) }
            if (form != null) setBody(FormDataContent(Parameters.build { form.forEach { (key, value) -> append(key, value) } }))
        }.execute { response ->
            if (!response.status.isSuccess()) throw StorageApiException(response.status.value)
            val text = response.readStorageBody(8L * 1024 * 1024)
            if (text.isBlank()) JsonNull else parseStorageJson(text)
        }

    override suspend fun list(path: String, requestId: String?, batchId: String?): Result<List<NetworkFileEntry>> = storageResult {
        val value = request(HttpMethod.Get, url("dir", remote(path)))
        val items = if (repoToken) value.storageObject()["dirent_list"] as? JsonArray else value as? JsonArray
        check(items != null && items.size <= 500_000) { "Invalid Seafile directory response" }
        val names = mutableSetOf<String>()
        items.map { item ->
            val obj = item.storageObject()
            val name = obj.storageString("name")
            require(!isUnsafeNetworkPathSegment(name) && name.none(Char::isISOControl) && names.add(name)) { "Invalid or duplicate Seafile entry" }
            val directory = when (obj.storageString("type")) { "dir" -> true; "file" -> false; else -> error("Unsupported Seafile entry type") }
            NetworkFileEntry(name, checkedPath(path).trimEnd('/') + "/" + name, directory,
                if (directory) -1L else obj.storageLong("size") ?: -1L,
                updatedDate = (obj.storageLong("mtime") ?: 0L).let { check(it <= Long.MAX_VALUE / 1000); it * 1000 },
                isHidden = name.startsWith('.'), isSymbolicLinkKnown = true)
        }
    }
    override suspend fun stat(path: StoragePath): Result<NetworkFileEntry> =
        if (checkedPath(path.path) == "/") storageResult {
            list("/", null, null).getOrThrow()
            NetworkFileEntry("/", "/", true, -1L)
        } else super<StorageProvider>.stat(path)

    private fun checkedTransferUrl(value: String): Url {
        val target = Url(value)
        val base = Url(server)
        require(target.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) && target.host == base.host &&
            (target.protocol == URLProtocol.HTTPS || base.protocol == URLProtocol.HTTP) &&
            target.user.isNullOrEmpty() && target.password.isNullOrEmpty() && target.fragment.isEmpty()) {
            "Seafile transfer URL leaves the configured server"
        }
        return target
    }
    override suspend fun downloadByChunks(remotePath: String, size: Long, onChunk: suspend (ByteArray, Long) -> Result<Unit>): Result<Boolean> = storageResult {
        val operation = if (repoToken) "download-link" else "file"
        val link = request(HttpMethod.Get, url(operation, remote(remotePath))).jsonPrimitive.content
        val target = checkedTransferUrl(link)
        api.client.readStorageFile(target, size, emptyMap(), { sameStorageOrigin(it, target) }, onChunk = onChunk).getOrThrow()
    }
    override suspend fun download(remotePath: String, localPath: String, size: Long, onProgress: (Long, Long) -> Unit) =
        downloadStorageFile(localPath, onProgress) { consume -> downloadByChunks(remotePath, size, consume) }
    override suspend fun upload(localPath: String, remotePath: String, size: Long, onProgress: (Long, Long) -> Unit) =
        storageResult { uploadLocalStorageFile(localPath, size) { length, source -> uploadFromSource(remotePath, length, onProgress, source) }.getOrThrow() }

    override suspend fun uploadFromSource(remotePath: String, size: Long, onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?): Result<Boolean> = storageResult {
        check(capabilities.write) { "Seafile account is read-only" }
        require(size >= 0) { "Seafile upload requires a known size" }
        val path = checkedPath(remotePath)
        require(path != "/") { "Seafile root cannot be overwritten" }
        val parent = path.substringBeforeLast('/').ifBlank { "/" }
        val name = path.substringAfterLast('/')
        val target = checkedTransferUrl(request(HttpMethod.Get, url("upload-link", remote(parent))).jsonPrimitive.content)
        val boundary = "folderspan" + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(secureRandomBytes(18))
        fun field(key: String, value: String) = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + key + "\"\r\n\r\n" + value + "\r\n"
        val filename = name.replace("\\", "\\\\").replace("\"", "\\\"")
        val prefix = (field("parent_dir", remote(parent)) + field("replace", "1") +
            "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename +
            "\"\r\nContent-Type: application/octet-stream\r\n\r\n").encodeToByteArray()
        val suffix = ("\r\n--" + boundary + "--\r\n").encodeToByteArray()
        val destination = URLBuilder(target).apply { parameters.append("ret-json", "1") }.buildString()
        val response = api.upload(HttpMethod.Post, destination, size, onProgress, readChunk, authenticated = false,
            bodyPrefix = prefix, bodySuffix = suffix, bodyContentType = ContentType.MultiPart.FormData.withParameter("boundary", boundary))
        val files = response as? JsonArray ?: error("Seafile upload returned no file metadata")
        check(files.size == 1 && files.single().storageObject().storageString("name") == name &&
            files.single().storageObject().storageLong("size") == size) { "Seafile did not save the selected file" }
        true
    }
    override suspend fun rename(path: String, newPath: String): Result<Boolean> = storageResult {
        check(capabilities.rename) { "Seafile account is read-only" }
        val source = checkedPath(path)
        val destination = checkedPath(newPath)
        require(source != "/" && destination != "/") { "Seafile root cannot be renamed" }
        require(source.substringBeforeLast('/') == destination.substringBeforeLast('/')) { "Use file tasks to move Seafile items between directories" }
        val entry = stat(StoragePath(source)).getOrThrow()
        val parent = destination.substringBeforeLast('/').ifBlank { "/" }
        check(list(parent, null, null).getOrThrow().none { it.name == destination.substringAfterLast('/') && it.path != source }) { "Seafile rename destination already exists" }
        request(HttpMethod.Post, url(if (entry.isDirectory) "dir" else "file", remote(source), fileOperation = !entry.isDirectory),
            mapOf("operation" to "rename", "newname" to destination.substringAfterLast('/')))
        true
    }
    override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> = storageResult {
        check(capabilities.delete) { "Seafile account is read-only" }
        val source = checkedPath(path)
        require(source != "/") { "Seafile root cannot be deleted" }
        request(HttpMethod.Delete, url(if (isDirectory) "dir" else "file", remote(source), fileOperation = !isDirectory))
        true
    }
    override suspend fun createFolder(path: String): Result<Boolean> = storageResult {
        check(capabilities.write) { "Seafile account is read-only" }
        val target = checkedPath(path)
        require(target != "/") { "Seafile root already exists" }
        val parent = target.substringBeforeLast('/').ifBlank { "/" }
        check(list(parent, null, null).getOrThrow().none { it.name == target.substringAfterLast('/') }) { "Seafile destination already exists" }
        request(HttpMethod.Post, url("dir", remote(target)), mapOf("operation" to "mkdir"))
        true
    }
    override suspend fun createFile(path: String) = uploadFromSource(path, 0L, readChunk = { null })
    override suspend fun close() = api.close()

    private fun advancedUrl(vararg segments: String, parameters: Map<String, String> = emptyMap()) =
        storageUrl(server, segments.toList() + "", parameters)

    private val connectionBinding get() = storageConnectionBinding(config, root.ifBlank { "/" })

    private fun requireBinding(binding: String) {
        require(binding.isNotBlank() && binding == connectionBinding) { "Seafile operation belongs to another connection" }
    }

    private fun requireUserToken() {
        if (repoToken) throw StorageFeatureException(StorageFeatureAvailability.UNSUPPORTED)
    }

    private suspend fun libraryPermission(): Boolean {
        requireUserToken()
        val value = request(HttpMethod.Get, advancedUrl("api2", "repos", repo)).storageObject()
        return capabilities.write && value.storageOptionalString("permission") == "rw"
    }

    private suspend fun probe(block: suspend () -> Unit): StorageFeatureAvailability = try {
        block(); StorageFeatureAvailability.AVAILABLE
    } catch (error: StorageApiException) {
        when (error.status) {
            401 -> StorageFeatureAvailability.AUTHORIZATION_REQUIRED
            403 -> StorageFeatureAvailability.PERMISSION_DENIED
            404, 405, 501 -> StorageFeatureAvailability.UNSUPPORTED
            else -> throw error
        }
    }

    private fun historyLocation(path: StoragePath, cursor: String?): Pair<String, String?> {
        if (cursor == null) return remote(path.path) to null
        require(cursor.length <= 32 * 1024) { "Invalid Seafile history cursor" }
        val value = parseStorageJson(cursor).storageObject()
        require(value.storageString("file") == path.path) { "Seafile history cursor belongs to another file" }
        val historical = checkedPath(value.storageString("path"))
        require(root.isEmpty() || historical.startsWith("$root/")) { "Seafile history leaves the configured root" }
        val commit = value.storageString("commit")
        require(commit.matches(Regex("[0-9a-fA-F]{40}"))) { "Invalid Seafile history cursor" }
        return historical to commit
    }

    private fun historyUrl(path: StoragePath, cursor: String? = null): String {
        val (historical, commit) = historyLocation(path, cursor)
        return advancedUrl("api", "v2.1", "repos", repo, "file", "history", parameters = buildMap {
            put("path", historical); put("limit", "50"); commit?.let { put("commit_id", it) }
        })
    }

    private fun invitationUrl(path: StoragePath, recipient: String? = null) =
        advancedUrl("api2", "repos", repo, "dir", "shared_items", parameters = buildMap {
            put("p", remote(path.path)); put("share_type", "user"); recipient?.let { put("username", it) }
        })

    private fun linksUrl(path: StoragePath, page: Int = 1) =
        advancedUrl("api", "v2.1", "share-links", parameters = mapOf("repo_id" to repo, "path" to remote(path.path), "page" to page.toString(), "per_page" to "100"))

    override suspend fun advancedCapabilities(path: StoragePath): Result<StorageAdvancedCapabilities> = storageResult {
        if (repoToken) return@storageResult StorageAdvancedCapabilities()
        val file = stat(path).getOrElse { error ->
            if (error is StorageEntryNotFoundException) return@storageResult StorageAdvancedCapabilities()
            throw error
        }
        val write = libraryPermission()
        val versions = if (file.isDirectory) StorageFeatureAvailability.UNSUPPORTED else probe { request(HttpMethod.Get, historyUrl(path)) }
        val links = probe { request(HttpMethod.Get, linksUrl(path)) }
        val invitations = if (file.isDirectory) probe { request(HttpMethod.Get, invitationUrl(path)) } else StorageFeatureAvailability.UNSUPPORTED
        val writable = if (write) StorageFeatureAvailability.AVAILABLE else StorageFeatureAvailability.PERMISSION_DENIED
        StorageAdvancedCapabilities(features = mapOf(
            StorageAdvancedFeature.RESUMABLE_UPLOAD to StorageFeatureAvailability.UNSUPPORTED,
            StorageAdvancedFeature.LIST_VERSIONS to versions, StorageAdvancedFeature.READ_VERSION to versions,
            StorageAdvancedFeature.RESTORE_VERSION to if (write) versions else writable,
            StorageAdvancedFeature.LIST_SHARES to links,
            StorageAdvancedFeature.CREATE_LINK to if (write) links else writable,
            StorageAdvancedFeature.INVITE_USER to if (write) invitations else writable,
            StorageAdvancedFeature.REVOKE_SHARE to if (write) links else writable,
        ), shareRoles = setOf("reader", "writer"), shareExpiry = true)
    }

    override suspend fun listVersions(path: StoragePath, cursor: String?): Result<StoragePage<StorageVersion>> = storageResult {
        requireUserToken()
        check(!stat(path).getOrThrow().isDirectory) { "Seafile directory versions are unsupported" }
        val response = request(HttpMethod.Get, historyUrl(path, cursor)).storageObject()
        val values = response["data"] as? JsonArray ?: error("Invalid Seafile history response")
        val next = (response["next_start_commit"] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "false" && it.isNotBlank() }
        val (queryPath, queryCommit) = historyLocation(path, cursor)
        require(next == null || next.matches(Regex("[0-9a-fA-F]{40}")) && next != queryCommit) { "Invalid Seafile history cursor" }
        val entries = values.map { value ->
            val revision = value.storageObject()
            val id = revision.storageString("commit_id")
            require(id.matches(Regex("[0-9a-fA-F]{40}"))) { "Invalid Seafile revision" }
            val historicalPath = revision.storageOptionalString("path") ?: queryPath
            checkedPath(historicalPath)
            require(root.isEmpty() || historicalPath.startsWith("$root/")) { "Seafile history leaves the configured root" }
            val fileId = revision.storageString("rev_file_id")
            require(fileId.matches(Regex("[0-9a-fA-F]{40}"))) { "Invalid Seafile revision file ID" }
            StorageVersion(path.copy(reference = StorageReference(parentId = fileId, ancestorId = repo, ancestorPath = historicalPath)), id,
                revision.storageLong("size")?.takeIf { it >= 0 }, storageTime(revision.storageOptionalString("ctime")).takeIf { it > 0 }, connectionBinding)
        }
        check(entries.size <= 50 && entries.map { it.id }.toSet().size == entries.size) { "Invalid Seafile history page" }
        val nextCursor = next?.let { commit ->
            val renamed = values.lastOrNull()?.storageObject()?.storageOptionalString("rev_renamed_old_path")?.takeIf { it.isNotBlank() }
            val historical = checkedPath(renamed ?: queryPath)
            require(root.isEmpty() || historical.startsWith("$root/")) { "Seafile history leaves the configured root" }
            buildJsonObject { put("file", path.path); put("path", historical); put("commit", commit) }.toString()
        }
        StoragePage(entries, nextCursor)
    }

    private fun checkedVersion(version: StorageVersion): String {
        requireBinding(version.connectionBinding)
        requireUserToken()
        checkedPath(version.file.path)
        val reference = version.file.reference ?: error("Seafile version requires its library and historical identity")
        require(reference.ancestorId == repo && version.id.matches(Regex("[0-9a-fA-F]{40}")) &&
            reference.parentId.matches(Regex("[0-9a-fA-F]{40}"))) { "Seafile version belongs to another library" }
        val historical = checkedPath(reference.ancestorPath)
        require(root.isEmpty() || historical.startsWith("$root/")) { "Seafile history leaves the configured root" }
        return historical
    }

    private suspend fun selectedVersionPath(version: StorageVersion): String {
        val historical = checkedVersion(version)
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        repeat(10_000) {
            val page = listVersions(version.file, cursor).getOrThrow()
            if (page.entries.any { it.id == version.id && it.file.reference?.parentId == version.file.reference?.parentId &&
                    it.file.reference?.ancestorPath == historical && (version.size == null || version.size == it.size) }) return historical
            val next = page.nextCursor ?: error("Seafile revision is no longer in the selected file history")
            check(seen.add(next)) { "Seafile history cursor cycle" }
            cursor = next
        }
        error("Seafile history exceeds the page limit")
    }

    override suspend fun readVersion(version: StorageVersion, onChunk: suspend (ByteArray, Long) -> Result<Unit>): Result<Unit> = storageResult {
        val historical = selectedVersionPath(version)
        val link = request(HttpMethod.Get, advancedUrl("api2", "repos", repo, "file", "revision",
            parameters = mapOf("p" to historical, "commit_id" to version.id))).jsonPrimitive.content
        val target = checkedTransferUrl(link)
        api.client.readStorageFile(target, version.size ?: -1L, emptyMap(), { sameStorageOrigin(it, target) }, onChunk = onChunk).getOrThrow()
    }

    override suspend fun restoreVersion(version: StorageVersion, confirmed: Boolean): Result<NetworkFileEntry> = storageResult {
        require(confirmed) { "Confirm the selected version before restoring" }
        val historical = checkedVersion(version)
        require(historical == remote(version.file.path)) { "This Seafile revision predates a rename; native restore to the selected path is unsupported" }
        selectedVersionPath(version)
        check(libraryPermission()) { "Seafile account cannot restore versions" }
        // Check the selected commit still exists before issuing the native mutation.
        request(HttpMethod.Get, advancedUrl("api2", "repos", repo, "file", "revision",
            parameters = mapOf("p" to historical, "commit_id" to version.id)))
        request(HttpMethod.Post, url("file", remote(version.file.path), fileOperation = true), mapOf("operation" to "revert", "commit_id" to version.id))
        val restored = request(HttpMethod.Get, advancedUrl("api2", "repos", repo, "file", "detail", parameters = mapOf("p" to remote(version.file.path)))).storageObject()
        check(restored.storageString("id") == version.file.reference?.parentId && (version.size == null || restored.storageLong("size") == version.size)) { "Seafile restore result is uncertain" }
        NetworkFileEntry(version.file.path.substringAfterLast('/'), version.file.path, false, restored.storageLong("size") ?: -1L)
    }

    private fun link(path: StoragePath, value: JsonElement): StorageShare {
        val objectValue = value.storageObject()
        require(objectValue.storageString("repo_id") == repo && objectValue.storageString("path").trimEnd('/') == remote(path.path).trimEnd('/')) { "Seafile share belongs to another library or file" }
        val permissions = objectValue["permissions"]?.storageObject() ?: error("Seafile did not confirm sharing permissions")
        val edit = (permissions["can_edit"] as? JsonPrimitive)?.booleanOrNull ?: false
        val upload = (permissions["can_upload"] as? JsonPrimitive)?.booleanOrNull ?: false
        val download = (permissions["can_download"] as? JsonPrimitive)?.booleanOrNull ?: false
        val role = if (!edit && !upload && download) "reader" else if (!edit && !upload && !download) "preview" else "editor"
        val expiration = objectValue.storageOptionalString("expire_date")?.takeIf { it.isNotBlank() }?.let { value ->
            storageTime(value).takeIf { it > 0 } ?: error("Seafile returned an unknown share expiration")
        }
        return StorageShare(path, objectValue.storageString("token"), StorageShareKind.LINK, role, expiresAt = expiration,
            url = objectValue.storageString("link"), canRevoke = true, connectionBinding = connectionBinding)
    }

    private fun invitation(path: StoragePath, value: JsonElement): StorageShare {
        val objectValue = value.storageObject()
        require(objectValue.storageString("share_type") == "user") { "Unsupported Seafile sharing type" }
        val recipient = objectValue["user_info"]?.storageObject()?.storageString("name") ?: error("Seafile invitation has no recipient")
        val role = if ((objectValue["is_admin"] as? JsonPrimitive)?.booleanOrNull == true) "admin" else when (val permission = objectValue.storageString("permission")) { "r" -> "reader"; "rw" -> "writer"; else -> permission }
        return StorageShare(path, "user:$recipient", StorageShareKind.INVITATION, role, recipient, connectionBinding = connectionBinding)
    }

    override suspend fun listShares(path: StoragePath, cursor: String?): Result<StoragePage<StorageShare>> = storageResult {
        requireUserToken()
        val page = cursor?.toIntOrNull() ?: if (cursor == null) 1 else error("Invalid Seafile sharing cursor")
        require(page in 1..10_000) { "Invalid Seafile sharing cursor" }
        val values = request(HttpMethod.Get, linksUrl(path, page)) as? JsonArray ?: error("Invalid Seafile share list")
        check(values.size <= 100) { "Seafile share page exceeds its limit" }
        val entries = values.map { link(path, it) }.toMutableList()
        if (page == 1 && stat(path).getOrThrow().isDirectory) {
            val invitations = request(HttpMethod.Get, invitationUrl(path)) as? JsonArray ?: error("Invalid Seafile invitation list")
            entries.addAll(invitations.map { invitation(path, it) })
        }
        StoragePage(entries, if (values.size == 100) (page + 1).toString() else null)
    }

    override suspend fun createShare(path: StoragePath, request: StorageShareRequest, confirmed: Boolean): Result<StorageShare> = storageResult {
        require(confirmed) { "Confirm the selected file, recipient and permissions before sharing" }
        requireUserToken()
        advancedCapabilities(path).getOrThrow().validateShare(request)
        require(request.expiresAt == null || request.expiresAt > kotlin.time.Clock.System.now().toEpochMilliseconds()) { "Share expiration must be in the future" }
        if (request.kind == StorageShareKind.INVITATION) {
            require(request.expiresAt == null) { "Seafile directory invitation expiration is unsupported" }
            val recipient = checkNotNull(request.recipient)
            val existing = this.request(HttpMethod.Get, invitationUrl(path)) as? JsonArray
                ?: error("Invalid Seafile invitation list")
            check(existing.none { it.storageObject()["user_info"]?.storageObject()?.storageOptionalString("name") == recipient }) {
                "Seafile selected recipient already has access"
            }
            val value = this.request(HttpMethod.Put, invitationUrl(path), mapOf(
                "share_type" to "user", "username" to recipient, "permission" to if (request.role == "reader") "r" else "rw")).storageObject()
            val created = (value["success"] as? JsonArray)?.singleOrNull()
            if (created == null) {
                check((value["failed"] as? JsonArray)?.isEmpty() == true) { "Seafile rejected the selected recipient" }
                error("Seafile invitation result is uncertain; no new grant identity was confirmed")
            }
            check(created.storageObject()["user_info"]?.storageObject()?.storageOptionalString("name") == recipient) {
                "Seafile invitation result is uncertain; no selected new grant identity was confirmed"
            }
            try {
                check((value["failed"] as? JsonArray)?.isEmpty() == true) { "Seafile rejected the selected recipient" }
                val result = invitation(path, created)
                check(result.role == request.role && result.recipient == recipient) { "Seafile returned different invitation permissions" }
                currentCoroutineContext().ensureActive()
                result
            } catch (error: Throwable) {
                compensateStorageShare(error) {
                    this.request(HttpMethod.Delete, invitationUrl(path, recipient))
                }
            }
        } else {
            require(request.role == "reader") { "Seafile public links support read-only sharing in this client" }
            val body = buildJsonObject {
                put("repo_id", repo); put("path", remote(path.path))
                request.expiresAt?.let { put("expiration_time", kotlin.time.Instant.fromEpochMilliseconds(it).toString()) }
                putJsonObject("permissions") { put("can_edit", false); put("can_upload", false); put("can_download", true) }
            }
            val created = api.json(HttpMethod.Post, advancedUrl("api", "v2.1", "share-links"), body)
            val token = created.storageObject().storageOptionalString("token")
            check(token != null && token.isNotBlank() && token.length <= 1024 && token.none(Char::isISOControl) && !isUnsafeNetworkPathSegment(token)) {
                "Seafile link result is uncertain; no new grant identity was confirmed"
            }
            try {
                val result = link(path, created)
                check(result.role == request.role &&
                    (request.expiresAt == null || result.expiresAt != null && result.expiresAt <= request.expiresAt)) {
                    "Seafile returned different link restrictions"
                }
                currentCoroutineContext().ensureActive()
                result
            } catch (error: Throwable) {
                compensateStorageShare(error) {
                    this.request(HttpMethod.Delete, advancedUrl("api", "v2.1", "share-links", token))
                }
            }
        }
    }

    override suspend fun revokeShare(share: StorageShare): Result<Unit> = storageResult {
        requireBinding(share.connectionBinding)
        requireUserToken()
        if (share.kind == StorageShareKind.INVITATION) {
            val current = request(HttpMethod.Get, invitationUrl(share.file)) as? JsonArray ?: error("Invalid Seafile invitation list")
            check(current.map { invitation(share.file, it) }.any { it.id == share.id && it.recipient == share.recipient }) { "Seafile invitation does not belong to the selected directory" }
            request(HttpMethod.Delete, invitationUrl(share.file, share.recipient))
        } else {
            require(share.id.isNotBlank() && share.id.length <= 1024 && share.id.none(Char::isISOControl) && !isUnsafeNetworkPathSegment(share.id)) { "Invalid Seafile share token" }
            val current = link(share.file, request(HttpMethod.Get, advancedUrl("api", "v2.1", "share-links", share.id)))
            check(current.id == share.id) { "Seafile share identity changed" }
            request(HttpMethod.Delete, advancedUrl("api", "v2.1", "share-links", share.id))
        }
    }
}

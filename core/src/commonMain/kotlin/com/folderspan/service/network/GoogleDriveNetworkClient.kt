package com.folderspan.service.network

import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal class GoogleDriveNetworkClient(
    config: NetworkConnectionConfig,
    api: StorageHttpApi = storageHttpApi(config),
) : IdStorageProvider(config, api) {
    override val rootId: String get() = config.extras.cloud.rootId.ifBlank { "root" }
    private val fields = "id,name,mimeType,size,modifiedTime,parents,trashed"
    private fun fileUrl(id: String, parameters: Map<String, String> = emptyMap(), upload: Boolean = false): String =
        storageUrl(if (upload) "https://www.googleapis.com/upload/drive/v3/files" else "https://www.googleapis.com/drive/v3/files",
            if (id.isEmpty()) emptyList() else listOf(id),
            mapOf("supportsAllDrives" to "true") + parameters)
    private fun parse(value: JsonElement): CloudStorageNode {
        val obj = value.storageObject()
        check((obj["trashed"] as? JsonPrimitive)?.booleanOrNull != true) { "Google Drive item is in the trash" }
        val mime = obj.storageString("mimeType")
        return CloudStorageNode(
            id = obj.storageString("id"), name = obj.storageString("name"),
            directory = mime == "application/vnd.google-apps.folder",
            size = obj.storageLong("size") ?: -1L,
            modified = storageTime(obj.storageOptionalString("modifiedTime")),
            parents = (obj["parents"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
            symbolicLink = mime == "application/vnd.google-apps.shortcut",
        )
    }
    override suspend fun node(id: String): CloudStorageNode =
        parse(api.json(HttpMethod.Get, fileUrl(id, mapOf("fields" to fields))))

    override suspend fun children(directory: CloudStorageNode): List<CloudStorageNode> = collectStoragePages { cursor ->
        val parent = directory.id.replace("\\", "\\\\").replace("'", "\\'")
        val parameters = mutableMapOf(
            "q" to "'$parent' in parents and trashed = false",
            "fields" to "nextPageToken,files($fields)", "pageSize" to "1000",
            "includeItemsFromAllDrives" to "true",
        )
        if (cursor != null) parameters["pageToken"] = cursor
        val result = api.json(HttpMethod.Get, fileUrl("", parameters)).storageObject()
        (result["files"] as? JsonArray ?: error("Google Drive page has no files")).map(::parse) to
            result.storageOptionalString("nextPageToken")
    }

    override suspend fun read(node: CloudStorageNode, size: Long, onChunk: suspend (ByteArray, Long) -> Result<Unit>) {
        val target = Url(fileUrl(node.id, mapOf("alt" to "media")))
        api.client.readStorageFile(target, size, api.bearerHeaders(), allowRedirect = { next ->
            next.protocol == URLProtocol.HTTPS &&
                (sameStorageOrigin(target, next) || next.host.endsWith(".googleusercontent.com") || next.host == "drive.usercontent.google.com")
        }, onChunk = onChunk).getOrThrow()
    }

    override suspend fun write(
        parent: CloudStorageNode, name: String, existing: CloudStorageNode?, size: Long,
        progress: (Long, Long) -> Unit, source: suspend () -> ByteArray?,
    ) {
        val target = StoragePath("/" + name, StorageReference(existing?.id.orEmpty(), parent.id))
        var session = beginUpload(parent, name, existing, size, target)
        val input = StorageUploadChunks(size, source)
        if (size == 0L) session = uploadChunk(session, 0, byteArrayOf()).getOrThrow()
        while (session.status == StorageUploadStatus.UPLOADING && session.confirmedOffset < size) {
            val part = input.next(uploadConstraints.maxChunkSize) ?: error("Storage upload ended early")
            val expectedOffset = session.confirmedOffset + part.size
            session = uploadChunk(session, session.confirmedOffset, part).getOrThrow()
            check(session.confirmedOffset == expectedOffset) { "Storage upload requires source repositioning" }
            progress(session.confirmedOffset, size)
        }
        check(session.status == StorageUploadStatus.COMPLETED) { "Google Drive upload is incomplete" }
        check(input.next(uploadConstraints.maxChunkSize) == null) { "Storage upload length exceeded" }
        progress(size, size)
    }

    override suspend fun mkdir(parent: CloudStorageNode, name: String) {
        api.json(HttpMethod.Post, fileUrl(""), buildJsonObject {
            put("name", name); put("mimeType", "application/vnd.google-apps.folder")
            putJsonArray("parents") { add(parent.id) }
        }).storageObject().storageString("id")
    }
    override suspend fun remove(node: CloudStorageNode) = api.empty(HttpMethod.Delete, fileUrl(node.id))
    override suspend fun move(node: CloudStorageNode, parent: CloudStorageNode, name: String) {
        val parameters = if (parent.id in node.parents) emptyMap() else mapOf(
            "addParents" to parent.id, "removeParents" to node.parents.joinToString(","),
        )
        api.json(HttpMethod.Patch, fileUrl(node.id, parameters), buildJsonObject { put("name", name) })
    }
    private val uploadConstraints = StorageUploadConstraints(alignment = 256 * 1024)

    override suspend fun advancedCapabilities(path: StoragePath): Result<StorageAdvancedCapabilities> = storageResult {
        val currentRoot = root()
        val selected = try { resolve(path, currentRoot) } catch (_: StorageEntryNotFoundException) { null }
        val item = selected ?: uploadTarget(path).first
        val metadata = api.json(HttpMethod.Get, fileUrl(item.id, mapOf("fields" to "mimeType,capabilities"))).storageObject()
        val permissions = metadata["capabilities"] as? JsonObject
        fun allowed(name: String) = (permissions?.get(name) as? JsonPrimitive)?.booleanOrNull == true
        fun status(value: Boolean) = if (value) StorageFeatureAvailability.AVAILABLE else StorageFeatureAvailability.PERMISSION_DENIED
        val upload = status(capabilities.write && allowed(if (selected == null || item.directory) "canAddChildren" else "canEdit"))
        val features = mutableMapOf(StorageAdvancedFeature.RESUMABLE_UPLOAD to upload)
        if (selected != null) {
            val mime = metadata.storageOptionalString("mimeType").orEmpty()
            if (!item.directory && !item.symbolicLink && !mime.startsWith("application/vnd.google-apps.")) {
                val history = status(capabilities.read && allowed("canReadRevisions"))
                features[StorageAdvancedFeature.LIST_VERSIONS] = history
                features[StorageAdvancedFeature.READ_VERSION] = history
                features[StorageAdvancedFeature.RESTORE_VERSION] = status(capabilities.write && allowed("canReadRevisions") && allowed("canEdit"))
            }
            val sharing = status(allowed("canShare"))
            listOf(StorageAdvancedFeature.LIST_SHARES, StorageAdvancedFeature.CREATE_LINK,
                StorageAdvancedFeature.INVITE_USER, StorageAdvancedFeature.REVOKE_SHARE).forEach { features[it] = sharing }
        }
        StorageAdvancedCapabilities(features, upload = uploadConstraints,
            shareRoles = setOf("reader", "commenter", "writer"), shareExpiry = true)
    }

    override suspend fun startUpload(target: StoragePath, size: Long): Result<StorageUploadSession> = storageResult {
        require(size >= 0) { "Storage upload requires a known size" }
        advancedCapabilities(target).getOrThrow().require(StorageAdvancedFeature.RESUMABLE_UPLOAD)
        val (parent, name, existing) = uploadTarget(target)
        beginUpload(parent, name, existing, size, target)
    }

    private suspend fun beginUpload(parent: CloudStorageNode, name: String, existing: CloudStorageNode?, size: Long, target: StoragePath): StorageUploadSession {
        val metadata = buildJsonObject {
            if (existing == null) {
                put("name", name)
                putJsonArray("parents") { add(parent.id) }
            }
        }
        val location = api.request(
            if (existing == null) HttpMethod.Post else HttpMethod.Patch,
            fileUrl(existing?.id.orEmpty(), mapOf("uploadType" to "resumable", "fields" to fields), upload = true),
            metadata,
            mapOf("X-Upload-Content-Length" to size.toString(), "X-Upload-Content-Type" to "application/octet-stream"),
        ) { response -> response.headers[HttpHeaders.Location] ?: error("Google Drive upload has no session URL") }
        checkedSessionUrl(location)
        return StorageUploadSession("GoogleDrive", target.copy(reference = StorageReference(existing?.id.orEmpty(), parent.id)),
            size, 0, secret = location, binding = uploadBinding())
    }

    private fun checkedSessionUrl(location: String) {
        val url = Url(location)
        check(url.protocol == URLProtocol.HTTPS && url.host == "www.googleapis.com" &&
            url.encodedPath.startsWith("/upload/drive/v3/files") && url.user.isNullOrEmpty() && url.password.isNullOrEmpty()) {
            "Google Drive returned an invalid upload session"
        }
    }

    private suspend fun uploadResponse(session: StorageUploadSession, offset: Long, bytes: ByteArray, query: Boolean): StorageUploadSession {
        validateUploadSession(session, "GoogleDrive")
        checkedSessionUrl(session.secret)
        val range = if (query || session.size == 0L) "bytes */${session.size}"
            else "bytes $offset-${offset + bytes.size - 1}/${session.size}"
        var pending: ByteArray? = bytes.takeIf { it.isNotEmpty() }
        return api.uploadResponse(HttpMethod.Put, session.secret, bytes.size.toLong(), { _, _ -> },
            { pending.also { pending = null } }, mapOf(HttpHeaders.ContentRange to range)) { response ->
            when (response.status.value) {
                200, 201 -> {
                    val value = parse(parseStorageJson(response.readStorageBody(8L * 1024 * 1024)))
                    val expectedId = session.target.reference?.id.orEmpty()
                    check(expectedId.isEmpty() || value.id == expectedId) { "Google Drive upload changed the target ID" }
                    check(value.size == session.size) { "Google Drive upload returned an unexpected length" }
                    session.copy(confirmedOffset = session.size, status = StorageUploadStatus.COMPLETED,
                        entry = entry(value, session.target.path, session.target.reference?.parentId))
                }
                308 -> {
                    val header = response.headers[HttpHeaders.Range]
                    val confirmed = if (header == null) 0L else {
                        val match = Regex("bytes=0-([0-9]+)").matchEntire(header)
                            ?: error("Google Drive returned an invalid upload range")
                        val last = match.groupValues[1].toLongOrNull() ?: error("Google Drive upload range overflow")
                        check(last < session.size) { "Google Drive upload range exceeds its length" }
                        last + 1
                    }
                    check(confirmed in 0..session.size) { "Google Drive upload offset is invalid" }
                    session.copy(confirmedOffset = confirmed, status = StorageUploadStatus.UPLOADING)
                }
                404, 410 -> session.copy(status = if (session.status == StorageUploadStatus.UNCERTAIN)
                    StorageUploadStatus.UNCERTAIN else StorageUploadStatus.EXPIRED)
                401 -> throw StorageFeatureException(StorageFeatureAvailability.AUTHORIZATION_REQUIRED)
                403 -> throw StorageFeatureException(StorageFeatureAvailability.PERMISSION_DENIED)
                else -> throw StorageApiException(response.status.value)
            }
        }
    }

    override suspend fun queryUpload(session: StorageUploadSession): Result<StorageUploadSession> =
        storageResult { uploadResponse(session, session.confirmedOffset, byteArrayOf(), query = true) }

    override suspend fun uploadChunk(session: StorageUploadSession, offset: Long, bytes: ByteArray): Result<StorageUploadSession> = storageResult {
        uploadConstraints.validate(session.size, offset, bytes.size)
        require(offset == session.confirmedOffset && session.status == StorageUploadStatus.UPLOADING) { "Upload offset must be confirmed first" }
        val final = offset + bytes.size == session.size
        try {
            uploadResponse(session, offset, bytes, query = false)
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException || (failure is StorageApiException && failure.status < 500) ||
                failure is StorageFeatureException || !final) throw failure
            throw StorageUploadUncertainException(session.copy(status = StorageUploadStatus.UNCERTAIN))
        }
    }

    override suspend fun finishUpload(session: StorageUploadSession): Result<StorageUploadSession> =
        if (session.status == StorageUploadStatus.COMPLETED) storageResult {
            require(session.backend == "GoogleDrive" && session.binding == uploadBinding()) { "Upload session belongs to another connection" }
            session
        } else queryUpload(session)

    override suspend fun cancelUpload(session: StorageUploadSession): Result<Unit> = storageResult {
        validateUploadSession(session, "GoogleDrive")
        checkedSessionUrl(session.secret)
        cancelledUploads.add(session.secret)
    }

    private suspend fun selected(path: StoragePath, fileOnly: Boolean = false): CloudStorageNode {
        val value = resolve(path, root())
        require(!value.symbolicLink && (!fileOnly || !value.directory)) { "Select a regular storage file" }
        return value
    }

    private fun revisionUrl(fileId: String, revisionId: String? = null, parameters: Map<String, String> = emptyMap()) =
        storageUrl("https://www.googleapis.com/drive/v3/files",
            listOf(fileId, "revisions") + listOfNotNull(revisionId), parameters)

    private fun permissionUrl(fileId: String, permissionId: String? = null, parameters: Map<String, String> = emptyMap()) =
        storageUrl("https://www.googleapis.com/drive/v3/files",
            listOf(fileId, "permissions") + listOfNotNull(permissionId), mapOf("supportsAllDrives" to "true") + parameters)

    override suspend fun listVersions(path: StoragePath, cursor: String?): Result<StoragePage<StorageVersion>> = storageResult {
        val file = selected(path, true)
        advancedCapabilities(path).getOrThrow().require(StorageAdvancedFeature.LIST_VERSIONS)
        require(cursor == null || cursor.length in 1..64 * 1024) { "Invalid revision cursor" }
        val parameters = mutableMapOf("pageSize" to "1000", "fields" to "nextPageToken,revisions(id,size,modifiedTime)")
        if (cursor != null) parameters["pageToken"] = cursor
        val page = api.json(HttpMethod.Get, revisionUrl(file.id, parameters = parameters)).storageObject()
        val exact = StoragePath(path.path, entry(file, path.path).reference)
        val revisions = page["revisions"] as? JsonArray ?: error("Google Drive returned no revision list")
        StoragePage(revisions.map {
            val version = it.storageObject()
            StorageVersion(exact, version.storageString("id"), version.storageLong("size"),
                version.storageOptionalString("modifiedTime")?.let(::storageTime), connectionBinding = uploadBinding())
        }, page.storageOptionalString("nextPageToken"))
    }

    private suspend fun revision(version: StorageVersion): Pair<CloudStorageNode, JsonObject> {
        require(version.connectionBinding.isNotEmpty() && version.connectionBinding == uploadBinding()) { "Revision belongs to another storage connection" }
        require(version.id.length in 1..4096 && version.id.none(Char::isISOControl)) { "Invalid storage revision" }
        val file = selected(version.file, true)
        require(version.file.reference?.id == file.id) { "Revision requires the selected stable file ID" }
        return file to api.json(HttpMethod.Get, revisionUrl(file.id, version.id,
            mapOf("fields" to "id,size,keepForever,md5Checksum"))).storageObject()
    }

    override suspend fun readVersion(version: StorageVersion, onChunk: suspend (ByteArray, Long) -> Result<Unit>): Result<Unit> = storageResult {
        require(version.connectionBinding.isNotEmpty() && version.connectionBinding == uploadBinding()) { "Revision belongs to another storage connection" }
        advancedCapabilities(version.file).getOrThrow().require(StorageAdvancedFeature.READ_VERSION)
        val (file, metadata) = revision(version)
        check((metadata["keepForever"] as? JsonPrimitive)?.booleanOrNull == true) {
            "Google Drive requires this revision to be kept forever before downloading"
        }
        api.client.readStorageFile(Url(revisionUrl(file.id, version.id, mapOf("alt" to "media"))),
            metadata.storageLong("size") ?: -1L, api.bearerHeaders(), onChunk = onChunk).getOrThrow()
    }

    override suspend fun restoreVersion(version: StorageVersion, confirmed: Boolean): Result<NetworkFileEntry> = storageResult {
        require(version.connectionBinding.isNotEmpty() && version.connectionBinding == uploadBinding()) { "Revision belongs to another storage connection" }
        require(confirmed) { "Confirm restoring and retaining the selected Google Drive revision" }
        advancedCapabilities(version.file).getOrThrow().require(StorageAdvancedFeature.RESTORE_VERSION)
        val (file, metadata) = revision(version)
        val size = metadata.storageLong("size") ?: error("Google Drive revision size is unknown")
        if ((metadata["keepForever"] as? JsonPrimitive)?.booleanOrNull != true) {
            api.json(HttpMethod.Patch, revisionUrl(file.id, version.id), buildJsonObject { put("keepForever", true) })
        }
        kotlinx.coroutines.coroutineScope {
            val chunks = kotlinx.coroutines.channels.Channel<ByteArray>(2)
            val producer = async {
                var failure: Throwable? = null
                try {
                    readVersion(version) { bytes, _ -> chunks.send(bytes); Result.success(Unit) }.getOrThrow()
                } catch (cause: Throwable) { failure = cause; throw cause }
                finally { chunks.close(failure) }
            }
            try {
                uploadFromSource(version.file, size, readChunk = {
                    val chunk = chunks.receiveCatching()
                    chunk.exceptionOrNull()?.let { throw it }
                    chunk.getOrNull()
                }).getOrThrow()
                producer.await()
            } finally { producer.cancel(); chunks.cancel() }
        }
        val current = api.json(HttpMethod.Get, fileUrl(file.id,
            mapOf("fields" to "$fields,md5Checksum"))).storageObject()
        metadata.storageOptionalString("md5Checksum")?.let {
            check(current.storageOptionalString("md5Checksum") == it) { "Restored Google Drive revision could not be verified" }
        }
        entry(parse(current), version.file.path)
    }

    private val permissionFields = "id,type,role,emailAddress,domain,expirationTime,permissionDetails"
    private fun parseShare(path: StoragePath, metadata: JsonObject, url: String? = null): StorageShare {
        val kind = if (metadata.storageString("type") == "anyone") StorageShareKind.LINK else StorageShareKind.INVITATION
        return StorageShare(path, metadata.storageString("id"), kind, metadata.storageString("role"),
            if (kind == StorageShareKind.LINK) null else metadata.storageOptionalString("emailAddress") ?: metadata.storageOptionalString("domain"),
            metadata.storageOptionalString("expirationTime")?.let(::storageTime), url,
            canRevoke = metadata.storageString("role") != "owner" &&
                (metadata["permissionDetails"] as? JsonArray).orEmpty().none {
                    (it.storageObject()["inherited"] as? JsonPrimitive)?.booleanOrNull == true
                }, connectionBinding = uploadBinding())
    }

    override suspend fun listShares(path: StoragePath, cursor: String?): Result<StoragePage<StorageShare>> = storageResult {
        val file = selected(path)
        advancedCapabilities(path).getOrThrow().require(StorageAdvancedFeature.LIST_SHARES)
        require(cursor == null || cursor.length in 1..64 * 1024)
        val parameters = mutableMapOf("pageSize" to "100", "fields" to "nextPageToken,permissions($permissionFields)")
        if (cursor != null) parameters["pageToken"] = cursor
        val page = api.json(HttpMethod.Get, permissionUrl(file.id, parameters = parameters)).storageObject()
        val exact = StoragePath(path.path, entry(file, path.path).reference)
        StoragePage((page["permissions"] as? JsonArray ?: error("Google Drive returned no permissions")).map {
            parseShare(exact, it.storageObject())
        }, page.storageOptionalString("nextPageToken"))
    }

    private suspend fun existingShares(path: StoragePath): List<StorageShare> {
        val shares = mutableListOf<StorageShare>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        repeat(10_000) {
            val page = listShares(path, cursor).getOrThrow()
            shares += page.entries
            cursor = page.nextCursor ?: return shares
            check(seen.add(cursor)) { "Google Drive sharing pagination cycle" }
        }
        error("Google Drive sharing page limit exceeded")
    }

    override suspend fun createShare(path: StoragePath, request: StorageShareRequest, confirmed: Boolean): Result<StorageShare> = storageResult {
        require(confirmed) { "Confirm the selected storage recipient and role" }
        val file = selected(path)
        advancedCapabilities(path).getOrThrow().validateShare(request)
        if (request.expiresAt != null) {
            require(request.kind == StorageShareKind.INVITATION) { "Google Drive public links cannot expire" }
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            require(request.expiresAt > now && request.expiresAt - now <= 365L * 24 * 60 * 60 * 1000) { "Google Drive expiry must be within one year" }
            require(!file.directory || request.role == "reader") { "Google Drive folder expiry requires a reader role" }
        }
        val previous = existingShares(path)
        require(previous.none { it.kind == request.kind && (request.kind == StorageShareKind.LINK ||
            it.recipient.equals(request.recipient, ignoreCase = true)) }) {
            "This recipient or link already has access; refresh before changing permissions"
        }
        val permission = api.json(HttpMethod.Post, permissionUrl(file.id, parameters = mapOf("fields" to permissionFields)),
            buildJsonObject {
                put("type", if (request.kind == StorageShareKind.LINK) "anyone" else "user")
                put("role", request.role)
                request.recipient?.let { put("emailAddress", it) }
                request.expiresAt?.let { put("expirationTime", kotlin.time.Instant.fromEpochMilliseconds(it).toString()) }
                if (request.kind == StorageShareKind.LINK) put("allowFileDiscovery", false)
            }).storageObject()
        val permissionId = permission.storageString("id")
        require(permissionId.isNotBlank()) { "Google Drive did not identify the created permission" }
        try {
            val exact = StoragePath(path.path, entry(file, path.path).reference)
            val result = parseShare(exact, permission)
            check(result.kind == request.kind && result.role == request.role && result.expiresAt == request.expiresAt &&
                (request.recipient == null || result.recipient.equals(request.recipient, ignoreCase = true))) {
                "Google Drive returned different sharing permissions; refresh the sharing state"
            }
            val verified = if (request.kind == StorageShareKind.LINK) {
                val current = api.json(HttpMethod.Get, fileUrl(file.id, mapOf("fields" to "webViewLink"))).storageObject()
                result.copy(url = current.storageString("webViewLink"))
            } else result
            currentCoroutineContext().ensureActive()
            verified
        } catch (failure: Throwable) {
            if (previous.any { it.id == permissionId }) throw failure
            compensateStorageShare(failure) { api.empty(HttpMethod.Delete, permissionUrl(file.id, permissionId)) }
        }
    }

    override suspend fun revokeShare(share: StorageShare): Result<Unit> = storageResult {
        require(share.connectionBinding.isNotEmpty() && share.connectionBinding == uploadBinding()) { "Share belongs to another storage connection" }
        require(share.canRevoke) { "This storage permission cannot be revoked directly" }
        val file = selected(share.file)
        require(share.file.reference?.id == file.id) { "Share requires the selected stable file ID" }
        advancedCapabilities(share.file).getOrThrow().require(StorageAdvancedFeature.REVOKE_SHARE)
        val current = api.json(HttpMethod.Get, permissionUrl(file.id, share.id,
            mapOf("fields" to permissionFields))).storageObject()
        val existing = parseShare(share.file, current)
        require(existing.canRevoke && existing.kind == share.kind && existing.role == share.role) { "Storage share has changed; refresh before revoking" }
        api.empty(HttpMethod.Delete, permissionUrl(file.id, share.id))
        try {
            api.json(HttpMethod.Get, permissionUrl(file.id, share.id))
            error("Storage share revocation could not be verified")
        } catch (failure: StorageApiException) {
            if (failure.status != 404) throw failure
        }
    }

}

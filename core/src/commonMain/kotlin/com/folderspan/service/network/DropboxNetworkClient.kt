package com.folderspan.service.network

import io.ktor.http.*
import io.ktor.client.request.*
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal fun dropboxHeader(value: JsonElement): String = buildString {
    for (character in value.toString()) {
        if (character.code in 32..126) append(character)
        else append("\\u").append(character.code.toString(16).padStart(4, '0'))
    }
}

internal class DropboxNetworkClient(
    config: NetworkConnectionConfig,
    api: StorageHttpApi = storageHttpApi(config),
) : IdStorageProvider(config, api) {
    override val rootId: String get() = config.extras.cloud.rootId.ifBlank { "__root__" }
    private fun rpc(operation: String) = "https://api.dropboxapi.com/2/files/$operation"
    private fun content(operation: String) = "https://content.dropboxapi.com/2/files/$operation"
    private fun parse(value: JsonElement): CloudStorageNode {
        val obj = value.storageObject()
        val kind = obj.storageString(".tag")
        check(kind == "file" || kind == "folder") { "Unexpected Dropbox item type" }
        return parseNode(obj, kind == "folder")
    }

    /** FileMetadata endpoints return the struct directly, unlike tagged Metadata unions. */
    private fun parseFile(value: JsonElement): CloudStorageNode {
        val obj = value.storageObject()
        check(".tag" !in obj || obj.storageString(".tag") == "file") { "Dropbox returned another metadata type" }
        val node = parseNode(obj, directory = false)
        check(node.id.isNotBlank() && node.size >= 0) { "Dropbox returned incomplete file metadata" }
        return node
    }

    private fun parseNode(obj: JsonObject, directory: Boolean): CloudStorageNode = CloudStorageNode(
        id = obj.storageString("id"), name = obj.storageString("name"), directory = directory,
        size = obj.storageLong("size") ?: -1L, modified = storageTime(obj.storageOptionalString("server_modified")),
        nativePath = obj.storageString("path_display"),
    )

    override suspend fun node(id: String): CloudStorageNode = if (id == "__root__") {
        CloudStorageNode("__root__", "/", true, nativePath = "")
    } else parse(api.readJson(HttpMethod.Post, rpc("get_metadata"), buildJsonObject { put("path", id) }))

    override suspend fun withinRoot(value: CloudStorageNode, root: CloudStorageNode): Boolean {
        val prefix = root.nativePath.trimEnd('/').lowercase()
        val path = value.nativePath.lowercase()
        return prefix.isEmpty() || path == prefix || path.startsWith("$prefix/")
    }

    override suspend fun children(directory: CloudStorageNode): List<CloudStorageNode> = collectStoragePages { cursor ->
        val result = api.readJson(HttpMethod.Post, rpc(if (cursor == null) "list_folder" else "list_folder/continue"),
            buildJsonObject {
                if (cursor != null) put("cursor", cursor) else {
                    put("path", if (directory.id == "__root__") "" else directory.id)
                    put("recursive", false); put("include_deleted", false); put("limit", 2000)
                }
            }).storageObject()
        val more = (result["has_more"] as? JsonPrimitive)?.booleanOrNull ?: error("Dropbox page has no continuation status")
        (result["entries"] as? JsonArray ?: error("Dropbox page has no entries")).map(::parse) to
            if (more) result.storageString("cursor") else null
    }

    override suspend fun read(node: CloudStorageNode, size: Long, onChunk: suspend (ByteArray, Long) -> Result<Unit>) {
        val headers = api.bearerHeaders() + ("Dropbox-API-Arg" to dropboxHeader(buildJsonObject { put("path", node.id) }))
        var delivered = false
        api.retryStorageRead(retryIf = { !delivered }) {
            api.client.readStorageFile(Url(content("download")), size, headers, method = HttpMethod.Post) { bytes, total ->
                delivered = true
                onChunk(bytes, total)
            }.getOrThrow()
        }
    }

    private fun commit(
        parent: CloudStorageNode, name: String, existing: CloudStorageNode?, expectedRevision: String? = null,
    ): JsonObject = buildJsonObject {
        put("path", existing?.id ?: parent.nativePath.trimEnd('/') + "/" + name)
        if (expectedRevision == null) put("mode", if (existing == null) "add" else "overwrite")
        else {
            require(existing != null && expectedRevision.isNotBlank()) { "Conditional Dropbox upload requires an existing revision" }
            putJsonObject("mode") { put(".tag", "update"); put("update", expectedRevision) }
        }
        put("autorename", false); put("strict_conflict", true)
    }

    override suspend fun write(parent: CloudStorageNode, name: String, existing: CloudStorageNode?, size: Long, progress: (Long, Long) -> Unit, source: suspend () -> ByteArray?) {
        val target = StoragePath("/" + name, StorageReference(existing?.id.orEmpty(), parent.id))
        var session = beginUpload(parent, name, existing, size, target)
        val input = StorageUploadChunks(size, source)
        while (session.confirmedOffset < size) {
            val part = input.next(uploadConstraints.maxChunkSize) ?: error("Storage upload ended early")
            val expectedOffset = session.confirmedOffset + part.size
            session = uploadChunk(session, session.confirmedOffset, part).getOrThrow()
            check(session.confirmedOffset == expectedOffset) { "Storage upload requires source repositioning" }
            progress(session.confirmedOffset, size)
        }
        check(input.next(uploadConstraints.maxChunkSize) == null) { "Storage upload length exceeded" }
        session = finishUpload(session).getOrThrow()
        check(session.status == StorageUploadStatus.COMPLETED) { "Dropbox upload is incomplete" }
        progress(size, size)
    }

    override suspend fun mkdir(parent: CloudStorageNode, name: String) {
        api.json(HttpMethod.Post, rpc("create_folder_v2"), buildJsonObject {
            put("path", parent.nativePath.trimEnd('/') + "/" + name); put("autorename", false)
        })
    }
    override suspend fun remove(node: CloudStorageNode) {
        api.json(HttpMethod.Post, rpc("delete_v2"), buildJsonObject { put("path", node.id) })
    }
    override suspend fun move(node: CloudStorageNode, parent: CloudStorageNode, name: String) {
        api.json(HttpMethod.Post, rpc("move_v2"), buildJsonObject {
            put("from_path", node.id); put("to_path", parent.nativePath.trimEnd('/') + "/" + name); put("autorename", false)
        })
    }
    override val contentManifestReadable: Boolean get() = true
    override val contentManifestConditionalWrites: Boolean get() = true

    private suspend fun manifestPath(anchor: String) =
        root().nativePath.trimEnd('/') + "/" + contentManifestFileName(anchor)

    override suspend fun readContentManifest(anchor: String): Result<StorageContentManifestSnapshot?> = storageResult {
        api.retryStorageRead {
            val path = manifestPath(anchor)
            api.client.preparePost(content("download")) {
                api.bearerHeaders().forEach { (key, value) -> header(key, value) }
                header("Dropbox-API-Arg", dropboxHeader(buildJsonObject { put("path", path) }))
            }.execute { response ->
                if (response.status == HttpStatusCode.Conflict) {
                    val error = parseStorageJson(response.readStorageBody(64 * 1024)).storageObject()
                    if (error.storageOptionalString("error_summary")?.startsWith("path/not_found/") == true) null
                    else throw StorageApiException(response.status.value)
                } else {
                    if (!response.status.isSuccess()) throw StorageApiException(response.status.value)
                    val metadata = parseStorageJson(checkNotNull(response.headers["Dropbox-API-Result"])).storageObject()
                    val revision = metadata.storageString("rev")
                    require(revision.matches(Regex("[0-9a-f]{9,128}"))) { "Invalid Dropbox content manifest revision" }
                    StorageContentManifestSnapshot(revision,
                        response.readStorageBody(STORAGE_CONTENT_MANIFEST_LIMIT.toLong()).encodeToByteArray())
                }
            }
        }
    }

    override suspend fun compareAndSetContentManifest(
        anchor: String, expectedRevision: String?, bytes: ByteArray,
    ): Result<StorageContentManifestSnapshot> = storageResult {
        check(capabilities.write) { "Content metadata requires write permission" }
        requireContentManifestBody(bytes)
        require(expectedRevision == null || expectedRevision.matches(Regex("[0-9a-f]{9,128}"))) { "Invalid content manifest revision" }
        val path = manifestPath(anchor)
        val argument = buildJsonObject {
            put("path", path)
            if (expectedRevision == null) put("mode", "add") else putJsonObject("mode") {
                put(".tag", "update"); put("update", expectedRevision)
            }
            put("autorename", false); put("strict_conflict", true); put("mute", true)
        }
        api.client.preparePost(content("upload")) {
            api.bearerHeaders().forEach { (key, value) -> header(key, value) }
            header("Dropbox-API-Arg", dropboxHeader(argument))
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }.execute { response ->
            if (response.status == HttpStatusCode.Conflict) {
                val error = parseStorageJson(response.readStorageBody(64 * 1024)).storageObject()
                if (error.storageOptionalString("error_summary")?.startsWith("path/conflict/") == true) {
                    throw StorageContentManifestConflictException()
                }
                throw StorageApiException(response.status.value)
            }
            if (!response.status.isSuccess()) throw StorageApiException(response.status.value)
            val metadata = parseStorageJson(response.readStorageBody(64 * 1024)).storageObject()
            val revision = metadata.storageString("rev")
            require(revision.matches(Regex("[0-9a-f]{9,128}")) &&
                metadata.storageLong("size") == bytes.size.toLong()) { "Dropbox content manifest result changed" }
            StorageContentManifestSnapshot(revision, bytes.copyOf())
        }
    }

    override suspend fun contentObjectIdentity(path: StoragePath): Result<String?> = storageResult {
        try { resolve(path, root()).id } catch (_: StorageEntryNotFoundException) { null }
    }

    private val uploadConstraints = StorageUploadConstraints(maxFileSize = 2_199_019_061_248L)

    override suspend fun advancedCapabilities(path: StoragePath): Result<StorageAdvancedCapabilities> = storageResult {
        val currentRoot = root()
        val selected = try { resolve(path, currentRoot) } catch (_: StorageEntryNotFoundException) { null }
        val item = selected ?: uploadTarget(path).first
        val metadata = if (item.id == "__root__") null else api.readJson(HttpMethod.Post, rpc("get_metadata"),
            buildJsonObject { put("path", item.id) }).storageObject()
        val readOnly = ((metadata?.get("sharing_info") as? JsonObject)?.get("read_only") as? JsonPrimitive)?.booleanOrNull == true
        fun status(value: Boolean) = if (value) StorageFeatureAvailability.AVAILABLE else StorageFeatureAvailability.PERMISSION_DENIED
        val write = capabilities.write && !readOnly
        val features = mutableMapOf(StorageAdvancedFeature.RESUMABLE_UPLOAD to status(write))
        val roles = mutableSetOf<String>()
        var expiry = false
        if (selected != null && !item.directory && !item.symbolicLink) {
            features[StorageAdvancedFeature.LIST_VERSIONS] = status(capabilities.read)
            features[StorageAdvancedFeature.READ_VERSION] = status(capabilities.read)
            features[StorageAdvancedFeature.RESTORE_VERSION] = status(write)
            try {
                val shared = api.readJson(HttpMethod.Post, sharing("get_file_metadata"), buildJsonObject {
                    put("file", item.id)
                    putJsonArray("actions") {
                        for (name in listOf("invite_viewer", "invite_editor", "create_view_link", "unshare"))
                            add(buildJsonObject { put(".tag", name) })
                    }
                }).storageObject()
                check(shared.storageString("id") == item.id) { "Dropbox permissions belong to another file" }
                val allowed = (shared["permissions"] as? JsonArray).orEmpty().filter {
                    (it.storageObject()["allow"] as? JsonPrimitive)?.booleanOrNull == true
                }.mapNotNull { tag(it.storageObject()["action"]) }.toSet()
                if ("invite_viewer" in allowed || "create_view_link" in allowed) roles += "viewer"
                if ("invite_editor" in allowed) roles += "editor"
                features[StorageAdvancedFeature.LIST_SHARES] = StorageFeatureAvailability.AVAILABLE
                features[StorageAdvancedFeature.CREATE_LINK] = status("create_view_link" in allowed)
                features[StorageAdvancedFeature.INVITE_USER] = status("invite_viewer" in allowed || "invite_editor" in allowed)
                features[StorageAdvancedFeature.REVOKE_SHARE] = status("unshare" in allowed)
                expiry = true
            } catch (failure: StorageApiException) {
                if (failure.status !in listOf(401, 403, 409)) throw failure
                val unavailable = if (failure.status == 401) StorageFeatureAvailability.AUTHORIZATION_REQUIRED else StorageFeatureAvailability.PERMISSION_DENIED
                for (feature in listOf(StorageAdvancedFeature.LIST_SHARES, StorageAdvancedFeature.CREATE_LINK,
                    StorageAdvancedFeature.INVITE_USER, StorageAdvancedFeature.REVOKE_SHARE)) features[feature] = unavailable
            }
        }
        StorageAdvancedCapabilities(features, upload = uploadConstraints, shareRoles = roles, shareExpiry = expiry)
    }

    override suspend fun startUpload(target: StoragePath, size: Long): Result<StorageUploadSession> = storageResult {
        require(size in 0..uploadConstraints.maxFileSize) { "Dropbox upload size exceeds its limit" }
        advancedCapabilities(target).getOrThrow().require(StorageAdvancedFeature.RESUMABLE_UPLOAD)
        val (parent, name, existing) = uploadTarget(target)
        beginUpload(parent, name, existing, size, target)
    }

    private suspend fun beginUpload(
        parent: CloudStorageNode, name: String, existing: CloudStorageNode?, size: Long, target: StoragePath,
        expectedRevision: String? = null,
    ): StorageUploadSession {
        require(size in 0..uploadConstraints.maxFileSize) { "Dropbox upload size exceeds its limit" }
        val start = api.upload(HttpMethod.Post, content("upload_session/start"), 0, { _, _ -> }, { null },
            mapOf("Dropbox-API-Arg" to "{\"close\":false}")).storageObject()
        val secret = buildJsonObject {
            put("session", start.storageString("session_id"))
            put("commit", commit(parent, name, existing, expectedRevision))
        }.toString()
        return StorageUploadSession("Dropbox", target.copy(reference = StorageReference(existing?.id.orEmpty(), parent.id)),
            size, 0, secret = secret, binding = uploadBinding())
    }

    private fun sessionData(session: StorageUploadSession): JsonObject {
        validateUploadSession(session, "Dropbox")
        return parseStorageJson(session.secret).storageObject()
    }

    private fun cursor(session: StorageUploadSession, data: JsonObject): JsonObject = buildJsonObject {
        put("session_id", data.storageString("session"))
        put("offset", session.confirmedOffset)
    }

    private suspend fun append(session: StorageUploadSession, bytes: ByteArray): StorageUploadSession {
        val data = sessionData(session)
        val argument = buildJsonObject { put("cursor", cursor(session, data)); put("close", false) }
        var pending: ByteArray? = bytes.takeIf { it.isNotEmpty() }
        return api.uploadResponse(HttpMethod.Post, content("upload_session/append_v2"), bytes.size.toLong(), { _, _ -> },
            { pending.also { pending = null } }, mapOf("Dropbox-API-Arg" to dropboxHeader(argument))) { response ->
            when (response.status.value) {
                200 -> session.copy(confirmedOffset = session.confirmedOffset + bytes.size, status = StorageUploadStatus.UPLOADING)
                409 -> {
                    var detail = parseStorageJson(response.readStorageBody(64 * 1024L)).storageObject()["error"]?.storageObject()
                        ?: error("Dropbox upload error has no detail")
                    if (detail.storageOptionalString(".tag") == "lookup_failed") detail = detail["lookup_failed"]?.storageObject() ?: error("Missing Dropbox lookup error")
                    when (detail.storageOptionalString(".tag")) {
                        "incorrect_offset" -> {
                            val offset = detail.storageLong("correct_offset") ?: error("Dropbox upload error has no confirmed offset")
                            check(offset in 0..session.size) { "Dropbox upload offset exceeds its length" }
                            session.copy(confirmedOffset = offset, status = StorageUploadStatus.UPLOADING)
                        }
                        "not_found" -> session.copy(status = if (session.status == StorageUploadStatus.UNCERTAIN)
                            StorageUploadStatus.UNCERTAIN else StorageUploadStatus.EXPIRED)
                        "closed" -> session.copy(status = StorageUploadStatus.UNCERTAIN)
                        else -> throw StorageApiException(409)
                    }
                }
                401 -> throw StorageFeatureException(StorageFeatureAvailability.AUTHORIZATION_REQUIRED)
                403 -> throw StorageFeatureException(StorageFeatureAvailability.PERMISSION_DENIED)
                else -> throw StorageApiException(response.status.value)
            }
        }
    }

    override suspend fun queryUpload(session: StorageUploadSession): Result<StorageUploadSession> =
        storageResult { append(session, byteArrayOf()) }

    override suspend fun uploadChunk(session: StorageUploadSession, offset: Long, bytes: ByteArray): Result<StorageUploadSession> = storageResult {
        uploadConstraints.validate(session.size, offset, bytes.size)
        require(offset == session.confirmedOffset && session.status == StorageUploadStatus.UPLOADING) { "Upload offset must be confirmed first" }
        append(session, bytes)
    }

    override suspend fun finishUpload(session: StorageUploadSession): Result<StorageUploadSession> = storageResult {
        if (session.status == StorageUploadStatus.COMPLETED) {
            require(session.backend == "Dropbox" && session.binding == uploadBinding()) { "Upload session belongs to another connection" }
            return@storageResult session
        }
        val data = sessionData(session)
        require(session.confirmedOffset == session.size && session.status == StorageUploadStatus.UPLOADING) {
            "Dropbox upload must be fully confirmed before commit"
        }
        try {
            api.uploadResponse(HttpMethod.Post, content("upload_session/finish"), 0, { _, _ -> }, { null },
                mapOf("Dropbox-API-Arg" to dropboxHeader(buildJsonObject {
                    put("cursor", cursor(session, data)); put("commit", data["commit"] ?: error("Missing Dropbox commit"))
                }))) { response ->
                if (!response.status.isSuccess()) throw StorageApiException(response.status.value)
                val value = parseFile(parseStorageJson(response.readStorageBody(8L * 1024 * 1024)))
                val expectedId = session.target.reference?.id.orEmpty()
                check(expectedId.isEmpty() || expectedId == value.id) { "Dropbox upload changed the target ID" }
                check(value.size == session.size) { "Dropbox upload returned an unexpected length" }
                session.copy(confirmedOffset = session.size, status = StorageUploadStatus.COMPLETED,
                    entry = entry(value, session.target.path, session.target.reference?.parentId))
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException || (failure is StorageApiException && failure.status < 500)) throw failure
            throw StorageUploadUncertainException(session.copy(status = StorageUploadStatus.UNCERTAIN))
        }
    }

    override suspend fun cancelUpload(session: StorageUploadSession): Result<Unit> = storageResult {
        validateUploadSession(session, "Dropbox")
        cancelledUploads.add(session.secret)
    }

    private fun sharing(operation: String) = "https://api.dropboxapi.com/2/sharing/$operation"
    private fun tag(value: JsonElement?): String? = when (value) {
        is JsonObject -> value.storageOptionalString(".tag")
        is JsonPrimitive -> value.contentOrNull
        else -> null
    }

    private suspend fun selected(path: StoragePath): CloudStorageNode = resolve(path, root()).also {
        require(!it.directory && !it.symbolicLink) { "Select a regular Dropbox file" }
    }

    private fun exact(path: StoragePath, node: CloudStorageNode) =
        StoragePath(path.path, entry(node, path.path).reference)

    override suspend fun listVersions(path: StoragePath, cursor: String?): Result<StoragePage<StorageVersion>> = storageResult {
        advancedCapabilities(path).getOrThrow().require(StorageAdvancedFeature.LIST_VERSIONS)
        val file = selected(path)
        require(cursor == null || (cursor.length in 1..4096 && cursor.none(Char::isISOControl))) { "Invalid Dropbox revision cursor" }
        val page = api.readJson(HttpMethod.Post, rpc("list_revisions"), buildJsonObject {
            put("path", file.id); putJsonObject("mode") { put(".tag", "id") }; put("limit", 100)
            cursor?.let { put("before_rev", it) }
        }).storageObject()
        val versions = (page["entries"] as? JsonArray ?: error("Dropbox returned no revisions")).map {
            val value = it.storageObject()
            check(value.storageString("id") == file.id) { "Dropbox revision belongs to another file" }
            StorageVersion(exact(path, file), value.storageString("rev"), value.storageLong("size"),
                value.storageOptionalString("server_modified")?.let(::storageTime), connectionBinding = uploadBinding())
        }
        val more = (page["has_more"] as? JsonPrimitive)?.booleanOrNull ?: error("Dropbox revisions have no continuation status")
        val next = if (more) versions.lastOrNull()?.id ?: error("Dropbox revision page has no continuation") else null
        check(next == null || next != cursor) { "Dropbox revision pagination cycle" }
        StoragePage(versions, next)
    }

    private suspend fun checkedRevision(version: StorageVersion): Pair<CloudStorageNode, JsonObject> {
        require(version.connectionBinding.isNotEmpty() && version.connectionBinding == uploadBinding()) { "Revision belongs to another storage connection" }
        val file = selected(version.file)
        require(version.file.reference?.id == file.id && version.id.length in 1..4096 &&
            version.id.none(Char::isISOControl)) { "Revision requires the selected stable Dropbox file ID" }
        val metadata = api.readJson(HttpMethod.Post, rpc("get_metadata"), buildJsonObject { put("path", "rev:" + version.id) }).storageObject()
        check(metadata.storageString("id") == file.id && metadata.storageString("rev") == version.id) { "Dropbox revision belongs to another file" }
        return file to metadata
    }

    override suspend fun readVersion(version: StorageVersion, onChunk: suspend (ByteArray, Long) -> Result<Unit>): Result<Unit> = storageResult {
        require(version.connectionBinding.isNotEmpty() && version.connectionBinding == uploadBinding()) { "Revision belongs to another storage connection" }
        advancedCapabilities(version.file).getOrThrow().require(StorageAdvancedFeature.READ_VERSION)
        val (_, metadata) = checkedRevision(version)
        val headers = api.bearerHeaders() + ("Dropbox-API-Arg" to dropboxHeader(buildJsonObject { put("path", "rev:" + version.id) }))
        var delivered = false
        api.retryStorageRead(retryIf = { !delivered }) {
            api.client.readStorageFile(Url(content("download")), metadata.storageLong("size") ?: -1L,
                headers, method = HttpMethod.Post) { bytes, total ->
                delivered = true
                onChunk(bytes, total)
            }.getOrThrow()
        }
    }

    override suspend fun restoreVersion(version: StorageVersion, confirmed: Boolean): Result<NetworkFileEntry> = storageResult {
        require(version.connectionBinding.isNotEmpty() && version.connectionBinding == uploadBinding()) { "Revision belongs to another storage connection" }
        require(confirmed) { "Confirm restoring the selected Dropbox revision" }
        advancedCapabilities(version.file).getOrThrow().require(StorageAdvancedFeature.RESTORE_VERSION)
        val (file, metadata) = checkedRevision(version)
        val current = api.readJson(HttpMethod.Post, rpc("get_metadata"), buildJsonObject { put("path", file.id) }).storageObject()
        check(current.storageString("id") == file.id) { "Dropbox restore target changed" }
        val (parent, name, existing) = uploadTarget(version.file)
        check(existing?.id == file.id) { "Dropbox restore target changed" }
        val size = metadata.storageLong("size") ?: error("Dropbox revision size is unknown")
        var session = beginUpload(parent, name, existing, size, version.file, current.storageString("rev"))
        // Native restore only accepts paths; conditional ID uploads cannot overwrite a same-name replacement.
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
                val input = StorageUploadChunks(size) {
                    val chunk = chunks.receiveCatching()
                    chunk.exceptionOrNull()?.let { throw it }
                    chunk.getOrNull()
                }
                while (session.confirmedOffset < size) {
                    val part = input.next(uploadConstraints.maxChunkSize) ?: error("Dropbox revision ended early")
                    val expectedOffset = session.confirmedOffset + part.size
                    session = uploadChunk(session, session.confirmedOffset, part).getOrThrow()
                    check(session.confirmedOffset == expectedOffset) { "Dropbox revision upload offset changed" }
                }
                check(input.next(uploadConstraints.maxChunkSize) == null) { "Dropbox revision exceeded its length" }
                producer.await()
                session = finishUpload(session).getOrThrow()
            } finally { producer.cancel(); chunks.cancel() }
        }
        val result = api.readJson(HttpMethod.Post, rpc("get_metadata"), buildJsonObject { put("path", file.id) }).storageObject()
        check(result.storageString("id") == file.id && result.storageLong("size") == metadata.storageLong("size") &&
            (metadata.storageOptionalString("content_hash") == null || result.storageOptionalString("content_hash") == metadata.storageOptionalString("content_hash"))) { "Dropbox revision restore could not be verified" }
        entry(parseFile(result), version.file.path)
    }

    private fun shareCursor(kind: String, cursor: String? = null) = buildJsonObject {
        put("kind", kind); cursor?.let { put("cursor", it) }
    }.toString()

    private fun link(path: StoragePath, fileId: String, value: JsonObject): StorageShare {
        check(value.storageString("id") == fileId) { "Dropbox link belongs to another file" }
        val permissions = value["link_permissions"]?.storageObject() ?: error("Dropbox link permissions are unknown")
        val role = tag(permissions["link_access_level"]) ?: error("Dropbox link access level is unknown")
        return StorageShare(path, value.storageString("url"), StorageShareKind.LINK, role,
            expiresAt = value.storageOptionalString("expires")?.let(::storageTime), url = value.storageString("url"),
            canRevoke = (permissions["can_revoke"] as? JsonPrimitive)?.booleanOrNull == true, connectionBinding = uploadBinding())
    }

    private fun member(path: StoragePath, value: JsonObject, kind: String): StorageShare {
        val user = value["user"] as? JsonObject
        val invitee = value["invitee"] as? JsonObject
        val group = value["group"] as? JsonObject
        val email = user?.storageOptionalString("email") ?: invitee?.storageOptionalString("email")
        val selector = buildJsonObject {
            when (kind) {
                "users" -> { put(".tag", "dropbox_id"); put("dropbox_id", user?.storageString("account_id") ?: error("Missing Dropbox member")) }
                "groups" -> { put(".tag", "dropbox_id"); put("dropbox_id", group?.storageString("group_id") ?: error("Missing Dropbox group")) }
                else -> { put(".tag", "email"); put("email", email ?: error("Missing Dropbox invitee")) }
            }
        }
        val role = tag(value["access_type"]) ?: error("Dropbox member role is unknown")
        val removable = (value["permissions"] as? JsonArray).orEmpty().any {
            val permission = it.storageObject()
            tag(permission["action"]) == "remove" && (permission["allow"] as? JsonPrimitive)?.booleanOrNull == true
        }
        return StorageShare(path, selector.toString(), StorageShareKind.INVITATION, role, email, canRevoke = role != "owner" && removable, connectionBinding = uploadBinding())
    }

    override suspend fun listShares(path: StoragePath, cursor: String?): Result<StoragePage<StorageShare>> = storageResult {
        advancedCapabilities(path).getOrThrow().require(StorageAdvancedFeature.LIST_SHARES)
        val file = selected(path)
        require(cursor == null || cursor.length in 1..64 * 1024)
        val state = cursor?.let { parseStorageJson(it).storageObject() }
        val kind = state?.storageOptionalString("kind") ?: "links"
        val next = state?.storageOptionalString("cursor")
        val exact = exact(path, file)
        if (kind == "links") {
            val page = api.readJson(HttpMethod.Post, sharing("list_shared_links"), buildJsonObject {
                if (next == null) { put("path", file.id); put("direct_only", true) } else put("cursor", next)
            }).storageObject()
            val more = (page["has_more"] as? JsonPrimitive)?.booleanOrNull ?: error("Dropbox sharing page has no continuation status")
            StoragePage((page["links"] as? JsonArray ?: error("Dropbox sharing page has no links")).map {
                link(exact, file.id, it.storageObject())
            }, if (more) shareCursor("links", page.storageString("cursor")) else shareCursor("members"))
        } else {
            require(kind == "members") { "Invalid Dropbox sharing cursor" }
            val page = api.readJson(HttpMethod.Post, sharing(if (next == null) "list_file_members" else "list_file_members/continue"), buildJsonObject {
                if (next == null) {
                    put("file", file.id); put("include_inherited", false); put("limit", 100)
                    putJsonArray("actions") { add(buildJsonObject { put(".tag", "remove") }) }
                } else put("cursor", next)
            }).storageObject()
            val entries = listOf("users", "groups", "invitees").flatMap { memberKind ->
                (page[memberKind] as? JsonArray).orEmpty().map { member(exact, it.storageObject(), memberKind) }
            }
            StoragePage(entries, page.storageOptionalString("cursor")?.let { shareCursor("members", it) })
        }
    }

    private suspend fun findShare(path: StoragePath, matches: (StorageShare) -> Boolean): StorageShare? {
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        repeat(10_000) {
            val page = listShares(path, cursor).getOrThrow()
            page.entries.firstOrNull(matches)?.let { return it }
            cursor = page.nextCursor ?: return null
            check(seen.add(cursor)) { "Dropbox sharing pagination cycle" }
        }
        error("Dropbox sharing page limit exceeded")
    }

    override suspend fun createShare(path: StoragePath, request: StorageShareRequest, confirmed: Boolean): Result<StorageShare> = storageResult {
        require(confirmed) { "Confirm the selected Dropbox recipient and role" }
        val file = selected(path)
        advancedCapabilities(path).getOrThrow().validateShare(request)
        if (request.kind == StorageShareKind.LINK) {
            require(request.role == "viewer") { "Dropbox link access level is unsupported" }
            val result = api.json(HttpMethod.Post, sharing("create_shared_link_with_settings"), buildJsonObject {
                put("path", file.id)
                putJsonObject("settings") {
                    putJsonObject("access") { put(".tag", "viewer") }
                    putJsonObject("audience") { put(".tag", "public") }
                    put("allow_download", true)
                    request.expiresAt?.let { put("expires", kotlin.time.Instant.fromEpochMilliseconds(it).toString()) }
                }
            }).storageObject()
            check(result.storageString("id") == file.id) { "Dropbox link belongs to another file" }
            val newUrl = result.storageString("url")
            require(newUrl.isNotBlank()) { "Dropbox did not identify the created link" }
            try {
                val share = link(exact(path, file), file.id, result)
                val permissions = result["link_permissions"]!!.storageObject()
                val audience = tag(permissions["effective_audience"]) ?: tag(permissions["resolved_visibility"])
                check(share.role == request.role && share.expiresAt == request.expiresAt && audience == "public") {
                    "Dropbox returned different link permissions"
                }
                currentCoroutineContext().ensureActive()
                share
            } catch (failure: Throwable) {
                compensateStorageShare(failure) {
                    api.empty(HttpMethod.Post, sharing("revoke_shared_link"), buildJsonObject { put("url", newUrl) })
                }
            }
        } else {
            require(request.expiresAt == null) { "Dropbox invitations do not support expiry" }
            require(findShare(path) { it.kind == StorageShareKind.INVITATION &&
                it.recipient.equals(request.recipient, ignoreCase = true) } == null) {
                "This recipient already has access; refresh before changing permissions"
            }
            val result = api.json(HttpMethod.Post, sharing("add_file_member"), buildJsonObject {
                put("file", file.id); put("quiet", false)
                putJsonArray("members") { add(buildJsonObject { put(".tag", "email"); put("email", request.recipient!!) }) }
                putJsonObject("access_level") { put(".tag", request.role) }
            }) as? JsonArray ?: error("Dropbox returned no invitation results")
            val outcome = result.singleOrNull()?.storageObject()?.get("result")?.storageObject()
                ?: error("Dropbox invitation result is ambiguous")
            check(outcome.storageOptionalString(".tag") == "success") { "Dropbox did not confirm the invitation" }
            try {
                check(tag(outcome["success"]) == request.role) { "Dropbox did not confirm the requested invitation role" }
                val verified = findShare(path) { it.kind == StorageShareKind.INVITATION &&
                    it.recipient.equals(request.recipient, ignoreCase = true) && it.role == request.role }
                    ?: error("Dropbox invitation could not be verified")
                currentCoroutineContext().ensureActive()
                verified
            } catch (failure: Throwable) {
                compensateStorageShare(failure) {
                    val removed = api.json(HttpMethod.Post, sharing("remove_file_member_2"), buildJsonObject {
                        put("file", file.id)
                        putJsonObject("member") { put(".tag", "email"); put("email", request.recipient!!) }
                    }).storageObject()
                    check(removed.storageOptionalString(".tag") == "success") { "Dropbox did not confirm member removal" }
                }
            }
        }
    }

    override suspend fun revokeShare(share: StorageShare): Result<Unit> = storageResult {
        require(share.connectionBinding.isNotEmpty() && share.connectionBinding == uploadBinding()) { "Share belongs to another storage connection" }
        require(share.canRevoke) { "This Dropbox grant cannot be revoked directly" }
        val file = selected(share.file)
        require(share.file.reference?.id == file.id) { "Share requires the selected stable Dropbox file ID" }
        advancedCapabilities(share.file).getOrThrow().require(StorageAdvancedFeature.REVOKE_SHARE)
        val current = findShare(share.file) { it.id == share.id && it.kind == share.kind }
            ?: error("Dropbox share no longer exists")
        require(current.canRevoke && current.role == share.role) { "Dropbox share has changed; refresh before revoking" }
        if (share.kind == StorageShareKind.LINK) {
            api.empty(HttpMethod.Post, sharing("revoke_shared_link"), buildJsonObject { put("url", current.id) })
        } else {
            val result = api.json(HttpMethod.Post, sharing("remove_file_member_2"), buildJsonObject {
                put("file", file.id); put("member", parseStorageJson(current.id))
            }).storageObject()
            check(result.storageOptionalString(".tag") == "success") { "Dropbox did not confirm member removal" }
        }
        check(findShare(share.file) { it.id == share.id && it.kind == share.kind } == null) { "Dropbox share revocation could not be verified" }
    }

}

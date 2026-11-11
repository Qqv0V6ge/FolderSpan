package com.folderspan.service.network

import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.test.runSuspendTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.*

class StorageVersionsSharingTest {
    private val target = StoragePath("/same.txt", StorageReference("B", "root"))
    private fun config(protocol: String) = NetworkConnectionConfig(protocol, "", "", "",
        extras = NetworkDriveExtras(cloud = CloudDriveExtras(accountId = "account", connectionId = "connection")))
    private fun google(id: String = "B", folder: Boolean = false, checksum: Boolean = false, historyReadable: Boolean = true) = buildJsonObject {
        put("id", id); put("name", if (folder) "/" else "same.txt")
        put("mimeType", if (folder) "application/vnd.google-apps.folder" else "application/octet-stream")
        put("size", "7"); if (!folder) putJsonArray("parents") { add("root") }
        if (checksum) put("md5Checksum", "checksum")
        putJsonObject("capabilities") {
            put("canReadRevisions", historyReadable); put("canEdit", true); put("canShare", true); put("canAddChildren", true)
        }
    }.toString()
    private fun dropbox(revision: String = "current") = """{".tag":"file","id":"B","name":"same.txt","path_display":"/same.txt","size":7,"rev":"$revision","content_hash":"checksum"}"""
    private val dropboxPermissions = """{"id":"B","permissions":[
        {"action":{".tag":"invite_viewer"},"allow":true},
        {"action":{".tag":"invite_editor"},"allow":true},
        {"action":{".tag":"create_view_link"},"allow":true},
        {"action":{".tag":"unshare"},"allow":true}]}"""
    private val link = """{"id":"B","url":"https://www.dropbox.com/s/private","link_permissions":{
        "link_access_level":{".tag":"viewer"},"effective_audience":{".tag":"public"},"can_revoke":true}}"""



    @Test
    fun googleCancellationAfterCreatingLinkStillRevokesOnlyTheNewPermission() = runSuspendTest {
        val verifying = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val deleted = mutableListOf<String>()
        var created = false
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/permissions") && request.method == HttpMethod.Get ->
                    respond("""{"permissions":[{"id":"existing","type":"user","role":"reader","emailAddress":"existing@example.test"}]}""")
                path.endsWith("/permissions") && request.method == HttpMethod.Post -> {
                    created = true
                    respond("""{"id":"new-link","type":"anyone","role":"reader"}""")
                }
                request.method == HttpMethod.Delete -> {
                    assertTrue(created)
                    assertTrue(path.endsWith("/B/permissions/new-link"))
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                    deleted += path.substringAfterLast('/')
                    respond("")
                }
                request.url.parameters["fields"] == "webViewLink" -> {
                    verifying.complete(Unit)
                    awaitCancellation()
                }
                path.endsWith("/root") -> respond(google("root", true))
                else -> { assertTrue(path.endsWith("/B")); respond(google()) }
            }
        }) { followRedirects = false }
        try {
            val provider = GoogleDriveNetworkClient(config("GoogleDrive"), StorageHttpApi({ "token" }, http))
            coroutineScope {
                val creating = async {
                    provider.createShare(target, StorageShareRequest(StorageShareKind.LINK, "reader"), true)
                }
                try {
                    withTimeout(5_000) { verifying.await() }
                    creating.cancel(CancellationException("User cancelled"))
                    withTimeout(5_000) { cleanupStarted.await() }
                } finally { releaseCleanup.complete(Unit) }
                withTimeout(5_000) { creating.join() }
                assertFailsWith<CancellationException> { creating.await() }
            }
            assertEquals(listOf("new-link"), deleted)
        } finally { releaseCleanup.complete(Unit); http.close() }
    }

    @Test
    fun dropboxCancellationWhileVerifyingNewMemberStillRemovesOnlyThatMember() = runSuspendTest {
        val verifying = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val removed = mutableListOf<String>()
        val recipient = "new@example.test"
        var created = false
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            when {
                path.endsWith("get_file_metadata") -> respond(dropboxPermissions)
                path.endsWith("get_metadata") -> respond(dropbox())
                path.endsWith("list_shared_links") -> respond("""{"links":[],"has_more":false}""")
                path.endsWith("list_file_members") -> if (created) {
                    verifying.complete(Unit)
                    awaitCancellation()
                } else respond("""{"users":[{"user":{"account_id":"existing","email":"existing@example.test"},"access_type":{".tag":"viewer"},"permissions":[]}],"groups":[],"invitees":[]}""")
                path.endsWith("add_file_member") -> {
                    assertEquals("B", body.getValue("file").jsonPrimitive.content)
                    assertEquals(recipient, body.getValue("members").jsonArray.single().jsonObject.getValue("email").jsonPrimitive.content)
                    created = true
                    respond("""[{"result":{".tag":"success","success":{".tag":"viewer"}}}]""")
                }
                path.endsWith("remove_file_member_2") -> {
                    assertEquals("B", body.getValue("file").jsonPrimitive.content)
                    val email = body.getValue("member").jsonObject.getValue("email").jsonPrimitive.content
                    assertEquals(recipient, email)
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                    removed += email
                    respond("""{".tag":"success"}""")
                }
                else -> error("Unexpected Dropbox sharing request")
            }
        }) { followRedirects = false }
        try {
            val provider = DropboxNetworkClient(config("Dropbox"), StorageHttpApi({ "token" }, http))
            coroutineScope {
                val creating = async {
                    provider.createShare(target, StorageShareRequest(StorageShareKind.INVITATION, "viewer", recipient), true)
                }
                try {
                    withTimeout(5_000) { verifying.await() }
                    creating.cancel(CancellationException("User cancelled"))
                    withTimeout(5_000) { cleanupStarted.await() }
                } finally { releaseCleanup.complete(Unit) }
                withTimeout(5_000) { creating.join() }
                assertFailsWith<CancellationException> { creating.await() }
            }
            assertEquals(listOf(recipient), removed)
        } finally { releaseCleanup.complete(Unit); http.close() }
    }

    @Test
    fun googleSharesPreserveRecipientRoleAndExpiryAndRejectPermanentFallback() = runSuspendTest {
        val expiry = (kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000 + 3600) * 1000
        val expires = kotlin.time.Instant.fromEpochMilliseconds(expiry).toString()
        val grants = mutableMapOf<String, JsonObject>()
        val revoked = mutableListOf<String>()
        var creates = 0
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/permissions") && request.method == HttpMethod.Get ->
                    respond(buildJsonObject { putJsonArray("permissions") { grants.values.forEach(::add) } }.toString())
                path.endsWith("/permissions") && request.method == HttpMethod.Post -> {
                    val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    val id = "grant-" + (++creates)
                    val invitation = body["type"]!!.jsonPrimitive.content == "user"
                    val value = buildJsonObject {
                        put("id", id); put("type", if (invitation) "user" else "anyone")
                        put("role", body["role"]!!)
                        if (invitation) {
                            put("emailAddress", body["emailAddress"]!!)
                            assertEquals(expires, body["expirationTime"]!!.jsonPrimitive.content)
                            if (creates != 3) put("expirationTime", body["expirationTime"]!!)
                        } else {
                            assertFalse(body["allowFileDiscovery"]!!.jsonPrimitive.boolean)
                            assertFalse("expirationTime" in body)
                        }
                    }
                    grants[id] = value
                    respond(value.toString())
                }
                request.method == HttpMethod.Delete -> {
                    val id = path.substringAfterLast('/')
                    assertEquals("grant-3", id)
                    revoked += id; grants.remove(id); respond("")
                }
                path.endsWith("/root") -> respond(google("root", true))
                request.url.parameters["fields"] == "webViewLink" -> respond("""{"webViewLink":"https://drive.google.com/file/d/B/view"}""")
                else -> { assertTrue(path.endsWith("/B")); respond(google()) }
            }
        }) { followRedirects = false }
        try {
            val provider = GoogleDriveNetworkClient(config("GoogleDrive"), StorageHttpApi({ "token" }, http))
            val invitation = provider.createShare(target,
                StorageShareRequest(StorageShareKind.INVITATION, "reader", "person@example.test", expiry), true).getOrThrow()
            assertEquals(StorageShareKind.INVITATION, invitation.kind)
            assertEquals("person@example.test", invitation.recipient)
            assertEquals("reader", invitation.role)
            assertEquals(expiry, invitation.expiresAt)
            assertEquals("B", invitation.file.reference?.id)
            assertEquals(storageConnectionBinding(config("GoogleDrive"), "root"), invitation.connectionBinding)
            val link = provider.createShare(target, StorageShareRequest(StorageShareKind.LINK, "reader"), true).getOrThrow()
            assertEquals(StorageShareKind.LINK, link.kind)
            assertEquals("reader", link.role)
            assertNull(link.expiresAt)
            assertEquals("https://drive.google.com/file/d/B/view", link.url)
            assertTrue(provider.createShare(target,
                StorageShareRequest(StorageShareKind.INVITATION, "reader", "other@example.test", expiry), true).isFailure)
            assertEquals(listOf("grant-3"), revoked)
            assertEquals(setOf(invitation.id, link.id), grants.keys)
        } finally { http.close() }
    }

    @Test
    fun dropboxSharesConfirmInvitationsAndExpiringLinksAndRejectChangedSettings() = runSuspendTest {
        val expiry = (kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000 + 3600) * 1000
        val expires = kotlin.time.Instant.fromEpochMilliseconds(expiry).toString()
        val members = mutableMapOf<String, String>()
        var activeLink: JsonObject? = null
        var linksCreated = 0
        val removedMembers = mutableListOf<String>()
        var linksRevoked = 0
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            when {
                path.endsWith("get_file_metadata") -> respond(dropboxPermissions)
                path.endsWith("get_metadata") -> { assertEquals("B", body["path"]!!.jsonPrimitive.content); respond(dropbox()) }
                path.endsWith("list_shared_links") -> respond(buildJsonObject {
                    putJsonArray("links") { activeLink?.let(::add) }; put("has_more", false)
                }.toString())
                path.endsWith("list_file_members") -> respond(buildJsonObject {
                    putJsonArray("users") {
                        members.forEach { (email, role) -> add(buildJsonObject {
                            putJsonObject("user") { put("account_id", email); put("email", email) }
                            putJsonObject("access_type") { put(".tag", role) }
                            putJsonArray("permissions") { add(buildJsonObject {
                                putJsonObject("action") { put(".tag", "remove") }; put("allow", true)
                            }) }
                        }) }
                    }
                    putJsonArray("groups") {}; putJsonArray("invitees") {}
                }.toString())
                path.endsWith("add_file_member") -> {
                    assertEquals("B", body["file"]!!.jsonPrimitive.content)
                    val email = body["members"]!!.jsonArray.single().jsonObject["email"]!!.jsonPrimitive.content
                    val requestedRole = body["access_level"]!!.jsonObject[".tag"]!!.jsonPrimitive.content
                    val role = if (email == "changed@example.test") "editor" else requestedRole
                    members[email] = role
                    respond(buildJsonArray { add(buildJsonObject {
                        putJsonObject("result") { put(".tag", "success"); putJsonObject("success") { put(".tag", role) } }
                    }) }.toString())
                }
                path.endsWith("remove_file_member_2") -> {
                    assertEquals("B", body["file"]!!.jsonPrimitive.content)
                    val email = body["member"]!!.jsonObject["email"]!!.jsonPrimitive.content
                    removedMembers += email; members.remove(email); respond("""{".tag":"success"}""")
                }
                path.endsWith("create_shared_link_with_settings") -> {
                    assertEquals("B", body["path"]!!.jsonPrimitive.content)
                    val settings = body["settings"]!!.jsonObject
                    assertEquals("viewer", settings["access"]!!.jsonObject[".tag"]!!.jsonPrimitive.content)
                    assertEquals("public", settings["audience"]!!.jsonObject[".tag"]!!.jsonPrimitive.content)
                    assertEquals(expires, settings["expires"]!!.jsonPrimitive.content)
                    linksCreated++
                    activeLink = buildJsonObject {
                        Json.parseToJsonElement(link).jsonObject.forEach { (key, value) -> put(key, value) }
                        put("url", "https://www.dropbox.com/s/test-" + linksCreated)
                        if (linksCreated == 1) put("expires", expires)
                    }
                    respond(checkNotNull(activeLink).toString())
                }
                path.endsWith("revoke_shared_link") -> {
                    assertEquals(activeLink?.get("url"), body["url"])
                    linksRevoked++; activeLink = null; respond("")
                }
                else -> error("Unexpected Dropbox sharing request")
            }
        }) { followRedirects = false }
        try {
            val provider = DropboxNetworkClient(config("Dropbox"), StorageHttpApi({ "token" }, http))
            val invitation = provider.createShare(target,
                StorageShareRequest(StorageShareKind.INVITATION, "editor", "person@example.test"), true).getOrThrow()
            assertEquals(StorageShareKind.INVITATION, invitation.kind)
            assertEquals("editor", invitation.role)
            assertEquals("person@example.test", invitation.recipient)
            assertNull(invitation.expiresAt)
            assertEquals("B", invitation.file.reference?.id)
            assertEquals(storageConnectionBinding(config("Dropbox"), "__root__"), invitation.connectionBinding)
            val expiring = StorageShareRequest(StorageShareKind.LINK, "viewer", expiresAt = expiry)
            val sharedLink = provider.createShare(target, expiring, true).getOrThrow()
            assertEquals("viewer", sharedLink.role)
            assertEquals(expiry, sharedLink.expiresAt)
            assertEquals("https://www.dropbox.com/s/test-1", sharedLink.url)
            provider.revokeShare(sharedLink).getOrThrow()
            assertTrue(provider.createShare(target, expiring, true).isFailure)
            assertEquals(2, linksRevoked)
            assertNull(activeLink)
            assertTrue(provider.createShare(target,
                StorageShareRequest(StorageShareKind.INVITATION, "viewer", "changed@example.test"), true).isFailure)
            assertEquals(listOf("changed@example.test"), removedMembers)
            assertEquals(mapOf("person@example.test" to "editor"), members)
        } finally { http.close() }
    }

    @Test
    fun deniedGoogleAndExpiredDropboxHistoryNeverDownloadsOrWritesAnyFile() = runSuspendTest {
        var historyReadable = false
        var revisionRequests = 0
        var downloads = 0
        var writes = 0
        val googleHttp = HttpClient(MockEngine { request ->
            when {
                request.method != HttpMethod.Get -> { writes++; error("Denied history must not change any file") }
                request.url.parameters["alt"] == "media" -> { downloads++; error("Denied history must not download") }
                request.url.encodedPath.contains("/revisions") -> {
                    revisionRequests++
                    assertTrue(request.url.encodedPath.contains("/B/revisions"))
                    respond("", HttpStatusCode.Forbidden)
                }
                request.url.encodedPath.endsWith("/root") -> respond(google("root", true))
                else -> {
                    assertTrue(request.url.encodedPath.endsWith("/B"))
                    respond(google(historyReadable = historyReadable))
                }
            }
        }) { followRedirects = false }
        try {
            val provider = GoogleDriveNetworkClient(config("GoogleDrive"), StorageHttpApi({ "token" }, googleHttp))
            val version = StorageVersion(target, "v1", connectionBinding = storageConnectionBinding(config("GoogleDrive"), "root"))
            val denied = listOf(
                provider.listVersions(target).exceptionOrNull(),
                provider.readVersion(version) { _, _ -> error("Denied history must not publish") }.exceptionOrNull(),
                provider.restoreVersion(version, true).exceptionOrNull(),
            )
            denied.forEach { assertEquals(StorageFeatureAvailability.PERMISSION_DENIED, assertIs<StorageFeatureException>(it).availability) }
            assertEquals(0, revisionRequests)

            historyReadable = true
            listOf(
                provider.listVersions(target).exceptionOrNull(),
                provider.readVersion(version) { _, _ -> error("Forbidden history must not publish") }.exceptionOrNull(),
                provider.restoreVersion(version, true).exceptionOrNull(),
            ).forEach { assertEquals(403, assertIs<StorageApiException>(it).status) }
            assertEquals(3, revisionRequests)
            assertEquals(0, downloads)
            assertEquals(0, writes)
        } finally { googleHttp.close() }

        var expiredLookups = 0
        val dropboxHttp = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("get_file_metadata") -> respond(dropboxPermissions)
                path.endsWith("get_metadata") -> {
                    val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    val selected = body["path"]!!.jsonPrimitive.content
                    if (selected.startsWith("rev:")) {
                        assertEquals("rev:expired", selected)
                        expiredLookups++
                        respond("""{"error":{".tag":"path","path":{".tag":"not_found"}}}""", HttpStatusCode.Conflict)
                    } else {
                        assertEquals("B", selected)
                        respond(dropbox())
                    }
                }
                path.endsWith("/download") -> { downloads++; error("Expired history must not download") }
                else -> { writes++; error("Expired history must not restore or alter another file") }
            }
        }) { followRedirects = false }
        try {
            val provider = DropboxNetworkClient(config("Dropbox"), StorageHttpApi({ "token" }, dropboxHttp))
            val version = StorageVersion(target, "expired",
                connectionBinding = storageConnectionBinding(config("Dropbox"), "__root__"))
            listOf(
                provider.readVersion(version) { _, _ -> error("Expired history must not publish") }.exceptionOrNull(),
                provider.restoreVersion(version, true).exceptionOrNull(),
            ).forEach { assertEquals(409, assertIs<StorageApiException>(it).status) }
            assertEquals(2, expiredLookups)
            assertEquals(0, downloads)
            assertEquals(0, writes)
        } finally { dropboxHttp.close() }
    }

    @Test
    fun creatingForExistingRecipientNeverChangesOrCompensatesTheExistingGrant() = runSuspendTest {
        var writes = 0
        val http = HttpClient(MockEngine { request ->
            val url = request.url.encodedPath
            when {
                request.method == HttpMethod.Delete || request.method == HttpMethod.Post &&
                    (url.endsWith("/permissions") || url.endsWith("add_file_member") || url.endsWith("remove_file_member_2")) -> {
                    writes++; error("Existing grant must not be modified")
                }
                url.endsWith("get_file_metadata") -> respond(dropboxPermissions)
                url.endsWith("get_metadata") -> respond(dropbox())
                url.endsWith("list_shared_links") -> respond("""{"links":[],"has_more":false}""")
                url.endsWith("list_file_members") -> respond("""{"users":[{"user":{"account_id":"existing","email":"user@example.test"},"access_type":{".tag":"viewer"},"permissions":[]}],"groups":[],"invitees":[]}""")
                url.endsWith("/permissions") -> respond("""{"permissions":[{"id":"existing","type":"user","role":"reader","emailAddress":"user@example.test"}]}""")
                url.endsWith("/root") -> respond(google("root", true))
                else -> respond(google())
            }
        }) { followRedirects = false }
        try {
            val google = GoogleDriveNetworkClient(config("GoogleDrive"), StorageHttpApi({ "token" }, http))
            val dropbox = DropboxNetworkClient(config("Dropbox"), StorageHttpApi({ "token" }, http))
            assertTrue(google.createShare(target, StorageShareRequest(StorageShareKind.INVITATION, "writer", "user@example.test"), true).isFailure)
            assertTrue(dropbox.createShare(target, StorageShareRequest(StorageShareKind.INVITATION, "editor", "user@example.test"), true).isFailure)
            assertEquals(0, writes)
        } finally { http.close() }
    }

    @Test
    fun versionAndShareRequireTheExactConnectionBeforeAnyNetworkRequest() = runSuspendTest {
        val http = HttpClient(MockEngine { error("Foreign connection DTO must be rejected before network access") })
        try {
            for (protocol in listOf("GoogleDrive", "Dropbox")) {
                val current = config(protocol)
                val provider: StorageProvider = if (protocol == "GoogleDrive") GoogleDriveNetworkClient(current, StorageHttpApi({ "token" }, http))
                    else DropboxNetworkClient(current, StorageHttpApi({ "token" }, http))
                val rootId = if (protocol == "GoogleDrive") "root" else "__root__"
                val foreign = storageConnectionBinding(current.copy(extras = current.extras.copy(
                    cloud = current.extras.cloud.copy(connectionId = "other-connection"))), rootId)
                for (binding in listOf("", foreign)) {
                    val version = StorageVersion(target, "v1", connectionBinding = binding)
                    assertIs<IllegalArgumentException>(provider.readVersion(version) { _, _ -> Result.success(Unit) }.exceptionOrNull())
                    assertIs<IllegalArgumentException>(provider.restoreVersion(version, true).exceptionOrNull())
                    val share = StorageShare(target, "grant", StorageShareKind.LINK, "reader", connectionBinding = binding)
                    assertIs<IllegalArgumentException>(provider.revokeShare(share).exceptionOrNull())
                }
            }
        } finally { http.close() }
    }

    @Test
    fun googleRevisionPagesAndExpiredVersionsKeepSelectedFileIdentity() = runSuspendTest {
        var pages = 0
        var writes = 0
        val http = HttpClient(MockEngine { request ->
            if (request.method != HttpMethod.Get) writes++
            when {
                request.url.encodedPath.endsWith("/revisions/gone") -> respond("", HttpStatusCode.NotFound)
                request.url.encodedPath.endsWith("/revisions") -> {
                    assertTrue(request.url.encodedPath.contains("/B/"))
                    pages++
                    if (pages == 1) respond("""{"revisions":[{"id":"v1","size":"7"}],"nextPageToken":"next"}""")
                    else {
                        assertEquals("next", request.url.parameters["pageToken"])
                        respond("""{"revisions":[{"id":"v2"}]}""")
                    }
                }
                request.url.encodedPath.endsWith("/root") -> respond(google("root", true))
                else -> respond(google())
            }
        }) { followRedirects = false }
        try {
            val provider = GoogleDriveNetworkClient(config("GoogleDrive"), StorageHttpApi({ "token" }, http))
            val first = provider.listVersions(target).getOrThrow()
            val second = provider.listVersions(target, first.nextCursor).getOrThrow()
            assertEquals(listOf("v1", "v2"), (first.entries + second.entries).map { it.id })
            assertTrue((first.entries + second.entries).all { it.file.reference?.id == "B" })
            assertTrue(provider.restoreVersion(first.entries.single(), false).isFailure)
            assertTrue(provider.readVersion(StorageVersion(target, "gone", connectionBinding = storageConnectionBinding(config("GoogleDrive"), "root"))) { _, _ -> error("Expired history must not publish") }.isFailure)
            assertEquals(0, writes)
        } finally { http.close() }
    }

    @Test
    fun googleConfirmedRestoreStreamsRetainedRevisionBackToSameId() = runSuspendTest {
        var revisionsRead = 0
        var writes = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.parameters["upload_id"] == "private" -> {
                    assertEquals("payload", request.body.toByteArray().decodeToString())
                    writes++; respond(google(checksum = true))
                }
                request.url.parameters["uploadType"] == "resumable" -> {
                    assertEquals(HttpMethod.Patch, request.method)
                    assertTrue(request.url.encodedPath.endsWith("/B"))
                    respond("", headers = headersOf(HttpHeaders.Location, "https://www.googleapis.com/upload/drive/v3/files/B?upload_id=private"))
                }
                request.url.encodedPath.endsWith("/revisions/v1") -> {
                    if (request.url.parameters["alt"] == "media") {
                        revisionsRead++; respond("payload", headers = headersOf(HttpHeaders.ContentLength, "7"))
                    } else respond("""{"id":"v1","size":"7","keepForever":true,"md5Checksum":"checksum"}""")
                }
                request.url.encodedPath.endsWith("/root") -> respond(google("root", true))
                else -> respond(google(checksum = true))
            }
        }) { followRedirects = false }
        try {
            val provider = GoogleDriveNetworkClient(config("GoogleDrive"), StorageHttpApi({ "token" }, http))
            val restored = provider.restoreVersion(StorageVersion(target, "v1", 7, connectionBinding = storageConnectionBinding(config("GoogleDrive"), "root")), true).getOrThrow()
            assertEquals("B", restored.reference?.id)
            assertEquals(1, revisionsRead); assertEquals(1, writes)
        } finally { http.close() }
    }

    @Test
    fun googleRejectsExpiringPublicLinksAndRevokesBroadenedServerResults() = runSuspendTest {
        var creates = 0
        var revokes = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/permissions") -> {
                    creates++
                    respond("""{"id":"grant","type":"anyone","role":"writer"}""")
                }
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/permissions") -> respond("{\"permissions\":[]}")
                request.method == HttpMethod.Delete -> { revokes++; respond("") }
                request.url.encodedPath.endsWith("/root") -> respond(google("root", true))
                else -> respond(google())
            }
        }) { followRedirects = false }
        try {
            val provider = GoogleDriveNetworkClient(config("GoogleDrive"), StorageHttpApi({ "token" }, http))
            val expiry = kotlin.time.Clock.System.now().toEpochMilliseconds() + 60_000
            assertTrue(provider.createShare(target, StorageShareRequest(StorageShareKind.LINK, "reader", expiresAt = expiry), true).isFailure)
            assertEquals(0, creates)
            assertTrue(provider.createShare(target, StorageShareRequest(StorageShareKind.LINK, "reader"), false).isFailure)
            assertEquals(0, creates)
            assertTrue(provider.createShare(target, StorageShareRequest(StorageShareKind.LINK, "reader"), true).isFailure)
            assertEquals(1, creates); assertEquals(1, revokes)
        } finally { http.close() }
    }

    @Test
    fun dropboxPagesByBeforeRevisionAndRestoresByIdWithConditionalCommit() = runSuspendTest {
        for (conflict in listOf(false, true)) {
            var pages = 0
            var commits = 0
            var writes = 0
            val http = HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("get_file_metadata") -> respond(dropboxPermissions)
                    path.endsWith("get_metadata") -> {
                        val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                        respond(dropbox(if (body["path"]?.jsonPrimitive?.content == "rev:v1") "v1" else "current"))
                    }
                    path.endsWith("list_revisions") -> {
                        pages++
                        val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                        assertEquals("id", body["mode"]!!.jsonObject[".tag"]!!.jsonPrimitive.content)
                        assertEquals("B", body["path"]!!.jsonPrimitive.content)
                        if (pages == 1) respond("""{"entries":[""" + dropbox("v1") + """],"has_more":true}""")
                        else {
                            assertEquals("v1", body["before_rev"]!!.jsonPrimitive.content)
                            respond("""{"entries":[""" + dropbox("v0") + """],"has_more":false}""")
                        }
                    }
                    path.endsWith("/download") -> {
                        val body = Json.parseToJsonElement(request.headers["Dropbox-API-Arg"]!!).jsonObject
                        assertEquals("rev:v1", body["path"]!!.jsonPrimitive.content)
                        respond("payload", headers = headersOf(HttpHeaders.ContentLength, "7"))
                    }
                    path.endsWith("upload_session/start") -> respond("""{"session_id":"restore-session"}""")
                    path.endsWith("upload_session/append_v2") -> {
                        assertEquals("payload", request.body.toByteArray().decodeToString())
                        respond("")
                    }
                    path.endsWith("upload_session/finish") -> {
                        commits++
                        val body = Json.parseToJsonElement(request.headers["Dropbox-API-Arg"]!!).jsonObject
                        val commit = body["commit"]!!.jsonObject
                        assertEquals("B", commit["path"]!!.jsonPrimitive.content)
                        assertEquals("update", commit["mode"]!!.jsonObject[".tag"]!!.jsonPrimitive.content)
                        assertEquals("current", commit["mode"]!!.jsonObject["update"]!!.jsonPrimitive.content)
                        assertFalse(commit["autorename"]!!.jsonPrimitive.boolean)
                        assertTrue(commit["strict_conflict"]!!.jsonPrimitive.boolean)
                        if (conflict) respond("{}", HttpStatusCode.Conflict)
                        else {
                            writes++
                            respond(JsonObject(Json.parseToJsonElement(dropbox("new-revision")).jsonObject.filterKeys { it != ".tag" }).toString())
                        }
                    }
                    else -> error("Unexpected Dropbox request")
                }
            }) { followRedirects = false }
            try {
                val provider = DropboxNetworkClient(config("Dropbox"), StorageHttpApi({ "token" }, http))
                val first = provider.listVersions(target).getOrThrow()
                val second = provider.listVersions(target, first.nextCursor).getOrThrow()
                assertEquals("v0", second.entries.single().id)
                assertTrue(provider.restoreVersion(first.entries.single(), false).isFailure)
                assertEquals(0, commits)
                val restored = provider.restoreVersion(first.entries.single(), true)
                if (conflict) {
                    assertIs<StorageApiException>(restored.exceptionOrNull())
                    assertEquals(0, writes)
                } else {
                    assertEquals("B", restored.getOrThrow().reference?.id)
                    assertEquals(1, writes)
                }
                assertEquals(1, commits)
            } finally { http.close() }
        }
    }

    @Test
    fun dropboxRefusesUnsupportedSettingsAndVerifiesOnlySelectedLinkRevocation() = runSuspendTest {
        var revoked = false
        var creates = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("get_file_metadata") -> respond(dropboxPermissions)
                request.url.encodedPath.endsWith("get_metadata") -> respond(dropbox())
                request.url.encodedPath.endsWith("create_shared_link_with_settings") -> { creates++; respond(link) }
                request.url.encodedPath.endsWith("list_shared_links") -> respond(
                    """{"links":[""" + (if (revoked) "" else link) + """],"has_more":false}""")
                request.url.encodedPath.endsWith("list_file_members") -> respond("""{"users":[],"groups":[],"invitees":[]}""")
                request.url.encodedPath.endsWith("revoke_shared_link") -> {
                    assertTrue(request.body.toByteArray().decodeToString().contains("https://www.dropbox.com/s/private"))
                    revoked = true; respond("")
                }
                else -> error("Unexpected Dropbox request")
            }
        }) { followRedirects = false }
        try {
            val provider = DropboxNetworkClient(config("Dropbox"), StorageHttpApi({ "token" }, http))
            assertTrue(provider.createShare(target, StorageShareRequest(StorageShareKind.LINK, "editor"), true).isFailure)
            assertTrue(provider.createShare(target, StorageShareRequest(StorageShareKind.INVITATION, "viewer", "user@example.test", 123), true).isFailure)
            assertEquals(0, creates)
            val share = provider.createShare(target, StorageShareRequest(StorageShareKind.LINK, "viewer"), true).getOrThrow()
            assertEquals("viewer", share.role)
            provider.revokeShare(share).getOrThrow()
            assertTrue(revoked); assertEquals(1, creates)
        } finally { http.close() }
    }
}

package com.folderspan.service.network

import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.test.runSuspendTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.*
import kotlin.test.*

class SelfHostedStorageAdvancedTest {
    private val repo = "00000000-0000-0000-0000-000000000001"
    private val commit = "a".repeat(40)
    private val content = "b".repeat(40)
    private val oldCommit = "c".repeat(40)
    private fun config(protocol: String, repoToken: Boolean = false) =
        NetworkConnectionConfig(protocol, "https://storage.example.test", "", "", extras = NetworkDriveExtras(
            cloud = CloudDriveExtras(accountId = "account", connectionId = "connection", libraryId = if (protocol == "Seafile") repo else "", seafileRepoToken = repoToken)))
    private fun nextcloud(config: NetworkConnectionConfig, http: HttpClient, write: Boolean = true) =
        NextcloudNetworkClient(config, { StorageCredential("account", StorageAccountKind.Nextcloud, "user", "Test", "user",
            "https://storage.example.test", "private-token", readOnly = !write) },
            { if (write) StorageCapabilities.ReadWrite else StorageCapabilities.ReadOnly }, http)
    private fun xml(id: String = "23", size: Int = 3, permissions: String = "RGW", href: String = "/remote.php/dav/files/user/file") =
        """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:response><d:href>$href</d:href>
            <d:propstat><d:prop><oc:fileid>$id</oc:fileid><oc:permissions>$permissions</oc:permissions>
            <d:getcontentlength>$size</d:getcontentlength><d:resourcetype/></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
    private fun ocs(data: String) = """{"ocs":{"meta":{"statuscode":200},"data":$data}}"""
    private fun share(permissions: Int = 1) =
        """{"id":"7","path":"/file","share_type":3,"permissions":$permissions,"uid_owner":"user","url":"https://storage.example.test/s/link"}"""

    @Test fun nextcloudVersionsUseNativeIdAndRestoreRequiresConfirmedConnection() = runSuspendTest {
        var moves = 0
        var downloaded = false
        val http = HttpClient(MockEngine { request ->
            assertTrue(request.headers[HttpHeaders.Authorization]?.startsWith("Basic ") == true)
            when {
                request.url.encodedPath.contains("/ocs/") -> respond(ocs("[]"))
                request.method == HttpMethod("MOVE") -> {
                    moves++
                    assertEquals("https://storage.example.test/remote.php/dav/versions/user/restore/23", request.headers["Destination"])
                    respond("", HttpStatusCode.Created)
                }
                request.url.encodedPath.endsWith("/versions/23/1700000000") && request.method == HttpMethod.Get -> {
                    downloaded = true; respond("old", headers = headersOf(HttpHeaders.ContentLength, "3"))
                }
                request.url.encodedPath.endsWith("/versions/23") && request.headers["Depth"] == "1" ->
                    respond(xml(href = "/remote.php/dav/versions/user/versions/23/1700000000"), HttpStatusCode.MultiStatus)
                else -> respond(xml(), HttpStatusCode.MultiStatus)
            }
        }) { followRedirects = false }
        try {
            val provider = nextcloud(config("Nextcloud"), http)
            val caps = provider.advancedCapabilities(StoragePath("/file")).getOrThrow()
            assertTrue(provider.capabilities.read && provider.capabilities.write && provider.capabilities.sync)
            assertEquals(StorageFeatureAvailability.UNSUPPORTED, caps.availability(StorageAdvancedFeature.RESUMABLE_UPLOAD))
            val versions = provider.listVersions(StoragePath("/file")).getOrThrow()
            assertNull(versions.nextCursor)
            val selected = versions.entries.single()
            assertEquals("23", selected.file.reference?.id)
            assertEquals(1700000000000, selected.updatedDate)
            assertTrue(provider.restoreVersion(selected, false).isFailure)
            assertTrue(provider.restoreVersion(selected.copy(connectionBinding = ""), true).isFailure)
            assertEquals(0, moves)
            provider.readVersion(selected) { bytes, _ -> assertEquals("old", bytes.decodeToString()); Result.success(Unit) }.getOrThrow()
            assertTrue(downloaded)
            assertEquals("23", provider.restoreVersion(selected, true).getOrThrow().reference?.id)
            assertEquals(1, moves)
            assertTrue(provider.listVersions(StoragePath("/file", StorageReference("wrong-id"))).isFailure)
        } finally { http.close() }
    }

    @Test fun nextcloudSeparatesDeniedMissingAndBroadenedSharing() = runSuspendTest {
        var denied = true
        var creates = 0
        var deletes = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.method == HttpMethod.Post -> { creates++; respond(ocs(share(3))) }
                request.method == HttpMethod.Delete -> { deletes++; respond(ocs("[]")) }
                request.url.encodedPath.contains("/ocs/") -> respond(ocs("[]"))
                request.url.encodedPath.contains("/versions/") ->
                    respond("", if (denied) HttpStatusCode.Forbidden else HttpStatusCode.NotFound)
                else -> respond(xml(permissions = "RG"), HttpStatusCode.MultiStatus)
            }
        }) { followRedirects = false }
        try {
            val provider = nextcloud(config("Nextcloud"), http)
            val target = StoragePath("/file")
            assertEquals(StorageFeatureAvailability.PERMISSION_DENIED, provider.advancedCapabilities(target).getOrThrow()
                .availability(StorageAdvancedFeature.LIST_VERSIONS))
            denied = false
            assertEquals(StorageFeatureAvailability.UNSUPPORTED, provider.advancedCapabilities(target).getOrThrow()
                .availability(StorageAdvancedFeature.LIST_VERSIONS))
            val request = StorageShareRequest(StorageShareKind.LINK, "reader")
            assertTrue(provider.createShare(target, request, false).isFailure)
            assertEquals(0, creates)
            assertTrue(provider.createShare(target, request, true).isFailure)
            assertEquals(1, creates); assertEquals(1, deletes)
        } finally { http.close() }
    }

    @Test fun nextcloudRevocationAcceptsSingleShareArraysAndRejectsAmbiguousOrForeignResults() = runSuspendTest {
        var response = share()
        var deletes = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.method == HttpMethod.Delete -> { deletes++; respond(ocs("[]")) }
                request.url.encodedPath.contains("/ocs/") -> respond(ocs(response))
                else -> respond(xml(), HttpStatusCode.MultiStatus)
            }
        }) { followRedirects = false }
        try {
            val config = config("Nextcloud")
            val provider = nextcloud(config, http)
            val selected = StorageShare(StoragePath("/file", StorageReference("23")), "7", StorageShareKind.LINK,
                "reader", connectionBinding = storageConnectionBinding(config, "/"))
            provider.revokeShare(selected).getOrThrow()
            response = "[" + share() + "]"
            provider.revokeShare(selected).getOrThrow()
            assertEquals(2, deletes)
            for (invalid in listOf("[]", "[" + share() + "," + share() + "]",
                "[" + share().replace("7", "8") + "]",
                "[" + share().replace("/file", "/other") + "]",
                "[" + share().replace("user", "other") + "]")) {
                response = invalid
                assertTrue(provider.revokeShare(selected).isFailure)
            }
            assertEquals(2, deletes)
        } finally { http.close() }
    }

    @Test fun nextcloudLinkExpiryUsesNativeDateAndCompensatesMissingOrLaterLimits() = runSuspendTest {
        val requested = kotlin.time.Instant.parse("2099-02-03T17:18:19Z").toEpochMilliseconds()
        val nativeDate = kotlin.time.Instant.parse("2099-02-02T23:59:59Z").toEpochMilliseconds()
        var expiration: String? = "2099-02-02 23:59:59"
        var creates = 0
        var revokes = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.method == HttpMethod.Post -> {
                    creates++
                    val form = parseQueryString(request.body.toByteArray().decodeToString())
                    assertEquals("2099-02-02", form["expireDate"])
                    assertEquals("3", form["shareType"])
                    assertEquals("1", form["permissions"])
                    val response = Json.parseToJsonElement(share()).jsonObject.toMutableMap()
                    expiration?.let { response["expiration"] = JsonPrimitive(it) }
                    respond(ocs(JsonObject(response).toString()))
                }
                request.method == HttpMethod.Delete -> {
                    assertTrue(request.url.encodedPath.endsWith("/shares/7"))
                    revokes++; respond(ocs("[]"))
                }
                request.url.encodedPath.endsWith("/cloud/capabilities") ->
                    respond(ocs("""{"capabilities":{"core":{"user":{"timezone":"UTC"}}}}"""))
                request.url.encodedPath.contains("/ocs/") -> respond(ocs("[]"))
                else -> respond(xml(), HttpStatusCode.MultiStatus)
            }
        }) { followRedirects = false }
        try {
            val provider = nextcloud(config("Nextcloud"), http)
            val selected = StoragePath("/file")
            val request = StorageShareRequest(StorageShareKind.LINK, "reader", expiresAt = requested)
            val share = provider.createShare(selected, request, true).getOrThrow()
            assertEquals(nativeDate, share.expiresAt)
            assertTrue(share.expiresAt!! <= requested)
            assertEquals(1, creates); assertEquals(0, revokes)
            for (invalidExpiration in listOf(null, "2099-02-03 23:59:59")) {
                expiration = invalidExpiration
                assertTrue(provider.createShare(selected, request, true).isFailure)
            }
            assertEquals(3, creates); assertEquals(2, revokes)
            assertTrue(provider.createShare(selected,
                StorageShareRequest(StorageShareKind.INVITATION, "reader", "native-user", requested), true).isFailure)
            assertEquals(3, creates); assertEquals(2, revokes)
        } finally { http.close() }
    }

    @Test fun nextcloudNativeDateBoundariesRespectTimezoneAndRejectUnrepresentableDeadlines() {
        fun millis(value: String) = kotlin.time.Instant.parse(value).toEpochMilliseconds()
        val now = millis("2099-02-03T17:18:19Z")
        assertEquals("2099-02-03", nextcloudExpiryDate(millis("2099-02-03T23:59:59Z"), TimeZone.UTC, now))
        assertEquals("2099-02-02", nextcloudExpiryDate(millis("2099-02-03T23:59:58.999Z"), TimeZone.UTC,
            millis("2099-02-01T00:00:00Z")))
        assertEquals("2099-02-03", nextcloudExpiryDate(millis("2099-02-03T17:18:19Z"),
            TimeZone.of("Pacific/Kiritimati"), millis("2099-02-01T00:00:00Z")))
        assertFailsWith<IllegalArgumentException> { nextcloudExpiryDate(now + 60_000, TimeZone.UTC, now) }
        assertFailsWith<IllegalArgumentException> { nextcloudExpiryDate(now, TimeZone.UTC, now) }
    }

    @Test fun nextcloudExpiryUsesConfirmedNonUtcTimezoneAndRefusesUnknownTimezoneBeforeCreation() = runSuspendTest {
        val requested = kotlin.time.Instant.parse("2099-02-03T17:18:19Z").toEpochMilliseconds()
        val expected = kotlin.time.Instant.parse("2099-02-03T09:59:59Z").toEpochMilliseconds()
        var timezone: String? = "Pacific/Kiritimati"
        var creates = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.method == HttpMethod.Post -> {
                    creates++
                    val form = parseQueryString(request.body.toByteArray().decodeToString())
                    assertEquals("2099-02-03", form["expireDate"])
                    val response = Json.parseToJsonElement(share()).jsonObject.toMutableMap()
                    response["expiration"] = JsonPrimitive("2099-02-03 23:59:59")
                    respond(ocs(JsonObject(response).toString()))
                }
                request.url.encodedPath.endsWith("/cloud/capabilities") -> {
                    val user = buildJsonObject { timezone?.let { put("timezone", it) } }
                    respond(ocs(buildJsonObject { putJsonObject("capabilities") {
                        putJsonObject("core") { put("user", user) }
                    } }.toString()))
                }
                request.url.encodedPath.contains("/ocs/") -> respond(ocs("[]"))
                else -> respond(xml(), HttpStatusCode.MultiStatus)
            }
        }) { followRedirects = false }
        try {
            val provider = nextcloud(config("Nextcloud"), http)
            val request = StorageShareRequest(StorageShareKind.LINK, "reader", expiresAt = requested)
            assertEquals(expected, provider.createShare(StoragePath("/file"), request, true).getOrThrow().expiresAt)
            for (invalidTimezone in listOf(null, "Unknown/Timezone")) {
                timezone = invalidTimezone
                assertTrue(provider.createShare(StoragePath("/file"), request, true).isFailure)
            }
            timezone = "UTC"
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            // If less than a day remains, this UTC deadline has no safely representable future whole-day limit.
            val nearDeadline = now + 1
            assertTrue(provider.createShare(StoragePath("/file"),
                StorageShareRequest(StorageShareKind.LINK, "reader", expiresAt = nearDeadline), true).isFailure)
            assertEquals(1, creates)
        } finally { http.close() }
    }

    @Test fun nextcloudShareOperationsResolveTimezoneOnceWithoutCrossOperationCaching() = runSuspendTest {
        var timezone = "UTC"
        var capabilityRequests = 0
        val expiration = "2099-02-02 23:59:59"
        fun expiredShare(id: String = "7") = JsonObject(Json.parseToJsonElement(share()).jsonObject.toMutableMap().apply {
            put("id", JsonPrimitive(id))
            put("expiration", JsonPrimitive(expiration))
        }).toString()
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/cloud/capabilities") -> {
                    capabilityRequests++
                    respond(ocs("""{"capabilities":{"core":{"user":{"timezone":"$timezone"}}}}"""))
                }
                request.method == HttpMethod.Post -> respond(ocs(expiredShare()))
                request.method == HttpMethod.Delete -> respond(ocs("[]"))
                request.url.encodedPath.contains("/ocs/") -> respond(ocs("[" + expiredShare() + "," + expiredShare("8") + "]"))
                else -> respond(xml(), HttpStatusCode.MultiStatus)
            }
        }) { followRedirects = false }
        try {
            val provider = nextcloud(config("Nextcloud"), http)
            val selected = StoragePath("/file")
            val first = provider.listShares(selected).getOrThrow()
            assertEquals(2, first.entries.size)
            assertEquals(1, capabilityRequests)
            assertEquals(kotlin.time.Instant.parse("2099-02-02T23:59:59Z").toEpochMilliseconds(), first.entries.first().expiresAt)
            timezone = "Pacific/Kiritimati"
            val second = provider.listShares(selected).getOrThrow()
            assertEquals(2, capabilityRequests)
            assertEquals(kotlin.time.Instant.parse("2099-02-02T09:59:59Z").toEpochMilliseconds(), second.entries.first().expiresAt)
            provider.createShare(selected, StorageShareRequest(StorageShareKind.LINK, "reader",
                expiresAt = kotlin.time.Instant.parse("2099-02-03T17:18:19Z").toEpochMilliseconds()), true).getOrThrow()
            // Preparing the native date and reading the returned grant share the same operation-scoped lookup.
            assertEquals(3, capabilityRequests)
        } finally { http.close() }
    }

    private fun history(path: String = "/file", next: String = "false", renamed: String = "") =
        """{"data":[{"commit_id":"$commit","rev_file_id":"$content","path":"$path","rev_renamed_old_path":"$renamed","size":3,"ctime":"2024-01-01T00:00:00Z"}],"next_start_commit":$next}"""
    private fun link(edit: Boolean = false) =
        """{"repo_id":"$repo","path":"/file","token":"share-token","link":"https://storage.example.test/f/link","permissions":{"can_download":true,"can_edit":$edit,"can_upload":false},"expire_date":""}"""

    @Test fun seafilePagesHistoryAcrossRenameAndRestoresOnlyCurrentPath() = runSuspendTest {
        var restores = 0
        var oldPage = false
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/history/") -> {
                    if (request.url.parameters["commit_id"] == oldCommit) {
                        oldPage = true
                        assertEquals("/old", request.url.parameters["path"])
                        respond(history("/old"))
                    } else respond(history(next = "\"$oldCommit\"", renamed = "/old"))
                }
                request.url.encodedPath.endsWith("/revision/") -> respond("\"https://storage.example.test:8082/download/revision\"")
                request.url.encodedPath.endsWith("/download/revision") -> {
                    assertNull(request.headers[HttpHeaders.Authorization]); respond("old", headers = headersOf(HttpHeaders.ContentLength, "3"))
                }
                request.url.encodedPath.endsWith("/detail/") -> respond("""{"id":"$content","size":3}""")
                request.method == HttpMethod.Post -> {
                    restores++
                    val body = request.body.toByteArray().decodeToString()
                    assertTrue(body.contains("operation=revert") && body.contains(commit))
                    respond("""{"success":true}""")
                }
                request.url.encodedPath.endsWith("/dir/") -> respond("""[{"type":"file","name":"file","size":3}]""")
                request.url.encodedPath.endsWith("/share-links/") -> respond("[]")
                else -> respond("""{"permission":"rw"}""")
            }
        }) { followRedirects = false }
        try {
            val provider = SeafileNetworkClient(config("Seafile"), StorageHttpApi({ "token" }, http))
            val path = StoragePath("/file")
            val caps = provider.advancedCapabilities(path).getOrThrow()
            assertTrue(provider.capabilities.read && provider.capabilities.write && provider.capabilities.sync)
            assertEquals(StorageFeatureAvailability.UNSUPPORTED, caps.availability(StorageAdvancedFeature.RESUMABLE_UPLOAD))
            assertEquals(StorageFeatureAvailability.UNSUPPORTED, caps.availability(StorageAdvancedFeature.INVITE_USER))
            val first = provider.listVersions(path).getOrThrow()
            val second = provider.listVersions(path, first.nextCursor).getOrThrow()
            assertTrue(oldPage); assertNull(second.nextCursor)
            assertTrue(provider.restoreVersion(second.entries.single(), true).isFailure)
            assertTrue(provider.restoreVersion(first.entries.single().copy(connectionBinding = "foreign"), true).isFailure)
            assertTrue(provider.readVersion(first.entries.single().copy(file = first.entries.single().file.copy(
                reference = first.entries.single().file.reference!!.copy(ancestorPath = "/unrelated")))) { _, _ ->
                error("A revision outside the selected file history must not be consumed")
            }.isFailure)
            assertEquals(0, restores)
            var historical = ""
            provider.readVersion(second.entries.single()) { bytes, _ -> historical += bytes.decodeToString(); Result.success(Unit) }.getOrThrow()
            assertEquals("old", historical)
            provider.restoreVersion(first.entries.single(), true).getOrThrow()
            assertEquals(1, restores)
        } finally { http.close() }
    }

    @Test fun seafileDirectoryInvitationPreservesNativeRecipientIdentity() = runSuspendTest {
        val recipient = "native-user@auth.local"
        val contact = "contact@example.test"
        val permission = "r"
        var deletes = 0
        var granted = false
        val native = """{"share_type":"user","user_info":{"name":"$recipient","contact_email":"$contact"},"permission":"$permission","is_admin":false}"""
        val http = HttpClient(MockEngine { request ->
            when {
                request.method == HttpMethod.Put -> {
                    val form = parseQueryString(request.body.toByteArray().decodeToString())
                    assertEquals(recipient, form["username"])
                    assertEquals("r", form["permission"])
                    granted = true
                    respond("""{"failed":[],"success":[$native]}""")
                }
                request.method == HttpMethod.Delete -> {
                    assertEquals(recipient, request.url.parameters["username"])
                    deletes++; respond("")
                }
                request.url.encodedPath.endsWith("/shared_items/") -> respond(if (granted) "[" + native + "]" else "[]")
                request.url.encodedPath.endsWith("/dir/") -> respond("""[{"type":"dir","name":"folder"}]""")
                request.url.encodedPath.endsWith("/share-links/") -> respond("[]")
                else -> respond("""{"permission":"rw"}""")
            }
        }) { followRedirects = false }
        try {
            val provider = SeafileNetworkClient(config("Seafile"), StorageHttpApi({ "token" }, http))
            val selected = provider.createShare(StoragePath("/folder"),
                StorageShareRequest(StorageShareKind.INVITATION, "reader", recipient), true).getOrThrow()
            assertEquals(recipient, selected.recipient)
            assertNotEquals(contact, selected.recipient)
            assertEquals("reader", selected.role)
            provider.revokeShare(selected).getOrThrow()
            assertEquals(1, deletes)
        } finally { http.close() }
    }

    @Test fun seafileLinkExpiryIsConfirmedAndMissingOrLaterLimitsAreRevoked() = runSuspendTest {
        val expirationTime = "2099-02-03T17:18:19Z"
        val requested = kotlin.time.Instant.parse(expirationTime).toEpochMilliseconds()
        var expiration: String? = expirationTime
        var creates = 0
        var revokes = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.method == HttpMethod.Post -> {
                    creates++
                    val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    assertEquals(expirationTime, body.getValue("expiration_time").jsonPrimitive.content)
                    assertFalse(body.containsKey("expire_days"))
                    assertEquals(repo, body.getValue("repo_id").jsonPrimitive.content)
                    assertEquals("/file", body.getValue("path").jsonPrimitive.content)
                    val response = Json.parseToJsonElement(link()).jsonObject.toMutableMap()
                    response["expire_date"] = expiration?.let(::JsonPrimitive) ?: JsonNull
                    respond(JsonObject(response).toString())
                }
                request.method == HttpMethod.Put -> error("Unsupported invitation expiry must not create a grant")
                request.method == HttpMethod.Delete -> {
                    assertTrue(request.url.encodedPath.endsWith("/share-links/share-token/"))
                    revokes++; respond("")
                }
                request.url.encodedPath.endsWith("/dir/") ->
                    respond("""[{"type":"file","name":"file","size":3},{"type":"dir","name":"folder"}]""")
                request.url.encodedPath.endsWith("/history/") -> respond(history())
                request.url.encodedPath.endsWith("/share-links/") || request.url.encodedPath.endsWith("/shared_items/") -> respond("[]")
                else -> respond("""{"permission":"rw"}""")
            }
        }) { followRedirects = false }
        try {
            val provider = SeafileNetworkClient(config("Seafile"), StorageHttpApi({ "token" }, http))
            val path = StoragePath("/file")
            val request = StorageShareRequest(StorageShareKind.LINK, "reader", expiresAt = requested)
            assertEquals(requested, provider.createShare(path, request, true).getOrThrow().expiresAt)
            assertEquals(1, creates); assertEquals(0, revokes)
            for (invalidExpiration in listOf(null, "2099-02-03T17:18:20Z")) {
                expiration = invalidExpiration
                assertTrue(provider.createShare(path, request, true).isFailure)
            }
            assertEquals(3, creates); assertEquals(2, revokes)
            assertTrue(provider.createShare(StoragePath("/folder"),
                StorageShareRequest(StorageShareKind.INVITATION, "reader", "native-user@auth.local", requested), true).isFailure)
            assertEquals(3, creates); assertEquals(2, revokes)
        } finally { http.close() }
    }

    @Test fun nextcloudCancellationDuringGrantValidationStillRevokesOnlyCreatedGrant() = runSuspendTest {
        val lookupStarted = CompletableDeferred<Unit>()
        var deletes = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.method == HttpMethod.Post -> {
                    val created = Json.parseToJsonElement(share()).jsonObject.toMutableMap()
                    created["expiration"] = JsonPrimitive("2099-02-02 23:59:59")
                    respond(ocs(JsonObject(created).toString()))
                }
                request.method == HttpMethod.Delete -> {
                    assertTrue(request.url.encodedPath.endsWith("/shares/7"))
                    deletes++; respond(ocs("[]"))
                }
                request.url.encodedPath.endsWith("/cloud/capabilities") -> {
                    lookupStarted.complete(Unit)
                    awaitCancellation()
                }
                request.url.encodedPath.contains("/ocs/") -> respond(ocs("[" + share().replace("7", "existing") + "]"))
                else -> respond(xml(), HttpStatusCode.MultiStatus)
            }
        }) { followRedirects = false }
        try {
            val provider = nextcloud(config("Nextcloud"), http)
            coroutineScope {
                val creating = async {
                    provider.createShare(StoragePath("/file"), StorageShareRequest(StorageShareKind.LINK, "reader"), true)
                }
                withTimeout(5_000) { lookupStarted.await() }
                creating.cancel(CancellationException("User cancelled"))
                assertFailsWith<CancellationException> { creating.await() }
            }
            assertEquals(1, deletes)
        } finally { http.close() }
    }

    @Test fun seafileCancellationDuringCompensationKeepsNewLinkAndInvitationCleanupRunning() = runSuspendTest {
        for (kind in StorageShareKind.entries) {
            val cleanupStarted = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            var deletes = 0
            val recipient = "native-user@auth.local"
            val http = HttpClient(MockEngine { request ->
                when {
                    request.method == HttpMethod.Post -> respond(link(edit = true))
                    request.method == HttpMethod.Put -> respond(
                        """{"failed":[],"success":[{"share_type":"user","user_info":{"name":"$recipient"},"permission":"rw","is_admin":false}]}""")
                    request.method == HttpMethod.Delete -> {
                        if (kind == StorageShareKind.LINK) assertTrue(request.url.encodedPath.endsWith("/share-links/share-token/"))
                        else assertEquals(recipient, request.url.parameters["username"])
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                        deletes++; respond("")
                    }
                    request.url.encodedPath.endsWith("/dir/") -> respond(
                        """[{"type":"file","name":"file","size":3},{"type":"dir","name":"folder"}]""")
                    request.url.encodedPath.endsWith("/history/") -> respond(history())
                    request.url.encodedPath.endsWith("/share-links/") || request.url.encodedPath.endsWith("/shared_items/") -> respond("[]")
                    else -> respond("""{"permission":"rw"}""")
                }
            }) { followRedirects = false }
            try {
                val provider = SeafileNetworkClient(config("Seafile"), StorageHttpApi({ "token" }, http))
                coroutineScope {
                    val creating = async {
                        provider.createShare(StoragePath(if (kind == StorageShareKind.LINK) "/file" else "/folder"),
                            StorageShareRequest(kind, "reader", if (kind == StorageShareKind.INVITATION) recipient else null), true)
                    }
                    withTimeout(5_000) { cleanupStarted.await() }
                    creating.cancel(CancellationException("User cancelled"))
                    releaseCleanup.complete(Unit)
                    assertFailsWith<CancellationException> { creating.await() }
                }
                assertEquals(1, deletes, kind.toString())
            } finally { http.close() }
        }
    }

    @Test fun seafileExistingInvitationsAndUnidentifiedResultsNeverRevokeOtherGrants() = runSuspendTest {
        val recipient = "native-user@auth.local"
        var existing = true
        var creates = 0
        var deletes = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.method == HttpMethod.Put -> {
                    creates++
                    respond("""{"failed":[{"email":"$recipient","error_msg":"Already shared"}],"success":[]}""")
                }
                request.method == HttpMethod.Post -> {
                    creates++
                    respond("""{"repo_id":"$repo","path":"/file"}""") // Unknown new-link identity.
                }
                request.method == HttpMethod.Delete -> { deletes++; respond("") }
                request.url.encodedPath.endsWith("/shared_items/") -> respond(if (existing)
                    """[{"share_type":"user","user_info":{"name":"$recipient"},"permission":"r","is_admin":false}]""" else "[]")
                request.url.encodedPath.endsWith("/dir/") -> respond(
                    """[{"type":"file","name":"file","size":3},{"type":"dir","name":"folder"}]""")
                request.url.encodedPath.endsWith("/history/") -> respond(history())
                request.url.encodedPath.endsWith("/share-links/") -> respond("[]")
                else -> respond("""{"permission":"rw"}""")
            }
        }) { followRedirects = false }
        try {
            val provider = SeafileNetworkClient(config("Seafile"), StorageHttpApi({ "token" }, http))
            val invitation = StorageShareRequest(StorageShareKind.INVITATION, "reader", recipient)
            assertTrue(provider.createShare(StoragePath("/folder"), invitation, true).isFailure)
            assertEquals(0, creates)
            existing = false
            assertTrue(provider.createShare(StoragePath("/folder"), invitation, true).isFailure)
            assertEquals(1, creates)
            assertTrue(provider.createShare(StoragePath("/file"), StorageShareRequest(StorageShareKind.LINK, "reader"), true).isFailure)
            assertEquals(2, creates)
            assertEquals(0, deletes)
        } finally { http.close() }
    }

    @Test fun seafileRevokesBroadenedLinksAndRejectsCrossConnectionDto() = runSuspendTest {
        var creates = 0
        var deletes = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.method == HttpMethod.Post -> { creates++; respond(link(edit = true)) }
                request.method == HttpMethod.Delete -> { deletes++; respond("") }
                request.url.encodedPath.endsWith("/dir/") -> respond("""[{"type":"file","name":"file","size":3}]""")
                request.url.encodedPath.endsWith("/history/") -> respond(history())
                request.url.encodedPath.endsWith("/share-links/") -> respond("[]")
                else -> respond("""{"permission":"rw"}""")
            }
        }) { followRedirects = false }
        try {
            val provider = SeafileNetworkClient(config("Seafile"), StorageHttpApi({ "token" }, http))
            val path = StoragePath("/file")
            assertTrue(provider.createShare(path, StorageShareRequest(StorageShareKind.LINK, "writer"), true).isFailure)
            assertEquals(0, creates)
            assertTrue(provider.createShare(path, StorageShareRequest(StorageShareKind.LINK, "reader"), true).isFailure)
            assertEquals(1, creates); assertEquals(1, deletes)
            assertTrue(provider.revokeShare(StorageShare(path, "token", StorageShareKind.LINK, "reader", connectionBinding = "foreign")).isFailure)
            assertEquals(1, deletes)
            val repositoryToken = SeafileNetworkClient(config("Seafile", repoToken = true), StorageHttpApi({ "token" }, http))
            assertTrue(repositoryToken.capabilities.read && repositoryToken.capabilities.write && repositoryToken.capabilities.sync)
            assertEquals(StorageFeatureAvailability.UNSUPPORTED, repositoryToken.advancedCapabilities(path).getOrThrow()
                .availability(StorageAdvancedFeature.RESUMABLE_UPLOAD))
            assertTrue(repositoryToken.listVersions(path).isFailure)
        } finally { http.close() }
    }
}

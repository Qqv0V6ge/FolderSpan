package com.folderspan.service.network

import com.folderspan.test.runSuspendTest
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import korlibs.crypto.sha256
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class StorageAuthorizationManagerTest {
    private fun store() = StorageAccountStore(MapSettings(), { error("Unexpected refresh") }, now = { 1000L })

    @Test
    fun officialAuthorizationWithoutAnInstalledBrokerDoesNotSendARequest() = runSuspendTest {
        var requests = 0
        val http = HttpClient(MockEngine {
            requests++
            respondError(HttpStatusCode.ServiceUnavailable)
        })
        try {
            val manager = StorageAuthorizationManager(http, store())
            for (protocol in listOf("GoogleDrive", "Dropbox")) {
                assertFailsWith<IllegalStateException> { manager.begin(StorageAuthorizationRequest(protocol)) }
            }
            assertEquals(0, requests)
        } finally {
            http.close()
        }
    }

    @Test
    fun brokerProofRemainsInRuntimeAndSuccessfulResultContainsOnlyAccountMetadata() = runSuspendTest {
        var challenge = ""
        var polls = 0
        val http = HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            when {
                request.url.encodedPath.endsWith("/start") -> {
                    challenge = body.storageString("challenge")
                    assertEquals("google", body.storageString("provider"))
                    assertFalse(body.containsKey("permissions"))
                    respond("""{"code":0,"data":{"ticket":"private-ticket","loginUrl":"https://broker.test/api/v1/storage/oauth/authorize?ticket=private-ticket","userCode":"123456","expiresAt":200000}}""")
                }
                request.url.encodedPath.endsWith("/poll") -> {
                    polls++
                    val verifier = body.storageString("verifier")
                    assertEquals(challenge, Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(verifier.encodeToByteArray().sha256().bytes))
                    assertEquals("private-ticket", body.storageString("ticket"))
                    respond("""{"code":0,"data":{"status":"authorized","grant":"private-broker-grant","label":"Alice","accessToken":"private-access-token","expiresAt":200000,"providerAccountId":"google-alice"}}""")
                }
                else -> error("Unexpected broker operation")
            }
        }) { followRedirects = false }
        try {
            val store = store()
            val manager = StorageAuthorizationManager(http, store, "https://broker.test/api/v1/storage/oauth", now = { 1000L })
            val start = manager.begin(StorageAuthorizationRequest("GoogleDrive"))
            assertFalse(start.handle.contains("private-ticket"))
            val result = manager.poll(start.handle)
            assertEquals("authorized", result.status)
            val serialized = Json.encodeToString(result)
            assertFalse(serialized.contains("private-")); assertFalse(serialized.contains("accessToken")); assertFalse(serialized.contains("grant"))
            assertEquals("Alice", store.accounts().single().label)
            assertFailsWith<IllegalStateException> { manager.poll(start.handle) }
            assertEquals(1, polls)
        } finally { http.close() }
    }

    @Test
    fun nextcloudUsesOneTimePasswordAndResolvesUserIdWithoutSendingItToAnotherOrigin() = runSuspendTest {
        var polls = 0
        val http = HttpClient(MockEngine { request ->
            assertEquals("cloud.test", request.url.host)
            when {
                request.url.encodedPath.endsWith("/login/v2") -> respond("""{"login":"https://cloud.test/nc/login/v2/flow/temporary","poll":{"endpoint":"https://cloud.test/nc/login/v2/poll","token":"private-poll-token"}}""")
                request.url.encodedPath.endsWith("/poll") -> {
                    polls++
                    if (polls == 1) respond("", HttpStatusCode.NotFound)
                    else respond("""{"server":"https://cloud.test/nc","loginName":"alice@example.test","appPassword":"private-app-password"}""")
                }
                request.url.encodedPath.endsWith("/cloud/user") -> {
                    assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().startsWith("Basic "))
                    respond("""{"ocs":{"data":{"id":"alice-uid","display-name":"Alice"}}}""")
                }
                else -> error("Unexpected Nextcloud operation")
            }
        }) { followRedirects = false }
        try {
            val store = store()
            val manager = StorageAuthorizationManager(http, store, "https://broker.test/api/v1/storage/oauth", now = { 1000L })
            val start = manager.begin(StorageAuthorizationRequest("Nextcloud", "https://cloud.test/nc"))
            assertEquals("pending", manager.poll(start.handle).status)
            val result = manager.poll(start.handle)
            assertEquals("https://cloud.test/nc", result.account?.server)
            val config = NetworkConnectionConfig("Nextcloud", "https://cloud.test/nc", "", "",
                extras = com.folderspan.data.main.network.NetworkDriveExtras(cloud = com.folderspan.data.main.network.CloudDriveExtras(accountId = result.account!!.id)))
            assertEquals("alice-uid", store.credential(config).providerAccountId)
            assertEquals("private-app-password", store.credential(config).accessToken)
            assertFalse(Json.encodeToString(result).contains("private-"))
        } finally { http.close() }
    }

    @Test
    fun authorizationRejectsAnotherInstanceAndCancellationCannotBePolledAgain() = runSuspendTest {
        var cancelled = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/login/v2") -> respond("""{"login":"https://evil.test/login","poll":{"endpoint":"https://cloud.test/poll","token":"private-poll"}}""")
                request.url.encodedPath.endsWith("/start") -> respond("""{"code":0,"data":{"ticket":"private-ticket","loginUrl":"https://broker.test/api/v1/storage/oauth/authorize","userCode":"123456","expiresAt":200000}}""")
                request.url.encodedPath.endsWith("/cancel") -> { cancelled++; respond("""{"code":0,"data":{"status":"cancelled"}}""") }
                else -> error("Unexpected request")
            }
        }) { followRedirects = false }
        try {
            val manager = StorageAuthorizationManager(http, store(), "https://broker.test/api/v1/storage/oauth", now = { 1000L })
            assertFailsWith<IllegalArgumentException> { manager.begin(StorageAuthorizationRequest("Nextcloud", "https://cloud.test")) }
            val start = manager.begin(StorageAuthorizationRequest("GoogleDrive"))
            manager.cancel(start.handle)
            manager.cancel(start.handle)
            assertFailsWith<IllegalStateException> { manager.poll(start.handle) }
            assertEquals(1, cancelled)
        } finally { http.close() }
    }

    @Test
    fun seafileRepoTokenIsBoundToItsLibraryAndNeverReturnedToBrowser() = runSuspendTest {
        val library = "00000000-0000-0000-0000-000000000001"
        val http = HttpClient(MockEngine { request ->
            assertEquals("Bearer private-repo-token", request.headers[HttpHeaders.Authorization])
            if (request.url.encodedPath.endsWith("/dir/")) respond("""{"user_perm":"rw","dirent_list":[]}""")
            else {
                assertEquals("/api/v2.1/via-repo-token/repo-info/", request.url.encodedPath)
                respond("""{"repo_id":"""" + library + """","repo_name":"Documents"}""")
            }
        }) { followRedirects = false }
        try {
            val store = store()
            val manager = StorageAuthorizationManager(http, store, "https://broker.test/api/v1/storage/oauth")
            assertFailsWith<IllegalArgumentException> { manager.installSeafile(SeafileAuthorizationRequest("https://cloud.test", "private-repo-token", true, "other-library")) }
            assertTrue(store.accounts().isEmpty())
            val account = manager.installSeafile(SeafileAuthorizationRequest("https://cloud.test", "private-repo-token", true))
            assertEquals(library, account.libraryId)
            assertFalse(Json.encodeToString(account).contains("private-repo-token"))
            assertEquals(library, manager.libraries(account.id).single().id)
        } finally { http.close() }
    }
    @Test
    fun sharingReauthorizationRequiresBrokerAcknowledgementAndActualProviderScopes() = runSuspendTest {
        val accountId = "existingaccount0001"
        for (protocol in listOf("GoogleDrive", "Dropbox")) {
            val kind = storageAccountKind(protocol)
            val required = if (protocol == "GoogleDrive") setOf("https://www.googleapis.com/auth/drive")
                else setOf("sharing.read", "sharing.write")
            for (mode in listOf("legacy-broker", "missing-scopes", "partial-scopes", "other-account", "accepted")) {
                val store = store()
                store.install(StorageCredential(accountId, kind, "original-user", "Original", accessToken = "original-access"))
                var cancels = 0
                var revokes = 0
                val http = HttpClient(MockEngine { request ->
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    when {
                        request.url.encodedPath.endsWith("/start") -> {
                            assertEquals(JsonArray(listOf(JsonPrimitive("SHARING"))), body["permissions"])
                            respond(buildJsonObject {
                                put("code", 0)
                                putJsonObject("data") {
                                    put("ticket", "private-ticket"); put("userCode", "123456"); put("expiresAt", 200000)
                                    put("loginUrl", "https://broker.test/api/v1/storage/oauth/authorize")
                                    if (mode != "legacy-broker") putJsonArray("acceptedPermissions") { add("SHARING") }
                                }
                            }.toString())
                        }
                        request.url.encodedPath.endsWith("/poll") -> respond(buildJsonObject {
                            put("code", 0)
                            putJsonObject("data") {
                                put("status", "authorized"); put("grant", "private-new-grant")
                                put("label", "New"); put("accessToken", "private-new-access"); put("expiresAt", 200000)
                                put("providerAccountId", if (mode == "other-account") "other-user" else "original-user")
                                if (mode != "missing-scopes") putJsonArray("grantedScopes") {
                                    if (mode == "partial-scopes") add(if (protocol == "GoogleDrive")
                                        "https://www.googleapis.com/auth/drive.readonly" else "sharing.read")
                                    else required.forEach(::add)
                                }
                            }
                        }.toString())
                        request.url.encodedPath.endsWith("/cancel") -> {
                            cancels++; respond("""{"code":0,"data":{"status":"cancelled"}}""")
                        }
                        request.url.encodedPath.endsWith("/revoke") -> {
                            assertEquals("private-new-grant", body.storageString("grant"))
                            assertEquals(protocol == "GoogleDrive", (body["forgetOnly"] as? JsonPrimitive)?.booleanOrNull == true)
                            revokes++; respond("""{"code":0,"data":{"revoked":true}}""")
                        }
                        else -> error("Unexpected authorization request")
                    }
                }) { followRedirects = false }
                try {
                    val manager = StorageAuthorizationManager(http, store, "https://broker.test/api/v1/storage/oauth", now = { 1000L })
                    val request = StorageAuthorizationRequest(protocol, replaceAccountId = accountId,
                        permissions = setOf(StorageAuthorizationPermission.SHARING))
                    if (mode == "legacy-broker") {
                        assertFailsWith<IllegalStateException> { manager.begin(request) }
                        assertEquals(1, cancels)
                    } else {
                        val started = manager.begin(request)
                        assertEquals(setOf(StorageAuthorizationPermission.SHARING), started.acceptedPermissions)
                        if (mode == "accepted") {
                            val result = manager.poll(started.handle)
                            assertEquals(accountId, result.account?.id)
                            assertFalse(Json.encodeToString(result).contains("private-"))
                        } else {
                            assertFailsWith<IllegalStateException> { manager.poll(started.handle) }
                            assertEquals(1, revokes)
                        }
                    }
                    val config = NetworkConnectionConfig(protocol, "", "", "", extras =
                        com.folderspan.data.main.network.NetworkDriveExtras(cloud =
                            com.folderspan.data.main.network.CloudDriveExtras(accountId = accountId)))
                    assertEquals(if (mode == "accepted") "private-new-access" else "original-access",
                        store.credential(config).accessToken)
                    assertEquals(1, store.accounts().size)
                } finally { http.close() }
            }
        }
    }

    @Test
    fun additionalPermissionsCannotBeRequestedAtFirstLoginOrForAnUnrelatedProvider() = runSuspendTest {
        val http = HttpClient(MockEngine { error("Invalid expansion must not make a network request") })
        try {
            val manager = StorageAuthorizationManager(http, store(), "https://broker.test/api/v1/storage/oauth")
            for (request in listOf(
                StorageAuthorizationRequest("Dropbox", permissions = setOf(StorageAuthorizationPermission.SHARING)),
                StorageAuthorizationRequest("Nextcloud", "https://cloud.test", "existingaccount0001",
                    setOf(StorageAuthorizationPermission.SHARING)))) {
                assertFailsWith<IllegalArgumentException> { manager.begin(request) }
            }
            assertEquals(emptySet(), Json.decodeFromString<StorageAuthorizationRequest>("""{"protocol":"Dropbox"}""").permissions)
            assertEquals(emptySet(), Json.decodeFromString<StorageAuthorizationStart>(
                """{"handle":"old-runtime","loginUrl":"https://broker.test/authorize","expiresAt":200000}""").acceptedPermissions)
        } finally { http.close() }
    }

}

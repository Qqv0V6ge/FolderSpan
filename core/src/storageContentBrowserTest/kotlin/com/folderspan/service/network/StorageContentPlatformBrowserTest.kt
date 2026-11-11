package com.folderspan.service.network

import com.folderspan.createSettings
import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.service.webui.*
import com.folderspan.settings.RuntimeScopedSettings
import com.folderspan.test.runSuspendTest
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.StorageSettings
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.*

class StorageContentPlatformBrowserTest {
    @Test
    fun runtimeSessionsPasswordsAndUncertainWritesKeepTheirOriginalContext() = runSuspendTest {
        coroutineScope {
            val fixtureUrl = storageContentFixtureUrl(runtime = false)
                ?: error("Start storage-content-browser-fixture.cjs and set FOLDERSPAN_STORAGE_CONTENT_TEST_URL")
            val first = WebUiRuntimeAddress.parse(fixtureUrl)
            val taskKey = Random.nextInt(1, Int.MAX_VALUE).toLong()
            val config = NetworkConnectionConfig("GoogleDrive", "", "", "",
                extras = NetworkDriveExtras(cloud = CloudDriveExtras(connectionId = "browser-fixture", rootId = "root")))
            val originalConnection = WebUiPlatformClient.connection
            val connections = mutableListOf<WebUiManagementConnection>()
            val stores = linkedMapOf<WebUiRuntimeAddress, Pair<RuntimeScopedSettings, Map<String, String?>>>()
            suspend fun attach(target: WebUiRuntimeAddress, token: String, keepPrevious: Boolean = false) {
                if (!keepPrevious) WebUiPlatformClient.connection?.takeIf { it !== originalConnection }?.close()
                WebUiPlatformClient.connection = null
                WebUiAttachment.target = target
                WebUiAttachment.sessionToken = token
                if (stores.keys.none { it.origin == target.origin }) {
                    val settings = RuntimeScopedSettings(StorageSettings(), target.origin)
                    createSettings()
                    stores[target] = settings to settings.keys.associateWith { settings.getStringOrNull(it) }
                }
                WebUiPlatformClient.connection = WebUiManagementConnection.connect(
                    target.origin.replaceFirst("http://", "ws://") + "/api/web-ui/socket", token, this, {}).also { connections += it }
            }
            suspend fun stage(entry: String) = Json.parseToJsonElement(
                StorageContentPlatform.createStage(taskKey, entry).getOrThrow()).jsonObject
            suspend fun stats(target: WebUiRuntimeAddress) = Json.parseToJsonElement(
                WebUiBrowserHttp.exchange("GET", target.httpUrl("/test/stats")).body.decodeToString()).jsonObject
            suspend fun password(block: suspend (CharArray) -> Unit) {
                val chars = "transport-secret".toCharArray()
                try { block(chars) } finally { chars.fill('\u0000') }
            }
            try {
                attach(first, "test-session-first")
                val firstStatus = StorageContentPlatform.status(config).getOrThrow()
                assertTrue(firstStatus.available && firstStatus.metadataProtectionAvailable)
                assertFalse(firstStatus.hasKey || firstStatus.enabled || firstStatus.backupVerified || firstStatus.unlocked)
                val initial = stage("first")
                val repeated = stage("same-session")
                assertEquals(initial["handle"], repeated["handle"])
                assertEquals(initial["openCount"], repeated["openCount"])
                assertEquals(initial["taskReference"], repeated["taskReference"])
                val target = StoragePath("/chosen-target", StorageReference(id = "stable-target"))
                StorageContentPlatform.beginUpload(config, taskKey, "begin-entry", target).getOrThrow()
                val begun = stage("after-begin").getValue("begin").jsonObject
                assertEquals(initial["taskReference"], begun["taskReference"])
                assertEquals("begin-entry", begun.getValue("entryId").jsonPrimitive.content)
                val sentTarget = begun.getValue("target").jsonObject
                assertEquals(target.path, sentTarget.getValue("path").jsonPrimitive.content)
                assertEquals(target.reference!!.id,
                    sentTarget.getValue("reference").jsonObject.getValue("id").jsonPrimitive.content)
                assertEquals(target, StorageContentPlatform.resolveUploadTarget(config, target).getOrThrow())
                assertFalse(StorageContentPlatform.uploadEncryptionEnabled(config, taskKey, "begin-entry").getOrThrow())

                attach(first, "test-session-renewed")
                val renewed = stage("renewed-session")
                val staleStage = StorageContentPlatform.createStage(taskKey, "old-runtime-private-stage").getOrThrow()
                assertNotEquals(initial["handle"], renewed["handle"])
                assertEquals(initial["taskReference"], renewed["taskReference"])
                val second = WebUiRuntimeAddress.parse(initial.getValue("otherRuntime").jsonPrimitive.content)
                attach(second, "test-session-second")
                assertEquals("Runtime encryption context changed", StorageContentPlatform.stageIdentity(taskKey, staleStage).exceptionOrNull()?.message)
                assertEquals("Runtime encryption context changed", StorageContentPlatform.completeStageWrite(taskKey, staleStage, "old-identity").exceptionOrNull()?.message)
                assertFalse(StorageContentPlatform.status(config).getOrThrow().metadataProtectionAvailable)
                val switched = stage("second-runtime")
                assertNotEquals(renewed["handle"], switched["handle"])
                assertNotEquals(initial["taskReference"], switched["taskReference"])
                assertNotEquals(initial["runtime"], switched["runtime"])

                attach(first, "test-session-password")
                val passwordConfig = NetworkConnectionConfig("FTP", "fixture-password-" + taskKey, "alice", "protocol-login")
                assertNull(StorageContentPlatform.inspectPasswordLocation(passwordConfig).getOrThrow().reference)
                var encrypted = passwordConfig
                password { chars ->
                    val reference = StorageContentPlatform.setupPassword(passwordConfig, chars).getOrThrow()
                    encrypted = passwordConfig.copy(extras = NetworkDriveExtras(contentEncryption = reference))
                }
                assertTrue(StorageContentPlatform.status(encrypted).getOrThrow().unlocked)
                StorageContentPlatform.lock(encrypted).getOrThrow()
                val wrong = StorageContentPlatform.unlockPassword(encrypted, "wrong-password".toCharArray()).exceptionOrNull()
                assertEquals(StorageContentErrorCode.UnlockFailed, assertIs<StorageContentException>(wrong).code)
                password { StorageContentPlatform.unlockPassword(encrypted, it).getOrThrow() }
                assertTrue(StorageContentPlatform.status(encrypted).getOrThrow().unlocked)

                // A live old connection can reply after the same URL and token reconnect.
                val delayedConfig = encrypted.copy(host = "fixture-delay")
                val delayed = async {
                    val chars = "transport-secret".toCharArray()
                    try { StorageContentPlatform.unlockPassword(delayedConfig, chars) }
                    finally { chars.fill('\u0000') }
                }
                withTimeout(5_000) {
                    while (!stage("wait-delayed").getValue("delayStarted").jsonPrimitive.boolean) delay(5)
                }
                val previousGeneration = WebUiAttachment.sessionGeneration
                attach(first, "test-session-password", keepPrevious = true)
                assertTrue(WebUiAttachment.sessionGeneration > previousGeneration)
                assertTrue(delayed.await().isFailure)
                assertFalse(StorageContentPlatform.status(encrypted).getOrThrow().unlocked)
                assertNotEquals(initial["handle"], stage("reconnected")["handle"])

                val delayedSwitch = async {
                    val chars = "transport-secret".toCharArray()
                    try { StorageContentPlatform.unlockPassword(delayedConfig, chars) }
                    finally { chars.fill('\u0000') }
                }
                withTimeout(5_000) {
                    while (!stage("wait-runtime-switch").getValue("delayStarted").jsonPrimitive.boolean) delay(5)
                }
                attach(second, "test-session-late-b", keepPrevious = true)
                assertTrue(delayedSwitch.await().isFailure)
                assertFalse(StorageContentPlatform.status(encrypted).getOrThrow().unlocked)
                assertEquals("0", stage("b-has-no-unlock").getValue("passwordRequests").jsonPrimitive.content)
                attach(first, "test-session-after-switch")

                // Reject plaintext remote HTTP before opening a context or serializing a password.
                val currentConnection = WebUiPlatformClient.connection
                val beforeRejected = stage("before-reject").getValue("passwordRequests")
                WebUiAttachment.target = WebUiRuntimeAddress.parse("http://192.0.2.10:8080")
                val rejected = StorageContentPlatform.setupPassword(passwordConfig, "transport-secret".toCharArray()).exceptionOrNull()
                assertTrue(rejected?.message.orEmpty().contains("HTTPS"))
                WebUiAttachment.target = first
                WebUiPlatformClient.connection = currentConnection
                assertEquals(beforeRejected, stage("after-reject").getValue("passwordRequests"))

                // The Runtime commits setup then loses its reply; there is no replay on another Runtime.
                val drop = passwordConfig.copy(host = "fixture-drop-response")
                val uncertain = StorageContentPlatform.setupPassword(drop, "transport-secret".toCharArray()).exceptionOrNull()
                assertEquals(StorageContentErrorCode.UncertainResult, assertIs<StorageContentException>(uncertain).code)
                attach(second, "test-session-no-replay")
                assertNull(StorageContentPlatform.inspectPasswordLocation(drop).getOrThrow().reference)
                attach(first, "test-session-check-original")
                assertNotNull(StorageContentPlatform.inspectPasswordLocation(drop).getOrThrow().reference)
                assertFalse(StorageContentPlatform.status(drop).getOrThrow().unlocked)
                assertEquals(initial["taskReference"], stage("returned")["taskReference"])

                val loginClient = WebUiRemoteClient(this)
                try {
                    val authBefore = stats(first).getValue("delayedAuthStarted").jsonPrimitive.int
                    val oldLogin = async { loginClient.login("test-login-delay-auth") }
                    withTimeout(5_000) {
                        while (stats(first).getValue("delayedAuthStarted").jsonPrimitive.int == authBefore) delay(5)
                    }
                    WebUiAttachment.target = second
                    loginClient.login("test-login-current").getOrThrow()
                    val currentLogin = WebUiPlatformClient.connection
                    assertTrue(oldLogin.await().isFailure)
                    assertSame(currentLogin, WebUiPlatformClient.connection)
                    assertEquals("test-session-test-login-current", WebUiAttachment.sessionToken)
                    assertEquals("fixture-b", StorageContentPlatform.status(config).getOrThrow().scope)

                    // Logout invalidates an in-flight login even when its target stays unchanged.
                    val logoutBefore = stats(second).getValue("delayedAuthStarted").jsonPrimitive.int
                    val abandoned = async { loginClient.login("test-login-delay-auth") }
                    withTimeout(5_000) {
                        while (stats(second).getValue("delayedAuthStarted").jsonPrimitive.int == logoutBefore) delay(5)
                    }
                    loginClient.logout()
                    assertTrue(abandoned.await().isFailure)
                    assertNull(WebUiPlatformClient.connection)
                    assertEquals("", WebUiAttachment.sessionToken)
                    assertFalse(WebUiAttachment.hasSession || WebUiAttachment.connected)
                    withTimeout(5_000) {
                        while (stats(first).getValue("loginSockets").jsonPrimitive.int != 0 ||
                            stats(second).getValue("loginSockets").jsonPrimitive.int != 0) delay(5)
                    }
                } finally { loginClient.disconnect() }
                for ((_, captured) in stores) {
                    val (settings, before) = captured
                    for (key in settings.keys) {
                        val value = settings.getStringOrNull(key).orEmpty()
                        assertFalse(value.contains("transport-secret") || value.contains("wrong-password"))
                        if (key !in before) assertTrue(key.startsWith("storage.content.task.") || key == SettingsUtils.KEY_DEVICE_ID)
                    }
                }
            } finally {
                try { connections.forEach { it.close() } }
                finally {
                    WebUiPlatformClient.connection = originalConnection
                    for ((runtime, captured) in stores) {
                        val (settings, before) = captured
                        val referenceKey = "storage.content.task." +
                            Base64.UrlSafe.encode(runtime.httpUrl("/").encodeToByteArray()) + "." + taskKey
                        for (key in setOf(referenceKey, SettingsUtils.KEY_DEVICE_ID)) {
                            if (key !in before) settings.remove(key)
                        }
                    }
                    WebUiAttachment.reset()
                }
            }
        }
    }
}

package com.folderspan.service.webui

import com.russhwolf.settings.MapSettings
import com.folderspan.utils.SettingsUtils
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class WebUiAuthStoreTest {
    @Test
    fun generatedSecretIsHashedAndMcpTokensAreRejected() {
        SettingsUtils.init(MapSettings())
        val settings = MapSettings()
        val store = WebUiCredentialStore(settings)
        val setup = store.ensureInitialized()
        val secret = assertNotNull(setup.oneTimeSecret)
        assertTrue(secret.startsWith("fwui_"))
        assertTrue(store.matches(secret))
        assertFalse(store.matches("wrong-secret"))
        assertFalse(store.matches("fmcp_deadbeef_00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"))
        assertFalse(settings.getString(SettingsUtils.KEY_WEB_UI_PASSWORD_HASH, "").contains(secret))
        assertEquals(null, store.ensureInitialized().oneTimeSecret)
    }

    @Test
    fun rotatingSecretInvalidatesOldSecretAndPreservesOtherSettings() {
        val settings = MapSettings()
        settings.putString(SettingsUtils.KEY_DEVICE_ID, "existing-device")
        settings.putString(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY, "existing-share-key")
        settings.putString("auth.token", "existing-account-token")
        val store = WebUiCredentialStore(settings)
        val oldSecret = assertNotNull(store.ensureInitialized().oneTimeSecret)
        val otherSettings = settings.keys
            .filterNot { it == SettingsUtils.KEY_WEB_UI_PASSWORD_HASH || it == SettingsUtils.KEY_WEB_UI_INITIALIZED }
            .associateWith { settings.getString(it, "") }

        val newSecret = store.rotate()
        val reloaded = WebUiCredentialStore(settings)

        assertFalse(reloaded.matches(oldSecret))
        assertTrue(reloaded.matches(newSecret))
        assertEquals(null, reloaded.ensureInitialized().oneTimeSecret)
        assertEquals(otherSettings, otherSettings.keys.associateWith { settings.getString(it, "") })
        assertFalse(settings.getString(SettingsUtils.KEY_WEB_UI_PASSWORD_HASH, "").contains(newSecret))
    }

    @Test
    fun sessionsExpireAndRespectCapacity() = runTest {
        var now = 1_000L
        val store = WebUiSessionStore(
            nowMillis = { now },
            idleTimeoutMs = 100L,
            maxSessions = 2,
        )
        val first = store.create()
        val second = store.create()
        assertNotNull(store.get(first.id))
        assertFailsWith<WebUiSessionCapacityException> { store.create() }
        now += 99
        assertNotNull(store.get(first.id, touch = false))
        now += 2
        assertEquals(null, store.get(first.id))
        val third = store.create()
        assertTrue(third.id != first.id)
        assertFalse(store.remove(second.id))
    }
}

class WebUiSecurityPolicyTest {
    @Test
    fun onlySameOriginIsAllowedByDefaultAndInvalidOriginOrHostIsRejected() {
        val policy = WebUiSecurityPolicy(
            advertisedHostProvider = { setOf("127.0.0.1", "192.168.1.10", "runtime.example") },
        )
        assertTrue(policy.allows("127.0.0.1:12043", "http://127.0.0.1:12043", "http"))
        assertTrue(policy.allows("192.168.1.10:12043", "https://192.168.1.10:12043", "https"))
        assertFalse(policy.allows("192.168.1.10:12043", "https://independent.example", "http"))
        assertFalse(policy.allows("192.168.1.10:12043", "http://192.168.1.10:8080", "http"))
        assertFalse(policy.allows("192.168.1.10:12043", "https://192.168.1.10:12043", "http"))
        assertFalse(policy.allows("192.168.1.10:12043", "http://127.0.0.1:12043", "http"))
        assertTrue(policy.allows("192.168.1.10:12043", null, "http"))
        assertTrue(policy.allows("localhost", "http://LOCALHOST:80", "http"))
        assertTrue(policy.allows("[::1]:12043", "http://[::1]:12043", "http"))
        assertTrue(policy.allows("RUNTIME.example.:12043", "http://runtime.example.:12043", "http"))
        listOf("", "null", "file:///tmp", "https://example.test/path", "https://a\\b", "https://a:99999", "https://u:p@host", "https://[notipv6]", "https://host|name", "https://host/").forEach {
            assertFalse(policy.allows("127.0.0.1:12043", it, "http"), it)
        }
        assertFalse(policy.allows("127.0.0.1:invalid", null, "http"))
        assertFalse(policy.allows("evil.example", "http://127.0.0.1:12043", "http"))
        assertFalse(policy.allows(null, "http://127.0.0.1:12043", "http"))
    }

    @Test
    fun explicitlyTrustedOriginsAllowIndependentWeb() {
        val policy = WebUiSecurityPolicy(
            trustedOrigins = setOf("https://independent.example", "http://localhost:8080"),
            advertisedHostProvider = { setOf("192.168.1.10") },
        )
        assertTrue(policy.allows("192.168.1.10:12043", "https://INDEPENDENT.example:443", "http"))
        assertTrue(policy.allows("192.168.1.10:12043", "http://localhost:8080", "http"))
        assertFalse(policy.allows("192.168.1.10:12043", "http://independent.example", "http"))
        assertFalse(policy.allows("192.168.1.10:12043", "https://independent.example:444", "http"))
        assertFalse(policy.allows("evil.example:12043", "https://independent.example", "http"))
        listOf("*", "https://*.example", "https://example.test/", "https://user@example.test").forEach { origin ->
            assertFailsWith<IllegalArgumentException> {
                WebUiSecurityPolicy(setOf(origin)) { setOf("127.0.0.1") }
            }
        }
    }

    @Test
    fun trustedIpOriginsMatchBrowserSerializationWithoutReinterpretingIpv4() {
        val policy = WebUiSecurityPolicy(
            trustedOrigins = setOf("http://[0:0:0:0:0:0:0:1]:8080", "https://[2001:0DB8:0:1::2]:8443",
                "http://[::ffff:192.0.2.1]:8080"),
            advertisedHostProvider = { setOf("127.0.0.1", "2001:db8:0:1:0:0:0:2") },
        )
        assertTrue(policy.allows("127.0.0.1:12043", "http://[::1]:8080", "http"))
        assertTrue(policy.allows("127.0.0.1:12043", "https://[2001:db8:0:1::2]:8443", "http"))
        assertTrue(policy.allows("127.0.0.1:12043", "http://[::ffff:c000:201]:8080", "http"))
        assertFalse(policy.allows("127.0.0.1:12043", "http://[::ffff:c000:202]:8080", "http"))
        assertTrue(policy.allows("[2001:db8:0:1::2]:12043", "http://[2001:db8:0:1:0:0:0:2]:12043", "http"))
        assertTrue(policy.allows("[0:0:0:0:0:0:0:1]:12043", "http://[::1]:12043", "http"))
        listOf("http://127.000.000.001:8080", "http://127.0.0.010:8080", "http://[::ffff:127.00.0.1]:8080",
            "http://0x7f.0.0.1:8080", "http://0x7f000001:8080").forEach { origin ->
            assertFailsWith<IllegalArgumentException> {
                WebUiSecurityPolicy(setOf(origin)) { setOf("127.0.0.1") }
            }
            assertFalse(policy.allows("127.0.0.1:12043", origin, "http"))
        }
    }
}

class WebUiPathJoinTest {
    @Test
    fun relativeDropPathsUseHostSeparator() {
        assertEquals("/home/user/a.txt", joinWebUiRelative("/home/user", "a.txt", "/"))
        assertEquals("C:\\Users\\a\\b.txt", joinWebUiRelative("C:\\Users\\a", "b.txt", "\\"))
    }
}

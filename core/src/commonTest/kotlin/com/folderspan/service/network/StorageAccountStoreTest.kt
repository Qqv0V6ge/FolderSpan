package com.folderspan.service.network

import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.test.runSuspendTest
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import kotlin.test.*

class StorageAccountStoreTest {
    private val id = "account_1234567890"
    private fun config(protocol: String = "GoogleDrive", host: String = "https://cloud.example.test", library: String = "") =
        NetworkConnectionConfig(protocol, host, "", "", extras = NetworkDriveExtras(cloud = CloudDriveExtras(accountId = id, libraryId = library)))
    private fun credential(kind: StorageAccountKind = StorageAccountKind.GoogleDrive) =
        StorageCredential(id, kind, "provider-user", "Alice", accessToken = "secret", expiresAt = 1L, brokerGrant = "refresh-secret")

    @Test
    fun concurrentRequestsRefreshOnceAndPersistBeforeContinuing() = runSuspendTest {
        val settings = MapSettings()
        var refreshes = 0
        val store = StorageAccountStore(settings, { old -> refreshes++; yield(); old.copy(accessToken = "new-secret", expiresAt = 200_000L) }, now = { 1000L })
        store.install(credential())
        val values = coroutineScope { List(12) { async { store.credential(config()).accessToken } }.awaitAll() }
        assertEquals(List(12) { "new-secret" }, values); assertEquals(1, refreshes)
        assertFalse(store.accounts().single().toString().contains("secret")); assertFalse(credential().toString().contains("secret"))
        val restored = StorageAccountStore(settings, { error("Already refreshed") }, now = { 1000L })
        assertEquals("new-secret", restored.credential(config()).accessToken)
    }

    @Test
    fun accountServerLibraryAndReauthorizationIdentityAreChecked() = runSuspendTest {
        val store = StorageAccountStore(MapSettings(), { it }, now = { 1000L })
        val value = credential(StorageAccountKind.Seafile).copy(server = "https://cloud.example.test", libraryId = "library-A", expiresAt = Long.MAX_VALUE)
        store.install(value)
        assertFailsWith<IllegalStateException> { store.credential(config("Seafile", "https://other.example.test", "library-A")) }
        assertFailsWith<IllegalStateException> { store.credential(config("Seafile", library = "library-B")) }
        assertFailsWith<IllegalStateException> { store.credential(config("GoogleDrive")) }
        assertFailsWith<IllegalStateException> { store.install(value.copy(providerAccountId = "other-user"), id) }
        assertEquals(id, store.credential(config("Seafile", library = "library-A")).id)
        store.remove(id)
        assertFailsWith<IllegalStateException> { store.credential(config("Seafile", library = "library-A")) }
    }

    @Test
    fun refreshedIdentityCannotReplaceAnotherAccount() = runSuspendTest {
        val store = StorageAccountStore(MapSettings(), { it.copy(providerAccountId = "other-user", expiresAt = 200_000L) }, now = { 1000L })
        store.install(credential())
        assertFailsWith<IllegalStateException> { store.credential(config()) }
        assertEquals("Alice", store.metadata(id).label)
    }

    @Test
    fun revokedRefreshRequiresAuthorizationAndDoesNotRetryUntilReauthorized() = runSuspendTest {
        var refreshes = 0
        val store = StorageAccountStore(MapSettings(), { refreshes++; throw StorageAuthorizationRequiredException() }, now = { 1000L })
        store.install(credential())
        assertFailsWith<StorageAuthorizationRequiredException> { store.credential(config()) }
        assertTrue(store.metadata(id).needsAuthorization)
        assertFailsWith<IllegalStateException> { store.credential(config()) }
        assertEquals(1, refreshes)
        store.install(credential().copy(expiresAt = 200_000L), id)
        assertFalse(store.metadata(id).needsAuthorization)
    }

    @Test
    fun reauthorizationRevocationAndRemovalRetireContentUnlocksButTokenRefreshDoesNot() = runSuspendTest {
        val store = StorageAccountStore(MapSettings(), { it.copy(accessToken = "refreshed", expiresAt = 200_000L) },
            now = { 1000L })
        assertEquals("", store.authorizationGeneration(id))
        store.install(credential())
        val initial = store.authorizationGeneration(id)
        assertTrue(initial.isNotEmpty())
        store.credential(config())
        assertEquals(initial, store.authorizationGeneration(id))
        store.install(credential().copy(expiresAt = 200_000L), id)
        val reauthorized = store.authorizationGeneration(id)
        assertNotEquals(initial, reauthorized)
        store.remove(id)
        assertNotEquals(reauthorized, store.authorizationGeneration(id))
        val removed = store.authorizationGeneration(id)
        store.install(credential().copy(expiresAt = 200_000L))
        assertNotEquals(removed, store.authorizationGeneration(id))

        val revoked = StorageAccountStore(MapSettings(), { throw StorageAuthorizationRequiredException() }, now = { 1000L })
        revoked.install(credential())
        val beforeRevoke = revoked.authorizationGeneration(id)
        assertFailsWith<StorageAuthorizationRequiredException> { revoked.credential(config()) }
        assertNotEquals(beforeRevoke, revoked.authorizationGeneration(id))
    }

    @Test
    fun brokerConfigurationRejectsInsecureNonLoopbackAndEmbeddedCredentials() {
        assertEquals("http://127.0.0.1:8080/storage", checkedStorageBrokerUrl("http://127.0.0.1:8080/storage/"))
        assertFailsWith<IllegalArgumentException> { checkedStorageBrokerUrl("http://public.example.test/storage") }
        assertFailsWith<IllegalArgumentException> { checkedStorageBrokerUrl("https://user:secret@broker.example.test/storage") }
        assertFailsWith<IllegalArgumentException> { canonicalStorageServer("https://cloud.example.test/?token=secret") }
    }
}

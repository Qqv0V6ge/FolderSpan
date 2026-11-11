package com.folderspan.service.network

import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.service.http.client.createNoProxyHttpClient
import com.folderspan.test.runSuspendTest
import com.russhwolf.settings.MapSettings
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.*

class CloudProtocolIntegrationTest {
    private fun fixture(): String {
        val value = System.getenv("FOLDERSPAN_CLOUD_TEST_URL").orEmpty()
        assumeTrue("需要通过 Podman 启动受控云盘 HTTP 服务", value.isNotEmpty())
        return value.trimEnd('/')
    }
    private fun store() = StorageAccountStore(MapSettings(), { error("Unexpected refresh") })
    private fun config(protocol: String, server: String, account: StorageAccount, library: String = "", repoToken: Boolean = false) =
        NetworkConnectionConfig(protocol, server, "", "", extras = NetworkDriveExtras(cloud = CloudDriveExtras(accountId = account.id, libraryId = library, seafileRepoToken = repoToken)))
    private suspend fun crud(provider: StorageProvider) {
        val folder = "/集成-" + UUID.randomUUID().toString()
        val expected = ByteArray(1024 * 1024 + 37) { (it % 251).toByte() }
        try {
            provider.createFolder(folder).getOrThrow()
            var offset = 0
            provider.uploadFromSource(folder + "/a b.txt", expected.size.toLong(), readChunk = {
                if (offset == expected.size) null else expected.copyOfRange(offset, minOf(offset + 64 * 1024, expected.size)).also { offset += it.size }
            }).getOrThrow()
            val output = ByteArrayOutputStream()
            (provider as ChunkReadableNetworkClient).downloadByChunks(folder + "/a b.txt", expected.size.toLong()) { bytes, _ ->
                output.write(bytes); Result.success(Unit)
            }.getOrThrow()
            assertContentEquals(expected, output.toByteArray())
            provider.rename(folder + "/a b.txt", folder + "/重命名.txt").getOrThrow()
            provider.createFile(folder + "/empty.txt").getOrThrow()
            assertEquals(setOf("重命名.txt", "empty.txt"), provider.list(folder).getOrThrow().map { it.name }.toSet())
            assertFailsWith<CancellationException> {
                provider.downloadByChunks(folder + "/重命名.txt", expected.size.toLong()) { _, _ -> throw CancellationException("stop") }
            }
            assertEquals(2, provider.list(folder).getOrThrow().size)
            provider.delete(folder + "/empty.txt", false).getOrThrow()
            provider.delete(folder + "/重命名.txt", false).getOrThrow()
            assertTrue(provider.list(folder).getOrThrow().isEmpty())
        } finally {
            provider.delete(folder, true)
            provider.close()
        }
    }

    @Test
    fun nextcloudFlowV2AndWebdavCrudUseRuntimeCredentials() = runSuspendTest {
        val server = fixture() + "/nextcloud"
        val http = createNoProxyHttpClient { expectSuccess = false; followRedirects = false }
        try {
            val store = store()
            val manager = StorageAuthorizationManager(http, store)
            val start = manager.begin(StorageAuthorizationRequest("Nextcloud", server))
            assertEquals("pending", manager.poll(start.handle).status)
            assertEquals(200, http.get(start.loginUrl).status.value)
            val account = checkNotNull(manager.poll(start.handle).account)
            val config = config("Nextcloud", server, account)
            crud(NextcloudNetworkClient(config, { store.credential(config) }, { StorageCapabilities.ReadWrite }))
        } finally { http.close() }
    }

    @Test
    fun realNextcloudWebdavCrudUsesAnAppPassword() = runSuspendTest {
        val server = System.getenv("FOLDERSPAN_NEXTCLOUD_TEST_URL").orEmpty()
        assumeTrue("需要通过 Podman 启动真实 Nextcloud 并提供临时应用密码", server.isNotEmpty())
        val username = checkNotNull(System.getenv("FOLDERSPAN_NEXTCLOUD_TEST_USER"))
        val password = checkNotNull(System.getenv("FOLDERSPAN_NEXTCLOUD_TEST_PASSWORD"))
        val store = store()
        val account = store.install(StorageCredential(
            id = UUID.randomUUID().toString().replace("-", ""),
            kind = StorageAccountKind.Nextcloud,
            providerAccountId = username,
            label = "Podman Nextcloud",
            username = username,
            server = server,
            accessToken = password,
        ))
        val config = config("Nextcloud", server, account)
        crud(NextcloudNetworkClient(config, { store.credential(config) }, { StorageCapabilities.ReadWrite }))
    }

    @Test
    fun realSeafileAccountAndRepoTokensPerformCrudInTheSelectedLibrary() = runSuspendTest {
        val server = System.getenv("FOLDERSPAN_SEAFILE_TEST_URL").orEmpty()
        assumeTrue("需要通过 Podman 启动官方 Seafile 并提供临时 Token", server.isNotEmpty())
        val libraryId = checkNotNull(System.getenv("FOLDERSPAN_SEAFILE_TEST_LIBRARY"))
        for (repoToken in listOf(false, true)) {
            val token = checkNotNull(System.getenv(
                if (repoToken) "FOLDERSPAN_SEAFILE_TEST_REPO_TOKEN" else "FOLDERSPAN_SEAFILE_TEST_ACCOUNT_TOKEN"
            ))
            val http = createNoProxyHttpClient { expectSuccess = false; followRedirects = false }
            try {
                val store = store()
                val manager = StorageAuthorizationManager(http, store)
                val account = manager.installSeafile(SeafileAuthorizationRequest(server, token, repoToken))
                val libraries = manager.libraries(account.id)
                assertTrue(libraries.any { it.id == libraryId })
                if (repoToken) assertEquals(listOf(libraryId), libraries.map { it.id })
                val config = config("Seafile", server, account, libraryId, repoToken)
                crud(SeafileNetworkClient(config, StorageHttpApi({ store.credential(config).accessToken }, http)))
            } finally { http.close() }
        }
    }

    @Test
    fun seafileAccountAndRepoTokensPerformRealHttpCrudInTheSelectedLibrary() = runSuspendTest {
        val server = fixture() + "/seafile"
        val http = createNoProxyHttpClient { expectSuccess = false; followRedirects = false }
        try {
            for (repoToken in listOf(false,true)) {
                val store = store()
                val manager = StorageAuthorizationManager(http, store)
                val account = manager.installSeafile(SeafileAuthorizationRequest(server, if (repoToken) "fixture-repo-token" else "fixture-account-token", repoToken))
                val library = manager.libraries(account.id).single()
                val config = config("Seafile", server, account, library.id, repoToken)
                crud(SeafileNetworkClient(config, StorageHttpApi({ store.credential(config).accessToken }, http)))
            }
        } finally { http.close() }
    }
}

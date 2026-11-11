package com.folderspan.service.network

import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class StorageAdvancedOperationsTest {
    private val path = StoragePath("/same.txt", StorageReference("chosen-file", "folder"))

    @Test
    fun legacyProviderExplicitlyRejectsAdvancedOperations() = runSuspendTest {
        val provider = TestProvider()
        assertEquals(StorageFeatureAvailability.UNSUPPORTED,
            provider.advancedCapabilities(path).getOrThrow().availability(StorageAdvancedFeature.LIST_VERSIONS))
        val failure = provider.listVersions(path).exceptionOrNull()
        assertIs<StorageFeatureException>(failure)
        assertEquals(StorageFeatureAvailability.UNSUPPORTED, failure.availability)
        assertTrue(provider.startUpload(path, 0).isFailure)
        assertTrue(provider.createShare(path, StorageShareRequest(StorageShareKind.LINK, "reader"), true).isFailure)
    }

    @Test
    fun permissionsAreCheckedForTheSelectedEntryAndOperation() = runSuspendTest {
        val capabilities = StorageAdvancedCapabilities(mapOf(
            StorageAdvancedFeature.LIST_VERSIONS to StorageFeatureAvailability.AVAILABLE,
            StorageAdvancedFeature.RESTORE_VERSION to StorageFeatureAvailability.PERMISSION_DENIED,
            StorageAdvancedFeature.RESUMABLE_UPLOAD to StorageFeatureAvailability.AUTHORIZATION_REQUIRED,
        ))
        val provider = TestProvider(capabilities)
        val versions = provider.listVersions(path).getOrThrow()
        assertEquals(path, versions.entries.single().file)
        assertEquals("revision-1", versions.entries.single().id)
        assertEquals(StorageFeatureAvailability.PERMISSION_DENIED,
            (provider.restoreVersion(versions.entries.single(), true).exceptionOrNull() as StorageFeatureException).availability)
        assertEquals(StorageFeatureAvailability.AUTHORIZATION_REQUIRED,
            (provider.startUpload(path, 7).exceptionOrNull() as StorageFeatureException).availability)
        assertEquals(0, provider.writes)
    }

    @Test
    fun sharingNeverDropsUnsupportedRoleExpiryOrRecipient() {
        val capabilities = StorageAdvancedCapabilities(
            mapOf(StorageAdvancedFeature.CREATE_LINK to StorageFeatureAvailability.AVAILABLE),
            shareRoles = setOf("reader"),
        )
        capabilities.validateShare(StorageShareRequest(StorageShareKind.LINK, "reader"))
        assertFailsWith<IllegalArgumentException> {
            capabilities.validateShare(StorageShareRequest(StorageShareKind.LINK, "writer"))
        }
        assertFailsWith<IllegalArgumentException> {
            capabilities.validateShare(StorageShareRequest(StorageShareKind.LINK, "reader", expiresAt = 123))
        }
        assertFailsWith<StorageFeatureException> {
            capabilities.validateShare(StorageShareRequest(StorageShareKind.INVITATION, "reader", "person@example.test"))
        }
        assertFailsWith<IllegalArgumentException> { StorageShareRequest(StorageShareKind.INVITATION, "reader") }
        assertFailsWith<IllegalArgumentException> { StorageShareRequest(StorageShareKind.LINK, "reader", "person@example.test") }
    }

    @Test
    fun chunksValidateBoundsAndAlignmentBeforeWriting() {
        val constraints = StorageUploadConstraints(alignment = 256 * 1024)
        constraints.validate(300_000, 0, 256 * 1024)
        constraints.validate(300_000, 256 * 1024L, 300_000 - 256 * 1024)
        constraints.validate(0, 0, 0)
        assertFailsWith<IllegalArgumentException> { constraints.validate(300_000, 0, 100) }
        assertFailsWith<IllegalArgumentException> { constraints.validate(300_000, 1, 256 * 1024) }
        assertFailsWith<IllegalArgumentException> { constraints.validate(300_000, 0, 300_001) }
        assertFailsWith<IllegalArgumentException> { constraints.validate(300_000, 0, 0) }
    }

    @Test
    fun secretsAndGrantingLinksAreNotLogged() {
        val session = StorageUploadSession("test", path, 7, 0, secret = "https://secret.test/token", binding = "private-binding")
        val share = StorageShare(path, "private-share-id", StorageShareKind.LINK, "reader", url = "https://private.test/link")
        assertFalse(session.toString().contains("secret.test"))
        assertFalse(session.toString().contains("private-binding"))
        assertFalse(share.toString().contains("private"))
        assertFalse(StorageShareRequest(StorageShareKind.INVITATION, "reader", "private@example.test").toString().contains("private"))
    }

    @Test
    fun cancelledCapabilityLookupIsNeverReportedAsAnUnsupportedOperation() = runSuspendTest {
        val provider = object : TestProvider() {
            override suspend fun advancedCapabilities(path: StoragePath): Result<StorageAdvancedCapabilities> =
                throw CancellationException("stop")
        }
        assertFailsWith<CancellationException> { provider.startUpload(path, 1) }
    }

    private open class TestProvider(
        private val advanced: StorageAdvancedCapabilities = StorageAdvancedCapabilities(),
    ) : StorageProvider {
        var writes = 0
        override suspend fun advancedCapabilities(path: StoragePath) = Result.success(advanced)
        override suspend fun listVersions(path: StoragePath, cursor: String?): Result<StoragePage<StorageVersion>> = storageResult {
            advancedCapabilities(path).getOrThrow().require(StorageAdvancedFeature.LIST_VERSIONS)
            StoragePage(listOf(StorageVersion(path, "revision-1")))
        }
        override suspend fun list(path: String, requestId: String?, batchId: String?) = Result.success(emptyList<NetworkFileEntry>())
        override suspend fun download(remotePath: String, localPath: String, size: Long, onProgress: (Long, Long) -> Unit) = Result.success(true)
        override suspend fun upload(localPath: String, remotePath: String, size: Long, onProgress: (Long, Long) -> Unit) = Result.success(true.also { writes++ })
        override suspend fun uploadFromSource(remotePath: String, size: Long, onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?) = Result.success(true.also { writes++ })
        override suspend fun rename(path: String, newPath: String) = Result.success(true)
        override suspend fun delete(path: String, isDirectory: Boolean) = Result.success(true)
        override suspend fun createFolder(path: String) = Result.success(true)
        override suspend fun createFile(path: String) = Result.success(true)
    }
}

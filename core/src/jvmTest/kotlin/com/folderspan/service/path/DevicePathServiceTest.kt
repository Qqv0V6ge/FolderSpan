package com.folderspan.service.path

import strings.AppStrings

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.createSettings
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.db.*
import com.folderspan.exception.AuthorityException
import com.folderspan.extensions.getFileAndFolder
import com.folderspan.service.data.CreateDirectoryRequest
import com.folderspan.service.data.ListRequest
import com.folderspan.testing.grantTestAdministratorAccess
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.ui.state.device.DeviceSharePathGrant
import com.folderspan.ui.state.device.DeviceSharePathScope
import com.folderspan.ui.state.device.DeviceTokenFingerprint
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileSensitivity
import com.folderspan.utils.SettingsUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DevicePathServiceTest {
    @Test
    fun deviceShareScopeUsesSingleVirtualRootAndHidesPhysicalPaths() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DevicePathService(certificateState) { "local-device" }
        val token = "share-scope-token"
        // macOS 临时目录可能经过 /var 链接，共享授权夹具必须使用真实路径。
        val parent = Files.createTempDirectory("device-share-path-scope").toRealPath()
        val shared = Files.createDirectory(parent.resolve("shared"))
        Files.writeString(shared.resolve("photo.jpg"), "photo")
        Files.writeString(parent.resolve("private.txt"), "private")
        try {
            certificateState.setTokenPermission(token, 1L)
            certificateState.setDeviceSharePathScope(
                token,
                DeviceSharePathScope(
                    listOf(DeviceSharePathGrant(shared.absolutePathString(), isDirectory = true))
                ),
            )

            val rootEntries = restoreDevicePathEntries(
                requestPath = "/",
                entries = service.list(token, ListRequest("/")).getOrThrow(),
            )
            val sharedEntries = restoreDevicePathEntries(
                requestPath = "/shared",
                entries = service.list(token, ListRequest("/shared")).getOrThrow(),
            )
            val physicalListing = service.list(token, ListRequest(parent.absolutePathString()))
            val roots = service.rootPaths(token).getOrThrow()

            assertEquals(listOf("shared"), rootEntries.map { entry -> entry.name })
            assertEquals(listOf("/shared"), rootEntries.map { entry -> entry.path })
            assertEquals(listOf("photo.jpg"), sharedEntries.map { entry -> entry.name })
            assertEquals(listOf("/shared/photo.jpg"), sharedEntries.map { entry -> entry.path })
            assertIs<AuthorityException>(physicalListing.exceptionOrNull())
            assertEquals(listOf("/"), roots.map { root -> root.path })
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun deviceShareScopeAllowsAndroidContentUriGrantsWithoutCanonicalFilesystemPath() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val token = "share-content-uri-token"
        val uri = "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fphoto.jpg"
        certificateState.setTokenPermission(token, 1L)
        certificateState.setDeviceSharePathScope(
            token,
            DeviceSharePathScope(
                listOf(DeviceSharePathGrant(uri, isDirectory = false)),
            ),
        )

        val denied = certificateState.checkPermission(
            FileAccessPermission.Allowed,
            token = token,
            path = uri,
            permission = "read",
        )

        assertFalse(denied)
        assertEquals(
            uri,
            certificateState.deviceShareRootPaths(token)?.single(),
        )
    }

    @Test
    fun deviceShareScopeRejectsListingThroughIntermediateSymbolicLink() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val listedPaths = mutableListOf<String>()
        val service = DevicePathService(
            deviceCertificateState = certificateState,
            localDeviceIdProvider = { "local-device" },
            listDirectory = { path ->
                listedPaths += path
                path.getFileAndFolder(FileAccessPermission.Allowed)
            },
            listLimiter = FixedDirectoryListLimiter(1),
        )
        val token = "share-scope-link-token"
        val parent = Files.createTempDirectory("device-share-path-link-boundary").toRealPath()
        val shared = Files.createDirectory(parent.resolve("shared"))
        val outside = Files.createDirectory(parent.resolve("outside"))
        val nestedOutside = Files.createDirectory(outside.resolve("nested"))
        Files.writeString(nestedOutside.resolve("secret.txt"), "secret")
        val link = shared.resolve("escape")
        try {
            Files.createSymbolicLink(link, outside)
            certificateState.setTokenPermission(token, 1L)
            certificateState.setDeviceSharePathScope(
                token,
                DeviceSharePathScope(
                    listOf(DeviceSharePathGrant(shared.absolutePathString(), isDirectory = true))
                ),
            )

            val sharedListing = service.list(token, ListRequest("/shared"))
            assertTrue(sharedListing.isSuccess)
            listOf("/shared/escape", "/shared/escape/nested", "/shared/escape/missing").forEach { path ->
                val escapedListing = service.list(token, ListRequest(path))
                assertIs<AuthorityException>(escapedListing.exceptionOrNull(), "必须拒绝链接路径：$path")
            }
            assertEquals(listOf(shared.absolutePathString()), listedPaths)
        } finally {
            Files.deleteIfExists(link)
            parent.toFile().deleteRecursively()
        }
        Unit
    }

    @Test
    fun webRtcPeerTokenIsBoundToTheApprovedDevice() {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DevicePathService(certificateState) { "local-device" }
        val token = "token-bound-to-peer-a"
        certificateState.setDeviceTokenAndPermission(
            deviceId = "peer-a",
            token = token,
            roleId = 1L,
            fingerprint = DeviceTokenFingerprint(
                deviceId = "peer-a",
                clientIp = null,
                userAgent = null,
            ),
        )

        assertTrue(service.isAuthorizedWebRtcPeerToken(token, "peer-a"))
        assertFalse(service.isAuthorizedWebRtcPeerToken(token, "peer-b"))
        assertFalse(service.isAuthorizedWebRtcPeerToken(token, ""))
        assertFalse(service.isAuthorizedWebRtcPeerToken("missing-token", "peer-a"))
    }

    @Test
    fun webRtcPeerTokenWithoutFingerprintIsRejected() {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DevicePathService(certificateState) { "local-device" }
        val token = "token-without-fingerprint"
        certificateState.setDeviceTokenAndPermission(
            deviceId = "peer-a",
            token = token,
            roleId = 1L,
        )

        assertFalse(service.isAuthorizedWebRtcPeerToken(token, "peer-a"))
        assertTrue(certificateState.isTokenValid(token))
    }

    @Test
    fun defaultDevicePathAdapterAllowsDirectoryListingForAdminToken() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DevicePathService(certificateState) { "local-device" }
        val token = "token-admin-denied"
        certificateState.setTokenPermission(token, 1L)
        val directory = Files.createTempDirectory("device-path-default-denied")
        try {
            val result = service.list(token, ListRequest(directory.absolutePathString()))
            val rootPathsResult = service.rootPaths(token)

            assertTrue(result.isSuccess)
            assertTrue(rootPathsResult.isSuccess)
            assertTrue(rootPathsResult.getOrThrow().isNotEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun listReturnsLocalFilesForAuthorizedToken() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DevicePathService(certificateState) { "local-device" }
        val token = "token-admin"
        certificateState.setTokenPermission(token, 1L)

        val directory = Files.createTempDirectory("device-path-service-list")
        Files.createDirectories(directory.resolve("docs"))
        Files.writeString(directory.resolve("hello.txt"), "hello")

        val result = service.list(token, ListRequest(directory.absolutePathString()))

        val entries = result.getOrThrow().values.flatten()
        assertEquals(setOf("docs", "hello.txt"), entries.map { entry -> entry.name }.toSet())
        assertTrue(entries.all { entry -> entry.sensitivity == FileSensitivity.None })
    }

    @Test
    fun listReturnsEmptyResultForAuthorizedEmptyDirectory() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DevicePathService(certificateState) { "local-device" }
        val token = "token-admin"
        certificateState.setTokenPermission(token, 1L)

        val directory = Files.createTempDirectory("device-path-service-empty")

        val result = service.list(token, ListRequest(directory.absolutePathString()))

        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun listLimitsConcurrentDirectoryEnumeration() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val activeListings = AtomicInteger(0)
        val maxActiveListings = AtomicInteger(0)
        val service = DevicePathService(
            deviceCertificateState = certificateState,
            localDeviceIdProvider = { "local-device" },
            listDirectory = {
                val active = activeListings.incrementAndGet()
                maxActiveListings.updateAndGet { current -> maxOf(current, active) }
                delay(100.milliseconds)
                activeListings.decrementAndGet()
                Result.success(emptyList())
            },
            listLimiter = FixedDirectoryListLimiter(2),
        )
        val token = "token-admin"
        certificateState.setTokenPermission(token, 1L)
        val directory = Files.createTempDirectory("device-path-service-concurrency")

        val results = List(6) {
            async { service.list(token, ListRequest(directory.absolutePathString())) }
        }.awaitAll()

        assertTrue(results.all { result -> result.isSuccess })
        assertEquals(2, maxActiveListings.get())
    }

    @Test
    fun createDirectoryRejectsGuestWriteAccess() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DevicePathService(certificateState)
        val token = "token-guest"
        certificateState.setTokenPermission(token, 2L)

        val directory = Files.createTempDirectory("device-path-service-deny")
        val child = directory.resolve("new-folder").absolutePathString()

        val result = service.createDirectory(token, CreateDirectoryRequest(child))

        assertTrue(result.isFailure)
        assertIs<AuthorityException>(result.exceptionOrNull())
        assertTrue(Files.notExists(directory.resolve("new-folder")))
    }

    @Test
    fun restrictedRoleCanListAuthorizedRootButCannotEscapeThroughSymbolicLink() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DevicePathService(certificateState) { "local-device" }
        val token = "token-restricted-link"
        val parent = Files.createTempDirectory("device-path-link-boundary")
        val allowedRoot = Files.createDirectory(parent.resolve("allowed"))
        val outside = Files.createDirectory(parent.resolve("outside"))
        Files.writeString(outside.resolve("secret.txt"), "secret")
        val link = allowedRoot.resolve("escape")
        try {
            runCatching { Files.createSymbolicLink(link, outside) }.getOrNull() ?: return@runBlocking
            grantPathPermission(database, roleId = 100L, permissionId = 100L, path = allowedRoot.absolutePathString())
            certificateState.setTokenPermission(token, 100L)

            val rootResult = service.list(token, ListRequest(allowedRoot.absolutePathString()))
            val escapedResult = service.list(token, ListRequest(link.absolutePathString()))

            assertTrue(rootResult.isSuccess)
            assertIs<AuthorityException>(escapedResult.exceptionOrNull())
        } finally {
            Files.deleteIfExists(link)
            parent.toFile().deleteRecursively()
        }
        Unit
    }

    @Test
    fun restrictedRoleRejectsSimilarPathPrefix() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DevicePathService(certificateState) { "local-device" }
        val token = "token-restricted-prefix"
        val parent = Files.createTempDirectory("device-path-prefix-boundary")
        val allowedRoot = Files.createDirectory(parent.resolve("share"))
        val similarRoot = Files.createDirectory(parent.resolve("share-backup"))
        try {
            grantPathPermission(database, roleId = 101L, permissionId = 101L, path = allowedRoot.absolutePathString())
            certificateState.setTokenPermission(token, 101L)

            val result = service.list(token, ListRequest(similarRoot.absolutePathString()))

            assertTrue(result.isFailure)
            assertIs<AuthorityException>(result.exceptionOrNull())
        } finally {
            parent.toFile().deleteRecursively()
        }
        Unit
    }

    @Test
    fun restrictedRoleCannotRemoveTerminalLinkThroughIntermediateLink() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val token = "token-restricted-nested-link"
        val parent = Files.createTempDirectory("device-path-terminal-link-boundary")
        val allowedRoot = Files.createDirectory(parent.resolve("share"))
        val outside = Files.createDirectory(parent.resolve("outside"))
        val intermediateLink = allowedRoot.resolve("escape")
        val terminalLink = outside.resolve("terminal")
        try {
            Files.writeString(outside.resolve("secret.txt"), "secret")
            Files.createSymbolicLink(terminalLink, outside.resolve("secret.txt"))
            Files.createSymbolicLink(intermediateLink, outside)
            grantPathPermission(database, roleId = 102L, permissionId = 102L, path = allowedRoot.absolutePathString())
            certificateState.setTokenPermission(token, 102L)

            val denied = certificateState.checkPermission(
                FileAccessPermission.Allowed,
                token = token,
                path = intermediateLink.resolve("terminal").absolutePathString(),
                permission = "remove",
            )

            assertTrue(denied)
            assertTrue(Files.isSymbolicLink(terminalLink))
        } finally {
            Files.deleteIfExists(intermediateLink)
            Files.deleteIfExists(terminalLink)
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun listAllowsDirectoryWhenIgnorePreferencesExist() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DevicePathService(certificateState) { "local-device" }
        val token = "token-admin"
        certificateState.setTokenPermission(token, 1L)

        val parent = Files.createTempDirectory("device-path-ignore-parent")
        val child = parent.resolve("child")
        Files.createDirectories(child)
        Files.writeString(parent.resolve(".gitignore"), "parent-only\n")
        Files.writeString(child.resolve(".gitignore"), "child-only\n")
        Files.writeString(child.resolve("parent-only"), "parent")
        Files.writeString(child.resolve("child-only"), "child")
        Files.writeString(child.resolve("visible.txt"), "visible")

        upsertPathPreference(database, parent.absolutePathString(), listOf(".gitignore"), timestamp = 1L)
        upsertPathPreference(database, child.absolutePathString(), listOf(".gitignore"), timestamp = 2L)

        val result = service.list(token, ListRequest(child.absolutePathString()))

        assertTrue(result.isSuccess)
    }

    @Test
    fun listAllowsDirectAccessToAuthorizedIgnoredDirectory() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DevicePathService(certificateState) { "local-device" }
        val token = "token-admin"
        certificateState.setTokenPermission(token, 1L)

        val directory = Files.createTempDirectory("device-path-ignore-deny")
        val ignoredDirectory = directory.resolve("ignored-dir")
        Files.createDirectories(ignoredDirectory)
        Files.writeString(ignoredDirectory.resolve("inside.txt"), "inside")
        Files.writeString(directory.resolve(".gitignore"), "ignored-dir/\n")
        upsertPathPreference(database, directory.absolutePathString(), listOf(".gitignore"), timestamp = 1L)

        val result = service.list(token, ListRequest(ignoredDirectory.absolutePathString()))

        assertTrue(result.isSuccess)
    }

    @Test
    fun pathPreferencePersistsIgnoreFilesWithSortAndHiddenValues() {
        val database = createInMemoryDatabase()
        val path = "/tmp/preference"
        database.filePathPreferenceQueries.upsert(
            protocol = FileProtocol.Local,
            protocolId = "",
            path = path,
            sort = FileFilterSort.SizeDesc,
            isHideFile = true,
            ignoreFiles = listOf(".gitignore", ".dockerignore"),
            createdAt = 1L,
            updatedAt = 2L,
            lastAccessed = 3L,
        )

        val preference = database.filePathPreferenceQueries.queryByPath(
            protocol = FileProtocol.Local,
            protocolId = "",
            path = path,
        ).executeAsOne()

        assertEquals(FileFilterSort.SizeDesc, preference.sort)
        assertTrue(preference.isHideFile)
        assertEquals(listOf(".gitignore", ".dockerignore"), preference.ignoreFiles)
    }
}

private fun upsertPathPreference(
    database: FolderSpanDatabase,
    path: String,
    ignoreFiles: List<String>,
    timestamp: Long,
) {
    database.filePathPreferenceQueries.upsert(
        protocol = FileProtocol.Local,
        protocolId = "",
        path = path,
        sort = FileFilterSort.NameAsc,
        isHideFile = false,
        ignoreFiles = ignoreFiles,
        createdAt = timestamp,
        updatedAt = timestamp,
        lastAccessed = timestamp,
    )
}

private fun grantPathPermission(
    database: FolderSpanDatabase,
    roleId: Long,
    permissionId: Long,
    path: String,
) {
    database.deviceRoleQueries.insertWithId(roleId, AppStrings.ui_test_device_path_service_restricted_role, AppStrings.ui_test_link_share_file_presentation_test, 0L)
    database.devicePermissionQueries.insertWithId(
        permissionId,
        path,
        true,
        true,
        true,
        true,
        true,
        0L,
        AppStrings.ui_test_device_path_service_test_path_permissions,
    )
    database.deviceRoleDevicePermissionQueries.insert(roleId, permissionId)
}

private fun createInMemoryDatabase(): FolderSpanDatabase {
    SettingsUtils.init(createSettings())
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    FolderSpanDatabase.Schema.create(driver)
    DatabaseReady.markReady()

    return FolderSpanDatabase(
        driver = driver,
        DeviceAdapter = Device.Adapter(
            typeAdapter = deviceTypeAdapter,
        ),
        DeviceConnectAdapter = DeviceConnect.Adapter(
            connectionTypeAdapter = deviceConnectTypeAdapter,
            categoryAdapter = deviceCategoryAdapter,
        ),
        FileBookmarkAdapter = FileBookmark.Adapter(
            typeAdapter = drawerBookmarkTypeAdapter,
            protocolAdapter = fileProtocolAdapter,
        ),
        FileFavoriteAdapter = FileFavorite.Adapter(
            protocolAdapter = fileProtocolAdapter,
        ),
        FileRecentAdapter = FileRecent.Adapter(
            protocolAdapter = fileProtocolAdapter,
        ),
        FileFilterAdapter = FileFilter.Adapter(
            typeAdapter = fileFilterTypeAdapter,
            extensionsAdapter = listOfStringsAdapter
        ),
        DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(
            connectionTypeAdapter = deviceConnectTypeAdapter,
        ),
        FilePathPreferenceAdapter = FilePathPreference.Adapter(
            protocolAdapter = fileProtocolAdapter,
            sortAdapter = fileFilterSortAdapter,
            ignoreFilesAdapter = listOfStringsAdapter,
        )
    ).also { database -> database.grantTestAdministratorAccess() }
}

private val deviceTypeAdapter = object : ColumnAdapter<DeviceType, String> {
    override fun decode(databaseValue: String): DeviceType = DeviceType.valueOf(databaseValue)
    override fun encode(value: DeviceType): String = value.name
}

private val deviceConnectTypeAdapter = object : ColumnAdapter<DeviceConnectType, String> {
    override fun decode(databaseValue: String): DeviceConnectType = DeviceConnectType.valueOf(databaseValue)
    override fun encode(value: DeviceConnectType): String = value.name
}

private val deviceCategoryAdapter = object : ColumnAdapter<DeviceCategory, String> {
    override fun decode(databaseValue: String): DeviceCategory = DeviceCategory.valueOf(databaseValue)
    override fun encode(value: DeviceCategory): String = value.name
}

private val drawerBookmarkTypeAdapter = object : ColumnAdapter<DrawerBookmarkType, String> {
    override fun decode(databaseValue: String): DrawerBookmarkType = DrawerBookmarkType.valueOf(databaseValue)
    override fun encode(value: DrawerBookmarkType): String = value.name
}

private val fileProtocolAdapter = object : ColumnAdapter<FileProtocol, String> {
    override fun decode(databaseValue: String): FileProtocol = FileProtocol.valueOf(databaseValue)
    override fun encode(value: FileProtocol): String = value.name
}

private val fileFilterTypeAdapter = object : ColumnAdapter<FileFilterType, String> {
    override fun decode(databaseValue: String): FileFilterType = FileFilterType.valueOf(databaseValue)
    override fun encode(value: FileFilterType): String = value.name
}

private val listOfStringsAdapter = object : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String): List<String> =
        databaseValue.split(",").map { item -> item.trim() }.filter { item -> item.isNotEmpty() }

    override fun encode(value: List<String>): String = value.joinToString(",")
}

private val fileFilterSortAdapter = object : ColumnAdapter<FileFilterSort, String> {
    override fun decode(databaseValue: String): FileFilterSort = FileFilterSort.valueOf(databaseValue)
    override fun encode(value: FileFilterSort): String = value.name
}

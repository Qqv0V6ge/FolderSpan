package com.folderspan.service.bookmark

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
import com.folderspan.service.data.CreateBookmarkRequest
import com.folderspan.service.data.DeleteBookmarkRequest
import com.folderspan.service.data.ReorderBookmarksRequest
import com.folderspan.service.data.UpdateBookmarkRequest
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.ui.state.device.DeviceTokenFingerprint
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.SettingsUtils
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceBookmarkServiceTest {
    @Test
    fun bookmarkCrudWorksForAuthorizedToken() = runBlocking {
        val database = createInMemoryDatabase()
        withTestKoin(database) {
            val certificateState = DeviceCertificateState(database)
            val service = DeviceBookmarkService(certificateState)
            val token = "bookmark-admin"
            certificateState.setTokenPermission(token, 1L)

            val createResult = service.createBookmark(
                token,
                CreateBookmarkRequest(
                    name = AppStrings.ui_test_device_bookmark_service_test_bookmarks,
                    path = "/tmp/demo",
                    iconType = DrawerBookmarkType.Custom,
                    iconPath = "demo"
                )
            )
            assertTrue(createResult.isSuccess)

            val bookmarksAfterCreate = service.getBookmarks(token).getOrThrow()
            val created = bookmarksAfterCreate.first { it.name == AppStrings.ui_test_device_bookmark_service_test_bookmarks }
            assertEquals("/tmp/demo", created.path)

            val updateResult = service.updateBookmark(
                token,
                UpdateBookmarkRequest(
                    id = created.id,
                    name = AppStrings.ui_test_device_bookmark_service_updated_bookmarks,
                    path = "/tmp/updated",
                    iconType = DrawerBookmarkType.Home,
                    iconPath = ""
                )
            )
            assertTrue(updateResult.isSuccess)

            val bookmarksAfterUpdate = service.getBookmarks(token).getOrThrow()
            val updated = bookmarksAfterUpdate.first { it.id == created.id }
            assertEquals(AppStrings.ui_test_device_bookmark_service_updated_bookmarks, updated.name)
            assertEquals("/tmp/updated", updated.path)

            val deleteResult = service.deleteBookmark(token, DeleteBookmarkRequest(created.id))
            assertTrue(deleteResult.isSuccess)
            assertTrue(service.getBookmarks(token).getOrThrow().none { it.id == created.id })
        }
    }

    @Test
    fun bookmarkDeleteRejectsGuestToken() = runBlocking {
        val database = createInMemoryDatabase()
        withTestKoin(database) {
            val certificateState = DeviceCertificateState(database)
            val service = DeviceBookmarkService(certificateState)
            val adminToken = "bookmark-admin"
            val guestToken = "bookmark-guest"
            certificateState.setTokenPermission(adminToken, 1L)
            certificateState.setTokenPermission(guestToken, 999L)

            val createResult = service.createBookmark(
                adminToken,
                CreateBookmarkRequest(AppStrings.ui_test_link_share_file_presentation_test, "/tmp/guest", DrawerBookmarkType.Custom, "")
            )
            assertTrue(createResult.isSuccess)
            val created = service.getBookmarks(adminToken).getOrThrow().first { it.name == AppStrings.ui_test_link_share_file_presentation_test }

            val result = service.deleteBookmark(guestToken, DeleteBookmarkRequest(created.id))

            assertTrue(result.isFailure)
            assertIs<AuthorityException>(result.exceptionOrNull())
            assertTrue(service.getBookmarks(adminToken).getOrThrow().any { it.id == created.id })
        }
    }

    @Test
    fun bookmarkReorderPersistsTheCompleteRequestedOrder() = runBlocking {
        val database = createInMemoryDatabase()
        withTestKoin(database) {
            val certificateState = DeviceCertificateState(database)
            val service = DeviceBookmarkService(certificateState)
            val token = "bookmark-admin"
            certificateState.setTokenPermission(token, 1L)
            service.createBookmark(
                token,
                CreateBookmarkRequest(AppStrings.ui_test_device_bookmark_service_first_item, "/tmp/first", DrawerBookmarkType.Custom, ""),
            ).getOrThrow()
            service.createBookmark(
                token,
                CreateBookmarkRequest(AppStrings.ui_test_device_bookmark_service_second_term, "/tmp/second", DrawerBookmarkType.Custom, ""),
            ).getOrThrow()
            val initial = service.getBookmarks(token).getOrThrow()

            val result = service.reorderBookmarks(
                token,
                ReorderBookmarksRequest(initial.reversed().map { bookmark -> bookmark.id }),
            )

            assertTrue(result.getOrThrow())
            assertEquals(
                initial.reversed().map { bookmark -> bookmark.id },
                service.getBookmarks(token).getOrThrow().map { bookmark -> bookmark.id },
            )
        }
    }

    @Test
    fun bookmarkReorderRejectsAnIncompleteIdSet() = runBlocking {
        val database = createInMemoryDatabase()
        withTestKoin(database) {
            val certificateState = DeviceCertificateState(database)
            val service = DeviceBookmarkService(certificateState)
            val token = "bookmark-admin"
            certificateState.setTokenPermission(token, 1L)
            service.createBookmark(
                token,
                CreateBookmarkRequest(AppStrings.ui_test_device_bookmark_service_first_item, "/tmp/first", DrawerBookmarkType.Custom, ""),
            ).getOrThrow()
            service.createBookmark(
                token,
                CreateBookmarkRequest(AppStrings.ui_test_device_bookmark_service_second_term, "/tmp/second", DrawerBookmarkType.Custom, ""),
            ).getOrThrow()
            val initial = service.getBookmarks(token).getOrThrow()

            val result = service.reorderBookmarks(
                token,
                ReorderBookmarksRequest(listOf(initial.first().id)),
            )

            assertTrue(result.isFailure)
            assertEquals(
                initial.map { bookmark -> bookmark.id },
                service.getBookmarks(token).getOrThrow().map { bookmark -> bookmark.id },
            )
        }
    }

    @Test
    fun fingerprintedTokenStaysValidDuringServiceAuthorizationCheck() = runBlocking {
        val database = createInMemoryDatabase()
        withTestKoin(database) {
            val certificateState = DeviceCertificateState(database)
            val service = DeviceBookmarkService(certificateState)
            val token = "bookmark-fingerprint"
            val fingerprint = DeviceTokenFingerprint(
                deviceId = "device-a",
                clientIp = "10.0.0.8",
                userAgent = "ktor-test"
            )

            certificateState.setDeviceTokenAndPermission(
                deviceId = "device-a",
                token = token,
                roleId = 1L,
                fingerprint = fingerprint
            )

            assertTrue(certificateState.isTokenValid(token, fingerprint))
            assertTrue(certificateState.isTokenValid(token))

            val result = service.getBookmarks(token)

            assertTrue(result.isSuccess)
            assertTrue(certificateState.isTokenValid(token, fingerprint))
            assertTrue(certificateState.isTokenValid(token))
        }
    }

    @Test
    fun omittingFingerprintDoesNotClearAnExistingBinding() {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val token = "keep-fingerprint"
        val fingerprint = DeviceTokenFingerprint(
            deviceId = "device-a",
            clientIp = "10.0.0.8",
            userAgent = "ktor-test",
        )
        certificateState.setDeviceTokenAndPermission(
            deviceId = "device-a",
            token = token,
            roleId = 1L,
            fingerprint = fingerprint,
        )

        certificateState.setDeviceTokenAndPermission(
            deviceId = "device-a",
            token = token,
            roleId = 1L,
        )

        assertTrue(certificateState.hasTokenFingerprint(token))
        assertTrue(certificateState.isTokenValid(token, fingerprint))
        assertTrue(certificateState.isTokenValid(token))
    }
}

private suspend fun withTestKoin(
    database: FolderSpanDatabase,
    block: suspend () -> Unit,
) {
    stopKoin()
    startKoin {
        modules(
            module {
                single { database }
            }
        )
    }
    try {
        block()
    } finally {
        stopKoin()
    }
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
    )
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

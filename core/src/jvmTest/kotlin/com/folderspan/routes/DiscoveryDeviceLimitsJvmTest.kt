package com.folderspan.routes

import com.folderspan.data.main.device.DeviceType
import com.folderspan.db.Device
import com.folderspan.db.DeviceConnect
import com.folderspan.db.DeviceReceiveShare
import com.folderspan.db.FileBookmark
import com.folderspan.db.FileFavorite
import com.folderspan.db.FileFilter
import com.folderspan.db.FilePathPreference
import com.folderspan.db.FileRecent
import com.folderspan.db.FolderSpanDatabase
import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.service.data.SocketDevice
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.executeAsOneOrNullAwait
import kotlinx.coroutines.runBlocking
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryDeviceLimitsJvmTest {
    @Test
    fun discoveryDeviceFieldErrorRejectsOversizedIdNameHostAndSeparator() {
        assertEquals(
            AppStrings.ui_invalid_discovery_device_id,
            discoveryDeviceFieldError(sampleDevice(id = "x".repeat(MAX_DISCOVERY_DEVICE_ID_LENGTH + 1))),
        )
        assertEquals(
            AppStrings.ui_invalid_discovery_device_id,
            discoveryDeviceFieldError(sampleDevice(name = "n".repeat(MAX_DISCOVERY_DEVICE_NAME_LENGTH + 1))),
        )
        assertEquals(
            AppStrings.ui_invalid_discovery_device_id,
            discoveryDeviceFieldError(sampleDevice(host = "h".repeat(MAX_DISCOVERY_DEVICE_HOST_LENGTH + 1))),
        )
        assertEquals(
            AppStrings.ui_invalid_discovery_device_id,
            discoveryDeviceFieldError(sampleDevice(pathSeparator = "/".repeat(MAX_DISCOVERY_PATH_SEPARATOR_LENGTH + 1))),
        )
        assertNull(discoveryDeviceFieldError(sampleDevice()))
    }

    @Test
    fun persistDiscoveredDeviceDoesNotInsertOversizedId() = runBlocking {
        val testDatabase = createDiscoveryTestDatabase()
        val device = sampleDevice(id = "x".repeat(MAX_DISCOVERY_DEVICE_ID_LENGTH + 1))
        val inserted = persistDiscoveredDevice(testDatabase.database, device, allowExistingUpdates = false)
        assertFalse(inserted)
        assertNull(testDatabase.database.deviceQueries.queryById(device.id).executeAsOneOrNullAwait())
        testDatabase.driver.close()
    }

    @Test
    fun persistDiscoveredDeviceInsertsValidDevice() = runBlocking {
        val testDatabase = createDiscoveryTestDatabase()
        val device = sampleDevice(id = "device-valid")
        persistDiscoveredDevice(testDatabase.database, device, allowExistingUpdates = false)
        val stored = testDatabase.database.deviceQueries.queryById(device.id).executeAsOneOrNullAwait()
        assertEquals(device.id, stored?.id)
        assertEquals(device.name, stored?.name)
        assertEquals(device.host, stored?.host)
        testDatabase.driver.close()
    }

    @Test
    fun persistDiscoveredDeviceStopsAfterGlobalCap() = runBlocking {
        val testDatabase = createDiscoveryTestDatabase()
        repeat(MAX_DISCOVERED_DEVICES) { index ->
            persistDiscoveredDevice(
                testDatabase.database,
                sampleDevice(id = "cap-$index", name = "n$index"),
                allowExistingUpdates = false,
            )
        }
        persistDiscoveredDevice(
            testDatabase.database,
            sampleDevice(id = "cap-overflow"),
            allowExistingUpdates = false,
        )
        val stored = testDatabase.database.deviceQueries.queryAll().executeAsListAwait()
        assertEquals(MAX_DISCOVERED_DEVICES, stored.size)
        assertNull(testDatabase.database.deviceQueries.queryById("cap-overflow").executeAsOneOrNullAwait())
        testDatabase.driver.close()
    }

    @Test
    fun pendingQuotaCapsUnknownDevicesPerSource() = runBlocking {
        DiscoveryPendingQuota.clearForTests()
        repeat(MAX_PENDING_UNKNOWN_DEVICES_PER_SOURCE) { index ->
            assertTrue(DiscoveryPendingQuota.tryReserve("10.0.0.8", "pending-$index"))
        }
        assertFalse(DiscoveryPendingQuota.tryReserve("10.0.0.8", "pending-overflow"))
        assertTrue(DiscoveryPendingQuota.tryReserve("10.0.0.9", "other-source"))
        DiscoveryPendingQuota.clearForTests()
    }

    @Test
    fun rejectedPairingCapacityUsesTooManyRequests() {
        val response = rejectedCapacityResponse(RawHttpRouteClass.Pairing)
        assertEquals(429, response.statusCode)
        val control = rejectedCapacityResponse(RawHttpRouteClass.Control)
        assertEquals(503, control.statusCode)
    }

    private fun sampleDevice(
        id: String = "device-1",
        name: String = "Phone",
        host: String = "192.168.1.8",
        pathSeparator: String = "/",
    ): SocketDevice {
        return SocketDevice(
            id = id,
            name = name,
            pathSeparator = pathSeparator,
            host = host,
            type = DeviceType.JVM,
        )
    }
}

private data class DiscoveryTestDatabase(
    val database: FolderSpanDatabase,
    val driver: JdbcSqliteDriver,
)

private fun createDiscoveryTestDatabase(): DiscoveryTestDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    FolderSpanDatabase.Schema.create(driver)
    DatabaseReady.markReady()
    val database = FolderSpanDatabase(
        driver = driver,
        DeviceAdapter = Device.Adapter(
            typeAdapter = object : ColumnAdapter<DeviceType, String> {
                override fun decode(databaseValue: String): DeviceType = DeviceType.valueOf(databaseValue)
                override fun encode(value: DeviceType): String = value.name
            },
        ),
        DeviceConnectAdapter = DeviceConnect.Adapter(
            connectionTypeAdapter = object : ColumnAdapter<DeviceConnectType, String> {
                override fun decode(databaseValue: String): DeviceConnectType = DeviceConnectType.valueOf(databaseValue)
                override fun encode(value: DeviceConnectType): String = value.name
            },
            categoryAdapter = object : ColumnAdapter<DeviceCategory, String> {
                override fun decode(databaseValue: String): DeviceCategory = DeviceCategory.valueOf(databaseValue)
                override fun encode(value: DeviceCategory): String = value.name
            },
        ),
        FileBookmarkAdapter = FileBookmark.Adapter(
            typeAdapter = object : ColumnAdapter<DrawerBookmarkType, String> {
                override fun decode(databaseValue: String): DrawerBookmarkType = DrawerBookmarkType.valueOf(databaseValue)
                override fun encode(value: DrawerBookmarkType): String = value.name
            },
            protocolAdapter = object : ColumnAdapter<FileProtocol, String> {
                override fun decode(databaseValue: String): FileProtocol = FileProtocol.valueOf(databaseValue)
                override fun encode(value: FileProtocol): String = value.name
            },
        ),
        FileFavoriteAdapter = FileFavorite.Adapter(
            protocolAdapter = object : ColumnAdapter<FileProtocol, String> {
                override fun decode(databaseValue: String): FileProtocol = FileProtocol.valueOf(databaseValue)
                override fun encode(value: FileProtocol): String = value.name
            },
        ),
        FileRecentAdapter = FileRecent.Adapter(
            protocolAdapter = object : ColumnAdapter<FileProtocol, String> {
                override fun decode(databaseValue: String): FileProtocol = FileProtocol.valueOf(databaseValue)
                override fun encode(value: FileProtocol): String = value.name
            },
        ),
        FileFilterAdapter = FileFilter.Adapter(
            typeAdapter = object : ColumnAdapter<FileFilterType, String> {
                override fun decode(databaseValue: String): FileFilterType = FileFilterType.valueOf(databaseValue)
                override fun encode(value: FileFilterType): String = value.name
            },
            extensionsAdapter = object : ColumnAdapter<List<String>, String> {
                override fun decode(databaseValue: String): List<String> =
                    if (databaseValue.isEmpty()) emptyList() else databaseValue.split(',')
                override fun encode(value: List<String>): String = value.joinToString(",")
            },
        ),
        DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(
            connectionTypeAdapter = object : ColumnAdapter<DeviceConnectType, String> {
                override fun decode(databaseValue: String): DeviceConnectType = DeviceConnectType.valueOf(databaseValue)
                override fun encode(value: DeviceConnectType): String = value.name
            },
        ),
        FilePathPreferenceAdapter = FilePathPreference.Adapter(
            protocolAdapter = object : ColumnAdapter<FileProtocol, String> {
                override fun decode(databaseValue: String): FileProtocol = FileProtocol.valueOf(databaseValue)
                override fun encode(value: FileProtocol): String = value.name
            },
            sortAdapter = object : ColumnAdapter<FileFilterSort, String> {
                override fun decode(databaseValue: String): FileFilterSort = FileFilterSort.valueOf(databaseValue)
                override fun encode(value: FileFilterSort): String = value.name
            },
            ignoreFilesAdapter = object : ColumnAdapter<List<String>, String> {
                override fun decode(databaseValue: String): List<String> =
                    if (databaseValue.isEmpty()) emptyList() else databaseValue.split(',')
                override fun encode(value: List<String>): String = value.joinToString(",")
            },
        )
    )
    return DiscoveryTestDatabase(database, driver)
}

package com.folderspan.routes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.mcp.http.createMcpHttpTestDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RawHttpDiscoveryPersistenceTest {
    @Test
    fun unauthenticatedDiscoveryCannotRewriteExistingDeviceEndpoint() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val database = createMcpHttpTestDatabase(driver)
            database.deviceQueries.insert(
                id = "paired-device-id",
                name = "Trusted phone",
                host = "192.168.1.20",
                port = 12040L,
                type = DeviceType.Android,
            )

            persistDiscoveredDevice(
                database = database,
                device = SocketDevice(
                    id = "paired-device-id",
                    name = "Imposter",
                    pathSeparator = "/",
                    host = "10.0.0.9",
                    type = DeviceType.JVM,
                    httpsPort = 22040,
                ),
                allowExistingUpdates = false,
            )

            val stored = database.deviceQueries.queryById("paired-device-id").executeAsOne()
            assertEquals("Trusted phone", stored.name)
            assertEquals("192.168.1.20", stored.host)
            assertEquals(12040L, stored.port)
            assertEquals(DeviceType.Android, stored.type)
        } finally {
            driver.close()
        }
    }

    @Test
    fun unauthenticatedDiscoveryStillInsertsPreviouslyUnknownDevice() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val database = createMcpHttpTestDatabase(driver)
            val discovered = SocketDevice(
                id = "new-device-id",
                name = "New phone",
                pathSeparator = "/",
                host = "192.168.1.30",
                type = DeviceType.Android,
                httpsPort = 12040,
            )

            persistDiscoveredDevice(database, discovered, allowExistingUpdates = false)

            val stored = database.deviceQueries.queryById(discovered.id).executeAsOne()
            assertEquals(discovered.host, stored.host)
            assertEquals(discovered.httpsPort.toLong(), stored.port)
        } finally {
            driver.close()
        }
    }

    @Test
    fun trustedDiscoveryPathCanRefreshAnExistingDeviceEndpoint() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val database = createMcpHttpTestDatabase(driver)
            database.deviceQueries.insert(
                id = "paired-device-id",
                name = "Old name",
                host = "192.168.1.20",
                port = 12040L,
                type = DeviceType.Android,
            )
            val refreshed = SocketDevice(
                id = "paired-device-id",
                name = "Current name",
                pathSeparator = "/",
                host = "192.168.1.21",
                type = DeviceType.Android,
                httpsPort = 13040,
            )

            val changed = persistDiscoveredDevice(database, refreshed, allowExistingUpdates = true)

            val stored = database.deviceQueries.queryById(refreshed.id).executeAsOne()
            assertTrue(changed)
            assertEquals(refreshed.name, stored.name)
            assertEquals(refreshed.host, stored.host)
            assertEquals(refreshed.httpsPort.toLong(), stored.port)
        } finally {
            driver.close()
        }
    }
}

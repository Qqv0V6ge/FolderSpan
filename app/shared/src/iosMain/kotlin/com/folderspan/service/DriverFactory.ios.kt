package com.folderspan.service

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.PathUtils
import com.folderspan.utils.protectIosPrivatePath

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val databaseName = "folderspan.db"
        val basePath = PathUtils.getAppPath()
        val driver = NativeSqliteDriver(
            FolderSpanDatabase.Schema,
            databaseName,
            onConfiguration = { config ->
                config.copy(extendedConfig = config.extendedConfig.copy(basePath = basePath))
            }
        )
        protectIosPrivateDatabaseFiles(basePath, databaseName)
        DatabaseReady.markReady()
        return driver
    }

    private fun protectIosPrivateDatabaseFiles(basePath: String, databaseName: String) {
        val root = basePath.trimEnd('/')
        protectIosPrivatePath("$root/$databaseName")
        protectIosPrivatePath("$root/$databaseName-wal")
        protectIosPrivatePath("$root/$databaseName-shm")
        protectIosPrivatePath("$root/$databaseName-journal")
    }
}

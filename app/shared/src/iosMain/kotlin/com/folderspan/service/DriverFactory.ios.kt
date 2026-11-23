package com.folderspan.service

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.IosDatabaseStorage

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        // 沙盒根目录可能不可写，先准备 Application Support 下的私有数据库目录。
        val basePath = IosDatabaseStorage.prepareDirectory()
        val driver = NativeSqliteDriver(
            FolderSpanDatabase.Schema,
            IosDatabaseStorage.databaseName,
            onConfiguration = { config ->
                config.copy(extendedConfig = config.extendedConfig.copy(basePath = basePath))
            }
        )
        IosDatabaseStorage.protectDatabaseFiles(basePath)
        DatabaseReady.markReady()
        return driver
    }
}

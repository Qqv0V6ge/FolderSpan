package com.folderspan.service

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.folderspan.androidContext
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.utils.DatabaseReady

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val driver = AndroidSqliteDriver(FolderSpanDatabase.Schema, androidContext(), "folderspan.db")
        DatabaseReady.markReady()
        return driver
    }
}

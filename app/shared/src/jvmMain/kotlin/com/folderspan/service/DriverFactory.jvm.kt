package com.folderspan.service

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.cleanup.resolveDesktopApplicationDataDirectory
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.LogKit
import com.folderspan.utils.restrictOwnerOnlyPath
import java.io.File

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val dbFile = resolveDatabaseFile()
        dbFile.parentFile?.mkdirs()
        dbFile.parentFile?.toPath()?.let { parent -> restrictOwnerOnlyPath(parent, directory = true) }
        val existed = dbFile.exists()
        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        restrictOwnerOnlyDatabaseFiles(dbFile)
        val currentVersion = FolderSpanDatabase.Schema.version
        if (!existed || !hasUserTables(driver)) {
            FolderSpanDatabase.Schema.create(driver)
            setUserVersion(driver, currentVersion)
        } else {
            val storedVersion = readUserVersion(driver)
            val sourceVersion = if (storedVersion == 0L) LEGACY_SCHEMA_VERSION else storedVersion
            when {
                sourceVersion < currentVersion -> {
                    FolderSpanDatabase.Schema.migrate(driver, sourceVersion, currentVersion)
                    setUserVersion(driver, currentVersion)
                }

                sourceVersion == currentVersion && storedVersion == 0L -> {
                    setUserVersion(driver, currentVersion)
                }

                sourceVersion > currentVersion -> LogKit.w(
                    "Desktop database version $sourceVersion is newer than code version $currentVersion."
                )
            }
        }
        restrictOwnerOnlyDatabaseFiles(dbFile)
        DatabaseReady.markReady()
        return driver
    }

    private fun restrictOwnerOnlyDatabaseFiles(dbFile: File) {
        val parent = dbFile.parentFile ?: return
        parent.toPath().let { restrictOwnerOnlyPath(it, directory = true) }
        listOf(
            dbFile,
            File(parent, "${dbFile.name}-wal"),
            File(parent, "${dbFile.name}-shm"),
            File(parent, "${dbFile.name}-journal"),
        ).filter(File::exists).forEach { file ->
            restrictOwnerOnlyPath(file.toPath(), directory = false)
        }
    }

    private fun resolveDatabaseFile() =
        resolveDesktopApplicationDataDirectory()
            .resolve("folderspan.db")
            .toFile()

    private fun readUserVersion(driver: SqlDriver): Long =
        driver.executeQuery(
            null,
            "PRAGMA user_version",
            { cursor ->
                val hasRow = cursor.next().value
                app.cash.sqldelight.db.QueryResult.Value(if (hasRow) cursor.getLong(0) ?: 0L else 0L)
            },
            0,
        ).value

    private fun hasUserTables(driver: SqlDriver): Boolean =
        driver.executeQuery(
            null,
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' LIMIT 1",
            { cursor -> app.cash.sqldelight.db.QueryResult.Value(cursor.next().value) },
            0,
        ).value

    private fun setUserVersion(driver: SqlDriver, version: Long) {
        driver.execute(null, "PRAGMA user_version = $version", 0).value
    }

    private companion object {
        const val LEGACY_SCHEMA_VERSION: Long = 1L
    }
}

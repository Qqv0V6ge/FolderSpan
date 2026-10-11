package com.folderspan.service

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.LogKit
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.Worker

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val workerUrl = js("""new URL("sqljs.worker.js", import.meta.url).toString()""") as String
        val target = checkNotNull(com.folderspan.service.webui.WebUiAttachment.target) { "Runtime address required before database initialization" }
        val worker = Worker(workerUrl + "?runtime=" + com.folderspan.service.webui.encodeWebUiQuery(target.origin))
        val driver = SqliteBooleanDriver(WebWorkerDriver(worker))
        MainScope().launch {
            try {
                createSchemaWithAwait(driver)
                DatabaseReady.markReady()
            } catch (error: Throwable) {
                DatabaseReady.markFailed(error)
                LogKit.e("Browser database initialization failed", error)
            }
        }
        return driver
    }
}

// SQLDelight's JS worker cursor casts SQLite's numeric 0/1 directly to Boolean.
// Nullable Boolean comparisons (for example permission.useAll != true) require real booleans.
private class SqliteBooleanDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = delegate.executeQuery(identifier, sql, { cursor ->
        mapper(object : SqlCursor by cursor {
            override fun getBoolean(index: Int): Boolean? = cursor.getLong(index)?.let { it != 0L }
        })
    }, parameters, binders)
}

private suspend fun createSchemaWithAwait(driver: SqlDriver) {
    val currentVersion = FolderSpanDatabase.Schema.version
    val userVersion = readUserVersion(driver)
    val hasTables = hasUserTables(driver)
    when {
        userVersion == 0L && !hasTables -> {
            applySchemaStatements(driver, "schema create") { item ->
                FolderSpanDatabase.Schema.create(item)
            }
            setUserVersion(driver, currentVersion)
            LogKit.i("SQLDelight schema created for JS worker driver at version $currentVersion.")
        }
        userVersion < currentVersion -> {
            applySchemaStatements(driver, "schema migrate") { item ->
                FolderSpanDatabase.Schema.migrate(item, userVersion, currentVersion)
            }
            setUserVersion(driver, currentVersion)
            LogKit.i(
                "SQLDelight schema migrated from $userVersion to $currentVersion for JS worker driver."
            )
        }
        userVersion > currentVersion -> {
            LogKit.w(
                "SQLDelight schema version $userVersion is newer than code version " +
                    "$currentVersion for JS worker driver."
            )
        }
        else -> LogKit.i(
            "SQLDelight schema already at version $currentVersion for JS worker driver."
        )
    }
}

private suspend fun applySchemaStatements(
    driver: SqlDriver,
    label: String,
    buildStatements: (SqlDriver) -> Unit
) {
    val collector = SchemaCollectorDriver()
    buildStatements(collector)
    val statements = collector.statements
    LogKit.i("SQLDelight $label statements collected: ${statements.size}")
    statements.forEachIndexed { index, sql ->
        try {
            driver.execute(null, sql, 0, null).await()
        } catch (e: Throwable) {
            val preview = sql.replace('\n', ' ').take(120)
            LogKit.e("Schema statement failed at #$index: $preview", e)
            throw e
        }
    }
}

private suspend fun readUserVersion(driver: SqlDriver): Long {
    val result = driver.executeQuery(
        null,
        "PRAGMA user_version",
        { cursor ->
            when (val first = cursor.next()) {
                is QueryResult.Value -> {
                    if (!first.value) return@executeQuery QueryResult.Value(0L)
                    QueryResult.Value(cursor.getLong(0) ?: 0L)
                }
                is QueryResult.AsyncValue -> QueryResult.AsyncValue {
                    if (!first.await()) return@AsyncValue 0L
                    cursor.getLong(0) ?: 0L
                }
            }
        },
        0,
        null
    )
    return result.await()
}

private suspend fun hasUserTables(driver: SqlDriver): Boolean {
    val result = driver.executeQuery(
        null,
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' LIMIT 1",
        { cursor ->
            when (val first = cursor.next()) {
                is QueryResult.Value -> QueryResult.Value(first.value)
                is QueryResult.AsyncValue -> QueryResult.AsyncValue { first.await() }
            }
        },
        0,
        null
    )
    return result.await()
}

private suspend fun setUserVersion(driver: SqlDriver, version: Long) {
    driver.execute(null, "PRAGMA user_version = $version", 0, null).await()
}

private class SchemaCollectorDriver : SqlDriver {
    val statements = mutableListOf<String>()

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<Long> {
        statements.add(sql)
        return QueryResult.Value(0L)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<R> {
        throw UnsupportedOperationException("Schema collector should not execute queries.")
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> =
        QueryResult.Value(SchemaTransaction())

    override fun currentTransaction(): Transacter.Transaction? = null

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun notifyListeners(vararg queryKeys: String) = Unit

    override fun close() = Unit
}

// Schema collection records SQL before the real worker transaction executes it.
private class SchemaTransaction : Transacter.Transaction() {
    override val enclosingTransaction: Transacter.Transaction? = null
    override fun endTransaction(successful: Boolean): QueryResult<Unit> = QueryResult.Unit
}

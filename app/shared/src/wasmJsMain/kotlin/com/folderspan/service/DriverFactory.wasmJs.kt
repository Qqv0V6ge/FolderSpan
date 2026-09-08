@file:OptIn(ExperimentalWasmJsInterop::class)

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

private val sqlWorkerUrl: String =
    js("new URL('sqljs.worker.js', import.meta.url).toString()")

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        return try {
            LogKit.i("SQLDelight worker URL: $sqlWorkerUrl")
            val worker = Worker(sqlWorkerUrl)
            val driver = WebWorkerDriver(worker)
            MainScope().launch {
                val schemaResult = runCatching { createSchemaWithAwait(driver) }
                schemaResult.exceptionOrNull()?.let { throwable ->
                    LogKit.e("SQLDelight schema init failed for WASM worker driver.", throwable)
                }
                DatabaseReady.markReady()
            }
            driver
        } catch (e: Throwable) {
            LogKit.e(
                "Failed to init SQLDelight WASM worker driver; falling back to noop driver. " +
                    "message=${e.message}",
                e
            )
            DatabaseReady.markReady()
            NoopSqlDriver()
        }
    }
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
            LogKit.i("SQLDelight schema created for WASM worker driver at version $currentVersion.")
        }
        userVersion == 0L && hasTables -> {
            applySchemaStatements(driver, "legacy schema migrate") { item ->
                FolderSpanDatabase.Schema.migrate(item, 1L, currentVersion)
            }
            setUserVersion(driver, currentVersion)
        }
        userVersion < currentVersion -> {
            applySchemaStatements(driver, "schema migrate") { item ->
                FolderSpanDatabase.Schema.migrate(item, userVersion, currentVersion)
            }
            setUserVersion(driver, currentVersion)
            LogKit.i(
                "SQLDelight schema migrated from $userVersion to $currentVersion for WASM worker driver."
            )
        }
        userVersion > currentVersion -> {
            LogKit.w(
                "SQLDelight schema version $userVersion is newer than code version " +
                    "$currentVersion for WASM worker driver."
            )
        }
        else -> LogKit.i(
            "SQLDelight schema already at version $currentVersion for WASM worker driver."
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
        QueryResult.Value(NoopTransaction())

    override fun currentTransaction(): Transacter.Transaction? = null

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun notifyListeners(vararg queryKeys: String) = Unit

    override fun close() = Unit
}

private class NoopSqlDriver : SqlDriver {
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<R> {
        binders?.invoke(NoopSqlPreparedStatement)
        return mapper(NoopSqlCursor())
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<Long> {
        binders?.invoke(NoopSqlPreparedStatement)
        return QueryResult.Value(0L)
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> =
        QueryResult.Value(NoopTransaction())

    override fun currentTransaction(): Transacter.Transaction? = null

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun notifyListeners(vararg queryKeys: String) = Unit

    override fun close() = Unit
}

private object NoopSqlPreparedStatement : SqlPreparedStatement {
    override fun bindBytes(index: Int, bytes: ByteArray?) = Unit

    override fun bindLong(index: Int, long: Long?) = Unit

    override fun bindDouble(index: Int, double: Double?) = Unit

    override fun bindString(index: Int, string: String?) = Unit

    override fun bindBoolean(index: Int, boolean: Boolean?) = Unit
}

private class NoopSqlCursor : SqlCursor {
    override fun next(): QueryResult<Boolean> = QueryResult.Value(false)

    override fun getString(index: Int): String? = null

    override fun getLong(index: Int): Long? = null

    override fun getBytes(index: Int): ByteArray? = null

    override fun getDouble(index: Int): Double? = null

    override fun getBoolean(index: Int): Boolean? = null
}

private class NoopTransaction : Transacter.Transaction() {
    override val enclosingTransaction: Transacter.Transaction? = null

    override fun endTransaction(successful: Boolean): QueryResult<Unit> = QueryResult.Unit
}

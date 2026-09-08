package com.folderspan.utils

import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.db.QueryResult

suspend fun <T> QueryResult<T>.awaitDatabaseReady(): T {
    DatabaseReady.await()
    return await()
}

suspend fun <T : Any> ExecutableQuery<T>.executeAsListAwait(): List<T> {
    DatabaseReady.await()
    return execute { cursor ->
        when (val first = cursor.next()) {
            is QueryResult.Value -> {
                val result = mutableListOf<T>()
                if (first.value) {
                    result.add(mapper(cursor))
                    while (cursor.next().value) {
                        result.add(mapper(cursor))
                    }
                }
                QueryResult.Value(result)
            }

            is QueryResult.AsyncValue -> QueryResult.AsyncValue {
                val result = mutableListOf<T>()
                var hasNext = first.await()
                while (hasNext) {
                    result.add(mapper(cursor))
                    hasNext = cursor.next().await()
                }
                result
            }
        }
    }.await()
}

suspend fun <T : Any> ExecutableQuery<T>.executeAsOneAwait(): T {
    DatabaseReady.await()
    return executeAsOneOrNullAwait()
        ?: throw NullPointerException("ResultSet returned null for $this")
}

suspend fun <T : Any> ExecutableQuery<T>.executeAsOneOrNullAwait(): T? {
    DatabaseReady.await()
    return execute { cursor ->
        when (val first = cursor.next()) {
            is QueryResult.Value -> {
                if (!first.value) return@execute QueryResult.Value(null)
                val value = mapper(cursor)
                check(!cursor.next().value) { "ResultSet returned more than 1 row for $this" }
                QueryResult.Value(value)
            }

            is QueryResult.AsyncValue -> QueryResult.AsyncValue {
                if (!first.await()) return@AsyncValue null
                val value = mapper(cursor)
                check(!cursor.next().await()) { "ResultSet returned more than 1 row for $this" }
                value
            }
        }
    }.await()
}

package com.folderspan.pro.core.common

import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonElement

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
        val statusCode: Int? = null,
        val apiCode: Int? = null,
    ) : ApiResult<Nothing>()
}

typealias JsonResult = ApiResult<JsonElement>

fun ApiResult.Failure.isUnauthorized(): Boolean {
    if (apiCode == UnauthorizedApiCode) return true
    if (statusCode == HttpStatusCode.Unauthorized.value) return true
    val clientException = cause as? ClientRequestException ?: return false
    return clientException.response.status == HttpStatusCode.Unauthorized
}

private const val UnauthorizedApiCode = 40100

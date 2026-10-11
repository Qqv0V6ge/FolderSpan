package com.folderspan.service.data

import com.folderspan.exception.AuthorityException
import com.folderspan.exception.EmptyDataException
import com.folderspan.exception.ParameterErrorException
import com.folderspan.exception.TimeoutException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoBuf
import strings.AppStrings

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SerializableResult(
    @ProtoNumber(1) val isSuccess: Boolean,
    @ProtoNumber(2) val data: ByteArray = byteArrayOf(),
    @ProtoNumber(3) val errorMessage: String? = null,
    @ProtoNumber(4) val errorType: String? = null,
    @ProtoNumber(5) val hasData: Boolean = false,
) {
    companion object {
        inline fun <reified T> success(data: T): SerializableResult {
            return SerializableResult(
                isSuccess = true,
                data = ProtoBuf.encodeToByteArray<T>(data),
                hasData = true,
            )
        }

        fun failure(errorMessage: String, errorType: String? = null): SerializableResult {
            return SerializableResult(isSuccess = false, errorMessage = errorMessage, errorType = errorType)
        }

        fun failure(exception: Throwable): SerializableResult {
            return SerializableResult(
                isSuccess = false,
                errorMessage = exception.message ?: "Unknown error",
                errorType = exception::class.simpleName
            )
        }
    }
}

inline fun <reified T> Result<T>.toSerializableResult(): SerializableResult {
    return if (isSuccess) {
        SerializableResult.success(getOrThrow())
    } else {
        val exception = exceptionOrNull()
        SerializableResult.failure(
            errorMessage = exception?.message ?: "Unknown error",
            errorType = exception?.let { error -> error::class.simpleName }
        )
    }
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> SerializableResult.toResult(): Result<T> {
    return if (isSuccess) {
        if (!hasData) {
            Result.failure(IllegalStateException(AppStrings.ui_missing_return_data))
        } else {
            runCatching { ProtoBuf.decodeFromByteArray<T>(data) }
        }
    } else {
        val exception = recreateException(errorType, errorMessage ?: "Unknown error")
        Result.failure(exception)
    }
}

@PublishedApi
internal fun recreateException(errorType: String?, errorMessage: String): Exception {
    return when (errorType) {
        "EmptyDataException" -> EmptyDataException(errorMessage)
        "AuthorityException" -> AuthorityException(errorMessage)
        "ParameterErrorException" -> ParameterErrorException(errorMessage)
        "TimeoutException" -> TimeoutException(errorMessage)
        else -> Exception(errorMessage)
    }
}

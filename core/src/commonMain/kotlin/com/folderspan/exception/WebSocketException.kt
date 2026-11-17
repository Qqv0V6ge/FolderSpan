package com.folderspan.exception

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import strings.AppStrings

class AuthenticationException(message: String = AppStrings.ui_auth_token_invalid) : IllegalStateException(message)

open class ConnectionClosedException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
class TimeoutException(
    @ProtoNumber(1) override val message: String = AppStrings.message_task_request_timeout,
) : Exception()

@OptIn(ExperimentalSerializationApi::class)
@Serializable
class ParameterErrorException(
    @ProtoNumber(1) override val message: String = AppStrings.error_parameter_invalid,
) : Exception(message)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AuthorityException(
    @ProtoNumber(1) override val message: String? = AppStrings.message_task_permission_denied,
) : Exception(message)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
class EmptyDataException(
    @ProtoNumber(1) override val message: String = AppStrings.ui_data_not_found,
) : Exception()

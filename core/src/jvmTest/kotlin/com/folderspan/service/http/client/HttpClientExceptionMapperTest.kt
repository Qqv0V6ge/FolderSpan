package com.folderspan.service.http.client

import strings.AppStrings

import com.folderspan.createSettings
import com.folderspan.exception.AuthorityException
import com.folderspan.exception.EmptyDataException
import com.folderspan.exception.ParameterErrorException
import com.folderspan.service.data.SerializableResult
import com.folderspan.service.data.toResult
import com.folderspan.service.data.toSerializableResult
import com.folderspan.utils.ProtoBufCodec
import com.folderspan.utils.SettingsUtils
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class HttpClientExceptionMapperTest {
    @BeforeTest
    fun setUp() {
        SettingsUtils.init(createSettings())
    }

    @Test
    fun forbiddenMapsToAuthorityException() {
        val exception = httpStatusException(HttpStatusCode.Forbidden, AppStrings.message_task_permission_denied)

        assertIs<AuthorityException>(exception)
        assertEquals(AppStrings.message_task_permission_denied, exception.message)
    }

    @Test
    fun transientTlsEofIsRetryableButCancellationIsNot() {
        assertTrue(java.io.EOFException("Not enough data available").isRetryableDeviceConnectionError())
        assertFalse(CancellationException("401 Unauthorized").isRetryableDeviceConnectionError())
    }

    @Test
    fun badRequestMapsToIllegalArgumentException() {
        val exception = httpStatusException(HttpStatusCode.BadRequest, AppStrings.ui_path_empty)

        assertIs<IllegalArgumentException>(exception)
        assertEquals(AppStrings.ui_path_empty, exception.message)
    }

    @Test
    fun unauthorizedMapsToAuthorityException() {
        val exception = httpStatusException(HttpStatusCode.Unauthorized, AppStrings.ui_auth_token_invalid)

        assertIs<AuthorityException>(exception)
        assertEquals(AppStrings.ui_auth_token_invalid, exception.message)
    }

    @Test
    fun notFoundMapsToEmptyDataException() {
        val exception = httpStatusException(HttpStatusCode.NotFound, AppStrings.ui_file_does_not_exist)

        assertIs<EmptyDataException>(exception)
        assertEquals(AppStrings.ui_file_does_not_exist, exception.message)
    }

    @Test
    fun protobufFailureBodyRestoresAuthorityException() {
        val body = ProtoBufCodec.encode(
            SerializableResult.failure(AuthorityException(AppStrings.error_path_access_denied))
        )

        val exception = httpExceptionFromBody(HttpStatusCode.Forbidden, body)

        assertIs<AuthorityException>(exception)
        assertEquals(AppStrings.error_path_access_denied, exception.message)
    }

    @Test
    fun protobufFailureBodyRestoresEmptyDataException() {
        val body = ProtoBufCodec.encode(
            SerializableResult.failure(EmptyDataException(AppStrings.ui_file_does_not_exist))
        )

        val exception = httpExceptionFromBody(HttpStatusCode.NotFound, body)

        assertIs<EmptyDataException>(exception)
        assertEquals(AppStrings.ui_file_does_not_exist, exception.message)
    }

    @Test
    fun protobufFailureBodyRestoresParameterErrorException() {
        val body = ProtoBufCodec.encode(
            SerializableResult.failure(ParameterErrorException(AppStrings.ui_read_the_parameter_invalid))
        )

        val exception = httpExceptionFromBody(HttpStatusCode.BadRequest, body)

        assertIs<ParameterErrorException>(exception)
        assertEquals(AppStrings.ui_read_the_parameter_invalid, exception.message)
    }

    @Test
    fun plainTextBodyFallsBackToStatusMapping() {
        val exception = httpExceptionFromBody(
            HttpStatusCode.BadRequest,
            AppStrings.ui_path_empty.encodeToByteArray()
        )

        assertIs<IllegalArgumentException>(exception)
        assertEquals(AppStrings.ui_path_empty, exception.message)
    }

    @Test
    fun decodeHttpResultBodyReadsWrappedSuccess() {
        val body = ProtoBufCodec.encode(Result.success(true).toSerializableResult())

        val result = decodeHttpResultBody<Boolean>(body)

        assertTrue(result.isSuccess)
        assertEquals(true, result.getOrThrow())
    }

    @Test
    fun decodeHttpResultBodyKeepsLegacyDirectBodyCompatibility() {
        val body = ProtoBufCodec.encode(true)

        val result = decodeHttpResultBody<Boolean>(body)

        assertTrue(result.isSuccess)
        assertEquals(true, result.getOrThrow())
    }

    @Test
    fun decodeHttpResultBodyKeepsLegacyDirectListCompatibility() {
        val body = ProtoBufCodec.encode(listOf(SerializableResult.success(true)))

        val result = decodeHttpResultBody<List<SerializableResult>>(body)

        assertTrue(result.isSuccess)
        assertEquals(true, result.getOrThrow().single().toResult<Boolean>().getOrThrow())
    }
}

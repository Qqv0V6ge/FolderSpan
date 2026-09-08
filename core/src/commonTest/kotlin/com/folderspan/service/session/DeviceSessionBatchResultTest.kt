package com.folderspan.service.session

import strings.AppStrings

import com.folderspan.exception.AuthorityException
import com.folderspan.service.data.SerializableResult
import com.folderspan.service.data.toResult
import com.folderspan.service.data.toSerializableResult
import com.folderspan.utils.ProtoBufCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceSessionBatchResultTest {
    @Test
    fun batchBooleanResultsRoundTripThroughSerializableWireValues() {
        val operationResult = Result.success(
            listOf(
                Result.success(true),
                Result.failure<Boolean>(AuthorityException(AppStrings.message_task_permission_denied)),
                Result.success(false),
            )
        )

        val wirePayload = ProtoBufCodec.encode(
            operationResult.toSerializableBooleanBatchResult().toSerializableResult()
        )
        val restored = ProtoBufCodec.decode<SerializableResult>(wirePayload)
            .toResult<List<SerializableResult>>()
            .toBooleanBatchResult()
            .getOrThrow()

        assertEquals(3, restored.size)
        assertTrue(restored[0].getOrThrow())
        assertIs<AuthorityException>(restored[1].exceptionOrNull())
        assertEquals(AppStrings.message_task_permission_denied, restored[1].exceptionOrNull()?.message)
        assertEquals(false, restored[2].getOrThrow())
    }

    @Test
    fun outerBatchFailureRemainsAnOuterFailure() {
        val restored = Result.failure<List<Result<Boolean>>>(IllegalStateException(AppStrings.ui_test_device_session_batch_result_batch_request_failed))
            .toSerializableBooleanBatchResult()

        assertTrue(restored.isFailure)
        assertEquals(AppStrings.ui_test_device_session_batch_result_batch_request_failed, restored.exceptionOrNull()?.message)
    }
}

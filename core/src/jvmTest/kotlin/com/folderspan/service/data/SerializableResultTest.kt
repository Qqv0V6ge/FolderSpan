package com.folderspan.service.data

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

@OptIn(ExperimentalSerializationApi::class)
class SerializableResultTest {
    @Test
    fun roundTripsEmptyMapSuccess() {
        val result = Result.success<Map<Pair<FileProtocol, String>, MutableList<FileSimpleInfo>>>(
            emptyMap()
        )

        val encoded = ProtoBuf.encodeToByteArray(result.toSerializableResult())
        val decoded = ProtoBuf.decodeFromByteArray<SerializableResult>(
            encoded
        ).toResult<Map<Pair<FileProtocol, String>, MutableList<FileSimpleInfo>>>()

        assertTrue(decoded.isSuccess)
        assertTrue(decoded.getOrThrow().isEmpty())
    }

    @Test
    fun roundTripsFalseBooleanSuccess() {
        val encoded = ProtoBuf.encodeToByteArray(Result.success(false).toSerializableResult())
        val decoded = ProtoBuf.decodeFromByteArray<SerializableResult>(encoded).toResult<Boolean>()

        assertTrue(decoded.isSuccess)
        assertEquals(false, decoded.getOrThrow())
    }
}

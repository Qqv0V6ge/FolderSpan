package com.folderspan.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
class ProtoBufCodecTest {
    @Serializable
    private data class Sample(
        @ProtoNumber(1) val value: String,
    )

    @Test
    fun encodesPlainProtobufBytes() {
        val sample = Sample("tls-protected")

        val encoded = ProtoBufCodec.encode(sample)

        assertContentEquals(ProtoBuf.encodeToByteArray(sample), encoded)
        assertEquals(sample, ProtoBufCodec.decode(encoded))
    }
}

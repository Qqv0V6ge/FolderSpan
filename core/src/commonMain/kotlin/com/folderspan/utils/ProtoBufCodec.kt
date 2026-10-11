package com.folderspan.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

object ProtoBufCodec {
    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T> encode(value: T): ByteArray {
        return ProtoBuf.encodeToByteArray(value)
    }

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T> decode(data: ByteArray): T {
        return ProtoBuf.decodeFromByteArray(data)
    }
}

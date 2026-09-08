package com.folderspan.data.file

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PathInfo(
    @ProtoNumber(1) val path: String,
    @ProtoNumber(2) val totalSpace: Long,
    @ProtoNumber(3) val freeSpace: Long,
)

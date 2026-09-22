package com.folderspan.pro.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class WebRtcRoomWriteRequest(
    val name: String,
    val stunUrl: String,
    val turnUrl: String,
    val turnUsername: String,
    val turnPassword: String,
)

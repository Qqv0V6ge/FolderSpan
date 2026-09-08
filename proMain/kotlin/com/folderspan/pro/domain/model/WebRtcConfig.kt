package com.folderspan.pro.domain.model

import com.folderspan.data.main.webrtc.WebRtcRoomSource

data class WebRtcConfig(
    val id: Long,
    val name: String,
    val wssUrl: String,
    val roomId: String,
    val stunUrl: String? = null,
    val turnUrl: String? = null,
    val turnUsername: String? = null,
    val turnPassword: String? = null,
    val source: WebRtcRoomSource,
    val pinned: Boolean = false,
    val sortOrder: Long = 0L,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

data class WebRtcConfigDraft(
    val name: String,
    val wssUrl: String,
    val roomId: String,
    val stunUrl: String? = null,
    val turnUrl: String? = null,
    val turnUsername: String? = null,
    val turnPassword: String? = null,
    val source: WebRtcRoomSource,
    val pinned: Boolean = false,
    val sortOrder: Long = 0L,
)

data class WebRtcConfigPage(
    val list: List<WebRtcConfig>,
    val total: Long,
)

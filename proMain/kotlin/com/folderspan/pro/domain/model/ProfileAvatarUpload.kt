package com.folderspan.pro.domain.model

data class ProfileAvatarUpload(
    val bytes: ByteArray,
    val contentType: String,
)

const val MAX_PROFILE_AVATAR_BYTES: Long = 2L * 1024L * 1024L

val SupportedProfileAvatarContentTypes: Set<String> = setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
)

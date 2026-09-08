package com.folderspan.pro.domain.model

data class UpdateProfileCommand(
    val name: String? = null,
    val signature: String? = null,
    val avatar: ProfileAvatarUpload? = null,
)

data class ChangePasswordCommand(
    val oldPassword: String,
    val newPassword: String,
)

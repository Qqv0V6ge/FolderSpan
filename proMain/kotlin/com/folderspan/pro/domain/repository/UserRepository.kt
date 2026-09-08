package com.folderspan.pro.domain.repository

import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.domain.model.ChangePasswordCommand
import com.folderspan.pro.domain.model.ProfileAvatarUpload
import com.folderspan.pro.domain.model.UpdateProfileCommand

interface UserRepository {
    suspend fun cachedMe(token: String): JsonResult? = null
    suspend fun me(token: String): JsonResult
    suspend fun listDevices(token: String): JsonResult
    suspend fun deleteDevice(id: Long, token: String): JsonResult
    suspend fun updateProfile(command: UpdateProfileCommand, token: String): JsonResult
    suspend fun uploadAvatar(upload: ProfileAvatarUpload, token: String): JsonResult
    suspend fun removeAvatar(token: String): JsonResult
    suspend fun withdrawProfileReview(token: String): JsonResult
    suspend fun changePassword(command: ChangePasswordCommand, token: String): JsonResult
    suspend fun refreshToken(refreshToken: String): JsonResult
}

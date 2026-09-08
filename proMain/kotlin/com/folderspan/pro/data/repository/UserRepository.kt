package com.folderspan.pro.data.repository

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.core.network.cache.ApiCachePolicy
import com.folderspan.pro.data.remote.api.UserApiService
import com.folderspan.pro.data.remote.dto.ChangePasswordRequest
import com.folderspan.pro.data.remote.dto.UpdateProfileRequest
import com.folderspan.pro.domain.model.ChangePasswordCommand
import com.folderspan.pro.domain.model.UpdateProfileCommand
import com.folderspan.pro.domain.model.MAX_PROFILE_AVATAR_BYTES
import com.folderspan.pro.domain.model.ProfileAvatarUpload
import com.folderspan.pro.domain.model.SupportedProfileAvatarContentTypes
import com.folderspan.pro.domain.repository.UserRepository
import strings.AppStrings
import kotlin.time.Duration.Companion.days

class DefaultUserRepository(
    private val userApiService: UserApiService,
) : UserRepository {
    override suspend fun cachedMe(token: String): JsonResult? =
        userApiService.cachedMe(
            token = token,
            cachePolicy = UserProfileCachePolicy,
        )

    override suspend fun me(token: String): JsonResult =
        userApiService.me(
            token = token,
            cachePolicy = UserProfileCachePolicy,
        )

    override suspend fun listDevices(token: String): JsonResult =
        userApiService.listDevices(
            token = token,
            cachePolicy = UserDevicesCachePolicy,
        )

    override suspend fun deleteDevice(id: Long, token: String): JsonResult =
        userApiService.deleteDevice(id, token)

    override suspend fun updateProfile(command: UpdateProfileCommand, token: String): JsonResult =
        command.avatar.validationFailureOrNull()
            ?: userApiService.updateProfile(
                request = UpdateProfileRequest(
                    name = command.name,
                    signature = command.signature,
                ),
                avatar = command.avatar,
                token = token,
            )

    override suspend fun uploadAvatar(upload: ProfileAvatarUpload, token: String): JsonResult {
        upload.validationFailureOrNull()?.let { return it }
        return userApiService.uploadAvatar(upload, token)
            .withSafeAvatarFailure(AppStrings.ui_profile_avatar_upload_failed)
    }

    override suspend fun removeAvatar(token: String): JsonResult =
        userApiService.removeAvatar(token)
            .withSafeAvatarFailure(AppStrings.ui_profile_avatar_remove_failed)

    override suspend fun withdrawProfileReview(token: String): JsonResult =
        userApiService.withdrawProfileReview(token)

    override suspend fun changePassword(command: ChangePasswordCommand, token: String): JsonResult =
        userApiService.changePassword(
            ChangePasswordRequest(
                oldPassword = command.oldPassword,
                newPassword = command.newPassword,
            ),
            token,
        )

    override suspend fun refreshToken(refreshToken: String): JsonResult =
        userApiService.refreshToken(refreshToken)
}

private fun ProfileAvatarUpload?.validationFailureOrNull(): ApiResult.Failure? = when {
    this == null -> null
    bytes.size.toLong() > MAX_PROFILE_AVATAR_BYTES ->
        ApiResult.Failure(AppStrings.ui_profile_avatar_output_too_large)
    contentType.lowercase() !in SupportedProfileAvatarContentTypes ->
        ApiResult.Failure(AppStrings.ui_profile_avatar_format_unsupported)
    else -> null
}

private fun JsonResult.withSafeAvatarFailure(message: String): JsonResult = when (this) {
    is ApiResult.Success -> this
    is ApiResult.Failure -> ApiResult.Failure(
        message = message,
        statusCode = statusCode,
        apiCode = apiCode,
    )
}

private val UserProfileCachePolicy = ApiCachePolicy(ttl = 30.days)
private val UserDevicesCachePolicy = ApiCachePolicy(ttl = 30.days)

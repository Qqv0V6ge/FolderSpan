package com.folderspan.pro.data.repository

import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.data.remote.api.UserApiService
import com.folderspan.pro.data.remote.dto.LoginRequest
import com.folderspan.pro.data.remote.dto.RegisterRequest
import com.folderspan.pro.data.remote.dto.ResetPasswordRequest
import com.folderspan.pro.domain.repository.AuthRepository

class DefaultAuthRepository(
    private val userApiService: UserApiService,
) : AuthRepository {
    override suspend fun login(email: String, password: String): JsonResult =
        userApiService.login(LoginRequest(email = email, password = password))

    override suspend fun register(name: String, email: String, password: String): JsonResult =
        userApiService.register(
            RegisterRequest(
                name = name.takeIf { it.isNotBlank() },
                email = email,
                password = password,
            ),
        )

    override suspend fun requestPasswordReset(email: String): JsonResult =
        userApiService.forgotPassword(email)

    override suspend fun resetPassword(email: String, code: String, newPassword: String): JsonResult =
        userApiService.resetPassword(
            ResetPasswordRequest(
                email = email,
                code = code,
                newPassword = newPassword,
            ),
        )
}

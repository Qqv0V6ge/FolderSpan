package com.folderspan.pro.domain.repository

import com.folderspan.pro.core.common.JsonResult

interface AuthRepository {
    suspend fun login(email: String, password: String): JsonResult
    suspend fun register(name: String, email: String, password: String): JsonResult
    suspend fun requestPasswordReset(email: String): JsonResult
    suspend fun resetPassword(email: String, code: String, newPassword: String): JsonResult
}

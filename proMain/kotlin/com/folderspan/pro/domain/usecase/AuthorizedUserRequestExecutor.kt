package com.folderspan.pro.domain.usecase

import strings.AppStrings

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.core.common.isUnauthorized
import com.folderspan.pro.core.common.normalizedAccessTokenOrNull
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.core.datastore.parseAuthSession
import com.folderspan.pro.core.datastore.shouldRefreshAccessToken
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock

class AuthorizedUserRequestExecutor(
    private val refreshToken: suspend (String) -> JsonResult,
    private val currentEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    suspend fun <T> execute(
        session: AuthSession?,
        actionLabel: String,
        onUnauthorized: () -> Unit,
        request: suspend (String) -> ApiResult<T>,
    ): ApiResult<T> {
        val currentSession = session ?: SessionManager.currentSession()
            ?: return ApiResult.Failure(AppStrings.ui_please_log_before_continuing)

        val readySession = if (currentSession.shouldRefreshAccessToken(currentEpochSeconds())) {
            when (val refreshResult = refreshSession(currentSession, onUnauthorized)) {
                is ApiResult.Success -> refreshResult.data
                is ApiResult.Failure -> return refreshResult
            }
        } else {
            currentSession
        }

        val accessToken = readySession.accessToken.normalizedAccessTokenOrNull()
            ?: return ApiResult.Failure(AppStrings.ui_please_log_before_continuing)

        val firstResult = request(accessToken)
        if (firstResult !is ApiResult.Failure || !firstResult.isUnauthorized()) {
            return firstResult
        }

        return retryWithRefreshedSession(
            currentSession = readySession,
            onUnauthorized = onUnauthorized,
            request = request,
        )
    }

    private suspend fun refreshSession(
        currentSession: AuthSession,
        onUnauthorized: () -> Unit,
    ): ApiResult<AuthSession> {
        val refreshCredential = currentSession.refreshToken.normalizedAccessTokenOrNull()
        if (refreshCredential == null) {
            SessionManager.clear()
            onUnauthorized()
            return ApiResult.Failure(AppStrings.ui_current_login_has_expired_please_log_again)
        }

        return when (val refreshResult = refreshToken(refreshCredential)) {
            is ApiResult.Success -> {
                val refreshedSession = refreshedSessionFrom(
                    currentSession = currentSession,
                    refreshPayload = refreshResult.data,
                )

                if (refreshedSession == null) {
                    SessionManager.clear()
                    onUnauthorized()
                    ApiResult.Failure(AppStrings.ui_login_information_has_expired_please_log_again)
                } else {
                    SessionManager.update(refreshedSession)
                    ApiResult.Success(refreshedSession)
                }
            }

            is ApiResult.Failure -> {
                SessionManager.clear()
                onUnauthorized()
                refreshResult
            }
        }
    }

    private suspend fun <T> retryWithRefreshedSession(
        currentSession: AuthSession,
        onUnauthorized: () -> Unit,
        request: suspend (String) -> ApiResult<T>,
    ): ApiResult<T> {
        val refreshedSession = when (val refreshResult = refreshSession(currentSession, onUnauthorized)) {
            is ApiResult.Success -> refreshResult.data
            is ApiResult.Failure -> return refreshResult
        }

        val retryResult = request(refreshedSession.accessToken)
        if (retryResult is ApiResult.Failure && retryResult.isUnauthorized()) {
            SessionManager.clear()
            onUnauthorized()
        }
        return retryResult
    }

    private fun refreshedSessionFrom(
        currentSession: AuthSession,
        refreshPayload: JsonElement,
    ): AuthSession? {
        val parsedSession = parseAuthSession(
            payload = refreshPayload,
            nowEpochSeconds = currentEpochSeconds(),
        )
        return parsedSession?.copy(
            refreshToken = parsedSession.refreshToken ?: currentSession.refreshToken,
            expiresIn = parsedSession.expiresIn ?: currentSession.expiresIn,
            userUuid = parsedSession.userUuid ?: currentSession.userUuid,
            userEmail = parsedSession.userEmail ?: currentSession.userEmail,
            loginData = currentSession.loginData ?: parsedSession.loginData,
        )
    }
}

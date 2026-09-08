package com.folderspan.pro.data.repository

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.data.mapper.toAccountDeviceTrustedIdentities
import com.folderspan.pro.data.remote.api.UserApiService
import com.folderspan.pro.domain.usecase.AccountDeviceTrustRefreshResult
import com.folderspan.pro.domain.usecase.AccountDeviceTrustSource

class AccountDeviceTrustRepository(
    private val userApiService: UserApiService,
) : AccountDeviceTrustSource {
    override suspend fun refresh(token: String): AccountDeviceTrustRefreshResult {
        return when (val result = userApiService.listDevices(token = token, cachePolicy = null)) {
            is ApiResult.Success -> AccountDeviceTrustRefreshResult.Success(
                result.data.toAccountDeviceTrustedIdentities()
            )

            is ApiResult.Failure -> if (result.statusCode == 401 || result.statusCode == 403) {
                AccountDeviceTrustRefreshResult.Unauthorized
            } else {
                AccountDeviceTrustRefreshResult.Failure
            }
        }
    }
}

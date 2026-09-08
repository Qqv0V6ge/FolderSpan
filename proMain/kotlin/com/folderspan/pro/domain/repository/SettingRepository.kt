package com.folderspan.pro.domain.repository

import strings.AppStrings

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.domain.model.CloneSettingTargetCommand
import com.folderspan.pro.domain.model.DeviceSettingEntry
import com.folderspan.pro.domain.model.RemoteSettingEntryKey
import com.folderspan.pro.domain.model.RemoteSettingSave

interface SettingRepository {
    suspend fun listDeviceTargets(token: String): JsonResult =
        ApiResult.Failure(AppStrings.ui_unable_read_available_settings_moment_please_try_again_later)

    suspend fun listSettings(type: String, targetId: String, timestamp: Long, token: String): ApiResult<List<DeviceSettingEntry>> =
        ApiResult.Failure(AppStrings.ui_unable_read_settings_moment_please_try_again_later)

    suspend fun saveSetting(type: String, targetId: String, entry: DeviceSettingEntry, token: String): JsonResult =
        ApiResult.Failure(AppStrings.ui_unable_save_settings_this_time_please_try_again_later)

    suspend fun batchSaveSettings(items: List<RemoteSettingSave>, token: String): JsonResult =
        ApiResult.Failure(AppStrings.ui_unable_save_settings_batches_moment_please_try_again_later)

    suspend fun batchDeleteSettings(items: List<RemoteSettingEntryKey>, token: String): JsonResult =
        ApiResult.Failure(AppStrings.ui_unable_delete_settings_batches_moment_please_try_again_later)

    suspend fun listDeviceSettings(targetId: String, timestamp: Long, token: String): ApiResult<List<DeviceSettingEntry>> =
        listSettings(type = "device", targetId = targetId, timestamp = timestamp, token = token)

    suspend fun saveDeviceSetting(targetId: String, entry: DeviceSettingEntry, token: String): JsonResult =
        saveSetting(type = "device", targetId = targetId, entry = entry, token = token)

    suspend fun cloneTargetSettings(command: CloneSettingTargetCommand, token: String): JsonResult =
        ApiResult.Failure(AppStrings.ui_unable_copy_settings_this_time_please_try_again_later)

    suspend fun deleteTargetSettings(type: String, targetId: String, token: String): JsonResult =
        ApiResult.Failure(AppStrings.ui_unable_delete_settings_this_time_please_try_again_later)
}

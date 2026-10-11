package com.folderspan.localization

import com.folderspan.data.device.DeviceJoinDeviceRole
import com.folderspan.data.device.DeviceRole
import com.folderspan.db.DevicePermission
import strings.AppStrings

private fun localizedRoleName(id: Long, name: String): String = when (id to name) {
    1L to "@device_role_admin_name" -> AppStrings.device_role_admin_name
    2L to "@device_role_guest_name" -> AppStrings.device_role_guest_name
    else -> name
}

val DeviceRole.localizedName: String
    get() = localizedRoleName(id, name)

val DeviceJoinDeviceRole.localizedRoleName: String
    get() = localizedRoleName(roleId, roleName)

val DeviceRole.localizedComment: String?
    get() = when (id to comment) {
        1L to "@device_role_admin_comment" -> AppStrings.device_role_admin_comment
        2L to "@device_role_guest_comment" -> AppStrings.device_role_guest_comment
        else -> comment
    }

val DevicePermission.localizedComment: String?
    get() = when (id to comment) {
        1L to "@device_permission_bookmarks_comment" -> AppStrings.device_permission_bookmarks_comment
        2L to "@device_permission_root_paths_comment" -> AppStrings.device_permission_root_paths_comment
        3L to "@device_permission_full_access_comment" -> AppStrings.device_permission_full_access_comment
        4L to "@device_permission_read_only_comment" -> AppStrings.device_permission_read_only_comment
        else -> comment
    }

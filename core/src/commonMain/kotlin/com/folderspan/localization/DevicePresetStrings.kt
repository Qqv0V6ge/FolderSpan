package com.folderspan.localization

import com.folderspan.data.device.DeviceJoinDeviceRole
import com.folderspan.data.device.DeviceRole
import com.folderspan.db.DevicePermission
import strings.AppStrings

// 同时兼容新库标识和旧库默认中文；自定义记录及修改后的文案保持原样。
private fun localizedRoleName(id: Long, name: String): String = when (id to name) {
    1L to "@device_role_admin_name", 1L to "超级管理员" -> AppStrings.device_role_admin_name
    2L to "@device_role_guest_name", 2L to "游客" -> AppStrings.device_role_guest_name
    else -> name
}

val DeviceRole.localizedName: String
    get() = localizedRoleName(id, name)

val DeviceJoinDeviceRole.localizedRoleName: String
    get() = localizedRoleName(roleId, roleName)

val DeviceRole.localizedComment: String?
    get() = when (id to comment) {
        1L to "@device_role_admin_comment",
        1L to "设置后设备将有所有权限，不建议使用到陌生设备" -> AppStrings.device_role_admin_comment
        2L to "@device_role_guest_comment",
        2L to "该角色可以设置任意的设备" -> AppStrings.device_role_guest_comment
        else -> comment
    }

val DevicePermission.localizedComment: String?
    get() = when (id to comment) {
        1L to "@device_permission_bookmarks_comment",
        1L to "获取书签权限" -> AppStrings.device_permission_bookmarks_comment
        2L to "@device_permission_root_paths_comment",
        2L to "获取根目录权限" -> AppStrings.device_permission_root_paths_comment
        3L to "@device_permission_full_access_comment",
        3L to "禁止将该权限，设置除超级管理员以外的角色" -> AppStrings.device_permission_full_access_comment
        4L to "@device_permission_read_only_comment",
        4L to "允许读取根目录下的文件夹和文件夹" -> AppStrings.device_permission_read_only_comment
        else -> comment
    }

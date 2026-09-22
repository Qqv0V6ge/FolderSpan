package com.folderspan.localization

import com.folderspan.data.device.DeviceJoinDeviceRole
import com.folderspan.data.device.DeviceRole
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.db.DevicePermission
import io.github.skeptick.libres.LibresSettings
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DevicePresetStringsTest {
    @Test
    fun presetsAndLegacyRowsFollowLanguageWithoutChangingStoredValues() {
        val previousLanguage = LibresSettings.languageCode
        val admin = DeviceRole(1, "@device_role_admin_name", "@device_role_admin_comment", 999)
        val guest = DeviceRole(2, "@device_role_guest_name", "@device_role_guest_comment", 1)
        val permissionKeys = listOf(
            "@device_permission_bookmarks_comment", "@device_permission_root_paths_comment",
            "@device_permission_full_access_comment", "@device_permission_read_only_comment",
        )
        val legacyComments = listOf(
            "获取书签权限", "获取根目录权限",
            "禁止将该权限，设置除超级管理员以外的角色", "允许读取根目录下的文件夹和文件夹",
        )
        val connectedDevice = DeviceJoinDeviceRole(
            "peer", "Peer", DeviceType.JVM, DeviceConnectType.APPROVED,
            0, 0, DeviceCategory.SERVER, admin.id, admin.name,
        )
        try {
            for (language in listOf("en", "zhHans", "en")) {
                LibresSettings.languageCode = language
                assertEquals(if (language == "en") "Super administrator" else "超级管理员", admin.localizedName)
                assertEquals(if (language == "en") "Guest" else "游客", guest.localizedName)
                assertEquals(admin.localizedName, connectedDevice.localizedRoleName)
                assertEquals(admin.localizedName, admin.copy(name = "超级管理员").localizedName)
                assertEquals(guest.localizedName, guest.copy(name = "游客").localizedName)
                assertEquals(AppStrings.device_role_admin_comment, admin.localizedComment)
                assertEquals(AppStrings.device_role_guest_comment, guest.localizedComment)
                assertEquals(admin.localizedComment, admin.copy(comment = "设置后设备将有所有权限，不建议使用到陌生设备").localizedComment)
                assertEquals(guest.localizedComment, guest.copy(comment = "该角色可以设置任意的设备").localizedComment)
                val expectedComments = listOf(
                    AppStrings.device_permission_bookmarks_comment, AppStrings.device_permission_root_paths_comment,
                    AppStrings.device_permission_full_access_comment, AppStrings.device_permission_read_only_comment,
                )
                permissionKeys.forEachIndexed { index, key ->
                    val permission = DevicePermission(index + 1L, "/", true, true, true, true, true, 0, key)
                    assertEquals(expectedComments[index], permission.localizedComment)
                    assertEquals(expectedComments[index], permission.copy(comment = legacyComments[index]).localizedComment)
                    assertEquals(key, permission.comment)
                    assertEquals(legacyComments[index], permission.copy(id = 99, comment = legacyComments[index]).localizedComment)
                    assertEquals("My permission", permission.copy(comment = "My permission").localizedComment)
                    assertNull(permission.copy(comment = null).localizedComment)
                }
                assertEquals("My role", admin.copy(name = "My role").localizedName)
                assertEquals("My comment", guest.copy(comment = "My comment").localizedComment)
                assertEquals("游客", guest.copy(id = 99, name = "游客").localizedName)
                assertEquals("@device_role_admin_name", admin.name)
                assertEquals("@device_role_guest_comment", guest.comment)
            }
        } finally {
            LibresSettings.languageCode = previousLanguage
        }
    }
}

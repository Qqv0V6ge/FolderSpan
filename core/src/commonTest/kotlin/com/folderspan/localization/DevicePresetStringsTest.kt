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
    fun presetsFollowLanguageWithoutChangingStoredValues() {
        val previousLanguage = LibresSettings.languageCode
        val admin = DeviceRole(1, "@device_role_admin_name", "@device_role_admin_comment", 999)
        val guest = DeviceRole(2, "@device_role_guest_name", "@device_role_guest_comment", 1)
        val permissionKeys = listOf(
            "@device_permission_bookmarks_comment", "@device_permission_root_paths_comment",
            "@device_permission_full_access_comment", "@device_permission_read_only_comment",
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
                assertEquals(AppStrings.device_role_admin_comment, admin.localizedComment)
                assertEquals(AppStrings.device_role_guest_comment, guest.localizedComment)
                val expectedComments = listOf(
                    AppStrings.device_permission_bookmarks_comment, AppStrings.device_permission_root_paths_comment,
                    AppStrings.device_permission_full_access_comment, AppStrings.device_permission_read_only_comment,
                )
                permissionKeys.forEachIndexed { index, key ->
                    val permission = DevicePermission(index + 1L, "/", true, true, true, true, true, 0, key)
                    assertEquals(expectedComments[index], permission.localizedComment)
                    assertEquals(key, permission.comment)
                    assertEquals("My permission", permission.copy(comment = "My permission").localizedComment)
                    assertNull(permission.copy(comment = null).localizedComment)
                }
                assertEquals("My role", admin.copy(name = "My role").localizedName)
                assertEquals("My comment", guest.copy(comment = "My comment").localizedComment)
                assertEquals("@device_role_admin_name", admin.name)
                assertEquals("@device_role_guest_comment", guest.comment)
            }
        } finally {
            LibresSettings.languageCode = previousLanguage
        }
    }
}

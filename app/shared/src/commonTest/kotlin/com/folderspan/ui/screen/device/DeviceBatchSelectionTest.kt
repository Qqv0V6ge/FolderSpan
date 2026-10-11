package com.folderspan.ui.screen.device

import strings.AppStrings

import com.folderspan.data.device.DeviceJoinDeviceRole
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceBatchSelectionTest {

    @Test
    fun toggleAllVisibleDeviceIds_selectsAllVisibleDevices() {
        val visibleIds = setOf("a", "b", "c")

        val result = toggleAllVisibleDeviceIds(visibleIds, setOf("a"))

        assertEquals(visibleIds, result)
    }

    @Test
    fun toggleAllVisibleDeviceIds_clearsSelectionWhenAllVisibleDevicesSelected() {
        val visibleIds = setOf("a", "b")

        val result = toggleAllVisibleDeviceIds(visibleIds, visibleIds)

        assertTrue(result.isEmpty())
    }

    @Test
    fun retainVisibleDeviceSelection_removesIdsOutsideCurrentResultSet() {
        val result = retainVisibleDeviceSelection(
            selectedDeviceIds = setOf("a", "b", "c"),
            visibleDeviceIds = setOf("b", "c", "d")
        )

        assertEquals(setOf("b", "c"), result)
    }

    @Test
    fun hasAllVisibleDevicesSelected_returnsFalseWhenVisibleSetIsEmpty() {
        assertFalse(hasAllVisibleDevicesSelected(emptySet(), setOf("a")))
    }

    @Test
    fun eligibleServerAccessDevices_returnsOnlySelectedDevicesWithServerAccess() {
        val accessDevices = mapOf(
            "a" to accessDevice(id = "a", category = DeviceCategory.SERVER),
            "b" to accessDevice(id = "b", category = DeviceCategory.CLIENT),
            "c" to accessDevice(id = "c", category = DeviceCategory.SERVER)
        )

        val result = eligibleServerAccessDevices(
            selectedDeviceIds = setOf("a", "b", "d"),
            accessDevicesById = accessDevices
        )

        assertEquals(listOf("a"), result.map { device -> device.id })
    }

    private fun accessDevice(
        id: String,
        category: DeviceCategory
    ): DeviceJoinDeviceRole {
        return DeviceJoinDeviceRole(
            id = id,
            name = "Device $id",
            type = DeviceType.JVM,
            connectionType = DeviceConnectType.APPROVED,
            firstConnection = 0L,
            lastConnection = 0L,
            category = category,
            roleId = 1L,
            roleName = AppStrings.ui_test_device_batch_selection_default_role
        )
    }
}

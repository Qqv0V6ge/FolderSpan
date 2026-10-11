package com.folderspan.ui.screen.device

import com.folderspan.data.device.DeviceJoinDeviceRole
import com.folderspan.data.main.device.DeviceCategory

internal fun hasAllVisibleDevicesSelected(
    visibleDeviceIds: Set<String>,
    selectedDeviceIds: Set<String>
): Boolean {
    return visibleDeviceIds.isNotEmpty() && visibleDeviceIds.all(selectedDeviceIds::contains)
}

internal fun toggleAllVisibleDeviceIds(
    visibleDeviceIds: Set<String>,
    selectedDeviceIds: Set<String>
): Set<String> {
    return if (hasAllVisibleDevicesSelected(visibleDeviceIds, selectedDeviceIds)) {
        emptySet()
    } else {
        visibleDeviceIds
    }
}

internal fun retainVisibleDeviceSelection(
    selectedDeviceIds: Set<String>,
    visibleDeviceIds: Set<String>
): Set<String> {
    return selectedDeviceIds.intersect(visibleDeviceIds)
}

internal fun eligibleServerAccessDevices(
    selectedDeviceIds: Set<String>,
    accessDevicesById: Map<String, DeviceJoinDeviceRole>
): List<DeviceJoinDeviceRole> {
    return selectedDeviceIds.mapNotNull { deviceId ->
        accessDevicesById[deviceId]?.takeIf { device -> device.category == DeviceCategory.SERVER }
    }
}

package com.folderspan.service.http.client

import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.service.data.ConnectType
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpRouteClientManagerTest {

    @Test
    fun rejectedApprovalMapsToRejectedUiState() {
        assertEquals(
            ConnectType.Rejected,
            DeviceConnectType.REJECTED.toHttpConnectType(),
        )
        assertEquals(
            ConnectType.Rejected,
            DeviceConnectType.PERMANENTLY_BANNED.toHttpConnectType(),
        )
    }

    @Test
    fun onlyApprovedResponseMapsToConnectedUiState() {
        assertEquals(
            ConnectType.Connect,
            DeviceConnectType.APPROVED.toHttpConnectType(),
        )
        assertEquals(
            ConnectType.Fail,
            DeviceConnectType.WAITING.toHttpConnectType(),
        )
        assertEquals(
            ConnectType.Fail,
            DeviceConnectType.AUTO_CONNECT.toHttpConnectType(),
        )
    }
}

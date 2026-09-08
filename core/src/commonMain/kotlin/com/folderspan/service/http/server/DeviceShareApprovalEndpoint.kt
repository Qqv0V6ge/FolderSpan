package com.folderspan.service.http.server

const val DEFAULT_DEVICE_SHARE_APPROVAL_PORT = 12042

fun defaultDeviceShareApprovalPort(sessionPort: Int): Int {
    require(sessionPort in 1..65535) { "Invalid device Session port: $sessionPort" }
    if (sessionPort == 12040) return DEFAULT_DEVICE_SHARE_APPROVAL_PORT
    return if (sessionPort <= 65533) sessionPort + 2 else sessionPort - 2
}

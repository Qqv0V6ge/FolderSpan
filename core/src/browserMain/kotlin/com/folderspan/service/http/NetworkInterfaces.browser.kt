package com.folderspan.service.http

import com.folderspan.service.webui.WebUiAttachment

actual fun getNetworkInterfacesInfo(): List<NetworkInterfaceInfo> =
    checkNotNull(WebUiAttachment.hostInfo) { "Runtime login required" }.interfaces

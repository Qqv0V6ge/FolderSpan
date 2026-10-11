package com.folderspan.service.webui

actual fun createWebUiPlatform(): WebUiPlatform =
    error("The platform HTTP listener is available in the JVM runtime")

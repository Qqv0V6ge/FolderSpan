package com.folderspan.service.http.server

import strings.AppStrings

import com.folderspan.ui.state.file.FileShareState
import com.folderspan.utils.LogKit

actual class HttpShareFileServer private actual constructor(
    private val fileShareState: FileShareState
) : HttpShareFileServerInterface {

    actual override fun start(port: Int) {
        LogKit.w(AppStrings.ui_httpsharefileserver_currently_not_available_js_platform)
    }

    actual override suspend fun stop() {
        // JS 平台不执行文件分享，保持空实现
    }

    actual override fun isRunning(): Boolean = false

    actual companion object {
        actual fun getInstance(fileShareState: FileShareState): HttpShareFileServer =
            HttpShareFileServer(fileShareState)
    }
}

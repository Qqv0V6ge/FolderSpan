package com.folderspan.service.webui

import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.PathUtils
import java.nio.file.Files

/** File API tests exercise real files and sessions without enumerating network interfaces or TLS identities. */
internal class WebUiFileTestPlatform : WebUiPlatform by JvmWebUiPlatform() {
    override suspend fun hostInfo(session: WebUiPlatformSession): WebUiHostInfo {
        val cache = Files.createTempDirectory("webui-file-test-cache-").toRealPath()
        session.own(cache) { PathUtils.deleteDirectory(FileAccessPermission.Allowed, cache.toString()) }
        return WebUiHostInfo(
            separator = PathUtils.getPathSeparator(),
            home = PathUtils.getHomePath(),
            cache = cache.toString(),
            app = PathUtils.getAppPath(),
            roots = PathUtils.getRootPaths(FileAccessPermission.Allowed).getOrThrow(),
        )
    }
}

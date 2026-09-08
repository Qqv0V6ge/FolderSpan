package com.folderspan.service.http.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFalse

class FileShareServiceStartupPolicyTest {
    @Test
    fun fileShareServiceStartupDoesNotRegisterBrowserWebRtcHost() {
        listOf(
            "core/src/jvmMain/kotlin/com/folderspan/service/http/server/FileShareService.jvm.kt",
            "core/src/androidMain/kotlin/com/folderspan/service/http/server/FileShareService.android.kt",
            "core/src/iosMain/kotlin/com/folderspan/service/http/server/FileShareService.ios.kt",
            "core/src/jsMain/kotlin/com/folderspan/service/http/server/FileShareService.js.kt",
            "core/src/wasmJsMain/kotlin/com/folderspan/service/http/server/FileShareService.wasmJs.kt",
        ).forEach { relativePath ->
            val source = Files.readString(repoRoot().resolve(relativePath))

            assertFalse(
                source.contains("startBrowserWebRtcHostForPort("),
                "$relativePath should not auto-register Browser WebRTC signaling host on startup"
            )
        }
    }

    private fun repoRoot(): Path {
        return generateSequence(Paths.get("").toAbsolutePath()) { path -> path.parent }
            .first { path -> Files.exists(path.resolve("settings.gradle.kts")) }
    }
}

package com.folderspan.service.http.client

import kotlin.test.Test
import kotlin.test.assertTrue

class DiscoveryPingTrustAllSourceTest {
    @Test
    fun pingDeviceDoesNotSendAccessKeyOverCaptureTrustAllClient() {
        val deviceState = locateRepoFile("core/src/commonMain/kotlin/com/folderspan/ui/state/main/DeviceState.kt").readText()
        val jvmFactory = locateRepoFile(
            "core/src/jvmMain/kotlin/com/folderspan/service/http/client/HttpClientFactory.jvm.kt",
        ).readText()
        val androidFactory = locateRepoFile(
            "core/src/androidMain/kotlin/com/folderspan/service/http/client/HttpClientFactory.android.kt",
        ).readText()
        val iosFactory = locateRepoFile(
            "core/src/iosMain/kotlin/com/folderspan/service/http/client/HttpClientFactory.ios.kt",
        ).readText()

        val pingDevice = extractFunction(deviceState, "suspend fun pingDevice")
        assertTrue("applyFileShareAccessKey()" !in pingDevice, pingDevice)
        assertTrue("discoveryPingPool.withClient" in pingDevice, pingDevice)

        assertTrue("if (capture != null) return" in jvmFactory, jvmFactory)
        assertTrue("if (capture != null) return" in androidFactory, androidFactory)
        assertTrue("NSURLSessionAuthChallengeUseCredential" in iosFactory, iosFactory)
        assertTrue("TlsMode.CaptureHandshake" in iosFactory, iosFactory)
    }
}

private fun extractFunction(source: String, signature: String): String {
    val start = source.indexOf(signature)
    require(start >= 0) { "missing $signature" }
    var depth = 0
    var started = false
    for (index in start until source.length) {
        when (source[index]) {
            '{' -> {
                depth++
                started = true
            }
            '}' -> {
                depth--
                if (started && depth == 0) return source.substring(start, index + 1)
            }
        }
    }
    error("unterminated $signature")
}

private fun locateRepoFile(relativePath: String): java.io.File {
    var directory = java.io.File(System.getProperty("user.dir")).absoluteFile
    repeat(8) {
        val candidate = java.io.File(directory, relativePath)
        if (candidate.isFile) return candidate
        directory = directory.parentFile ?: return@repeat
    }
    error("missing $relativePath from ${System.getProperty("user.dir")}")
}

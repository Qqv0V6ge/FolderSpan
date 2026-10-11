@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.folderspan

import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.service.webui.WebUiBrowserHttp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeViewport
import com.folderspan.di.initKoin
import com.folderspan.notification.LocalNotifier
import com.folderspan.notification.initializeWebNotificationBackend
import com.folderspan.service.webrtc.download.WebRtcBrowserDownloadRegistry
import com.folderspan.service.webrtc.download.WebRtcBrowserDownloadSupport
import com.folderspan.ui.effects.ExternalFileDropEffect
import com.folderspan.ui.screen.webui.WebUiAttachedOverlay
import com.folderspan.ui.screen.webui.detectWebUiAttachment
import com.folderspan.ui.state.file.FileState
import com.mmk.kmpnotifier.KMPNotifier
import com.mmk.kmpnotifier.local.LocalNotifications
import com.mmk.kmpnotifier.notification.configuration.NotificationPlatformConfiguration
import com.folderspan.utils.installAppLogging
import kotlinx.browser.document
import org.koin.compose.koinInject

private var loggingInitialized = false
private var webRuntimeInitialized = false

private fun initLoggingOnce() {
    if (loggingInitialized) return
    installAppLogging()
    loggingInitialized = true
}

private fun installWebGlDebugRendererInfoGuard() {
    js(
        """
        (function () {
          var patch = function (proto) {
            if (!proto || proto.__fmGetParameterPatched) return;
            var originalGetParameter = proto.getParameter;
            if (typeof originalGetParameter !== "function") return;
            proto.getParameter = function (pname) {
              if (pname === 37445 || pname === 37446) {
                var ext = this.getExtension && this.getExtension("WEBGL_debug_renderer_info");
                if (!ext) return null;
              }
              return originalGetParameter.call(this, pname);
            };
            proto.__fmGetParameterPatched = true;
          };
          patch(globalThis.WebGLRenderingContext && globalThis.WebGLRenderingContext.prototype);
          patch(globalThis.WebGL2RenderingContext && globalThis.WebGL2RenderingContext.prototype);
        })();
        """
    )
}

fun main() {
    initLoggingOnce()
    installWebGlDebugRendererInfoGuard()
    val body = document.body
    if (body != null) {
        body.innerHTML = ""
        ComposeViewport(body) {
            WebApp()
        }
        return
    }
    ComposeViewport {
        WebApp()
    }
}

@Composable
private fun WebApp() {
    var bootstrapReady by remember { mutableStateOf(false) }
    var platformRuntimeReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        detectWebUiAttachment()
        initKoin()
        bootstrapReady = true
    }
    var authenticatedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(WebUiAttachment.hasSession) {
        if (WebUiAttachment.hasSession) authenticatedOnce = true
        else if (authenticatedOnce) {
            WebUiAttachment.target?.let {
                WebUiBrowserHttp.selectTargetAndReload(it.origin)
            }
        }
    }
    if (!bootstrapReady) return
    if (WebUiAttachment.hasSession) App(
        overlayContent = {
            if (platformRuntimeReady) {
                ExternalFileDropEffect(koinInject<FileState>())
            }
        },
        onFirstFrameRendered = {
            initializeWebRuntime()
            platformRuntimeReady = true
        },
    )
    WebUiAttachedOverlay()
}

private fun initializeWebRuntime() {
    if (webRuntimeInitialized) return
    webRuntimeInitialized = true
    KMPNotifier.initialize(
        NotificationPlatformConfiguration.Web(askNotificationPermissionOnStart = false),
        LocalNotifications,
    )
    initializeWebNotificationBackend()
    LocalNotifier.initialize(askPermissionOnStart = false)
    WebRtcBrowserDownloadRegistry.install(WebRtcBrowserDownloadSupport)
}

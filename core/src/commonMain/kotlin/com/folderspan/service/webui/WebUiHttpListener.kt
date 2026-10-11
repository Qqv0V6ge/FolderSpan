package com.folderspan.service.webui

import kotlinx.serialization.Serializable
import com.folderspan.service.http.server.linkshare.LinkShareHostFileRange

@Serializable
internal data class WebUiListenerOpen(val callbackId: String, val kind: String, val port: Int, val lanAccess: Boolean = true)

@Serializable
internal data class WebUiListenerStarted(val handle: String, val httpsPort: Int?, val fingerprint: String)

@Serializable
internal data class WebUiIncomingHttp(
    val method: String, val uri: String, val headers: Map<String, List<String>>,
    val remoteHost: String?, val scheme: String, val host: String, val port: Int,
    val contentLength: Long?,
)

@Serializable
internal data class WebUiOutgoingHttp(val status: Int, val headers: List<Pair<String, String>>, val contentLength: Long?, val hostFileRange: LinkShareHostFileRange? = null)

@Serializable
internal data class WebUiAcceptedDevice(val handle: String, val remoteHost: String?)

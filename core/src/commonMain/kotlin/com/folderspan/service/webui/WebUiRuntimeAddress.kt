package com.folderspan.service.webui

import io.ktor.http.URLProtocol
import io.ktor.http.Url

const val DEFAULT_WEB_UI_ADDRESS = "127.0.0.1:12043"

/** One root origin supplies every platform HTTP URL; credentials never enter URLs. */
class WebUiRuntimeAddress private constructor(val origin: String) {
    val plaintext: Boolean get() = origin.startsWith("http:")

    fun httpUrl(path: String): String {
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains('\\'))
        return origin + path
    }

    companion object {
        private fun validHost(value: String): Boolean {
            val host = value.removeSurrounding("[", "]")
            fun ipv4(value: String): Boolean = value.split('.').let { parts ->
                parts.size == 4 && parts.all { part ->
                    part.isNotEmpty() && part.all { it in '0'..'9' } && part.toIntOrNull() in 0..255
                }
            }
            if (':' in host) {
                if (host.count { it == ':' } < 2 || ":::" in host) return false
                val halves = host.split("::")
                if (halves.size > 2) return false
                val pieces = halves.flatMap { it.split(':').filter(String::isNotEmpty) }
                var units = 0
                for ((index, part) in pieces.withIndex()) {
                    if ('.' in part) {
                        if (index != pieces.lastIndex || !ipv4(part)) return false
                        units += 2
                    } else {
                        if (part.length !in 1..4 || part.any { it.digitToIntOrNull(16) == null }) return false
                        units++
                    }
                }
                return if (halves.size == 2) units < 8 else units == 8 && !host.startsWith(':') && !host.endsWith(':')
            }
            if (host.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it !in "-_." }) return false
            if (host.all { it in '0'..'9' || it == '.' }) return ipv4(host)
            return host.trimEnd('.').split('.').all { it.isNotBlank() && it.length <= 63 } && host.length <= 253
        }

        fun parse(value: String, securePage: Boolean = false): WebUiRuntimeAddress {
            val text = value.trim()
            require(text.isNotEmpty() && text.none { it.isWhitespace() || it in "\\@?#" }) {
                "Runtime address must not contain credentials, a query or a fragment"
            }
            val qualified = if ("://" in text) text else "${if (securePage) "https" else "http"}://$text"
            val authority = qualified.substringAfter("://").substringBefore('/')
            val portText = if (authority.startsWith('[')) authority.substringAfter(']', "").removePrefix(":")
                else authority.substringAfter(':', "")
            // Ktor represents both an absent port and explicit :0 as DEFAULT_PORT.
            require(!authority.endsWith(':') && (portText.isEmpty() || portText.toIntOrNull() in 1..65535)) {
                "Invalid runtime port"
            }
            val url = Url(qualified)
            require(!authority.startsWith('[') || ':' in url.host) { "Brackets require an IPv6 address" }
            require(url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS) { "Use HTTP or HTTPS" }
            require(validHost(url.host) && url.port in 1..65535) { "Invalid runtime host or port" }
            require(url.encodedPath.isEmpty() || url.encodedPath == "/") { "Use the runtime root address" }
            require(!qualified.substringAfter("://").substringBefore('/').endsWith(':')) { "A port is required after ':'" }
            require(!securePage || url.protocol == URLProtocol.HTTPS) { "An HTTPS page requires an HTTPS runtime" }
            return WebUiRuntimeAddress(url.toString().removeSuffix("/"))
        }
    }
}

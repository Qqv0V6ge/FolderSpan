package com.folderspan.service.webui

import io.ktor.http.parseQueryString
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val WEB_UI_SOCKET_PATH = "/api/web-ui/socket"
internal const val WEB_UI_WIRE_HEADER_LIMIT = 32 * 1024
internal const val WEB_UI_WIRE_BODY_LIMIT = WEB_UI_FILE_STREAM_CHUNK_BYTES
internal const val WEB_UI_WIRE_LIMIT = 4 + WEB_UI_WIRE_HEADER_LIMIT + WEB_UI_WIRE_BODY_LIMIT
internal const val WEB_UI_REQUEST_LIMIT = 32

/** Fixed platform routes, correlation and resource metadata; the body stays binary. */
@Serializable
internal data class WebUiWireHeader(
    val type: String,
    val id: Long = 0,
    val method: String = "",
    val path: String = "",
    val contentType: String = "",
    val status: Int = 0,
    val headers: Map<String, String> = emptyMap(),
    val handle: String = "",
    val eventType: String = "",
    val requiresReply: Boolean = false,
    val token: String = "",
    val version: Int = WEB_UI_PROTOCOL_VERSION,
)

internal data class WebUiWirePacket(val header: WebUiWireHeader, val body: ByteArray = byteArrayOf())

internal object WebUiWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(packet: WebUiWirePacket): ByteArray {
        val header = json.encodeToString(packet.header).encodeToByteArray()
        require(header.size in 1..WEB_UI_WIRE_HEADER_LIMIT && packet.body.size <= WEB_UI_WIRE_BODY_LIMIT) { "management message too large" }
        return ByteArray(4 + header.size + packet.body.size).also { bytes ->
            for (i in 0..3) bytes[i] = (header.size ushr (24 - i * 8)).toByte()
            header.copyInto(bytes, 4)
            packet.body.copyInto(bytes, 4 + header.size)
        }
    }

    fun decode(bytes: ByteArray): WebUiWirePacket {
        require(bytes.size in 5..WEB_UI_WIRE_LIMIT) { "invalid management message size" }
        var length = 0
        for (i in 0..3) length = (length shl 8) or (bytes[i].toInt() and 255)
        require(length in 1..WEB_UI_WIRE_HEADER_LIMIT && length <= bytes.size - 4 && bytes.size - 4 - length <= WEB_UI_WIRE_BODY_LIMIT) { "invalid management header size" }
        return WebUiWirePacket(json.decodeFromString(bytes.decodeToString(4, 4 + length)), bytes.copyOfRange(4 + length, bytes.size))
    }
}

internal fun isWebUiBodyRoute(method: String, path: String): Boolean = when (path.substringBefore('?')) {
    "$WEB_UI_PLATFORM_PATH/devices/transport" -> parseQueryString(path.substringAfter('?', ""))["operation"] in setOf("body", "data")
    "$WEB_UI_PLATFORM_PATH/listeners/body" -> method == "GET" || method == "PUT"
    "$WEB_UI_PLATFORM_PATH/files/content" -> true
    "$WEB_UI_PLATFORM_PATH/files/append" -> method == "POST"
    "$WEB_UI_PLATFORM_PATH/networks/downloads", "$WEB_UI_PLATFORM_PATH/http/download", "$WEB_UI_PLATFORM_PATH/devices/read" -> method == "GET"
    "$WEB_UI_PLATFORM_PATH/networks/uploads", "$WEB_UI_PLATFORM_PATH/http/upload", "$WEB_UI_PLATFORM_PATH/compression" -> method == "PUT" || method == "GET"
    "$WEB_UI_PLATFORM_PATH/devices/write" -> true
    else -> false
}

internal fun isWebUiCommandRoute(method: String, path: String): Boolean =
    path.startsWith("$WEB_UI_PLATFORM_PATH/") && !isWebUiBodyRoute(method, path) &&
        !path.contains('#') && !path.substringBefore('?').contains('\\') &&
        path.substringBefore('?').split('/').none { it == ".." || it == "." }

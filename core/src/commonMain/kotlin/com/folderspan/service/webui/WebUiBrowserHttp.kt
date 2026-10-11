package com.folderspan.service.webui

data class WebUiHttpExchange(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = byteArrayOf(),
)

expect object WebUiBrowserHttp {
    suspend fun exchange(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
    ): WebUiHttpExchange

    /** Null or a legacy empty value uses bootstrap/default selection. Only an address is persisted. */
    fun selectedTarget(): String?

    fun selectTargetAndReload(address: String)

    fun origin(): String

    fun isPlainHttp(): Boolean

    suspend fun probeBootstrap(): Boolean
}

fun encodeWebUiQuery(value: String): String {
    val bytes = value.encodeToByteArray()
    return buildString(bytes.size * 3) {
        bytes.forEach { byte ->
            val code = byte.toInt() and 0xff
            val character = code.toChar()
            if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "-_.~") {
                append(character)
            } else {
                append('%')
                append(code.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}

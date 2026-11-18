package com.folderspan.data.main.webrtc

import com.folderspan.service.webrtc.models.isWebRtcHost
import com.folderspan.service.webrtc.models.isWebRtcIceUri
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

@Serializable
data class WebRtcDirectInvite(
    val deviceId: String,
    val host: String,
    val port: Int,
    val fingerprintSha256: String,
    val credential: String,
    val expiresAtEpochMillis: Long,
    val stunUrl: String = "",
) {
    override fun toString(): String = "WebRtcDirectInvite(<redacted>)"
}

@Serializable
data class OfficialWebRtcRoomCode(
    val source: String,
    val name: String,
    val roomId: String,
    val wssUrl: String,
    val stunUrl: String = "",
    val turnUrls: List<String> = emptyList(),
    val turnUsername: String = "",
    val turnPassword: String = "",
) {
    override fun toString(): String = "OfficialWebRtcRoomCode(<redacted>)"
}

enum class WebRtcShareCodeError {
    Invalid, TooLarge, WrongKind, UnsupportedVersion, Expired, UntrustedGateway,
}

class WebRtcShareCodeException(val reason: WebRtcShareCodeError) : IllegalArgumentException(reason.name)

enum class WebRtcDirectFailure { Contact, Handshake, Ice }

class WebRtcDirectConnectionException(val failure: WebRtcDirectFailure, cause: Throwable? = null) :
    IllegalStateException(failure.name, cause)

object WebRtcShareCode {
    private const val VERSION = 1
    private const val DIRECT_INVITE = "direct-invite"
    private const val OFFICIAL_ROOM = "official-room"
    private const val MAX_CODE_LENGTH = 8192
    private const val MAX_JSON_BYTES = 6144
    private const val MAX_ICE_LENGTH = 255
    private val fingerprintPattern = Regex("[A-Fa-f0-9]{64}")
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        encodeDefaults = true
        exceptionsWithDebugInfo = false
        maxNestingDepth = 4
    }

    fun encodeDirectInvite(invite: WebRtcDirectInvite): String {
        validateDirectInvite(invite)
        return encode(DIRECT_INVITE, WebRtcDirectInvite.serializer(), invite)
    }

    fun decodeDirectInvite(
        code: String,
        nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): WebRtcDirectInvite {
        val fields = decode(code, DIRECT_INVITE)
        if ((fields["port"] as? JsonPrimitive)?.isString != false ||
            (fields["expiresAtEpochMillis"] as? JsonPrimitive)?.isString != false
        ) invalid()
        val invite = parse { json.decodeFromJsonElement(WebRtcDirectInvite.serializer(), fields) }
        validateDirectInvite(invite)
        if (invite.expiresAtEpochMillis <= nowMillis) fail(WebRtcShareCodeError.Expired)
        return invite
    }

    fun encodeOfficialRoom(room: OfficialWebRtcRoomCode): String {
        validateRoom(room)
        return encode(OFFICIAL_ROOM, OfficialWebRtcRoomCode.serializer(), room)
    }

    fun decodeOfficialRoom(code: String, trustedGatewayUrl: String): OfficialWebRtcRoomCode {
        val fields = decode(code, OFFICIAL_ROOM)
        val room = parse { json.decodeFromJsonElement(OfficialWebRtcRoomCode.serializer(), fields) }
        validateRoom(room)
        if (room.wssUrl != trustedGatewayUrl) fail(WebRtcShareCodeError.UntrustedGateway)
        return room
    }

    internal fun validateDirectInvite(invite: WebRtcDirectInvite) {
        if (!validText(invite.deviceId, 128) || invite.deviceId.any { it.isWhitespace() || it.isISOControl() } ||
            !isWebRtcHost(invite.host) || invite.port !in 1..65535 ||
            !fingerprintPattern.matches(invite.fingerprintSha256) || !isToken32(invite.credential) ||
            invite.expiresAtEpochMillis <= 0 || !validStun(invite.stunUrl)
        ) invalid()
    }

    private fun validateRoom(room: OfficialWebRtcRoomCode) {
        if (room.source != "official" || !validText(room.name, OfficialRoomNameMaxLength) ||
            room.name.any(Char::isISOControl) || !isToken32(room.roomId) ||
            !validGateway(room.wssUrl) || !validStun(room.stunUrl)
        ) invalid()

        if (room.turnUrls.isEmpty()) {
            if (room.turnUsername.isNotEmpty() || room.turnPassword.isNotEmpty()) invalid()
        } else if (room.turnUrls.size - 1 + room.turnUrls.sumOf(String::length) > MAX_ICE_LENGTH ||
            !room.turnUrls.all { isWebRtcIceUri(it, turn = true) } ||
            !validText(room.turnUsername, 255) || !validText(room.turnPassword, 255)
        ) invalid()
    }

    private fun validStun(value: String): Boolean =
        value.isEmpty() || (value.length <= MAX_ICE_LENGTH && isWebRtcIceUri(value, turn = false))

    private fun validGateway(value: String): Boolean {
        if (!value.startsWith("wss://") || value.any { it.isWhitespace() || it.isISOControl() || it in "?#\\" }) {
            return false
        }
        val url = runCatching { Url(value) }.getOrNull() ?: return false
        return url.protocol == URLProtocol.WSS && url.port in 1..65535 && url.user == null && url.password == null &&
            isWebRtcHost(url.host.removeSurrounding("[", "]"))
    }

    private fun validText(value: String, limit: Int): Boolean {
        if (value.isBlank() || value.length > limit * 2) return false
        val bytes = runCatching { value.encodeToByteArray(throwOnInvalidSequence = true) }.getOrNull() ?: return false
        return bytes.count { it.toInt() and 0xc0 != 0x80 } <= limit
    }

    private fun isToken32(value: String): Boolean = value.length == 43 && runCatching {
        val bytes = base64.decode(value)
        bytes.size == 32 && base64.encode(bytes) == value
    }.getOrDefault(false)

    private fun <T> encode(kind: String, serializer: KSerializer<T>, value: T): String {
        val payload = json.encodeToJsonElement(serializer, value).jsonObject
        val fields = linkedMapOf<String, JsonElement>("version" to JsonPrimitive(VERSION), "kind" to JsonPrimitive(kind))
        fields.putAll(payload)
        val bytes = json.encodeToString(JsonObject.serializer(), JsonObject(fields)).encodeToByteArray()
        if (bytes.size > MAX_JSON_BYTES) fail(WebRtcShareCodeError.TooLarge)
        return base64.encode(bytes)
    }

    private fun decode(code: String, expectedKind: String): JsonObject {
        if (code.length > MAX_CODE_LENGTH) fail(WebRtcShareCodeError.TooLarge)
        val fields = parse {
            val bytes = base64.decode(code.trim())
            if (bytes.size > MAX_JSON_BYTES) fail(WebRtcShareCodeError.TooLarge)
            json.decodeFromString(
                MapSerializer(UniqueFieldName(), JsonElement.serializer()),
                bytes.decodeToString(throwOnInvalidSequence = true),
            )
        }
        val version = (fields["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: invalid()
        if (version != VERSION) fail(WebRtcShareCodeError.UnsupportedVersion)
        val kind = (fields["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
        if (kind != expectedKind) {
            if (kind == DIRECT_INVITE || kind == OFFICIAL_ROOM) fail(WebRtcShareCodeError.WrongKind)
            invalid()
        }
        return JsonObject(fields - "version" - "kind")
    }

    // JsonObject alone would silently keep only the last occurrence of a field.
    private class UniqueFieldName : KSerializer<String> by String.serializer() {
        private val seen = mutableSetOf<String>()

        override fun deserialize(decoder: Decoder): String = decoder.decodeString().also {
            if (!seen.add(it)) invalid()
        }
    }

    private inline fun <T> parse(decode: () -> T): T = try {
        decode()
    } catch (error: WebRtcShareCodeException) {
        throw error
    } catch (_: IllegalArgumentException) {
        invalid()
    } catch (_: CharacterCodingException) {
        invalid()
    }

    private fun invalid(): Nothing = fail(WebRtcShareCodeError.Invalid)

    private fun fail(reason: WebRtcShareCodeError): Nothing = throw WebRtcShareCodeException(reason)
}

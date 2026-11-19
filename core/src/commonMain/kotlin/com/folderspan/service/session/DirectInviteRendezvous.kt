package com.folderspan.service.session

import com.folderspan.service.http.client.applyConfiguredHttpProxy
import com.folderspan.utils.AesGcm
import com.folderspan.utils.secureRandomBytes
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import korlibs.crypto.sha256
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64

internal const val DIRECT_INVITE_RENDEZVOUS_URL = "wss://api.folderspan.com/api/v1/webrtc/invites"
private const val PACKET_BYTES = 48 * 1024
private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

internal suspend fun openDirectInviteRendezvous(
    url: String,
    credential: String,
    issuer: Boolean,
): DeviceSessionByteChannel {
    val keys = RendezvousKeys(credential)
    val client = HttpClient {
        applyConfiguredHttpProxy()
        followRedirects = false
        install(WebSockets) { maxFrameSize = (PACKET_BYTES + AesGcm.NONCE_BYTES + AesGcm.TAG_BYTES).toLong() }
    }
    try {
        return withTimeout(8_000L) {
            val socket = client.webSocketSession(url)
            val prefix = if (issuer) "issue" else "join"
            val proof = if (issuer) base64.encode(keys.joinToken.encodeToByteArray().sha256().bytes) else keys.joinToken
            socket.send("$prefix:${keys.ticket}:$proof")
            val acknowledgement = socket.incoming.receiveCatching().getOrNull()
            check(acknowledgement is Frame.Text && acknowledgement.readText() == "ok") {
                "invitation signaling is unavailable"
            }
            RendezvousChannel(client, socket, keys, issuer)
        }
    } catch (error: Throwable) {
        client.coroutineContext.cancel()
        client.close()
        throw error
    }
}

private class RendezvousKeys(credential: String) {
    private val seed = base64.decode(credential)

    private fun derive(purpose: String): ByteArray =
        ("FolderSpan.InviteSignal.v1.$purpose".encodeToByteArray() + seed).sha256().bytes

    val ticket = base64.encode(derive("ticket"))
    val joinToken = base64.encode(derive("join"))
    val key = derive("encrypted-frame")
}

private class RendezvousChannel(
    private val client: HttpClient,
    private val socket: DefaultClientWebSocketSession,
    private val keys: RendezvousKeys,
    private val issuer: Boolean,
) : DeviceSessionByteChannel {
    private var sendSequence = 0L
    private var receiveSequence = 0L
    private var pending = ByteArray(0)
    private var pendingOffset = 0

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (pendingOffset == pending.size) {
            val frame = socket.incoming.receiveCatching().getOrNull() ?: return -1
            check(frame is Frame.Binary && frame.data.size in
                (AesGcm.NONCE_BYTES + AesGcm.TAG_BYTES + 1)..(PACKET_BYTES + AesGcm.NONCE_BYTES + AesGcm.TAG_BYTES)) {
                "invalid invitation signaling frame"
            }
            val data = frame.data
            pending = AesGcm.decrypt(keys.key, data.copyOfRange(0, AesGcm.NONCE_BYTES),
                data.copyOfRange(AesGcm.NONCE_BYTES, data.size), context(!issuer, receiveSequence++))
            pendingOffset = 0
        }
        val count = minOf(length, pending.size - pendingOffset)
        pending.copyInto(buffer, offset, pendingOffset, pendingOffset + count)
        pendingOffset += count
        return count
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        var position = offset
        val end = offset + length
        while (position < end) {
            val next = minOf(end, position + PACKET_BYTES)
            val nonce = secureRandomBytes(AesGcm.NONCE_BYTES)
            val sealed = AesGcm.encrypt(keys.key, nonce, buffer.copyOfRange(position, next),
                context(issuer, sendSequence++))
            socket.send(Frame.Binary(true, nonce + sealed))
            position = next
        }
    }

    override suspend fun flush() = Unit

    override fun close() {
        socket.coroutineContext.cancel()
        client.coroutineContext.cancel()
        client.close()
    }

    private fun context(fromIssuer: Boolean, sequence: Long): ByteArray =
        "${keys.ticket}:$fromIssuer:$sequence".encodeToByteArray()
}

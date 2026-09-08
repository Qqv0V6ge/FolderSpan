package com.folderspan.service.webrtc.models

import android.os.Handler
import android.os.Looper
import com.shepeliev.webrtckmp.WebRtc
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Creates the owned DataChannel in the native callback, before any payload can arrive. */
actual class WebRtcPeerConnection actual constructor(iceServers: List<WebRtcIceServerConfig>) {
    private val closed = AtomicBoolean(false)
    private val nativeLock = ReentrantReadWriteLock()
    private val channels = CopyOnWriteArrayList<AndroidWebRtcDataChannel>()
    private val incoming = Channel<WebRtcDataChannel>(capacity = 1)
    private val candidates = Channel<WebRtcIceCandidate>(capacity = 64)
    private val connectionState = MutableStateFlow(WebRtcPeerConnectionState.New)

    actual val onIceCandidate: Flow<WebRtcIceCandidate> = candidates.receiveAsFlow()
    actual val onDataChannel: Flow<WebRtcDataChannel> = incoming.receiveAsFlow()
    actual val onConnectionStateChange: Flow<WebRtcPeerConnectionState> = connectionState

    private val native: PeerConnection = checkNotNull(factory.createPeerConnection(
        PeerConnection.RTCConfiguration(iceServers.map { server ->
            PeerConnection.IceServer.builder(server.urls)
                .setUsername(server.username)
                .setPassword(server.password)
                .createIceServer()
        }).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN },
        object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onRenegotiationNeeded() = Unit

            override fun onIceCandidate(candidate: IceCandidate) {
                if (!closed.get() && !candidates.trySend(WebRtcIceCandidate(candidate.sdpMid.orEmpty(), candidate.sdpMLineIndex, candidate.sdp)).isSuccess) close()
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                val next = when (state) {
                    PeerConnection.PeerConnectionState.NEW -> WebRtcPeerConnectionState.New
                    PeerConnection.PeerConnectionState.CONNECTING -> WebRtcPeerConnectionState.Connecting
                    PeerConnection.PeerConnectionState.CONNECTED -> WebRtcPeerConnectionState.Connected
                    PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcPeerConnectionState.Disconnected
                    PeerConnection.PeerConnectionState.FAILED -> WebRtcPeerConnectionState.Failed
                    PeerConnection.PeerConnectionState.CLOSED -> WebRtcPeerConnectionState.Closed
                }
                connectionState.update { if (closed.get()) WebRtcPeerConnectionState.Closed else next }
            }

            override fun onDataChannel(channel: org.webrtc.DataChannel) {
                fun discard() {
                    Handler(Looper.getMainLooper()).post { channel.dispose() }
                }
                if (closed.get()) { discard(); return }
                val read = nativeLock.readLock()
                if (!read.tryLock()) { discard(); return }
                try {
                    if (closed.get()) { discard(); return }
                    val owned = ownChannel(channel) ?: return
                    if (closed.get() || !incoming.trySend(owned).isSuccess) owned.close()
                } finally {
                    read.unlock()
                }
            }
        },
    )) { "Creating Android WebRTC PeerConnection failed" }

    actual suspend fun createOffer(): WebRtcSessionDescription = createDescription { observer ->
        native.createOffer(observer, MediaConstraints())
    }

    actual suspend fun createAnswer(): WebRtcSessionDescription = createDescription { observer ->
        native.createAnswer(observer, MediaConstraints())
    }

    private suspend fun createDescription(start: (SdpObserver) -> Unit): WebRtcSessionDescription =
        suspendCancellableCoroutine { continuation ->
            nativeLock.read {
                check(!closed.get()) { "WebRTC PeerConnection is closed" }
                start(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) {
                        continuation.resume(WebRtcSessionDescription(
                            if (description.type == SessionDescription.Type.OFFER) WebRtcSdpType.Offer else WebRtcSdpType.Answer,
                            description.description,
                        ))
                    }
                    override fun onCreateFailure(error: String) = continuation.resumeWithException(IllegalStateException(error))
                    override fun onSetSuccess() = Unit
                    override fun onSetFailure(error: String) = Unit
                })
            }
        }

    actual suspend fun setLocalDescription(description: WebRtcSessionDescription) = setDescription(description, true)
    actual suspend fun setRemoteDescription(description: WebRtcSessionDescription) = setDescription(description, false)

    private suspend fun setDescription(description: WebRtcSessionDescription, local: Boolean): Unit =
        suspendCancellableCoroutine { continuation ->
            val sdp = SessionDescription(
                if (description.type == WebRtcSdpType.Offer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER,
                description.sdp,
            )
            val observer = object : SdpObserver {
                override fun onSetSuccess() = continuation.resume(Unit)
                override fun onSetFailure(error: String) = continuation.resumeWithException(IllegalStateException(error))
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onCreateFailure(error: String) = Unit
            }
            nativeLock.read {
                check(!closed.get()) { "WebRTC PeerConnection is closed" }
                if (local) native.setLocalDescription(observer, sdp) else native.setRemoteDescription(observer, sdp)
            }
        }

    actual suspend fun addIceCandidate(candidate: WebRtcIceCandidate) {
        nativeLock.read {
            check(!closed.get()) { "WebRTC PeerConnection is closed" }
            check(native.addIceCandidate(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate))) {
                "WebRTC rejected ICE candidate"
            }
        }
    }

    actual fun createDataChannel(label: String, ordered: Boolean, maxRetransmits: Int?): WebRtcDataChannel? =
        nativeLock.read {
            if (closed.get()) return@read null
            native.createDataChannel(label, org.webrtc.DataChannel.Init().apply {
                this.ordered = ordered
                this.maxRetransmits = maxRetransmits ?: -1
            })?.let { channel ->
                ownChannel(channel)
            }
        }

    private fun ownChannel(channel: org.webrtc.DataChannel): AndroidWebRtcDataChannel? {
        val owned = AndroidWebRtcDataChannel(channel)
        val retained = synchronized(channels) {
            channels.removeAll { it.state == WebRtcDataChannelState.Closed }
            if (closed.get() || channels.size >= 8) false else { channels.add(owned); true }
        }
        if (!retained) {
            owned.close()
            return null
        }
        if (closed.get()) owned.close()
        return owned
    }

    actual fun close() {
        if (!closed.compareAndSet(false, true)) return
        connectionState.value = WebRtcPeerConnectionState.Closed
        incoming.cancel()
        candidates.cancel()
        channels.forEach { it.close() }
        channels.clear()
        // Do not dispose a PeerConnection from its own native callback stack.
        Handler(Looper.getMainLooper()).post { nativeLock.write { native.dispose() } }
    }

    private companion object {
        val factory by lazy { WebRtc.createPeerConnectionFactoryBuilder().createPeerConnectionFactory() }
    }
}

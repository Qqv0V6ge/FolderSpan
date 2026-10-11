package com.folderspan.service.webrtc.models

import android.os.Handler
import android.os.Looper
import com.folderspan.service.webrtc.WebRtcDeviceSessionCarrierLimits
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.webrtc.DataChannel as NativeDataChannel

/** Owns received bytes before the native callback returns and releases its borrowed buffer. */
internal class AndroidWebRtcDataChannel(private val native: NativeDataChannel) : WebRtcDataChannel {
    private val limits = WebRtcDeviceSessionCarrierLimits()
    private val incoming = Channel<ByteArray>(capacity = limits.maxInboundMessages)
    private val closed = AtomicBoolean(false)
    private val nativeLock = ReentrantReadWriteLock()
    private val channelState = MutableStateFlow(native.state().toWebRtcState())
    override val label: String = native.label()
    override val state: WebRtcDataChannelState get() = channelState.value
    override val bufferedAmount: Long get() = nativeLock.read {
        if (closed.get()) 0L else native.bufferedAmount()
    }
    override val onOpen: Flow<Unit> = channelState.filter { it == WebRtcDataChannelState.Open }.map { }
    override val onClose: Flow<Unit> = channelState.filter { it == WebRtcDataChannelState.Closed }.map { }
    override val onMessage: Flow<ByteArray> = incoming.receiveAsFlow()

    init {
        // Install while still in PeerConnection.Observer.onDataChannel.
        native.registerObserver(object : NativeDataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                inNativeCallback {
                    val next = native.state().toWebRtcState()
                    channelState.update { if (closed.get()) WebRtcDataChannelState.Closed else next }
                    if (next == WebRtcDataChannelState.Closed) close()
                }
            }

            override fun onMessage(buffer: NativeDataChannel.Buffer) {
                inNativeCallback {
                    val source = buffer.data.duplicate()
                    if (!buffer.binary || source.remaining() > limits.maxInboundMessageBytes) {
                        close()
                        return@inNativeCallback
                    }
                    // No suspension: the copy must complete before this callback returns.
                    val owned = ByteArray(source.remaining())
                    source.get(owned)
                    if (!incoming.trySend(owned).isSuccess) close()
                }
            }
        })
        nativeLock.read {
            if (!closed.get()) {
                val next = native.state().toWebRtcState()
                channelState.update { if (closed.get()) WebRtcDataChannelState.Closed else next }
            }
        }
        if (channelState.value == WebRtcDataChannelState.Closed) close()
    }

    override fun send(data: ByteArray): Boolean = nativeLock.read {
        !closed.get() && native.send(NativeDataChannel.Buffer(ByteBuffer.wrap(data), true))
    }

    private inline fun inNativeCallback(block: () -> Unit) {
        if (closed.get()) return
        val read = nativeLock.readLock()
        // Never block a native callback behind disposal, which can itself wait for it.
        if (!read.tryLock()) return
        try { if (!closed.get()) block() } finally { read.unlock() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        channelState.value = WebRtcDataChannelState.Closed
        incoming.cancel()
        // Leave the current callback stack; the lock also excludes in-flight native work.
        Handler(Looper.getMainLooper()).post {
            nativeLock.write {
                try {
                    native.unregisterObserver()
                } finally {
                    try { native.close() } finally { native.dispose() }
                }
            }
        }
    }
}

private fun NativeDataChannel.State.toWebRtcState() = when (this) {
    NativeDataChannel.State.CONNECTING -> WebRtcDataChannelState.Connecting
    NativeDataChannel.State.OPEN -> WebRtcDataChannelState.Open
    NativeDataChannel.State.CLOSING -> WebRtcDataChannelState.Closing
    NativeDataChannel.State.CLOSED -> WebRtcDataChannelState.Closed
}

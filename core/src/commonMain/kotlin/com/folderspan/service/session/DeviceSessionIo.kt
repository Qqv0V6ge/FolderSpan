package com.folderspan.service.session

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal interface DeviceSessionByteChannel {
    // Blocking carriers keep the whole frame loop on their I/O dispatcher.
    val ioContext: CoroutineContext
        get() = EmptyCoroutineContext

    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    suspend fun write(buffer: ByteArray, offset: Int, length: Int)
    suspend fun flush()

    suspend fun writeAndFlush(buffer: ByteArray, offset: Int, length: Int) {
        write(buffer, offset, length)
        flush()
    }

    fun close()
}

internal class DeviceSessionIoException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal object DeviceSessionIo {
    suspend fun readExact(channel: DeviceSessionByteChannel, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = channel.read(buffer, offset, length - offset)
            if (read <= 0) throw DeviceSessionIoException("session stream closed")
            offset += read
        }
        return buffer
    }

    suspend fun writeFully(channel: DeviceSessionByteChannel, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        channel.writeAndFlush(bytes, 0, bytes.size)
    }

    suspend fun readFrame(channel: DeviceSessionByteChannel): DeviceSessionFrame {
        val header = DeviceSessionFrames.readHeader(readExact(channel, DEVICE_SESSION_HEADER_SIZE))
        val payload = readExact(channel, header.payloadLength)
        return DeviceSessionFrame(header.type, header.streamId, payload)
    }

    suspend fun writeFrame(channel: DeviceSessionByteChannel, frame: DeviceSessionFrame) {
        writeFully(channel, DeviceSessionFrames.encode(frame))
    }

    suspend fun writeFrames(channel: DeviceSessionByteChannel, frames: List<DeviceSessionFrame>) {
        if (frames.size == 1) {
            writeFrame(channel, frames.single())
            return
        }
        writeFully(channel, DeviceSessionFrames.encodeAll(frames))
    }
}

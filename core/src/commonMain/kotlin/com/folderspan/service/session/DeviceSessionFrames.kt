package com.folderspan.service.session

internal const val DEVICE_SESSION_ALPN = "folderspan/1"
internal const val DEVICE_SESSION_HEADER_SIZE = 1 + 4 + 4
internal const val DEVICE_SESSION_MAX_PAYLOAD_BYTES = 64 * 1024
internal const val DEVICE_SESSION_CONTROL_STREAM_ID = 0
internal const val DEVICE_SESSION_MAX_ACTIVE_STREAMS = 256

internal enum class DeviceSessionFrameType(val code: Int) {
    WindowUpdate(1),
    Open(2),
    Data(3),
    Trailer(4),
    Rst(5),
    Ping(6),
    Pong(7),
    GoAway(8),
    ;

    companion object {
        fun fromCode(code: Int): DeviceSessionFrameType? = entries.firstOrNull { it.code == code }
    }
}

internal enum class DeviceSessionProtocolError {
    Truncated,
    OversizedPayload,
    UnknownType,
    InvalidStreamId,
}

internal data class DeviceSessionFrame(
    val type: DeviceSessionFrameType,
    val streamId: Int,
    val payload: ByteArray = ByteArray(0),
) {
    val payloadSize: Int
        get() = payload.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceSessionFrame) return false
        return type == other.type && streamId == other.streamId && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + streamId
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

internal sealed class DeviceSessionDecodeResult {
    data class Frame(
        val frame: DeviceSessionFrame,
        val nextIndex: Int,
    ) : DeviceSessionDecodeResult()

    data class Invalid(
        val error: DeviceSessionProtocolError,
    ) : DeviceSessionDecodeResult()
}

internal data class DeviceSessionFrameHeader(
    val type: DeviceSessionFrameType,
    val streamId: Int,
    val payloadLength: Int,
)

internal object DeviceSessionFrames {
    fun encode(frame: DeviceSessionFrame): ByteArray {
        require(frame.streamId >= 0) { "streamId must be non-negative" }
        require(frame.payload.size <= DEVICE_SESSION_MAX_PAYLOAD_BYTES) {
            "payload exceeds $DEVICE_SESSION_MAX_PAYLOAD_BYTES bytes"
        }
        val buffer = ByteArray(DEVICE_SESSION_HEADER_SIZE + frame.payload.size)
        buffer[0] = frame.type.code.toByte()
        writeIntLe(buffer, 1, frame.streamId)
        writeIntLe(buffer, 5, frame.payload.size)
        if (frame.payload.isNotEmpty()) {
            frame.payload.copyInto(buffer, destinationOffset = DEVICE_SESSION_HEADER_SIZE)
        }
        return buffer
    }

    fun encodeAll(frames: List<DeviceSessionFrame>): ByteArray {
        if (frames.isEmpty()) return ByteArray(0)
        val totalBytes = frames.sumOf { frame ->
            require(frame.streamId >= 0) { "streamId must be non-negative" }
            require(frame.payload.size <= DEVICE_SESSION_MAX_PAYLOAD_BYTES) {
                "payload exceeds $DEVICE_SESSION_MAX_PAYLOAD_BYTES bytes"
            }
            DEVICE_SESSION_HEADER_SIZE.toLong() + frame.payload.size.toLong()
        }
        require(totalBytes <= Int.MAX_VALUE.toLong()) { "encoded frame batch is too large" }
        val buffer = ByteArray(totalBytes.toInt())
        var offset = 0
        frames.forEach { frame ->
            buffer[offset] = frame.type.code.toByte()
            writeIntLe(buffer, offset + 1, frame.streamId)
            writeIntLe(buffer, offset + 5, frame.payload.size)
            if (frame.payload.isNotEmpty()) {
                frame.payload.copyInto(buffer, destinationOffset = offset + DEVICE_SESSION_HEADER_SIZE)
            }
            offset += DEVICE_SESSION_HEADER_SIZE + frame.payload.size
        }
        return buffer
    }

    fun decode(
        buffer: ByteArray,
        startIndex: Int = 0,
        endIndexExclusive: Int = buffer.size,
    ): DeviceSessionDecodeResult {
        val header = try {
            readHeader(buffer, startIndex, endIndexExclusive)
        } catch (error: DeviceSessionFrameException) {
            return DeviceSessionDecodeResult.Invalid(error.error)
        }
        val payloadLength = header.payloadLength
        val frameEnd = startIndex + DEVICE_SESSION_HEADER_SIZE + payloadLength
        if (frameEnd > endIndexExclusive) {
            return DeviceSessionDecodeResult.Invalid(DeviceSessionProtocolError.Truncated)
        }
        val payload = if (payloadLength == 0) {
            ByteArray(0)
        } else {
            buffer.copyOfRange(startIndex + DEVICE_SESSION_HEADER_SIZE, frameEnd)
        }
        return DeviceSessionDecodeResult.Frame(
            frame = DeviceSessionFrame(type = header.type, streamId = header.streamId, payload = payload),
            nextIndex = frameEnd,
        )
    }

    // Validate metadata before a streaming reader allocates or waits for the payload.
    fun readHeader(
        buffer: ByteArray,
        startIndex: Int = 0,
        endIndexExclusive: Int = buffer.size,
    ): DeviceSessionFrameHeader {
        if (startIndex < 0 || endIndexExclusive > buffer.size || startIndex > endIndexExclusive) {
            throw DeviceSessionFrameException(DeviceSessionProtocolError.Truncated)
        }
        val available = endIndexExclusive - startIndex
        if (available < DEVICE_SESSION_HEADER_SIZE) {
            throw DeviceSessionFrameException(DeviceSessionProtocolError.Truncated)
        }
        val type = DeviceSessionFrameType.fromCode(buffer[startIndex].toInt() and 0xFF)
            ?: throw DeviceSessionFrameException(DeviceSessionProtocolError.UnknownType)
        val streamId = readIntLe(buffer, startIndex + 1)
        if (streamId < 0) {
            throw DeviceSessionFrameException(DeviceSessionProtocolError.InvalidStreamId)
        }
        val payloadLength = readIntLe(buffer, startIndex + 5)
        if (payloadLength < 0 || payloadLength > DEVICE_SESSION_MAX_PAYLOAD_BYTES) {
            throw DeviceSessionFrameException(DeviceSessionProtocolError.OversizedPayload)
        }
        return DeviceSessionFrameHeader(type, streamId, payloadLength)
    }

    fun decodeAll(buffer: ByteArray): List<DeviceSessionFrame> {
        val frames = ArrayList<DeviceSessionFrame>()
        var index = 0
        while (index < buffer.size) {
            when (val decoded = decode(buffer, startIndex = index)) {
                is DeviceSessionDecodeResult.Frame -> {
                    frames += decoded.frame
                    index = decoded.nextIndex
                }
                is DeviceSessionDecodeResult.Invalid -> {
                    throw DeviceSessionFrameException(decoded.error)
                }
            }
        }
        return frames
    }

    fun windowUpdate(streamId: Int, creditBytes: Int): DeviceSessionFrame {
        require(creditBytes >= 0) { "creditBytes must be non-negative" }
        return DeviceSessionFrame(
            type = DeviceSessionFrameType.WindowUpdate,
            streamId = streamId,
            payload = intLeBytes(creditBytes),
        )
    }

    fun ping(payload: ByteArray): DeviceSessionFrame {
        return DeviceSessionFrame(
            type = DeviceSessionFrameType.Ping,
            streamId = DEVICE_SESSION_CONTROL_STREAM_ID,
            payload = payload,
        )
    }

    fun pong(payload: ByteArray): DeviceSessionFrame {
        return DeviceSessionFrame(
            type = DeviceSessionFrameType.Pong,
            streamId = DEVICE_SESSION_CONTROL_STREAM_ID,
            payload = payload,
        )
    }

    fun rst(streamId: Int, errorCode: Int): DeviceSessionFrame {
        return DeviceSessionFrame(
            type = DeviceSessionFrameType.Rst,
            streamId = streamId,
            payload = intLeBytes(errorCode),
        )
    }

    fun goAway(lastStreamId: Int): DeviceSessionFrame {
        return DeviceSessionFrame(
            type = DeviceSessionFrameType.GoAway,
            streamId = DEVICE_SESSION_CONTROL_STREAM_ID,
            payload = intLeBytes(lastStreamId),
        )
    }

    fun readIntLe(payload: ByteArray, offset: Int = 0): Int {
        require(offset >= 0 && offset + 4 <= payload.size) { "int payload is truncated" }
        return (payload[offset].toInt() and 0xFF) or
            ((payload[offset + 1].toInt() and 0xFF) shl 8) or
            ((payload[offset + 2].toInt() and 0xFF) shl 16) or
            ((payload[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun clientStreamId(index: Int): Int {
        require(index >= 0) { "index must be non-negative" }
        return index * 2 + 1
    }

    fun serverStreamId(index: Int): Int {
        require(index >= 0) { "index must be non-negative" }
        return (index + 1) * 2
    }

    fun writeIntLe(buffer: ByteArray, offset: Int, value: Int) {
        require(offset >= 0 && offset + 4 <= buffer.size) { "int payload is truncated" }
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun intLeBytes(value: Int): ByteArray {
        val buffer = ByteArray(4)
        writeIntLe(buffer, 0, value)
        return buffer
    }
}

internal class DeviceSessionFrameException(
    val error: DeviceSessionProtocolError,
) : IllegalArgumentException("device session frame error: $error")

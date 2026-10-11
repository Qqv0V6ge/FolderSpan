package com.folderspan.service.session

import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeviceSessionIoTest {
    @Test
    fun fragmentedMaximumPayloadAndFollowingEmptyFrameStaySeparate() = runSuspendTest {
        val frames = listOf(
            DeviceSessionFrame(DeviceSessionFrameType.Data, 7, ByteArray(DEVICE_SESSION_MAX_PAYLOAD_BYTES) { it.toByte() }),
            DeviceSessionFrame(DeviceSessionFrameType.Trailer, 7),
            DeviceSessionFrame(DeviceSessionFrameType.Ping, 0, byteArrayOf(9)),
        )
        val channel = FragmentedInput(DeviceSessionFrames.encodeAll(frames), fragmentSize = 7)
        frames.forEach { assertEquals(it, DeviceSessionIo.readFrame(channel)) }
        assertEquals(channel.bytes.size, channel.position)
    }

    @Test
    fun invalidMetadataIsRejectedBeforeReadingTheBody() = runSuspendTest {
        val valid = DeviceSessionFrames.encode(DeviceSessionFrame(DeviceSessionFrameType.Data, 1, byteArrayOf(1)))
        val cases = listOf(
            valid.copyOf().apply { this[0] = 99 } to DeviceSessionProtocolError.UnknownType,
            valid.copyOf().apply { DeviceSessionFrames.writeIntLe(this, 1, -1) } to DeviceSessionProtocolError.InvalidStreamId,
            valid.copyOf().apply { DeviceSessionFrames.writeIntLe(this, 5, -1) } to DeviceSessionProtocolError.OversizedPayload,
            valid.copyOf().apply { DeviceSessionFrames.writeIntLe(this, 5, DEVICE_SESSION_MAX_PAYLOAD_BYTES + 1) } to DeviceSessionProtocolError.OversizedPayload,
        )
        cases.forEach { (bytes, expected) ->
            val channel = FragmentedInput(bytes, fragmentSize = 3)
            assertEquals(expected, assertFailsWith<DeviceSessionFrameException> { DeviceSessionIo.readFrame(channel) }.error)
            assertEquals(DEVICE_SESSION_HEADER_SIZE, channel.position)
        }
    }

    @Test
    fun closingDuringHeaderOrPayloadFailsInsteadOfReturningAPartialFrame() = runSuspendTest {
        val frame = DeviceSessionFrames.encode(DeviceSessionFrame(DeviceSessionFrameType.Data, 1, byteArrayOf(1, 2)))
        listOf(0, DEVICE_SESSION_HEADER_SIZE - 1, frame.size - 1).forEach { size ->
            assertFailsWith<DeviceSessionIoException> {
                DeviceSessionIo.readFrame(FragmentedInput(frame.copyOf(size), fragmentSize = 3))
            }
        }
    }
}

private class FragmentedInput(val bytes: ByteArray, private val fragmentSize: Int) : DeviceSessionByteChannel {
    var position = 0
        private set

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position == bytes.size) return -1
        val size = minOf(length, fragmentSize, bytes.size - position)
        bytes.copyInto(buffer, offset, position, position + size)
        position += size
        return size
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = error("Input only")
    override suspend fun flush() = Unit
    override fun close() = Unit
}

package com.folderspan.service.session

import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import strings.AppStrings

private const val DEVICE_SESSION_WRITE_BATCH_BYTES = 1024 * 1024

internal class DeviceSessionTransport(
    private val channel: DeviceSessionByteChannel,
    private val sendPlan: DeviceSessionWindowPlan,
    private val receivePlan: DeviceSessionWindowPlan = sendPlan,
    private val isClient: Boolean,
    onPing: (suspend (ByteArray) -> Unit)? = null,
) {
    private val outgoing = Channel<DeviceSessionFrame>(capacity = sendPlan.frameQueueCapacity())
    private val incoming = Channel<DeviceSessionFrame>(capacity = receivePlan.frameQueueCapacity())
    val sendCredit = DeviceSessionCredit(sendPlan)
    val connection = DeviceSessionConnection(
        outgoing = outgoing,
        incoming = incoming,
        sendCredit = sendCredit,
        isClient = isClient,
        incomingFrameCapacity = receivePlan.frameQueueCapacity(),
        onPing = onPing,
    )

    fun start(scope: CoroutineScope): Job {
        val role = if (isClient) "client" else "server"
        LogKit.i(
            AppStrings.ui_device_session_transmission_start_role_arg0_sendwindow_arg1.format(arg0 = (role), arg1 = (sendPlan.sessionWindowBytes).toString()) +
                "receiveWindow=${receivePlan.sessionWindowBytes}",
        )
        return scope.launch {
            val writer = launch(channel.ioContext) {
                try {
                    var pendingFrame: DeviceSessionFrame? = null
                    while (true) {
                        val received = if (pendingFrame == null) outgoing.receiveCatching() else null
                        received?.exceptionOrNull()?.let { throw it }
                        val first = pendingFrame ?: received?.getOrNull() ?: break
                        pendingFrame = null
                        val frames = ArrayList<DeviceSessionFrame>()
                        frames += first
                        var batchBytes = DEVICE_SESSION_HEADER_SIZE + first.payload.size
                        while (batchBytes < DEVICE_SESSION_WRITE_BATCH_BYTES) {
                            val next = outgoing.tryReceive().getOrNull() ?: break
                            val nextBytes = DEVICE_SESSION_HEADER_SIZE + next.payload.size
                            if (batchBytes + nextBytes > DEVICE_SESSION_WRITE_BATCH_BYTES) {
                                pendingFrame = next
                                break
                            }
                            frames += next
                            batchBytes += nextBytes
                        }
                        DeviceSessionIo.writeFrames(channel, frames)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    LogKit.w(AppStrings.ui_session_ends_role_arg0_message_arg1.format(arg0 = (role), arg1 = (error.message).toString()))
                    connection.close()
                }
            }
            val reader = launch(channel.ioContext) {
                try {
                    while (true) {
                        incoming.send(DeviceSessionIo.readFrame(channel))
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    LogKit.w(AppStrings.ui_device_conversation_read_end_role_arg0_message_arg1.format(arg0 = (role), arg1 = (error.message).toString()))
                    incoming.close()
                    connection.close()
                }
            }
            try {
                connection.run()
            } finally {
                LogKit.i(AppStrings.ui_device_session_transmission_stop_role_arg0.format(arg0 = (role)))
                writer.cancel()
                reader.cancel()
                incoming.close()
                outgoing.close()
                channel.close()
            }
        }
    }

    suspend fun close() {
        val role = if (isClient) "client" else "server"
        LogKit.i(AppStrings.ui_device_session_transmission_close_role_arg0.format(arg0 = (role)))
        runCatching { connection.goAway() }
        connection.close()
        channel.close()
    }
}

package com.folderspan.ui.state.main

import com.folderspan.service.data.SocketDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import strings.AppStrings

internal suspend fun connectSyncDeviceEndpoint(
    isConnected: () -> Boolean,
    discoveredDevice: () -> SocketDevice?,
    connect: suspend (SocketDevice) -> Unit,
    timeoutMillis: Long = 30_000,
): Result<Boolean> {
    if (isConnected()) return Result.success(true)
    val device = discoveredDevice()
        ?: return Result.failure(IllegalStateException(AppStrings.ui_device_endpoint_unavailable))
    return try {
        Result.success(withTimeoutOrNull(timeoutMillis) {
            connect(device)
            while (!isConnected()) delay(100)
            true
        } == true)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }
}

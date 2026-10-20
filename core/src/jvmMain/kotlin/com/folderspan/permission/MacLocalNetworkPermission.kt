package com.folderspan.permission

import com.folderspan.utils.LogKit
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlin.random.Random

internal object MacLocalNetworkPermission {
    fun isSupported(
        osName: String = System.getProperty("os.name").orEmpty(),
        osVersion: String = System.getProperty("os.version").orEmpty(),
    ): Boolean = MacPermissionSettings.supports(15, osName = osName, osVersion = osVersion)

    fun status(
        osName: String = System.getProperty("os.name").orEmpty(),
        osVersion: String = System.getProperty("os.version").orEmpty(),
    ): PermissionStatus = if (isSupported(osName, osVersion)) PermissionStatus.Unknown else PermissionStatus.Unsupported

    fun request(): PermissionStatus {
        if (!isSupported()) return PermissionStatus.Unsupported
        // Apple TN3179: connecting UDP to a scoped IPv6 link-local address can
        // trigger the system alert without sending packets. It is best effort;
        // neither connect success nor a routing error proves the permission state.
        // https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isPointToPoint && it.supportsMulticast() }
                .forEach { network ->
                    network.inetAddresses.toList().filterIsInstance<Inet6Address>()
                        .filter { it.isLinkLocalAddress }
                        .forEach { address ->
                            runCatching {
                                DatagramSocket().use { socket ->
                                    socket.connect(InetSocketAddress(localNetworkPermissionProbeAddress(address), 9))
                                }
                            }
                        }
                }
        }.onFailure { error -> LogKit.w("Unable to trigger macOS local network permission alert: ${error.message}") }
        return PermissionStatus.Unknown
    }

}

internal fun localNetworkPermissionProbeAddress(
    source: Inet6Address,
    hostPart: ByteArray = Random.nextBytes(8),
): Inet6Address {
    require(source.isLinkLocalAddress && hostPart.size == 8)
    val bytes = source.address
    hostPart.copyInto(bytes, destinationOffset = 8)
    return Inet6Address.getByAddress(null, bytes, source.scopeId)
}

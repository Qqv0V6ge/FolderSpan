@file:OptIn(ExperimentalForeignApi::class)

package com.folderspan.permission

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIDevice
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET6
import platform.posix.IFF_BROADCAST
import platform.posix.IFF_UP
import platform.posix.SOCK_DGRAM
import platform.posix.close
import platform.posix.connect
import platform.posix.memcpy
import platform.posix.sockaddr_in6
import platform.posix.socket
import kotlin.random.Random

internal fun currentIosMajorVersion(): Int = UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 0

internal class IosLocalNetworkPermission(
    private val majorVersion: Int = currentIosMajorVersion(),
    private val trigger: () -> Unit = ::triggerIosLocalNetworkPrivacyAlert,
) {
    fun status(): PermissionStatus = if (majorVersion >= 14) PermissionStatus.Unknown else PermissionStatus.Unsupported

    fun request(): PermissionStatus {
        val status = status()
        if (status != PermissionStatus.Unsupported) runCatching { trigger() }
        return status
    }
}

// Apple TN3179: a connected UDP socket to a link-local IPv6 address may trigger
// the privacy alert without sending traffic. It cannot reveal the authorization state.
// https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy
private fun triggerIosLocalNetworkPrivacyAlert() = memScoped {
    if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) return@memScoped

    val interfaces = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(interfaces.ptr) != 0) return@memScoped
    try {
        var cursor = interfaces.value
        while (cursor != null) {
            val entry = cursor.pointed
            cursor = entry.ifa_next
            val source = entry.ifa_addr ?: continue
            val flags = entry.ifa_flags.toInt()
            if (flags and IFF_UP == 0 || flags and IFF_BROADCAST == 0) continue
            if (source.pointed.sa_family.toInt() != AF_INET6) continue
            if (source.pointed.sa_len.toInt() < sizeOf<sockaddr_in6>()) continue

            val address = alloc<sockaddr_in6>()
            memcpy(address.ptr, source, sizeOf<sockaddr_in6>().convert())
            val bytes = address.sin6_addr.ptr.reinterpret<UByteVar>()
            if (bytes[0].toInt() != 0xFE || bytes[1].toInt() and 0xC0 != 0x80) continue

            // Port 9 is the discard service; scope ID and network prefix remain intact.
            address.sin6_port = 0x0900u.toUShort()
            repeat(2) {
                Random.nextBytes(8).forEachIndexed { index, byte -> bytes[index + 8] = byte.toUByte() }
                val descriptor = socket(AF_INET6, SOCK_DGRAM, 0)
                if (descriptor >= 0) {
                    try {
                        connect(descriptor, address.ptr.reinterpret(), sizeOf<sockaddr_in6>().convert())
                    } finally {
                        close(descriptor)
                    }
                }
            }
        }
    } finally {
        freeifaddrs(interfaces.value)
    }
}

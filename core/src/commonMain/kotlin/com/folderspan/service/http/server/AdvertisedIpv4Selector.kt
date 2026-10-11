package com.folderspan.service.http.server

import com.folderspan.extensions.isSame24Subnet
import com.folderspan.service.http.NetworkInterfaceInfo

/**
 * 从平台网卡快照中选择用于广播的 IPv4 地址。
 *
 * 普通单播优先于链路本地地址，同类地址中实体网络优先；目标存在时仍优先选择同 /24 接口。
 */
internal fun selectPreferredAdvertisedIpv4Host(
    currentHost: String,
    targetHost: String? = null,
    interfaces: List<NetworkInterfaceInfo>,
): String {
    val candidates = rankedAdvertisableIpv4Candidates(interfaces)
    val current = currentHost.trim()
    val currentCandidate = candidates.firstOrNull { item -> item.address == current }
    val target = targetHost?.trim().orEmpty()

    if (target.toIpv4SortKeyOrNull() != null) {
        if (currentCandidate != null && current.isSame24Subnet(target)) {
            return current
        }
        candidates.firstOrNull { item -> item.address.isSame24Subnet(target) }?.let { item ->
            return item.address
        }
    }

    if (currentCandidate != null && !currentCandidate.isLikelyVirtual && !currentCandidate.isLinkLocal) {
        return current
    }

    return candidates.firstOrNull()?.address
        ?: current.takeIf { item -> item.toIpv4SortKeyOrNull() != null }
        ?: "127.0.0.1"
}

internal fun rankedAdvertisableIpv4Hosts(
    interfaces: List<NetworkInterfaceInfo>,
): List<String> = rankedAdvertisableIpv4Candidates(interfaces).map { item -> item.address }

private data class AdvertisableIpv4Candidate(
    val address: String,
    val addressSortKey: Long,
    val interfaceName: String,
    val interfacePriority: Int,
    val isLikelyVirtual: Boolean,
    val isLinkLocal: Boolean,
)

private fun rankedAdvertisableIpv4Candidates(
    interfaces: List<NetworkInterfaceInfo>,
): List<AdvertisableIpv4Candidate> {
    return interfaces.asSequence()
        .filter { item -> item.isUp && !item.isLoopback }
        .flatMap { networkInterface ->
            val isLikelyVirtual = networkInterface.isLikelyVirtual()
            val interfacePriority = networkInterface.advertisingPriority()
            networkInterface.addresses.asSequence()
                .filter { item -> !item.isIPv6 }
                .mapNotNull { item ->
                    val address = item.address.trim()
                    val addressSortKey = address.toIpv4SortKeyOrNull() ?: return@mapNotNull null
                    AdvertisableIpv4Candidate(
                        address = address,
                        addressSortKey = addressSortKey,
                        interfaceName = networkInterface.name.trim().lowercase(),
                        interfacePriority = interfacePriority,
                        isLikelyVirtual = isLikelyVirtual,
                        isLinkLocal = address.startsWith("169.254."),
                    )
                }
        }
        .sortedWith(
            compareBy<AdvertisableIpv4Candidate>(
                { item -> item.isLinkLocal },
                { item -> item.isLikelyVirtual },
                { item -> item.interfacePriority },
                { item -> item.interfaceName },
                { item -> item.addressSortKey },
            )
        )
        .distinctBy { item -> item.address }
        .toList()
}

private fun NetworkInterfaceInfo.isLikelyVirtual(): Boolean {
    val normalizedName = name.trim().lowercase()
    val normalizedDisplayName = displayName.trim().lowercase()
    val isNamedLoopback = normalizedName == "lo" ||
        (normalizedName.startsWith("lo") && normalizedName.drop(2).all { item -> item.isDigit() })
    return isNamedLoopback ||
        VIRTUAL_INTERFACE_PREFIXES.any { item -> normalizedName.startsWith(item) } ||
        VIRTUAL_INTERFACE_DISPLAY_MARKERS.any { item -> normalizedDisplayName.contains(item) }
}

private fun NetworkInterfaceInfo.advertisingPriority(): Int {
    val normalizedName = name.trim().lowercase()
    val normalizedDisplayName = displayName.trim().lowercase()
    return when {
        normalizedName.startsWith("wlan") ||
            normalizedName.startsWith("wl") ||
            normalizedName.startsWith("wifi") ||
            normalizedDisplayName.contains("wi-fi") ||
            normalizedDisplayName.contains("wifi") ||
            normalizedDisplayName.contains("wireless") -> 0

        normalizedName.startsWith("eth") ||
            normalizedName.startsWith("en") ||
            normalizedName.startsWith("usb") ||
            normalizedName.startsWith("rndis") ||
            normalizedDisplayName.contains("ethernet") -> 1

        normalizedName.startsWith("pdp_ip") ||
            normalizedName.startsWith("rmnet") ||
            normalizedName.startsWith("ccmni") ||
            normalizedName.startsWith("ccemni") ||
            normalizedName.startsWith("wwan") ||
            normalizedName.startsWith("wwp") ||
            normalizedDisplayName.contains("cellular") ||
            normalizedDisplayName.contains("mobile broadband") -> 2

        else -> 3
    }
}

private fun String.toIpv4SortKeyOrNull(): Long? {
    val octets = trim().split('.')
    if (octets.size != 4) return null

    val values = octets.map { item -> item.toIntOrNull() ?: return null }
    if (values.any { item -> item !in 0..255 }) return null
    if (values[0] == 0 || values[0] == 127 || values[0] >= 224) return null

    return values.fold(0L) { result, octet -> (result shl 8) or octet.toLong() }
}

private val VIRTUAL_INTERFACE_PREFIXES = listOf(
    "awdl",
    "br-",
    "bridge",
    "clat",
    "docker",
    "dummy",
    "gif",
    "ipsec",
    "llw",
    "stf",
    "tap",
    "tailscale",
    "tun",
    "utun",
    "vboxnet",
    "veth",
    "virbr",
    "vmnet",
    "wg",
    "zt",
)

private val VIRTUAL_INTERFACE_DISPLAY_MARKERS = listOf(
    "docker",
    "hyper-v",
    "loopback",
    "tailscale",
    "tunnel",
    "virtual",
    "vmware",
    "vpn",
    "zerotier",
)

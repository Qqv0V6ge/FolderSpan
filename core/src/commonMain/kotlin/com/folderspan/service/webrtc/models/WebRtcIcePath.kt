package com.folderspan.service.webrtc.models

import kotlinx.serialization.Serializable

@Serializable
enum class WebRtcIcePathKind { Unknown, Direct, Relay }

@Serializable
enum class WebRtcIpFamily { Unknown, IPv4, IPv6 }

@Serializable
enum class WebRtcIceProtocol { Unknown, Udp, Tcp }

@Serializable
enum class WebRtcTurnTransport { Unknown, Udp, Tcp, Tls }

@Serializable
enum class WebRtcIceObservationSource { Local, Remote }

/** Directions are relative to observerDeviceId, including the TURN access transport. */
@Serializable
data class WebRtcIcePath(
    val kind: WebRtcIcePathKind = WebRtcIcePathKind.Unknown,
    val localFamily: WebRtcIpFamily = WebRtcIpFamily.Unknown,
    val remoteFamily: WebRtcIpFamily = WebRtcIpFamily.Unknown,
    val localProtocol: WebRtcIceProtocol = WebRtcIceProtocol.Unknown,
    val remoteProtocol: WebRtcIceProtocol = WebRtcIceProtocol.Unknown,
    val localTurnTransport: WebRtcTurnTransport = WebRtcTurnTransport.Unknown,
)

@Serializable
data class WebRtcIceSnapshot(
    val observerDeviceId: String,
    val remoteDeviceId: String,
    val connectionAttemptId: String,
    val negotiation: Int,
    val path: WebRtcIcePath,
    val source: WebRtcIceObservationSource = WebRtcIceObservationSource.Local,
)

internal fun WebRtcIceSnapshot.asRemoteObservation(
    localDeviceId: String,
    peerDeviceId: String,
    attempt: String,
    currentNegotiation: Int,
): WebRtcIceSnapshot? = takeIf {
    source == WebRtcIceObservationSource.Local && observerDeviceId == peerDeviceId &&
        remoteDeviceId == localDeviceId && connectionAttemptId == attempt && negotiation == currentNegotiation
}?.copy(source = WebRtcIceObservationSource.Remote)

internal data class WebRtcIceStats(val type: String, val members: Map<String, Any?>)

internal fun selectedWebRtcIcePath(stats: Map<String, WebRtcIceStats>): WebRtcIcePath {
    val selectedIds = stats.values.filter { it.type == "transport" }
        .mapNotNull { it.members["selectedCandidatePairId"] as? String }.distinct()
    val pair = selectedIds.singleOrNull()?.let(stats::get)?.takeIf { it.type == "candidate-pair" }
        ?: return WebRtcIcePath()
    val local = (pair.members["localCandidateId"] as? String)?.let(stats::get)
        ?.takeIf { it.type == "local-candidate" }?.members.orEmpty()
    val remote = (pair.members["remoteCandidateId"] as? String)?.let(stats::get)
        ?.takeIf { it.type == "remote-candidate" }?.members.orEmpty()
    val types = listOf(local["candidateType"], remote["candidateType"])
    val kind = when {
        "relay" in types -> WebRtcIcePathKind.Relay
        types.all { it in listOf("host", "srflx", "prflx") } -> WebRtcIcePathKind.Direct
        else -> WebRtcIcePathKind.Unknown
    }
    fun family(candidate: Map<String, Any?>): WebRtcIpFamily {
        val address = (candidate["address"] as? String)?.takeIf(String::isNotBlank)
            ?: candidate["ip"] as? String ?: return WebRtcIpFamily.Unknown
        return when {
            isWebRtcIpv6(address.removeSurrounding("[", "]").substringBefore('%')) -> WebRtcIpFamily.IPv6
            isWebRtcIpv4(address) -> WebRtcIpFamily.IPv4
            else -> WebRtcIpFamily.Unknown
        }
    }
    fun protocol(value: Any?) = when (value) {
        "udp" -> WebRtcIceProtocol.Udp
        "tcp" -> WebRtcIceProtocol.Tcp
        else -> WebRtcIceProtocol.Unknown
    }
    return WebRtcIcePath(
        kind = kind,
        localFamily = family(local), remoteFamily = family(remote),
        localProtocol = protocol(local["protocol"]), remoteProtocol = protocol(remote["protocol"]),
        localTurnTransport = if (local["candidateType"] != "relay") WebRtcTurnTransport.Unknown else when (local["relayProtocol"]) {
            "udp" -> WebRtcTurnTransport.Udp
            "tcp" -> WebRtcTurnTransport.Tcp
            "tls" -> WebRtcTurnTransport.Tls
            else -> WebRtcTurnTransport.Unknown
        },
    )
}

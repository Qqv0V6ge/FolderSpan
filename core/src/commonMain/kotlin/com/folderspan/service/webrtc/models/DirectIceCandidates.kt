package com.folderspan.service.webrtc.models

private val candidateFields = Regex("[ \\t]+")
private val directCandidateTypes = setOf("host", "srflx", "prflx")

internal fun String.isDirectIceCandidate(): Boolean {
    val candidate = removePrefix("a=")
    if (!candidate.startsWith("candidate:", ignoreCase = true)) return false
    val fields = candidate.split(candidateFields, limit = 9)
    return fields.size >= 8 && fields[6].equals("typ", ignoreCase = true) &&
        fields[7].lowercase() in directCandidateTypes
}

internal fun String.withoutNonDirectIceCandidates(): String =
    lineSequence().filter { !it.startsWith("a=candidate:", ignoreCase = true) || it.isDirectIceCandidate() }
        .joinToString("\r\n")

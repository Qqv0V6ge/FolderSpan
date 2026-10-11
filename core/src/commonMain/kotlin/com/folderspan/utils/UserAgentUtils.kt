package com.folderspan.utils

import strings.AppStrings

internal fun buildUserAgentDisplayName(
    userAgent: String,
    fallback: String = AppStrings.ui_web_client,
): String {
    if (userAgent.isBlank()) return fallback

    val browser = parseBrowser(userAgent)
    val os = parseOperatingSystem(userAgent)
    val displayBrowser = browser.takeIf { item ->  item.isNotBlank() && item != "Unknown Browser" }
    val displayOs = os.takeIf { item ->  item.isNotBlank() && item != "Unknown OS" }

    return buildString {
        if (!displayBrowser.isNullOrBlank()) {
            append(displayBrowser)
        }
        if (!displayOs.isNullOrBlank()) {
            if (isNotEmpty()) append(" - ")
            append(displayOs)
        }
    }.ifBlank { fallback }
}

internal fun parseBrowser(userAgent: String): String {
    // CLI tools
    if (userAgent.contains("curl", ignoreCase = true)) return extractVersion(userAgent, "curl/([\\d.]+)", "Curl")
    if (userAgent.contains("Wget", ignoreCase = true)) return extractVersion(userAgent, "Wget/([\\d.]+)", "Wget")
    if (userAgent.contains("PostmanRuntime", ignoreCase = true)) return extractVersion(
        userAgent,
        "PostmanRuntime/([\\d.]+)",
        "Postman"
    )

    // iOS browsers (Safari engine)
    if (userAgent.contains("CriOS/", ignoreCase = true)) return extractVersion(
        userAgent,
        "CriOS/([\\d.]+)",
        "Chrome iOS"
    )
    if (userAgent.contains("EdgiOS/", ignoreCase = true)) return extractVersion(
        userAgent,
        "EdgiOS/([\\d.]+)",
        "Edge iOS"
    )
    if (userAgent.contains("FxiOS/", ignoreCase = true)) return extractVersion(
        userAgent,
        "FxiOS/([\\d.]+)",
        "Firefox iOS"
    )
    if (userAgent.contains("OPiOS/", ignoreCase = true)) return extractVersion(
        userAgent,
        "OPiOS/([\\d.]+)",
        "Opera iOS"
    )

    return when {
        userAgent.contains("Firefox/", ignoreCase = true) -> extractVersion(userAgent, "Firefox/([\\d.]+)", "Firefox")
        userAgent.contains("EdgA/", ignoreCase = true) -> extractVersion(userAgent, "EdgA/([\\d.]+)", "Edge Android")
        userAgent.contains("Edg/", ignoreCase = true) -> extractVersion(userAgent, "Edg/([\\d.]+)", "Edge")
        userAgent.contains("OPR/", ignoreCase = true) || userAgent.contains("Opera/", ignoreCase = true) ->
            extractVersion(userAgent, "(?:OPR|Opera)/([\\d.]+)", "Opera")

        userAgent.contains("Vivaldi/", ignoreCase = true) -> extractVersion(userAgent, "Vivaldi/([\\d.]+)", "Vivaldi")
        userAgent.contains("SamsungBrowser/", ignoreCase = true) -> extractVersion(
            userAgent,
            "SamsungBrowser/([\\d.]+)",
            "Samsung Internet"
        )

        userAgent.contains("Chrome/", ignoreCase = true) -> extractVersion(userAgent, "Chrome/([\\d.]+)", "Chrome")

        userAgent.contains("Safari/", ignoreCase = true) && !userAgent.contains("Chrome", ignoreCase = true) ->
            extractVersion(userAgent, "Version/([\\d.]+)", "Safari")

        userAgent.contains("MSIE", ignoreCase = true) || userAgent.contains("Trident/", ignoreCase = true) -> {
            if (userAgent.contains("MSIE", ignoreCase = true)) {
                extractVersion(userAgent, "MSIE ([\\d.]+)", "Internet Explorer")
            } else {
                "Internet Explorer 11.0"
            }
        }

        else -> "Unknown Browser"
    }
}

private fun extractVersion(ua: String, pattern: String, name: String): String {
    val regex = pattern.toRegex()
    val match = regex.find(ua)
    val version = match?.groupValues?.getOrNull(1)
    return if (version.isNullOrBlank()) name else "$name $version"
}

internal fun parseOperatingSystem(userAgent: String): String {
    return when {
        userAgent.contains("Android", ignoreCase = true) -> {
            val version = "Android ([\\d.]+)".toRegex().find(userAgent)?.groupValues?.getOrNull(1)
            "Android ${version ?: ""}".trim()
        }

        userAgent.contains("iPhone", ignoreCase = true) || userAgent.contains("CPU iPhone OS", ignoreCase = true) -> {
            val v = "(?:iPhone OS|CPU iPhone OS) ([\\d_]+)".toRegex().find(userAgent)?.groupValues?.getOrNull(1)
            "iOS ${v?.replace('_', '.') ?: ""}".trim()
        }

        userAgent.contains("iPad", ignoreCase = true) || userAgent.contains("CPU OS", ignoreCase = true) -> {
            val v = "(?:CPU OS|iPad OS) ([\\d_]+)".toRegex().find(userAgent)?.groupValues?.getOrNull(1)
            "iPadOS ${v?.replace('_', '.') ?: ""}".trim()
        }

        userAgent.contains("Windows NT", ignoreCase = true) -> {
            when (val version = "Windows NT ([\\d.]+)".toRegex().find(userAgent)?.groupValues?.getOrNull(1)) {
                "10.0" -> "Windows 10/11"
                "6.3" -> "Windows 8.1"
                "6.2" -> "Windows 8"
                "6.1" -> "Windows 7"
                "6.0" -> "Windows Vista"
                "5.2" -> "Windows XP x64"
                "5.1" -> "Windows XP"
                else -> "Windows ${version ?: ""}".trim()
            }
        }

        userAgent.contains("CrOS", ignoreCase = true) -> {
            val v = "CrOS [^ ]+ ([\\d.]+)".toRegex().find(userAgent)?.groupValues?.getOrNull(1)
            "ChromeOS ${v ?: ""}".trim()
        }

        userAgent.contains("Mac OS X", ignoreCase = true) -> {
            val v = "Mac OS X ([\\d_\\.]+)".toRegex().find(userAgent)?.groupValues?.getOrNull(1)
            "macOS ${v?.replace('_', '.') ?: ""}".trim()
        }

        userAgent.contains("Linux", ignoreCase = true) -> {
            when {
                userAgent.contains("Ubuntu", ignoreCase = true) -> "Ubuntu Linux"
                userAgent.contains("Fedora", ignoreCase = true) -> "Fedora Linux"
                userAgent.contains("Arch", ignoreCase = true) -> "Arch Linux"
                else -> "Linux"
            }
        }

        else -> "Unknown OS"
    }
}

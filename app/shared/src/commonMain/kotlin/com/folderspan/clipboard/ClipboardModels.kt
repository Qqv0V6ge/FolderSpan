package com.folderspan.clipboard

data class ClipboardContent(
    val texts: List<String> = emptyList(),
    val filePaths: List<String> = emptyList()
)

data class ClipboardParseResult(
    val rawText: String,
    val filePaths: List<String>,
    val paths: List<String>,
    val standaloneHttpUrl: String?,
    val unrecognizedTexts: List<String>,
) {
    val urls: List<String>
        get() = listOfNotNull(standaloneHttpUrl)
}

fun parseClipboardContent(content: ClipboardContent): ClipboardParseResult {
    return parseClipboardContent(content.texts, content.filePaths)
}

internal fun parseClipboardContent(
    texts: List<String>,
    filePaths: List<String>
): ClipboardParseResult {
    val paths = LinkedHashSet<String>()
    val normalizedFilePaths = LinkedHashSet<String>()
    val rawText = texts.joinToString(separator = "\n")
    val standaloneHttpUrl = parseStandaloneHttpUrl(rawText)

    filePaths.forEach { entry ->
        val path = normalizeEntry(entry)
        if (path.isNotBlank()) {
            normalizedFilePaths.add(path)
            paths.add(path)
        }
    }

    texts.forEach { text ->
        text.lineSequence().forEach { line ->
            val entry = normalizeEntry(line)
            if (entry.isBlank()) return@forEach
            if (isFileUrl(entry)) {
                val path = fileUrlToPath(entry)
                if (path.isNotBlank()) {
                    paths.add(path)
                }
                return@forEach
            }
            if (looksLikeUrl(entry)) {
                return@forEach
            }
            paths.add(entry)
        }
    }

    return ClipboardParseResult(
        rawText = rawText,
        filePaths = normalizedFilePaths.toList(),
        paths = paths.toList(),
        standaloneHttpUrl = standaloneHttpUrl,
        unrecognizedTexts = if (rawText.isBlank() || standaloneHttpUrl != null) {
            emptyList()
        } else {
            texts.filter { text -> text.isNotBlank() }
        },
    )
}

private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")
private val windowsPathRegex = Regex("^[a-zA-Z]:[\\\\/].*")

private fun normalizeEntry(entry: String): String {
    val trimmed = entry.trim()
    if (trimmed.length >= 2) {
        val first = trimmed.first()
        val last = trimmed.last()
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return trimmed.substring(1, trimmed.length - 1).trim()
        }
    }
    return trimmed
}

private fun isFileUrl(value: String): Boolean = value.startsWith("file://", ignoreCase = true)

private fun looksLikeUrl(value: String): Boolean {
    return !windowsPathRegex.matches(value) && schemeRegex.matches(value)
}

internal fun parseStandaloneHttpUrl(value: String): String? {
    val candidate = value.trim()
    if (
        candidate.isEmpty() ||
        candidate.any { char -> char.isWhitespace() || char.isISOControl() || char == '\\' }
    ) {
        return null
    }
    val schemeSeparator = candidate.indexOf("://")
    if (schemeSeparator <= 0) return null
    val scheme = candidate.substring(0, schemeSeparator).lowercase()
    if (scheme != "http" && scheme != "https") return null

    val authorityStart = schemeSeparator + 3
    val authorityEnd = candidate.indexOfFirstFrom(authorityStart) { char ->
        char == '/' || char == '?' || char == '#'
    }.let { index -> if (index < 0) candidate.length else index }
    val authority = candidate.substring(authorityStart, authorityEnd)
    if (authority.isEmpty() || '@' in authority || !isValidHttpAuthority(authority)) return null
    return candidate
}

private fun isValidHttpAuthority(authority: String): Boolean {
    if (authority.startsWith('[')) {
        val closingIndex = authority.indexOf(']')
        if (closingIndex <= 1) return false
        val host = authority.substring(1, closingIndex)
        if (!host.all { char -> char.isDigit() || char.lowercaseChar() in 'a'..'f' || char == ':' || char == '.' }) {
            return false
        }
        val suffix = authority.substring(closingIndex + 1)
        return suffix.isEmpty() || isValidPortSuffix(suffix)
    }

    if (authority.count { char -> char == ':' } > 1) return false
    val host = authority.substringBeforeLast(':', authority)
    val suffix = authority.removePrefix(host)
    if (host.isEmpty() || host.startsWith('.') || host.endsWith('.')) return false
    if (!host.any { char -> char.isLetterOrDigit() }) return false
    if (!host.all { char -> char.isLetterOrDigit() || char == '.' || char == '-' }) return false
    return suffix.isEmpty() || isValidPortSuffix(suffix)
}

private fun isValidPortSuffix(value: String): Boolean {
    if (!value.startsWith(':')) return false
    val port = value.drop(1)
    return port.isNotEmpty() &&
        port.all(Char::isDigit) &&
        port.toIntOrNull()?.let { parsed -> parsed in 1..65535 } == true
}

private inline fun String.indexOfFirstFrom(startIndex: Int, predicate: (Char) -> Boolean): Int {
    for (index in startIndex until length) {
        if (predicate(this[index])) return index
    }
    return -1
}

private fun fileUrlToPath(value: String): String {
    var path = value.removePrefix("file://")
    if (path.startsWith("localhost/")) {
        path = path.removePrefix("localhost")
    }
    if (path.startsWith("/")) {
        if (path.length >= 3 && path[0] == '/' && path[2] == ':') {
            path = path.drop(1)
        }
    }
    return decodePercent(path)
}

private fun decodePercent(value: String): String {
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val hi = hexValue(value[index + 1])
            val lo = hexValue(value[index + 2])
            if (hi >= 0 && lo >= 0) {
                bytes.add(((hi shl 4) + lo).toByte())
                index += 3
                continue
            }
        }
        val encoded = char.toString().encodeToByteArray()
        encoded.forEach { item ->  bytes.add(item) }
        index += 1
    }
    return bytes.toByteArray().decodeToString()
}

private fun hexValue(char: Char): Int {
    return when (char) {
        in '0'..'9' -> char.code - '0'.code
        in 'a'..'f' -> char.code - 'a'.code + 10
        in 'A'..'F' -> char.code - 'A'.code + 10
        else -> -1
    }
}

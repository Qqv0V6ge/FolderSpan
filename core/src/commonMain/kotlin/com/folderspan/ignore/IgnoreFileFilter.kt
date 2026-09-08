package com.folderspan.ignore

import com.folderspan.utils.FileAccessPermission
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.extensions.parentPath
import com.folderspan.extensions.pathLevel
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import com.folderspan.utils.executeAsOneOrNullAwait

val SupportedIgnoreFileNames: List<String> = listOf(
    ".gitignore",
    ".dockerignore",
    ".npmignore",
    ".eslintignore",
    ".prettierignore",
    ".stylelintignore",
    ".ignore",
    ".rgignore",
    ".fdignore",
    ".agignore",
    ".gcloudignore",
    ".firebaseignore",
    ".helmignore",
    ".vercelignore",
    ".slugignore",
    ".ebignore",
)

fun normalizeSupportedIgnoreFileNames(fileNames: List<String>): List<String> {
    val selected = fileNames.toSet()
    return SupportedIgnoreFileNames.filter { fileName -> fileName in selected }
}

fun findExistingSupportedIgnoreFileNames(entries: List<FileSimpleInfo>): List<String> {
    return normalizeSupportedIgnoreFileNames(
        entries
            .asSequence()
            .filterNot { item -> item.isDirectory || item.isSymbolicLink }
            .map { item -> item.name }
            .toList()
    )
}

data class IgnoreFileMenuItem(
    val fileName: String,
    val enabled: Boolean,
    val exists: Boolean,
)

data class IgnorePreferenceScope(
    val protocol: FileProtocol,
    val protocolId: String,
    val basePath: String,
    val ignoreFiles: List<String>,
    val separator: String,
)

class IgnoreFileReadException(
    val fileName: String,
    cause: Throwable,
) : IllegalStateException("Failed to read Ignore file: $fileName", cause)

data class ResolvedIgnoreMatcher(
    val preference: IgnorePreferenceScope,
    val matcher: IgnoreMatcher,
) {
    fun matchesOperationSkip(path: String, isDirectory: Boolean): Boolean {
        val relativePath = relativePathUnder(
            basePath = preference.basePath,
            targetPath = path,
            separator = preference.separator,
        ) ?: return false
        return matchesRelativeOperationSkip(relativePath, isDirectory)
    }

    fun matchesRelativeOperationSkip(relativePath: String, isDirectory: Boolean): Boolean {
        val normalized = relativePath.replace('\\', '/').trim('/')
        return (!isDirectory && normalized in preference.ignoreFiles) || matcher.matches(normalized, isDirectory)
    }

    fun isEnabledIgnoreFile(path: String, isDirectory: Boolean): Boolean {
        if (isDirectory) return false
        val relativePath = relativePathUnder(
            basePath = preference.basePath,
            targetPath = path,
            separator = preference.separator,
        ) ?: return false
        return relativePath in preference.ignoreFiles
    }

    fun matches(path: String, isDirectory: Boolean): Boolean {
        val relativePath = relativePathUnder(
            basePath = preference.basePath,
            targetPath = path,
            separator = preference.separator,
        ) ?: return false
        return matcher.matches(relativePath, isDirectory)
    }

    fun mark(file: FileSimpleInfo): FileSimpleInfo {
        val ignored = matches(file.path, file.isDirectory)
        return if (file.isIgnored == ignored) file else file.withCopy(isIgnored = ignored)
    }
}

data class IgnoreOperationFilterResult(
    val entries: List<FileSimpleInfo>,
    val protectedSourceDirectories: Set<String>,
    val skippedCount: Int,
)

class LocalIgnoreFileFilter(
    private val database: FolderSpanDatabase,
    private val separator: String = PathUtils.getPathSeparator().ifBlank { "/" },
) {
    suspend fun loadFor(path: String): ResolvedIgnoreMatcher? {
        val preference = findNearestIgnorePreference(
            database = database,
            protocol = FileProtocol.Local,
            protocolId = "",
            path = path,
            separator = separator,
        ) ?: return null
        return loadResolvedIgnoreMatcher(preference) { filePath ->
            runCatching { FileUtils.readFileLines(FileAccessPermission.Allowed, filePath) }
        }
    }

}

suspend fun loadOperationIgnoreMatcher(
    database: FolderSpanDatabase,
    protocol: FileProtocol,
    protocolId: String,
    path: String,
    separator: String,
    readLines: suspend (String) -> Result<List<String>>,
): ResolvedIgnoreMatcher? {
    val preference = findNearestIgnorePreference(
        database = database,
        protocol = protocol,
        protocolId = protocolId,
        path = path,
        separator = separator,
    ) ?: return null
    return loadResolvedIgnoreMatcher(preference, readLines)
}

fun filterIgnoredOperationEntries(
    root: FileSimpleInfo,
    entries: List<FileSimpleInfo>,
    matcher: ResolvedIgnoreMatcher?,
    separator: String,
): IgnoreOperationFilterResult {
    if (matcher == null || entries.isEmpty()) {
        return IgnoreOperationFilterResult(
            entries = entries,
            protectedSourceDirectories = emptySet(),
            skippedCount = 0,
        )
    }

    val normalizedSeparator = separator.ifBlank { "/" }
    val kept = mutableListOf<FileSimpleInfo>()
    val skippedDirectories = mutableSetOf<String>()
    val protectedDirectories = mutableSetOf<String>()
    var skippedCount = 0

    entries
        .sortedWith(
            compareBy<FileSimpleInfo> { entry -> entry.path.pathLevel() }
                .thenBy { entry -> entry.path }
        )
        .forEach { entry ->
            val normalizedPath = normalizeOperationPath(entry.path, normalizedSeparator)
            if (isUnderSkippedDirectory(normalizedPath, skippedDirectories, normalizedSeparator)) {
                skippedCount++
                return@forEach
            }

            if (matcher.matchesOperationSkip(entry.path, entry.isDirectory)) {
                skippedCount++
                if (entry.isDirectory) {
                    skippedDirectories += normalizedPath
                }
                protectSourceAncestors(
                    rootPath = root.path,
                    retainedPath = entry.path,
                    separator = normalizedSeparator,
                    protectedDirectories = protectedDirectories,
                )
                return@forEach
            }

            kept += entry
        }

    return IgnoreOperationFilterResult(
        entries = kept,
        protectedSourceDirectories = protectedDirectories,
        skippedCount = skippedCount,
    )
}

suspend fun findNearestIgnorePreference(
    database: FolderSpanDatabase,
    protocol: FileProtocol,
    protocolId: String,
    path: String,
    separator: String,
): IgnorePreferenceScope? {
    val normalizedSeparator = separator.ifBlank { "/" }
    return buildPathPreferenceCandidates(path, normalizedSeparator).firstNotNullOfOrNull { candidate ->
        val preference = database.filePathPreferenceQueries.queryByPath(
            protocol = protocol,
            protocolId = protocolId,
            path = candidate,
        ).executeAsOneOrNullAwait()
        val ignoreFiles = preference
            ?.ignoreFiles
            ?.filter { item -> item in SupportedIgnoreFileNames }
            ?.distinct()
            .orEmpty()
        if (ignoreFiles.isEmpty()) {
            null
        } else {
            IgnorePreferenceScope(
                protocol = protocol,
                protocolId = protocolId,
                basePath = candidate,
                ignoreFiles = ignoreFiles,
                separator = normalizedSeparator,
            )
        }
    }
}

fun buildPathPreferenceCandidates(path: String, separator: String): List<String> {
    val normalizedSeparator = separator.ifBlank { "/" }
    val normalized = normalizeAbsolutePath(path, normalizedSeparator)
        .ifBlank { normalizedSeparator }
    if (normalized == normalizedSeparator) return listOf(normalizedSeparator)

    if (normalizedSeparator == "/") {
        val absolute = normalized.startsWith("/")
        val parts = normalized.split('/').filter { item -> item.isNotEmpty() }
        return buildList {
            for (index in parts.size downTo 1) {
                val candidate = parts.take(index).joinToString("/")
                add(if (absolute) "/$candidate" else candidate)
            }
            if (absolute) add("/")
        }.distinct()
    }

    val root = resolveNonUnixRoot(normalized, normalizedSeparator)
    val relative = if (root.isNotEmpty()) normalized.removePrefix(root) else normalized
    val parts = relative.split(normalizedSeparator).filter { item -> item.isNotEmpty() }
    return buildList {
        for (index in parts.size downTo 1) {
            val candidate = parts.take(index).joinToString(normalizedSeparator)
            add(if (root.isNotEmpty()) root + candidate else candidate)
        }
        if (root.isNotEmpty()) add(root.removeSuffix(normalizedSeparator).ifBlank { normalizedSeparator })
    }.distinct()
}

private fun protectSourceAncestors(
    rootPath: String,
    retainedPath: String,
    separator: String,
    protectedDirectories: MutableSet<String>,
) {
    val normalizedRoot = normalizeOperationPath(rootPath, separator)
    var current = retainedPath.parentPath(separator)
    var normalizedCurrent = normalizeOperationPath(current, separator)
    while (
        normalizedCurrent == normalizedRoot ||
        isOperationAncestor(normalizedRoot, normalizedCurrent, separator)
    ) {
        protectedDirectories += normalizedCurrent
        if (normalizedCurrent == normalizedRoot) break
        val parent = normalizedCurrent.parentPath(separator)
        val normalizedParent = normalizeOperationPath(parent, separator)
        if (normalizedParent == normalizedCurrent) break
        current = normalizedParent
        normalizedCurrent = normalizeOperationPath(current, separator)
    }
}

private fun isUnderSkippedDirectory(
    path: String,
    skippedDirectories: Set<String>,
    separator: String,
): Boolean {
    return skippedDirectories.any { skippedDirectory ->
        path != skippedDirectory && isOperationAncestor(skippedDirectory, path, separator)
    }
}

private fun isOperationAncestor(parentPath: String, childPath: String, separator: String): Boolean {
    if (parentPath == childPath) return false
    val prefix = if (parentPath == separator) parentPath else parentPath + separator
    return childPath.startsWith(prefix)
}

private fun normalizeOperationPath(path: String, separator: String): String {
    val normalizedSeparator = separator.ifBlank { "/" }
    var normalized = path.trim()
    normalized = if (normalizedSeparator == "/") {
        normalized.replace('\\', '/')
    } else {
        normalized.replace("/", normalizedSeparator)
    }
    while (normalized.length > normalizedSeparator.length && normalized.endsWith(normalizedSeparator)) {
        normalized = normalized.dropLast(normalizedSeparator.length)
    }
    return normalized.ifBlank { normalizedSeparator }
}

suspend fun loadResolvedIgnoreMatcher(
    preference: IgnorePreferenceScope,
    readLines: suspend (String) -> Result<List<String>>,
): ResolvedIgnoreMatcher {
    val rules = buildList {
        for (fileName in preference.ignoreFiles) {
            val filePath = joinIgnorePath(preference.basePath, fileName, preference.separator)
            val lines = readLines(filePath).getOrNull() ?: continue
            addAll(IgnoreMatcher.parse(lines))
        }
    }
    return ResolvedIgnoreMatcher(preference, IgnoreMatcher(rules))
}

suspend fun loadResolvedIgnoreMatcherStrict(
    preference: IgnorePreferenceScope,
    readLines: suspend (String) -> Result<List<String>>,
): Result<ResolvedIgnoreMatcher> {
    val unsupported = preference.ignoreFiles.firstOrNull { fileName -> fileName !in SupportedIgnoreFileNames }
    if (unsupported != null) {
        return Result.failure(IllegalArgumentException("Unsupported Ignore file: $unsupported"))
    }
    val normalizedPreference = preference.copy(
        ignoreFiles = normalizeSupportedIgnoreFileNames(preference.ignoreFiles),
        separator = preference.separator.ifBlank { "/" },
    )
    if (normalizedPreference.ignoreFiles.isEmpty()) {
        return Result.failure(IllegalArgumentException("No Ignore files selected"))
    }
    return runCatching {
        val rules = buildList {
            for (fileName in normalizedPreference.ignoreFiles) {
                val filePath = joinIgnorePath(
                    normalizedPreference.basePath,
                    fileName,
                    normalizedPreference.separator,
                )
                val lines = readLines(filePath).getOrElse { error ->
                    throw IgnoreFileReadException(fileName, error)
                }
                addAll(IgnoreMatcher.parse(lines))
            }
        }
        ResolvedIgnoreMatcher(normalizedPreference, IgnoreMatcher(rules))
    }
}

fun joinIgnorePath(basePath: String, fileName: String, separator: String): String {
    val normalizedSeparator = separator.ifBlank { "/" }
    val base = basePath.ifBlank { normalizedSeparator }
    return when {
        base == normalizedSeparator -> normalizedSeparator + fileName
        base.endsWith(normalizedSeparator) -> base + fileName
        else -> base + normalizedSeparator + fileName
    }
}

fun relativePathUnder(basePath: String, targetPath: String, separator: String): String? {
    val normalizedSeparator = separator.ifBlank { "/" }
    val base = normalizeAbsolutePath(basePath, normalizedSeparator)
    val target = normalizeAbsolutePath(targetPath, normalizedSeparator)
    if (target == base) return ""
    val prefix = if (base == normalizedSeparator) base else base + normalizedSeparator
    if (!target.startsWith(prefix)) return null
    return target.removePrefix(prefix).replace(normalizedSeparator, "/").trim('/')
}

private fun normalizeAbsolutePath(path: String, separator: String): String {
    val normalizedSeparator = separator.ifBlank { "/" }
    var normalized = path.trim()
    normalized = if (normalizedSeparator == "/") {
        normalized.replace('\\', '/')
    } else {
        normalized.replace("/", normalizedSeparator)
    }
    while (normalized.length > normalizedSeparator.length && normalized.endsWith(normalizedSeparator)) {
        normalized = normalized.dropLast(normalizedSeparator.length)
    }
    return normalized
}

private fun resolveNonUnixRoot(path: String, separator: String): String {
    if (separator != "\\") return ""
    if (path.length >= 3 && path[1] == ':' && path[2] == '\\') {
        return path.substring(0, 3)
    }
    return if (path.startsWith("\\")) "\\" else ""
}

data class IgnoreRule(
    val pattern: String,
    val negated: Boolean,
    val anchored: Boolean,
    val directoryOnly: Boolean,
) {
    private val containsSlash = pattern.contains('/')
    private val regex = globToRegex(pattern)

    fun matches(relativePath: String, isDirectory: Boolean): Boolean {
        val normalizedPath = normalizeRelativePath(relativePath)
        if (normalizedPath.isEmpty()) return false

        val candidates = if (directoryOnly) {
            directoryCandidates(normalizedPath, isDirectory)
        } else {
            pathCandidates(normalizedPath, isDirectory)
        }
        if (anchored) {
            return candidates.any { candidate -> regex.matches(candidate) }
        }
        if (containsSlash) {
            return candidates.any { candidate ->
                suffixCandidates(candidate).any { suffix -> regex.matches(suffix) }
            }
        }
        return candidates
            .flatMap { candidate -> candidate.split('/').filter { segment -> segment.isNotEmpty() } }
            .any { segment -> regex.matches(segment) }
    }
}

class IgnoreMatcher(
    private val rules: List<IgnoreRule>,
) {
    fun matches(relativePath: String, isDirectory: Boolean): Boolean {
        var ignored = false
        for (rule in rules) {
            if (rule.matches(relativePath, isDirectory)) {
                ignored = !rule.negated
            }
        }
        return ignored
    }

    companion object {
        fun parse(lines: List<String>): List<IgnoreRule> {
            return lines.mapNotNull(::parseRule)
        }
    }
}

private fun parseRule(rawLine: String): IgnoreRule? {
    val trimmedEnd = trimUnescapedEnd(rawLine)
    if (trimmedEnd.isBlank()) return null

    val commentCandidate = trimmedEnd.trimStart()
    if (commentCandidate.startsWith("#")) return null

    var line = trimmedEnd
    var negated = false
    if (line.startsWith("\\#") || line.startsWith("\\!")) {
        line = line.drop(1)
    } else if (line.startsWith("!")) {
        negated = true
        line = line.drop(1)
    }

    line = unescapeIgnorePattern(line)
    if (line.isBlank()) return null

    val directoryOnly = line.endsWith("/")
    if (directoryOnly) {
        line = line.trimEnd('/')
    }
    val anchored = line.startsWith("/")
    line = line.trimStart('/').replace('\\', '/')
    line = line.split('/').filter { item -> item.isNotEmpty() }.joinToString("/")
    if (line.isBlank()) return null

    return IgnoreRule(
        pattern = line,
        negated = negated,
        anchored = anchored,
        directoryOnly = directoryOnly,
    )
}

private fun trimUnescapedEnd(value: String): String {
    var end = value.length
    while (end > 0 && value[end - 1].isWhitespace()) {
        var slashCount = 0
        var index = end - 2
        while (index >= 0 && value[index] == '\\') {
            slashCount++
            index--
        }
        if (slashCount % 2 == 1) break
        end--
    }
    return value.substring(0, end)
}

private fun unescapeIgnorePattern(value: String): String {
    val output = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '\\' && index + 1 < value.length) {
            val next = value[index + 1]
            if (next == '#' || next == '!' || next == ' ' || next == '\\') {
                output.append(next)
                index += 2
                continue
            }
        }
        output.append(char)
        index++
    }
    return output.toString()
}

private fun normalizeRelativePath(path: String): String {
    return path
        .replace('\\', '/')
        .trim('/')
        .split('/')
        .filter { item -> item.isNotEmpty() }
        .joinToString("/")
}

private fun pathCandidates(relativePath: String, isDirectory: Boolean): List<String> {
    return (listOf(relativePath) + directoryCandidates(relativePath, isDirectory = false)).distinct()
}

private fun directoryCandidates(relativePath: String, isDirectory: Boolean): List<String> {
    val segments = relativePath.split('/').filter { item -> item.isNotEmpty() }
    val maxSize = if (isDirectory) segments.size else (segments.size - 1).coerceAtLeast(0)
    return (1..maxSize).map { size -> segments.take(size).joinToString("/") }
}

private fun suffixCandidates(path: String): List<String> {
    val segments = path.split('/').filter { item -> item.isNotEmpty() }
    return segments.indices.map { index -> segments.drop(index).joinToString("/") }
}

private fun globToRegex(pattern: String): Regex {
    val output = StringBuilder("^")
    var index = 0
    while (index < pattern.length) {
        when (val char = pattern[index]) {
            '*' -> {
                if (index + 1 < pattern.length && pattern[index + 1] == '*') {
                    while (index + 1 < pattern.length && pattern[index + 1] == '*') {
                        index++
                    }
                    if (index + 1 < pattern.length && pattern[index + 1] == '/') {
                        output.append("(?:.*/)?")
                        index++
                    } else {
                        output.append(".*")
                    }
                } else {
                    output.append("[^/]*")
                }
            }

            '?' -> output.append("[^/]")
            '/', '.', '+', '(', ')', '^', '$', '{', '}', '|', '[', ']' -> {
                output.append('\\').append(char)
            }

            else -> output.append(char)
        }
        index++
    }
    output.append('$')
    return Regex(output.toString())
}

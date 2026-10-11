package com.folderspan.service.http.server.linkshare

/**
 * 链路分享批量下载脚本的占位符转义（FS-28）。
 *
 * 会话指纹依赖原始 User-Agent，因此不能改成固定产品 UA，只能按 bash / PowerShell / CMD
 * 各自的赋值语法转义。本地目录名去掉分隔符与控制字符；bash / PowerShell 的远程路径
 * 按段百分号编码，CMD 则剔除会触发展开的元字符。
 */
internal enum class LinkShareDownloadScriptKind {
    Bash,
    PowerShell,
    Cmd,
}

internal fun linkShareDownloadScriptKind(templatePath: String): LinkShareDownloadScriptKind {
    return when {
        templatePath.endsWith(".ps1", ignoreCase = true) -> LinkShareDownloadScriptKind.PowerShell
        templatePath.endsWith(".bat", ignoreCase = true) ||
            templatePath.endsWith(".cmd", ignoreCase = true) -> LinkShareDownloadScriptKind.Cmd
        else -> LinkShareDownloadScriptKind.Bash
    }
}

internal fun escapeLinkShareDownloadScriptValue(
    value: String,
    kind: LinkShareDownloadScriptKind,
): String {
    return when (kind) {
        LinkShareDownloadScriptKind.Bash -> escapeForDoubleQuotedShell(value)
        LinkShareDownloadScriptKind.PowerShell -> escapeForDoubleQuotedPowerShell(value)
        LinkShareDownloadScriptKind.Cmd -> sanitizeForCmdSetValue(value)
    }
}

internal fun sanitizeLinkShareScriptFileName(value: String): String {
    if (value.isEmpty()) return ""
    val filtered = buildString(value.length.coerceAtMost(LINK_SHARE_SCRIPT_FILE_NAME_MAX_LENGTH)) {
        for (char in value) {
            if (length >= LINK_SHARE_SCRIPT_FILE_NAME_MAX_LENGTH) break
            if (char == '/' || char == '\\' || char.isUnsafeScriptControl()) continue
            append(char)
        }
    }
    return if (filtered.isEmpty() || filtered == "." || filtered == "..") {
        LINK_SHARE_SCRIPT_FALLBACK_DIR
    } else {
        filtered
    }
}

internal fun encodeLinkShareScriptRootPath(
    path: String,
    kind: LinkShareDownloadScriptKind,
): String {
    val normalized = path.trim()
    if (normalized.isEmpty() || normalized == "/") return ""
    return if (kind == LinkShareDownloadScriptKind.Cmd) {
        sanitizeForCmdSetValue(normalized)
    } else {
        normalized.urlPath()
    }
}

private fun escapeForDoubleQuotedShell(value: String): String {
    return buildString(value.length) {
        value.forEach { char ->
            when {
                char.isUnsafeScriptControl() -> append('_')
                char == '\\' || char == '"' || char == '$' || char == '`' -> {
                    append('\\')
                    append(char)
                }
                else -> append(char)
            }
        }
    }
}

private fun escapeForDoubleQuotedPowerShell(value: String): String {
    return buildString(value.length) {
        value.forEach { char ->
            when {
                char.isUnsafeScriptControl() -> append('_')
                char == '`' || char == '"' || char == '$' -> {
                    append('`')
                    append(char)
                }
                else -> append(char)
            }
        }
    }
}

private fun sanitizeForCmdSetValue(value: String): String {
    return buildString(value.length) {
        value.forEach { char ->
            when {
                char.isUnsafeScriptControl() -> append('_')
                char == '%' ||
                    char == '"' ||
                    char == '!' ||
                    char == '&' ||
                    char == '|' ||
                    char == '<' ||
                    char == '>' ||
                    char == '^' -> append('_')
                else -> append(char)
            }
        }
    }
}

private fun Char.isUnsafeScriptControl(): Boolean {
    return code < 0x20 ||
        code == 0x7F ||
        this == '\u0085' ||
        this == '\u2028' ||
        this == '\u2029'
}

private const val LINK_SHARE_SCRIPT_FILE_NAME_MAX_LENGTH = 128
private const val LINK_SHARE_SCRIPT_FALLBACK_DIR = "folderspan-download"

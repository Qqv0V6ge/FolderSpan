package com.folderspan.extensions

import java.nio.file.Paths
import kotlin.io.path.name

/**
 * 将路径字符串按 '/' 或 '\' 分隔符拆分为多个片段
 *
 * @param path 要拆分的路径字符串
 * @return 非空且经过修剪的路径片段列表
 */
private fun splitPathSegments(path: String): List<String> {
    return path
        .split('/', '\\')
        .map { item ->  item.trim() }
        .filter { item ->  item.isNotEmpty() }
}

/**
 * 将路径字符串解析为路径片段列表
 *
 * 此函数同时支持 Unix 风格和 Windows 风格的路径。它会自动检测 Windows 路径
 * (例如 "C:\path\to\file") 并使用合适的解析方式。对于 Unix 路径，会尝试使用
 * [java.nio.file.Paths] 进行解析，如果失败则回退到简单的字符串拆分。
 *
 * @receiver 要解析的路径字符串
 * @return 路径片段列表，如果输入为空则返回空列表
 */
actual fun String.parsePath(): List<String> {
    val trimmed = trim()
    if (trimmed.isEmpty()) {
        return emptyList()
    }

    val looksLikeWindowsPath =
        trimmed.length >= 2 && trimmed[1] == ':' && trimmed[0].isLetter()
    if (looksLikeWindowsPath || trimmed.contains('\\')) {
        return splitPathSegments(trimmed)
    }

    return runCatching { Paths.get(trimmed).map { item ->  item.name } }
        .getOrElse { splitPathSegments(trimmed) }
}

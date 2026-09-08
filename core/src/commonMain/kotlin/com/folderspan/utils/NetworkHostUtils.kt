package com.folderspan.utils

/**
 * 主机和端口的数据类
 * @param host 主机地址（域名或IP）
 * @param port 端口号，可为空
 */
data class HostPort(
    val host: String,
    val port: Int?
)

/**
 * 网络主机工具类，提供主机地址与端口的解析和组合功能
 */
object NetworkHostUtils {

    /**
     * 解析主机端口字符串，将其拆分为主机和端口
     *
     * 支持以下格式：
     * - IPv4: "192.168.1.1:8080" 或 "192.168.1.1"
     * - IPv6（带括号）: "[::1]:8080" 或 "[2001:db8::1]"
     * - 域名: "example.com:80" 或 "example.com"
     *
     * @param input 输入的主机端口字符串
     * @return 解析后的 HostPort 对象
     */
    fun splitHostPort(input: String): HostPort {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return HostPort("", null)

        // 处理 IPv6 地址格式（方括号包裹）
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            if (end > 0) {
                val host = trimmed.substring(1, end)
                val port = if (end + 1 < trimmed.length && trimmed[end + 1] == ':') {
                    trimmed.substring(end + 2).toIntOrNull()
                } else {
                    null
                }
                return HostPort(host, port)
            }
        }

        // 处理 IPv4 或域名格式
        val lastColon = trimmed.lastIndexOf(':')
        if (lastColon <= 0) return HostPort(trimmed, null)

        val hostPart = trimmed.substring(0, lastColon)
        val portPart = trimmed.substring(lastColon + 1)
        val port = portPart.toIntOrNull()

        // 如果主机部分包含冒号（可能是未加括号的IPv6），则视为无端口
        return if (port != null && !hostPart.contains(":")) {
            HostPort(hostPart, port)
        } else {
            HostPort(trimmed, null)
        }
    }

    /**
     * 解析主机端口字符串，并在端口缺失时使用默认端口
     *
     * @param input 输入的主机端口字符串
     * @param defaultPort 默认端口号
     * @return 解析后的 HostPort 对象，端口保证不为空
     */
    fun resolveHostPort(input: String, defaultPort: Int): HostPort {
        val parsed = splitHostPort(input)
        val resolvedPort = parsed.port ?: defaultPort
        return HostPort(parsed.host, resolvedPort)
    }

    /**
     * 将主机和端口组合为字符串
     *
     * IPv6 地址会自动添加方括号包裹
     *
     * @param host 主机地址
     * @param port 端口号，为空或小于等于0时只返回主机
     * @return 组合后的字符串，如 "example.com:80" 或 "[::1]:8080"
     */
    fun combineHostPort(host: String, port: Int?): String {
        val trimmedHost = host.trim()
        if (trimmedHost.isBlank()) return ""

        val resolvedPort = port?.takeIf { it > 0 } ?: return trimmedHost

        // IPv6 地址需要用方括号包裹
        val hostWithBrackets = if (trimmedHost.contains(":") &&
            !trimmedHost.startsWith("[") &&
            !trimmedHost.endsWith("]")
        ) {
            "[$trimmedHost]"
        } else {
            trimmedHost
        }
        return "$hostWithBrackets:$resolvedPort"
    }
}

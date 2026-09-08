package com.folderspan.utils

import com.folderspan.exception.AuthorityException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FileSensitivity {
    @SerialName("none")
    None,

    @SerialName("sensitive")
    Sensitive,

    @SerialName("critical")
    Critical,
}

data class SensitivePathClassification(
    val sensitivity: FileSensitivity,
    val category: String = "",
    val isValid: Boolean = true,
) {
    val isProtected: Boolean
        get() = sensitivity != FileSensitivity.None
}

internal data class SensitivePathRule(
    val path: String,
    val sensitivity: FileSensitivity,
    val category: String,
    val includeDescendants: Boolean = true,
)

internal expect fun platformSensitivePathRules(): List<SensitivePathRule>

/**
 * 当前平台默认文件系统在安全边界比较中是否大小写不敏感。
 * 不能以路径分隔符推断：macOS/iOS 分隔符为 "/"，但默认 APFS 卷大小写不敏感。
 */
internal expect val securityBoundaryCaseInsensitive: Boolean

/**
 * 对外部文件请求执行不触碰文件系统的敏感路径分类。
 *
 * Token scope、设备角色和路径授权仍由各自服务负责；本策略只负责在授权之后阻止
 * FolderSpan 私有数据、凭据、运行态和恢复缓存被通用文件接口访问。
 */
object SensitiveFileAccessPolicy {
    private val rules: List<SensitivePathRule> by lazy {
        platformSensitivePathRules() + commonSensitivePathRules()
    }

    fun classify(path: String): SensitivePathClassification =
        classify(path, PathUtils.getPathSeparator(), rules, securityBoundaryCaseInsensitive)

    fun permissionForExternalPath(path: String): FileAccessPermission =
        if (classify(path).isProtected) FileAccessPermission.Denied else FileAccessPermission.Allowed

    fun deniedException(path: String): AuthorityException? {
        lexicalDeniedException(path)?.let { error -> return error }
        val canonical = PathUtils.resolveCanonicalPath(
            FileAccessPermission.Allowed,
            path,
            allowNonExistentLeaf = true,
        ) ?: return null
        if (canonical == path) return null
        return lexicalDeniedException(canonical)
    }

    private fun lexicalDeniedException(path: String): AuthorityException? {
        val classification = classify(path)
        if (!classification.isProtected) return null
        return AuthorityException(protectedPathMessage(classification))
    }

    /**
     * FileProvider 打开/分享用的更窄拒绝规则。
     * Android 把整个 cacheDir 标为 Sensitive，但远程打开会把副本放在 cache 根下；
     * 因此允许 `application_cache`，仍拒绝数据库、TLS 身份、编辑器缓存等具体敏感目录。
     */
    fun deniedExceptionForFileProvider(path: String): AuthorityException? {
        return deniedExceptionForFileProvider(classify(path))
    }

    internal fun deniedExceptionForFileProvider(
        classification: SensitivePathClassification,
    ): AuthorityException? {
        if (!classification.isProtected) return null
        if (
            classification.sensitivity == FileSensitivity.Sensitive &&
            classification.category == "application_cache"
        ) {
            return null
        }
        if (classification.category == "pro_http_cache") {
            return AuthorityException(protectedPathMessage(classification))
        }
        return AuthorityException(protectedPathMessage(classification))
    }

    fun protectedPathMessage(classification: SensitivePathClassification): String {
        val category = classification.category.ifBlank { INVALID_PATH_CATEGORY }
        return "Sensitive application data is protected ($category)"
    }

    internal fun classify(
        path: String,
        separator: String,
        rules: List<SensitivePathRule>,
        caseInsensitive: Boolean = separator == "\\",
    ): SensitivePathClassification {
        val target = comparablePath(path, separator, caseInsensitive)
            ?: return SensitivePathClassification(
                sensitivity = FileSensitivity.Critical,
                category = INVALID_PATH_CATEGORY,
                isValid = false,
            )

        val matched = rules.mapNotNull { rule ->
            val root = comparablePath(rule.path, separator, caseInsensitive) ?: return@mapNotNull null
            val matches = target == root || (
                rule.includeDescendants &&
                    target.startsWith(if (root.endsWith('/')) root else "$root/")
                )
            if (matches) root.length to rule else null
        }.maxWithOrNull(
            compareBy<Pair<Int, SensitivePathRule>> { item -> item.first }
                .thenBy { item -> item.second.sensitivity.ordinal }
        )?.second

        return if (matched == null) {
            SensitivePathClassification(FileSensitivity.None)
        } else {
            SensitivePathClassification(matched.sensitivity, matched.category)
        }
    }

    private fun commonSensitivePathRules(): List<SensitivePathRule> {
        val separator = PathUtils.getPathSeparator()
        val appPath = PathUtils.getAppPath()
        val cachePath = PathUtils.getCachePath()
        val rules = mutableListOf<SensitivePathRule>()

        fun addExact(base: String, name: String, sensitivity: FileSensitivity, category: String) {
            rules += SensitivePathRule(
                path = joinPath(base, name, separator),
                sensitivity = sensitivity,
                category = category,
                includeDescendants = false,
            )
        }

        fun addDirectory(base: String, name: String, sensitivity: FileSensitivity, category: String) {
            rules += SensitivePathRule(
                path = joinPath(base, name, separator),
                sensitivity = sensitivity,
                category = category,
            )
        }

        listOf("folderspan.db", "folderspan.db-wal", "folderspan.db-shm", "folderspan.db-journal")
            .forEach { name -> addExact(appPath, name, FileSensitivity.Critical, "database") }
        addDirectory(appPath, "tls-identity", FileSensitivity.Critical, "tls_identity")

        val runtimeDirectories = listOf(
            "sync-tasks" to "sync_tasks",
            "share-history" to "share_history",
            "task-runtime" to "task_runtime",
            "task-failure-results" to "task_failure_results",
            "sync-stage" to "sync_staging",
            "device_logs" to "device_logs",
        )
        val cacheDirectories = listOf(
            "file-editor-backups" to "editor_backups",
            "file-editor-recovery" to "editor_recovery",
            "editor-content" to "editor_content_cache",
            "file-editor-line-index" to "editor_line_index",
            "mcp-file-staging" to "mcp_staging",
            "pro_http_cache" to "pro_http_cache",
        )
        runtimeDirectories.forEach { (name, category) ->
            addDirectory(appPath, name, FileSensitivity.Sensitive, category)
        }
        cacheDirectories.forEach { (name, category) ->
            addDirectory(cachePath, name, FileSensitivity.Sensitive, category)
        }
        return rules
    }

    private fun comparablePath(path: String, separator: String, caseInsensitive: Boolean): String? {
        val normalized = normalizePathForSecurityBoundary(path, separator) ?: return null
        return if (caseInsensitive) normalized.lowercase() else normalized
    }

    private fun joinPath(base: String, child: String, separator: String): String =
        base.trimEnd('/', '\\') + separator + child

    private const val INVALID_PATH_CATEGORY = "invalid_path"
}

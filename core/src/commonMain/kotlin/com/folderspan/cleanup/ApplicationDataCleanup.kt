package com.folderspan.cleanup

/**
 * 清除 FolderSpan 在当前设备上创建的本地数据。
 *
 * 原生平台会先记录清理请求，并在下次启动、数据库初始化之前执行清理；
 * Web 平台会直接清理浏览器存储，再由界面重新加载应用。
 */
expect object ApplicationDataCleanup {
    val availableCategories: Set<ApplicationDataCleanupCategory>

    val isSupported: Boolean

    suspend fun requestCleanup(categories: Set<ApplicationDataCleanupCategory>): Result<Unit>

    fun completePendingCleanup(): Result<Boolean>
}

enum class ApplicationDataCleanupCategory {
    ApplicationData,
    PreferencesAndAccount,
    TransferHistory,
    CacheAndLogs,
    LoginStartup,
    ;

    companion object {
        val all: Set<ApplicationDataCleanupCategory> =
            ApplicationDataCleanupCategory.entries.toSet()
    }
}

class ApplicationDataCleanupState {
    val availableCategories: Set<ApplicationDataCleanupCategory>
        get() = ApplicationDataCleanup.availableCategories

    val isSupported: Boolean
        get() = ApplicationDataCleanup.isSupported

    suspend fun requestCleanup(categories: Set<ApplicationDataCleanupCategory>): Result<Unit> =
        ApplicationDataCleanup.requestCleanup(categories)
}

private const val CLEANUP_REQUEST_VERSION = "version=1"
private const val CLEANUP_REQUEST_CATEGORIES_PREFIX = "categories="
private const val LEGACY_CLEANUP_REQUEST = "FolderSpan local data cleanup requested"

internal fun encodeApplicationDataCleanupRequest(
    categories: Set<ApplicationDataCleanupCategory>,
): String {
    require(categories.isNotEmpty()) { "At least one application data category must be selected" }
    val categoryNames = ApplicationDataCleanupCategory.entries
        .filter(categories::contains)
        .joinToString(",", transform = ApplicationDataCleanupCategory::name)
    return "$CLEANUP_REQUEST_VERSION\n$CLEANUP_REQUEST_CATEGORIES_PREFIX$categoryNames\n"
}

internal fun decodeApplicationDataCleanupRequest(
    content: String,
): Set<ApplicationDataCleanupCategory> {
    if (content.trim() == LEGACY_CLEANUP_REQUEST) {
        return ApplicationDataCleanupCategory.all
    }

    val lines = content.lineSequence().filter(String::isNotBlank).toList()
    require(lines.firstOrNull() == CLEANUP_REQUEST_VERSION) { "Unsupported cleanup request version" }
    val encodedCategories = lines
        .firstOrNull { line -> line.startsWith(CLEANUP_REQUEST_CATEGORIES_PREFIX) }
        ?.removePrefix(CLEANUP_REQUEST_CATEGORIES_PREFIX)
        .orEmpty()
    val categoriesByName = ApplicationDataCleanupCategory.entries.associateBy { category -> category.name }
    val categories = encodedCategories
        .split(',')
        .filter(String::isNotBlank)
        .mapTo(linkedSetOf()) { name ->
            requireNotNull(categoriesByName[name]) { "Unknown application data cleanup category: $name" }
        }
    require(categories.isNotEmpty()) { "Cleanup request does not contain any categories" }
    return categories
}

internal fun requireAvailableApplicationDataCleanupCategories(
    categories: Set<ApplicationDataCleanupCategory>,
    availableCategories: Set<ApplicationDataCleanupCategory>,
) {
    require(categories.isNotEmpty()) { "At least one application data category must be selected" }
    require(categories.all(availableCategories::contains)) {
        "The cleanup request contains categories that are unavailable on this platform"
    }
}

internal fun isFolderSpanPreferenceKey(key: String): Boolean =
    key.startsWith("settings.") ||
        key.startsWith("auth.") ||
        key.startsWith("app.") ||
        key.startsWith("pro.") ||
        key.startsWith("trustedDeviceTlsFingerprintSha256.") ||
        key.startsWith("trustedTlsFp.")

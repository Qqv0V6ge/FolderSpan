package com.folderspan.ui.state.device

import com.folderspan.utils.FileAccessPermission
import strings.AppStrings

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.isAndroidContentUriPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class DeviceCertificateState(private val database: FolderSpanDatabase) {
    private data class AuthState(
        // Map<连接的token, 角色id>
        val permissions: Map<String, Long> = emptyMap(),
        // Map<设备ID, 连接token>
        val deviceTokens: Map<String, String> = emptyMap(),
        // Map<token, 设备ID>，避免并发遍历 map
        val tokenDeviceIds: Map<String, String> = emptyMap(),
        // Map<token, 鉴权指纹>
        val tokenFingerprints: Map<String, DeviceTokenFingerprint> = emptyMap(),
        // Map<token, 原生设备分享只读路径作用域>
        val deviceSharePathScopes: Map<String, DeviceSharePathScope> = emptyMap(),
    )

    private val authState = MutableStateFlow(AuthState())

    /**
     * 设置设备权限
     * @param deviceId 设备ID
     * @param roleId 角色ID
     */
    fun setDevicePermission(deviceId: String, roleId: Long) {
        authState.update { state ->
            val token = state.deviceTokens[deviceId] ?: return@update state
            state.copy(
                permissions = state.permissions + (token to roleId)
            )
        }
    }

    /**
     * 为临时会话 token 直接授予权限。
     * 不会写入 deviceId -> token 绑定，适用于不依赖 HTTP 心跳的短生命周期传输。
     */
    fun setTokenPermission(token: String, roleId: Long) {
        authState.update { state ->
            val nextState = if (state.tokenDeviceIds.containsKey(token)) {
                removeTokenFromState(state, token)
            } else {
                state
            }
            nextState.copy(
                permissions = nextState.permissions + (token to roleId)
            )
        }
    }

    /**
     * 设置设备token和权限
     * @param deviceId 设备ID
     * @param token 连接token
     * @param roleId 角色ID
     */
    fun setDeviceTokenAndPermission(
        deviceId: String,
        token: String,
        roleId: Long,
        fingerprint: DeviceTokenFingerprint? = null
    ) {
        authState.update { state ->
            var nextState = state
            val previousToken = state.deviceTokens[deviceId]
            if (previousToken != null && previousToken != token) {
                nextState = removeTokenFromState(nextState, previousToken)
            }
            val existingDeviceForToken = nextState.tokenDeviceIds[token]
            if (existingDeviceForToken != null && existingDeviceForToken != deviceId) {
                nextState = removeTokenFromState(nextState, token)
            }

            nextState.copy(
                deviceTokens = nextState.deviceTokens + (deviceId to token),
                tokenDeviceIds = nextState.tokenDeviceIds + (token to deviceId),
                permissions = nextState.permissions + (token to roleId),
                tokenFingerprints = updatedFingerprintMap(
                    currentFingerprints = nextState.tokenFingerprints,
                    token = token,
                    deviceId = deviceId,
                    fingerprint = fingerprint
                )
            )
        }
    }

    /**
     * 移除设备权限
     * @param deviceId 设备ID
     */
    fun removeDevicePermission(deviceId: String) {
        authState.update { state ->
            val token = state.deviceTokens[deviceId] ?: return@update state
            state.copy(
                permissions = state.permissions - token,
                tokenFingerprints = state.tokenFingerprints - token,
                deviceSharePathScopes = state.deviceSharePathScopes - token,
            )
        }
    }

    /**
     * 移除设备token
     * @param deviceId 设备ID
     */
    fun removeDeviceToken(deviceId: String) {
        authState.update { state ->
            val token = state.deviceTokens[deviceId]
            var nextState = state.copy(
                deviceTokens = state.deviceTokens - deviceId
            )
            if (token != null) {
                nextState = nextState.copy(
                    permissions = nextState.permissions - token,
                    tokenDeviceIds = nextState.tokenDeviceIds - token,
                    tokenFingerprints = nextState.tokenFingerprints - token,
                    deviceSharePathScopes = nextState.deviceSharePathScopes - token,
                )
            }
            nextState
        }
    }

    /**
     * 通过 token 获取对应的设备ID
     */
    fun getDeviceIdByToken(token: String): String? {
        return authState.value.tokenDeviceIds[token]
    }

    fun hasTokenFingerprint(token: String): Boolean {
        return authState.value.tokenFingerprints.containsKey(token)
    }

    fun setDeviceSharePathScope(token: String, scope: DeviceSharePathScope) {
        authState.update { state ->
            if (!state.permissions.containsKey(token)) return@update state
            state.copy(deviceSharePathScopes = state.deviceSharePathScopes + (token to scope))
        }
    }

    fun getDeviceSharePathScope(token: String): DeviceSharePathScope? {
        return authState.value.deviceSharePathScopes[token]
    }

    /**
     * 直接通过 token 移除授权（同时清理 token 与 deviceId 的关联）。
     */
    fun removeToken(token: String) {
        authState.update { state ->
            removeTokenFromState(state, token)
        }
    }

    /**
     * 校验 token 是否有效（是否在当前已登记的权限映射中）
     */
    fun isTokenValid(token: String, fingerprint: DeviceTokenFingerprint? = null): Boolean {
        val state = authState.value
        if (!state.permissions.containsKey(token)) return false
        if (fingerprint == null) return true
        val expectedFingerprint = state.tokenFingerprints[token] ?: return true
        val matched = expectedFingerprint.matches(fingerprint)
        if (!matched) {
            LogKit.w(
                AppStrings.ui_authentication_fingerprint_does_not_match +
                    "expectedDevice=${expectedFingerprint.deviceId}, actualDevice=${fingerprint.deviceId}, " +
                    "expectedIp=${expectedFingerprint.clientIp}, actualIp=${fingerprint.clientIp}, " +
                    "expectedUa=${expectedFingerprint.userAgent}, actualUa=${fingerprint.userAgent}"
            )
            removeToken(token)
        }
        return matched
    }

    /**
     * 检查是否具备指定权限。
     * 路径匹配前会规范化，兼容 Windows 分隔符和大小写差异。
     *
     * @param fileAccessPermission 是否允许在权限校验期间观察本地文件系统；调用方应先完成认证与敏感路径分类
     * @param token 设备认证 token
     * @param path 目标路径
     * @param permission 权限类型（read/write/remove/rename）
     * @return true 表示无权限（应拒绝），false 表示允许
     */
    suspend fun checkPermission(
        fileAccessPermission: FileAccessPermission,
        token: String,
        path: String,
        permission: String,
    ): Boolean {
        val state = authState.value
        val roleId = state.permissions[token] ?: return true
        val shareScope = state.deviceSharePathScopes[token]
        if (shareScope != null) {
            if (permission != "read") return true
            return !isSafeDeviceShareContentPath(fileAccessPermission, shareScope, path)
        }

        val rolePermissions = withContext(Dispatchers.Default) {
            database.deviceRoleDevicePermissionQueries
                .queryByRoleId(roleId)
                .executeAsListAwait()
        }

        if (rolePermissions.isEmpty()) {
            return true
        }

        val normalizedPath = normalizePermissionPath(path)
        val firstOrNull = rolePermissions.firstOrNull { item ->
            val permissionStatus = mapOf(
                "read" to item.read,
                "write" to item.write,
                "remove" to item.remove,
                "rename" to item.rename,
            )[permission] ?: false

            if (!permissionStatus) {
                return@firstOrNull false
            }

            val permissionPath = normalizePermissionPath(item.path ?: "")
            if (permissionPath.isEmpty()) {
                return@firstOrNull false
            }

            if (normalizedPath == permissionPath) {
                return@firstOrNull true
            }
            if (item.useAll != true || !isChildPermissionPath(normalizedPath, permissionPath)) {
                return@firstOrNull false
            }
            val boundaryRoot = permissionBoundaryRoot(normalizedPath, permissionPath)
                ?: return@firstOrNull false

            // Denied 仅用于调用方明确禁止文件系统探测的场景；授权后的普通外部路径使用
            // Allowed，以便校验根目录边界和符号链接安全性。
            if (fileAccessPermission == FileAccessPermission.Denied) {
                return@firstOrNull true
            }

            val terminalLinkOperation =
                permission in setOf("remove", "rename") && PathUtils.isSymbolicLink(fileAccessPermission, normalizedPath)
            val terminalLinkParentIsSafe = terminalLinkOperation && PathUtils.isPathWithinRoot(fileAccessPermission,
                rootPath = boundaryRoot,
                targetPath = parentPermissionPath(normalizedPath),
                allowNonExistentLeaf = false,
            )
            terminalLinkParentIsSafe || PathUtils.isPathWithinRoot(fileAccessPermission,
                rootPath = boundaryRoot,
                targetPath = normalizedPath,
                allowNonExistentLeaf = permission == "write" || permission == "rename",
            )
        }

        return firstOrNull == null
    }

    fun isDeviceShareListingAllowed(token: String, path: String): Boolean {
        val scope = authState.value.deviceSharePathScopes[token] ?: return true
        return scope.allowsListingPath(path)
    }

    fun filterDeviceShareListing(token: String, entries: List<FileSimpleInfo>): List<FileSimpleInfo> {
        val scope = authState.value.deviceSharePathScopes[token] ?: return entries
        return scope.filterListing(entries)
    }

    fun deviceShareRootPaths(token: String): List<String>? {
        val scope = authState.value.deviceSharePathScopes[token] ?: return null
        return scope.grants.map { grant -> grant.path }.distinct()
    }

    private fun isSafeDeviceShareContentPath(
        fileAccessPermission: FileAccessPermission,
        scope: DeviceSharePathScope,
        path: String,
    ): Boolean {
        val grant = scope.matchingContentGrant(path) ?: return false
        if (isAndroidContentUriPath(path) || isAndroidContentUriPath(grant.path)) {
            return !grant.isDirectory &&
                isAndroidContentUriPath(grant.path) &&
                isAndroidContentUriPath(path) &&
                normalizeDeviceSharePath(path) == grant.path
        }
        if (PathUtils.isSymbolicLink(fileAccessPermission, path)) return false
        val canonicalPath = PathUtils.resolveCanonicalPath(fileAccessPermission, path) ?: return false
        if (normalizeDeviceSharePath(canonicalPath) != normalizeDeviceSharePath(path)) return false
        return if (grant.isDirectory) {
            val canonicalRoot = PathUtils.resolveCanonicalPath(fileAccessPermission, grant.path) ?: return false
            if (normalizeDeviceSharePath(canonicalRoot) != grant.path) return false
            PathUtils.isPathWithinRoot(
                permission = fileAccessPermission,
                rootPath = grant.path,
                targetPath = path,
                allowNonExistentLeaf = false,
            )
        } else {
            normalizeDeviceSharePath(path) == grant.path
        }
    }

    /**
     * 规范化权限路径，统一跨平台格式。
     *
     * @param path 原始路径
     * @return 规范化后的路径
     */
    private fun normalizePermissionPath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return ""
        return if (PathUtils.getPathSeparator() == "\\") {
            normalizeWindowsPath(trimmed)
        } else {
            normalizeUnixPath(trimmed)
        }
    }

    /**
     * Windows 路径不区分大小写，分隔符统一为反斜杠。
     * 会将盘符根路径统一为 `c:\` 形式。
     *
     * @param path 原始路径
     * @return 规范化后的路径
     */
    private fun normalizeWindowsPath(path: String): String {
        val normalized = path.replace('/', '\\').trim()
        val driveRootRegex = Regex("^[A-Za-z]:\\\\?$")
        if (driveRootRegex.matches(normalized)) {
            val drive = normalized.substring(0, 2).lowercase()
            return "$drive\\"
        }
        val drive = if (normalized.length >= 2 && normalized[1] == ':' && normalized[0].isLetter()) {
            normalized.substring(0, 2).lowercase()
        } else {
            ""
        }
        val absolute = drive.isNotEmpty() || normalized.startsWith("\\")
        val pathWithoutDrive = if (drive.isNotEmpty()) normalized.substring(2) else normalized
        val parts = normalizePathSegments(pathWithoutDrive.split('\\'), absolute)
        val prefix = when {
            drive.isNotEmpty() -> "$drive\\"
            normalized.startsWith("\\") -> "\\"
            else -> ""
        }
        return when {
            parts.isEmpty() -> prefix.ifEmpty { "" }
            prefix.isNotEmpty() -> prefix + parts.joinToString("\\")
            else -> parts.joinToString("\\")
        }.lowercase()
    }

    /**
     * Unix 路径区分大小写，分隔符统一为斜杠。
     *
     * @param path 原始路径
     * @return 规范化后的路径
     */
    private fun normalizeUnixPath(path: String): String {
        val normalized = path.replace('\\', '/')
        val absolute = normalized.startsWith("/")
        val parts = normalizePathSegments(normalized.split('/'), absolute)
        return when {
            absolute && parts.isEmpty() -> "/"
            absolute -> "/" + parts.joinToString("/")
            else -> parts.joinToString("/")
        }
    }

    private fun isChildPermissionPath(path: String, permissionPath: String): Boolean {
        if (permissionPath == "/") return path.startsWith(permissionPath)
        if (permissionPath == "\\") return permissionBoundaryRoot(path, permissionPath) != null
        val separator = if (permissionPath.contains('\\')) "\\" else "/"
        val root = permissionPath.trimEnd('/', '\\')
        return path.startsWith(root + separator)
    }

    private fun parentPermissionPath(path: String): String {
        val separator = if (path.contains('\\')) '\\' else '/'
        val normalized = path.trimEnd('/', '\\')
        val separatorIndex = normalized.lastIndexOf(separator)
        return when {
            separatorIndex < 0 -> ""
            separatorIndex == 0 -> separator.toString()
            separator == '\\' && separatorIndex == 2 && normalized.getOrNull(1) == ':' ->
                normalized.substring(0, separatorIndex + 1)

            else -> normalized.substring(0, separatorIndex)
        }
    }

    private fun normalizePathSegments(
        rawSegments: List<String>,
        absolute: Boolean,
    ): List<String> {
        val segments = mutableListOf<String>()
        rawSegments
            .map { item -> item.trim() }
            .filter { item -> item.isNotEmpty() }
            .forEach { segment ->
                when (segment) {
                    "." -> Unit
                    ".." -> {
                        if (segments.isNotEmpty() && segments.last() != "..") {
                            segments.removeAt(segments.lastIndex)
                        } else if (!absolute) {
                            segments.add(segment)
                        }
                    }

                    else -> segments.add(segment)
                }
            }
        return segments
    }

    private fun removeTokenFromState(
        state: AuthState,
        token: String
    ): AuthState {
        val deviceId = state.tokenDeviceIds[token]
        return state.copy(
            permissions = state.permissions - token,
            deviceTokens = if (deviceId == null) state.deviceTokens else state.deviceTokens - deviceId,
            tokenDeviceIds = state.tokenDeviceIds - token,
            tokenFingerprints = state.tokenFingerprints - token,
            deviceSharePathScopes = state.deviceSharePathScopes - token,
        )
    }

    private fun updatedFingerprintMap(
        currentFingerprints: Map<String, DeviceTokenFingerprint>,
        token: String,
        deviceId: String,
        fingerprint: DeviceTokenFingerprint?
    ): Map<String, DeviceTokenFingerprint> {
        if (fingerprint == null) {
            return currentFingerprints
        }
        return currentFingerprints + (token to fingerprint.copy(deviceId = deviceId))
    }
}

internal fun permissionBoundaryRoot(path: String, permissionPath: String): String? {
    if (permissionPath != "\\") return permissionPath
    if (path.length >= 3 && path[0].isLetter() && path[1] == ':' && path[2] == '\\') {
        return path.substring(0, 3)
    }
    return "\\".takeIf { path.startsWith(it) }
}

data class DeviceTokenFingerprint(
    val deviceId: String,
    val clientIp: String?,
    val userAgent: String?
) {
    fun matches(other: DeviceTokenFingerprint?): Boolean {
        if (other == null) return false

        if (deviceId.isNotEmpty()) {
            if (other.deviceId.isEmpty()) return false
            if (deviceId != other.deviceId) return false
        }

        val expectedIp = clientIp.normalizedClientIp()
        val actualIp = other.clientIp.normalizedClientIp()
        if (expectedIp.isNullOrBlank() || actualIp.isNullOrBlank() || expectedIp != actualIp) {
            return false
        }

        val expectedUserAgent = userAgent?.trim()
        val actualUserAgent = other.userAgent?.trim()
        return !(expectedUserAgent.isNullOrBlank() || actualUserAgent.isNullOrBlank() || expectedUserAgent != actualUserAgent)
    }

    private fun String?.normalizedClientIp(): String? {
        val value = this
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.substringBefore('%')
            ?.lowercase()
            ?: return null
        return value.removePrefix("::ffff:")
    }
}

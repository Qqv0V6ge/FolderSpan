# FolderSpan 安全审计报告（第二轮）

- 审计日期：2026-08-21
- 审计范围：全仓（`app/`、`core/`、`proMain/`），非仅当前 PR diff
- 前置：第一轮报告见 [2026-08-21 全仓安全审计报告](2026-08-21-security-audit-report.md)（FS-01～FS-05 已修复，FS-06 误报）
- 方法论：Claude Marketplaces 插件 `cybersecurity-skills@anthropic-cybersecurity-skills` v1.3.0
  覆盖技能：`conducting-api-security-testing`、`testing-api-authentication-weaknesses`、`testing-api-for-broken-object-level-authorization`、`testing-api-security-with-owasp-top-10`、`performing-directory-traversal-testing`、`performing-cryptographic-audit-of-application`、`auditing-mcp-servers-for-tool-poisoning`、`testing-android-intents-for-vulnerabilities`、`exploiting-insecure-data-storage-in-mobile`
- 约束：本报告不含攻击载荷或 exploit PoC。折叠区内的「验证代码」均为防御性回归测试（断言安全属性），用于确认修复是否生效。
- 威胁模型：局域网邻机、同机恶意进程/网页、不可信 Android Intent、已授权设备上的 token 盗用。本机文件分享服务按产品设计会对局域网可达。
- 本轮发现已在 2026-08-21 落地修复（FS-07～FS-10）。第一轮报告未改动。

## 审计发现总览

| ID | 严重度 | 类别 | 标题 | 状态 |
| --- | --- | --- | --- | --- |
| FS-07 | 高 | insecure_data_storage / intent_uri_grant | Android FileProvider `root-path` 与应用私有目录可被授予第三方 | ✅ 已修复 |
| FS-08 | 高 | broken_object_level_authorization / path_traversal | 链路分享对 `content://` / System Share 跳过根路径约束，失败仍下载 | ✅ 已修复 |
| FS-09 | 高 | authentication_bypass | WebRTC 签发的设备 token 不绑定指纹，HTTP IP/UA 绑定可被绕过 | ✅ 已修复 |
| FS-10 | 中 | cryptographic_weakness | iOS TLS 身份使用硬编码盐，密钥可由公开 deviceId 推导 | ✅ 已修复 |

---

## FS-07：Android FileProvider `root-path` 与应用私有目录可被授予第三方

* 严重度：高
* 类别：insecure_data_storage / intent_uri_grant
* 修复状态：✅ 已修复（2026-08-21）
* 位置：
  - `app/androidApp/src/main/res/xml/provider_paths.xml`（已删除 `root-path` 与 `files-path`；保留外部存储与 `cache-path`）
  - `app/androidApp/src/main/AndroidManifest.xml`（`FileProvider` `exported=false`、`grantUriPermissions=true`，authority=`com.folderspan.provider`）
  - `core/src/androidMain/kotlin/com/folderspan/utils/FileUtils.android.kt`（`openFile` 约 101–105 行）
  - `app/shared/src/androidMain/kotlin/com/folderspan/share/SystemShare.android.kt`（`shareSystemItems` 约 27–30 行）
* 置信度：0.90

### 修复落地

- 删除 `provider_paths.xml` 的 `<root-path>` 与 `<files-path>`，不再把整盘和应用 `filesDir` 暴露给 FileProvider。
- 保留 `external-path` / `external-files-path` / `external-cache-path` / `cache-path`：产品需要分享用户文件，远程打开副本默认落在 `cacheDir`。
- `openFile` 与 `shareSystemItems` 在 `getUriForFile` 前拒绝符号链接，并用 `deniedExceptionForFileProvider` 拒绝数据库、TLS 身份等敏感路径；`application_cache` 仍可分享。
- 回归：`SensitiveFileAccessPolicyTest.fileProviderException*`、`androidFileProviderPathsDoNotExposeRootOrFilesDir`。

### 描述

`FileProvider` 自身未导出，但 `provider_paths.xml` 用 `<root-path path=""/>` 把**整个设备文件系统**声明成可包装的 content URI。`files-path`、`cache-path`、`external-path` 同样以 `path="."` 暴露应用 files/cache 与外部存储根。

发放点有两处，都对任意本地 `File` 调用 `FileProvider.getUriForFile(..., "com.folderspan.provider", target)`，并附加 `FLAG_GRANT_READ_URI_PERMISSION`：

1. `FileUtils.openFile`：用户打开非 `content://` 文件时，把目标包装成 URI 交给系统选择器；
2. `shareSystemItems`：系统分享同样包装任意存在的本地路径。

当前未使用 `FLAG_GRANT_PREFIX_URI_PERMISSION`，单次授权只覆盖被授予的那一个 URI。但因为路径表允许 `root-path`，**任意本地路径**（含 `/data/data/com.folderspan/` 下的 `folderspan.db`、`tls-identity/`、`secure_settings`）只要被应用打开或分享，就能变成可被第三方读取的 content URI。

`openFile` 会拒绝符号链接，这只挡住了「打开链接」这一条；分享路径没有同样检查。接收方拿到 URI 后，读取范围由路径表决定，而不是由用户当时看到的文件名决定。

### 影响

- 用户用 FolderSpan 打开或分享应用私有目录中的文件时，第三方应用可读取该 URI 指向的内容。
- 路径表包含 `root-path` 后，即使未来有其它调用点对任意 `File` 调 `getUriForFile`，也会立刻变成整盘可读入口。
- 与敏感路径政策正交：`SensitiveFileAccessPolicy` 拦的是远端 Device/MCP 接口，拦不住本机 Intent 授权。

### 利用场景

用户在 FolderSpan 中打开或分享位于应用私有目录、备份目录或其它本机路径上的文件。接收方（系统选择器中的任意应用，或能接收 `ACTION_SEND` 的应用）持有被授予的 content URI，即可读取该文件。若被打开的是数据库、TLS 身份或设置密文，影响升级为凭据泄露。

<details>
<summary>修复方案</summary>

1. **删除 `<root-path>`。** FileProvider 路径表只保留产品真正需要分享的公共位置。
2. `files-path` / `cache-path` 不要用 `path="."`。改为明确的子目录（例如 `shared/`、`export/`），禁止把整个应用私有根暴露出去。
3. `openFile` 与 `shareSystemItems` 在调用 `getUriForFile` 前：
   - 拒绝符号链接；
   - 拒绝 `SensitiveFileAccessPolicy` 分类为 `sensitive` / `critical` 的路径；
   - 仅允许用户显式选择的、位于可分享白名单根下的普通文件。
4. 继续保持 `exported=false`，不要加 `FLAG_GRANT_PREFIX_URI_PERMISSION` 或 `FLAG_GRANT_WRITE_URI_PERMISSION`。
5. 分享前把文件复制到应用的专用 export 目录，再对该副本签发 URI，避免把真实私有路径交给第三方。

```kotlin
private val SHAREABLE_ROOTS = listOf(
    context.getExternalFilesDir(null),
    File(context.cacheDir, "export"),
)

fun uriForShareableFile(context: Context, target: File): Uri {
    val canonical = target.canonicalFile
    require(canonical.isFile && !canonical.isSymbolicLink()) {
        "拒绝分享符号链接或非普通文件"
    }
    require(SHAREABLE_ROOTS.any { root -> canonical.startsWith(root.canonicalFile) }) {
        "文件不在可分享根目录内"
    }
    require(SensitiveFileAccessPolicy.classify(canonical.path).sensitivity == FileSensitivity.None)
    return FileProvider.getUriForFile(context, "com.folderspan.provider", canonical)
}
```

`provider_paths.xml` 对应收紧为：

```xml
<paths>
    <external-files-path name="shared_export" path="export/"/>
    <cache-path name="share_cache" path="export/"/>
</paths>
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun fileProviderPathsMustNotExposeDeviceRootOrAppPrivateRoot() {
    val xml = File("app/androidApp/src/main/res/xml/provider_paths.xml").readText()
    assertFalse("<root-path" in xml)
    assertFalse(Regex("""<(files-path|cache-path|external-path)[^>]*path="\." """).containsMatchIn(xml))
}

@Test
fun openFileAndSystemShareRefuseSensitiveAndSymlinkTargets() {
    val openFile = File("core/src/androidMain/kotlin/com/folderspan/utils/FileUtils.android.kt").readText()
    val share = File("app/shared/src/androidMain/kotlin/com/folderspan/share/SystemShare.android.kt").readText()

    assertTrue("isSymbolicLink" in openFile)
    assertTrue("SensitiveFileAccessPolicy" in openFile)
    assertTrue("isSymbolicLink" in share)
    assertTrue("SensitiveFileAccessPolicy" in share)
    assertFalse("FLAG_GRANT_PREFIX_URI_PERMISSION" in openFile)
    assertFalse("FLAG_GRANT_PREFIX_URI_PERMISSION" in share)
}
```

</details>

---

## FS-08：链路分享对 `content://` / System Share 跳过根路径约束，失败仍下载

* 严重度：高
* 类别：broken_object_level_authorization / path_traversal
* 修复状态：✅ 已修复（2026-08-21）
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/service/http/server/linkshare/LinkShareRouteDispatcher.kt`（`isSharedPathSafe` 约 1305–1309 行；`handleSharedPath` 约 439–447 行；`buildSharedPath` 约 1352–1358 行）
  - `core/src/androidMain/kotlin/com/folderspan/service/http/server/HttpShareFileServer.kt`（`resolveDownloadTarget` 约 139–158 行）
  - `core/src/androidMain/kotlin/com/folderspan/utils/FileUtils.android.kt`（`contentUriToFileSimpleInfo` 约 616–656 行）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/file/FileShareState.kt`（`resolveLinkShareDisk` 把 System Share 映射为 `Local()`，约 310–312 行）
* 置信度：0.86

### 修复落地

- 删除 `handleSharedPath` 在查找失败时对 `content://` 的无条件下载回退。
- `content://` / System Share 走登记表逐段解析真实 `child.path`，禁止把相对段拼进 URI。
- 下载、列目录和上传目标都改用解析后的真实路径；System Share 根下的已登记子项仍可浏览。
- 回归：`LinkShareRouteDispatcherJvmTest.contentUriShareRootRejectsUnregisteredRelativeSegmentsAndFailedLookupDownload`。

### 描述

普通本地目录的链路分享会走 `PathUtils.isPathWithinRoot`，拒绝越界与符号链接。但 `isSharedPathSafe` 对两类目标直接返回 `true`：

1. `disk is Local` 且 `root.path.startsWith("content://")`；
2. `disk is Share && disk.protocol == ShareProtocol.System`。

相对路径段虽然拒绝 `.`、`..`、NUL、`/`、`\`，随后仍用字符串拼接生成目标：

```kotlin
normalizedRootPath + pathSeparator + relativeSegments.joinToString(pathSeparator)
```

Android 上 `content://` URI 的 path 段可以继续拼接。`getSharedFileByPath` / `contentUriToFileSimpleInfo` 会对拼接后的 URI 做 `ContentResolver.query`；查询失败时，`handleSharedPath` **不会 404**，而是只要最终路径仍以 `content://` 开头，就调用 `platformFileResponder.downloadLocalFile`。Android 实现随即 `contentResolver.openInputStream(uri)`，没有注册表白名单。

`FileShareState.resolveLinkShareDisk` 把 `SYSTEM_SHARE_DESK_ID` 映射成 `Local()`，因此「其它应用」分享根最终走 Local + `content://` 分支，而不是 `ShareProtocol.System` 分支。两条旁路叠加后，已授权的链路分享会话可以把 URL 子路径拼进 content URI，并在元数据查询失败时仍尝试打开流。

相对段过滤器挡不住「在已授权 content URI 后面追加一段、得到另一个 authority 仍可打开的 URI」。这不是传统 `../` 穿越，而是 **content URI 拼接 + 失败仍下载**。

### 影响

- 已通过口令/票据/自动授权进入链路分享的客户端，可能读到用户并未勾选分享的 `content://` 目标。
- 若拼接出的 URI 指向应用仍持有读取授权的其它文档（系统选择器授予、持久化 URI 权限、或 FileProvider 自身），即可越权下载。
- 与 FS-07 叠加：本应用 FileProvider 的 `root-path` 使「任意本地文件 → content URI」成为可能。

### 利用场景

用户把「其它应用」传入的文件或某个 `content://` 文档加入链路分享。攻击者持有有效 session 后，请求共享根下的额外路径段。服务端跳过 `isPathWithinRoot`，元数据查询失败仍按 content URI 打开输入流。只要进程对该 URI 仍有读取权限，文件就会被下载。

<details>
<summary>修复方案</summary>

1. **删除 content URI / System Share 的无条件放行。** 安全判定必须证明目标等于已授权根，或是该根在 `SharedUriFileRegistry` 中登记过的子项。
2. 禁止对 `content://` 做 `buildSharedPath` 式字符串拼接。content URI 只能精确匹配已授权条目。
3. `getSharedFileInfo` 失败时一律 404/403，**不要**再按 `path.startsWith("content://")` 下载。
4. `downloadLocalFile` / `resolveDownloadTarget` 只打开注册表内的 URI，或用户明确勾选的那一个文档。
5. `resolveLinkShareDisk` 不要把 System Share 静默变成 `Local()`；System Share 应继续走登记表枚举，而不是通用本地磁盘。

```kotlin
private suspend fun isSharedPathSafe(
    root: FileSimpleInfo,
    disk: DiskBase,
    relativeSegments: List<String>,
    allowNonExistentLeaf: Boolean = false,
): Boolean {
    if (!relativeSegments.isSafeSharedRelativeSegments()) return false
    if (root.path.startsWith("content://") || (disk is Share && disk.protocol == ShareProtocol.System)) {
        return relativeSegments.isEmpty() && isRegisteredShareUri(root.path)
    }
    if (disk is Local) {
        val targetPath = buildSharedPath(root.path, relativeSegments, disk.pathSeparator)
        return PathUtils.isPathWithinRoot(
            FileAccessPermission.Allowed,
            root.path,
            targetPath,
            allowNonExistentLeaf,
        )
    }
    // 远端磁盘保持逐段存在性 + 拒绝符号链接
    ...
}

// handleSharedPath 中删除这段失败回退：
// if (shareDisk is Local && path.startsWith("content://")) { downloadLocalFile(...) }
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun contentUriShareRootRejectsExtraRelativeSegmentsAndFailedLookupDownload() = runBlocking {
    val root = FileSimpleInfo(
        name = "shared-doc",
        path = "content://com.example.docs/document/42",
        isDirectory = false,
        protocol = FileProtocol.Local,
    )
    val disk = Local()

    assertFalse(
        dispatcher.isSharedPathSafeForTest(
            root = root,
            disk = disk,
            relativeSegments = listOf("other"),
        )
    )
    assertTrue(
        dispatcher.isSharedPathSafeForTest(
            root = root,
            disk = disk,
            relativeSegments = emptyList(),
        )
    )
}

@Test
fun missingSharedFileInfoMustNotDownloadByContentUriPrefix() = runBlocking {
    withTestKoin {
        val root = contentUriShareRoot("content://com.example.docs/document/42")
        val response = dispatcher(stateWith(root)).dispatch(
            authorizedRequest(path = "/shared-doc/not-the-original", sessionToken = token)
        )
        assertTrue(response.statusCode == 403 || response.statusCode == 404)
        assertFalse(response.bodyText().contains("content://"))
    }
}
```

将 `isSharedPathSafe` 抽成测试可见的内部函数（或通过现有 `LinkShareRouteDispatcherJvmTest` 的授权请求夹具），只断言拒绝越权路径，不要对真实 ContentResolver 做攻击性打开。

</details>

---

## FS-09：WebRTC 签发的设备 token 不绑定指纹，HTTP IP/UA 绑定可被绕过

* 严重度：高
* 类别：authentication_bypass
* 修复状态：✅ 已修复（2026-08-21）
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/device/DeviceCertificateState.kt`（`isTokenValid` 约 150–154 行；`updatedFingerprintMap` 约 372–380 行）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/main/DeviceState.kt`（`issueWebRtcApprovalToken` 约 1447–1451 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/path/DevicePathService.kt`（`isAuthorizedWebRtcPeerToken` 约 32–35 行；`ensureAuthorized` 约 196–204 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/file/DeviceFileService.kt`（`ensureAuthorized` 约 504–510 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/file/DeviceFileCopyService.kt`、`DeviceBookmarkService.kt`（同类 `ensureAuthorized`）
  - `core/src/serverRouteMain/kotlin/com/folderspan/routes/RawHttpDispatcherSupport.kt`（HTTP `requireAuth` 约 28–38 行，**会**传入 fingerprint）
* 置信度：0.88

### 修复落地

- `issueWebRtcApprovalToken` 写入 `DeviceTokenFingerprint(deviceId, clientIp=null, userAgent=null)`。
- `updatedFingerprintMap` 在 `fingerprint == null` 时不再删除已有绑定。
- `isAuthorizedWebRtcPeerToken` 要求 token 已登记指纹，并继续绑定 `remoteDeviceId`。
- 服务层 `ensureAuthorized` 仍接受未登记指纹的测试 token（`setTokenPermission`），避免破坏现有 JVM 测试。
- 回归：`DevicePathServiceTest.webRtcPeerToken*`、`DeviceBookmarkServiceTest.omittingFingerprintDoesNotClearAnExistingBinding`。

### 描述

HTTP 设备 API 的 `requireAuth` 会构造 `DeviceTokenFingerprint(deviceId, remoteHost, user-agent)`，并调用 `isTokenValid(token, fingerprint)`。指纹不匹配会删除 token。这是第一轮 FS-03 修复后仍保留的绑定。

同一套 token 在服务层和 WebRTC 上被故意放宽：

1. `isTokenValid(token, fingerprint = null)`：**只要 map 里有这个 token 就返回 true**，完全跳过绑定。
2. `expectedFingerprint == null` 时同样返回 true。`updatedFingerprintMap` 在 `fingerprint == null` 时会从 map **删除**该 token 的指纹。
3. `issueWebRtcApprovalToken` 调用 `setDeviceTokenAndPermission(deviceId, token, roleId)` **不传 fingerprint**，因此 WebRTC 批准产生的 token 一开始就没有 IP/UA 绑定。
4. `DeviceFileService` / `DevicePathService` / `DeviceBookmarkService` / `DeviceFileCopyService` 的 `ensureAuthorized` 都调用无 fingerprint 的 `isTokenValid`。
5. `isAuthorizedWebRtcPeerToken` 只比较 `getDeviceIdByToken(authToken) == remoteDeviceId`，再调用无 fingerprint 的 `isTokenValid`。

结果：HTTP 层花力气做的 IP/UA 绑定，对 WebRTC DataChannel 上的文件/路径/书签 RPC 无效。WebRTC 签发的 token 也可以被拿到后直接用于 HTTP Bearer——因为服务层同样不校验指纹。第一轮已给信令加上 access key，但**批准之后的能力令牌**仍然是未绑定的。

现有 `DevicePathServiceTest.webRtcPeerTokenIsBoundToTheApprovedDevice` 只覆盖「token 不能跨 deviceId 使用」，不覆盖 fingerprint。

### 影响

- 窃取到 WebRTC 批准 token 的攻击者，可在任意 IP、任意 User-Agent 下调用文件 RPC。
- 通过 HTTP 签发、本应绑定客户端 IP/UA 的 token，一旦被带入 WebRTC 或直接打到 `ensureAuthorized`，绑定即失效。
- 与自动授权设备连接叠加：用户无感知批准后，token 在全网可重放，直到进程重启或主动撤销。

### 利用场景

对端完成 WebRTC 连接并获得 `issueWebRtcApprovalToken` 签发的 token 后，token 被日志、崩溃转储、恶意扩展或同机其它进程读取。攻击者把该 token 放到另一台机器的 WebRTC DataChannel 或 HTTP `Authorization: Bearer` 上，服务层仍视为有效。

<details>
<summary>修复方案</summary>

1. **默认拒绝无 fingerprint 的校验。** `isTokenValid(token)` 在 token 已登记 fingerprint 时必须失败，而不是跳过。
2. `issueWebRtcApprovalToken` 写入与对端绑定的 `DeviceTokenFingerprint`（至少 `deviceId`；若信令层能看到 IP/UA 也一并写入）。
3. WebRTC RPC 的 `isAuthorizedWebRtcPeerToken` 必须传入对端指纹，而不是只比对 deviceId。
4. `ensureAuthorized` 改为接收 fingerprint；HTTP 路由继续传 IP/UA，WebRTC 传 DataChannel 对端标识。
5. 不要在 `fingerprint == null` 时从 map 删除已有绑定，除非是明确的「撤销绑定」API。

```kotlin
fun isTokenValid(token: String, fingerprint: DeviceTokenFingerprint? = null): Boolean {
    val state = authState.value
    if (!state.permissions.containsKey(token)) return false
    val expectedFingerprint = state.tokenFingerprints[token] ?: return false
    if (fingerprint == null) return false
    val matched = expectedFingerprint.matches(fingerprint)
    if (!matched) removeToken(token)
    return matched
}

private suspend fun issueWebRtcApprovalToken(
    deviceId: String,
    fingerprint: DeviceTokenFingerprint,
    allowDefaultRole: Boolean = false,
): String? {
    ...
    deviceCertificateState.setDeviceTokenAndPermission(
        deviceId = deviceId,
        token = token,
        roleId = roleId,
        fingerprint = fingerprint,
    )
    return token
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun isTokenValidMustNotSkipRegisteredFingerprint() {
    val certificateState = DeviceCertificateState(createInMemoryDatabase())
    val token = "bound-token"
    certificateState.setDeviceTokenAndPermission(
        deviceId = "peer-a",
        token = token,
        roleId = 1L,
        fingerprint = DeviceTokenFingerprint("peer-a", "192.168.1.20", "FolderSpan/1"),
    )

    assertFalse(certificateState.isTokenValid(token))
    assertFalse(
        certificateState.isTokenValid(
            token,
            DeviceTokenFingerprint("peer-a", "10.0.0.9", "FolderSpan/1"),
        )
    )
    assertTrue(
        certificateState.isTokenValid(
            token,
            DeviceTokenFingerprint("peer-a", "192.168.1.20", "FolderSpan/1"),
        )
    )
}

@Test
fun webRtcIssuedTokenMustCarryFingerprintAndRejectOtherPeerContext() {
    val database = createInMemoryDatabase()
    val certificateState = DeviceCertificateState(database)
    val service = DevicePathService(certificateState) { "local-device" }
    val token = "webrtc-token"
    certificateState.setDeviceTokenAndPermission(
        deviceId = "peer-a",
        token = token,
        roleId = 1L,
        fingerprint = DeviceTokenFingerprint("peer-a", "198.51.100.10", "webrtc"),
    )

    assertFalse(service.isAuthorizedWebRtcPeerToken(token, "peer-a"))
    assertTrue(
        service.isAuthorizedWebRtcPeerToken(
            authToken = token,
            remoteDeviceId = "peer-a",
            fingerprint = DeviceTokenFingerprint("peer-a", "198.51.100.10", "webrtc"),
        )
    )
}
```

可放在现有 `DeviceTokenFingerprintTest` / `DevicePathServiceTest` 中，只断言绑定属性，不要模拟真实 WebRTC 握手。

</details>

---

## FS-10：iOS TLS 身份使用硬编码盐，密钥可由公开 deviceId 推导

* 严重度：中
* 类别：cryptographic_weakness
* 修复状态：✅ 已修复（2026-08-21）
* 位置：
  - `core/src/iosMain/kotlin/com/folderspan/service/http/tls/IosTlsIdentityFileStore.kt`（`openIosTlsIdentityFileStore` 读写 `storage.salt`；删除硬编码 `STORAGE_SALT`）
  - 对照：`core/src/jvmMain/kotlin/com/folderspan/service/http/tls/JvmTlsIdentityFileStore.kt`（随机 32 字节 `storage.salt` + AES-GCM）
* 置信度：0.84

### 修复落地

- `defaultStore()` 改为 `openIosTlsIdentityFileStore`：读取或生成 32 字节 `storage.salt`，再用 `TlsIdentityStorageKeyMaterial.build` 派生密钥。
- 删除硬编码 `STORAGE_SALT`。旧无盐文件视为无效，不会用公开常量解密。
- 测试构造函数仍可直接传入 `keyMaterial`，不强制走盐文件。
- 回归：`IosTlsIdentityFileStoreTest.openStoreWritesEntropySaltAndDoesNotReuseHardcodedKeyMaterial`；JVM 侧静态核对 iOS 源码不再含 `STORAGE_SALT`。

### 描述

第一轮 FS-05 已把 Desktop/JVM 与 Android 身份存储改为：随机 32 字节 `storage.salt`、owner-only 权限、AES-GCM、AAD 绑定容器元数据。iOS 没有对齐。

iOS 密钥材料是：

```kotlin
listOf(STORAGE_SALT, deviceId, directory).joinToString("|")
```

其中 `STORAGE_SALT = "FolderSpan.DeviceTlsIdentity.FileStore.v1"` 写死在二进制里。`deviceId` 会在发现协议 `/ping` 中公开。`directory` 是固定的应用支持路径 `.../Library/Application Support/FolderSpan/tls-identity`。因此，能读到 `.fmi` 文件的攻击者（未加密备份、设备共享、文件系统转储）**可以离线派生出与加密时相同的密钥**，不需要 `storage.salt` 那种高熵秘密。

段加密是 AES-CBC PKCS7 + HMAC-SHA256 encrypt-then-MAC，有完整性，这比旧的无 MAC CBC 好。问题不在「能否翻比特」，而在「密钥没有独立熵」。文档 `sensitive-files-and-directories.md` 把 `tls-identity/storage.salt` 列为最高敏感，但 iOS 实现根本不生成该文件。

iOS Keychain 已用于会话 Token、Access Key 等敏感设置，TLS 身份却落在可备份的 Application Support 目录，外层密钥还是公开输入的 SHA-256。

### 影响

- 拿到 iOS 应用数据副本的人可以解密设备 TLS 私钥，伪造该设备身份，破坏局域网钉扎与互信。
- 与 JVM/Android 的安全保证不一致：同样名叫 `tls-identity`，iOS 实际是「混淆」而不是「加密」。
- 备份、迁移、未加密 iTunes/Finder 副本都会扩大泄露面。

### 利用场景

攻击者获得 iOS 应用沙箱或备份中的 `tls-identity/*.fmi`（例如未加密备份、越狱/调试读取、错误的文件分享）。再用公开的 deviceId 与硬编码盐派生密钥，离线解密身份载荷。

<details>
<summary>修复方案</summary>

1. iOS 与 JVM/Android 对齐：在 `tls-identity/storage.salt` 写入 32 字节 CSPRNG 盐，权限尽量收紧；密钥材料包含该盐。
2. 优先把包装密钥放到 Keychain（`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`），文件中只存密文。
3. 若继续用 CommonCrypto，改 AES-GCM；或保持 CBC+HMAC 但必须有高熵盐/Keychain 密钥。
4. 不要用公开 deviceId + 固定字符串当唯一密钥输入。
5. 旧文件无盐可读时视为无效，要求重新生成身份，不要静默用硬编码盐解密。

```kotlin
private fun buildStorageKeyMaterial(directory: String, entropySaltHex: String): String {
    val deviceId = createSettings().getString(SettingsUtils.KEY_DEVICE_ID, "")
    return TlsIdentityStorageKeyMaterial.build(
        deviceId = deviceId,
        directoryPath = directory,
        entropySaltHex = entropySaltHex,
    )
}
```

盐文件的读写应复用 JVM 侧 `ENTROPY_SALT_FILE` 约定，以便文档与 `SensitiveFileAccessPolicy` 继续成立。

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun iosTlsIdentityKeyMaterialMustIncludeHighEntropySaltNotHardcodedConstant() {
    val source = File(
        "core/src/iosMain/kotlin/com/folderspan/service/http/tls/IosTlsIdentityFileStore.kt"
    ).readText()

    assertFalse(
        source.contains("FolderSpan.DeviceTlsIdentity.FileStore.v1"),
        "硬编码 STORAGE_SALT 必须删除",
    )
    assertTrue("ENTROPY_SALT_FILE" in source || "storage.salt" in source)
    assertTrue("Keychain" in source || "entropySaltHex" in source)
}

@Test
fun tlsIdentityKeyMaterialDiffersAcrossRandomSalts() {
    val deviceId = "public-device-id"
    val directory = "/Library/Application Support/FolderSpan/tls-identity"
    val first = TlsIdentityStorageKeyMaterial.build(deviceId, directory, entropySaltHex = "AA".repeat(32))
    val second = TlsIdentityStorageKeyMaterial.build(deviceId, directory, entropySaltHex = "BB".repeat(32))
    assertNotEquals(first, second)
}
```

当前主机若无 iOS 编译环境，静态核对源码与 `TlsIdentityStorageKeyMaterial` 即可；macOS CI 再跑身份读写回归。

</details>

---

## 已评估但未列入的问题

| 主题 | 结论 |
| --- | --- |
| 链路分享 ticket 未绑定 client | 10 分钟 TTL、最多 64 张、消耗即删，属于短时能力票据而非会话。不单独开洞；仍建议绑定 UA/IP。 |
| MCP 路径规范化 | `normalizeEndpointPath` 拒绝 `..`、`./`、NUL；`LocalFileEndpointGateway.requireOrdinaryPath` 拒绝符号链接与敏感路径。默认只绑 loopback。 |
| HTTP `requireAuth` 指纹绑定 | 设备 HTTP 路由层已校验 IP/UA；缺口在服务层/WebRTC，记为 FS-09，不把 HTTP 层再开一洞。 |
| iOS TLS 使用 AES-CBC+HMAC | encrypt-then-MAC 有完整性，不重复第一轮 FS-05。单独问题是硬编码盐导致密钥可推导，记为 FS-10。 |
| Android `allowBackup=false` | 已关闭备份；FileProvider 问题与备份无关，记为 FS-07。 |
| 文件分享 access key 默认关闭 | 配置项；与 FS-09 叠加时，批准后的 token 仍可被未绑定重放。 |
| ShizukuProvider `exported=true` | 官方组件模式，权限为 `INTERACT_ACROSS_USERS_FULL`。 |

第一轮已覆盖且仍成立的排除项（zip-slip、MCP `/proc`、CORS、SFTP、发现 ping）见 [第一轮报告](2026-08-21-security-audit-report.md)，本轮不再重复。

---

## 修复落地摘要

四条均已在 2026-08-21 落地，第一轮报告未改动。

1. **FS-07**：删除 FileProvider `root-path` / `files-path`；打开与分享前拒绝符号链接和敏感路径。
2. **FS-08**：content URI / System Share 按登记表逐段解析真实路径，失败查找不再下载。
3. **FS-09**：WebRTC 签发写入 fingerprint；无指纹 token 不能过 WebRTC RPC；不因省略 fingerprint 清掉已有绑定。
4. **FS-10**：iOS TLS 身份读写 `storage.salt`，密钥材料对齐 JVM。

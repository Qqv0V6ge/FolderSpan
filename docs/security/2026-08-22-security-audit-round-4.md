# FolderSpan 安全审计报告（第四轮）

- 审计日期：2026-08-22
- 审计范围：全仓（`app/`、`core/`、`proMain/`），非仅当前 PR diff；未提交的剪贴板粘贴文件改动一并覆盖
- 前置：
  - 第一轮见 [2026-08-21 全仓安全审计报告](2026-08-21-security-audit-report.md)（FS-01～FS-05 已修复，FS-06 误报）
  - 第二轮见 [2026-08-21 全仓安全审计报告（第二轮）](2026-08-21-security-audit-round-2.md)（FS-07～FS-10 已修复）
  - 第三轮见 [2026-08-21 全仓安全审计报告（第三轮）](2026-08-21-security-audit-round-3.md)（FS-11～FS-16 已修复）
- 方法论：Claude Marketplaces 插件 `cybersecurity-skills@anthropic-cybersecurity-skills` v1.3.0
  覆盖技能：`conducting-api-security-testing`、`testing-api-for-broken-object-level-authorization`、`testing-for-broken-access-control`、`performing-directory-traversal-testing`、`performing-ssrf-vulnerability-exploitation`、`testing-cors-misconfiguration`、`auditing-mcp-servers-for-tool-poisoning`、`performing-cryptographic-audit-of-application`、`testing-android-intents-for-vulnerabilities`、`testing-jwt-token-security`、`conducting-api-security-testing`
- 约束：本报告不含攻击载荷或 exploit PoC。折叠区内的「验证代码」均为防御性回归测试（断言安全属性），用于确认修复是否生效。
- 威胁模型：局域网邻机、同机恶意进程/网页、不可信 Android Intent、SFTP 中间人、已授权设备上的 token 盗用。本机文件分享服务按产品设计会对局域网可达。
- 本轮只登记相对前三轮**仍未修复的新问题**。已落地的 FS-01～FS-16、已排除项与产品明确文档化的同步行为不重复开洞。

## 审计发现总览

| ID | 严重度 | 类别 | 标题 | 状态 |
| --- | --- | --- | --- | --- |
| FS-17 | 高 | authentication_bypass / broken_object_level_authorization | 链路分享会话指纹失败后，仅凭 `FolderSpanLinkShareClient` cookie 即可重签发授权 | 已修复 |
| FS-18 | 高 | path_traversal / sensitive_file_read | Darwin `/var` → `/private/var` 别名绕过敏感路径策略；设备文件 API 连 canonical 二次检查都没有 | 已修复 |
| FS-19 | 中 | broken_function_level_authorization | MCP `FavoritesWrite` 不要求 `FilesRead`，可通过收藏接口读取任意非敏感路径元数据 | 已修复 |
| FS-20 | 中 | secret_exfiltration / mitm | 局域网发现扫描仍对每个地址做 Capture Trust-All，并在 access key 开启时随 `/ping` 发出 | 已修复 |

---

## FS-17：链路分享会话指纹失败后，仅凭 client cookie 即可重签发授权

* 严重度：高
* 类别：authentication_bypass / broken_object_level_authorization
* 修复状态：已修复（已删除授权表按 clientId 重签发；session 指纹失败后走待授权 / 密码，不再静默发新 session）
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/service/http/server/linkshare/LinkShareRouteDispatcher.kt`（`prepareLinkShareAccess` 约 201–229 行；`resolveLinkShareClientId` 约 1571–1574 行；`linkShareCookie` 约 1597–1611 行）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/file/FileShareState.kt`（`getAuthorizedLinkShareDevice` 约 115–119 行；`resolveLinkShareSession` 约 146–159 行；`LinkShareSession.matches` 约 608–612 行；`ShareTokenFingerprint.matches` 约 633–643 行）
  - JVM / Android `HttpShareFileServer`：`allowPlainHttpOnLan = true`，LAN 上链路分享走明文 HTTP
* 置信度：0.92

### 描述

链路分享有两层身份：

1. **会话** `FolderSpanLinkShareSession`：12 小时 TTL，绑定 `clientId` + 客户端 IP + User-Agent。指纹不匹配时 `resolveLinkShareSession` **删除该 session** 并返回 `null`。
2. **授权表** `authorizedLinkShareDevices`：按 `Device.id`（即 client cookie）命中。**没有 TTL**；`pruneExpiredLinkShareAccess` 只清理 session 与 ticket。

`prepareLinkShareAccess` 的顺序是：ticket → session → **授权表回退** → 自动批准 / 密码 / 待授权。session 校验失败后立刻走：

```kotlin
val approvedFiles = fileShareState.getAuthorizedLinkShareDevice(device.id)
if (approvedFiles != null) {
    val approvedSession = fileShareState.issueLinkShareSession(
        clientId = device.id,
        fingerprint = fingerprint, // 当前请求的 IP/UA
        ...
    )
    // 把新 session cookie 写回当前请求方
}
```

`device.id` 来自 `FolderSpanLinkShareClient` cookie（16–128 位字母数字 `_-`，否则新生成 32 位随机串），**不校验 IP/UA**。新 session 用攻击者当前指纹签发。

Cookie 属性：`HttpOnly`、`SameSite=Lax`；`secure` 仅当 `request.scheme == "https"`。产品局域网分享允许明文 HTTP，邻机可嗅探 client cookie。client cookie 有效期 12 小时，授权表本身更长（进程内直到主人撤销）。

第二轮 FS-09 / 第三轮已强调「会话绑指纹」。本条是：**指纹失败本应终止访问，却被授权表按 clientId 无条件回退抵消。**

### 影响

- 拿到 `FolderSpanLinkShareClient` 的邻机，无需原 IP、无需 session token、无需再次被主人点批准，即可浏览并下载该 client 已被授权的分享文件。
- 若该授权带 `allowUpload`，攻击者还能向分享根写入。
- 主人在分享页看到的仍是原先那台「已批准设备」，不会出现新的待授权条目。

### 利用场景

用户在局域网开启文件分享并批准了手机浏览器。邻机嗅探到 HTTP 上的 `FolderSpanLinkShareClient`，或从被盗用的浏览器配置里复制该 cookie。邻机用自己的 IP 与 UA 访问同一分享 URL，只带 client cookie、不带（或带已失效的）session cookie。服务端删掉旧 session 后按 clientId 命中授权表，向邻机签发绑定其指纹的新 session。

<details>
<summary>修复方案</summary>

1. **删除「授权表按 clientId 重签发」这条回退。** session 指纹失败应视为未认证，重新进入待授权 / 密码流程。授权表只用于主人侧 UI 与撤销，不作为网络身份。
2. 若必须保留「已批准设备免再点一次」：授权表条目必须同时存储签发时的 IP+UA（或 session 公钥），`getAuthorizedLinkShareDevice` 命中后仍要 `fingerprint.matches`；不匹配则拒绝，不要用攻击者指纹覆盖。
3. 给授权表加与 session 相同的 TTL，并在 `pruneExpiredLinkShareAccess` 中清理。
4. 局域网分享若继续提供 HTTP，client cookie 无法靠 `Secure` 保护；此时更不能把 client cookie 当作授权证明。优先强制 HTTPS，或把 client 标识改为不可预测且与 session 绑定的一对密钥，而不是可重放的 cookie。

```kotlin
val session = request.resolveLinkShareSession(device.id, fingerprint)
if (session != null) {
    // 现有：写入授权、继续
    return null
}

// 删除下面整段 getAuthorizedLinkShareDevice(...) { issueLinkShareSession(...) }

// 指纹失败或没有 session：走待授权 / 密码，不要静默重签发
if (fileShareState.autoApprove.value) { /* 保持产品行为 */ }
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun expiredOrMismatchedSessionDoesNotReissueFromClientCookie() = runBlocking(Dispatchers.IO) {
    withTestKoin {
        withSharedLocalFile("secret.txt", "payload".encodeToByteArray()) { root, state, _ ->
            val originalFingerprint = ShareTokenFingerprint(clientIp = "192.168.8.20", userAgent = "PhoneUA")
            state.authorizeLinkShareDevice(
                device = Device(
                    id = CLIENT_ID,
                    name = "phone",
                    type = DeviceType.Phone,
                    host = "192.168.8.20",
                ),
                allowHidden = false,
                allowUpload = false,
                files = listOf(root),
            )
            state.issueLinkShareSession(
                clientId = CLIENT_ID,
                fingerprint = originalFingerprint,
                allowHidden = false,
                files = listOf(root),
            )

            val attacker = LinkShareHttpRequest.from(
                method = "GET",
                rawUri = "/${root.name}/secret.txt",
                headers = linkShareHeadersOf(
                    "User-Agent" to "NeighborUA",
                    "Cookie" to "FolderSpanLinkShareClient=$CLIENT_ID",
                    "X-API-Request" to "true",
                ),
                remoteHost = "192.168.8.99",
            )
            val response = dispatcher(state).dispatch(attacker)

            assertTrue(
                response.statusCode == 401 || response.statusCode == 403 || response.statusCode == 202,
                "client cookie must not re-authorize a different IP/UA: ${response.statusCode} ${response.bodyText()}",
            )
            assertFalse(response.bodyText().contains("payload"))
            val setCookie = response.header("Set-Cookie").orEmpty()
            assertFalse(setCookie.contains("FolderSpanLinkShareSession="))
        }
    }
}

@Test
fun getAuthorizedLinkShareDeviceMustNotBypassFingerprint() {
    val state = FileShareState()
    val device = Device(id = "client-a", name = "phone", type = DeviceType.Phone, host = "10.0.0.2")
    state.authorizeLinkShareDevice(device, allowHidden = false, allowUpload = false, files = emptyList())
    val session = state.issueLinkShareSession(
        clientId = "client-a",
        fingerprint = ShareTokenFingerprint("10.0.0.2", "PhoneUA"),
        allowHidden = false,
        files = emptyList(),
    )
    assertNull(
        state.resolveLinkShareSession(
            token = session.token,
            clientId = "client-a",
            fingerprint = ShareTokenFingerprint("10.0.0.99", "NeighborUA"),
        )
    )
    // 修复后：授权表命中也不能单独构成网络身份
    assertNull(state.takeIf { false }?.getAuthorizedLinkShareDevice("client-a"))
}
```

第二段中 `takeIf { false }` 仅作占位：落地时应改为「授权表查找必须带指纹，不匹配返回 null」。不要在测试里调用会重签发的 dispatcher 路径并断言 200。

</details>

---

## FS-18：Darwin `/var` → `/private/var` 别名绕过敏感路径策略

* 严重度：高
* 类别：path_traversal / sensitive_file_read
* 修复状态：已修复（词法层折叠 Darwin `/private/var` ↔ `/var`；`deniedException` 在词法拒绝后再对 canonical 路径做二次拒绝）
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/utils/SensitiveFileAccessPolicy.kt`（`classify` 只做 `normalizePathForSecurityBoundary` 词法规范化，约 96–124、180–183 行；`commonSensitivePathRules` 用 `PathUtils.getAppPath()` / `getCachePath()`，约 128–176 行）
  - `core/src/iosMain/kotlin/com/folderspan/utils/SensitiveFileAccessPolicy.ios.kt`（规则根为 `NSHomeDirectory()/Library`、`NSTemporaryDirectory()`，约 6–17 行）
  - `core/src/iosMain/kotlin/com/folderspan/utils/PathUtils.ios.kt`（`getAppPath`/`getCachePath` 直接返回上述 API；`resolveCanonicalPath` 走 `stringByResolvingSymlinksInPath`，约 143–171 行）
  - `core/src/jvmMain/kotlin/com/folderspan/utils/PathUtils.jvm.kt`（`getCachePath()` = `java.io.tmpdir`，macOS 常为 `/var/folders/...`）
  - `core/src/commonMain/kotlin/com/folderspan/service/mcp/file/LocalFileEndpointGateway.kt`（`requireOrdinaryPath`：词法 deny → canonical → 再 deny，约 151–174 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/file/DeviceFileService.kt`（`prepareReadBytes` / `getFileSimpleInfo` 只对请求路径做 `deniedException`，无 canonical，约 273–281、461–463 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/path/DevicePathService.kt`（`list` 同样只词法检查，约 62–64 行）
* 置信度：0.92

### 描述

Darwin 上 `/var` 是 `/private/var` 的符号链接。iOS 的 `NSHomeDirectory()`、`NSTemporaryDirectory()` 以及 macOS 的 `java.io.tmpdir` 通常给出 `/var/mobile/...`、`/var/folders/...` 这种**未折叠**路径。

`SensitiveFileAccessPolicy.classify()` 明确「不触碰文件系统」：只折叠 `.` / `..`，**不解析 symlink 别名**。规则根因此登记为 `/var/...`。

MCP 网关 `requireOrdinaryPath` 的设计本意是「词法拒绝 + canonical 后再拒绝一次」。但第二次 `denySensitiveOrRestricted(resolved)` 拿的是 `/private/var/...`，与规则根 `/var/...` 字符串不相等、也不是前缀，于是放行。随后 `FileUtils` 按真实路径读写，应用沙箱内的数据库、TLS 身份、Library 私有数据、缓存均可被访问。

设备文件 / 路径 API 更弱：连 canonical 二次检查都没有。已授权对端只要把路径写成 `/private/var/mobile/Containers/Data/Application/<id>/Library/...`（或 macOS 上 `/private/var/folders/.../file-editor-backups/...`），词法分类为 `None`，直接 `FileUtils.readFileRange`。

Android 不在本条范围内：`context.dataDir.absolutePath` 已是 `/data/user/0/...`，canonical 后仍落在规则下；`/data/data` 别名会被第二次检查拦住。Linux 桌面 `tmpdir` 一般是 `/tmp`，无此别名。Windows 无 `/var`。

现有测试 `localGatewayRejectsProtectedApplicationDataBeforeFileSystemAccess` 用 `resolveDesktopApplicationDataDirectory()`（macOS 上是 `~/Library/Application Support/FolderSpan`，不经过 `/var`），因此绿测不能覆盖本洞。

### 影响

- 持有 `FilesRead` 的 MCP token（含只读预设）可在 iOS / macOS 上读取本应 `Critical` 的应用私有数据：`folderspan.db`、`tls-identity`、设置金库相关文件、编辑器备份等。
- 已授权的局域网设备可通过设备文件 API 列出并读取同一批路径，且不必经过 MCP。
- `FilesWrite` 则可写入 / 删除这些目录（MCP `writeRange` / `delete` 同样走 `requireOrdinaryPath`）。

### 利用场景

用户在 iOS 或 macOS 上开启了 MCP（或已批准一台设备连接）。攻击者持有该 token / 设备会话，不传 `NSHomeDirectory()` 返回的 `/var/...` 路径，而传 `stringByResolvingSymlinksInPath` 之后的 `/private/var/...` 路径，读取 `Library` 下的数据库或 TLS 身份。词法规则不匹配，canonical 后的二次检查仍然不匹配。

<details>
<summary>修复方案</summary>

1. **规则根与比较目标都登记「词法路径 + canonical 路径」两套。** 加载 `platformSensitivePathRules` / `commonSensitivePathRules` 时，对每个根调用 `resolveCanonicalPath`（根必须存在）并把折叠后的路径一并加入规则。
2. `classify()` 在词法匹配失败且平台支持 realpath 时，对目标再做一次 canonical 后匹配。为避免把「不触碰文件系统」的注释作废，可拆成 `classifyLexical`（给 FileProvider 等）与 `classifyResolved`（给 MCP / 设备 API）。
3. **设备文件 / 路径 / 信息 API 必须与 MCP 一样：规范化 → 拒绝 → canonical → 再拒绝**，再执行读写。不要只检查请求字符串。
4. Darwin 上为 `/var` 与 `/private/var` 增加显式等价前缀，作为 realpath 不可用时的兜底（例如目标尚不存在）。
5. 回归必须覆盖 `/var/...` 与 `/private/var/...` 两种写法，且 MCP 与 Device API 各测一条。

```kotlin
internal fun SensitivePathRule.withCanonicalAlias(): List<SensitivePathRule> {
    val canonical = PathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, path, allowNonExistentLeaf = true)
        ?: return listOf(this)
    if (canonical == path) return listOf(this)
    return listOf(this, copy(path = canonical))
}

private fun requireOrdinaryPath(path: String, allowNonExistentLeaf: Boolean = false): String {
    val normalized = normalizeEndpointPath(path, pathSeparator)
    denySensitiveOrRestricted(normalized)
    val resolved = PathUtils.resolveCanonicalPath(
        FileAccessPermission.Allowed,
        normalized,
        allowNonExistentLeaf,
    ) ?: throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "path is not accessible")
    denySensitiveOrRestricted(resolved)
    return resolved
}

private suspend fun prepareReadBytes(...): Result<PreparedReadBytesRequest> {
    val canonical = PathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, request.path)
        ?: return Result.failure(AuthorityException(AppStrings.error_path_access_denied))
    SensitiveFileAccessPolicy.deniedException(request.path)?.let { return Result.failure(it) }
    SensitiveFileAccessPolicy.deniedException(canonical)?.let { return Result.failure(it) }
    // 后续读写一律用 canonical
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun classifyTreatsDarwinPrivateVarAsTheSameSensitiveRoot() {
    val varRoot = "/var/mobile/Containers/Data/Application/ABCD/Library"
    val rules = listOf(
        SensitivePathRule(varRoot, FileSensitivity.Critical, "application_private_data"),
        SensitivePathRule(
            "/private$varRoot",
            FileSensitivity.Critical,
            "application_private_data",
        ),
    )
    val viaAlias = SensitiveFileAccessPolicy.classify(
        path = "/private$varRoot/folderspan.db",
        separator = "/",
        rules = rules,
        caseInsensitive = true,
    )
    assertEquals(FileSensitivity.Critical, viaAlias.sensitivity)
    assertEquals("application_private_data", viaAlias.category)
}

@Test
fun localGatewayRejectsCanonicalPrivateVarAlias() = runBlocking(Dispatchers.IO) {
    val gateway = LocalFileEndpointGateway()
    val lexical = PathUtils.getAppPath().trimEnd('/') + "/Library"
    val canonical = PathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, lexical)
        ?: lexical
    if (canonical == lexical) return@runBlocking // 非 Darwin 跳过

    val result = gateway.info("$canonical/folderspan.db")
    val error = assertIs<FileEndpointException>(result.exceptionOrNull())
    assertEquals(FileEndpointErrorCode.PermissionDenied, error.code)
}

@Test
fun deviceFileReadRejectsCanonicalPrivateVarAlias() = runBlocking {
    val lexicalCache = PathUtils.getCachePath()
    val canonical = PathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, lexicalCache) ?: lexicalCache
    if (canonical == lexicalCache) return@runBlocking

    val denied = SensitiveFileAccessPolicy.deniedException(canonical)
    assertNotNull(denied, "canonical Darwin cache path must stay protected")
}
```

设备 API 的完整用例应在持有测试 token 的 `DeviceFileService.prepareReadBytes` 上断言 `Result.failure`，避免在测试里读真实 Library 文件。

</details>

---

## FS-19：MCP `FavoritesWrite` 不要求 `FilesRead` 即可读取任意非敏感路径元数据

* 严重度：中
* 类别：broken_function_level_authorization
* 修复状态：已修复（`folderspan_favorites_add` / `remove` 同时要求 `FavoritesWrite` 与 `FilesRead`；管理页勾选收藏写会自动带上文件读）
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/service/mcp/tools/McpToolRegistry.kt`（`folderspan_favorites_add` / `remove` 仅 `McpTokenScope.FavoritesWrite`，约 132–137 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/mcp/automation/McpAutomationFacade.kt`（`addFavorite` 调用 `gateway.info(path)`，约 220–237 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/mcp/file/LocalFileEndpointGateway.kt`（`info` 返回 name / size / mime / 时间戳）
  - `app/shared/src/commonMain/kotlin/com/folderspan/ui/screen/mcp/McpManageScreen.kt`（自定义 token 可只勾选 Favorites 写；Operator 预设同时含 `FilesRead`，约 440–465 行）
* 置信度：0.88

### 描述

MCP 把收藏写成独立 scope，与 `FilesRead` 正交。`folderspan_favorites_add` 在写入数据库前调用 `gateway.info(path)` 确认目标存在，并把 `name`、`isDirectory`、`mimeType`、`size`、`createdAt`、`updatedAt`、解析后的 `path` 填进 `McpFavoriteDto` 返回给调用方。

`info` 与 `folderspan_file_info` 走同一网关，受敏感路径与 symlink 限制，但**不再要求 `FilesRead`**。自定义 token 可以只授予 `FavoritesWrite`（管理页按区域勾选，不必用预设）。该 token 不能调用 `folderspan_files_list` / `folderspan_file_read`，却能对任意非敏感绝对路径做存在性与元数据探测。

Operator / Administrator / ReadOnly 预设都包含 `FilesRead`，默认预设不受影响。本条针对自定义最小权限 token。

`removeFavorite` 同样 `resolve` + `info`，但随后按已有收藏删除，探测面小于 `add`。

### 影响

- 被盗用或被过度授予的 `FavoritesWrite` token，可枚举本机用户目录、确认隐藏文件是否存在、读取大小与修改时间。
- 探测结果写入收藏表，之后即使用 `FavoritesRead` 也能再次列出。等于把「写收藏」升级成「有限的文件枚举」。
- 敏感路径仍被 `requireOrdinaryPath` 拦住，不能直接读数据库；与 FS-18 叠加时，Darwin 别名可使探测面扩大到本应 Critical 的目录。

### 利用场景

用户为某个自动化 agent 建了「只能管理收藏」的自定义 token，故意不给 `FilesRead`。agent 或拿到该 token 的邻机对一系列路径调用 `folderspan_favorites_add`。存在的文件返回完整元数据；不存在或敏感路径返回错误。借此还原用户主目录结构。

<details>
<summary>修复方案</summary>

1. `folderspan_favorites_add` / `remove` 的工具声明改为同时要求 `FavoritesWrite` **与** `FilesRead`（对 Device / Network locator 再叠加对应读权限）。
2. 网关侧 `addFavorite` 在 `resolver.resolve` 之后显式检查调用方 scope，而不是只靠工具表。
3. 管理页：勾选 Favorites 写时自动带上 Files 读，或在自定义模式下提示依赖。
4. 不要让 `info` 的完整 DTO 回到只持有 FavoritesWrite 的调用方；若产品坚持「按路径加收藏不必能读内容」，则 `addFavorite` 只接受已经出现在 `files_list` 结果里的 locator，或只返回 `{id, locator}` 而不回 size/mtime。

```kotlin
tool(
    "folderspan_favorites_add",
    "Add a favorite by file locator.",
    requiredScopes = setOf(McpTokenScope.FavoritesWrite, McpTokenScope.FilesRead),
    locatorSchema(),
) { args ->
    json(facade.catalog.addFavorite(args.locator()))
}
```

若工具表仍是单 scope，在 `McpHttpRequestHandler` 分发处对这类工具做 `token.scopes.containsAll(...)`。

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun favoritesAddRequiresFilesReadScope() {
    val add = McpToolRegistry.builtInTool("folderspan_favorites_add")
    assertTrue(
        add.requiredScopes.contains(McpTokenScope.FilesRead),
        "favorites_add must not be callable with FavoritesWrite alone",
    )
    assertTrue(add.requiredScopes.contains(McpTokenScope.FavoritesWrite))
}

@Test
fun addFavoriteWithWriteOnlyTokenDoesNotReturnFileMetadata() = runTest {
    val directory = Files.createTempDirectory("mcp-fav-").toFile()
    val target = File(directory, "notes.txt").apply { writeText("secret") }
    try {
        val facade = McpCatalogFacade(
            bookmarks = ScopedBookmarkRepository(createMcpHttpTestDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))),
            database = createMcpHttpTestDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)),
            resolver = FileEndpointResolver(mapOf(FileProtocol.Local to { LocalFileEndpointGateway() })),
        )
        // 修复后：无 FilesRead 时应 PermissionDenied，而不是返回 size/mtime
        val result = runCatching {
            facade.addFavorite(FileLocator(FileEndpointRef(FileProtocol.Local), target.absolutePath))
        }
        // 本测试在加上 scope 拦截后的 handler 层运行更合适；
        // facade 本身若继续调用 info，handler 必须先拒绝。
        assertTrue(result.isFailure || result.getOrNull()?.size == 0L)
    } finally {
        directory.deleteRecursively()
    }
}
```

落地时优先在 `McpHttpService` / tool dispatcher 层用只含 `FavoritesWrite` 的 token 打 `folderspan_favorites_add`，断言 MCP 错误码为权限不足，且响应不含文件 size。

</details>

---

## FS-20：局域网发现扫描仍对每个地址 Capture Trust-All，并在 access key 开启时随 `/ping` 发出

* 严重度：中
* 类别：secret_exfiltration / mitm
* 修复状态：已修复（发现 ping 不再调用 `applyFileShareAccessKey()`；Capture Trust-All 仍仅用于 TOFU 握手采指纹，access key 只走后续已钉扎请求）
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/main/DeviceState.kt`（`scanner` 对网段 chunk 64 并发调用 `pingDevice`，约 471–576 行；`pingDevice` 使用 `discoveryPingPool` + `applyFileShareAccessKey()`，约 707–771 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/client/FileShareAccessKeyRequest.kt`（access key 已启用且合法时写入请求头，约 16–23 行）
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/client/HttpClientFactory.jvm.kt` 与 Android 对应实现（`FingerprintTrustManager`：`capture != null` 时只记录指纹、不校验，约 87–95 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/client/HttpTransportUrls.kt`（`usePlainHttpDeviceTransport()` 固定 `false`，发现 ping 走 HTTPS）
* 置信度：0.86

### 描述

第一轮已把「发现 ping 钉扎握手叶子证书、不再信任自报指纹」记为已修复（`ff3672b368`）。第三轮 FS-15 修的是 **MCP `folderspan_device_scan` 任意 CIDR/端口**，并要求探测**不要**带 access key。MCP 扫描那条已经落地。

产品自己的局域网 `scanner()` 仍对当前网卡 `/24` 调用同一套 `pingDevice`：

- 客户端是 `createDiscoveryNoProxyHttpClient(LeafCertificateCapture())`。`FingerprintTrustManager.checkServerTrusted` 在 `capture != null` 时 `return`，**接受任意叶子证书**。
- 每个目标都会 `applyFileShareAccessKey()`。access key **默认关闭**；用户一旦在设置里打开，密钥会随扫描发到网段内每一个地址（包括被中间人劫持的地址）。
- 捕获的指纹写入 `socketDevice.tlsFingerprintSha256`，作为后续 `createPinnedNoProxyHttpClient` 的 TOFU 钉扎。已信任设备在 `connect` 时走 `TrustedDeviceCertificateStore`，指纹变化会拦截。新设备 / 尚未批准的设备没有这层预检。

本条不是「又一次任意 CIDR SSRF」，也不是「AUTO_CONNECT 信任自报指纹」（FS-12 已修）。残留面是：**LAN 扫描本身在 access key 开启时，把共享密钥交给 Trust-All 的 HTTPS 对端。**

### 影响

- 同一网段的恶意接入点或 ARP/DHCP 劫持，可对扫描端口出示任意证书，从 ping 请求头读出文件分享 access key。该密钥随后可用于调用本机文件分享 API（与 FS-15 记载的密钥用途相同）。
- 新发现设备的 TOFU 指纹来自这次未校验的握手；用户若紧接着批准连接，会钉扎攻击者证书。已批准且写入信任库的设备不受这次扫描单独破坏。

### 利用场景

用户打开「文件分享访问密钥」并触发设备扫描。邻机在网段内冒充若干 IP 的 文件分享端口，用自签证书完成 TLS。扫描器因 Capture 模式不校验证书，把 access key 发过去。邻机保存该密钥，之后用它访问受害者的分享服务。

<details>
<summary>修复方案</summary>

1. **发现 ping 不要携带 access key。** MCP 扫描在 FS-15 已这样做；产品 `scanner()` / `pingDevice` 应对齐。access key 只在后续已钉扎的业务请求上发送。
2. 扫描阶段继续 Capture 可以保留（TOFU 需要观察叶子证书），但捕获结果在用户批准前不得用于自动连接；AUTO_CONNECT 已改走账户身份证明，不要再让扫描路径 `connect()`。
3. 可选：对已在 `TrustedDeviceCertificateStore` 中的 host，扫描也改用 pinned client，避免把密钥送给「地址仍在网段、证书已换」的对端。
4. 文档明确：开启 access key 后，发现扫描在修复前会把密钥发到网段。

```kotlin
internal fun HeadersBuilder.applyFileShareAccessKey(
    settings: Settings = createSettings(),
    allowOnUnpinnedDiscovery: Boolean = false,
) {
    if (!allowOnUnpinnedDiscovery) return
    val config = settings.readFileShareAccessKeyConfig()
    if (!config.enabled || !config.hasValidValue()) return
    remove(FILE_SHARE_ACCESS_KEY_HEADER)
    append(FILE_SHARE_ACCESS_KEY_HEADER, config.value)
}

// pingDevice 内：
// applyFileShareAccessKey() 改为不调用，或显式 allowOnUnpinnedDiscovery = false
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun discoveryPingDoesNotSendAccessKeyHeader() = runTest {
    val captured = mutableListOf<Headers>()
    val client = recordingPingClient(captured)
    pingDeviceForTest(
        ip = "10.0.0.23",
        port = HttpRouteClientManager.PORT,
        client = client,
        accessKeyEnabled = true,
        accessKeyValue = "test-access-key",
    )
    assertTrue(
        captured.none { headers -> headers.contains(FILE_SHARE_ACCESS_KEY_HEADER) },
        "unpinned discovery ping must not leak the file-share access key",
    )
}

@Test
fun captureTrustManagerStillRecordsFingerprintWithoutSendingSecrets() {
    val capture = LeafCertificateCapture()
    val manager = FingerprintTrustManager(expectedFingerprintSha256 = null, capture = capture)
    // 用测试证书调用 checkServerTrusted 后：
    assertNotNull(capture.take())
    // 与上一则测试一起：允许 Capture，但不允许在同一请求上附带 access key
}
```

`deviceScanProbeDoesNotSendAccessKeyHeader`（第三轮 FS-15）只覆盖 MCP 扫描。本条需要同等断言挂在 `DeviceState.pingDevice` / 产品 `scanner()` 上。

</details>

---

## 已检查、不作为本轮新漏洞

| 主题 | 结论 |
| --- | --- |
| FS-01～FS-16 | 源码与测试表明已落地或（FS-06）为误报，不重复开洞。 |
| MCP `folderspan_device_scan` 任意 CIDR | FS-15 已限制本机 CIDR 与文件分享端口，MCP 探测不再带 access key。本轮 FS-20 只覆盖**产品 LAN `scanner()`** 残留。 |
| 局域网 MCP 明文 HTTP | FS-16 已改为 LAN 只广告 / 接受 HTTPS。 |
| AUTO_CONNECT 自报 TLS 指纹 / WebRTC 自报 `peer.id` | FS-12 已改账户身份证明与指纹绑定签发。 |
| CORS Origin 主机=Host 未比端口 | FS-13 现要求 Origin 主机**与端口**等于请求 Host。 |
| 链路分享 `content://` 跳过根约束 | FS-08 已改登记表 `isRegisteredContentUriPath`。 |
| Android SEND 无 URI grant | FS-14 已加 grant 检查。残留：`grantedShareUri` 在 Intent 带 `FLAG_GRANT_READ_URI_PERMISSION` 时短路、不调用 `checkUriPermission`。FLAG 可被发送方设置，不等于系统 grant；无真实 grant 时 `openInputStream` 仍会失败。剪贴板 `raw:` 文档 ID 需 DocumentsProvider 查询成功才会落到 `File()`。按第三轮补丁残差排除，不新开 ID。 |
| 剪贴板 / 拖放路径穿越 | `BrowserExternalPathPolicy` 拒绝 `..` / NUL；桌面复制拒绝 symlink；iOS 暂存名去掉 `/`；Android 相对路径 `sanitizeClipboardRelativePath`。未达 ≥0.8。 |
| WebRTC 房间 ID `kotlin.random.Random` | 官方房间编辑页生成。JVM 通常有系统种子；JS `Math.random` 更弱，但官方房间主要在原生 UI。房间 ID 不是加入秘密的唯一因子。作硬化建议，不开洞。 |
| 链路分享 CSRF / cookie 标志 | `HttpOnly`、`SameSite=Lax`；HTTPS 时 `Secure`。本轮 FS-17 针对的是 **client cookie 授权回退**，不是 CSRF。 |
| zip-slip / XSS / 命令注入 | 归档拒绝 `..` 与绝对路径；HTML 走 kotlinx.html；`ProcessBuilder` 参数固定。 |
| JWT | 仓库无 JWT 实现。 |
| 密钥落盘 | 桌面/iOS 敏感设置进金库；Android Keystore。按审计硬排除处理。 |
| iOS 剪贴板漏 `return` | `prepareIosClipboardFileBatch` 最后表达式即返回值，误报。 |

## 修复优先级建议

1. **FS-17、FS-18**：直接导致已授权分享被邻机接管，或应用私有数据经 MCP / 设备 API 读出，应先做。
2. **FS-20**：access key 默认关闭，开启后与 LAN MITM 叠加才会泄密钥；与 FS-15 同类，扫描路径对齐即可。
3. **FS-19**：影响自定义最小权限 token，预设用户默认带 `FilesRead`；与 FS-18 叠加时危害上升。
)

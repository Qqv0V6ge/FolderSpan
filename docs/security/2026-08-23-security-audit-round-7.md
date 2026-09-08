# FolderSpan 安全审计报告（第七轮）

- 审计日期：2026-08-23
- 审计范围：全仓（`app/`、`core/`、`proMain/`），非仅当前 PR diff
- 前置：
  - 第一轮见 [2026-08-21 全仓安全审计报告](2026-08-21-security-audit-report.md)（FS-01～FS-05 已修复，FS-06 误报）
  - 第二轮见 [2026-08-21 全仓安全审计报告（第二轮）](2026-08-21-security-audit-round-2.md)（FS-07～FS-10 已修复）
  - 第三轮见 [2026-08-21 全仓安全审计报告（第三轮）](2026-08-21-security-audit-round-3.md)（FS-11～FS-16 已修复）
  - 第四轮见 [2026-08-22 全仓安全审计报告（第四轮）](2026-08-22-security-audit-round-4.md)（FS-17～FS-20 已修复）
  - 第五轮见 [2026-08-23 全仓安全审计报告（第五轮）](2026-08-23-security-audit-round-5.md)（FS-21～FS-27 已修复）
  - 第六轮见 [2026-08-23 全仓安全审计报告（第六轮）](2026-08-23-security-audit-round-6.md)（FS-28 已修复）
- 方法论：Claude Marketplaces 插件 `cybersecurity-skills@anthropic-cybersecurity-skills` v1.3.0
  覆盖技能：`testing-for-broken-access-control`、`testing-api-for-broken-object-level-authorization`、`auditing-mcp-servers-for-tool-poisoning`、`performing-directory-traversal-testing`、`exploiting-http-request-smuggling`、`exploiting-websocket-vulnerabilities`、`testing-jwt-token-security`、`performing-cryptographic-audit-of-application`、`testing-android-intents-for-vulnerabilities`、`implementing-secret-scanning-with-gitleaks`、`testing-for-insecure-deserialization`
- 约束：本报告不含攻击载荷或 exploit PoC。折叠区内的「验证代码」均为防御性回归测试（断言安全属性），用于确认修复是否生效。
- 威胁模型：局域网邻机、同机恶意进程/网页、不可信 Android Intent、SFTP 中间人、已授权设备上的 token 盗用。本机文件分享服务按产品设计会对局域网可达。
- 本轮只登记相对前六轮**仍未修复的新问题**。已落地的 FS-01～FS-28、已排除项与产品明确文档化的同步行为不重复开洞。

## 审计发现总览

| ID | 严重度 | 类别 | 标题 | 状态 |
| --- | --- | --- | --- | --- |
| FS-29 | 高 | broken_function_level_authorization / privilege_escalation | MCP `files.share` 可签发可写链路分享，绕过 `files.write` 与宿主上传确认 | 已修复 |
| FS-30 | 高 | sensitive_file_read / confused_deputy | 网盘 `Network.copyTo` 的 Local→Network 路径未接入 `SensitiveFileAccessPolicy`，SFTP/SMB/FTP 上传还会跟随叶子符号链接 | 已修复 |
| FS-31 | 中 | intent_confused_deputy | Android SEND 的 URI grant 检查把 Intent flag 当真实授权，拖放与剪贴板仍可在无 grant 时打开 content URI | 已修复 |

---

## FS-29：MCP 分享工具可用 `allowUpload` 把只读分享权升级成局域网可写入口

* 严重度：高
* 类别：broken_function_level_authorization / privilege_escalation
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/service/mcp/tools/McpToolRegistry.kt`（`folderspan_files_share_link` 约 278–291 行：`extraScopes` 只有 `FilesRead`，`allowUpload` 原样传入）
  - `core/src/commonMain/kotlin/com/folderspan/service/mcp/automation/McpAutomationFacade.kt`（`McpFileSharingFacade.shareLink` 约 642–667 行：必要时启动分享服务，再 `issueLinkShareTicket(allowHidden, allowUpload, …)`）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/file/FileShareState.kt`（`issueLinkShareTicket` 约 161–180 行把 `allowUpload` 写入 ticket；宿主默认 `_allowUpload = false`，访客额外申请走 `requestLinkShareUploadPermission`）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/server/linkshare/LinkShareRouteDispatcher.kt`（`prepareLinkShareAccess` 约 188–205 行：兑换 ticket 时把 `redeemedTicket.allowUpload` 写进 session；`resolveShareUploadRequestOrRespond` 约 712–715 行只检查 `authorization.allowUpload`）
  - `core/src/commonMain/kotlin/com/folderspan/data/main/Disk.kt`（`Local.menuPermission.write = true`，网页上传对 Local / Device 开放）
* 置信度：0.91

### 描述

第五轮 FS-23 已经要求分享工具同时具备 `files.read`，避免「只会分享、不能读」的 token 枚举并签发局域网分享。本轮剩余的缺口是**写权限**：`folderspan_files_share_link` 的 `requiredScopes` 仍是 `FilesShare + FilesRead`，**不要求** `FilesWrite`。工具 schema 把 `allowUpload` 做成可选布尔值，处理器默认 `false`，但一旦调用方显式传入 `true`，就会原样进入 ticket：

```kotlin
tool(
    "folderspan_files_share_link",
    "Create a time-limited LAN link share.",
    McpTokenScope.FilesShare,
    shareLinkSchema(),
    extraScopes = setOf(McpTokenScope.FilesRead),
) { args ->
    json(
        fileFacade.shareLink(
            args.locators("locators"),
            args.boolean("allowHidden") ?: false,
            args.boolean("allowUpload") ?: false,
        ),
    )
}
```

`McpFileSharingFacade.shareLink` 解析 locator、过滤可分享文件后，必要时直接 `shareServer.start(port)`，再：

```kotlin
val ticket = shareState.issueLinkShareTicket(allowHidden, allowUpload, filtered, nowMillis())
```

ticket 兑换不经过主人确认。`prepareLinkShareAccess` 消费 ticket 后立刻签发 session，并把 **ticket 上的 `allowUpload`** 拷进会话与授权表：

```kotlin
val session = fileShareState.issueLinkShareSession(
    clientId = device.id,
    fingerprint = fingerprint,
    allowHidden = redeemedTicket.allowHidden,
    allowUpload = redeemedTicket.allowUpload,
    files = redeemedTicket.files
)
```

之后网页上传只看 `authorization.allowUpload`。Local 磁盘默认 `write=true`，`supportsHttpShareUpload()` 对 Local / Device 返回 true。敏感路径与符号链接仍会被网关拦住，所以打不到应用私有数据；但任意**非敏感**本地目录都可以变成局域网可写根。

这与宿主 UI 的两条护栏都不一致：

1. `FileShareState._allowUpload` 默认 `false`。MCP 路径**不**与该开关做 AND，也不读取当前 UI 勾选。
2. 访客在只读分享里再申请上传，会走 `requestLinkShareUploadPermission()`，由本机通知确认。MCP 签发的 ticket 已经带 `allowUpload=true`，兑换后 `authorization.allowUpload` 直接为真，**不再弹出确认**。

权限矩阵因此出现缺口：

| 工具 | 声明 scopes | 实际能力 |
| --- | --- | --- |
| `folderspan_file_write` / copy / move / delete | `files.write` | 直接写本机或已授权端点 |
| `folderspan_files_share_link` | `files.share` + `files.read` | `allowUpload=true` 时给局域网访客间接写 |

OpenSpec `mcp-file-access` 把 mutation 归在 `files.write`，分享工具只应创建分享。现有实现让「分享助手」token 获得写效果。自定义权限网格可以只勾 FilesShare + FilesRead；`withImpliedMcpScopes` 也不会因为勾选分享而补上 FilesWrite。

### 影响

- 持有 `files.read` + `files.share`、故意不给 `files.write` 的 MCP token，可以把任意非敏感 Local / Device 目录变成带上传权的局域网分享。
- 分享服务若尚未运行，工具会自行启动；ticket 有效期 10 分钟，兑换后 session 12 小时。
- 邻机拿到 URL 后可向分享根上传或覆盖文件，不必再过主人确认。
- 被盗 token 或被恶意 prompt 驱动的 MCP 客户端同样成立；MCP 本身不必开启局域网绑定（loopback 上的客户端被驱动即可）。

### 利用场景

用户给自动化客户端签发「分享助手」token，只勾选文件读取与分享，明确不给写入。客户端被恶意上下文驱动，调用 `folderspan_files_share_link`，locator 指向本机文档目录，并把 `allowUpload` 设为 true。工具返回带 ticket 的 HTTP/HTTPS URL。局域网邻机在有效期内兑换 ticket，获得 `allowUpload=true` 的 session，向该目录上传文件。主人侧分享页不会出现「访客申请上传」的待确认项。

<details>
<summary>修复方案</summary>

1. **`allowUpload=true` 时额外要求 `McpTokenScope.FilesWrite`。** 缺 scope 则在 `tools/list` 仍可看到分享工具，但 `tools/call` 必须拒绝，或强制把 `allowUpload` 降为 `false`。推荐拒绝：调用方明确要写，静默降级会掩盖权限错误。
2. **与宿主开关做 AND。** MCP 不得单独打开 `FileShareState.allowUpload` 当前为 false 的上传。更稳妥的是：即使 token 有 FilesWrite，也只允许在主人已经打开网页上传时签发可写 ticket。
3. **不要绕过本机确认。** 若产品仍希望 MCP 能开上传，应对齐 UI：先 `requestLinkShareUploadPermission`，由本机用户确认后再把 `allowUpload` 写进 ticket / session。
4. 现有 `shareToolsRequireFilesReadInAdditionToFilesShare` 只覆盖「缺 FilesRead」。应补「仅 FilesShare + FilesRead、`allowUpload=true` 必须失败」；有 FilesWrite 时才允许把 `allowUpload` 传下去。
5. 文档 `docs/networking/mcp-http-server.md` 应写明：可写链路分享属于 mutation，需要 `files.write`。

```kotlin
tool(
    "folderspan_files_share_link",
    "Create a time-limited LAN link share.",
    McpTokenScope.FilesShare,
    shareLinkSchema(),
    extraScopes = setOf(McpTokenScope.FilesRead),
) { args ->
    val allowUpload = args.boolean("allowUpload") ?: false
    json(
        fileFacade.shareLink(
            args.locators("locators"),
            args.boolean("allowHidden") ?: false,
            allowUpload,
        ),
    )
}

suspend fun call(
    name: String,
    arguments: JsonObject,
    scopes: Set<McpTokenScope>,
): McpCallToolResult {
    val tool = tools[name] ?: return errorResult("not_found", "tool was not found")
    if (!scopes.containsAll(tool.requiredScopes)) {
        return errorResult("permission_denied", "token does not allow this tool")
    }
    if (name == "folderspan_files_share_link" &&
        arguments["allowUpload"]?.jsonPrimitive?.booleanOrNull == true &&
        McpTokenScope.FilesWrite !in scopes
    ) {
        return errorResult("permission_denied", "writable link share requires files.write")
    }
    // ...
}
```

更干净的做法是在 `McpFileSharingFacade.shareLink` 入口校验，并与 `shareState.allowUpload.value` 做 AND，避免只改 registry、漏掉其他调用方。

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun shareLinkAllowUploadRequiresFilesWrite() = runTest {
    val registry = McpToolRegistry()
    val shareAndRead = setOf(McpTokenScope.FilesShare, McpTokenScope.FilesRead)
    val args = buildJsonObject {
        put(
            "locators",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("protocol", FileProtocol.Local.name)
                        put("path", "/tmp/share.txt")
                    },
                )
            },
        )
        put("allowUpload", true)
    }

    assertTrue("folderspan_files_share_link" in registry.list(shareAndRead).map { it.name })
    val denied = registry.call("folderspan_files_share_link", args, shareAndRead)
    assertToolError(denied, "permission_denied")

    val readOnlyShare = registry.call(
        "folderspan_files_share_link",
        buildJsonObject {
            put("locators", args.getValue("locators"))
            put("allowUpload", false)
        },
        shareAndRead,
    )
    assertFalse(readOnlyShare.structuredContent?.get("allowUpload")?.jsonPrimitive?.booleanOrNull == true)
}

@Test
fun shareLinkAllowUploadIsRejectedWhenHostUploadSwitchIsOff() = runTest {
    val shareState = FileShareState()
    assertFalse(shareState.allowUpload.value)
    val ticket = shareState.issueLinkShareTicket(
        allowHidden = false,
        allowUpload = true,
        files = emptyList(),
    )
    assertFalse(ticket.allowUpload)
}
```

测试只断言 scope 与宿主开关会拒绝可写分享；不要在夹具里放真实局域网 URL 或可执行上传客户端。第二个用例对应「MCP 不得绕过 `_allowUpload` 默认关闭」；若选择「有 FilesWrite 即可开上传、不看宿主开关」，则只保留第一条。

</details>

---

## FS-30：网盘 Local→Network 复制绕过敏感路径策略，SFTP/SMB/FTP 还会跟随叶子符号链接

* 严重度：高
* 类别：sensitive_file_read / confused_deputy
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/data/main/network/Network.kt`（`copyTo` 的 `FileProtocol.Local → Network` 分支约 316–371 行：单文件直接 `client.upload(localPath = srcFileSimpleInfo.path)`，无 `deniedException`、无 `isSymbolicLink` 检查；目录分支约 372–405 行：`collectDirectoryEntriesAdaptive(..., rejectSymbolicLinkEntries = true)` 只拒子项符号链接；`uploadFileEntry` 约 520–549 行同样把 `entry.path` 交给 `client.upload`）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/file/FileStateCopyCoordinator.kt`（`CopyRoute.LocalToNetwork` 约 154–161 行走 `networkAccess.copyTo`，**不**走 `FileSimpleInfo.copyTo`）
  - `core/src/commonMain/kotlin/com/folderspan/data/file/FileInfo.kt`（对照：`FileSimpleInfo.copyTo` / `writeToFile` 约 508–524、153–169 行已对 Local 源做 `deniedException` + 符号链接拒绝）
  - `core/src/jvmMain/kotlin/com/folderspan/service/network/SftpNetworkClient.kt` 与 Android 对应实现、`SmbNetworkClient`、`FtpNetworkClient`（`upload` 用 `FileSystem.SYSTEM.source(localPath.toPath())`，跟随符号链接）
  - `core/src/commonMain/kotlin/com/folderspan/service/network/WebDavNetworkClient.kt`（`upload` 走 `FileUtils.readFileChunks`，叶子符号链接会失败，但仍不拦敏感普通文件）
  - `core/src/commonMain/kotlin/com/folderspan/service/mcp/file/FileGatewayTransferCoordinator.kt`（对照：MCP `rejectProtectedLocalPaths` 约 361–372 行已在 copy/move 提交前拦截受保护 Local 路径，本条不是 MCP 洞）
* 置信度：0.90

### 描述

第五轮 FS-25 修的是**桌面剪贴板文件列表与拖放准备**：`prepareDesktopExternalFiles` 现在会 `deniedException(absolutePath)` 并拒绝符号链接。本地 `FileSimpleInfo.copyTo` / `writeToFile` 也补上了同一道门。本轮剩余的缺口是**网盘复制协调器走的另一条路**。

用户在本机文件视图选中文件或目录，粘贴/复制到已连接的 SFTP、SMB、FTP 或 WebDAV 时，`FileStateCopyCoordinator` 判定 `CopyRoute.LocalToNetwork`，直接调用 `Network.copyTo`：

```kotlin
CopyRoute.LocalToNetwork -> {
    ensureRunningOrThrow()
    val networkAccess = resolveNetworkAccess(
        preferredDesk = currentDesk(),
        protocolId = destFileSimpleInfo.protocolId,
    )
    networkAccess?.copyTo(task, srcFileSimpleInfo, destFileSimpleInfo)
        ?: Result.failure(EmptyDataException())
}
```

`Network.copyTo` 全文搜不到 `deniedException`。单文件分支把 `srcFileSimpleInfo.path` 原样交给 `client.upload`，也不看 `isSymbolicLink`。目录分支用 `rejectSymbolicLinkEntries = true` 跳过**子项**符号链接，但对每个普通文件仍然：

```kotlin
val result = client.upload(
    localPath = entry.path,
    remotePath = remotePath,
    size = entry.size,
    onProgress = { ... },
)
```

SFTP / SMB / FTP 的 JVM 与 Android 实现都是：

```kotlin
FileSystem.SYSTEM.source(localPath.toPath()).buffer().use { source ->
    copyStreamWithProgress(source.inputStream(), output, size, onProgress)
}
```

Okio `FileSystem.SYSTEM.source` 跟随符号链接。因此：

1. **策略覆盖的真实文件**（`folderspan.db`、`tls-identity`、凭据包装密钥、secure-settings 金库等）只要出现在 Local 源路径上，就会被上传到远端网盘。本地 `copyTo` 已经拒绝这些路径；网盘路径没有。
2. **叶子符号链接**（单文件复制，或不被目录遍历当成「子项符号链接」而跳过的入口）在 SFTP/SMB/FTP 上会读到链接目标。WebDAV 走 `readFileChunks`，叶子链接会失败，但敏感普通文件仍会上传。

这与 FS-25 不是同一入口：剪贴板/拖放准备阶段已经过滤；用户从本机文件列表发起的复制、以及 `startDropUpload` 在准备批次之后再次 `fileState.copyTo` 落到 Network 时，都会走这条未设门禁的路径。MCP 网关的 `rejectProtectedLocalPaths` 不保护 UI 复制。

产品对敏感路径的意图是「即使本机用户选中，也不应经分享、设备 API、MCP 或网盘离开本机」。设备 RPC 与 MCP 已落地；Local→Local 已落地；Local→Network 漏了。

### 影响

- 本机文件视图里能列到的应用私有数据，可被复制到用户已登录的 SFTP/SMB/FTP/WebDAV。远端或能访问该网盘的邻机即可读取 `tls-identity`、数据库和凭据。
- 单文件复制一个指向敏感目标的符号链接时，SFTP/SMB/FTP 会上传目标内容，而不是拒绝链接。
- 不需要 MCP token，不需要 Intent，也不需要剪贴板投毒。只要用户（或被 UI 自动化驱动的复制任务）把 Local 源贴到网盘。

### 利用场景

用户已连接一台 SFTP。本机文件列表导航到应用数据目录（或复制一个指向该目录下真实文件的符号链接），执行复制到网盘当前目录。`Network.copyTo` 调用 `SftpNetworkClient.upload`，Okio 跟随链接或直接读取普通文件，远端出现 `folderspan.db` / `tls-identity` 的副本。同一操作若目标是本机另一目录，会被 `FileSimpleInfo.copyTo` 拒绝。

<details>
<summary>修复方案</summary>

1. **在 `Network.copyTo` 的 Local 源入口与每个 `uploadFileEntry` 调用 `SensitiveFileAccessPolicy.deniedException(path)`。** 命中即 `markCopyFailure` 并跳过该条目，不要把路径原文写进任务错误（沿用 `protectedPathMessage`）。
2. **单文件与目录上传都拒绝叶子符号链接。** 在 `client.upload` 之前检查 `entry.isSymbolicLink` 或 `PathUtils.isSymbolicLink(FileAccessPermission.Allowed, entry.path)`。目录遍历的 `rejectSymbolicLinkEntries` 不能替代单文件分支。
3. 更稳妥的是让 Local→Network 复用 `FileSimpleInfo.copyTo` / `writeToFile` 已经做过的源侧校验，或抽成 `requireOrdinaryUnprotectedLocalSource(path)` 供复制协调器、网盘客户端、桌面 `startDropUpload` 共用。
4. SFTP/SMB/FTP 的 `upload` 不要用跟随链接的 `FileSystem.SYSTEM.source` 读用户指定路径；应先确认是普通文件再打开。WebDAV 已走 `readFileChunks`，但仍需在更外层拦敏感路径。
5. MCP 路径已有 `rejectProtectedLocalPaths`，不要只修网关、漏掉 UI。

```kotlin
FileProtocol.Local if destFileSimpleInfo.protocol == FileProtocol.Network -> {
    SensitiveFileAccessPolicy.deniedException(srcFileSimpleInfo.path)?.let { error ->
        return Result.failure(error)
    }
    if (srcFileSimpleInfo.isSymbolicLink ||
        PathUtils.isSymbolicLink(FileAccessPermission.Allowed, srcFileSimpleInfo.path)
    ) {
        return Result.failure(
            IllegalStateException(
                AppStrings.file_symbolic_link_copy_not_supported.format(path = srcFileSimpleInfo.path)
            )
        )
    }
    // ... 再 client.upload
}

suspend fun uploadFileEntry(entry: FileSimpleInfo) {
    SensitiveFileAccessPolicy.deniedException(entry.path)?.let { error ->
        recordFailure(buildRemotePath(entry), error.message.orEmpty(), false)
        return
    }
    if (entry.isSymbolicLink ||
        PathUtils.isSymbolicLink(FileAccessPermission.Allowed, entry.path)
    ) {
        recordFailure(buildRemotePath(entry), AppStrings.file_symbolic_link_copy_not_supported, false)
        return
    }
    val result = client.upload(localPath = entry.path, ...)
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun localToNetworkCopyRejectsProtectedApplicationData() = runTest {
    val appData = resolveDesktopApplicationDataDirectory()
    val db = appData.resolve("folderspan.db").toString()
    val identity = appData.resolve("tls-identity").toString()
    val network = recordingNetworkClient()

    val dbResult = copyLocalToNetwork(network, localPath = db, remotePath = "/upload/db")
    val identityResult = copyLocalToNetwork(network, localPath = identity, remotePath = "/upload/identity")

    assertTrue(dbResult.isFailure)
    assertTrue(identityResult.isFailure)
    assertTrue(network.uploadedLocalPaths.isEmpty())
    assertTrue(dbResult.exceptionOrNull()?.message.orEmpty().contains("application_private_data"))
    assertFalse(dbResult.exceptionOrNull()?.message.orEmpty().contains(db))
}

@Test
fun localToNetworkCopyRejectsLeafSymbolicLinkWithoutFollowingIt() = runTest {
    val directory = Files.createTempDirectory("network-copy-link")
    val secret = directory.resolve("secret.txt")
    val link = directory.resolve("alias")
    Files.writeString(secret, "secret")
    val created = runCatching { Files.createSymbolicLink(link, secret) }.getOrNull()
        ?: return@runTest
    try {
        val network = recordingNetworkClient()
        val result = copyLocalToNetwork(
            network,
            localPath = created.toString(),
            remotePath = "/upload/alias",
        )
        assertTrue(result.isFailure)
        assertTrue(network.uploadedLocalPaths.isEmpty())
        assertTrue(network.readBytes.isEmpty())
    } finally {
        Files.deleteIfExists(link)
        directory.toFile().deleteRecursively()
    }
}
```

测试只断言受保护路径与叶子符号链接不会进入 `client.upload`，也不要把真实数据库或 TLS 身份内容读进夹具。`recordingNetworkClient` 记录被请求的本地路径即可，不要对真实远端发上传。

</details>

---

## FS-31：Android SEND 把 Intent flag 当成 URI grant，拖放与剪贴板在无系统授权时仍打开 content URI

* 严重度：中
* 类别：intent_confused_deputy
* 修复状态：已修复
* 位置：
  - `app/shared/src/androidMain/kotlin/com/folderspan/share/ShareIntentHandler.android.kt`（`grantedShareUri` 约 173–184 行：`intent.flags and FLAG_GRANT_READ_URI_PERMISSION != 0` 即放行；回落 `checkUriPermission(uri, Binder.getCallingPid(), Binder.getCallingUid(), …)`；`handleDragEvent` ACTION_DROP 约 101–127 行：`extractGrantedContentUris` 只要求 `scheme == content`，`requestDragAndDropPermissions` 为 null 仍调用 `handleDroppedFiles` / `handleDroppedFilesForShare`；`extractGrantedContentUris` 约 224–234 行）
  - `core/src/androidMain/kotlin/com/folderspan/utils/ShareHandler.kt`（`handleSharedFilesForShare` / `handleDroppedFiles` 用 `getFileInfoFromUri` 打开 URI；`stageClipboardEntry` 约 680–697 行：`content://` 直接 `openInputStream`，否则 `File(sourcePath).inputStream()`，无 grant、无 `deniedException`）
  - `app/shared/src/androidMain/kotlin/com/folderspan/clipboard/ClipboardFilePasteController.android.kt`（`readClipboardFileBatch` 约 12–26 行取出 `primaryClip` URI 后交给 `prepareClipboardFiles`）
* 置信度：0.84

### 描述

第三轮 FS-14 的原文是「SEND 完全不检查 grant」，且自动同步默认开启。落地后：自动更新开关默认已是 `false`；SEND 增加了 `grantedShareUri`。本条不是再开一次 FS-14，而是**那次修复的检查主体仍不正确**。

```kotlin
private fun grantedShareUri(intent: Intent, uri: Uri?): Uri? {
    if (uri == null) return null
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) return uri
    val granted = activity.checkUriPermission(
        uri, Binder.getCallingPid(), Binder.getCallingUid(),
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
    ) == PackageManager.PERMISSION_GRANTED
    return uri.takeIf { granted }
}
```

两处都不验证「系统是否已经把该 URI 的读权限授予 FolderSpan」：

1. **Flag 短路。** `Intent.FLAG_GRANT_READ_URI_PERMISSION` 由**发送方**设置。恶意应用可以给任意 `content://` extra 加上这个 flag。系统只会在发送方自己拥有该 URI 时写入 grant；发送方没有权限时，接收方也拿不到 grant。但本函数见 flag 就返回 URI，随后 `ShareHandler` 用 FolderSpan 自己的 `MANAGE_EXTERNAL_STORAGE` / `READ_MEDIA_*` 去 `query` / `openInputStream`。
2. **`Binder.getCallingPid()` / `getCallingUid()`。** 在已经启动的 Activity 里处理 SEND，这两个值通常是 **FolderSpan 自己的 pid/uid**，不是发送方。`checkUriPermission` 问的是「本应用是否已有该 URI 的权限」，应用因存储权限本就可以读大量 MediaStore / 外部存储 URI，检查失去「发送方是否授予」的意义。应使用 `uriPermissionChecks` / 系统写入的 URI grant（例如确认 `checkUriPermission` 针对 persistable/temporary grant，或尝试 `takePersistableUriPermission` 失败则拒绝），而不是 Intent 上可伪造的 flag。

拖放与剪贴板连这层不完整的检查都没有：

- `extractGrantedContentUris` 只过滤 `content` scheme。
- ACTION_DROP 在 `requestDragAndDropPermissions(event)` 返回 null 时，非分享列表路径仍然 `handleDroppedFiles(activity, fileState, uris)`；分享列表路径也会先调用 `handleDroppedFilesForShare`。
- 剪贴板 `readClipboardFileBatch` 取出 URI 后 `prepareClipboardFiles` → `stageClipboardEntry` 直接 `openInputStream`。同机应用可以把 FolderSpan 读得到、自己读不到的 content URI 放进剪贴板。

`file://` 与 nested intent 在 SEND 路径上已被 scheme 过滤挡住，这一点比 FS-14 原文有进步。自动同步默认关闭，也降低了「导入后立刻出现在已授权链路分享」的默认风险。剩余问题是：**未获系统 URI grant 的跨应用内容仍可借 FolderSpan 身份被导入分享桌面或当前目录。**

### 影响

- 恶意应用发送 `ACTION_SEND`，带上 `FLAG_GRANT_READ_URI_PERMISSION` 和一个 FolderSpan 凭自身存储权限能打开的 `content://`（例如其他应用私有 Provider 之外、但本应用因 `MANAGE_EXTERNAL_STORAGE` 能读的外部存储文档）。FolderSpan 会把该文件导入分享列表或打开。
- 跨应用拖放在系统未给出 `DragAndDropPermissions` 时仍会处理 URI。
- 剪贴板粘贴同样不校验 grant。自动同步默认关闭，所以不一定立刻同步到局域网分享；用户若之后手动分享或打开了自动更新，导入内容仍会离开本机。

### 利用场景

用户打开 FolderSpan。另一应用构造 `ACTION_SEND`，`type=*/*`，`FLAG_GRANT_READ_URI_PERMISSION`，`EXTRA_STREAM` 指向一个发送方自己打不开、FolderSpan 因存储权限打得开的 content URI。`grantedShareUri` 因 flag 短路放行，文件进入分享桌面。用户未开自动同步时文件先留在本机列表；一旦用户分享或打开自动更新，邻机即可读取。

<details>
<summary>修复方案</summary>

1. **不要把发送方设置的 `FLAG_GRANT_READ_URI_PERMISSION` 当作已授权。** Flag 只表示发送方*请求*授予，不表示系统已写入 grant。
2. **用系统 URI permission 表判断。** 对每个 URI 调用 `checkUriPermission` 时，不能用 Activity 自己的 pid/uid 当「调用方」。可行做法：去掉 flag 短路；对 `content://` 先 `acquireUnstableContentProviderClient` / `openInputStream` 包在只接受已授权 URI 的辅助函数里，失败则丢弃；或检查 `context.contentResolver.persistedUriPermissions` 与当前 Intent 的 ClipData grant。`Intent.getClipData()` 上的 URI 才是系统实际授予的集合，优先只处理 ClipData 里的项，而不是裸 `EXTRA_STREAM`。
3. **拖放：`requestDragAndDropPermissions` 为 null 则整次 DROP 失败**，不要继续 `handleDroppedFiles`。`extractGrantedContentUris` 应只返回 permissions 覆盖的 URI。
4. **剪贴板：只接受能 `openInputStream` 且不是应用私有路径的 URI**；本地 `file://` / 绝对路径走 `deniedException`。不要把剪贴板当 SEND 的免检入口。
5. 现有 FS-14 测试若只断言「没有 extra 则忽略」，应改成「仅有 flag、系统未写入 grant 则忽略」。

```kotlin
private fun grantedShareUri(intent: Intent, uri: Uri?): Uri? {
    if (uri == null) return null
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    val granted = activity.checkUriPermission(
        uri,
        /* pid = */ -1,
        /* uid = */ -1,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
    ) != PackageManager.PERMISSION_GRANTED
    // 不要用 callingPid/callingUid，也不要读 intent.flags。
    // 更稳妥：仅当 ClipData 含该 URI，或 openInputStream 在无全局存储权限的受限上下文中成功。
    return uri.takeIf { hasSystemUriGrant(uri) }
}

fun handleDragEvent(event: DragEvent): Boolean = when (event.action) {
    DragEvent.ACTION_DROP -> {
        val permission = requestDropPermissions(event) ?: return false
        val uris = extractGrantedContentUris(event.clipData)
        if (uris.isEmpty()) {
            runCatching { permission.release() }
            false
        } else {
            heldDropPermissions += permission
            ShareHandler.handleDroppedFiles(activity, fileState, uris)
            true
        }
    }
    else -> false
}
```

`hasSystemUriGrant` 的实现应能在「应用持有 `MANAGE_EXTERNAL_STORAGE` 但发送方未授予该 URI」时返回 false。不要靠「打得开就放行」。

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun sendWithGrantFlagButNoSystemUriPermissionIsIgnored() {
    val uri = Uri.parse("content://com.other.app/private/1")
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_STREAM, uri)
    }
    val handler = ShareIntentHandler(activity, fileState, fileShareState, mainState)
    assertFalse(handler.hasShareData(intent))
    assertTrue(handler.grantedShareUriForTest(intent, uri) == null)
}

@Test
fun dropWithoutDragAndDropPermissionsIsIgnored() {
    val uris = listOf(Uri.parse("content://com.other.app/private/2"))
    val accepted = handler.handleDropForTest(
        uris = uris,
        dragAndDropPermissions = null,
    )
    assertFalse(accepted)
    assertTrue(fileState.currentFiles.none { item -> item.path.contains("private/2") })
}

@Test
fun clipboardStagingDoesNotOpenUngrantedContentUri() {
    val uri = Uri.parse("content://com.other.app/private/3")
    val batch = ShareHandler.prepareClipboardFiles(context, listOf(uri))
    assertTrue(batch.files.isEmpty())
    assertTrue(batch.skipped.any { skip -> skip.reason == ExternalFileSkipReason.Unreadable })
}
```

测试只断言无系统 URI grant 时 SEND / 拖放 / 剪贴板不会导入；不要在夹具里放可读取真实私有 Provider 的攻击 Intent，也不要演示跨应用读取步骤。

</details>

---

## 已检查、不作为本轮新漏洞

| 主题 | 结论 |
| --- | --- |
| FS-01～FS-28 | 源码与测试表明已落地或（FS-06）为误报，不重复开洞。 |
| MCP `files.write` 的 copy / move | 规格把 copy/move 归在 `files.write`。`copyTree` 会检查源端 `permissions.read`（磁盘能力，不是 token）。自定义「只写」token 可以复制非敏感文件，但内容不会回到 MCP 响应，也不绕过敏感路径。与 FS-19 / FS-23 的「缺隐含读权限」不同，本轮不另开 ID。 |
| 归档相对路径 / zip-slip | `FolderSpanArchiveCodec.normalizeRelativePath` 拒绝 `..`、绝对路径、反斜杠。设备归档上传走 `prepareWriteBytes`（鉴权 + 敏感路径 + 角色根）。相对路径未拒 NUL，但权限根与 canonical 检查仍在；未形成 >80% 可利用链。 |
| HTTP 请求走私 | 设备 API（JVM/Android）忽略 chunked，只信 Content-Length；LinkShare / iOS 单解析器且 TE 优先。scheme-switching 代理按连接透传，不复用后端连接。无前置反向代理时，残留字节只污染攻击者自己的连接。 |
| WebSocket | 本机 Raw HTTP / 链路分享没有 Upgrade。仓库内 WebSocket 是 WebRTC 客户端连外部信令。 |
| 编辑器本地读写 | `FileEditorContentAccess` 对 Local 使用 `FileAccessPermission.Allowed`。打开路径来自本机 UI 已选文件，不是网络输入；敏感路径策略未在这一层重复，但不构成邻机可利用面。 |
| Android `content://` 打开（系统选择器） | `openFile` 对 content URI 加 `FLAG_GRANT_READ_URI_PERMISSION` 后交给系统选择器，属用户选定目标。SEND/拖放/剪贴板的 grant 残留见 FS-31，不在此重复。 |
| Shizuku IPC | UserService 绑定本包 `ShizukuFileService`；`onTransact` 只 `enforceInterface`，无 callingUid。利用前提是攻击者已经持有 Shizuku（等价 ADB/root 级文件系统）。前几轮已排除；增量风险是复用 FolderSpan 已拉起的特权进程，未达本轮 >80% 新洞门槛。 |
| 设备 API `/proc` `/sys` `/dev` | `isRestrictedAliasFilesystemPath` 只在 MCP `LocalFileEndpointGateway.denySensitiveOrRestricted` 使用。设备 `prepareReadBytes` 走 `deniedException` + 角色路径，不覆盖这些挂载点。`checkPermission` 在 token 缺失或角色权限列表为空时拒绝。管理员角色在测试库由 `grantTestAdministratorAccess` 授权，生产上对已授权对等设备暴露整盘更接近产品行为，未形成稳固的未授权邻机链。 |
| 桌面剪贴板 / 拖放准备 | FS-25 已在 `prepareDesktopExternalFiles` 接入 `deniedException` 与符号链接拒绝。本轮 FS-30 覆盖的是准备之后、经 `FileStateCopyCoordinator.LocalToNetwork` 落入 `Network.copyTo` 的复制路径，不重复开 FS-25。 |
| JWT / OAuth / 不安全反序列化 | 产品无 JWT/OAuth 资源服务器。`FileAccessPermission` 不是网络可反序列化类型；归档头走自研 protobuf。 |
| 密钥扫描 | 仓库内无 `AKIA` / `BEGIN PRIVATE KEY` / PKCS12 跟踪命中。 |
| StreamSaver `Service-Worker-Allowed: /` | 第五轮已记录，不另开 ID。 |
| 局域网明文 HTTP 分享 | 产品设计，前几轮已记录。 |

## 修复优先级建议

1. **FS-29（高，已修复）**：`folderspan_files_share_link` 在 `allowUpload=true` 时要求 `files.write`，并与宿主上传开关对齐。缺 scope 或宿主未开上传时拒绝签发可写 ticket。
2. **FS-30（高，已修复）**：`Network.copyTo` 的 Local 源、目录条目与 `uploadFileFromLocal` 对齐 `deniedException` + 叶子符号链接拒绝。
3. **FS-31（中，已修复）**：SEND 按本应用 URI grant 表检查，不再读 Intent flag 或 callingPid/Uid；拖放在 `requestDragAndDropPermissions == null` 时整次失败；剪贴板暂存拒绝无 grant 的 content URI 与敏感本地路径。

# FolderSpan 安全审计报告（第十轮）

- 审计日期：2026-08-24
- 审计范围：全仓（`app/`、`core/`、`proMain/`），非仅当前 PR diff
- 前置：
  - 第一轮见 [2026-08-21 全仓安全审计报告](2026-08-21-security-audit-report.md)（FS-01～FS-05 已修复，FS-06 误报）
  - 第二轮见 [2026-08-21 全仓安全审计报告（第二轮）](2026-08-21-security-audit-round-2.md)（FS-07～FS-10 已修复）
  - 第三轮见 [2026-08-21 全仓安全审计报告（第三轮）](2026-08-21-security-audit-round-3.md)（FS-11～FS-16 已修复）
  - 第四轮见 [2026-08-22 全仓安全审计报告（第四轮）](2026-08-22-security-audit-round-4.md)（FS-17～FS-20 已修复）
  - 第五轮见 [2026-08-23 全仓安全审计报告（第五轮）](2026-08-23-security-audit-round-5.md)（FS-21～FS-27 已修复）
  - 第六轮见 [2026-08-23 全仓安全审计报告（第六轮）](2026-08-23-security-audit-round-6.md)（FS-28 已修复）
  - 第七轮见 [2026-08-23 全仓安全审计报告（第七轮）](2026-08-23-security-audit-round-7.md)（FS-29～FS-31 已修复）
  - 第八轮见 [2026-08-23 全仓安全审计报告（第八轮）](2026-08-23-security-audit-round-8.md)（FS-32～FS-37 已修复）
  - 第九轮见 [2026-08-23 全仓安全审计报告（第九轮）](2026-08-23-security-audit-round-9.md)（FS-38～FS-44 已修复）
- 方法论：Claude Marketplaces 插件 `cybersecurity-skills@anthropic-cybersecurity-skills` v1.3.0
  覆盖技能：`performing-csrf-attack-simulation`、`testing-for-xss-vulnerabilities`、`testing-cors-misconfiguration`、`performing-directory-traversal-testing`、`testing-for-open-redirect-vulnerabilities`、`testing-websocket-api-security`、`testing-android-intents-for-vulnerabilities`、`exploiting-deeplink-vulnerabilities`、`testing-for-xxe-injection-vulnerabilities`、`exploiting-insecure-deserialization`、`testing-for-host-header-injection`、`performing-clickjacking-attack-test`、`performing-security-headers-audit`、`exploiting-insecure-data-storage-in-mobile`、`testing-for-sensitive-data-exposure`、`implementing-secret-scanning-with-gitleaks`、`auditing-mcp-servers-for-tool-poisoning`、`exploiting-smb-vulnerabilities-with-metasploit`、`performing-ssrf-vulnerability-exploitation`
- 约束：本报告不含攻击载荷或 exploit PoC。折叠区内的「验证代码」均为防御性回归测试（断言安全属性），用于确认现有控制是否仍生效。
- 威胁模型：局域网邻机、同机恶意进程/网页、不可信 Android Intent、SFTP 中间人、已授权设备上的 token 盗用。本机文件分享服务按产品设计会对局域网可达。
- 本轮只登记相对前九轮**仍未修复的新问题**，且置信度约 ≥0.8、有可利用路径。已落地的 FS-01～FS-44、已排除项与产品明确文档化的同步行为不重复开洞。

## 审计发现总览

本轮**无新登记漏洞**（不分配 FS-45 及后续编号）。

对照第九轮修复提交 `82fb756a95`（`fix(security): 修复第九轮审计 FS-38 至 FS-44`）之后的 `develop` 工作树，按上列技能复核 CSRF、XSS、开放重定向、归档穿越、导出组件、剪贴板粘贴、MCP 工具 scope、SMB 签名与 StreamSaver 等面。凡能落到具体代码路径的线索，要么已被前九轮修复，要么不满足本轮开洞阈值。

| ID | 严重度 | 类别 | 标题 | 状态 |
| --- | --- | --- | --- | --- |
| — | — | — | 本轮无置信度 ≥0.8 的新可利用问题 | 无新登记 |

---

## 本轮复核结论

第九轮落地后，下列入口与第九轮修复对齐，本轮复测通过：

| 面 | 当前控制 | 对照 |
| --- | --- | --- |
| 链路分享 Host | `dispatch` 在 CORS 预检之后、业务路由之前调用 `rejectedLinkShareHostResponse`；非广告 Host 403 | FS-38 |
| Web 敏感设置 | Web 走 `SensitiveSettings` AES-GCM 金库，不再明文 `localStorage` | FS-39 |
| 金库刮除明文 | `putString` 写入后清除 Preferences / NSUserDefaults 历史明文 | FS-40 |
| Desktop 权限 | 应用数据根、`folderspan.db`、`tls-identity/` 强制 0700/0600 | FS-41 |
| iOS 备份 | 数据库与 TLS 身份标 `NSURLIsExcludedFromBackupKey` | FS-42 |
| 网盘目录下载 | 相对路径规范化 + `isPathWithinRoot`；SFTP/FTP/SMB 不跟随叶子 symlink | FS-43 |
| 桌面缓存 | `getCachePath()` 迁出全局 `tmpdir`，owner-only | FS-44 |

本轮额外核对、仍不单开 ID 的面见下一节。

---

## 已检查、不作为本轮新漏洞

| 主题 | 结论 |
| --- | --- |
| FS-01～FS-44 | 源码与测试表明已落地或（FS-06）为误报，不重复开洞。 |
| 链路分享 CSRF / 上传 API | `handleShareUploadPermissionRequest` 与 `resolveShareUploadRequestOrRespond` 都先走 `ensureShareUploadApiRequest`：必须 `X-API-Request: true`；若有 Origin 则必须 `isSameOrigin`（scheme、主机、端口一致且在广告/loopback 集合）。无自定义头的跨站表单被 `SameSite=Lax` 挡住。 |
| 口令页 CSRF | `POST /auth` 无 CSRF token，但口令即能力、cookie `SameSite=Lax`、CSP `form-action 'self'`。第六/九轮已排除。 |
| 设备 API CSRF | `RawHttpApiDispatcher` 鉴权是 FileShare Access Key 头，无 cookie 会话；OPTIONS 与正式请求都走 `isRejectedPrivateWebRequest`。 |
| 分享列表 XSS / `href` | `handleRoot` / `handleSharedPath` 把 `file.path` 改写为 `"/${file.name}".urlPath()` 或 `"$urlPath/${file.name}".urlPath()`，百分号编码非 unreserved 字符。`IndexTemplate` 的 `href = file.path` 拿到的是分享 URL 路径，不是文件系统路径，也不是 `javascript:`。文件名经 kotlinx.html 转义。 |
| 主题 CSS 注入 | `toHex()` 只输出 `#RRGGBB`，不是用户字符串。 |
| 上传页 DOM XSS | `share-upload.js` 队列名走 `escapeHtml`；snackbar 用 `textContent`。 |
| 开放重定向 | `sanitizeLinkShareRedirect` 拒绝非相对路径、`//`、反斜杠与控制字符，超长回落到 `/`。 |
| 静态资源路径 | `resolveLinkShareStaticRelativePath` 只接受 `/static/` 前缀，分段拒绝 `.` / `..` / NUL / `\`；`normalizeLinkShareBundledResourcePath` 强制 `files/share-file/` 前缀后再读资源。`/static/` 跳过设备守卫是静态公开资源的产品选择，Host 白名单仍在 `dispatch` 入口。 |
| 归档 zip-slip | `FolderSpanArchiveCodec.normalizeRelativePath` 拒绝 `..`、`\`、绝对路径。第八轮已排除；第九轮 FS-43 修的是网盘目录下载未复用该规范化，现已落地。 |
| WebDAV `stripBasePath` | 前缀不匹配时返回 `"/"`，丢条目而非把远程 `href` 当成本地路径。 |
| Android 剪贴板粘贴 | `ShareHandler.prepareClipboardFiles`：`content:` 必须有 URI grant；`file:` / 无 scheme 走 `SensitiveFileAccessPolicy` 且拒绝 symlink。相对路径 `sanitizeClipboardRelativePath` 拒绝 `.` / `..` / NUL。 |
| Desktop 剪贴板 / 拖放 | 文件列表走 `prepareDesktopExternalFiles`：敏感路径拒绝 + 拒绝 symlink。剪贴板图片写入 `externalFileStagingRootPath`，目录 0700、文件 0600（FS-26 / FS-44 已覆盖暂存位置）。 |
| Android 导出组件 | `MainActivity` 导出是 SEND / VIEW 入口，第七轮 FS-31 已按 URI grant 校验。`FileProvider` `exported=false`；`BackgroundService` / `RootFileService` / `BootCompletedReceiver` 均 `exported=false`。`ShizukuProvider exported=true` 是 Shizuku 常规形态，权限 `INTERACT_ACROSS_USERS_FULL`，前几轮已排除。 |
| Android `ACTION_VIEW` MIME | FS-06 误报，不重开。 |
| iOS 深链 | `com.folderspan` 只把 `file:` URL 交给文档打开协调器；无 `NSAllowsArbitraryLoads`。 |
| MCP 工具 scope | 工具按 token scope 注册；`favorites_add` / `favorites_remove` 额外要求 `FilesRead`；可写链路分享同时要求 `files.write`。FS-15 / FS-19 / FS-23 / FS-29 已修完。 |
| MCP `device_scan` SSRF | `scanSchema` 只接受 `subnet`；`DeviceScanOperationStore.start` 把 CIDR 限制在本机活动 IPv4 前缀内，并限制地址数量。不是任意 URL / 任意主机探测。 |
| MCP Host | `McpHttpSecurityPolicy.allows` 在鉴权前拒绝非广告 Host。FS-16 之后 LAN 只接受 HTTPS。 |
| SMB 未强制签名 | JVM/Android `SmbNetworkClient` 使用默认 `SMBClient()`，未显式 `signingRequired`。这是用户配置的网盘客户端默认行为，威胁模型不把「用户连了未签名 SMB」登记为新洞。列表名仍走 `isUnsafeNetworkPathSegment`。 |
| StreamSaver `postMessage(..., '*')` | 供应商 `mitm.html` 行为；实际 `register` scope 是 `./`（`/static/streamsaver/`）。第六/九轮已排除。 |
| `Service-Worker-Allowed: /` | 只放宽最大 scope，注册 scope 仍是 `/static/streamsaver/`。第九轮已排除。 |
| GNOME `monitors.xml` XXE | `DocumentBuilderFactory` 未硬化，但输入仅本机配置，不是网络或上传 XML。第九轮已排除。 |
| 反序列化 | 仓库无 `ObjectInputStream` / Java 原生反序列化入口。 |
| WebView 注入 | 无 `addJavascriptInterface`。 |
| ProcessBuilder | DPI / 登录开机项命令是固定本机工具，无用户输入拼接。 |
| WebSocket | 仅作 WebRTC 信令客户端；本仓库不托管未鉴权的分享 WebSocket 服务。 |
| HostnameVerifier 全放行 | 仅测试代码（如 `McpHttpServiceJvmTest`），不进生产路径。 |
| Cookie `Secure=false`（明文 HTTP） | 产品局域网分享设计，前几轮已记录。 |
| 局域网明文 HTTP 分享 | 产品设计。本轮不因「LAN 可达」本身开洞。 |

<details>
<summary>残留硬化建议（不单开漏洞 ID）</summary>

这些项减小以后踩坑，但当前没有独立可利用路径，不分配 FS 编号。

1. 把 `Service-Worker-Allowed` 改成 `/static/streamsaver/`（或删掉），与注册 scope 对齐。
2. 链路分享 CORS 不要原样回显 `Access-Control-Request-Headers`，只用 `DEFAULT_CORS_ALLOW_HEADERS`。
3. 统一点击劫持头：常规页 `frame-ancestors 'none'` + `X-Frame-Options: DENY`，mitm 单独 `'self'` / `SAMEORIGIN`。
4. `POST /auth` 可走与上传 API 同一套 `isSameOrigin`，减少同 IP 其它端口上的口令尝试噪声。
5. SMB 客户端可在用户未关闭签名时启用 SMBJ signing（`SmbConfig.builder().withSigningRequired(true)` 或等价配置），作为网盘连接的纵深防御。
6. StreamSaver `mitm.html` / `StreamSaver.js` 的 `postMessage` 目标从 `*` 收到具体 origin，减少同页其它脚本误投递。

</details>

<details>
<summary>防御性验证代码（确认本轮控制仍生效，不含攻击载荷）</summary>

下列断言只检查安全属性：非广告 Host 被拒、上传缺自定义头被拒、静态路径不能跳出资源根、桌面外部文件跳过敏感路径与 symlink、MCP 扫描拒绝非本机网段。不要构造跨站表单、不要读取分享文件正文、不要对公网主机发探测。

```kotlin
@Test
fun linkShareRejectsUnaadvertisedHostBeforeDispatch() = runTest {
    val response = dispatcher.dispatch(
        request("/").copy(host = "share.example", port = 1204),
    )
    assertEquals(403, response.statusCode)
}

@Test
fun shareUploadWithoutApiHeaderIsForbidden() = runTest {
    val response = dispatcher.dispatch(
        authorizedPost("/api/share/upload-permission/request"),
    )
    assertEquals(403, response.statusCode)
}

@Test
fun shareUploadCrossOriginIsForbidden() = runTest {
    val response = dispatcher.dispatch(
        authorizedPost("/api/share/upload-permission/request").copy(
            headers = listOf(
                "X-API-Request" to "true",
                "Origin" to "https://evil.example",
            ),
        ),
    )
    assertEquals(403, response.statusCode)
}

@Test
fun staticPathRejectsParentSegments() {
    assertNull(resolveLinkShareStaticRelativePath("/static/../secret"))
    assertNull(normalizeLinkShareBundledResourcePath("files/share-file/static/../../other"))
    assertEquals(
        "share/share-index.js",
        resolveLinkShareStaticRelativePath("/static/share/share-index.js"),
    )
}

@Test
fun listingHrefUsesEncodedSharePathNotFilesystemPath() = runTest {
    val html = dispatcher.dispatch(authorizedGet("/")).bodyText()
    assertFalse(html.contains("href=\"/home/"))
    assertFalse(html.contains("javascript:"))
}

@Test
fun desktopExternalFilesSkipSensitiveAndSymlink() {
    val sensitive = File(resolveDesktopApplicationDataDirectory().toString(), "folderspan.db")
    val batch = prepareDesktopExternalFiles(listOf(sensitive))
    assertTrue(batch.files.isEmpty())
    assertTrue(batch.skipped.any { skip -> skip.reason == ExternalFileSkipReason.Unsupported })
}

@Test
fun mcpDeviceScanRejectsSubnetOutsideLocalPrefix() = runTest {
    val store = DeviceScanOperationStore(
        scope = this,
        subnetProvider = ActiveIpv4SubnetProvider { listOf(Ipv4InterfaceSubnet("192.168.1.10", 24)) },
        probe = McpDeviceProbe { _, _ -> false },
    )
    assertFails { store.start("10.0.0.0/8", port = 1204) }
}
```

测试只断言拒绝与路径规范化。不要打印口令、Access Key、金库明文或分享文件内容。

</details>

---

## 修复优先级建议

本轮无新登记漏洞，不新增必须立即落地的修复项。

若继续做纵深防御，优先顺序与第九轮残留硬化一致：

1. 收紧 `Service-Worker-Allowed` 与 StreamSaver `postMessage` origin。
2. CORS 允许头改为固定白名单，不要回显 `Access-Control-Request-Headers`。
3. 口令 `POST /auth` 对齐上传 API 的同源检查。
4. 可选：用户未关闭 SMB 签名时启用 SMBJ signing。

后续审计应把焦点放到本轮技能覆盖之外、或新引入的协议面（例如新的分享传输、新的 MCP 工具、新的平台导出组件），而不是重开 FS-01～FS-44。

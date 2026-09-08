# FolderSpan 安全审计报告（第五轮）

- 审计日期：2026-08-23
- 审计范围：全仓（`app/`、`core/`、`proMain/`），非仅当前 PR diff
- 前置：
  - 第一轮见 [2026-08-21 全仓安全审计报告](2026-08-21-security-audit-report.md)（FS-01～FS-05 已修复，FS-06 误报）
  - 第二轮见 [2026-08-21 全仓安全审计报告（第二轮）](2026-08-21-security-audit-round-2.md)（FS-07～FS-10 已修复）
  - 第三轮见 [2026-08-21 全仓安全审计报告（第三轮）](2026-08-21-security-audit-round-3.md)（FS-11～FS-16 已修复）
  - 第四轮见 [2026-08-22 全仓安全审计报告（第四轮）](2026-08-22-security-audit-round-4.md)（FS-17～FS-20 已修复）
- 方法论：Claude Marketplaces 插件 `cybersecurity-skills@anthropic-cybersecurity-skills` v1.3.0
  覆盖技能：`testing-cors-misconfiguration`、`testing-host-header-attack-vulnerabilities`、`performing-cryptographic-audit-of-application`、`conducting-api-security-testing`、`testing-for-broken-access-control`、`auditing-mcp-servers-for-tool-poisoning`、`performing-directory-traversal-testing`、`performing-ssrf-vulnerability-exploitation`、`testing-android-intents-for-vulnerabilities`
- 约束：本报告不含攻击载荷或 exploit PoC。折叠区内的「验证代码」均为防御性回归测试（断言安全属性），用于确认修复是否生效。
- 威胁模型：局域网邻机、同机恶意进程/网页、不可信 Android Intent、SFTP 中间人、已授权设备上的 token 盗用。本机文件分享服务按产品设计会对局域网可达。
- 本轮只登记相对前四轮**仍未修复的新问题**。已落地的 FS-01～FS-20、已排除项与产品明确文档化的同步行为不重复开洞。

## 审计发现总览

| ID | 严重度 | 类别 | 标题 | 状态 |
| --- | --- | --- | --- | --- |
| FS-21 | 中 | cors_misconfiguration / dns_rebinding / host_header | 设备 API 与链路分享 CORS 把 Origin 与客户端 `Host` 头比较，缺少 MCP 已有的广告地址白名单 | 已修复 |
| FS-22 | 中 | hardcoded_credential / cryptographic_weakness | WebRTC 默认 TURN 用户名与口令编译进客户端；空白配置回落到该共享凭据，浏览器 ICE 也直接使用 | 已修复 |
| FS-23 | 中 | broken_function_level_authorization | MCP `FilesShare` 不要求 `FilesRead`，可通过分享工具读取任意非敏感路径元数据并对其签发局域网分享 | 已修复 |
| FS-24 | 中 | secret_exfiltration / mitm | FS-20 残留：设备心跳探测与浏览器 WebRTC 发现仍可在 Capture Trust-All 上发送文件分享 access key | 已修复 |
| FS-25 | 高 | sensitive_file_read / confused_deputy | 桌面剪贴板文件列表与拖放未接入 `SensitiveFileAccessPolicy`，可将应用机密复制到分享目录或网盘 | 已修复 |
| FS-26 | 中 | insecure_temp_file / local_information_disclosure | 桌面剪贴板图片写入全局 `java.io.tmpdir`，目录与文件默认对同机其他用户可读 | 已修复 |
| FS-27 | 中 | credential_leakage / open_redirect | WebDAV Token 与自定义鉴权头跟随跨主机重定向；Ktor 默认只剥离 `Authorization` | 已修复 |

---

## FS-21：设备 API 与链路分享 CORS 信任客户端 `Host` 头，缺少广告地址白名单

* 严重度：中
* 类别：cors_misconfiguration / dns_rebinding / host_header
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/service/http/server/linkshare/LinkShareHttpHeaderPolicies.kt`（`isSameOrigin` 约 103–106 行；`corsHeaders` 约 108–121 行，含 `Access-Control-Allow-Credentials: true`）
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/server/LinkShareRawHttpServer.jvm.kt` 与 Android 对应实现（`hostAndPort` 从请求 `Host` 头解析 `request.host` / `request.port`，约 415–432 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/server/linkshare/LinkShareRouteDispatcher.kt`（`buildLinkSharePageConfig` 用同一 `host` 拼 `httpBaseUrl` / `httpsBaseUrl` / HTTPS consent key，约 1512–1526 行）
  - `core/src/serverRouteMain/kotlin/com/folderspan/routes/RawHttpDispatcherSupport.kt`（`isAllowedPrivateWebCorsOrigin` 约 217–223 行；`parseRequestHost` 约 253–257 行）
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/server/raw/RawTlsHttpServer.jvm.kt`（`parseRequest` 把客户端 `Host` 原样写入 `headers`；`start` 显式 `allowPlainHttpOnLan = true`，约 36–53、220–277 行）
  - 对照实现：`core/src/commonMain/kotlin/com/folderspan/service/mcp/http/McpHttpSecurityPolicy.kt`（Host 必须是 loopback 或 `allowedHostProvider` 广告地址；带 Origin 时 Origin 主机还必须是 loopback）
* 置信度：0.88

### 描述

第三轮 FS-13 修掉了两处过宽规则：任意 loopback Origin 无条件放行，以及「Origin 主机等于 Host 即放行（不比端口）」。落地后的策略是 **Origin 的主机与端口必须等于请求 Host 的主机与端口**。现有测试 `httpWebRtcPreflightAllowsSameHostAndPortOriginOnRawApi`、`corsAllowsOnlySameOriginRequests` 把这一点当作正向保证。

这条比较的右侧不是套接字本地地址，也不是当前广告的局域网 IP，而是**客户端自己带的 `Host` 头**：

- 链路分享：`hostAndPort()` 解析 `Host`，空白才回落到 `127.0.0.1` + 本地端口。解析结果成为 `LinkShareHttpRequest.host/port`，同时喂给 CORS 和页面配置。
- 设备 API：`isAllowedPrivateWebCorsOrigin(origin, request.header("host"))`。`parseRequestHost` 要求 Host **带端口**，否则拒绝；通过后只做大小写不敏感的主机比较和端口相等。
- 两边都不比较 scheme。设备 API CORS **不**设 `Access-Control-Allow-Credentials`，也不再发 PNA 头。链路分享成功时回显 Origin，并设 `Access-Control-Allow-Credentials: true`。Cookie 为 `HttpOnly` + `SameSite=Lax`，`Secure` 仅当 `request.scheme == "https"`。

MCP 在 FS-16 之后对同一类威胁有明确防护，文档也写了「Host 必须是 loopback，或在已打开局域网时为当前广告地址，防止 DNS rebinding」。`McpHttpSecurityPolicy.allows` 在鉴权前拒绝不在白名单里的 Host（例如 `attacker.example`），即使该名字最终解析到本机局域网地址。设备 API 与链路分享**没有**这层白名单。

产品局域网分享与设备 HTTP 仍默认 `allowPlainHttpOnLan = true`（这是文档化的产品行为，不是本条要重开的洞）。明文 HTTP 上，浏览器把某个主机名解析到受害者局域网地址后，会把该主机名同时放进 `Host` 与 `Origin`。此时「同主机同端口」为真，CORS 与 same-origin 都会通过。Chrome 对「公网页打私网」的 Private Network Access 有缓解，但对「页面本身就以该主机名:分享端口加载」无效。

链路分享还有两条加重因素：

1. 凭证随带：预检与正式响应都允许 credentials，session / client cookie 会随同站请求发出。
2. 页面配置注入：`buildLinkSharePageConfig` 用 `Host` 拼 `data-link-share-http-base-url` 等属性，后续前端 API 与 HTTPS 同意键都绑在这个主机名上。StreamSaver 的 `Service-Worker-Allowed: /` 也挂在同一源上。

本条**不是**「浏览器可随意伪造 Host 绕过 CORS」——常规跨站 XHR 不能改 Host。也**不是** FS-13 的重复：FS-13 修的是 loopback 无条件放行和不同端口；残留面是 **Host 本身可被 DNS rebinding 控制，且没有 MCP 那种广告地址校验**。

### 影响

- 链路分享：在用户打开（或 DNS 翻转到）绑定分享端口的攻击者主机名后，脚本可读回凭证随带的分享 API 响应；已批准会话的 cookie 会按该主机名作用域发送。不能单靠这一条伪造别人的 IP+UA 指纹，但可以在该源上完成浏览、下载，以及（若主人已授权上传）写入。
- 设备 API：无 credentials，不能直接读走 `Authorization` cookie。未认证或弱认证响应（例如带发现头的 `/ping` 回包、WebRTC 信令在 access key 关闭时的错误形态）仍可被同一源脚本读取。文件读写仍要 `requireAuth`。
- 与产品「局域网明文 HTTP 可达」叠加后，MCP 已单独堵住的 DNS rebinding 在设备/分享路径上仍然成立。

### 利用场景

用户在局域网开启文件分享或设备 HTTP。邻机诱使同一网段的浏览器访问一个解析到该设备地址、端口等于分享/设备端口的主机名。浏览器发出的 `Host` 与 `Origin` 都是该主机名加端口，CORS 放行。链路分享还会把页面里的 API 基址写成这个主机名，并允许带 cookie 的跨源（此时已是同站点）读取。

<details>
<summary>修复方案</summary>

1. **把 MCP 的 Host 白名单下沉到设备 API 与链路分享。** 允许的 Host 只能是 loopback，或当前 `selectAdvertisedHttpHost` / 网卡枚举得到的局域网地址（含括号包裹的 IPv6）。主机名不在集合内时，在 CORS 判定和路由分发之前拒绝，不要用客户端 `Host` 当「真实源」。
2. CORS 比较改为：Origin 主机+端口必须等于**套接字本地地址或广告地址**+实际监听端口，而不是请求头里的 Host。scheme 也要匹配当前连接（明文只接受 `http`，TLS 只接受 `https`）。
3. 链路分享 `buildLinkSharePageConfig` 的 `host` 改用广告地址或连接本地地址，禁止把未校验的 `Host` 头写进 `httpBaseUrl` / HTTPS consent key。
4. 链路分享若继续对局域网提供明文 HTTP，credentials CORS 的收益很小、风险更大：可改为只对已校验的广告源回显 Origin，或不对跨源预检设 `Allow-Credentials`（同站导航不需要这条头）。
5. 现有测试把「Origin 主机端口 == 请求 Host」当作正向保证，应改为「Origin 匹配广告地址，且任意非广告主机名即使端口相同也拒绝」。

```kotlin
private fun isAllowedPrivateWebCorsOrigin(
    origin: String,
    requestHost: String?,
    advertisedHosts: Set<String>,
    localPort: Int,
): Boolean {
    val parsed = RawHttpCorsOrigin.parse(origin) ?: return false
    if (parsed.scheme !in setOf("http", "https")) return false
    val host = normalizeHostHeader(parsed.host) ?: return false
    val allowed = advertisedHosts.mapNotNull(::normalizeHostHeader).toSet()
    if (!isLoopbackHost(host) && host !in allowed) return false
    if (parsed.port != localPort) return false
    val requestAuthority = parseRequestHost(requestHost) ?: return false
    return host.equals(requestAuthority.host, ignoreCase = true) &&
        parsed.port == requestAuthority.port &&
        (isLoopbackHost(requestAuthority.host) || requestAuthority.host in allowed)
}
```

链路分享 `isSameOrigin` 应对齐同一白名单，而不是只比较 `parsed.host == requestHost`。

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun deviceApiPreflightRejectsRebindingHostnameEvenWhenPortMatches() {
    val response = rawDispatcher.handleOptions(
        origin = "http://share.example:12040",
        host = "share.example:12040",
        advertisedHosts = setOf("192.168.1.20"),
        localPort = 12040,
    )
    assertEquals(403, response.statusCode)
    assertNull(response.header("Access-Control-Allow-Origin"))
}

@Test
fun deviceApiPreflightAllowsAdvertisedLanAddressSamePort() {
    val response = rawDispatcher.handleOptions(
        origin = "http://192.168.1.20:12040",
        host = "192.168.1.20:12040",
        advertisedHosts = setOf("192.168.1.20"),
        localPort = 12040,
    )
    assertEquals(204, response.statusCode)
    assertEquals("http://192.168.1.20:12040", response.header("Access-Control-Allow-Origin"))
}

@Test
fun linkShareCorsRejectsHostHeaderThatIsNotAdvertised() {
    val request = LinkShareHttpRequest.from(
        method = "OPTIONS",
        rawUri = "/api/share/upload",
        headers = linkShareHeadersOf(
            "Origin" to "http://share.example:1204",
            "Host" to "share.example:1204",
        ),
        scheme = "http",
        host = "share.example",
        port = 1204,
    )
    val rejected = LinkShareHttpHeaderPolicies.corsPreflightResponse(request)
    assertEquals(403, rejected?.statusCode)
}

@Test
fun linkSharePageConfigDoesNotUseUnlistedHostHeader() {
    val config = dispatcher.buildLinkSharePageConfigForTest(
        requestHost = "share.example",
        advertisedHost = "192.168.1.20",
        httpPort = 1204,
    )
    assertEquals("http://192.168.1.20:1204", config.httpBaseUrl)
    assertFalse(config.httpBaseUrl.contains("share.example"))
}
```

MCP 已有 `securityPolicyRejectsDnsRebindingAndNonLoopbackOriginsBeforeAuthentication`。设备 API / 链路分享修复后应有同等断言，且不得再把「Origin 等于任意 Host 头」当作通过条件。

</details>

---

## FS-22：WebRTC 默认 TURN 用户名与口令编译进客户端，空白配置回落该共享凭据

* 严重度：中
* 类别：hardcoded_credential / cryptographic_weakness
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/service/webrtc/models/WebRtcIceConfig.kt`（`DEFAULT_WEBRTC_ICE_HOST`、`DEFAULT_WEBRTC_TURN_USERNAME`、`DEFAULT_WEBRTC_TURN_PASSWORD`；`buildWebRtcIceServers` 对空白用户名/口令 `ifBlank` 回落到上述常量，约 3–45 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/webrtc/signaling/WebRtcSignalingTransport.kt`（`buildBrowserWebRtcConfig` 无参调用 `buildWebRtcIceServers()`，约 24–31 行）
  - `core/src/commonMain/kotlin/com/folderspan/data/main/webrtc/WebRtcRoom.kt`（房间配置把用户填写的 TURN 字段送进 `buildWebRtcIceServers`，空白同样回落默认）
  - `core/src/commonTest/kotlin/com/folderspan/service/webrtc/WebRtcIceConfigTest.kt`（`buildWebRtcIceServers fills default turn credentials when blank` 把填入默认口令当作正向保证）
* 置信度：0.95

### 描述

客户端把一套共享 TURN 参数编进二进制：主机 `119.91.209.238:3478`、用户名 `folderspan`、口令 `folderspan123`。`buildWebRtcIceServers` 的默认参数就是这些常量；`turnUsername.trim().ifBlank { DEFAULT_WEBRTC_TURN_USERNAME }` 与 `turnPassword.ifBlank { DEFAULT_WEBRTC_TURN_PASSWORD }` 使**用户留空或只填空格时，仍然发出这套凭据**。

两条产品路径都会用到：

1. **浏览器 WebRTC**：`buildBrowserWebRtcConfig` 不读房间设置，直接 `buildWebRtcIceServers()`，分享页 / 浏览器信令侧 ICE 列表含默认 TURN 用户名和口令。
2. **原生房间**：编辑页可以改 STUN/TURN URL 与账号，但空白口令不会变成「无 TURN」，而是静默换回编译期口令。`toDebugSummary()` 只把用户名打成 `<set>` / `<empty>`，不记录是否来自默认值。

现有测试明确断言：无参调用得到默认 STUN+TURN；TURN URL 有值但用户名/口令空白时，结果里的 username/password 等于那两个常量。这不是过期测试残留，而是当前意图。

前四轮未登记硬编码 TURN。FS-11 修的是 Pro HTTP 缓存 DEK，范围不同。TURN 口令出现在发行版与浏览器 ICE 配置里，任何持有客户端或打开过浏览器 WebRTC 页的人都能得到同一组中继凭据。

### 影响

- 共享 TURN 可被用于中继流量、消耗该主机带宽，或作为面向该 TURN 的通用 UDP/TCP 中继。
- 观察经过该 TURN 的 FolderSpan 会话，可看到对端候选地址（NAT 后的公网/内网映射），削弱「WebRTC 仅局域网发现」的隔离预期。
- 浏览器配置把口令交给网页 ICE 栈；同机扩展或被注入的脚本可读 `RTCPeerConnection` 使用的 ICE servers。
- 用户以为自己「没填 TURN 密码」即未启用中继，实际仍会连接官方默认 TURN。

### 利用场景

任意安装同一发行版的人从二进制或浏览器 ICE 配置中读出默认 TURN 账号，向 `119.91.209.238:3478` 申请中继分配。无需登录 FolderSpan 账号，也无需受害者再操作。若受害者使用浏览器 WebRTC 或未改默认 ICE 的房间，中继上可看到其连通性候选。

<details>
<summary>修复方案</summary>

1. **删除编译期共享口令。** 官方 TURN 若仍提供，应下发短时、按会话或按安装绑定的 REST 凭据（ coturn REST / 时间窗口 HMAC ），不要把长期密码写进客户端。
2. **空白凭据表示「不要带 TURN 用户名口令」**，而不是回落默认。没有用户名/口令的 TURN URL 走现有 `without-credentialless-turn` 回退即可。
3. `buildBrowserWebRtcConfig` 不得暗含共享口令：浏览器 ICE 只用 STUN，或向已鉴权的本机信令端点申请一次性 TURN 凭据。
4. 默认主机地址同样不应作为「所有安装共用的中继」。可保留公开 STUN，TURN 必须可轮换。
5. 作废当前口令并轮换服务端配置；已发布客户端在升级前仍会尝试旧口令，服务端应拒绝。
6. 删除或改写 `fills default turn credentials when blank`：空白必须得到「无 TURN 凭据」，而不是填入常量。

```kotlin
fun buildWebRtcIceServers(
    stunUrl: String = DEFAULT_WEBRTC_STUN_URL,
    turnUrl: String = "",
    turnUsername: String = "",
    turnPassword: String = "",
): List<WebRtcIceServerConfig> {
    val normalizedStunUrl = stunUrl.trim()
    val normalizedTurnUrl = turnUrl.trim()
    val normalizedTurnUsername = turnUsername.trim()
    val normalizedTurnPassword = turnPassword
    return buildList {
        if (normalizedStunUrl.isNotEmpty()) {
            add(WebRtcIceServerConfig(urls = listOf(normalizedStunUrl)))
        }
        if (normalizedTurnUrl.isNotEmpty()) {
            add(
                WebRtcIceServerConfig(
                    urls = listOf(normalizedTurnUrl),
                    username = normalizedTurnUsername,
                    password = normalizedTurnPassword,
                )
            )
        }
    }
}

fun buildBrowserWebRtcConfig(baseUrl: String): WebRtcConfig {
    return WebRtcConfig(
        wssUrl = baseUrl.trim(),
        roomId = BROWSER_WEBRTC_ROOM_ID,
        iceServers = buildWebRtcIceServers(turnUrl = ""),
        unreliableMode = false,
    )
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun blankTurnCredentialsDoNotFallBackToCompiledDefaults() {
    val servers = buildWebRtcIceServers(
        stunUrl = " ",
        turnUrl = "turn:203.0.113.10:3478",
        turnUsername = " ",
        turnPassword = "",
    )
    assertEquals(
        listOf(
            WebRtcIceServerConfig(
                urls = listOf("turn:203.0.113.10:3478"),
                username = "",
                password = "",
            )
        ),
        servers,
    )
    assertTrue(servers.none { server -> server.password.isNotEmpty() })
}

@Test
fun browserWebRtcConfigDoesNotEmbedSharedTurnPassword() {
    val config = buildBrowserWebRtcConfig("http://192.168.1.20:12040")
    assertTrue(
        config.iceServers.none { server -> server.password.isNotEmpty() },
        "browser ICE must not ship a compiled TURN password",
    )
}

@Test
fun compiledTurnPasswordConstantIsNotUsedAsRuntimeFallback() {
    val servers = buildWebRtcIceServers()
    assertTrue(
        servers
            .filter { server -> server.urls.any { url -> url.startsWith("turn:") } }
            .all { server -> server.username.isEmpty() && server.password.isEmpty() },
    )
}
```

修复后应删除「空白即填 `DEFAULT_WEBRTC_TURN_PASSWORD`」这条正向测试，避免回归把共享口令重新打开。

</details>

---

## FS-23：MCP `FilesShare` 不要求 `FilesRead` 即可读取任意非敏感路径元数据并签发局域网分享

* 严重度：中
* 类别：broken_function_level_authorization
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/service/mcp/tools/McpToolRegistry.kt`（`folderspan_files_share_link` / `share_device` 仅 `McpTokenScope.FilesShare`，约 278–295 行；`folderspan_favorites_add/remove` 已有 `extraScopes = FilesRead`，约 144–159 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/mcp/automation/McpAutomationFacade.kt`（`resolveShareFiles` 调用 `gateway.info(path)` 并组装 `FileSimpleInfo`，约 689–715 行；`shareLink` 随后 `issueLinkShareTicket` 并把 ticket 写进局域网 URL，约 642–667 行）
  - `app/shared/src/commonMain/kotlin/com/folderspan/ui/screen/mcp/McpManageScreen.kt`（`withImpliedMcpScopes` 只在勾选 `FavoritesWrite` 时补 `FilesRead`，约 440–446 行；`FilesShare` 与读写同属 Files 区，自定义 token 可只勾分享）
  - `core/src/jvmTest/kotlin/com/folderspan/service/mcp/auth/McpTokenRepositoryTest.kt`（`setScopes(FilesWrite, FilesShare)` 不强制 `FilesRead`，约 79–84 行）
  - 对照文档：`docs/networking/mcp-http-server.md` 把 `files.read / files.write / files.share` 写成一组能力
* 置信度：0.90

### 描述

第四轮 FS-19 修的是：`FavoritesWrite` 工具内部会 `gateway.info`，却不要求 `FilesRead`。落地方式是工具层 `extraScopes = FilesRead`，管理页勾选收藏写时自动带上文件读。

分享工具是同一模式，且更重：

1. `folderspan_files_share_link` / `folderspan_files_share_device` 的 `requiredScopes` 只有 `FilesShare`。
2. `McpFileSharingFacade.resolveShareFiles` 对每个 locator 做 `gateway.info(path)`，返回 name / size / mime / 时间戳，并拒绝符号链接。敏感路径仍走 `requireOrdinaryPath`，本条不重复 FS-18。
3. `shareLink` 在拿到元数据后启动分享服务、签发 ticket，把 `https://<lan>/?ticket=...` 与明文 `http://` 一并返回给 MCP 客户端。`shareDevice` 把同一批 `FileSimpleInfo` 写入 `shareToDevices` 并调用 `deviceState.share`。

Operator 预设不含 `FilesShare`；Administrator 含全部 scope。真正受影响的是**自定义最小权限 token**：管理页可以把 Files 区只勾「分享」，`withImpliedMcpScopes` 不会因此补上 `FilesRead`。仓库测试也允许 `FilesWrite + FilesShare` 不带 `FilesRead`。

本条不是 FS-19 的重复：FS-19 已修收藏写；分享工具没有跟进。也不是「局域网可分享」产品行为本身——产品允许已授权用户分享，但 MCP 把 `files.share` 文档成与 `files.read` 一组能力，实现却允许无读权限的 token 先读元数据再对外签发。

### 影响

- 持有仅 `FilesShare` 的 MCP token 可对任意非敏感绝对路径调用 `info`，得到与 `folderspan_file_info` 同级的元数据。
- `share_link` 还会把该路径放进局域网可达的分享会话（含 ticket URL）。邻机随后走链路分享，不需要再持有 MCP token。
- `share_device` 可把同一文件推给当前已连接设备。

### 利用场景

用户为自动化客户端签发了「只允许分享、不允许读文件」的自定义 token（例如只想让助手把用户已经选好的项发出去）。持有该 token 的客户端对任意本机非敏感路径调用 `folderspan_files_share_link`，既读到 size/mtime，也得到可在局域网打开的 ticket。

<details>
<summary>修复方案</summary>

1. **与 FS-19 对齐。** `folderspan_files_share_link` / `share_device` 增加 `extraScopes = setOf(McpTokenScope.FilesRead)`。
2. `withImpliedMcpScopes` 在勾选 `FilesShare` 时同样补上 `FilesRead`。
3. 仓库测试改为：只含 `FilesShare` 的 token 调用分享工具必须权限不足；`FilesRead + FilesShare` 才允许 `info` 与签发。
4. 文档已把三个 files scope 写成一组能力，实现必须与文档一致。

```kotlin
tool("folderspan_files_share_link", "...", McpTokenScope.FilesShare, shareLinkSchema(), extraScopes = setOf(McpTokenScope.FilesRead)) { args ->
    json(fileFacade.shareLink(args.locators("locators"), args.boolean("allowHidden") ?: false, args.boolean("allowUpload") ?: false))
}

internal fun withImpliedMcpScopes(scopes: Set<McpTokenScope>): Set<McpTokenScope> {
    var implied = scopes
    if (McpTokenScope.FavoritesWrite in implied) implied = implied + McpTokenScope.FilesRead
    if (McpTokenScope.FilesShare in implied) implied = implied + McpTokenScope.FilesRead
    return implied
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun shareLinkWithShareOnlyTokenDoesNotReturnMetadataOrTicket() = runTest {
    val result = callTool(
        name = "folderspan_files_share_link",
        scopes = setOf(McpTokenScope.FilesShare),
        arguments = shareLinkArguments("/tmp/notes.txt"),
    )
    assertEquals("permission_denied", result.errorCode)
    assertNull(result.resultObject)
}

@Test
fun withImpliedMcpScopesAddsFilesReadWhenSharing() {
    val implied = withImpliedMcpScopes(setOf(McpTokenScope.FilesShare))
    assertTrue(McpTokenScope.FilesRead in implied)
    assertTrue(McpTokenScope.FilesShare in implied)
}
```

落地时优先在 tool dispatcher 层用只含 `FilesShare` 的 token 打分享工具，断言 MCP 错误码为权限不足，且响应不含 path / size / ticket。

</details>

---

## FS-24：FS-20 残留——设备心跳探测与浏览器 WebRTC 发现仍可在 Capture Trust-All 上发送文件分享 access key

* 严重度：中
* 类别：secret_exfiltration / mitm
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/main/SocketDevicePresence.kt`（`probeAlive`：无内存指纹时回落传入的 `client`，仍 `applyFileShareAccessKey()`，约 47–70 行）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/main/DeviceState.kt`（`client = createDiscoveryNoProxyHttpClient(LeafCertificateCapture())`，约 160 行；`presenceMonitor` 把该客户端借给心跳，约 383–394 行；`pingDevice` 已不再带 access key，约 738–756 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/webrtc/signaling/HttpWebRtcDiscoveryClient.kt`（`discoverHost` 对传入客户端 `applyFileShareAccessKey()`，约 16–21 行）
  - `core/src/serverRouteMain/kotlin/com/folderspan/routes/RawHttpDeviceRoutes.kt`（`handlePing` 把对端 protobuf 设备写入 `socketDevices`，对端自报指纹可为空，约 54–76、427–441 行；响应里会把本机指纹清掉，约 78 行）
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/client/HttpClientFactory.jvm.kt`（`FingerprintTrustManager`：`capture != null` 时只记录、不校验，约 87–95 行）
* 置信度：0.88

### 描述

第四轮 FS-20 修的是**产品 LAN `scanner()` / `pingDevice`**：发现 ping 不再调用 `applyFileShareAccessKey()`，Capture Trust-All 只用于 TOFU 采指纹。第五轮排除表把这一点记成「已改为 ping 不发 access key」。扫描路径确实已对齐。

两条相邻路径没有跟进：

1. **心跳 `probeAlive`。** `DevicePresenceMonitor` 每 10 秒对 `socketDevices` 里的 HTTP 设备调用 `probeAlive(client)`。有 `tlsFingerprintSha256` 时会新建 pinned 客户端；**指纹空白时直接使用传入的发现客户端**（Capture Trust-All）。无论哪一种，请求都 `applyFileShareAccessKey()`。入站 `/ping` 会把对端 protobuf 设备 `insertUnknownDeviceIfNeeded` 进列表；对端可以不带指纹。此后心跳就会在 Trust-All 的 HTTPS `/ping` 上发送 access key。
2. **浏览器 WebRTC 发现。** JS 扫描走 `discoverBrowserWebRtcDevice` → `HttpWebRtcDiscoveryClient.discoverHost`。该客户端就是同一个 Capture 发现客户端，GET `/api/webrtc/signaling/discover` 仍带 access key。

本条不是再开一次「扫描 ping 带密钥」：`pingDevice` 已修。残留面是 **心跳与 WebRTC 发现这两条仍把 access key 交给未钉扎（或可空指纹）的对端。** Capture Trust-All 本身按 FS-20 仍允许用于 TOFU，问题是密钥不得走同一请求。

### 影响

- 用户开启文件分享访问密钥后，邻机只要让受害者列表里出现一台无指纹（或指纹可被入站 ping 写入）的 HTTP 设备，即可从周期性心跳读出该密钥。
- 浏览器扫描路径上，对网段内每个探测地址的 WebRTC discover 也会带密钥。
- 密钥随后可用于调用本机文件分享 / 设备 API（与 FS-15、FS-20 记载的用途相同）。

### 利用场景

用户打开 access key。邻机向受害者发一条发现 ping（或不带指纹的设备记录进入 `socketDevices`）。受害者 `DevicePresenceMonitor` 随后用 Trust-All 客户端向该地址 `/ping`，请求头含 access key。邻机用自签证书完成 TLS 并保存密钥。

<details>
<summary>修复方案</summary>

1. **`probeAlive` 与 `discoverHost` 对齐 `pingDevice`：不要调用 `applyFileShareAccessKey()`。** access key 只走后续已钉扎的业务请求。
2. 无指纹时不要回落 Capture 客户端发密钥；没有可钉扎指纹就跳过 HTTPS 探测，或只做明文/无密钥的存活检查。
3. 入站 ping 写入 `socketDevices` 时，不要把空指纹设备当成可探测的 HTTPS 目标。
4. 现有 FS-20 测试只覆盖 `pingDevice`；补心跳与 WebRTC discover 两条。

```kotlin
internal suspend fun SocketDevice.probeAlive(client: HttpClient): Boolean {
    val fingerprint = normalizedTlsFingerprint()
        .takeIf { item -> item.isNotBlank() && requestProtocol == URLProtocol.HTTPS }
        ?: return false
    val probeClient = createPinnedNoProxyHttpClient(fingerprint) { /* timeouts */ }
    return try {
        val response = probeClient.post {
            // 不调用 applyFileShareAccessKey()
            header(DISCOVERY_PING_HEADER, "true")
            // ...
        }
        response.status.isSuccess()
    } finally {
        runCatching { probeClient.close() }
    }
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun heartbeatProbeDoesNotSendAccessKeyOnTrustAllFallback() = runTest {
    val captured = mutableListOf<Headers>()
    val device = SocketDevice(id = "peer", tlsFingerprintSha256 = "")
    probeAliveForTest(device, recordingClient(captured), accessKeyEnabled = true)
    assertTrue(
        captured.none { headers -> headers.contains(FILE_SHARE_ACCESS_KEY_HEADER) },
        "unpinned heartbeat must not leak the file-share access key",
    )
}

@Test
fun webRtcDiscoverDoesNotSendAccessKeyHeader() = runTest {
    val captured = mutableListOf<Headers>()
    HttpWebRtcDiscoveryClient(recordingClient(captured)).discoverHost("https://10.0.0.23:12040")
    assertTrue(captured.none { headers -> headers.contains(FILE_SHARE_ACCESS_KEY_HEADER) })
}
```

</details>

---

## FS-25：桌面剪贴板文件列表与拖放未接入敏感路径策略，可将应用机密复制到分享目录或网盘

* 严重度：高
* 类别：sensitive_file_read / confused_deputy
* 修复状态：已修复
* 位置：
  - `core/src/jvmMain/kotlin/com/folderspan/utils/DesktopExternalFilePreparation.kt`（`prepareDesktopExternalFiles` 用 `FileAccessPermission.Allowed` 直接 `getFile(absolutePath)`，约 10–30 行）
  - `app/shared/src/jvmMain/kotlin/com/folderspan/clipboard/ClipboardFilePasteController.jvm.kt`（`javaFileListFlavor` 走上述准备函数，约 40–46 行）
  - `core/src/commonMain/kotlin/com/folderspan/utils/SensitiveFileAccessPolicy.kt`（`permissionForExternalPath` 无调用方，约 57–58 行）
  - `core/src/commonMain/kotlin/com/folderspan/data/file/FileInfo.kt`（本地 `copyTo` / `writeToFile` 只拒 symlink，不查敏感路径，约 153–216 行）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/file/FilePasteTaskExecutor.kt`（`pasteExternalFiles` 把准备好的源复制到当前 desk，约 69–91 行）
* 置信度：0.92

### 描述

设备 RPC、MCP 与 FileProvider 已用 `SensitiveFileAccessPolicy.deniedException()` 挡住 `tls-identity`、`folderspan.db` 和应用私有数据。本轮新的桌面外部导入没有接上。

`Ctrl+V` 若剪贴板是 `DataFlavor.javaFileListFlavor`，以及桌面拖放，都调用 `prepareDesktopExternalFiles`：把 `File.absolutePath` 标成 `Allowed` 后交给 `FileUtils.getFile`。`Allowed` 只表示这次调用被放行，**不会**做敏感分类。`permissionForExternalPath()` 是死代码。随后 `pasteExternalFiles` 按当前目录复制；当前目录可以是链路分享根或已连接网盘。

文本里的 `file://` / 绝对路径只用于抽屉「从剪贴板打开」，不进入这条复制链。投毒载体是**文件列表 flavor**，不是纯文本。同机恶意进程或网页可以往系统剪贴板写入该 flavor。复制本身拒绝 symlink，不能靠链接绕过；问题是**真实敏感文件路径被当成普通本地源**。

第五轮初稿把「剪贴板 `getFile(Allowed)` 不做敏感分类」排除为「已授权用户在本机 UI 上的操作」。这低估了 confused deputy：用户以为自己在粘贴一张普通文件，实际源可以是应用数据目录，目标可以是局域网分享。威胁模型含同机恶意进程；本机文件分享按产品设计对局域网可达。

### 影响

- 用户在分享目录或 SFTP/WebDAV 上粘贴/拖放后，`tls-identity`、`folderspan.db` 以及策略覆盖的运行态/缓存会被复制到局域网可达位置或远端。
- 不需要 Intent，不需要跟随 symlink，也不需要 MCP token。

### 利用场景

同机进程把应用数据目录下的真实文件放入剪贴板文件列表。用户在 FolderSpan 分享视图或网盘视图按 Ctrl+V（或拖放同一组文件）。文件被复制进当前分享根，邻机即可通过已打开的分享服务读取。

<details>
<summary>修复方案</summary>

1. **在准备批次时按原始源路径拒绝受保护文件。** 不要对 Android/iOS 暂存副本做同样检查（暂存在 `cacheDir`，整棵 cache 在 Android 上被标为 Sensitive，会误杀全部粘贴）。
2. 建议同步在本地 `copyTo` / `writeToFile` 对 Local 源再做一次 `deniedException(path)`，作为设备复制已有门禁的本地对等层。
3. 拖拽与剪贴板共用 `prepareDesktopExternalFiles`，修一处即可覆盖两入口。

```kotlin
fun prepareDesktopExternalFiles(files: List<File>): PreparedExternalFileBatch {
    val prepared = mutableListOf<FileSimpleInfo>()
    val skipped = mutableListOf<ExternalFileSkip>()
    files.forEach { file ->
        val absolutePath = file.absolutePath
        if (SensitiveFileAccessPolicy.deniedException(absolutePath) != null) {
            skipped += ExternalFileSkip(ExternalFileSkipReason.Unsupported, file.name)
            return@forEach
        }
        if (PathUtils.isSymbolicLink(FileAccessPermission.Allowed, absolutePath)) {
            skipped += ExternalFileSkip(ExternalFileSkipReason.Unsupported, file.name)
            return@forEach
        }
        val source = FileUtils.getFile(FileAccessPermission.Allowed, absolutePath)
            .getOrNull()
            ?.withCopy(protocol = FileProtocol.Local, protocolId = "")
        if (source == null) {
            skipped += ExternalFileSkip(ExternalFileSkipReason.Unreadable, file.name)
        } else {
            prepared += source
        }
    }
    return buildPreparedExternalFileBatch(files = prepared, skipped = skipped, representedItemCount = files.size)
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun clipboardPreparationRejectsApplicationPrivateDataWithoutReadingIt() {
    val appData = resolveDesktopApplicationDataDirectory()
    val db = appData.resolve("folderspan.db").toFile()
    val identityDir = appData.resolve("tls-identity").toFile()
    val ordinary = kotlin.io.path.createTempFile("folderspan-paste-ok", ".txt").toFile()
    ordinary.writeText("ok")
    try {
        val batch = prepareDesktopExternalFiles(listOf(db, identityDir, ordinary))
        assertTrue(batch.files.none { SensitiveFileAccessPolicy.classify(it.path).isProtected })
        assertTrue(batch.files.all { it.path == ordinary.absolutePath })
        assertEquals(setOf(ExternalFileSkipReason.Unsupported), batch.skipped.map { it.reason }.toSet())
    } finally {
        ordinary.delete()
    }
}
```

测试不得读取真实 `folderspan.db` 或 TLS 身份内容；只断言准备阶段跳过这些路径。

</details>

---

## FS-26：桌面剪贴板图片写入全局临时目录，默认权限对同机其他用户可读

* 严重度：中
* 类别：insecure_temp_file / local_information_disclosure
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/file/ExternalFileImportModels.kt`（`externalFileStagingRootPath` = `getCachePath()/clipboard-file-import/{leaseId}`，约 146–152 行）
  - `core/src/jvmMain/kotlin/com/folderspan/utils/PathUtils.jvm.kt`（`getCachePath() = java.io.tmpdir`，约 138 行）
  - `app/shared/src/jvmMain/kotlin/com/folderspan/clipboard/ClipboardFilePasteController.jvm.kt`（`mkdirs()` + `ImageIO.write`，未收紧权限，约 56–71 行）
* 置信度：0.78

### 描述

图片粘贴会把 PNG 写到 `{tmpdir}/clipboard-file-import/{leaseId}/clipboard-image-*.png`。Linux 上 `java.io.tmpdir` 一般为 `/tmp`（sticky 1777）。`File.mkdirs()` 与 `ImageIO.write` 使用 umask 默认权限（常见 755/644），**其他用户可读**。Lease 在任务结束后才删。

Android `cacheDir`、iOS 容器临时目录是应用私有，本条主要打桌面。威胁模型含同机恶意进程；截图或证件照会在复制完成前落在世界可读位置。

### 影响

多用户桌面或同机其他账户可在粘贴窗口内读取剪贴板图片明文。

### 利用场景

用户向分享目录粘贴截图。另一本地用户轮询 `/tmp/clipboard-file-import/`，在 lease 释放前读走 PNG。

<details>
<summary>修复方案</summary>

1. 创建暂存目录和图片文件后设为 owner-only（`0700` / `0600`）。
2. 更稳妥：把桌面暂存改到 `resolveDesktopApplicationDataDirectory()/cache/clipboard-file-import`（该树已被标为 Critical）。

```kotlin
internal fun ensurePrivateStagingDirectory(root: File): Boolean {
    if (!(root.mkdirs() || root.isDirectory)) return false
    runCatching {
        Files.setPosixFilePermissions(
            root.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
        )
    }
    return true
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun desktopClipboardImageStagingDirectoryIsOwnerOnly() {
    val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    val batch = prepareDesktopClipboardImage(image, epochMillis = 1L)
    val root = File(requireNotNull(batch.lease?.rootPath))
    try {
        val perms = Files.getPosixFilePermissions(root.toPath())
        assertTrue(perms.none { it.name.startsWith("OTHERS_") || it.name.startsWith("GROUP_") })
        val filePerms = Files.getPosixFilePermissions(File(batch.files.single().path).toPath())
        assertTrue(filePerms.none { it.name.startsWith("OTHERS_") || it.name.startsWith("GROUP_") })
    } finally {
        batch.lease?.id?.let(ExternalFileResourceLeaseRegistry::releaseProducer)
    }
}
```

非 POSIX 平台（Windows）可跳过权限断言，但仍应把暂存放到应用数据目录而不是共享临时目录。

</details>

---

## FS-27：WebDAV Token 与自定义鉴权头跟随跨主机重定向

* 严重度：中
* 类别：credential_leakage / open_redirect
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/service/network/WebDavNetworkClient.kt`（`applyWebDavHeaders` 写入 Token 头名或 `extras.headers`，约 387–403 行；`createNoProxyHttpClient` 未关闭跟随重定向，约 35–63 行）
  - Ktor 3.5.2 默认 `HttpRedirect`：跨权威重定向只剥离 `Authorization`，自定义头名会跟过去
* 置信度：0.82

### 描述

WebDAV 客户端把用户配置的 Token 或自定义头直接写进每条请求。鉴权类型为 Token 时，头名来自 `extras.tokenHeaderName`（空白才回落 `Authorization`）。`extras.headers` 里的任意头（含非标准鉴权头）同样附加。

客户端使用 `createNoProxyHttpClient`，没有关闭或限制 `HttpRedirect`。Ktor 对跨主机 302/307 默认只去掉 `Authorization`。因此：

- Token 若使用 `X-Auth-Token`、`Token`、`Private-Token` 等自定义头名，会带到 `Location` 指定的另一主机。
- 即使用户把 Token 写进 `Authorization`，`extras.headers` 里的第二套密钥仍会跟过去。
- Basic/Digest 走 Ktor Auth 插件时，`Authorization` 会被剥离；Token 模式显式关闭了 Auth 插件，改走自定义头，正好踩中这条差异。

用户填写的 WebDAV 基址是可信的，但 **302 的目标主机不是用户批准的权威**。威胁模型含不可信网络盘 / 中间人把列表或下载响应改成跨站 Location。

### 影响

配置了 Token 或自定义鉴权头的 WebDAV 网盘，一旦对端（或路径上的代理）返回跨主机重定向，密钥会随请求发到第三方。

### 利用场景

用户添加了一台 WebDAV。该服务或中间人对 `PROPFIND` / `GET` 返回 `302 Location: https://collector.example/...`。客户端跟随并把 `X-Auth-Token` 带到 collector。

<details>
<summary>修复方案</summary>

1. **跨权威重定向一律剥离鉴权类头**：`Authorization`、Token 头名、以及 `extras.headers` 里用户标记为鉴权的键。更稳妥是 WebDAV 客户端 `followRedirects = false`，只允许同主机、同 scheme 的相对重定向并手动重放（重放时按新 URL 重新套用头）。
2. 禁止跟随 http→https 以外的 scheme 切换，以及用户信息（userinfo）变化。
3. 对 Token 模式默认头名若不是 `Authorization`，必须与 `Authorization` 同等对待。

```kotlin
val client = overrideClient ?: createNoProxyHttpClient {
    expectSuccess = false
    followRedirects = false
    // Basic/Digest 安装 Auth 的现有逻辑保持不变
}

private suspend fun requestFollowingSameOriginRedirects(builder: HttpRequestBuilder.() -> Unit): HttpResponse {
    var current = client.request(builder)
    repeat(5) {
        if (current.status.value !in 300..399) return current
        val location = current.headers[HttpHeaders.Location] ?: return current
        val next = Url(location)
        val origin = baseUrlParsed ?: return current
        if (!sameWebDavAuthority(origin, next)) return current
        current = client.request {
            method = current.call.request.method
            url(next)
            applyWebDavHeaders(this)
        }
    }
    return current
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun webDavTokenHeaderIsNotForwardedAcrossHosts() = runTest {
    val captured = mutableListOf<Pair<String, Headers>>()
    val client = redirectingClient(
        firstLocation = "https://collector.example/steal",
        captured = captured,
    )
    val network = testWebDavNetwork(
        authType = WebDavAuthType.Token,
        tokenHeaderName = "X-Auth-Token",
        token = "user-token",
    )
    WebDavNetworkClient(network, client).list("/")
    assertTrue(captured.none { (host, headers) ->
        host == "collector.example" && headers["X-Auth-Token"] == "user-token"
    })
}
```

测试只断言自定义鉴权头不得出现在第二跳；不要在夹具里放真实口令。

</details>

---

## 已检查、不作为本轮新漏洞

| 主题 | 结论 |
| --- | --- |
| FS-01～FS-20 | 源码与测试表明已落地或（FS-06）为误报，不重复开洞。本轮 FS-24 只覆盖 FS-20 **未改到的心跳 / WebRTC 发现**，不重开扫描 ping。 |
| CORS Origin 主机=Host 未比端口 / loopback 无条件放行 | FS-13 已改为同主机同端口。本轮 FS-21 只覆盖 **Host 可被 DNS rebinding 控制、无广告地址白名单** 这一残留。 |
| 局域网 MCP 明文 HTTP | FS-16 已改为 LAN 只广告 / 接受 HTTPS。MCP Host 白名单仍在。 |
| 链路分享 / 设备 API 局域网明文 HTTP | 产品设计，第四轮已记录。本轮不因「LAN 可达」本身再开洞，只在 FS-21 中作为 CORS/rebind 的叠加条件。 |
| 链路分享 client cookie 重签发 | FS-17 已删除授权表按 clientId 回退。 |
| 发现扫描 `pingDevice` 带 access key | FS-20 已改为 ping 不发 access key；Capture Trust-All 仍仅用于 TOFU 采指纹。心跳与 WebRTC discover 的残留见 FS-24。 |
| MCP `FavoritesWrite` 缺 `FilesRead` | FS-19 已给收藏写加上 `extraScopes = FilesRead`。分享工具未跟进，见 FS-23。 |
| Darwin `/var` 别名 | FS-18 已做 canonical 二次检查。 |
| `keystore.properties` | `.gitignore` 忽略且未跟踪，权限 600，不是入库密钥。 |
| TLS 身份 `storage.salt` 与密文同目录、SHA-256 包装 | 文档化设计（见敏感清单）；盐为 32 字节随机、文件 owner-only；桌面还有 `secure-settings` 金库。同机已能读目录则明文身份也可被替换。不新开 ID。 |
| 普通 UNC `\\server\share` | `normalizeEndpointPath` 因以 `\` 开头当作绝对路径；verbatim `\\?\` 等已拒。`requireOrdinaryPath` 仍做敏感分类 + canonical。远程 SMB 是否可达取决于 OS 凭据，未形成可复现的敏感路径绕过。 |
| WebRTC 入站写路径 | `prepareStreamWrite` 在注册 `IncomingFileTarget` 之前执行敏感路径与权限检查；无 target 不落盘。 |
| 剪贴板文本 `file://` / 绝对路径 | 只用于抽屉「从剪贴板打开」，不进入 `pasteExternalFiles`。文件列表 flavor 的敏感路径缺口见 FS-25。 |
| 剪贴板跟随 symlink | 本地 `copyTo` / `writeToFile`、目录遍历与 `FileUtils` 均 `NOFOLLOW`。不能靠链接读出 tls-identity。 |
| Android 剪贴板 `content://` grant | 走系统 ClipData 临时读权限，与不可信 Intent 注入不同。 |
| Web 跨源剪贴板 | 只消费 `clipboardData.files` / 图片；暂存在 `WebInMemoryFileStore`。 |
| MCP `network_connect` | 只连接已配置网盘，不是任意 URL SSRF。 |
| zip-slip / 路径穿越归档 | `FolderSpanArchiveCodec.normalizeRelativePath` 拒绝 `\`、绝对路径、`.` / `..`。 |
| 密码爆破 | 链路分享 `passwordAttemptLimiter` 有失败登记与 lockout。 |
| StreamSaver `Service-Worker-Allowed: /` | 作用域限于分享源；单独不构成新洞。与 FS-21 的同源于叠加时作为加重因素，不另开 ID。 |
| JWT | 仓库无 JWT 实现。 |

## 修复优先级建议

1. **FS-25**：桌面粘贴/拖放是新入口，已声明保护的应用机密在这条链上完全未检查，且目标可以是局域网分享。准备函数加敏感拒绝即可覆盖剪贴板与拖放。
2. **FS-21**：与 MCP 已有的 DNS rebinding 防护对齐，改动集中在 CORS / Host 校验与页面基址，不改变「局域网可分享」的产品行为。
3. **FS-24**：与 FS-20 同类，心跳和无指纹回落、WebRTC discover 停止发送 access key。
4. **FS-23**：与 FS-19 同类，分享工具补 `FilesRead`，管理页勾选分享时带上文件读。
5. **FS-22**：先停用编译期 TURN 口令并停止空白回落，再视需要改为短时凭据；已发布客户端升级前服务端应拒绝旧口令。
6. **FS-27**：WebDAV 客户端禁止跨权威重定向携带 Token/自定义鉴权头。
7. **FS-26**：桌面图片暂存改 owner-only 或迁到应用数据目录。

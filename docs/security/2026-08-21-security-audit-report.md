# FolderSpan 安全审计报告

- 审计日期：2026-08-21
- 审计范围：全仓（`app/`、`core/`、`proMain/`），非仅当前 PR diff
- 方法论：Claude Marketplaces 插件 `cybersecurity-skills@anthropic-cybersecurity-skills` v1.3.0
  覆盖技能：`conducting-api-security-testing`、`testing-api-authentication-weaknesses`、`testing-cors-misconfiguration`、`performing-directory-traversal-testing`、`performing-cryptographic-audit-of-application`、`auditing-mcp-servers-for-tool-poisoning`、`testing-android-intents-for-vulnerabilities`、`testing-for-xss-vulnerabilities`
- 约束：本报告不含攻击载荷或 exploit PoC。折叠区内的「验证代码」均为防御性回归测试（断言安全属性），用于确认修复是否生效。
- 威胁模型：局域网邻机、同机恶意进程/网页、不可信 Android Intent、SFTP 路径上的网络中间人。本机文件分享服务按产品设计会对局域网可达（CORS / 发现 ping / WebRTC 信令均按该模型编写）。

## 近期已修复（不作为新漏洞）

以下问题在 `develop` 近期提交中已处理，审计时已排除，避免重复开洞：

| 提交 | 内容 |
| --- | --- |
| `913a0eabaf` | Pro 请求 HMAC 改用登录 Bearer，删除共享 `desktop-secret` |
| `c6e137886d` | 网盘口令改配置目标级 DEK；桌面/iOS 敏感设置进金库；Android 生产不信任用户 CA、禁止备份 |
| `6363517806` | 从版本库移除已跟踪的 `keystore.p12` |
| `42a27f24c2` | MCP 默认只绑 loopback；按真实路径拒绝敏感文件与 `/proc` `/sys` `/dev` |
| `430a87f454` | 设备 API CORS 去掉通配与默认 PNA 全放行 |
| `ff3672b368` | 发现 ping 按握手叶子证书钉扎，不再信任自报指纹 |

归档解压 `FolderSpanArchiveCodec.normalizeRelativePath` 拒绝 `..`、绝对路径与反斜杠；链接分享 HTML 走 kotlinx.html 自动转义。这两处当前实现未发现可利用的 zip-slip / 反射 XSS。

---

## 审计发现总览

| ID | 严重度 | 类别 | 标题 | 状态 |
| --- | --- | --- | --- | --- |
| FS-01 | 高 | authentication_bypass / data_tampering | 未认证 `/ping` 可改写已保存设备地址 | ✅ 已修复 |
| FS-02 | 高 | cryptographic_weakness | SFTP 无 known_hosts 时接受任意主机密钥 | ✅ 已修复 |
| FS-03 | 高 | authentication_bypass | WebRTC 信令 discover/join 无设备鉴权 | ✅ 已修复 |
| FS-04 | 中 | cors_misconfiguration | 设备 API CORS 回显任意私网 Origin（含 169.254）并允许 PNA | ✅ 已修复 |
| FS-05 | 中 | cryptographic_weakness | Desktop TLS 身份文件使用 AES-CBC 且无完整性保护 | ✅ 已修复 |
| FS-06 | 不适用 | not_actionable | Android `ACTION_VIEW` 任意 MIME 展示入口（重新分诊为误报） | ⛔ 误报 |

---

## FS-01：未认证 `/ping` 可改写已保存设备地址

* 严重度：高
* 类别：authentication_bypass / data_tampering
* 修复状态：✅ 已修复（2026-08-21）
* 位置：`core/src/serverRouteMain/kotlin/com/folderspan/routes/RawHttpDeviceRoutes.kt`（`handlePing` 约 43–85 行，`insertUnknownDeviceIfNeeded` 约 372–416 行）
* 置信度：0.90

### 修复落地

- 未认证发现 ping 调用持久化逻辑时固定使用 `allowExistingUpdates=false`，已有设备的 host、端口、类型和名称均不会被改写。
- 未知设备仍可首次写入，受信任的其他调用路径继续使用默认的更新能力，不影响正常设备刷新。
- 拒绝空设备 ID 和超过 256 字符的设备 ID。
- `RawHttpDiscoveryPersistenceTest` 覆盖“未认证 ping 不改已有端点”“仍可插入未知设备”“受信任路径可更新已有端点”。

### 描述

`/ping` 只需自定义头 `DISCOVERY_PING_HEADER=true` 和 protobuf 设备体，**不校验 Bearer、不校验 TLS 身份、不校验账号设备证明**。处理逻辑会：

1. 把对端写入/更新发现列表；
2. 若数据库已有相同 `device.id`，且 `host` / `httpsPort` / `type` 不同，直接 `updateEndpointById`。

`resolveDiscoveredHttpDeviceHost()` 在 `remoteHost` 非回环时优先采用 TCP 源地址。局域网内任意主机都可以用**受害者已保存设备的 id** 发 ping，把该设备记录的连接地址改成攻击者地址。

自动连接路径会核对已保存 TLS 指纹，指纹不匹配会拒绝；但人工连接、书签、历史主机列表仍会指向被污染的地址，造成钓鱼式连接或中间人入口。

### 影响

- 已配对设备的连接目标被劫持。
- 设备列表被垃圾设备刷屏。
- 与「发现 ping 钉扎叶子证书」的修复正交：钉扎发生在后续握手，污染发生在握手之前的地址簿。

### 利用场景

局域网邻机（或本机其他进程）向文件分享端口 `POST /ping`，带发现头和伪造/盗用的 `SocketDevice.id`。无需口令、无需 access key（access key 默认关闭）。

<details>
<summary>原修复方案（已落地）</summary>

1. **发现 ping 不得改写已有设备的 `host`/`port`。** 仅允许插入未知设备，或只更新「最后可见时间」。
2. 已存在记录的地址变更必须走已认证通道（Bearer 或账号设备证明），或要求用户确认。
3. `/ping` 响应可以继续公开本机广告信息（这是发现协议），但写入侧要区分「看见」和「信任」。
4. 建议对 ping 做速率限制，并拒绝明显伪造的 id（空、过长、与 TLS 证书 CN/指纹无绑定）。

推荐补丁方向（示意，非完整补丁）：

```kotlin
if (deviceData == null) {
    database.deviceQueries.insert(/* 仅首次发现 */)
} else {
    // 不在未认证 ping 中更新 host/port/type
    database.deviceQueries.touchLastSeen(device.id)
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun unauthenticatedPingMustNotRewriteExistingDeviceEndpoint() = runBlocking {
    val existingId = "paired-device-id"
    database.deviceQueries.insert(
        id = existingId,
        name = "Phone",
        host = "192.168.1.20",
        port = 12040L,
        type = DeviceType.Android,
    )

    val ping = RawHttpRequest(
        method = "POST",
        path = "/ping",
        headers = mapOf(DISCOVERY_PING_HEADER to "true"),
        body = ProtoBuf.encodeToByteArray(
            SocketDevice.serializer(),
            SocketDevice(
                id = existingId,
                name = "Phone",
                pathSeparator = "/",
                host = "10.0.0.9",
                type = DeviceType.Android,
                httpsPort = 12040,
            ),
        ),
        remoteHost = "10.0.0.9",
    )
    dispatcher.dispatch(ping)

    val stored = database.deviceQueries.queryById(existingId).executeAsOne()
    assertEquals("192.168.1.20", stored.host)
    assertEquals(12040L, stored.port)
}
```

</details>

---

## FS-02：SFTP 无 known_hosts 时接受任意主机密钥

* 严重度：高
* 类别：cryptographic_weakness / mitm
* 修复状态：✅ 已修复（2026-08-21）
* 位置：
  - `core/src/jvmMain/kotlin/com/folderspan/service/network/SftpNetworkClient.kt`（约 51–61 行）
  - `core/src/androidMain/kotlin/com/folderspan/service/network/SftpNetworkClient.kt`（同样逻辑）
* 置信度：0.95

### 修复落地

- JVM 与 Android 在 `known_hosts` 为空时使用 `RejectAllServerKeyVerifier`，解析失败时直接报配置错误。
- 有配置时必须同时匹配目标 host/port 和服务器公钥；没有匹配条目时拒绝连接，不再接受任意密钥。
- iOS 在缺少 `known_hosts` 时同样拒绝建立 SFTP 连接。
- `SftpServerKeyVerifierTest` 覆盖缺失配置、无效配置、主机不匹配、密钥不匹配和合法匹配。

### 描述

Apache MINA SSHD 客户端在以下两种情况都会设置 `AcceptAllServerKeyVerifier.INSTANCE`：

1. 用户未配置 `known_hosts`；
2. 配置了 `known_hosts` 但解析结果为空（解析失败同样 fallback 到接受全部）。

这会让 SFTP 连接无法检测主机密钥变化，网络路径上的中间人可以冒充服务器，窃取口令或私钥认证通过后的文件内容。

### 影响

- 凭据与文件内容可被中间人截获。
- `known_hosts` 配错/解析失败时静默降级，用户以为已启用校验。

### 利用场景

用户通过不可信网络（公共 Wi-Fi、被劫持的网关、恶意 DNS）连接自己的 SFTP 服务器，且未提供有效 known_hosts。攻击者展示任意主机密钥即可完成 SSH 握手。

<details>
<summary>原修复方案（已落地）</summary>

1. **默认拒绝未知主机密钥**，不要 `AcceptAll`。
2. 首次连接采用 TOFU：弹出指纹确认，确认后写入 known_hosts。
3. known_hosts 解析失败应视为配置错误并中止连接，而不是降级为接受全部。
4. 密钥变更必须告警并阻止自动重连。

```kotlin
if (sftpExtras.knownHosts.isNotBlank()) {
    val entries = loadKnownHostEntries(sftpExtras.knownHosts)
    require(entries.isNotEmpty()) { "known_hosts 无法解析，拒绝连接" }
    client.serverKeyVerifier = buildKnownHostsVerifier(entries, host, port)
} else {
    client.serverKeyVerifier = RejectAllServerKeyVerifier.INSTANCE
    // 或：TofuServerKeyVerifier(prompt = ::confirmHostKey)
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun sftpClientMustNotFallBackToAcceptAllWhenKnownHostsMissingOrEmpty() {
    val verifierWhenBlank = resolveServerKeyVerifier(knownHosts = "")
    val verifierWhenUnreadable = resolveServerKeyVerifier(knownHosts = "not-a-valid-known-hosts")

    assertIsNot<AcceptAllServerKeyVerifier>(verifierWhenBlank)
    assertIsNot<AcceptAllServerKeyVerifier>(verifierWhenUnreadable)
    assertFalse(verifierWhenBlank.verifyServerKey(session, address, unknownKey))
    assertFalse(verifierWhenUnreadable.verifyServerKey(session, address, unknownKey))
}
```

将 `resolveServerKeyVerifier` 抽成可单测的内部函数，避免在集成环境里对真实 SSH 服务器做攻击性握手。

</details>

---

## FS-03：WebRTC 信令 discover / Browser join 无设备鉴权

* 严重度：高
* 类别：authentication_bypass
* 修复状态：✅ 已修复（2026-08-21）
* 位置：
  - `core/src/serverRouteMain/kotlin/com/folderspan/routes/RawHttpWebRtcRoutes.kt`（discover 38–41 行，join 43–80 行，poll 112–118 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/webrtc/signaling/HttpWebRtcSignalingHub.kt`
  - `core/src/serverRouteMain/kotlin/com/folderspan/routes/RawHttpApiDispatcher.kt`（信令路径在 Bearer 之前旁路）
* 置信度：0.88

### 修复落地

- WebRTC `discover` 与 Browser `join` 现在要求已启用且有效的文件分享访问密钥，并使用恒定时间比较。
- `poll` 只接受请求头中的 client token，显式拒绝 query string token 和缺失 token。
- `connect-approved` 仅允许 Host 角色发送，Browser 不能伪造连接批准。
- 文件 RPC 和双向文件流在执行前验证 token 所属设备 ID 与当前 WebRTC 对端一致，防止令牌跨对端复用。
- `RawHttpWebRtcRoutesTest`、`HttpWebRtcSignalingHubTest`、`DevicePathServiceTest` 与 `WebRtcStreamTransferFailureJvmTest` 覆盖鉴权、角色、令牌绑定和失败路径。

### 描述

设备文件 API 的读写需要 Bearer，但 WebRTC HTTP 信令是例外：

- `GET /api/webrtc/signaling/discover`：无 token，返回当前 Host 的 `id` / `name` / `type`。
- `POST /api/webrtc/signaling/join`：Host 角色需要进程内随机 `x-folderspan-webrtc-host-secret`；**Browser 角色无任何密钥**。
- join 成功后发放 48 位 `clientToken`，即可向 Host 发送 `connect-request` / SDP / ICE。
- `GET /api/webrtc/signaling/poll` 允许把 `clientToken` 放在 query string，易进入访问日志与 Referer。

文件 RPC 仍校验 `authToken`（`DevicePathService.ensureAuthorized`），攻击者不能仅靠 join 直接读盘。但：

1. 用户点「同意」或开启「自动授权设备连接」后，Host 会签发真正的设备 token（`issueWebRtcApprovalToken`），且 **WebRTC 签发的 token 不绑定 IP/UA 指纹**（`setTokenPermission` 不写 fingerprint；RPC 侧 `isTokenValid(token)` 在 fingerprint 为 null 时直接通过）。
2. 私网 Origin 的网页可通过 CORS（见 FS-04）直接调用 join/discover，无需用户在 FolderSpan UI 里操作。

### 影响

- 局域网网页或邻机可发现正在等待的 Host，并挤进信令房间。
- 自动授权开启时，可在用户无感知下拿到设备 API token，进而通过 WebRTC 数据通道做文件 RPC。
- 自动授权关闭时，仍能对用户制造连接请求轰炸（社交工程）。

### 利用场景

本机或局域网中的恶意页面（路由器管理页、NAS、被 XSS 的内网页）向 FolderSpan 文件分享端口发起 WebRTC join。用户若习惯性点同意，或开启了自动授权，攻击者即获得与已授权设备同等的文件访问能力。

<details>
<summary>原修复方案（已落地）</summary>

1. Browser join 增加短时邀请码 / 一次性票据（由 Host UI 显示，或由 access key 覆盖），不要对局域网匿名开放。
2. `discover` 同样要求邀请码或仅对本机 loopback 开放。
3. WebRTC 签发的设备 token 必须写入 `DeviceTokenFingerprint`，RPC 校验时传入对端标识。
4. `clientToken` 只允许放在请求头，禁止 query string。
5. `connect-approved` 仅接受来自 Host 角色的信令（当前 `send()` 已要求 `from.id == clientId`，需保持并加角色校验）。
6. 建议默认启用文件分享 access key，或至少在 WebRTC 信令上强制。

```kotlin
if (joinRequest.role != HttpWebRtcSignalingRole.Host) {
    val invite = request.header(HTTP_WEBRTC_BROWSER_INVITE_HEADER).orEmpty()
    if (!HttpWebRtcInviteAuth.verify(invite)) {
        return HttpWebRtcJoinResponse(code = "JOIN_FORBIDDEN", ...)
    }
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun webrtcDiscoverAndBrowserJoinRequireInviteOrAccessKey() = runBlocking {
    val dispatcher = RawHttpApiDispatcher()
    val discover = dispatcher.dispatch(
        RawHttpRequest("GET", "/api/webrtc/signaling/discover", remoteHost = "192.168.1.50")
    )
    assertEquals(401, discover.statusCode)

    val join = dispatcher.dispatch(
        RawHttpRequest(
            method = "POST",
            path = "/api/webrtc/signaling/join",
            headers = mapOf("content-type" to "application/json"),
            body = """{"role":"Browser","device":{"id":"attacker"}}""".encodeToByteArray(),
            remoteHost = "192.168.1.50",
        )
    )
    assertTrue(join.statusCode == 401 || join.statusCode == 403)
}

@Test
fun webrtcPollRejectsClientTokenInQueryString() = runBlocking {
    val poll = dispatcher.dispatch(
        RawHttpRequest(
            method = "GET",
            path = "/api/webrtc/signaling/poll",
            queryParameters = mapOf("clientId" to "id", "clientToken" to "secret"),
            remoteHost = "127.0.0.1",
        )
    )
    assertEquals(401, poll.statusCode)
}
```

</details>

---

## FS-04：设备 API CORS 回显任意私网 Origin 并允许 Private Network Access

* 严重度：中
* 类别：cors_misconfiguration
* 修复状态：✅ 已修复（2026-08-21）
* 位置：`core/src/serverRouteMain/kotlin/com/folderspan/routes/RawHttpDispatcherSupport.kt`（`isAllowedPrivateWebCorsOrigin` / `isPrivateIpv4` 约 220–254 行）
* 置信度：0.86

### 修复落地

- CORS 仅允许 loopback Origin 或与当前请求 Host 相同的 Origin，不再信任任意 RFC1918、链路本地或 ULA 地址。
- 不再返回 `Access-Control-Allow-Private-Network`，即使预检请求要求 PNA 也不会授权。
- 无 Origin 的原生客户端请求保持可用；合法的 localhost 与同 Host 浏览器场景继续返回精确 Origin。
- `RawHttpDeviceApiCorsTest` 与 `RawHttpWebRtcRoutesTest` 覆盖任意私网、链路本地、公网、同 Host、localhost 和 PNA 预检。

### 描述

设备 API 与 WebRTC 信令的 CORS 策略是：Origin 只要是 http(s) + 私网主机，就回显该 Origin，并在预检带 `Access-Control-Request-Private-Network: true` 时返回 `Access-Control-Allow-Private-Network: true`。

被当作「私网」的 IPv4 包括：

- `10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`
- **链路本地 `169.254.0.0/16`**
- 回环 `127.0.0.0/8`

因此，局域网内任意网站（含很多路由器/打印机/IoT 默认页、以及 APIPA 地址上的页面）都能对 FolderSpan 发起跨源请求。设备文件 API 仍需 Bearer，但 FS-01 的 `/ping` 与 FS-03 的 WebRTC 信令**不需要 Bearer**，CORS 放行会把它们暴露给浏览器。

`430a87f454` 已去掉 `*` 通配，这是正确方向；当前实现仍过宽。

### 影响

- 浏览器成为局域网攻击者的 CORS 客户端，用户无需离开恶意内网页。
- 与 FS-01、FS-03 叠加后，攻击面从「能发 TCP 的邻机」扩大到「用户正在浏览的内网页」。

### 利用场景

用户在浏览器打开 `http://192.168.1.1/`（或某台 NAS 的被 XSS 页面），页面对 `http://<folderspan-lan-ip>:12040/api/webrtc/signaling/join` 做 CORS POST。

<details>
<summary>原修复方案（已落地）</summary>

1. CORS 白名单改为 **loopback + 用户明确配置的 Origin**，不要信任整个 RFC1918/169.254。
2. 不要仅凭 Origin 主机落在私网就回显。
3. WebRTC 与 `/ping` 若必须给浏览器用，应为它们单独配置更窄的 Origin（例如仅本机扩展页）。
4. 取消对 169.254 的放行；链路本地不是可信边界。

```kotlin
private fun String.isPrivateIpv4(): Boolean {
    val numbers = toIpv4Numbers() ?: return false
    val first = numbers[0]
    val second = numbers[1]
    return first == 10 ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168) ||
        first == 127
    // 删除 169.254；更彻底的做法是删除整段私网放行，改白名单
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun deviceApiCorsMustNotReflectLinkLocalOrArbitraryRfc1918Origins() {
    val linkLocal = preflight("http://169.254.10.20")
    val rfc1918 = preflight("http://192.168.8.8:3000")
    val loopback = preflight("http://127.0.0.1:5173")

    assertEquals(403, linkLocal.statusCode)
    assertNull(rfc1918.headers["Access-Control-Allow-Origin"])
    // loopback 若仍需给本机 Web 调试，可单独允许，但不得附带 PNA
    assertEquals("http://127.0.0.1:5173", loopback.headers["Access-Control-Allow-Origin"])
    assertNull(loopback.headers["Access-Control-Allow-Private-Network"])
}

private fun preflight(origin: String) = runBlocking {
    dispatcher.dispatch(
        RawHttpRequest(
            method = "OPTIONS",
            path = "/api/webrtc/signaling/join",
            headers = mapOf(
                "origin" to origin,
                "access-control-request-method" to "POST",
                "access-control-request-private-network" to "true",
            ),
        )
    )
}
```

</details>

---

## FS-05：Desktop TLS 身份文件使用 AES-CBC 且无完整性保护

* 严重度：中
* 类别：cryptographic_weakness
* 修复状态：✅ 已修复（2026-08-21）
* 位置：
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/tls/JvmTlsIdentityFileStore.kt`（`AesCbcTlsIdentitySegmentCipher` 约 180–197 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/tls/SegmentedTlsIdentityContainer.kt`（解码无 MAC，约 57–85 行）
* 置信度：0.84

### 修复落地

- Desktop/JVM 与 Android 身份段改用 AES-GCM，并以容器魔数、版本、段数和段索引作为 AAD。
- iOS 使用 AES-CBC + HMAC-SHA256 的 encrypt-then-MAC 结构，认证标签以恒定时间比较。
- 容器只接受当前 v2 认证格式；项目仍处于开发阶段，因此不读取、不迁移旧 CBC v1 身份文件。旧开发身份需要重新生成。
- 身份文件名使用 v2 上下文的 SHA-256 哈希，不再为文件名保留旧 CBC cipher。
- 认证失败、版本不支持或文件损坏都会终止加载，不会静默覆盖原文件。
- `JvmTlsIdentityFileStoreTest` 覆盖密文位翻转、旧 CBC 拒绝、损坏文件不静默重建、原子替换和正常重载。

### 描述

桌面 TLS 设备身份（私钥材料）落盘时：

- 密钥 = `SHA-256(deviceId || directory || entropy salt)`，算法为 `AES/CBC/PKCS5Padding`；
- 容器把身份段与填充段分别加密，**没有 HMAC / GCM 认证标签**；
- 解密后只检查魔数、段类型字节和「恰好一段 IDENTITY」。

CBC 无完整性意味着本地攻击者（能写 `tls-identity` 目录）可以翻转密文比特、尝试填充预言机，或把身份段替换成可解密的伪造块。设备身份一旦被篡改，后续局域网 TLS 钉扎与互信都会建立在攻击者可控的密钥上。

盐文件权限虽尽量 `0600`，但加密方案本身不提供篡改检测。

### 影响

- 本机恶意软件/共享用户可在不读出明文密钥的情况下破坏或嫁接 TLS 身份。
- 与「发现 ping 按叶子证书钉扎」叠加：钉扎的是被篡改后的证书。

### 利用场景

攻击者已能写用户数据目录（恶意桌面应用、错误的目录权限、备份还原投毒），但还没有直接导出私钥。通过改密文使应用加载攻击者准备的身份。

<details>
<summary>原修复方案（已落地）</summary>

1. 将段加密替换为 **AES-GCM**（或 AES-CBC + HMAC-SHA256 encrypt-then-MAC）。
2. AAD 绑定魔数、版本、段类型，防止段重排/替换。
3. 认证失败必须拒绝加载并告警，不要静默生成新身份（除非用户明确重置）。
4. Android 侧若仍用同类容器，一并改为 AEAD（Android 设置已用 Tink AEAD，身份文件应对齐）。

```kotlin
private fun crypt(mode: Int, data: ByteArray, iv: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val spec = GCMParameterSpec(128, iv)
    cipher.init(mode, key, spec)
    cipher.updateAAD(byteArrayOf(VERSION.toByte()))
    return cipher.doFinal(data)
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun tlsIdentityContainerRejectsBitFlipWithoutAuthenticatedEncryption() {
    val cipher = AesGcmTlsIdentitySegmentCipher(keyMaterial)
    val encoded = SegmentedTlsIdentityContainer.encodeIdentityPayload(
        payload = byteArrayOf(1, 2, 3, 4),
        cipher = cipher,
        random = SecureTlsIdentityStorageRandom(),
    )
    encoded[encoded.lastIndex] = (encoded.lastIndex.toByte()) // 翻转最后一字节
    val decoded = SegmentedTlsIdentityContainer.decodeIdentityPayloadOrNull(encoded, cipher)
    assertNull(decoded)
}
```

当前 CBC 实现在许多翻转下仍可能「成功解密」出 PKCS5 填充合法的垃圾明文；修复为 GCM 后该测试必须稳定失败（返回 null）。

</details>

---

## FS-06（误报）：Android `ACTION_VIEW` 任意 MIME 展示入口

* 重新分诊结论：`not_actionable`
* 严重度：不适用
* 类别：预期产品行为，不构成 `intent_injection`
* 状态：⛔ 已撤销漏洞结论（2026-08-21）
* 位置：
  - `app/androidApp/src/main/AndroidManifest.xml`（MainActivity 的 VIEW filter）
  - `app/shared/src/androidMain/kotlin/com/folderspan/share/ShareIntentHandler.android.kt`（`handleView`）
  - `core/src/androidMain/kotlin/com/folderspan/utils/ShareHandler.kt`（`getFileInfoFromUri`、`handleOpenFileLocked`）
  - `core/src/androidMain/kotlin/com/folderspan/utils/PathUtilsHelpers.android.kt`（`localListDirectory`）
* 置信度：0.96

### 重新分诊结论

原报告从“外部应用可传入 URI”推导出“FolderSpan 会加载、解析或打开外部文件”，但当前可执行代码中不存在这个危险 sink。该入口的产品目的就是接收系统“打开方式”传来的文件，并把名称、大小和 MIME 等元数据展示给用户；接受任意 MIME 与这一目的相符。

因此，`ACTION_VIEW` + `*/*` 本身不是漏洞。此前按具体 MIME 建立的白名单会阻止未知格式、Office、压缩包、安装包等文件进入展示列表，属于与产品目标冲突的兼容性限制，现已撤销。

### 静态调用链

1. `ShareIntentHandler.handleView` 取得外部 `content://` URI，并调用 `ShareHandler.handleOpenFile`。
2. `getFileInfoFromUri` 只调用 `ContentResolver.getType` 和 `query`，读取 `DISPLAY_NAME`、`SIZE` 与 MIME 元数据。
3. `handleOpenFileLocked` 只构造 `FileSimpleInfo`、写入 `SharedUriFileRegistry` 并切换到分享文件列表。
4. `localListDirectory` 从注册表返回 `FileSimpleInfo` 供 UI 展示，不打开或解析文件内容。
5. 旧版 `openUri`、`openInputStream`、复制和打开文件逻辑均处于注释中，不属于可执行路径。

仓库其他位置存在由用户操作触发的复制、传输或编辑能力，但未发现它们由此 `ACTION_VIEW` 入口自动调用。若未来增加自动预览、内容解析或安装行为，应针对新增的真实读取/执行 sink 重新做安全评估。

### 保留的输入有效性约束

- Manifest 使用 `content://` + `*/*`，允许展示任意文件类型。
- 运行时要求 `FLAG_GRANT_READ_URI_PERMISSION`，无读取授权的 URI 不进入元数据查询。
- 不接收 `file://`，避免把普通路径字符串误当成可查询的 ContentProvider URI。
- 不根据 MIME 读取、执行或安装文件。

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun exportedViewIntentAcceptsAnyGrantedContentUriWithoutLoadingIt() {
    val manifest = File("app/androidApp/src/main/AndroidManifest.xml").readText()
    val viewBlock = Regex(
        """<action android:name="android.intent.action.VIEW"[\s\S]*?</intent-filter>"""
    ).find(manifest)?.value.orEmpty()

    assertTrue("android:scheme=\"content\"" in viewBlock)
    assertTrue("android:mimeType=\"*/*\"" in viewBlock)
    assertFalse("android:scheme=\"file\"" in viewBlock)

    val shareHandler = File("core/src/androidMain/kotlin/com/folderspan/utils/ShareHandler.kt").readText()
    val activeMetadataPath = shareHandler.substringBefore("// 以下是注释掉的旧实现代码")
    assertTrue("contentResolver.query(uri" in activeMetadataPath)
    assertFalse("contentResolver.openInputStream(uri)" in activeMetadataPath)
}
```

</details>

---

## 已评估但未列入的问题

| 主题 | 结论 |
| --- | --- |
| 归档 zip-slip | `normalizeRelativePath` 拒绝 `..`、绝对路径、反斜杠，现有测试覆盖根目录拼接。 |
| MCP `/proc` `/sys` `/dev` 与敏感目录 | `isRestrictedAliasFilesystemPath` + `SensitiveFileAccessPolicy` 在规范化后拒绝；默认 MCP 只绑 loopback。 |
| MCP `lanAccess=true` | 代理绑 `0.0.0.0`，但 Bearer 哈希恒定时间比较；缺 Origin 时放行是为原生客户端。LAN 浏览器 Origin 仍被限制为 loopback。属可选配置，不单独开洞。 |
| 链接分享密码 | 有尝试锁定；kotlinx.html 自动转义。未发现反射 XSS。 |
| Android 设备 ID 硬编码 AES 密钥 `1234567890123456` | 只用于混淆本地 UUID，设备 ID 会在发现协议中公开，不构成机密泄露。建议改为无密钥的随机 ID，但不作为漏洞。 |
| 明文 HTTP | 局域网分享/MCP/WebDAV 的产品选择，`network_security_config` 已注明。建议默认 HTTPS，但不把「允许明文」单独列为可利用漏洞。 |
| 文件分享 access key 默认关闭 | 配置项，应在文档中强调；与 FS-01/FS-03 叠加时放大影响。 |
| ShizukuProvider `exported=true` | 官方组件模式，权限为 `INTERACT_ACROSS_USERS_FULL`。 |

---

## 修复状态汇总

1. FS-01 至 FS-05 已完成代码修复，并有对应的防御性回归测试。
2. FS-06 经完整 source-to-sink 复核后判定为误报，不需要漏洞修复；任意 MIME 文件展示属于预期产品行为。
3. TLS 身份存储只接受当前 v2 认证格式，不兼容开发阶段的旧 CBC v1 数据。

### 本次验证记录

- 聚焦 `:core:jvmTest` 安全回归套件通过，覆盖 FS-01 至 FS-05 的修复边界和 FS-06 的展示行为。
- `:app:androidApp:assembleDebug` 通过，Android Manifest、VIEW 入口和 Android AES-GCM 身份存储均完成编译验证。
- 当前主机为 Linux，iOS CommonCrypto/cinterop 实现无法在本机编译；iOS AES-CBC + HMAC-SHA256 实现仅完成静态核对，需在 macOS CI/Xcode 环境补充验证。

后续若修改这些安全边界，应继续运行对应回归测试，并保持 `md_descriptions_paths.md` 与本报告同步。

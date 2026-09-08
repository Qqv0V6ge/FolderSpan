# FolderSpan 安全审计报告（第十一轮）

- 审计日期：2026-08-24
- 审计范围：全仓（`app/`、`core/`、`proMain/`），非仅当前 PR diff
- 焦点：局域网邻机恶意请求能否把分享进程的堆内存打爆（请求体缓冲、会话、待审设备、通知、设备列表）
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
  - 第十轮见 [2026-08-24 全仓安全审计报告（第十轮）](2026-08-24-security-audit-round-10.md)（技能面 CSRF/XSS 等无新登记）
- 方法论：Claude Marketplaces 插件 `cybersecurity-skills@anthropic-cybersecurity-skills` v1.3.0
  覆盖技能：`performing-api-rate-limiting-bypass`、`implementing-api-rate-limiting-and-throttling`、`testing-for-business-logic-vulnerabilities`、`testing-for-host-header-injection`、`performing-security-headers-audit`
  说明：技能库默认不把纯 DoS 当独立漏洞。本轮是用户点名「邻机恶意请求导致内存暴增」之后的专项，对照第八轮已落地的 FS-36 / FS-37（可用性 / 无界持久化）口径登记。
- 约束：本报告不含攻击载荷或 exploit PoC。折叠区内的「验证代码」均为防御性回归测试（断言安全属性），用于确认修复是否生效。
- 威胁模型：局域网邻机、同机恶意进程/网页。本机文件分享服务按产品设计会对局域网可达。本轮不要求有效会话、ticket 或口令。
- 本轮只登记相对前十轮**仍未修复的新问题**，且置信度约 ≥0.8、有可利用路径。已落地的 FS-01～FS-44、已排除项与产品明确文档化的同步行为不重复开洞。

## 审计发现总览

| ID | 严重度 | 类别 | 标题 | 状态 |
| --- | --- | --- | --- | --- |
| FS-45 | 高 | availability / unbounded_buffer | 链路分享 HTTP 在 Host 白名单与设备守卫之前，按 `Content-Length` 最多分配 128 MB 堆；64 路并发可把进程打到数 GB | 已修复 |
| FS-46 | 中 | unbounded_persistence / inventory_pollution | 无口令、非自动批准时，伪造 `clientId` 可无界写入待审设备、应用内通知与请求日志；自动批准时授权表同样无 trim | 已修复 |

---

## FS-45：链路分享在鉴权前按声明长度分配最多 128 MB 堆缓冲

* 严重度：高
* 类别：availability / unbounded_buffer
* 修复状态：已修复
* 位置：
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/server/LinkShareRawHttpServer.jvm.kt`（`parseRequest` 约 192–230 行：非 `/api/share/upload` 走 `readExact`；`readExact` 约 371–380 行先 `ByteArray(length)`；`DEFAULT_MAX_REQUEST_BODY_BYTES = 128L * 1024L * 1024L`、`MAX_CONCURRENT_CLIENTS = 64`、套接字超时 5 分钟，约 448–453 行）
  - `core/src/androidMain/kotlin/com/folderspan/service/http/server/LinkShareRawHttpServer.android.kt`（同结构、同常量）
  - `core/src/iosMain/kotlin/com/folderspan/service/http/server/LinkShareRawHttpServer.ios.kt`（`parseRequest` 约 246–286 行同样 `readExact(connection, contentLength.toInt())`；默认上限 128 MB、并发 64，约 603–607 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/server/linkshare/LinkShareRouteDispatcher.kt`（`rejectedLinkShareHostResponse` 约 155–164 行、`prepareLinkShareAccess` 约 166 行：都在 `dispatch` 内，发生在 body 已经读完之后）
  - 对照已落地路径：`core/src/jvmMain/kotlin/com/folderspan/service/http/server/raw/RawTlsHttpServer.jvm.kt`（设备 API 控制面 `MAX_BUFFERED_REQUEST_BODY_BYTES = 8MB`，流式路由才用 128 MB 且 **不** 整包进 `ByteArray`，约 251–268、464–465 行）；`core/src/commonMain/kotlin/com/folderspan/service/mcp/http/McpHttpRequestHandler.kt`（MCP body 上限 1 MB，约 196 行）
* 置信度：0.93

### 描述

链路分享 raw HTTP 服务器在 `accept` 之后、进入 `handler`（也就是 `LinkShareRouteDispatcher.dispatch`）之前解析整份请求。`parseRequest` 读完 header 后：

```kotlin
val maxBodyBytes = maxRequestBodyBytes
if (contentLength !in 0L..maxBodyBytes) throw LinkShareRequestBodyTooLargeException()
val body = when {
    "chunked" in transferEncodings -> LinkShareHttpRequestBody.Bytes(input.readChunkedBody(maxBodyBytes))
    contentLength <= 0L -> LinkShareHttpRequestBody.Empty
    target.substringBefore("?") == "/api/share/upload" -> InputStreamLinkShareRequestBody(input, contentLength)
    else -> LinkShareHttpRequestBody.Bytes(input.readExact(contentLength.toInt()))
}
```

只有路径恰好是 `/api/share/upload` 才流式。其它任何方法、任何路径——包括 `GET /`、`POST /auth`、`/favicon.ico`、`/static/...`、甚至随后会被 Host 白名单 403 的请求——都会走 `readExact`。

`readExact` 在从套接字读完数据之前就按声明长度分配：

```kotlin
private fun InputStream.readExact(length: Int): ByteArray {
    val result = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(result, offset, length - offset)
        if (read < 0) throw EOFException("请求体不完整")
        offset += read
    }
    return result
}
```

默认上限是 **128 MB**。邻机只要在 header 里写合法的 `Content-Length`（≤128 MB），进程就会立刻拿出对应大小的堆数组。body 可以慢慢传，也可以几乎不传：套接字超时是 5 分钟，分配会一直占着。

并发控制挡不住这条路径。`Semaphore(MAX_CONCURRENT_CLIENTS = 64)` 在 `parseRequest` **之前** `tryAcquire`。64 个已接受连接各自都可以声明 128 MB。理论峰值约 **8 GB** 堆，远超桌面/手机文件管理器进程的正常工作集。header 已有 16 KB / 96 条 / 64 KB 上限，挡不住 body。

FS-38 把非广告 Host 改成 403，但检查在 dispatcher 里，body 已经进堆了。MCP 复用同一套服务器时传入 1 MB 上限，设备 API 控制面是 8 MB、上传走流式 reader——这两条已经对齐，链路分享业务端口没有。

chunked 编码会累计校验 `maxBodyBytes`，但仍把各 chunk 放进 `mutableListOf<ByteArray>` 再拼成整包，峰值同样可以顶到 128 MB。

### 影响

- 局域网邻机不需要口令、会话、ticket，也不需要正确的 `Host`。
- 分享服务与主 UI 同进程。堆被打满后，目录列举、传输、托盘都会一起 OOM / 卡死，而不只是分享页不可用。
- Android 低内存设备上，单路 128 MB 就可能触发 LMK。
- 与「产品设计上 LAN 可达」无关：可达不等于允许未鉴权方按声明长度预分配上百 MB。

### 利用场景

主人打开轻松分享（HTTP 监听局域网）。邻机对分享端口建立最多 64 条连接，每条发送带大 `Content-Length` 的请求（路径不是 `/api/share/upload`），然后把连接挂住直到超时。进程在读完网络数据、做 Host 检查、做设备守卫之前就已经按声明长度分配堆。本报告不提供请求样例。

<details>
<summary>修复方案</summary>

1. **把非流式路径的缓冲上限降到与设备 API 控制面同级**（建议 8 MB，或更小的 JSON/表单上限，例如 64 KB～1 MB）。128 MB 只留给真正流式的上传路由。
2. **按方法/路径分流**：`GET` / `HEAD` / `OPTIONS` 以及静态资源、favicon 不应接受非空 body；有 `Content-Length > 0` 直接 413 并丢弃，不要分配。
3. **`readExact` 不要按声明长度一次性 `ByteArray(length)`**。先按块读（例如 64 KB），累计超过上限再抛 `LinkShareRequestBodyTooLargeException`。声明 128 MB 但只送来几十字节时，堆占用应接近已读字节，而不是声明值。
4. **把 Host 白名单前移到读 body 之前**（读完 header 即可判定）。非广告 Host 直接 403，丢弃剩余字节，不进堆。
5. JVM / Android / iOS 三套 `LinkShareRawHttpServer` 一起改，常量不要再分叉。
6. MCP 已经传入 1 MB，保持不变。不要把链路分享默认上限「顺便」抬回 128 MB。

建议常量拆分，避免以后又把上传上限误用到 JSON：

```kotlin
private const val MAX_BUFFERED_REQUEST_BODY_BYTES = 8L * 1024L * 1024L
private const val MAX_STREAMING_REQUEST_BODY_BYTES = 128L * 1024L * 1024L
```

`parseRequest` 伪代码：

```kotlin
val isUpload = target.substringBefore("?") == "/api/share/upload"
val maxBodyBytes = if (isUpload) MAX_STREAMING_REQUEST_BODY_BYTES else MAX_BUFFERED_REQUEST_BODY_BYTES
if (contentLength !in 0L..maxBodyBytes) throw LinkShareRequestBodyTooLargeException()
if (!isUpload && method in setOf("GET", "HEAD", "OPTIONS") && contentLength > 0L) {
    throw LinkShareRequestBodyTooLargeException()
}
val body = when {
    "chunked" in transferEncodings -> {
        if (isUpload) throw LinkShareRequestBodyTooLargeException()
        LinkShareHttpRequestBody.Bytes(input.readChunkedBody(maxBodyBytes))
    }
    contentLength <= 0L -> LinkShareHttpRequestBody.Empty
    isUpload -> InputStreamLinkShareRequestBody(input, contentLength)
    else -> LinkShareHttpRequestBody.Bytes(input.readExactCapped(contentLength.toInt(), maxBodyBytes))
}
```

</details>

<details>
<summary>防御性验证代码</summary>

下列断言只检查上限与分配策略：超限返回 413、非上传路径不得按声明长度一次性分配、GET 带 body 被拒。不要构造占满堆的并发载荷，不要对局域网其它主机发请求。

```kotlin
@Test
fun linkShareBufferedBodyLimitIsFarBelowStreamingUploadLimit() {
    assertTrue(MAX_BUFFERED_REQUEST_BODY_BYTES <= 8L * 1024L * 1024L)
    assertTrue(MAX_STREAMING_REQUEST_BODY_BYTES <= 128L * 1024L * 1024L)
    assertTrue(MAX_BUFFERED_REQUEST_BODY_BYTES < MAX_STREAMING_REQUEST_BODY_BYTES)
}

@Test
fun parseRequestRejectsContentLengthAboveBufferedLimitOnNonUploadPath() {
    val headers = "POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: ${8 * 1024 * 1024 + 1}\r\n\r\n"
    val response = serveOnce(headers.encodeToByteArray())
    assertEquals(413, response.statusCode)
}

@Test
fun parseRequestDoesNotAllocateDeclaredLengthBeforeBytesArrive() {
    val declared = 8 * 1024 * 1024
    val before = usedHeapBytes()
    val stalled = stallAfterHeaders(
        "POST /auth HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: $declared\r\n\r\n"
    )
    val after = usedHeapBytes()
    stalled.close()
    assertTrue(after - before < declared / 4)
}

@Test
fun getWithContentLengthIsRejectedWithoutBuffering() {
    val response = serveOnce(
        "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 1048576\r\n\r\n".encodeToByteArray()
    )
    assertEquals(413, response.statusCode)
}

@Test
fun shareUploadStillStreamsAndDoesNotMaterializeBodyArray() {
    assertTrue(shouldStreamLinkShareRequestBody("/api/share/upload", contentLength = 16L * 1024L * 1024L))
    assertFalse(shouldStreamLinkShareRequestBody("/", contentLength = 16L * 1024L * 1024L))
}
```

测试只断言 413 与「分配量远小于声明长度」。不要打印分享文件内容，不要跑满 64 路 128 MB。

</details>

---

## FS-46：链路分享待审设备、通知与授权表对伪造 clientId 无数量上限

* 严重度：中
* 类别：unbounded_persistence / inventory_pollution
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/service/http/server/linkshare/LinkShareRouteDispatcher.kt`（`resolveLinkShareClientId` 约 1659–1661 行：无 cookie 则 `32.randomString`；`prepareLinkShareAccess` 约 261–275 行：无口令时 `addPendingLinkShareDevice` 后仍 `return null` 继续业务路由；`handleRoot` 约 336 行按 `Device` `getOrPut` `deviceRequestLog`）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/file/FileShareState.kt`（`addPendingLinkShareDevice` 约 329–342 行只按 `device.id` 去重、无 MAX；`authorizeLinkShareDevice` 约 98–113 行写入 `authorizedLinkShareDevices` 不 trim；会话/ticket 有 `MAX_LINK_SHARE_SESSIONS = 64` / `MAX_LINK_SHARE_TICKETS = 64`，约 687–690、416–432 行）
  - `core/src/commonMain/kotlin/com/folderspan/data/main/device/Device.kt`（`data class Device` 约 126–136 行，实现 `DiskBase` + `KoinComponent`，构造后 inject `DeviceState` / `TaskState`）
  - `core/src/commonMain/kotlin/com/folderspan/notification/RequestNotificationFactory.kt`（`requestId(kind, deviceId) = "${kind.name}:$deviceId"`，约 62–64 行；每条新 clientId 对应一条新通知）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/main/NotificationState.kt`（`upsert` 约 176–187 行：id 不同就 `add(0, notification)`，列表无上限）
  - `core/src/commonMain/kotlin/com/folderspan/utils/DeviceRequestLogUtils.kt`（每个 Device 一份日志对象；内存 100 条后 flush 到 `device_logs/{device.id}.txt`，约 13–32 行——**设备个数**仍无上限）
  - 对照已落地路径：`core/src/serverRouteMain/kotlin/com/folderspan/routes/RawHttpDeviceRoutes.kt`（FS-37：`MAX_PENDING_UNKNOWN_DEVICES = 64`、每源 8，约 566–567 行）。链路分享待审没有对齐。
* 置信度：0.90

### 描述

轻松分享在「密码访问」关闭时（`initializeEasyShareIfNeeded` 约 554–558 行：`getPasswordAccess() == false` 则口令为空），未授权请求不会停在口令页，而是进入待审：

```kotlin
if (!fileShareState.pendingLinkShareDevices.contains(device)) {
    fileShareState.addPendingLinkShareDevice(device)
    LogKit.d("加入待授权列表: $device")
}
return null
```

`return null` 表示「继续分发」。`handleRoot` 接着给每个 `Device` 建请求日志，并返回等待页。

客户端身份来自 cookie。没有 cookie、或 cookie 不是 16–128 位安全字符时，服务器**当场签发**新 id：

```kotlin
private fun LinkShareHttpRequest.resolveLinkShareClientId(): String {
    val existing = cookies[LINK_SHARE_CLIENT_COOKIE]?.takeIf { item -> item.isSafeLinkShareToken() }
    return existing ?: 32.randomString(includeSpecial = false)
}
```

邻机只要不带 `LINK_SHARE_CLIENT_COOKIE`，每次请求都是新 `device.id`。`addPendingLinkShareDevice` 只按 id 去重：

```kotlin
fun addPendingLinkShareDevice(device: Device, ...) {
    if (pendingLinkShareDevices.any { item -> item.id == device.id }) return
    pendingLinkShareDevices.add(device)
    val bundle = RequestNotificationFactory.buildLinkShareNotification(
        deviceId = device.id,
        deviceName = device.name,
        ...
    )
    RequestNotificationDispatcher.post(notificationState, bundle)
}
```

没有 `MAX_PENDING_*`，也没有按源 IP 限流。`Device` 是带 Koin 依赖的 data class，不是轻量 DTO。通知 `requestId` 含 `deviceId`，`upsert` 不会合并不同 id，铃铛列表与（默认开启的）系统通知一起涨。`pendingLinkShareDevices.contains(device)` 走 data class 全字段相等；id 不同就加。

自动批准时会话会 `trimOldestLinkShareSessions` 到 64，但 `authorizedLinkShareDevices` 以整个 `Device` 为键、只按 id 覆盖同 id 旧项，**不同 clientId 会一直堆**。`deviceRequestLog` 同样按 `Device` 实例 `getOrPut`，无上限；每个伪造 id 还会在缓存目录留下 `device_logs/{id}.txt`。

这与 FS-37 是同一类问题：未认证邻机把几乎无界的设备记录写进内存（以及旁路磁盘）。FS-37 修的是 `/ping` 与 `/api/devices/connect`，没覆盖链路分享待审。

有口令时，未认证请求停在 401 / 口令页，不会进待审——所以 **FS-46 的主路径是「无密码分享」**（轻松分享默认即可关掉密码访问）。FS-45 与是否有口令无关。

### 影响

- 无口令分享时，邻机可用不同 clientId 把待审列表、Compose 状态、应用内通知和系统通知打到任意长度，UI 卡顿、内存持续上涨。
- 自动批准时授权表与请求日志仍然无界；会话 trim 救不了 `authorizedLinkShareDevices`。
- 每个伪造设备对应一份日志工具对象；内存 100 条后还会按 id 落盘，缓存目录被垃圾文件填满。
- 不读分享文件、不绕过口令哈希；这是可用性与库存污染，和 FS-37 同级。

### 利用场景

主人开启轻松分享且未开密码访问。邻机对分享根路径反复发带 `User-Agent`、不带客户端 cookie 的 GET。每次请求进入待审、弹通知、写入 `deviceRequestLog`。主人不点批准也会一直涨。自动批准开启时，授权 map 同样涨。本报告不提供请求样例。

<details>
<summary>修复方案</summary>

对齐 FS-37 的配额，并让 cookie 缺省时不要每次新建身份。

1. **待审 / 拒绝 / 授权 / 上传待审 四面都加上限**，例如：
   - 全局待审 64
   - 每源 IP 8
   - 授权设备 256（或与会话 64 对齐，按 `device.id` trim 最旧）
   - 通知列表 64（同 request kind 超出则删最旧）
2. **`resolveLinkShareClientId` 在无 cookie 时不要无条件 `randomString`**。可按 `remoteHost + User-Agent` 的短哈希派生稳定 id（仍要过 `isSafeLinkShareToken`），让同一邻机反复请求打进同一待审槽。需要防伪造 cookie 时，用 HMAC（本机密钥）而不是客户端可预测的明文。
3. **`addPendingLinkShareDevice` 满额时拒绝新项**（429 或继续等在等待页，但不再 `add` / 不再 `post` 通知），并 `trimOldest`。
4. **`authorizeLinkShareDevice` 写入前按 id trim**，与 `trimOldestLinkShareSessions` 共用同一套上限。不要让授权表比会话更宽。
5. **`deviceRequestLog` 按 `device.id` 索引，并限制 map 大小**；淘汰时 `clear()` 对应日志文件。不要用整个 `Device` data class 当键。
6. **`NotificationState.upsert` 对 LinkShare kind 做容量裁剪**，或在 dispatcher 里先 `removeByRequestId` 再受配额控制地插入。

```kotlin
private const val MAX_PENDING_LINK_SHARE_DEVICES = 64
private const val MAX_PENDING_LINK_SHARE_DEVICES_PER_SOURCE = 8
private const val MAX_AUTHORIZED_LINK_SHARE_DEVICES = 64

fun addPendingLinkShareDevice(device: Device, sourceHost: String? = null, ...) {
    if (pendingLinkShareDevices.any { item -> item.id == device.id }) return
    if (pendingLinkShareDevices.size >= MAX_PENDING_LINK_SHARE_DEVICES) {
        trimOldestPendingLinkShareDevices(1)
    }
    if (countPendingFrom(sourceHost) >= MAX_PENDING_LINK_SHARE_DEVICES_PER_SOURCE) return
    pendingLinkShareDevices.add(device)
    RequestNotificationDispatcher.post(notificationState, bundle)
}
```

</details>

<details>
<summary>防御性验证代码</summary>

下列断言只检查列表上限与同 id 去重。用内存中的 `FileShareState` 直接调 API，不要对局域网端口发洪水请求。

```kotlin
@Test
fun pendingLinkShareDevicesCapAtMaxAndDedupeById() {
    val state = FileShareState(/* 测试夹具 */)
    repeat(MAX_PENDING_LINK_SHARE_DEVICES + 16) { index ->
        state.addPendingLinkShareDevice(
            Device(
                id = "client-$index",
                name = "Browser",
                pathSeparator = "/",
                host = mutableMapOf(),
                type = DeviceType.JS,
                token = "",
            )
        )
    }
    assertTrue(state.pendingLinkShareDevices.size <= MAX_PENDING_LINK_SHARE_DEVICES)
}

@Test
fun pendingLinkShareDevicesDedupeSameClientId() {
    val state = FileShareState(/* 测试夹具 */)
    val device = testDevice(id = "same-id")
    repeat(8) { state.addPendingLinkShareDevice(device.copy()) }
    assertEquals(1, state.pendingLinkShareDevices.count { item -> item.id == "same-id" })
}

@Test
fun authorizeLinkShareDeviceTrimsToMaxDistinctIds() {
    val state = FileShareState(/* 测试夹具 */)
    repeat(MAX_AUTHORIZED_LINK_SHARE_DEVICES + 8) { index ->
        state.authorizeLinkShareDevice(
            device = testDevice(id = "auto-$index"),
            allowHidden = false,
            files = emptyList(),
            revokeExistingSessions = false,
        )
    }
    assertTrue(state.authorizedLinkShareDevices.size <= MAX_AUTHORIZED_LINK_SHARE_DEVICES)
}

@Test
fun linkShareNotificationUpsertDoesNotGrowWithoutBound() {
    val notifications = NotificationState()
    repeat(128) { index ->
        val bundle = RequestNotificationFactory.buildLinkShareNotification(
            deviceId = "id-$index",
            deviceName = "Browser",
        )
        RequestNotificationDispatcher.post(notifications, bundle)
    }
    assertTrue(notifications.notificationList.size <= MAX_PENDING_LINK_SHARE_DEVICES)
}

@Test
fun missingClientCookieReusesStableIdForSameSource() {
    val first = request("/").resolveLinkShareClientIdForTest()
    val second = request("/").resolveLinkShareClientIdForTest()
    assertEquals(first, second)
    assertTrue(first.isSafeLinkShareToken())
}
```

测试只断言 size 上限与去重。不要打印设备 token，不要在测试里启动真实系统通知。

</details>

---

## 已检查、不作为本轮新漏洞

| 主题 | 结论 |
| --- | --- |
| FS-01～FS-44 | 不重复开洞。第十轮 CSRF/XSS/开放重定向等技能面维持「无新登记」。 |
| 设备 API 控制面 body | `RawTlsHttpServer` 非流式路径 `MAX_BUFFERED_REQUEST_BODY_BYTES = 8MB`，流式路由 128 MB 且 body 数组为空、走 `InputStreamRawHttpRequestBodyReader`。控制 32 / 数据 64 / 配对 4 分桶。达不到链路分享「未鉴权 128 MB × 64 整包」的量级。 |
| MCP | 复用 `LinkShareRawHttpServer` 但构造时传入 1 MB；工具并发 16、SSE 16。 |
| Header | 行 16 KB、条数 96、总字节 64 KB。三端链路分享与设备 API 一致。 |
| 会话 / ticket | `MAX_LINK_SHARE_SESSIONS = 64`、`MAX_LINK_SHARE_TICKETS = 64`，插入前 `trimOldest*`。不够单独开洞。 |
| 设备发现 | FS-37 已对 `/ping` 与 `/api/devices/connect` 设 64 全局 / 每源 8 / 已存 256。本轮不重开。 |
| `DeviceRequestLogUtils` 单设备内存 | 每设备内存 100 条后 flush。问题是**设备个数**无界（并入 FS-46），不是单日志无限涨。 |
| 有口令时的待审路径 | `connectPassword` 非空时 `prepareLinkShareAccess` 返回 401 / 口令页，不调用 `addPendingLinkShareDevice`。FS-46 主路径是无密码分享。 |
| FS-36 connect 饿死 | 已用配对桶修好，不重开。 |
| WebRTC join | browser join 已有 cap，本轮未发现与链路分享同级的未鉴权堆分配。 |
| 目录列表响应体积 | `DynamicDirectoryListLimiter` 限制并发列举。大目录 JSON 仍可能偏大，但需要已授权会话，且不是「声明长度立刻分配」。不够本轮阈值。 |
| chunked 与 Content-Length 同时出现 | 走 chunked 分支并受 `maxBodyBytes` 约束。单独看是解析粗糙，峰值仍被 128 MB 盖住，并入 FS-45 修，不另开 ID。 |

---

## 修复优先级建议

1. **立即：FS-45**。未鉴权邻机即可把同进程打到数 GB。先降非流式上限、GET 禁 body、Host 检查前移，三端一起改。
2. **紧随：FS-46**。无密码分享是产品常用形态，待审/通知/授权表对齐 FS-37 配额。
3. 第十轮残留硬化（`Service-Worker-Allowed`、CORS 允许头白名单、`POST /auth` 同源）仍建议做，但不阻塞本轮两项。

后续审计不要重开 FS-01～FS-44。本轮之后若修了缓冲上限与待审配额，下一轮应回归验证「声明 128 MB 不再整包进堆」以及「待审列表 size ≤ 上限」，而不是再扫一遍 CSRF/XSS。

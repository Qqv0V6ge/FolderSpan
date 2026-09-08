# FolderSpan 安全审计报告（第九轮）

- 审计日期：2026-08-23
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
- 方法论：Claude Marketplaces 插件 `cybersecurity-skills@anthropic-cybersecurity-skills` v1.3.0
  覆盖技能：`exploiting-insecure-data-storage-in-mobile`、`performing-ios-app-security-assessment`、`testing-for-sensitive-data-exposure`、`performing-clickjacking-attack-test`、`testing-websocket-api-security`、`exploiting-deeplink-vulnerabilities`、`performing-security-headers-audit`、`testing-android-intents-for-vulnerabilities`、`performing-directory-traversal-testing`、`implementing-secret-scanning-with-gitleaks`、`testing-for-xxe-injection-vulnerabilities`、`exploiting-insecure-deserialization`、`testing-for-host-header-injection`
- 约束：本报告不含攻击载荷或 exploit PoC。折叠区内的「验证代码」均为防御性回归测试（断言安全属性），用于确认修复是否生效。
- 威胁模型：局域网邻机、同机恶意进程/网页、不可信 Android Intent、SFTP 中间人、已授权设备上的 token 盗用。本机文件分享服务按产品设计会对局域网可达。
- 本轮只登记相对前八轮**仍未修复的新问题**。已落地的 FS-01～FS-37、已排除项与产品明确文档化的同步行为不重复开洞。

## 审计发现总览

| ID | 严重度 | 类别 | 标题 | 状态 |
| --- | --- | --- | --- | --- |
| FS-38 | 中 | dns_rebinding / host_header / same_origin | 链路分享对非广告 Host 仍按 200 分发页面；页面脚本用 `location.origin` 发同源请求，FS-21 的页面基址改写挡不住 DNS rebinding | 已修复 |
| FS-39 | 中 | insecure_data_storage | Web `localStorage` 明文持久化 Pro Token、文件分享 Access Key 与包装密钥，未走 Desktop/iOS 的 `SensitiveSettings` 金库 | 已修复 |
| FS-40 | 中 | insecure_data_storage / leftover_secret | `SensitiveSettings.putString` 写入金库时不清除 Preferences / NSUserDefaults 里的历史明文 | 已修复 |
| FS-41 | 中 | insecure_file_permissions | Desktop `folderspan.db`、`tls-identity/` 未强制 0700/0600，与已落地的 `secure-settings` / `pro_http_cache` 不一致 | 已修复 |
| FS-42 | 中 | backup_exposure | iOS `folderspan.db` 与 `tls-identity` 未标 `NSURLIsExcludedFromBackupKey`，会进 iCloud / iTunes 备份 | 已修复 |
| FS-43 | 高 | directory_traversal / zip_slip | 网盘目录下载用字符串拼接落盘，恶意服务器可用 `..` 写出用户选定目录；SFTP/FTP/SMB 下载还跟随叶子符号链接 | 已修复 |
| FS-44 | 中 | insecure_temp_file / local_information_disclosure | 桌面编辑器备份/恢复、任务运行态、失败明细、设备日志、同步暂存与 MCP 暂存仍写入全局 `java.io.tmpdir`，默认对同机其他用户可读 | 已修复 |

---

## FS-38：链路分享未在路由分发层拒绝非广告 Host，页面脚本仍走 rebound 源

* 严重度：中
* 类别：dns_rebinding / host_header / same_origin
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/service/http/server/linkshare/LinkShareRouteDispatcher.kt`（`dispatch` 约 119–144 行：只拦 CORS 预检，不拦非广告 Host 的正式请求）
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/server/LinkShareRawHttpServer.jvm.kt` 与 Android 对应实现（`hostAndPort` 约 416–432 行：客户端 `Host` 原样写入 `LinkShareHttpRequest.host`）
  - `core/src/commonMain/composeResources/files/share-file/static/share/share-index.js`（约 106 行相对路径 `fetch`；约 125 行 `new URL(..., window.location.origin)`；约 588 行 `httpBaseUrl` 只作数据属性，页面 API 并不用它）
  - `core/src/commonMain/composeResources/files/share-file/static/share/share-upload.js`（`buildApiUrl` 约 311–325 行同样以 `window.location.origin` 拼上传 API）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/server/linkshare/LinkShareHttpHeaderPolicies.kt`（CSP `connect-src 'self'` 约 162 行；CORS 同源白名单约 114–128 行只约束带 Origin 的跨源预检）
  - 对照已落地路径：`core/src/serverRouteMain/kotlin/com/folderspan/routes/RawHttpApiDispatcher.kt`（`isRejectedPrivateWebRequest` 在 OPTIONS 与正式分发之前 403，约 62–81 行）；`core/src/commonMain/kotlin/com/folderspan/service/mcp/http/McpHttpSecurityPolicy.kt`（Host 必须是 loopback 或广告地址，约 12–15 行）
  - 现有测试把残留写成正向保证：`LinkShareRouteDispatcherJvmTest.pageConfigUsesAdvertisedHostInsteadOfUntrustedRequestHost` 对 `host = "share.example"` 期望 **200**，只断言 HTML 不含该主机名
* 置信度：0.90

### 描述

第五轮 FS-21 的修复方案原文要求：主机名不在广告集合内时，**在 CORS 判定和路由分发之前拒绝**，不要用客户端 `Host` 当真实源。落地情况是分层的：

| 面 | MCP | 设备 API | 链路分享 |
| --- | --- | --- | --- |
| CORS / Origin 白名单 | 拒绝非广告 Host | 拒绝 | 拒绝（`corsAllowsOnlyAdvertisedSameOriginRequests` 对 `share.example` 预检期望 403） |
| 页面 / 脚本里的绝对 URL | 不适用 | 不适用 | `buildLinkSharePageConfig` 改写为广告地址 |
| **路由分发** | 鉴权前拒绝 | `isRejectedPrivateWebRequest` → 403 | **仍 200** |

链路分享 `dispatch` 入口：

```kotlin
suspend fun dispatch(request: LinkShareHttpRequest): LinkShareHttpResponse {
    val corsPreflight = LinkShareHttpHeaderPolicies.corsPreflightResponse(
        request,
        advertisedHostProvider.get(),
    )
    if (corsPreflight != null) {
        return corsPreflight.withCommonHeaders(request)
    }
    return try {
        val pageConfig = request.buildLinkSharePageConfig()
        ...
        router.dispatch(exchange)...
```

没有等价于设备 API 的 `isRejectedPrivateWebHost`。浏览器导航、相对路径 `fetch`、表单 `POST /auth` 都不带跨源 Origin 预检，因此 CORS 白名单根本碰不到这条路径。

FS-21 已改的 `data-link-share-http-base-url` 也挡不住同源脚本。页面实际发出的分享 API 请求是：

```javascript
await fetch("/api/share/upload-permission/request", { method: "POST", headers: { "X-API-Request": "true" } });
const url = new URL("/api/share/upload-check", window.location.origin);
const url = new URL(endpoint, window.location.origin); // share-upload.js
```

`readLinkSharePageConfig()` 读到的广告基址只用于 HTTPS 同意跳转等少数路径；目录浏览、上传检查、上传、取消都绑在 **当前文档源**。CSP 还写了 `connect-src 'self'`：即便前端改去打广告 IP，从 rebound 主机名加载的页面也会被浏览器拦住。所以「HTML 里不出现 `share.example`」并不能把会话留在广告源上。

Cookie 加重这条链：

- `FolderSpanLinkShareSession` / `FolderSpanLinkShareClient` 不设 `Domain`，是 **host-only**。
- `SameSite=Lax`、`HttpOnly`；`Secure` 仅当 `request.scheme == "https"`。
- 产品局域网分享默认明文 HTTP，因此 rebound 导航上签发的会话 cookie 作用域就是该主机名，后续同源 `fetch` 会带上。

这不是重开 FS-21 的 CORS 比较错误：CORS 预检与页面绝对 URL 已经按广告地址改过。残留是 **「页面可以在非广告 Host 上成为分享源」**，再叠加「脚本故意走 `location.origin`」。设备 API 与 MCP 已经把同一 Host 检查放到分发层；链路分享是唯一仍对 `share.example` 返回业务 200 的局域网 HTTP 入口。

现有测试把这一点锁成回归：

```kotlin
).copy(host = "share.example", port = 1204)
...
assertEquals(200, response.statusCode, html)
assertContains(html, "data-link-share-http-base-url=\"http://192.168.1.20:1204\"")
assertFalse("share.example" in html)
```

设备 API 对照测试 `httpWebRtcPreflightRejectsUnaadvertisedHostnameEvenWhenPortMatchesOnRawApi` 对同一主机名期望 403。

### 影响

- 用户（或同机恶意页）一旦用解析到分享端口的非广告主机名打开链路分享，该源就成为分享应用的同源。已批准会话、口令通过后的 cookie、ticket 兑换后的跳转，都会落在这个主机名上。
- 之后该源上的脚本可以浏览、下载；若主人已授权上传，也可以写入。不需要 CORS 放行，因为请求已经是同源。
- 不能单靠这一条伪造别人的 IP+UA 指纹（会话仍绑 `ShareTokenFingerprint`）。危害是把「主人分享给访客的源」从广告 IP 换成攻击者控制的 DNS 名。
- Chrome 对「公网页打私网」的 Private Network Access 帮不上忙：文档本身就以该主机名:分享端口加载。

### 利用场景

主人在局域网开启文件分享。邻机让同一网段的浏览器打开一个最终解析到该设备地址、端口等于分享端口的主机名（DNS rebinding、恶意本机 hosts、同机 rogue DHCP 等）。浏览器把该主机名放进 `Host`。链路分享返回 200 页面并在该 host-only cookie 上签发或复用会话。页面脚本用 `location.origin` 继续打分享 API。CORS 白名单与 `data-link-share-http-base-url` 都不会参与这条同源路径。

<details>
<summary>修复方案</summary>

1. **把设备 API / MCP 的 Host 白名单下沉到链路分享 `dispatch`。** 在 CORS 预检之后、`prepareLinkShareAccess` / 路由之前：`Host` 不是 loopback 且不在 `advertisedHostProvider` 集合内时返回 403，不要再生成页面、cookie 或会话。
2. 空白或无法解析的 `Host` 也拒绝（设备 API：`normalizeHostHeader` 失败即拒绝）。不要回落到 `127.0.0.1` 后继续当已校验源。
3. 现有 `pageConfigUsesAdvertisedHostInsteadOfUntrustedRequestHost` 应改为期望 403，不再把「HTML 不含该主机名」当作安全属性。
4. 纵深防御：分享页脚本的 API 基址统一走已校验的 `httpBaseUrl`，不要再用 `window.location.origin` 或根相对路径拼 `/api/share/*`。注意 CSP `connect-src 'self'`——若页面仍能在 rebound 源加载，改基址反而会被 CSP 拦住；**因此第 1 步的分发拒绝是主修复**，脚本改写不能单独充当控制。
5. 不要为了让改写后的基址在 rebound 源上可用而放宽 `connect-src`。

```kotlin
suspend fun dispatch(request: LinkShareHttpRequest): LinkShareHttpResponse {
    if (isRejectedLinkShareHost(request)) {
        return LinkShareHttpResponse.bytes(statusCode = 403).withCommonHeaders(request)
    }
    val corsPreflight = LinkShareHttpHeaderPolicies.corsPreflightResponse(
        request,
        advertisedHostProvider.get(),
    )
    ...
}

private fun isRejectedLinkShareHost(request: LinkShareHttpRequest): Boolean {
    val host = normalizeHostHeader(request.host) ?: return true
    return !isAdvertisedOrLoopbackHost(host, advertisedHostProvider.get())
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun linkShareDispatchRejectsUnadvertisedHostBeforeServingPageOrApi() = runBlocking(Dispatchers.IO) {
    withTestKoin {
        withSharedLocalFile("notes.txt", "hello".encodeToByteArray()) { root, state, sessionToken ->
            val dispatcher = dispatcher(
                state = state,
                advertisedHosts = setOf("192.168.1.20", "127.0.0.1"),
            )
            val page = dispatcher.dispatch(
                authorizedRequest(
                    rawUri = "/${root.name}",
                    sessionToken = sessionToken,
                ).copy(host = "share.example", port = 1204)
            )
            val api = dispatcher.dispatch(
                authorizedRequest(
                    rawUri = "/api/share/upload-permission/request",
                    sessionToken = sessionToken,
                    method = "POST",
                ).copy(host = "share.example", port = 1204)
            )
            val advertised = dispatcher.dispatch(
                authorizedRequest(
                    rawUri = "/${root.name}",
                    sessionToken = sessionToken,
                ).copy(host = "192.168.1.20", port = 1204)
            )

            assertEquals(403, page.statusCode)
            assertEquals(403, api.statusCode)
            assertFalse("data-link-share-http-base-url" in page.bodyText())
            assertEquals(200, advertised.statusCode)
        }
    }
}

@Test
fun linkShareScriptsDoNotBuildApiUrlsFromLocationOrigin() {
    val index = readBundledShareScript("share/share-index.js")
    val upload = readBundledShareScript("share/share-upload.js")
    assertFalse("window.location.origin" in index)
    assertFalse("window.location.origin" in upload)
    assertContains(upload, "httpBaseUrl")
}
```

测试只断言非广告 Host 被拒绝、广告 Host 仍 200。不要对真实局域网地址做 DNS 翻转或往分享口发送业务流量。

</details>

---

## FS-39：Web `localStorage` 明文持久化 Pro Token、Access Key 与包装密钥

* 严重度：中
* 类别：insecure_data_storage
* 修复状态：已修复
* 位置：
  - `core/src/jsMain/kotlin/com/folderspan/Platform.js.kt` 与 `core/src/wasmJsMain/kotlin/com/folderspan/Platform.wasmJs.kt`（`createSettings()` 直接 `StorageSettings()`，约 15–27 行，无 `SensitiveSettings`）
  - `app/shared/src/commonMain/kotlin/com/folderspan/App.kt`（`initializeProAuthSession(settings)` 约 184–190、355 行，Web 与原生共用）
  - `proMain/kotlin/com/folderspan/pro/core/datastore/AuthSessionStore.kt`（`save` 把 `auth.accessToken` / `auth.refreshToken` 写入 `Settings`，约 35–43、81–88 行）
  - `core/src/commonMain/kotlin/com/folderspan/settings/SensitiveSettingKeys.kt`（Desktop/iOS 把 `auth.*`、`pro.*`、Access Key、DEK、PKCS12 口令分流到金库；Web 未使用）
  - `core/src/commonMain/kotlin/com/folderspan/utils/DataEncryptionKey.kt`（`getOrCreateEncoded` / `replaceFromRemote` 把 `settings.crypto.key` 写入同一 `Settings`，约 30–46 行）
  - 对照：[敏感路径清单](sensitive-files-and-directories.md) 只写 Web localStorage「可能包含设备 ID 与连接配置」，未覆盖会话与包装密钥
* 置信度：0.90

### 描述

Desktop 用 `FileAesGcmSecretVault`，iOS 用 Keychain `ThisDeviceOnly`，Android 用 Tink AEAD。Web 的 `createSettings()` 没有这条分流，russhwolf `StorageSettings` 把键原样写进浏览器 `localStorage`。

`initializeProAuthSession` 在 `commonMain`，Web 登录后同样走 `SettingsAuthSessionStore.save()`。随后 `pullProDeviceSettingsIfLoggedIn` 可按白名单写入 `settings.fileShare.accessKey` 和 `settings.crypto.key`。DevTools 里键名即可识别，无需解金库。

这不是「浏览器只能用 localStorage」的产品必然：缺少的是 WebCrypto 包装或至少把长寿命 refresh token / DEK 留在内存。敏感清单把 Web 存储说成设备 ID 与连接配置，低估了实际落盘的会话材料。

### 影响

- 共享电脑、浏览器配置同步、扩展、同源 XSS 可直接读出 Pro 会话。
- 若该浏览器已同步文件分享 Access Key，可读 `X-FolderSpan-Key` 打本机/局域网分享（密钥开启时）。
- DEK 一旦落盘，同一配置目标下的网盘 / TURN 密文可被解开。Web SQL.js 库在内存里，刷新后库没了，但 DEK 仍在 `localStorage`。

### 利用场景

用户在 `webApp` 登录 Pro。另一人打开同一浏览器配置，或恶意扩展读取 `localStorage` 中的 `auth.accessToken` / `auth.refreshToken`，冒充该账号同步设置。若曾同步过文件分享 Access Key，还可访问分享服务。

<details>
<summary>修复方案</summary>

1. Web 为 `auth.*`、`pro.*`、`settings.fileShare.accessKey`、`settings.crypto.key` 增加包装层：用 Web Crypto AES-GCM，密钥放在不可导出的 `CryptoKey`（或至少不把 refresh token / DEK 以明文键名持久化）。
2. 不要在 Web 上同步文件分享 Access Key 与包装密钥，除非用户明确选择「在此浏览器保存凭据」。
3. 更新 [敏感路径清单](sensitive-files-and-directories.md)，写明 Web 实际持久化的会话与密钥键。
4. 退出登录必须删除这些键，不能只清内存 `SessionManager`。

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun webSettingsMustNotPersistSensitiveKeysInRawStorageSettings() {
    val keys = listOf(
        "auth.accessToken",
        "auth.refreshToken",
        SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY,
        SettingsUtils.KEY_CRYPTO_KEY,
    )
    keys.forEach { key ->
        assertTrue(SensitiveSettingKeys.isSensitive(key), key)
    }
}

@Test
fun webCreateSettingsWrapsSensitiveKeys() {
    val settings = createSettings()
    settings.putString("auth.accessToken", "secret-token")
    val raw = kotlinx.browser.window.localStorage.getItem("auth.accessToken")
    assertTrue(raw == null || raw != "secret-token")
    assertEquals("secret-token", settings.getString("auth.accessToken", ""))
}
```

第二条在 js/wasm 测试里跑。不要把真实 Token 写进夹具或日志。

</details>

---

## FS-40：金库写入不清除 Preferences / NSUserDefaults 明文残留

* 严重度：中
* 类别：insecure_data_storage / leftover_secret
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/settings/SensitiveSettings.kt`（`putString` 约 38–44 行：敏感键只 `vault.put` 然后 return；只有 `remove` / `clear` 会碰明文）
  - `core/src/jvmMain/kotlin/com/folderspan/Platform.jvm.kt`（明文后端 `Preferences.userRoot()`，约 34–39 行）
  - `core/src/iosMain/kotlin/com/folderspan/Platform.ios.kt`（明文后端 `NSUserDefaults` 域 `FolderSpan`，约 18–21 行）
  - `core/src/commonTest/kotlin/com/folderspan/settings/SensitiveSettingsTest.kt`（`leftoverPlaintextSecretsAreIgnored` 约 13–26 行把「明文残留仍在」写成正向断言；没有启动时刮除测试）
* 置信度：0.88

### 描述

金库分流之后读敏感键只看 vault，这是对的。`newSensitiveWritesDoNotStayInPlaintextStorage` 也保证**新写入**不会进明文。缺口是迁移：

```kotlin
override fun putString(key: String, value: String) {
    if (SensitiveSettingKeys.isSensitive(key)) {
        vault.put(key, value)
        return
    }
    plaintext.putString(key, value)
}
```

没有 `plaintext.remove(key)`，启动时也没有把明文里的 `auth.*` / Access Key / `settings.crypto.key` 清掉。`leftoverPlaintextSecretsAreIgnored` 明确断言：应用读不到金库里的空值，明文里的 `leftover-access-key` 却还在。

Desktop 明文落在 Java userRoot（Linux 上常见 `~/.java/.userPrefs/prefs.xml`，权限常为 0644）。iOS 明文落在可备份的 `FolderSpan` UserDefaults。引入金库之前登录过的安装，旧 Token / Access Key 会一直留着；之后每次登录只更新金库。

### 影响

- 升级前写过敏感键的安装，Token / Access Key 仍在明文 Preferences / plist 里。
- 同机其他用户（家目录可遍历时）或 iCloud 备份可以拿到**旧**会话材料；Keychain 的 `ThisDeviceOnly` 保护不到这份残留。
- 清理账号若只清金库、没走到 `remove`/`clear`，残留还会留下。

### 利用场景

用户在引入 `SensitiveSettings` 之前登录过，Access Key 或 Token 写进了 Java Preferences。升级后应用只把新 Token 写入 `secure-settings/values.v1`。能读 `prefs.xml` 的人仍能得到旧 Access Key / Token，直到服务端吊销。

<details>
<summary>修复方案</summary>

1. `putString` 在写入金库后立刻 `plaintext.remove(key)`。
2. `SensitiveSettings` 构造或首次 `hasKey`/`getString` 时，对 `SensitiveSettingKeys` 覆盖的键做一次性刮除（明文有、金库无则删除明文；两边都有则只删明文）。
3. 把 `leftoverPlaintextSecretsAreIgnored` 改成：读隔离仍然成立，**并且**构造后明文不再持有该键。

```kotlin
override fun putString(key: String, value: String) {
    if (SensitiveSettingKeys.isSensitive(key)) {
        vault.put(key, value)
        plaintext.remove(key)
        return
    }
    plaintext.putString(key, value)
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun leftoverPlaintextSecretsAreScrubbedOnReadAndWrite() {
    val plaintext = createInMemorySettings(
        SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY to "leftover-access-key",
    )
    val vault = MemoryStringSecretVault()
    val settings = SensitiveSettings(plaintext, vault)

    assertEquals("", settings.getString(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY, ""))
    assertFalse(plaintext.hasKey(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY))

    settings.putString("auth.accessToken", "new-token")
    assertEquals("new-token", vault.get("auth.accessToken"))
    assertFalse(plaintext.hasKey("auth.accessToken"))
}
```

不要把真实 Access Key 写进测试。

</details>

---

## FS-41：Desktop `folderspan.db` 与 `tls-identity` 未强制 owner-only 权限

* 严重度：中
* 类别：insecure_file_permissions
* 修复状态：已修复
* 位置：
  - `app/shared/src/jvmMain/kotlin/com/folderspan/service/DriverFactory.jvm.kt`（`dbFile.parentFile?.mkdirs()` 后直接 `jdbc:sqlite:`，约 12–15、42–44 行，无 POSIX 限制）
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/tls/JvmTlsIdentityFileStore.kt`（目录 `mkdirs()` 约 53–55、154–160 行；`storage.salt` 仅新建时 `writeOwnerOnlyFile`；`.fmi` 经 `File.createTempFile` + `ATOMIC_MOVE`，约 128–140、172–178 行，移动后不再 `setPosixFilePermissions`）
  - `core/src/jvmMain/kotlin/com/folderspan/settings/FileAesGcmSecretVault.kt`（已有 `master.key` 再次启动只读入，不重新 0600，约 57–69 行；对照 `persistLocked` 每次都会 `restrictOwnerOnly`）
  - 对照：同模块 `restrictOwnerOnly`；`proMain/.../ProHttpCachePaths.jvm.kt` 对 `pro_http_cache` 已 0700/0600
* 置信度：0.87

### 描述

文档与金库实现要求敏感目录 0700、文件 0600。实际不一致：

1. `~/.local/share/FolderSpan`（及 macOS/Windows 对应根）由 `mkdirs()` 创建，默认 0755。
2. `folderspan.db` 由 JDBC SQLite 按 umask 创建，Linux 上常见 0644。口令列已是 AES-GCM 密文，但库里仍有明文的网盘用户名/主机、TURN URL、书签路径、设备记录。
3. `tls-identity/` 只用 `mkdirs()`。`storage.salt` 在**首次创建**时 0600；`.fmi` 依赖 `createTempFile` 的默认权限，写入路径没有 `restrictOwnerOnly`。Windows 上 `setPosixFilePermissions` 失败后，盐文件的 `writeOwnerOnlyFile` 也没有 `setReadable` 回退（金库的 `restrictOwnerOnly` 才有）。
4. 已存在的 `master.key` 再次启动不重新 0600。

同机威胁模型下，这与已按 0700/0600 保护的 `secure-settings/`、`pro_http_cache/` 不对齐。Android `filesDir`、iOS 沙箱不靠 POSIX，本条针对 Desktop。

### 影响

- 家目录为 755 的共享工作站上，其他 UID 可读取 `folderspan.db`（及 WAL），枚举已保存的 SFTP/SMB 主机与用户名。
- 身份目录若可列、身份文件未落到 0600，配合可读的 `storage.salt` 与公开 `deviceId`，可离线派生存储密钥。

### 利用场景

Linux 工作站家目录 755。用户运行 FolderSpan 后，另一 UID 读取 `~/.local/share/FolderSpan/folderspan.db`，得到已保存的网络盘主机与用户名。

<details>
<summary>修复方案</summary>

1. 应用数据根、`tls-identity/`、数据库文件在创建后调用与金库相同的 `restrictOwnerOnly`。
2. `.fmi` 在 `ATOMIC_MOVE` 之后对目标文件再设 0600；盐文件与身份文件在每次启动时重申权限。
3. JDBC 打开数据库后对 `folderspan.db`、`-wal`、`-shm` 设 0600。
4. Windows 回退到 `setReadable(false, false)` / `setWritable(true, true)`，与金库一致。

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun desktopDatabaseAndTlsIdentityAreOwnerOnly() {
    assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
    val appData = resolveDesktopApplicationDataDirectory()
    val db = appData.resolve("folderspan.db")
    val identityDir = appData.resolve("tls-identity")
    fun posix(path: Path) = Files.getPosixFilePermissions(path)
    if (Files.isRegularFile(db)) {
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            posix(db),
        )
    }
    if (Files.isDirectory(identityDir)) {
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            posix(identityDir),
        )
    }
}
```

测试只断言权限位。不要读取或打印数据库内容、身份密文。

</details>

---

## FS-42：iOS 数据库与 TLS 身份未排除 iCloud / iTunes 备份

* 严重度：中
* 类别：backup_exposure
* 修复状态：已修复
* 位置：
  - `app/shared/src/iosMain/kotlin/com/folderspan/service/DriverFactory.ios.kt`（`folderspan.db` 写在 `NSHomeDirectory()` 根下，约 10–18 行）
  - `core/src/iosMain/kotlin/com/folderspan/service/http/tls/IosTlsIdentityFileStore.kt`（`Library/Application Support/FolderSpan/tls-identity`，`createDirectoryAtPath(..., attributes = null)`，约 39–63 行）
  - 全仓无 `NSURLIsExcludedFromBackupKey` / `isExcludedFromBackup`
  - 对照：`core/src/iosMain/kotlin/com/folderspan/settings/IosKeychainStore.kt` 已用 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`；Android 已 `allowBackup=false` 且 `data_extraction_rules` 排除 cloud-backup 与 device-transfer
* 置信度：0.86

### 描述

Token、DEK、deviceId 在 Keychain 里，且 `AfterFirstUnlockThisDeviceOnly`：不进 iCloud Keychain，也不随备份迁到新机。文件侧没有对齐。

`NativeSqliteDriver` 的 `basePath` 是沙箱根，`folderspan.db` 会进标准 iCloud/iTunes 备份（系统排除的是 `Caches`/`tmp`）。TLS 身份在 `Library/Application Support`，默认同样备份。目录创建未设 `NSFileProtectionComplete`，也未标排除备份。

没有 Keychain 里的 deviceId/DEK 时，备份里的 `.fmi` 与口令密文很难解开；但 SQLite 里的主机、用户名、路径、TURN URL 是明文。FS-40 的 UserDefaults 残留若存在，也会进同一份备份。

### 影响

- 未加密的本地 iTunes 备份、被盗的 iCloud 账号、设备迁移，会带出网盘/设备元数据与 TLS 密文。
- 新机还原时 ThisDeviceOnly Keychain 不跟着走，身份会重新生成；旧机或能解密该备份的人仍能读库里的明文元数据。
- 与 MASVS「敏感数据不得随备份泄漏」以及 Android 已做的备份关闭不对齐。

### 利用场景

用户开启 iCloud 备份。攻击者取得该 Apple ID 的备份。Keychain 项因 ThisDeviceOnly 不在备份里，解不开网盘口令密文，但可以读 `folderspan.db` 中的 SMB/SFTP 主机与用户名、WebRTC 房间与 TURN URL。

<details>
<summary>修复方案</summary>

1. 对 `folderspan.db`（含 `-wal`/`-shm`）和 `tls-identity/` 设置 `NSURLIsExcludedFromBackupKey = true`。
2. 目录与文件使用 `NSFileProtectionCompleteUntilFirstUserAuthentication` 或更严。
3. 数据库放到 `Library/Application Support/FolderSpan/`，不要放在沙箱根，便于统一排除备份。
4. 回归：创建后读取资源值，断言排除备份为真。

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun iosDatabaseAndTlsIdentityAreExcludedFromBackup() {
    val db = PathUtils.getAppPath().trimEnd('/') + "/folderspan.db"
    val identity = PathUtils.getAppPath().trimEnd('/') +
        "/Library/Application Support/FolderSpan/tls-identity"
    assertTrue(isExcludedFromBackup(db))
    assertTrue(isExcludedFromBackup(identity))
}
```

`isExcludedFromBackup` 读 `NSURLIsExcludedFromBackupKey`。不要把备份包或数据库内容写进测试输出。

</details>

---

## FS-43：网盘目录下载用字符串拼接落盘，恶意服务器可用 `..` 写出用户选定目录

* 严重度：高
* 类别：directory_traversal / zip_slip
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/data/main/network/Network.kt`（`copyTo` 目录下载分支约 714–889 行：`buildLocalPath` 用 `removePrefix` + 分隔符替换拼接；`processDirectoryEntry` / `downloadFileEntry` 不对 `localPath` 调 `isPathWithinRoot` 或 `SensitiveFileAccessPolicy.deniedException`）
  - `core/src/commonMain/kotlin/com/folderspan/service/network/WebDavNetworkClient.kt`（`normalizeRemotePath` 约 419–429 行只保证前导 `/`，不折叠 `..`；`stripBasePath` 约 499–509 行前缀不匹配时原样返回 href；`parsePropfindResponse` 把解码后的路径直接当 `NetworkFileEntry.path`）
  - `core/src/jvmMain/kotlin/com/folderspan/service/network/SftpNetworkClient.kt`、`FtpNetworkClient.kt`、`SmbNetworkClient.kt`（`joinPath` 把列表名直接接到当前目录后；`download` 用 `FileSystem.SYSTEM.sink(localPath.toPath())`，跟随叶子符号链接）
  - `core/src/commonMain/kotlin/com/folderspan/service/network/KtorS3NetworkClient.kt`（`list` 的 `commonPrefixes` / `contents` 同样把远端 key 原样变成 `/...` 路径，不拒绝 `..`）
  - 对照已落地路径：`FolderSpanArchiveCodec.normalizeRelativePath` 拒绝 `..`、`\`、绝对路径；`PathUtils.isPathWithinRoot` 已做规范化 + 不跟随符号链接；FS-30 只修了 **Local→Network 上传** 的敏感路径与叶子 symlink，目录下载这条路径未覆盖
* 置信度：0.90

### 描述

用户把网盘目录复制到本机时，`Network.copyTo` 先 `client.list` 递归收集远端条目，再用远端路径减去 `srcRoot` 得到相对段，拼到用户选定的 `destRoot`：

```kotlin
fun buildLocalPath(remotePath: String): String {
    val relative = remotePath.removePrefix(srcRoot)
    return if (destRoot.endsWith(localSeparator)) {
        destRoot + relative.replace(pathSeparator, localSeparator)
    } else {
        destRoot + localSeparator + relative.replace(pathSeparator, localSeparator)
    }
}
```

`removePrefix` 是字符串前缀删除，不是路径规范化。相对段里的 `..`、绝对路径、多余分隔符都原样进入 `FileUtils.createFolder` / `client.download`。`collectDirectoryEntriesAdaptive` 的 `normalizeTraversalPath` 只统一分隔符并去掉尾部斜杠，同样不折叠 `..`。目录下载分支也不调 `isPathWithinRoot`，也不对落盘路径做 `SensitiveFileAccessPolicy.deniedException`。

远端列表是否能吐出 `..`，取决于协议：

| 协议 | 列表如何组路径 | 是否过滤 `..` |
| --- | --- | --- |
| WebDAV | PROPFIND `href` → `stripBasePath` → `decodePath` → `normalizeRemotePath` | 否。href 可含 `../`；前缀不匹配时整条路径原样返回 |
| SFTP / FTP / SMB | `joinPath(current, filename)` | 只过滤名为 `.` / `..` 的**条目本身**；名为 `foo/../bar` 或 WebDAV 风格的多段 href 不会被挡 |
| S3 | `/$dirKey` / `/$normalizedKey` | `contents` 用 delimiter 丢掉含 `/` 的相对段，但 `commonPrefixes` 仍可含 `..` |

因此恶意或被劫持的网盘不必依赖客户端主动请求 `../secret`：只要列表响应里出现带 `..` 的子路径，下载就会按该相对段落盘。

WebDAV 下载走 `FileUtils.createFile` / `writeBytes`（`NoFollowFileChannels`，不跟随叶子 symlink）。SFTP / FTP / SMB 的 `download` 直接 `FileSystem.SYSTEM.sink(localPath.toPath())`，会跟随已存在的叶子符号链接。FS-30 给上传加了 `openLocalSourceNoFollow` 和敏感路径拒绝；下载这条 sink 路径没有对齐。

单文件 Network→Local 下载用用户选定的 `destFileSimpleInfo.path`，不经 `buildLocalPath`，所以穿越主要发生在**目录复制**。

### 影响

- 用户把一个看起来正常的网盘目录下载到例如 `~/Downloads/share/` 时，恶意服务器可把文件写到该目录之外，包括家目录、应用数据根、已打开的分享目录。
- 若落盘路径命中 `folderspan.db`、`secure-settings/`、已授权链路分享根等，可覆盖机密或把恶意文件送进正在分享的目录。
- SFTP/FTP/SMB 若目标叶子已是符号链接，还会把内容写到链接指向处，扩大到任意本地路径。
- 不需要伪造用户本机路径选择：用户只选了合法目标目录。

### 利用场景

用户添加一台不可信或证书校验被绕过的 WebDAV / SFTP 服务器，选中某个目录下载到 `~/Downloads/inbox/`。服务器在 PROPFIND / `readdir` 里返回带上级目录段的子项。客户端把该相对路径拼到 `inbox` 后调用 `createFolder` / `download`，文件出现在 `~/Downloads/` 之外。同机恶意进程若事先在目标处放了叶子 symlink，SFTP/FTP/SMB 的 sink 还会跟着写。

<details>
<summary>修复方案</summary>

1. **落盘前用与归档相同的相对路径规则。** 对 `removePrefix` 之后的相对段调用（或抽出）`FolderSpanArchiveCodec.normalizeRelativePath` 一类检查：拒绝 `..`、`.`、空段、反斜杠、绝对路径。
2. **再用 `PathUtils.isPathWithinRoot(Allowed, destRoot, localPath, allowNonExistentLeaf = true)`。** 规范化后仍必须落在用户选定的目标目录内。
3. **目录下载的每个 `localPath` 走 `SensitiveFileAccessPolicy.deniedException`。** 与 FS-30 上传侧对齐，避免穿越后覆盖应用机密。
4. **SFTP/FTP/SMB `download` 改为 `NoFollowFileChannels` / `openLocalSourceNoFollow` 对偶的不跟随 sink**，不要用 `FileSystem.SYSTEM.sink`。
5. WebDAV `normalizeRemotePath` / `stripBasePath` 在列表阶段就拒绝 `..`；前缀不匹配的 href 丢弃，不要当成本次列表的子项。

```kotlin
fun buildLocalPath(remotePath: String): String {
    val relative = FolderSpanArchiveCodec.normalizeRelativePath(
        remotePath.removePrefix(srcRoot).replace(pathSeparator, "/"),
    )
    val localPath = destRoot.trimEnd('/', '\\') + localSeparator +
        relative.replace("/", localSeparator)
    check(
        PathUtils.isPathWithinRoot(
            FileAccessPermission.Allowed,
            destRoot,
            localPath,
            allowNonExistentLeaf = true,
        )
    ) { "网盘下载路径超出目标目录" }
    SensitiveFileAccessPolicy.deniedException(localPath)?.let { throw it }
    return localPath
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun networkDirectoryDownloadRejectsParentRelativeRemotePaths() {
    val destRoot = "/tmp/folderspan-inbox"
    val srcRoot = "/share/"
    fun localPath(remote: String): String? = runCatching {
        val relative = FolderSpanArchiveCodec.normalizeRelativePath(
            remote.removePrefix(srcRoot),
        )
        val candidate = "$destRoot/$relative"
        check(
            PathUtils.isPathWithinRoot(
                FileAccessPermission.Allowed,
                destRoot,
                candidate,
                allowNonExistentLeaf = true,
            )
        )
        candidate
    }.getOrNull()

    assertEquals("$destRoot/notes.txt", localPath("/share/notes.txt"))
    assertNull(localPath("/share/../outside.txt"))
    assertNull(localPath("/share/sub/../../outside.txt"))
    assertNull(localPath("/etc/passwd"))
}

@Test
fun sftpDownloadDoesNotFollowLeafSymbolicLink() {
    assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
    val dir = Files.createTempDirectory("folderspan-net-dl")
    val target = Files.createFile(dir.resolve("secret"))
    val leaf = dir.resolve("alias")
    Files.createSymbolicLink(leaf, target)
    assertFails {
        NoFollowFileChannels.openReadWrite(leaf.toString(), create = false)
    }
}
```

测试只断言相对路径被拒绝、不跟随叶子符号链接。不要连接真实恶意服务器，也不要把穿越后的文件内容写进测试输出。

</details>

---

## FS-44：桌面编辑器备份、任务运行态与设备日志仍写入全局 tmpdir

* 严重度：中
* 类别：insecure_temp_file / local_information_disclosure
* 修复状态：已修复
* 位置：
  - `core/src/jvmMain/kotlin/com/folderspan/utils/PathUtils.jvm.kt`（`getCachePath()` = `System.getProperty("java.io.tmpdir")`，约 138 行）
  - `core/src/jvmMain/kotlin/com/folderspan/utils/PathUtils.jvm.kt`（`createDirectoryIfNotExists` 约 242–249 行：`mkdirs()`，无 POSIX 权限）
  - `core/src/commonMain/kotlin/com/folderspan/editor/EditorBackupStore.kt`（`backupDirectory` 约 135–139 行）
  - `core/src/commonMain/kotlin/com/folderspan/editor/EditorRecoveryJournalStore.kt`（`recoveryDirectory` 约 94–98 行）
  - `core/src/commonMain/kotlin/com/folderspan/editor/EditorLineIndexCache.kt`（`cacheDirectory` 约 66–70 行）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/file/FileEditorContentAccess.kt`（`buildEditorCachePath` 约 711–722 行）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/main/TaskRuntimePersistenceStore.kt`（`buildCacheChildPath("task-runtime")` 约 735–743、676–678 行）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/main/TaskFailureResultStore.kt`（`task-failure-results` 约 143–151 行）
  - `core/src/commonMain/kotlin/com/folderspan/utils/DeviceRequestLogUtils.kt`（`device_logs/<deviceId>.txt` 约 18–19 行）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/file/FileStateCopyCoordinator.kt`（`sync-stage` 约 999–1005 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/mcp/file/RemoteFileEndpointGateways.kt`（`mcp-file-staging` 约 271–275 行）
  - 对照：FS-11 已把 `pro_http_cache` 迁出 tmpdir 并设 0700/0600；FS-26 只修了**剪贴板图片**暂存。第三轮已记录编辑器备份落 tmpdir，但当时只作为 FS-11 的同根说明，**没有单独开洞，也没有落地修复**
* 置信度：0.88

### 描述

JVM 上几乎所有「缓存」都经 `PathUtils.getCachePath()`。该值是进程级 `java.io.tmpdir`（Linux 通常 `/tmp`，sticky bit 只阻止删别人的文件，**不阻止读** umask 留下的 0755/0644）。`createDirectoryIfNotExists` 只 `mkdirs()`；`FileUtils.createFile` / `writeBytes` 也不设 0600。仓库里持续 `setPosixFilePermissions` 的只有 TLS 身份目录和已修复的 `secure-settings` / `pro_http_cache`。

因此下列明文会落到同机其他用户可读的全局临时目录：

| 目录 | 内容 |
| --- | --- |
| `file-editor-backups/` | 正在编辑的文件正文与范围备份 |
| `file-editor-recovery/` | 崩溃恢复日志与待恢复片段 |
| `editor-content/` | 分页内容缓存 |
| `file-editor-line-index/` | 大文件行索引 |
| `task-runtime/` | 传输队列、进度、路径 |
| `task-failure-results/` | 失败路径与错误上下文 |
| `device_logs/` | 按设备 ID 保存的请求路径 |
| `sync-stage/` | 同步暂存，可能是完整用户文件 |
| `mcp-file-staging/` | MCP 远端文件暂存 |

`SensitiveFileAccessPolicy` 把这些目录标为 `sensitive`，只挡住 Device/MCP 通用文件接口，**不改变 `/tmp` 的 POSIX 权限**。Android `getCachePath()` 是应用 `cacheDir`，iOS 是沙箱缓存，不在本条范围内。

这不是重开 FS-26：第五轮修的是剪贴板图片写入 tmpdir。也不是重开 FS-11：第三轮修的是 Pro HTTP 缓存的硬编码密钥与缓存根。第三轮正文把编辑器备份写成 FS-11 的「同根存储问题」，修复方案第 4 点要求迁出 tmpdir，但落地只动了 `pro_http_cache`。本条把**仍未迁走的运行态与编辑器缓存**单独登记。

### 影响

- 同机其他账户或能读 `/tmp` 的进程，可取出用户正在编辑的文件正文、传输任务路径、设备请求日志、同步/MCP 暂存副本。
- `device_logs` 文件名含设备 ID，内容含请求路径，便于拼出局域网设备拓扑。
- 多用户 Linux 桌面、共享 CI 机器、被其它本地进程读取 `/tmp` 的场景均可触发；不需要应用漏洞或登录态。

### 利用场景

用户在桌面端打开网盘或本地大文件进行编辑，并跑过一次目录复制。同机另一账户列出 `/tmp/file-editor-backups`、`/tmp/task-runtime`、`/tmp/device_logs`，读取明文备份与任务快照。不需要 ptrace，也不需要改应用代码。

<details>
<summary>修复方案</summary>

1. **JVM `getCachePath()` 改到应用私有数据目录下的 `cache/`**（与 `pro_http_cache` 同一根），不要再用 `java.io.tmpdir`。
2. 该 `cache/` 及子目录创建时 `0700`，文件 `0600`；启动时对已存在项重申权限。
3. 若必须兼容旧路径，启动时把上述目录从 tmpdir 迁走或删除，不要继续追加写入。
4. `createDirectoryIfNotExists` / `createFile` 在 POSIX 上默认 owner-only，避免以后再漏设。

```kotlin
actual fun getCachePath(): String {
    val root = resolveDesktopApplicationDataDirectory().resolve("cache")
    Files.createDirectories(root)
    runCatching {
        Files.setPosixFilePermissions(
            root,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }
    return root.toString()
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun jvmCachePathIsNotSystemTmpdirAndIsOwnerOnly() {
    assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
    val cache = Path.of(PathUtils.getCachePath())
    val tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
    assertFalse(cache.toRealPath().startsWith(tmp), "cache must leave java.io.tmpdir")
    assertEquals(
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        ),
        Files.getPosixFilePermissions(cache),
    )
}

@Test
fun editorBackupDirectoryIsNotWorldReadable() {
    assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
    val backup = Path.of(PathUtils.getCachePath(), "file-editor-backups")
    if (Files.isDirectory(backup)) {
        val perms = Files.getPosixFilePermissions(backup)
        assertFalse(PosixFilePermission.OTHERS_READ in perms)
        assertFalse(PosixFilePermission.GROUP_READ in perms)
    }
}
```

测试只断言缓存根离开 `tmpdir`、权限为 owner-only。不要读取或打印备份正文、任务快照、设备日志。

</details>

---

## 已检查、不作为本轮新漏洞

| 主题 | 结论 |
| --- | --- |
| FS-01～FS-37 | 源码与测试表明已落地或（FS-06）为误报，不重复开洞。本轮 FS-38 只覆盖 FS-21 **未改到的链路分享路由分发**，不重开 CORS 比较或页面绝对 URL 改写。FS-39～FS-42 是存储面新问题，不重开已落地的 Tink / Keychain / 金库包装方案。FS-43 不重开 FS-30（那条只修了 Local→Network 上传）。FS-44 不重开 FS-11（只迁了 `pro_http_cache`）或 FS-26（只修了剪贴板图片）。 |
| 设备 API Host 白名单 | `RawHttpApiDispatcher.dispatch` 对 OPTIONS 与正式请求都走 `isRejectedPrivateWebRequest`；`httpWebRtcPreflightRejectsUnaadvertisedHostnameEvenWhenPortMatchesOnRawApi` 期望 403。 |
| MCP Host 白名单 | `McpHttpSecurityPolicy.allows` 在鉴权前拒绝非广告 Host。FS-16 之后 LAN 只接受 HTTPS。 |
| 链路分享 CORS / 页面基址 | `isSameOrigin` 要求 Origin 与 Host 都在广告/loopback 集合；`resolvePublicHttpHost` 把未校验 Host 改写成广告地址。这是 FS-21 已落地部分。 |
| 会话头跳过 clientId | `X-FolderSpan-Link-Session` 不绑 client cookie 是下载脚本的产品选择；`ShareTokenFingerprint` 仍要求非空 IP 与 UA。不新开 ID。 |
| Cookie `Secure=false`（明文 HTTP） | 产品局域网分享设计，前几轮已记录。 |
| `Service-Worker-Allowed: /` | 注册 scope 是 `./`（`/static/streamsaver/`）。第六轮已排除。 |
| CORS `Access-Control-Request-Headers` 回显 | 先过广告地址同源白名单；CRLF 已剥。第六/八轮已排除。 |
| StreamSaver `postMessage(..., '*')` | 供应商 mitm.html 行为；SW 作用域已记录。不新开 ID。 |
| CSP `frame-ancestors 'self'` vs `X-Frame-Options: DENY` | 非 mitm 页 XFO 为 DENY，clickjacking 面已覆盖；mitm 为 SAMEORIGIN 是 StreamSaver 需要。 |
| 口令页 CSRF | `SameSite=Lax` + `form-action 'self'`；上传要求 `X-API-Request`。第六轮已排除。 |
| 开放重定向 | `sanitizeLinkShareRedirect` 拒绝非相对路径、`//`、反斜杠与控制字符。 |
| iOS Keychain | 服务 `com.folderspan.secure-settings`，`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`，不进 iCloud。 |
| iOS ATS / 深链 | `Info.plist` 无 `NSAllowsArbitraryLoads`；`com.folderspan` 只把 `file:` URL 交给文档打开协调器。 |
| iOS Share Extension | 写入 app-group `share-import/{staging,pending}/<uuid>/`，文件名剥 `/`、`:` 与控制字符。 |
| Desktop `secure-settings` 包装方案 | AES-GCM + AAD，目录 0700 / 文件 0600。第五轮已把「盐与密文同目录」列为文档化设计。本轮 FS-41 只登记**未持续 chmod 的 db/tls-identity**，不重开包装方案。 |
| Android 备份 | `allowBackup=false`；`backup_rules` / `data_extraction_rules` 排除 file/database/sharedpref/external。Tink AES-256-GCM + Keystore。 |
| iOS Keychain 可访问性 | `AfterFirstUnlockThisDeviceOnly`，未设 `kSecAttrSynchronizable`。条目留在应用自身 keychain。正确收紧；备份缺口见 FS-42。 |
| Android `ACTION_VIEW` MIME | FS-06 误报，不重开。 |
| ShizukuProvider `exported=true` | Shizuku 常规形态，权限 `INTERACT_ACROSS_USERS_FULL`。 |
| Web SQL.js | 库在 Worker 内存中。Web 持久化问题在 `localStorage`（FS-39），不在 SQLite 文件。 |
| `NetworkDrive.password` | 写入前 `SymmetricCrypto.encrypt`；extras 同样加密。列类型 TEXT 不代表明文落库。 |
| GNOME `monitors.xml` XXE | `DocumentBuilderFactory` 未硬化，但输入仅本机 `~/.config/monitors.xml`，不是网络或上传 XML。 |
| 归档 zip-slip | `FolderSpanArchiveCodec.normalizeRelativePath` 拒绝 `..`、`\`、绝对路径。第八轮已排除。本轮 FS-43 是网盘目录下载**没有**复用该规范化。 |
| 静态资源路径 | `normalizeLinkShareBundledResourcePath` 限制在 `files/share-file/` 前缀。 |
| HTML/SVG/XML 预览 | sandbox CSP；不支持则 `Content-Disposition: attachment`。 |
| ProcessBuilder | DPI / 登录开机项命令是固定本机工具，无用户输入拼接。 |
| WebSocket | 仅作 WebRTC 信令客户端；本仓库不托管未鉴权的分享 WebSocket 服务。 |
| 硬编码 AES `"1234567890123456"` | 仅用于把随机 UUID 变成不透明 Android `deviceId` 字符串；密文存 Tink DataStore，deviceId 本身会在发现协议中公开。不是存储密钥。 |
| 局域网明文 HTTP 分享 | 产品设计，前几轮已记录。FS-38 不因「LAN 可达」本身开洞，只覆盖非广告 Host 仍被当成分享源。 |
| FS-30 Local→Network 上传 | 已接入 `SensitiveFileAccessPolicy` 与 `openLocalSourceNoFollow`。本轮 FS-43 覆盖的是反向的 **Network→Local 目录下载** 拼接与 SFTP/FTP/SMB sink。 |
| FS-11 / FS-26 tmpdir | `pro_http_cache` 已迁出并 0700/0600；剪贴板图片已离开全局 tmpdir。编辑器备份、任务运行态、device_logs、sync-stage、mcp-file-staging 仍走 `getCachePath()`，见 FS-44。 |
| `PathUtils.isPathWithinRoot` | JVM/Android/iOS 实现会规范化且不跟随中间符号链接。网盘目录下载当前**没有调用**它。 |
| 单文件网盘下载 | `copyTo` 非目录分支使用用户选定的 `destFileSimpleInfo.path`，不经 `buildLocalPath`。穿越面在目录复制。 |
| Android / iOS 缓存根 | `getCachePath()` 分别是应用 `cacheDir` 与沙箱缓存，不在 FS-44 范围内。 |
| CORS / CSRF / SW（本轮独立复核） | 链路分享 `isSameOrigin` 要求 scheme、主机、端口一致且两边都在广告/loopback 集合；设备 API CORS 无 `Allow-Credentials`、不回显 ACRH、鉴权是 Bearer。上传/申请上传必须 `X-API-Request: true`。`POST /auth` 无 CSRF token，但口令即能力、`SameSite=Lax` 挡住跨站复用已有会话。`Service-Worker-Allowed: /` 只放宽最大 scope，`mitm.html` 实际 `register(..., { scope: './' })`，拦截面是 `/static/streamsaver/`。均不新开 ID。 |

残留硬化（不单开漏洞 ID，减小以后踩坑）：

1. 把 `Service-Worker-Allowed` 改成 `/static/streamsaver/`（或删掉），与注册 scope 对齐。
2. 链路分享 CORS 不要原样回显 `Access-Control-Request-Headers`，只用 `DEFAULT_CORS_ALLOW_HEADERS`。
3. 统一点击劫持头：常规页 `frame-ancestors 'none'` + `X-Frame-Options: DENY`，mitm 单独 `'self'` / `SAMEORIGIN`。
4. `POST /auth` 可走与上传 API 同一套 `isSameOrigin`，减少同 IP 其它端口上的口令尝试噪声。

## 修复优先级建议

1. **FS-43（高）**：网盘目录下载对相对路径做与归档相同的规范化，并用 `isPathWithinRoot` + 敏感路径拒绝；SFTP/FTP/SMB 下载改为不跟随叶子符号链接。
2. **FS-38（中）**：链路分享 `dispatch` 对齐设备 API / MCP，非广告 Host 直接 403；把 `pageConfigUsesAdvertisedHostInsteadOfUntrustedRequestHost` 改成拒绝断言。脚本去掉 `location.origin` 只能作为纵深防御，不能代替分发层拒绝。
3. **FS-44（中）**：JVM `getCachePath()` 迁到应用私有 `cache/`，目录 0700、文件 0600；覆盖编辑器备份/恢复、任务运行态、失败明细、设备日志、同步暂存与 MCP 暂存。
4. **FS-40（中）**：金库 `putString` 刮除明文；启动时迁移删除 Preferences / NSUserDefaults 历史敏感键。
5. **FS-41（中）**：Desktop 应用数据根、`folderspan.db`、`tls-identity` 对齐 `restrictOwnerOnly`。
6. **FS-39（中）**：Web 敏感键不要明文进 `localStorage`；至少停止在浏览器持久化 Access Key 与 DEK。
7. **FS-42（中）**：iOS 数据库与 TLS 身份排除备份，与 Keychain `ThisDeviceOnly`、Android `allowBackup=false` 对齐。

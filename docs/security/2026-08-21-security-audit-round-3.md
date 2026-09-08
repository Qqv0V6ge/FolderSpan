# FolderSpan 安全审计报告（第三轮）

- 审计日期：2026-08-21
- 审计范围：全仓（`app/`、`core/`、`proMain/`），非仅当前 PR diff
- 前置：
  - 第一轮见 [2026-08-21 全仓安全审计报告](2026-08-21-security-audit-report.md)（FS-01～FS-05 已修复，FS-06 误报）
  - 第二轮见 [2026-08-21 全仓安全审计报告（第二轮）](2026-08-21-security-audit-round-2.md)（FS-07～FS-10 已修复）
- 方法论：Claude Marketplaces 插件 `cybersecurity-skills@anthropic-cybersecurity-skills` v1.3.0
  覆盖技能：`conducting-api-security-testing`、`testing-api-security-with-owasp-top-10`、`testing-for-broken-access-control`、`performing-directory-traversal-testing`、`performing-ssrf-vulnerability-exploitation`、`testing-cors-misconfiguration`、`auditing-mcp-servers-for-tool-poisoning`、`performing-cryptographic-audit-of-application`、`exploiting-insecure-data-storage-in-mobile`、`testing-android-intents-for-vulnerabilities`、`exploiting-deeplink-vulnerabilities`、`conducting-mobile-app-penetration-test`
- 约束：本报告不含攻击载荷或 exploit PoC。折叠区内的「验证代码」均为防御性回归测试（断言安全属性），用于确认修复是否生效。
- 威胁模型：局域网邻机、同机恶意进程/网页、不可信 Android Intent、SFTP 中间人、已授权设备上的 token 盗用。本机文件分享服务按产品设计会对局域网可达。
- 本轮只登记相对前两轮**仍未修复的新问题**。已落地的 FS-01～FS-10、已排除项与产品明确文档化的同步行为不重复开洞。

## 审计发现总览

| ID | 严重度 | 类别 | 标题 | 状态 |
| --- | --- | --- | --- | --- |
| FS-11 | 中 | insecure_data_storage / cryptographic_weakness | Pro HTTP 缓存使用编译期硬编码 AES 密钥，JVM 密文落在系统临时目录 | 已修复 |
| FS-12 | 高 | authentication_bypass / broken_object_level_authorization | AUTO_CONNECT 信任请求体自报 TLS 指纹；WebRTC 仅按自报 `peer.id` 自动签发 token | 已修复 |
| FS-13 | 中 | cors_misconfiguration | 设备 API CORS 无条件放行任意 loopback Origin，且 Origin 主机等于 Host 即放行 | 已修复 |
| FS-14 | 中 | intent_confused_deputy | Android `ACTION_SEND` / 拖放不校验 URI grant，可借应用身份导入并默认同步到已授权链路分享 | 已修复 |
| FS-15 | 高 | ssrf / secret_exfiltration | MCP `folderspan_device_scan` 接受任意 IPv4 CIDR 与端口，探测会向目标发送文件分享 access key | 已修复 |
| FS-16 | 中 | cleartext_transmission | 开启局域网 MCP 后，公共端口同时接受明文 HTTP 并广告 `http://<lan-ip>` 端点 | 已修复 |

---

## FS-11：Pro HTTP 缓存使用编译期硬编码 AES 密钥，JVM 密文落在系统临时目录

* 严重度：中
* 类别：insecure_data_storage / cryptographic_weakness
* 修复状态：已修复
* 位置：
  - `proMain/kotlin/com/folderspan/pro/core/network/cache/ProApiCacheCrypto.kt`（`EmbeddedProApiCacheKeyProvider`，约 86–100 行）
  - `proMain/kotlin/com/folderspan/pro/core/network/cache/FileApiResponseCacheStore.kt`（`defaultRootDirectory`，约 128–131 行）
  - `proMain/kotlin/com/folderspan/pro/data/repository/UserRepository.kt`（`UserProfileCachePolicy` / `UserDevicesCachePolicy`，约 97–98 行）
  - `core/src/jvmMain/kotlin/com/folderspan/utils/PathUtils.jvm.kt`（`getCachePath`，约 138 行）
* 置信度：0.92

### 描述

Pro 用户资料与设备列表 HTTP 响应会按 30 天 TTL 写入 AES-GCM 缓存。算法实现完整（随机 nonce、GHASH 标签、AAD 绑定 `version/namespace/storageKey`），但加密密钥是编译进二进制的 32 字节常量 `EMBEDDED_SECRET_KEY`。登录 `token` 只进入 `storageKey` 的 SHA-256，**不参与密钥派生**。拿到密文文件后，任何持有同一发行版二进制的人都能解密。

JVM 上 `PathUtils.getCachePath()` 等于 `System.getProperty("java.io.tmpdir")`（Linux 通常是 `/tmp`）。`FileUtils.writeBytes` 与 `createDirectoryIfNotExists` 都不设置 POSIX `0600` / `0700`。仓库里仅 TLS 身份目录使用 `setPosixFilePermissions`。因此桌面端缓存目录对同机其他用户往往可读。

生产真正启用该缓存的调用只有：

- `DefaultUserRepository.me` / `cachedMe`（资料：uuid、name、email、avatar、signature、status）
- `DefaultUserRepository.listDevices`（设备：id、device_type、device_name、`device_key`、created_at）

`login` / `register` / `refreshToken` 的 `cachePolicy` 默认 `null`，登录会话本身不进该缓存。`DefaultSettingRepository` 与 `DefaultPluginRepository` 当前生产路径也不传 `cachePolicy`；它们若日后启用，会继承同一硬编码密钥。

同根存储问题：编辑器备份 / 恢复 / 行索引 / 内容缓存也走 `getCachePath()`（`file-editor-backups/` 等），JVM 上是明文用户正文副本落系统临时目录。敏感路径政策已把这些编辑器目录标为 `sensitive`，但**不阻止本机其他用户读 `/tmp`**。`pro_http_cache` 尚未列入 `SensitiveFileAccessPolicy.commonSensitivePathRules`，也未列入 [敏感路径清单](sensitive-files-and-directories.md)。Android 整棵 `cacheDir` 标为 `application_cache`，`deniedExceptionForFileProvider` 对该类别放行（第二轮 FS-07 的产品例外）。

### 影响

- 同机其他用户或能读临时目录的进程，可取出 `pro_http_cache` 密文，再用发行版中的固定密钥还原邮箱、资料与已登记设备列表。
- `device_key` 用作设置同步请求头设备标识，不是登录 Bearer；泄露仍可辅助冒充该设备参与设置同步。
- Android / iOS 缓存落在应用沙箱内，风险低于 JVM；硬编码密钥使沙箱逃逸或备份提取后的解密成本降为零。
- 编辑器备份明文落同一 JVM tmpdir，扩大同机可读面。

### 利用场景

用户在桌面端登录 Pro 后使用过「我的资料」或设备列表。同机另一账户读取系统临时目录中的 `pro_http_cache`，对照公开二进制提取 `EMBEDDED_SECRET_KEY`，解密 30 天 TTL 内的缓存条目。不需要登录态，也不需要改应用代码。

<details>
<summary>修复方案</summary>

1. **删除编译期全局密钥。** 按安装实例从桌面金库 / Android Keystore / iOS Keychain 派生缓存 DEK；`userScope`（token 哈希）必须进入 HKDF 信息或密钥，而不能只进文件名。
2. **缓存根目录改到应用私有数据目录**，不要用 `java.io.tmpdir`。目录 `0700`，文件 `0600`。
3. 把 `pro_http_cache` 登记进 `SensitiveFileAccessPolicy` 与 [敏感路径清单](sensitive-files-and-directories.md)；Android FileProvider 对该目录应拒绝，而不是走 `application_cache` 放行。
4. 编辑器备份 / 恢复同样迁出系统临时目录，或至少 `0700`。
5. 现有测试 `embeddedKeyProviderDoesNotPersistKeyToSettings` 只证明「密钥不写入 settings」，应改为「密钥不得硬编码、两套安装密文不可互换」。

```kotlin
internal class VaultProApiCacheKeyProvider(
    private val vault: SettingsVault,
) : ProApiCacheKeyProvider {
    override fun keyMaterial(): ProApiCacheKeyMaterial {
        val existing = vault.getOrCreateSecret(CACHE_DEK_ALIAS, secretSize = 32)
        return ProApiCacheKeyMaterial(encryptionKey = existing.copyOf())
    }
}

actual fun getCachePath(): String = resolveDesktopApplicationDataDirectory()
    .resolve("cache")
    .also { dir ->
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
    }
    .toString()
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun proHttpCacheMustNotUseCompileTimeGlobalKey() {
    val source = File("proMain/kotlin/com/folderspan/pro/core/network/cache/ProApiCacheCrypto.kt").readText()
    assertFalse("EMBEDDED_SECRET_KEY" in source)
    assertFalse(Regex("""byteArrayOf\s*\(\s*0x51\s*,\s*0x0d""").containsMatchIn(source))
}

@Test
fun jvmCachePathIsOwnerOnlyApplicationDataNotSystemTmp() {
    val cache = PathUtils.getCachePath()
    val tmp = System.getProperty("java.io.tmpdir")
    assertFalse(File(cache).canonicalPath.startsWith(File(tmp).canonicalPath))
    val perms = Files.getPosixFilePermissions(Path.of(cache))
    assertFalse(perms.contains(PosixFilePermission.OTHERS_READ))
    assertFalse(perms.contains(PosixFilePermission.GROUP_READ))
}

@Test
fun twoInstallationsProduceNonInterchangeableCacheCiphertext() {
    val first = ProApiCacheCrypto(VaultProApiCacheKeyProvider(vaultA))
    val second = ProApiCacheCrypto(VaultProApiCacheKeyProvider(vaultB))
    val nonce = ByteArray(12) { 1 }
    val aad = "v1\nuser\nkey".encodeToByteArray()
    val payload = first.encrypt("profile-json".encodeToByteArray(), first.keyMaterial().encryptionKey, nonce, aad)
    assertNull(
        second.decrypt(
            EncryptedApiCachePayload("AES-256-GCM", payload.nonce, payload.cipherText, payload.tag),
            namespace = "user",
            key = sampleCacheKey,
        )
    )
}

@Test
fun sensitivePolicyProtectsProHttpCache() {
    val path = FileApiResponseCacheStore.defaultRootDirectory() + "/entry"
    val classification = SensitiveFileAccessPolicy.classify(path)
    assertTrue(classification.isProtected)
    assertNotNull(SensitiveFileAccessPolicy.deniedExceptionForFileProvider(path))
}
```

</details>

---

## FS-12：AUTO_CONNECT 信任请求体自报 TLS 指纹；WebRTC 仅按自报 `peer.id` 自动签发 token

* 严重度：高
* 类别：authentication_bypass / broken_object_level_authorization
* 修复状态：已修复
* 位置：
  - `core/src/serverRouteMain/kotlin/com/folderspan/routes/RawHttpDeviceRoutes.kt`（`approveAutoConnectedDevice` 约 276–300 行；`trustDeviceTlsFingerprint` 约 312–316 行；`handlePing` 约 55、76 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/tls/TrustedDeviceCertificateStore.kt`
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/main/DeviceState.kt`（`resolveWebRtcConnectDecision` 约 1405–1418 行；`issueWebRtcApprovalToken` 约 1447–1456 行）
* 置信度：0.88

### 描述

HTTP 设备连接在用户曾对该 `deviceId` 选择「始终允许」（`DeviceConnectType.AUTO_CONNECT`）后，会跳过人工确认并直接 `issueDeviceToken`。钉扎校验调用 `TrustedDeviceCertificateStore.verify(device.id, tlsFingerprint)`，但两侧指纹都来自 **Protobuf 请求体** `device.tlsFingerprintSha256`，不是 TLS 握手叶子证书。设备 HTTP 服务端不要求对端出示客户端证书（仓库无 `clientCertificate` 校验）。`trustDeviceTlsFingerprint` 在签发 token 时把请求体指纹写回 store，形成「自报 → 入库 → 再信自报」闭环。

未认证 `/ping` 在校验发现头后回传 `getSocketDevice()`，其中包含本机 `id` 与 `tlsFingerprintSha256`。因此邻机可以先 ping 目标设备拿到其公开身份字段，再向已对该 id 开启 AUTO_CONNECT 的主机发起 connect。

WebRTC 路径更弱：`resolveWebRtcConnectDecision` 只看数据库里该 `peer.id` 是否为 `AUTO_CONNECT`（或全局 `autoAuthorizeDeviceConnect`）。信令 join 使用客户端自报 `device.id`，仅禁止当前房间内已在场的 id。自动批准后 `issueWebRtcApprovalToken` 绑定的指纹是 `DeviceTokenFingerprint(deviceId, clientIp=null, userAgent=null)`，HTTP 侧 IP/UA 绑定在这条 token 上无法生效。第二轮 FS-09 已要求 WebRTC RPC 校验 token 绑定的 peer id；本问题是**签发前**的身份来源，不是 RPC 指纹缺失的重复开洞。

`KEY_FILE_SHARE_AUTO_AUTHORIZE_DEVICE_CONNECT` 默认 `false`。若用户打开该全局开关，未知设备也会 `upsert AUTO_CONNECT` 并直接签发 token，连自报指纹都不需要。

### 影响

- 局域网邻机在用户曾对某设备点「始终允许」后，可冒充该设备领取文件 API token，读写被该角色授权的路径。
- WebRTC 自动批准不验证 TLS，只验证自报 id，冒充成本更低。
- 与 FS-13 叠加时，同机网页可读 connect 响应中的 token。

### 利用场景

用户允许笔记本 A 始终连接手机 B。邻机先向 A 的 `/ping` 读取 A 的 `id` 与自报指纹，再向 B 的 `/api/devices/connect` 提交相同字段。B 走 AUTO_CONNECT 分支，比对请求体指纹与已存字符串后签发 token。WebRTC 场景下只需在信令中使用 A 的 id。

<details>
<summary>修复方案</summary>

1. **入站 AUTO_CONNECT 必须钉扎握手层证书**，禁止使用请求体 `tlsFingerprintSha256`。与发现 ping（`ff3672b368`，按握手叶子证书钉扎）对齐。
2. 设备 HTTP 对 AUTO_CONNECT 回连启用客户端证书，或在 TLS 层捕获对端证书指纹后再与 store 比对。
3. `/ping` 不要把可用于冒充的指纹当公开字段回传；至少对未认证发现响应省略 `tlsFingerprintSha256`。
4. WebRTC AUTO_CONNECT 必须绑定信令连接的不可伪造属性（本机 join secret、已登记 peer 公钥、或先前 HTTP 会话的握手指纹），禁止只看自报 `peer.id`。
5. 全局 `autoAuthorizeDeviceConnect` 即使打开，未知设备也不得跳过握手钉扎。

```kotlin
internal suspend fun RawHttpApiDispatcher.approveAutoConnectedDevice(
    device: SocketDevice,
    token: String,
    roleId: Long,
    fingerprint: DeviceTokenFingerprint,
    handshakeTlsFingerprint: String,
): DeviceConnectResponse {
    if (handshakeTlsFingerprint.isBlank() || !TrustedDeviceCertificateStore.has(device.id)) {
        return waitForDeviceApproval(device, token, roleId, fingerprint)
    }
    if (!TrustedDeviceCertificateStore.verify(device.id, handshakeTlsFingerprint)) {
        return DeviceConnectResponse(REJECTED, "", DeviceConnectAuthorizationMode.STANDARD)
    }
    issueDeviceToken(device, token, roleId, fingerprint)
    return DeviceConnectResponse(APPROVED, token, DeviceConnectAuthorizationMode.STANDARD)
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun autoConnectRejectsBodyReportedFingerprintWhenHandshakeDiffers() = runTest {
    TrustedDeviceCertificateStore.save("device-a", "AA".repeat(32))
    val body = connectRequest(
        deviceId = "device-a",
        tlsFingerprintSha256 = "AA".repeat(32),
    )
    val response = dispatcher.handleDeviceConnect(
        request = rawConnect(body, handshakeFingerprint = "BB".repeat(32)),
    )
    assertEquals(DeviceConnectType.REJECTED, response.decode<DeviceConnectResponse>().connectType)
}

@Test
fun unauthenticatedPingDoesNotExposeTlsFingerprint() = runTest {
    val response = dispatcher.handlePing(discoveryPing(deviceId = "other"))
    val self = response.decode<SocketDevice>()
    assertTrue(self.tlsFingerprintSha256.isNullOrBlank())
}

@Test
fun webRtcAutoConnectDoesNotApproveUnknownSelfReportedPeerId() = runTest {
    database.deviceConnectQueries.upsert(
        id = "trusted-peer",
        connectionType = DeviceConnectType.AUTO_CONNECT,
        category = DeviceCategory.SERVER,
        roleId = 2L,
    )
    val decision = deviceState.resolveWebRtcConnectDecisionForTest("attacker-chosen-id")
    assertEquals(WebRtcConnectDecision.Waiting, decision)
}
```

</details>

---

## FS-13：设备 API CORS 无条件放行任意 loopback Origin，且 Origin 主机等于 Host 即放行

* 严重度：中
* 类别：cors_misconfiguration
* 修复状态：已修复
* 位置：`core/src/serverRouteMain/kotlin/com/folderspan/routes/RawHttpDispatcherSupport.kt`（`isAllowedPrivateWebCorsOrigin`，约 217–228 行）
* 置信度：0.86

### 描述

第一轮 FS-04 已去掉对任意私网 Origin 的通配回显和默认 PNA 全放行。残留策略仍是：

1. Origin 主机为 `localhost` / `127.0.0.0/8` / `::1` 时**无条件允许**，不看端口、不看是否为本应用页面。
2. Origin 主机等于请求 `Host` 时允许。这会放行 `http://<lan-ip>:<任意端口>` 访问同一主机上的设备 API。

现有测试 `httpWebRtcPreflightAllowsLocalhostOriginWithoutWildcardOnRawApi` 把 localhost 放行当作正向保证。威胁模型包含同机恶意网页：本机浏览器页面可以把设备 API 的 JSON/Protobuf 读回脚本。与 FS-12 叠加时，connect 响应里的 token 可被同机页面读取。

设备 API 本身仍要鉴权；本问题不是「无 token 读文件」，而是「有浏览器可达的跨源读响应」。链路分享 Cookie 已是 `HttpOnly` + `SameSite=Lax`，不在本条范围内。

### 影响

- 同机恶意网页（扩展、被 XSS 的本地服务、用户打开的 HTML）可对 loopback/同 Host 设备 API 做凭证随带的跨源读取。
- 不能单独打穿 `requireAuth`；需要再叠 AUTO_CONNECT、已有 token 或用户交互。

### 利用场景

用户本机同时运行 FolderSpan 设备 HTTP 服务和另一个监听 loopback 的页面。该页面向 `https://127.0.0.1:<fileSharePort>/api/devices/connect` 发预检，收到回显的 `Access-Control-Allow-Origin`，再读取后续响应。

<details>
<summary>修复方案</summary>

1. 允许的 Web Origin 改为明确白名单：本机分享页、WebRTC 宿主页、用户配置的额外 Origin。不要按「是不是 loopback」放行。
2. 禁止「Origin 主机 == Host」这条宽规则；端口必须匹配应用实际监听端口，且仅 https（或仅本应用签发的页面）。
3. 预检失败时不回显 `Access-Control-Allow-Origin`。
4. 更新把 localhost 无条件放行当作正向保证的测试。

```kotlin
private fun isAllowedPrivateWebCorsOrigin(origin: String, requestHost: String?): Boolean {
    val parsed = RawHttpCorsOrigin.parse(origin) ?: return false
    if (parsed.scheme != "https" && !isLoopbackDevOrigin(parsed)) return false
    return allowedWebOrigins().any { allowed -> originsEqual(parsed, allowed) }
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun deviceApiPreflightRejectsUnrelatedLoopbackOrigin() {
    val response = rawDispatcher.handleOptions(
        origin = "http://127.0.0.1:34567",
        host = "192.168.1.8:12040",
    )
    assertNull(response.header("Access-Control-Allow-Origin"))
}

@Test
fun deviceApiPreflightRejectsSameHostDifferentPort() {
    val response = rawDispatcher.handleOptions(
        origin = "http://192.168.1.8:9999",
        host = "192.168.1.8:12040",
    )
    assertNull(response.header("Access-Control-Allow-Origin"))
}
```

</details>

---

## FS-14：Android `ACTION_SEND` / 拖放不校验 URI grant，可借应用身份导入并默认同步到已授权链路分享

* 严重度：中
* 类别：intent_confused_deputy
* 修复状态：已修复
* 位置：
  - `app/shared/src/androidMain/kotlin/com/folderspan/share/ShareIntentHandler.android.kt`（`hasShareData` / `handleSend` / `extractUris`，约 32–38、134–152、205–216 行）
  - `app/androidApp/src/main/AndroidManifest.xml`（`MainActivity` `exported=true`，`SEND` / `SEND_MULTIPLE` `mimeType=*/*`）
  - `core/src/androidMain/kotlin/com/folderspan/utils/ShareHandler.kt`（`getFileInfoFromUri` 约 174–199 行；`handleSharedFilesForShare` 约 373–417 行；`resolveExternalStorageDocumentPath` 约 859–861 行）
  - `app/shared/src/commonMain/kotlin/com/folderspan/ui/screen/file/share/FileShareScreen.kt`（空列表直接应用 incoming files，约 205–211 行；`synchronizeCurrentShareFiles` 默认开启，约 122–147 行）
* 置信度：0.80

### 描述

`ACTION_VIEW` 已要求 `content://` + `FLAG_GRANT_READ_URI_PERMISSION`（FS-06 相关收紧）。`ACTION_SEND` / `ACTION_SEND_MULTIPLE` 只检查 extra 是否存在 URI，**不检查 grant、不调用 `checkUriPermission`**。应用持有 `MANAGE_EXTERNAL_STORAGE` 与各类 `READ_MEDIA_*`，可作为confused deputy 读取发送方自己读不到的 content URI。

`getFileInfoFromUri` 在 query 无行时仍返回 `FileInfo("unknown", 0, mimeType)`，只有抛异常才返回 `null`。`handleSharedFilesForShare` 把 `path = uri.toString()` 放进分享列表。

分享页在列表为空时直接 `files.clear(); addAll(incomingFiles)`，不弹合并对话框。`KEY_EASY_FILE_SHARE_AUTO_UPDATE_LINK_SHARE_FILES` 与 `KEY_EASY_FILE_SHARE_AUTO_UPDATE_DEVICE_SHARE_FILES` 默认 `true`；`LaunchedEffect(currentShareFiles)` 每次列表变化都调用 `synchronizeCurrentShareFiles()`，会把新文件推到已授权链路分享会话。

拖放会解析 nested `ClipData.Item.intent.data`；`resolveExternalStorageDocumentPath` 对 `file://` 直接返回 `uri.path`。`requestDragAndDropPermissions` 只覆盖系统拖放 grant，不能约束 nested intent 或 `file://`。

这不是「文件管理器读 MediaStore」本身，而是：**未按 grant 约束的跨应用导入 + 默认发布到局域网已授权分享**。

### 影响

- 恶意应用可把 FolderSpan 读得到、自己读不到的 URI 塞进分享列表。
- 若用户当时有已授权链路分享且保持默认自动更新，导入的文件会对局域网持有分享 token 的客户端可见。
- `file://` / nested intent 可把本机绝对路径送进分享桌面。

### 利用场景

用户已打开快捷分享并授权过某链路设备，分享列表当时为空。另一应用向 FolderSpan 发送 `ACTION_SEND`，extra 中带一个 FolderSpan 有权读取的 content URI。列表被直接替换，并因默认自动更新同步到已授权链路分享。

<details>
<summary>修复方案</summary>

1. SEND / SEND_MULTIPLE / 拖放与 VIEW 使用同一套 URI 准入：`content://`（或用户选择器返回的 URI）+ 持久/临时 grant，或 `checkUriPermission == PERMISSION_GRANTED`。
2. 拒绝 `file://`、拒绝 nested `ClipData.Item.intent`，除非该 intent 自身也带 grant。
3. `getFileInfoFromUri` 在 query 无行或无法 `openInputStream` 时返回 `null`，不要造 `"unknown"`。
4. 外部导入进入分享列表必须走与「已有文件冲突」相同的确认；自动同步不得在用户确认前触发。
5. 自动更新开关默认改为 `false`，或至少对「来自外部 Intent 的文件」禁用自动同步。

```kotlin
private fun hasGrantedShareUri(intent: Intent, uri: Uri): Boolean {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
    if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) return true
    return activity.checkUriPermission(
        uri,
        Binder.getCallingPid(),
        Binder.getCallingUid(),
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
    ) == PackageManager.PERMISSION_GRANTED
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun sendWithoutReadGrantIsIgnored() {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "*/*"
        putExtra(Intent.EXTRA_STREAM, Uri.parse("content://com.other.app/private/1"))
    }
    assertFalse(ShareIntentHandler(activity, fileState, fileShareState, mainState).hasShareData(intent))
}

@Test
fun extractUrisIgnoresNestedIntentAndFileScheme() {
    val clip = ClipData.newIntent("nested", Intent().setData(Uri.parse("file:///data/data/com.folderspan/folderspan.db")))
    val uris = handler.extractUrisForTest(clip)
    assertTrue(uris.isEmpty())
}

@Test
fun getFileInfoFromUriReturnsNullWhenQueryHasNoRow() {
    val uri = Uri.parse("content://com.android.providers.media.documents/document/missing")
    assertNull(ShareHandler.getFileInfoFromUriForTest(resolver, uri))
}

@Test
fun incomingFilesDoNotAutoSyncUntilUserConfirms() {
    fileShareState.authorizedLinkShareDevices[device] = access
    fileShareState.updateIncomingFiles(listOf(externalFile))
    assertTrue(fileShareState.authorizedLinkShareDevices[device]!!.files.none { item -> item.path == externalFile.path })
}
```

</details>

---

## FS-15：MCP `folderspan_device_scan` 接受任意 IPv4 CIDR 与端口，探测会向目标发送文件分享 access key

* 严重度：高
* 类别：ssrf / secret_exfiltration
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/service/mcp/tools/McpToolRegistry.kt`（工具注册约 169–170 行；`scanSchema` 约 409–411 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/mcp/automation/DeviceScanOperationStore.kt`（`start` 约 77–86 行；`parseIpv4Cidr` 约 198–207 行）
  - `app/shared/src/commonMain/kotlin/com/folderspan/di/CommonModule.kt`（probe 绑定 `DeviceState.pingDevice`，约 156–161 行）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/main/DeviceState.kt`（`pingDevice` 使用 `applyFileShareAccessKey()`，约 737–753 行）
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/client/HttpClientFactory.jvm.kt`（`FingerprintTrustManager` 在 capture 模式下接受任意证书，约 87–92 行）
* 置信度：0.90

### 描述

MCP 工具 `folderspan_device_scan` 的 `subnet` 与 `port` 都是调用方参数。`port` 允许 `1..65535`。调用方提供 CIDR 时走 `parseIpv4Cidr(..., enforceLimit = true)`，上限 4096 个地址，**不要求该网段属于本机活动网卡**（活动网卡检查只发生在 `subnet == null` 的默认扫描）。因此 `/20` 的公网、链路本地、CGNAT、云元数据地址都可以成为扫描目标。

生产 probe 直接调用 `DeviceState.pingDevice`：完整设备发现 HTTPS POST、`applyFileShareAccessKey()`、TLS capture 模式下不校验服务端证书。命中 TLS 的地址会收到带 access key 的发现请求；未知响应者会被插入设备表，并可能按 AUTO_CONNECT / 同账号自动连接策略继续 `connect()`。并发为 64。

现有测试 `deviceScanUsesAllActiveSubnetsByDefaultAndHonorsCidrLimit` 只断言 `/19`（8192 地址）因数量上限失败，不拒绝 `169.254.169.254/32` 或公网 `/24`。

前提：MCP 已启用且调用方持有 `DevicesScan` scope 的 token。默认 MCP 只绑 loopback；token 失窃、本机被投毒的 agent、或用户开启局域网 MCP（见 FS-16）都会把该工具变成扫描器。

### 影响

- 持有扫描 scope 的 token 可把本机变成最多 4096 主机、任意端口、64 并发的探测器。
- 若用户启用了文件分享 access key，该密钥会被发到每一个扫描地址。
- 扫描过程接受任意 TLS 证书，中间人可收集 access key 与本机设备描述。
- 未知主机写入设备库，并可能触发自动连接。

### 利用场景

本机 MCP token（`DevicesScan`）被自动化客户端或恶意本地进程使用。调用 `folderspan_device_scan`，`subnet` 为非本机网段、`port` 为任意服务端口。FolderSpan 以设备发现客户端身份发起请求，并在 access key 开启时附带该密钥。

<details>
<summary>修复方案</summary>

1. 显式 `subnet` 必须落在 `ActiveIpv4SubnetProvider` 返回的前缀内（或 RFC1918/ULA 且排除链路本地、回环、组播、CGNAT、元数据地址）。
2. 端口固定为文件分享端口，不要接受调用方任意端口。
3. 扫描使用专用 probe：TCP/TLS 握手或裸 HTTP 发现，**禁止** `applyFileShareAccessKey`、账号设备证明、写设备库、触发 `connect()`。不要复用 `pingDevice`。
4. 保持 4096 主机上限，并按 token 做并发 / 速率限制。
5. capture 模式的 TrustManager 仅用于受控发现，不得用于调用方指定的任意地址。

```kotlin
suspend fun start(subnet: String?, port: Int): DeviceScanOperation {
    require(port == HttpRouteClientManager.PORT) { "scan port must be the file-share port" }
    val interfaces = subnetProvider.get()
    val cidrs = if (subnet.isNullOrBlank()) {
        interfaces.map { item -> parseIpv4Cidr("${item.address}/${item.prefixLength}", enforceLimit = false) }
    } else {
        val requested = parseIpv4Cidr(subnet, enforceLimit = true)
        require(requested.isCoveredBy(interfaces)) { "subnet must match an attached IPv4 prefix" }
        require(requested.isScanSafe()) { "subnet is not a permitted scan range" }
        listOf(requested)
    }
    ...
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun deviceScanRejectsPublicLinkLocalAndMetadataCidrs() = runTest {
    val store = DeviceScanOperationStore(
        scope = this,
        subnetProvider = ActiveIpv4SubnetProvider {
            listOf(Ipv4InterfaceSubnet("192.168.8.1", 24))
        },
        probe = McpDeviceProbe { _, _ -> error("probe must not run") },
        dispatcher = StandardTestDispatcher(testScheduler),
        nowMillis = { 1L },
    )
    assertFailsWith<IllegalArgumentException> { store.start("1.1.1.0/24", 443) }
    assertFailsWith<IllegalArgumentException> { store.start("169.254.169.254/32", 80) }
    assertFailsWith<IllegalArgumentException> { store.start("192.168.8.0/24", 22) }
}

@Test
fun deviceScanProbeDoesNotSendAccessKeyHeader() = runTest {
    val captured = mutableListOf<Headers>()
    val store = DeviceScanOperationStore(
        scope = this,
        subnetProvider = { listOf(Ipv4InterfaceSubnet("10.0.0.1", 30)) },
        probe = recordingProbe(captured),
        dispatcher = StandardTestDispatcher(testScheduler),
        nowMillis = { 1L },
    )
    store.start(subnet = null, port = HttpRouteClientManager.PORT)
    advanceUntilIdle()
    assertTrue(captured.none { headers -> headers.contains(FILE_SHARE_ACCESS_KEY_HEADER) })
}
```

</details>

---

## FS-16：开启局域网 MCP 后，公共端口同时接受明文 HTTP 并广告 `http://<lan-ip>` 端点

* 严重度：中
* 类别：cleartext_transmission
* 修复状态：已修复
* 位置：
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/server/HttpPortSchemeSwitchingProxy.kt`（`createBindSockets` 约 164–169 行；`handleClient` 按前 8 字节分流 HTTP/TLS，约 76–81 行）
  - `core/src/jvmMain/kotlin/com/folderspan/service/mcp/http/McpHttpService.jvm.kt` / Android 对应实现（`proxy.start(..., bindLan = settings.lanAccess)`）
  - `core/src/commonMain/kotlin/com/folderspan/service/mcp/http/McpHttpService.kt`（`buildMcpAdvertisedEndpoints` 对每个 host 同时广告 `https` 与 `http`，约 32–46 行）
  - `core/src/iosMain/kotlin/com/folderspan/service/mcp/http/McpHttpService.ios.kt`（`bindLoopback = !settings.lanAccess`）
  - 现有测试 `httpAndHttpsAreReachableThroughNonLoopbackLanAddressWhenEnabled` 把局域网明文 HTTP 当作正向保证
* 置信度：0.87

### 描述

MCP 默认 `lanAccess=false`，HTTP/HTTPS 绑 `127.0.0.1`（前两轮已评估）。用户打开「允许局域网连接」后：

- JVM/Android 公共端口 `ServerSocket(port)` 绑 `0.0.0.0`，用首字节判断 HTTP 方法前缀或 TLS，再分别转到 loopback 上的明文 / TLS 上游。
- 广告列表对每个局域网地址都给出 `http://` 与 `https://`。
- `McpHttpRequestHandler` 要求 `Authorization: Bearer fmcp_…`。明文 HTTP 把该 token 放在局域网上传输。
- Host 策略允许 `127.0.0.1`，不校验真实客户端 IP 是否来自 loopback。

产品文档 [MCP HTTP 服务](../networking/mcp-http-server.md) 描述了本机同时提供 HTTP/HTTPS，并说明打开局域网后会列出网卡 IP。本条针对的是：**局域网邻机威胁模型下，把 Bearer token 放上明文 HTTP**，而不是「默认绑 loopback」被破坏。iOS 在 `lanAccess=true` 时直接把 Raw HTTP 服务绑到非 loopback，同样接受 HTTP。

### 影响

- 邻机嗅探或同网恶意接入点可截获 `fmcp_` token，权限等于该 token 的全部 scope（文件读写、任务、设备连接等）。
- 广告明文 URL 会引导客户端走不安全传输。

### 利用场景

用户为了让另一台电脑上的 agent 连接，打开了 MCP 局域网开关，并按管理页复制了 `http://192.168.x.x:52137/mcp`。同一 Wi-Fi 上的邻机观察该 HTTP 请求中的 `Authorization` 头。

<details>
<summary>修复方案</summary>

1. `bindLan=true` 时公共端口只接受 TLS；明文 HTTP 仅留在 loopback 上游，不对外转发。
2. `buildMcpAdvertisedEndpoints` 对非 loopback host 只广告 `https://`。
3. 可选：校验对端 IP，拒绝「Host 为 127.0.0.1 但 socket 来自局域网」的请求。
4. 修改把局域网 HTTP 200 当作正向保证的测试。

```kotlin
private fun createBindSockets(port: Int, bindLan: Boolean): List<ServerSocket> {
    val loopback = listOf(
        ServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.1")),
        runCatching { ServerSocket(port, BACKLOG, InetAddress.getByName("::1")) }.getOrNull(),
    )
    if (!bindLan) return loopback.filterNotNull()
    return listOf(ServerSocket(port)) // 对外仅由 TLS 入口使用；HTTP 探测应拒绝
}

fun buildMcpAdvertisedEndpoints(hosts: Iterable<String>, port: Int): List<McpAdvertisedEndpoint> =
    hosts.flatMap { host ->
        val schemes = if (host.isLoopbackAdvertisedHost()) listOf("https", "http") else listOf("https")
        schemes.map { scheme -> endpoint(scheme, host, port) }
    }
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun lanAccessDoesNotAdvertiseCleartextLanEndpoints() {
    val endpoints = buildMcpAdvertisedEndpoints(listOf("127.0.0.1", "192.168.8.10"), port = 52137)
    assertTrue(endpoints.any { item -> item.host == "127.0.0.1" && item.scheme == "http" })
    assertTrue(endpoints.none { item -> item.host == "192.168.8.10" && item.scheme == "http" })
    assertTrue(endpoints.any { item -> item.host == "192.168.8.10" && item.scheme == "https" })
}

@Test
fun lanHttpBearerPostIsNotAcceptedWhenLanAccessEnabled() = runTest {
    val status = service.start(McpServerSettings(enabled = true, port = port, lanAccess = true))
    assertTrue(status.running)
    val token = repository.create("lan", setOf(McpTokenScope.FilesRead)).token
    assertFails {
        postJson("http://$lanHost:$port/mcp/stateless", token, pingBody)
    }
    assertTrue(postJson("https://$lanHost:$port/mcp/stateless", token, pingBody).contains("\"result\""))
}
```

</details>

---

## 已检查、不作为本轮新漏洞

| 主题 | 结论 |
| --- | --- |
| FS-01～FS-10 | 源码与测试表明已落地或（FS-06）为误报，不重复开洞。 |
| 链路分享 CSRF | Cookie `HttpOnly`、默认 `SameSite=Lax`、HTTPS 时 `Secure`；会话绑 IP+UA；上传要求 `X-API-Request` 与同 Origin。`sanitizeLinkShareRedirect` 拒绝协议相对 URL。 |
| WebRTC RPC 鉴权 | FS-09 修复后要求 token 绑定 peer `deviceId` 且有指纹；本轮 FS-12 只覆盖签发前的自报身份。 |
| MCP 默认绑定 / 工具投毒 / 路径穿越 | 默认 loopback；Host/Origin 拒绝 DNS rebinding；内置工具描述非指令式；本地文件工具规范化路径并拒绝 `/proc` `/sys` `/dev` 与敏感目录。 |
| `settings.fileShare.accessKey` 进入 Pro 同步白名单 | [设置同步白名单](../product/settings-sync-whitelist.md) 明确允许同步该 LAN 共享密钥，属产品行为。本地仍走敏感设置金库。建议后续改为本机专用（与 `settings.crypto.key` 相同），但不作为本轮漏洞 ID。 |
| Plugin / Setting API 缓存 | 基础设施默认构造 `FileApiResponseCacheStore`，生产仓库不传 `cachePolicy`，现网不落盘。若启用会继承 FS-11。 |
| 登录 / refresh token 缓存 | `UserApiService.login/register/refreshToken` 生产不传 `cachePolicy`。 |
| JWT | 仓库无 JWT 实现。 |
| 命令注入 | `ProcessBuilder` 仅用于 DPI 探测与桌面开机启动，参数为固定命令/可执行路径。 |
| XSS / zip-slip | 链接分享 HTML 走 kotlinx.html 转义；归档解压拒绝 `..` 与绝对路径。 |
| Android 设备 ID AES `1234567890123456` | 第一轮已评估为公开 UUID 混淆，不是数据密钥。 |
| SFTP known_hosts / Desktop TLS 身份 / iOS 盐 | FS-02、FS-05、FS-10 修复后未发现回归。 |
| FileProvider `root-path` | FS-07 已删除；`cache-path` 仍在，与 FS-11 的 Android 缓存面相关，不单独开洞。 |

## 修复优先级建议

1. **FS-12、FS-15**：直接导致 token 签发或密钥外泄，应先做。
2. **FS-11、FS-16**：降低同机 / 邻机凭据暴露，可与密钥治理一起做。
3. **FS-13、FS-14**：收紧浏览器与 Android 导入面，避免与上述问题叠加。

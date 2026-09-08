# FolderSpan 安全审计报告（第八轮）

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
- 方法论：Claude Marketplaces 插件 `cybersecurity-skills@anthropic-cybersecurity-skills` v1.3.0
  覆盖技能：`exploiting-race-condition-vulnerabilities`、`performing-api-rate-limiting-bypass`、`implementing-api-rate-limiting-and-throttling`、`performing-ssl-tls-security-assessment`、`configuring-tls-1-3-for-secure-communications`、`testing-for-business-logic-vulnerabilities`、`testing-api-for-mass-assignment-vulnerability`、`exploiting-mass-assignment-in-rest-apis`、`exploiting-idor-vulnerabilities`、`performing-security-headers-audit`、`exploiting-api-injection-vulnerabilities`、`testing-for-insecure-deserialization`
- 约束：本报告不含攻击载荷或 exploit PoC。折叠区内的「验证代码」均为防御性回归测试（断言安全属性），用于确认修复是否生效。
- 威胁模型：局域网邻机、同机恶意进程/网页、不可信 Android Intent、SFTP 中间人、已授权设备上的 token 盗用。本机文件分享服务按产品设计会对局域网可达。
- 本轮只登记相对前七轮**仍未修复的新问题**。已落地的 FS-01～FS-31、已排除项与产品明确文档化的同步行为不重复开洞。

## 审计发现总览

| ID | 严重度 | 类别 | 标题 | 状态 |
| --- | --- | --- | --- | --- |
| FS-32 | 中 | race / rate_limit_bypass | 链路分享口令锁定先检查再 PBKDF2，并发请求可越过 5 次上限并放大 CPU | 已修复 |
| FS-33 | 高 | toctou / symlink_follow / confused_deputy | FS-30 落地的是检查，打开仍跟随符号链接；同机进程可在握手窗口把叶子换成受保护路径 | 已修复 |
| FS-34 | 高 | tls_hostname_verification | JVM/Android FTPS 使用 Commons Net 默认 `FTPSClient()`，不校验证书主机名 | 已修复 |
| FS-35 | 中 | weak_tls_protocol | 设备 API 与链路分享 TLS 只过滤 `SSLv*`，Android 8–9 仍可能接受 TLS 1.0/1.1 | 已修复 |
| FS-36 | 中 | availability / resource_exhaustion | 未认证的 `/api/devices/connect` 在共享控制信号量里等待最多 10 分钟，可饿死 ping / 心跳 / 信令 | 已修复 |
| FS-37 | 中 | unbounded_persistence / inventory_pollution | `/ping` 与 `/api/devices/connect` 把几乎无界的设备记录写入 SQLite 与内存列表 | 已修复 |

---

## FS-32：链路分享口令锁定把昂贵校验放在计数器之外

* 严重度：中
* 类别：race / rate_limit_bypass
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/service/http/server/linkshare/LinkShareRouteDispatcher.kt`（`handlePasswordAuth` 约 262–292 行：先 `lockoutOrNull`，再 `passwordVerifier.matches`，最后才 `registerFailure` / `registerSuccess`）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/server/linkshare/LinkSharePasswordAttemptLimiter.kt`（`Mutex` 只保护计数器，约 16–33 行；默认 5 次失败 / 15 分钟，约 67–68 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/server/linkshare/LinkSharePasswordVerifier.kt`（每次猜测跑 `DEFAULT_ITERATIONS = 32_768` 次 PBKDF2-HMAC-SHA256，约 22–31、51 行）
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/server/LinkShareRawHttpServer.jvm.kt` 与 Android 对应实现（每个已接受套接字 `launch { handleClient }`，请求在 `Dispatchers.IO` 上真正并发）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/file/FileShareState.kt`（轻松分享口令 `6.randomString(includeSpecial = false)`，约 554–555 行）
* 置信度：0.86

### 描述

链路分享在开启「密码访问」时，用 IP（`request.remoteHost`）做失败计数。限制器本身是对的：`lockoutOrNull` / `registerFailure` 都在 `Mutex` 里，空白主机归一成 `"unknown"`，桶上限 4096。缺口在**调用点**：

```kotlin
passwordAttemptLimiter.lockoutOrNull(clientKey)?.let { lockout ->
    return respondPasswordThrottled(request, redirectTo, lockout)
}
val passwordMatches = expectedPassword.isNotEmpty() &&
    passwordVerifier.matches(expectedPassword, password)
if (!passwordMatches) {
    val lockout = passwordAttemptLimiter.registerFailure(clientKey)
    ...
}
```

`matches` 对**每次猜测**做 32 768 轮 PBKDF2。期望口令有缓存，猜测没有。限制器的锁在这一步之外。

`LinkShareRawHttpServer` 对每个 TCP 客户端单独 `launch`。同一局域网 IP 可以同时发出多条 `POST /auth`。它们都会通过步骤 1（当时尚未锁定），全部跑完 PBKDF2，再串行 `registerFailure`。默认策略是 5 次失败锁定 15 分钟，一次突发却能消耗 **N 次猜测和 N 次 PBKDF2**。

`registerFailure` 救不了已经发生的猜测。后续请求会被挡住；第一波同步请求不会。

轻松分享默认口令是 6 位字母数字。单靠长度不足以在线打穿，但锁定正是让在线猜测变贵的控制；校验器还是 CPU 放大器。这不是「空白 IP 撞进同一个桶」（已排除），也不是 FS-29 的上传权限。

### 影响

- 邻机可以用一轮并发把有效猜测次数从 5 抬到「当前能打进分享 HTTP 服务的并发连接数」。
- 同一波请求会让分享进程在 `Dispatchers.IO` 上跑满 PBKDF2，拖慢同进程的目录列举与下载。
- 不需要有效会话，不需要 ticket；只要分享开了密码。

### 利用场景

主人开启轻松分享并打开密码访问。局域网另一台机器对分享端口并发提交多条错误口令。限制器在全部 PBKDF2 完成之后才把计数加到 5。第一波里多出来的猜测已经算完；进程在锁定生效前被占满 CPU。

<details>
<summary>修复方案</summary>

1. **把「是否锁定 / 占用一次尝试 / 再校验」做成每个 clientKey 的临界区。** 不要在持有尝试名额之前跑 PBKDF2。
2. 推荐：`tryAcquireAttempt(key)` 在校验**之前**把 `failureCount + 1`（或发一个 in-flight permit）。成功再 `registerSuccess` 清零；失败则保留已消耗的次数。
3. 每个 IP 的 in-flight 校验用 `Semaphore(1)`（或很小的上限），避免 N 路 PBKDF2。
4. 不要把限制器的 `Mutex` 包住整个 PBKDF2（那会让所有 IP 互相排队）；按 key 分锁，或「先占名额、锁外校验」。
5. 锁定响应继续走 429 + `Retry-After`，不要改成 401。

```kotlin
suspend fun tryAcquireAttempt(clientKey: String): LinkSharePasswordLockout? = mutex.withLock {
    pruneExpiredLocked(nowMillis())
    val key = normalizedKey(clientKey)
    lockoutOf(key, nowMillis())?.let { return@withLock it }
    val failureCount = (buckets[key]?.failureCount ?: 0) + 1
    val lockedUntil = if (failureCount >= maxFailures) nowMillis() + lockoutDurationMillis else 0L
    buckets[key] = Bucket(failureCount, lockedUntil)
    null
}

// handlePasswordAuth:
passwordAttemptLimiter.tryAcquireAttempt(clientKey)?.let { return respondPasswordThrottled(...) }
val passwordMatches = passwordVerifier.matches(expectedPassword, password)
if (passwordMatches) passwordAttemptLimiter.registerSuccess(clientKey)
else if (passwordAttemptLimiter.lockoutOrNull(clientKey) != null) return respondPasswordThrottled(...)
else return respondPasswordPage(...)
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun concurrentWrongPasswordsCannotExceedFailureBudget() = runTest {
    val limiter = LinkSharePasswordAttemptLimiter(
        maxFailures = 3,
        lockoutDurationMillis = 15 * 60 * 1000L,
        nowMillis = { 1_000L },
    )
    val state = FileShareState().apply { updateConnectPassword("secret") }
    val dispatcher = dispatcher(
        state = state,
        passwordAttemptLimiter = limiter,
        passwordVerifier = LinkSharePasswordVerifier(iterations = 1),
    )
    val jobs = List(20) {
        async(Dispatchers.Default) {
            dispatcher.dispatch(passwordAuthRequest(password = "wrong", remoteHost = "10.0.0.8"))
        }
    }
    val responses = jobs.awaitAll()
    val unauthorized = responses.count { item -> item.statusCode == 401 }
    val throttled = responses.count { item -> item.statusCode == 429 }
    assertTrue(unauthorized <= 3)
    assertTrue(throttled >= 17)
    val after = dispatcher.dispatch(passwordAuthRequest(password = "wrong", remoteHost = "10.0.0.8"))
    assertEquals(429, after.statusCode)
}
```

测试只断言失败预算与锁定；不要夹带正确口令撞车，也不要构造口令字典。

</details>

---

## FS-33：敏感路径与符号链接检查之后，打开仍然跟随

* 严重度：高
* 类别：toctou / symlink_follow / confused_deputy
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/data/main/network/Network.kt`（`rejectUnsafeLocalUploadSource` 约 1042–1050 行：`deniedException` + `PathUtils.isSymbolicLink`；随后 `client.upload(localPath = …)`，约 317–347、1025–1039 行）
  - `core/src/jvmMain/kotlin/com/folderspan/service/network/SftpNetworkClient.kt`（`upload` 约 158–171 行：`withClient` 完成 SSH 握手后再 `FileSystem.SYSTEM.source(localPath)`）
  - `core/src/jvmMain/kotlin/com/folderspan/service/network/SmbNetworkClient.kt`（约 140–164 行）、`FtpNetworkClient.kt`（约 128–144 行）：同样跟随
  - `core/src/jvmMain/kotlin/com/folderspan/utils/FileUtils.jvm.kt`（`readFileChunks` 约 241–266 行、`writeBytes` 约 340–367 行：`requireNotSymbolicLink()` / `isSymbolicLinkNoFollow()` 之后 `fileSystem.source` / `RandomAccessFile`）
  - `core/src/commonMain/kotlin/com/folderspan/service/file/DeviceFileCopyService.kt`（约 33–67、152–169 行：策略与 `ensureCopyTreeIsOrdinary` 之后 `source.copyTo` → `writeToFile` → `readFileChunks`）
  - `core/src/commonMain/kotlin/com/folderspan/data/file/FileInfo.kt`（`writeToFile` 约 159–197 行：先拒链接与敏感路径，再 `FileUtils.readFileChunks`）
* 置信度：0.90

### 描述

第七轮 FS-30 给 Local→Network 补上了**路径**检查：敏感文件拒绝，叶子符号链接拒绝。打开仍走会跟随的 API。同机恶意进程（威胁模型已包含）可以：

1. 应用把 `/home/user/Public/doc.txt` 分类为非敏感、非链接。
2. SFTP/SMB/FTP 的 `withClient` 接着做完整协议握手（秒级）。
3. 攻击者把叶子替换成指向受保护路径的符号链接（`folderspan.db`、`tls-identity`、凭据包装密钥等，见 `docs/security/sensitive-files-and-directories.md`）。
4. `FileSystem.SYSTEM.source` / `RandomAccessFile` / Okio `FileSystem.SYSTEM` 跟随；受保护文件的字节进入网盘、设备复制或 MCP 写入。

父目录替换是同一类：`requireNotSymbolicLink` 只看叶子；`RandomAccessFile("/share/dir/file")` 会跟随中间层链接。

这与 FS-30 不是同一条洞。FS-30 是「没有策略 / 永远跟随」。落地修复是 check-then-open。本条是剩下的 time-of-use 窗口。剪贴板粘贴不是另一条复制原语，它走同一条 `copyTo` 链，不另开 ID。

设备复制对文件源只在 `copyPath` 入口做一次 `deniedException` + `getFile`；`ensureCopyTreeIsOrdinary` 只遍历**目录**。单文件在 `getFile` 与 `copyTo` 之间同样有窗口。MCP `LocalFileEndpointGateway` 的 `requireOrdinaryPath` 之后仍调用 `FileUtils.writeBytes`，写入路径同样跟随。

### 影响

- 同机进程（或同一用户下的恶意脚本）可以把一次「复制普通文档到 SFTP」变成外泄应用机密。
- 窗口在网盘上传上最大，因为握手在打开本地 fd **之前**。
- 不需要邻机、不需要 MCP token、不需要 Intent。需要的是：用户（或自动化任务）正在把 Local 源复制到会跟随的后端，同时另一进程能替换该路径。

### 利用场景

用户把下载目录里的普通文件复制到已连接的 SFTP。复制开始后，本机另一进程在 SSH 握手期间把该路径换成指向 `tls-identity` 的符号链接。`SftpNetworkClient.upload` 用 Okio 跟随，远端出现身份文件副本。同一操作若在检查与打开之间路径不变，FS-30 的门仍有效。

<details>
<summary>修复方案</summary>

把「不是符号链接」当成**打开标志**，而不是事先 `stat`：

1. **JVM：用 `FileChannel.open(path, READ/WRITE, LinkOption.NOFOLLOW_LINKS)`**（叶子必须不存在时加 `CREATE_NEW`）。不要在这些路径上用 `FileInputStream` / `RandomAccessFile` / Okio `FileSystem.SYSTEM`。
2. **拿到 fd 之后**再对 `path.toRealPath(LinkOption.NOFOLLOW_LINKS)`（或 `/proc/self/fd/N`）跑一次 `SensitiveFileAccessPolicy`。
3. 父目录用 `NOFOLLOW` 走（与 `PathUtils.isPathWithinRoot` 相同），并抓住 directory fd（`openat` 链），避免父目录在叶子打开前被换掉。
4. **SFTP/SMB/FTP：在 `withClient` 之前用 `NOFOLLOW` 打开本地 fd**，再对流那一个 fd。握手不得成为第一次打开。
5. Android：`localWriteBytes` / range read 走 `Os.open(..., O_NOFOLLOW)`，不要只在 `FileUtils.writeBytes` 里预先检查。

```kotlin
internal fun openLocalSourceNoFollow(path: String): FileChannel {
    SensitiveFileAccessPolicy.deniedException(path)?.let { throw it }
    return FileChannel.open(
        Paths.get(path),
        StandardOpenOption.READ,
        LinkOption.NOFOLLOW_LINKS,
    )
}

override suspend fun upload(localPath: String, remotePath: String, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> {
    val channel = openLocalSourceNoFollow(localPath)
    return channel.use { source ->
        withClient { client ->
            client.write(normalizePath(remotePath)).use { output ->
                copyChannelWithProgress(source, output, size, onProgress)
            }
            true
        }
    }
}
```

WebDAV 已走 `readFileChunks`；修好 `FileUtils` 的 NOFOLLOW 打开后，这条链会一起闭合。

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun localUploadDoesNotFollowLeafReplacedAfterPolicyCheck() = runTest {
    val directory = Files.createTempDirectory("toctou-upload")
    val ordinary = directory.resolve("doc.txt")
    val protectedFile = resolveDesktopApplicationDataDirectory().resolve("tls-identity")
    Files.writeString(ordinary, "ordinary")
    val client = RecordingNetworkClient(
        beforeOpen = {
            Files.deleteIfExists(ordinary)
            Files.createSymbolicLink(ordinary, protectedFile)
        }
    )
    val result = copyLocalToNetwork(client, localPath = ordinary.toString(), remotePath = "/upload/doc.txt")
    assertTrue(result.isFailure)
    assertTrue(client.uploadedPayloads.none { bytes -> bytes.contains(protectedFile) || looksLikeTlsIdentity(bytes) })
}

@Test
fun fileUtilsReadDoesNotFollowSymlinkCreatedAfterStat() = runTest {
    val directory = Files.createTempDirectory("toctou-read")
    val ordinary = directory.resolve("notes.txt")
    Files.writeString(ordinary, "notes")
    val opener: () -> Unit = {
        Files.deleteIfExists(ordinary)
        Files.createSymbolicLink(ordinary, directory.resolve("secret"))
    }
    val flow = FileUtils.readFileChunks(FileAccessPermission.Allowed, ordinary.toString(), 1024)
    opener()
    val chunks = flow.toList()
    assertTrue(chunks.all { item -> item.isFailure })
}
```

测试只断言替换成链接后失败，并且接收端没有受保护字节。不要在夹具里打印机密内容，也不要给网盘客户端接真实远端。

</details>

---

## FS-34：JVM/Android FTPS 不校验证书主机名

* 严重度：高
* 类别：tls_hostname_verification
* 修复状态：已修复
* 位置：
  - `core/src/jvmMain/kotlin/com/folderspan/service/network/FtpNetworkClient.kt`（约 21–51 行：`if (ftpExtras.ftpsEnabled) FTPSClient() else FTPClient()`，随后 `connect` / `login` / `execPROT("P")`）
  - `core/src/androidMain/kotlin/com/folderspan/service/network/FtpNetworkClient.kt`（同样实现）
  - Commons Net 3.13.0（`gradle/libs.versions.toml` 的 `commons-net = "3.13.0"`）：`FTPSClient()` 默认 `hostnameVerifier == null`，`setEndpointCheckingEnabled` 默认 false
  - 对照：`core/src/iosMain/kotlin/com/folderspan/service/network/FtpNetworkClient.kt`（`CURLOPT_USE_SSL = 3`，curl 默认 `VERIFYPEER` / `VERIFYHOST`）
* 置信度：0.93

### 描述

用户可以把网盘协议配成 FTPS。JVM/Android 客户端构造的是无参数 `FTPSClient()`。Commons Net 3.13.0 源码写明：

> Warning: the hostname is not verified against the certificate by default, use `setHostnameVerifier(HostnameVerifier)` or `setEndpointCheckingEnabled(boolean)` (on Java 1.7+) to enable verification.

生产代码两处都没有调用。握手使用系统默认 `TrustManager`（接受公有 CA），但**不**做 CN/SAN 匹配。`execPBSZ(0)` / `execPROT("P")` 只保护数据通道加密，不补主机名。

这不是「局域网自签分享」。那是产品文档化的设备/分享 TLS。本条是用户把 FolderSpan 连到**具名**的 `ftp.example.com`（或 IP 字面量加证书）时，中间人可以出示任意一张公有 CA 证书。链能通过，主机名不查，口令与文件进攻击者套接字。

iOS 走 curl，默认校验主机；同一用户在桌面/Android 上更弱。SFTP 在空白 `known_hosts` 时已 `RejectAll`（前几轮），FTPS 没有对等防护。仓库里唯一的 `HostnameVerifier { _, _ -> true }` 在 `McpHttpServiceJvmTest`，不是生产代码。

### 影响

- 对用户配置的 FTPS 主机做 TLS 中间人，可得到 FTP 用户名/口令，以及随后上传下载的文件。
- 任意公有 CA 证书都够用，不必伪造目标主机名。
- 开启 FTPS 的用户以为通道已认证；实际只加密到中间人。

### 利用场景

用户添加「FTPS、被动模式、主机 `ftp.example.com`」。同一网络的中间人在 21/990 上终止 TLS，出示一张与 `ftp.example.com` 无关但链完整的证书。FolderSpan 登录并传输。iOS 客户端在同一配置下会失败。

<details>
<summary>修复方案</summary>

1. **显式开启端点识别。** `FTPSClient().apply { setEndpointCheckingEnabled(true) }`。不要关掉它。
2. 需要自定义 TrustManager 时，仍然做主机名校验（HTTPS 算法，或 `HttpsURLConnection.getDefaultHostnameVerifier()`）。
3. 不要为了自签 FTPS 在生产里放 Trust-All。若产品要支持自签，做成按主机钉扎，而不是全局关闭校验。
4. 隐式 FTPS（990）与显式 AUTH TLS 都要覆盖。
5. 回归测试对准「CN/SAN 不匹配则失败」，不要对准「任意证书都能连」。

```kotlin
val client: FTPClient = if (ftpExtras.ftpsEnabled) {
    FTPSClient().apply {
        setEndpointCheckingEnabled(true)
    }
} else {
    FTPClient()
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun ftpsClientEnablesEndpointIdentification() {
    val enabled = inspectProductionFtpsSetup()
    assertTrue(enabled.endpointCheckingEnabled)
    assertTrue(enabled.hostnameVerifier != null || enabled.endpointCheckingEnabled)
}

@Test
fun ftpsHandshakeFailsWhenCertificateNameDoesNotMatchHost() = runTest {
    val server = startTlsFtpServer(certificateCn = "unrelated.example")
    val network = Network(
        host = "127.0.0.1:${server.port}",
        extras = NetworkExtras(ftp = FtpExtras(ftpsEnabled = true, passiveMode = true)),
    )
    val result = FtpNetworkClient(network).list("/")
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is SSLException || result.exceptionOrNull()?.cause is SSLException)
}
```

测试只断言不匹配的证书被拒绝。不要在夹具里放真实第三方主机口令，也不要演示如何伪造证书链。

</details>

---

## FS-35：设备 API 与链路分享 TLS 只过滤 `SSLv*`，Android 8–9 仍可能接受 TLS 1.0/1.1

* 严重度：中
* 类别：weak_tls_protocol
* 修复状态：已修复
* 位置：
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/server/raw/RawTlsHttpServer.jvm.kt`（约 103 行）
  - `core/src/androidMain/kotlin/com/folderspan/service/http/server/raw/RawTlsHttpServer.android.kt`（约 98 行）
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/server/LinkShareRawHttpServer.jvm.kt` 与 Android 对应实现（约 134 行）
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/tls/DeviceTlsIdentity.jvm.kt` / Android（`SSLContext.getInstance("TLS")`，约 76 行）
  - `gradle/libs.versions.toml`（`android-minSdk = 26`）
  - 对照：`core/src/iosMain/cinterop/openssl_wrapper.h`（`SSL_CTX_set_min_proto_version(ctx, TLS1_2_VERSION)`，约 257 行）
* 置信度：0.82

### 描述

四处服务端套接字都把协议集收成「以 `TLS` 开头」：

```kotlin
socket.enabledProtocols = socket.enabledProtocols.filter { item -> item.startsWith("TLS") }.toTypedArray()
```

这只丢掉 `SSLv*`。在 API 26–28 上，`SSLServerSocket.enabledProtocols` 仍常见 `TLSv1` / `TLSv1.1`。内部 TLS 套接字绑在 `127.0.0.1`，但 `HttpPortSchemeSwitchingProxy` 对局域网 `bindLan = true` 绑定公开端口；非 HTTP 前缀的连接被转到内部 TLS 端口。因此邻机**可以**打到这条 TLS 监听。

现代桌面 JDK 11+ 往往已经通过 `jdk.tls.disabledAlgorithms` 关掉 1.0/1.1；**现实缺口在 Android 8–9**。iOS 服务端已经钉在 TLS 1.2。局域网自签服务缺 HSTS 不在本条范围（产品设计）；协议下限不是。

设备证书是自签的，客户端另有指纹钉扎。本条不是「任意人可以假冒设备」，而是协议套件允许已知的 TLS 1.0 类降级，与 iOS / 现代 JDK 基线不一致。

### 影响

- Android 8–9 主机上，邻机可以把握手降到 TLS 1.0/1.1。
- 与自签身份叠加后，不能单靠这一条伪造已钉扎设备；仍削弱仅依赖系统 TLS 的浏览器 / 旧客户端。
- 桌面 JDK 若发行版关掉了 1.0/1.1，则不受影响。

### 利用场景

主人在 Android 9 上开启设备 HTTP 或链路分享 HTTPS。邻机用只提供 TLS 1.0 的客户端连接公开端口。scheme-switching 代理把非 HTTP 字节转到内部 `SSLServerSocket`。过滤器仍包含 `TLSv1`，握手成功。iOS 上同一构建会拒绝。

<details>
<summary>修复方案</summary>

1. **显式只启用 TLS 1.2 与 1.3**，再与 `supportedProtocols` 求交。
2. JVM / Android 的设备 API 与链路分享套接字用同一辅助函数，避免四处各写一遍。
3. iOS 已钉 TLS 1.2，不要回退。
4. 客户端 SSLContext 若用于连对等设备，同样设下限（对等连接已有指纹钉扎，服务端下限仍要关）。

```kotlin
internal fun SSLServerSocket.restrictToModernTls() {
    val allowed = arrayOf("TLSv1.3", "TLSv1.2")
    enabledProtocols = allowed.filter { item -> item in supportedProtocols }.toTypedArray()
    check(enabledProtocols.isNotEmpty()) { "no modern TLS protocol is supported" }
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun rawTlsServerDoesNotEnableLegacyProtocols() {
    val socket = DeviceTlsIdentity.createServerSslContext()
        .serverSocketFactory
        .createServerSocket(0) as SSLServerSocket
    socket.restrictToModernTls()
    try {
        val enabled = socket.enabledProtocols.toSet()
        assertTrue(enabled.all { item -> item == "TLSv1.2" || item == "TLSv1.3" })
        assertFalse("TLSv1" in enabled)
        assertFalse("TLSv1.1" in enabled)
        assertTrue(enabled.isNotEmpty())
    } finally {
        socket.close()
    }
}
```

测试只断言启用的协议集合。不要附带 TLS 1.0 客户端握手脚本。

</details>

---

## FS-36：未认证配对在共享控制信号量里阻塞最多 10 分钟

* 严重度：中
* 类别：availability / resource_exhaustion
* 修复状态：已修复
* 位置：
  - `core/src/serverRouteMain/kotlin/com/folderspan/routes/RawHttpDeviceRoutes.kt`（`handleDeviceConnect` 约 128–277 行；`waitForDeviceApproval` 约 353–388 行；`waitForFirstDeviceApproval` 约 391–424 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/client/HttpRouteClientManager.kt`（`CONNECT_TIMEOUT = 600`，约 375 行）
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/server/raw/RawTlsHttpServer.jvm.kt`（`handleClient` 在 `routeCapacity.withAdmittedRequestOrNull` 里调用 `dispatcher.dispatch`，约 176–178 行；`MAX_CONCURRENT_CONTROL_REQUESTS = 32`、`MAX_CONCURRENT_CLIENTS = 96`，约 454–455 行）
  - `core/src/serverRouteMain/kotlin/com/folderspan/routes/RawHttpModels.kt`（非 `DATA_ROUTES` 的路径都算 Control，约 41–43、142–156 行；`/api/devices/connect` 与 `/api/webrtc/signaling/*` 都不在数据集合里）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/FileShareAccessKey.kt`（access key 默认 `enabled = false`）
* 置信度：0.88

### 描述

配对在未开 access key 时本来就不认证，这是产品设计。问题是**等待审批占用了共享控制名额**。

`waitForFirstDeviceApproval` 在 `dispatch()` 内 `withTimeout(600.seconds)` 循环，直到主人点允许/拒绝。`RawHttpRouteCapacity.withAdmittedRequestOrNull` 对 Control 走 `controlSemaphore.withPermit { block() }`，把整个 `dispatch` 包进去。32 个进行中的 connect 占满控制池；更多客户端仍占用 `clientSemaphore`（96），并在 `withPermit` 上阻塞。控制池耗尽时新控制请求直接 503。

`/ping`、设备心跳、主题、WebRTC 信令与 connect 共用 Control。数据面（读/写/流）有单独的 64 名额，所以文件传输不一定停，但发现、配对、信令会停数分钟。

这不是「配对应当立刻 200」。产品需要等人。缺口是把这次等待算进与 ping/信令共享的 32 个名额，而不是独立的小桶或 202+轮询。

### 影响

- 邻机（默认无 access key）可以让设备 API 控制面在最多 10 分钟内不可用。
- 已连接设备的心跳与浏览器 WebRTC 信令会一起饿死。
- 不读取文件，不提升权限；这是可用性。

### 利用场景

主人在局域网打开文件分享。邻机打开 32 条到 `/api/devices/connect` 的连接并保持。每条在服务端等到超时。同时 `/ping` 与 `/api/webrtc/signaling/join` 得到 503 或一直挂起，直到第一条 connect 结束。

<details>
<summary>修复方案</summary>

1. **给配对单独的小桶**（例如 2–4），不要占用共享控制 permit 过完整等待。
2. 更好：connect 立刻 **202**，把请求放进 `deviceState.connectionRequest`，让客户端短轮询；服务端工作线程不占 HTTP 控制名额。
3. 超额 connect 回 **429**，不要 503 混成容量故障。
4. 可选：按源 IP 限制未决配对（例如每 IP 1 条）。
5. 开了 access key 时，未持钥匙的客户端不应当能占住等待循环（现有 403 已在 `dispatch` 入口；保持先于 `waitFor*`）。

```kotlin
internal val PAIRING_ROUTES = setOf("/api/devices/connect")

internal fun classifyRawHttpRoute(path: String): RawHttpRouteClass = when {
    path in DATA_ROUTES -> RawHttpRouteClass.Data
    path in PAIRING_ROUTES -> RawHttpRouteClass.Pairing
    else -> RawHttpRouteClass.Control
}
```

`withAdmittedRequestOrNull(Pairing)` 使用 `Semaphore(4)`，并考虑把等待移出 permit：先插入 WAITING，回 202，permit 在写入响应后释放。

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun pairingWaitDoesNotStarvePing() = runTest {
    val capacity = RawHttpRouteCapacity(
        maxControlRequests = 2,
        maxDataRequests = 4,
        maxIdleDataKeepAliveConnections = 0,
        maxPairingRequests = 2,
    )
    val pairing = List(2) {
        async {
            capacity.withAdmittedRequestOrNull(RawHttpRouteClass.Pairing) {
                delay(30_000)
                "connect"
            }
        }
    }
    yield()
    val ping = capacity.withAdmittedRequestOrNull(RawHttpRouteClass.Control) { "pong" }
    val extraPairing = capacity.withAdmittedRequestOrNull(RawHttpRouteClass.Pairing) { "extra" }
    assertEquals("pong", ping)
    assertEquals(null, extraPairing)
    pairing.forEach { item -> item.cancel() }
}
```

测试只断言控制面在配对占满时仍能进、超额配对被拒。不要对真实局域网主机打满 32 条连接。

</details>

---

## FS-37：发现与配对把几乎无界的设备记录写进数据库

* 严重度：中
* 类别：unbounded_persistence / inventory_pollution
* 修复状态：已修复
* 位置：
  - `core/src/serverRouteMain/kotlin/com/folderspan/routes/RawHttpDeviceRoutes.kt`
    - `handlePing` 约 54–76 行：只检查 `device.id` 非空且 `<= MAX_DISCOVERY_DEVICE_ID_LENGTH`（256），然后 `insertUnknownDeviceIfNeeded`
    - `handleDeviceConnect` 约 276–277 行：插入前**不**应用该长度上限
    - `insertUnknownDeviceIfNeeded` / `persistDiscoveredDevice` 约 427–486 行：新 `id` 就 `deviceQueries.insert`
  - `core/src/commonMain/kotlin/com/folderspan/service/data/SocketDevice.kt`（`id` / `name` / `host` 为无界 `String`，约 39–43 行）
  - `core/src/jvmMain/kotlin/com/folderspan/service/http/server/raw/RawTlsHttpServer.jvm.kt`（缓冲 body 上限 8 MiB，约 462 行）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/FileShareAccessKey.kt`（默认关闭）
* 置信度：0.84

### 描述

`/ping`（带发现头）和 `/api/devices/connect` 在默认配置下对局域网是未认证的。Ping 把 id 限到 256 字符，但 `name` / `host` 没有对等上限。Connect **连 id 上限都没有**。唯一的 protobuf `id` 会：

1. 加入 `deviceState.socketDevices`（Compose 快照列表，驱动 UI）；
2. `deviceQueries.insert` 进 SQLite。

`allowExistingUpdates = false` 的 ping 不会刷新已有行，但每个新 id 仍是一行。Connect 在等待审批前就会插入。8 MiB 的 body 上限仍然允许非常大的 `name`/`host`（protobuf 开销很小）。

这不是 CORS、也不是 TLS 信任。它是发现协议缺少库存边界。产品需要把看见的对等节点记下来；缺口是没有「每来源上限 / 字段长度 / 未决未知设备上限」。

### 影响

- 邻机可以让设备列表和 SQLite 充满垃圾设备。UI 变慢，发现噪声变大。
- 超长 `name` 进入通知与连接请求文案（`waitForDeviceApproval` 把 `device.name` 写进 `updateConnectionRequest`）。
- 不授予文件权限；配对仍要主人点允许。这是存储与 UI 拒绝服务。

### 利用场景

主人打开设备 HTTP。邻机发送大量带发现头的 `/ping`，每个都是新的短 id 和很长的 `name`。`persistDiscoveredDevice` 为每个 id 插入一行。设备页出现成千上万条「新设备」。Connect 路径还可以用超过 256 字符的 id 绕过 ping 已有的那条上限。

<details>
<summary>修复方案</summary>

1. **Connect 使用与 ping 相同的 id 长度/字符集上限。** 超长 id 回 400，不要插入。
2. 限制 `name` / `host` / `pathSeparator`（例如 name 256、host 253、pathSeparator 8）。截断或拒绝。
3. **限制未决未知设备**：每源 IP 一个小配额，全局未决上限（例如 64）。超额丢弃或忽略。
4. 不要把 8 MiB protobuf 里的字符串原样写入 UI 绑定的快照列表。
5. 可选：对重复的 `(remoteHost, id)` 去抖，避免同一对等节点刷屏。

```kotlin
private fun SocketDevice.validatedForDiscovery(): SocketDevice? {
    if (id.isBlank() || id.length > MAX_DISCOVERY_DEVICE_ID_LENGTH) return null
    if (name.length > MAX_DISCOVERY_DEVICE_NAME_LENGTH) return null
    if (host.length > MAX_DISCOVERY_DEVICE_HOST_LENGTH) return null
    return this
}

internal suspend fun RawHttpApiDispatcher.handleDeviceConnect(request: RawHttpRequest): RawHttpResponse {
    val device = body.device.validatedForDiscovery()
        ?: return protobufFailure(400, IllegalArgumentException(AppStrings.ui_invalid_discovery_device_id))
    ...
}
```

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun connectRejectsOversizedDeviceIdWithoutInserting() = runTest {
    val dispatcher = rawDispatcherWithInMemoryDb()
    val device = sampleSocketDevice(id = "d".repeat(257), name = "peer")
    val response = dispatcher.dispatch(connectRequest(device))
    assertEquals(400, response.statusCode)
    assertEquals(null, dispatcher.database.deviceQueries.queryById(device.id).executeAsOneOrNullAwait())
}

@Test
fun pingStopsInsertingAfterUnknownDeviceCap() = runTest {
    val dispatcher = rawDispatcherWithInMemoryDb(maxUnknownDevices = 8)
    repeat(20) { index ->
        dispatcher.dispatch(pingRequest(sampleSocketDevice(id = "peer-$index")))
    }
    val stored = dispatcher.database.deviceQueries.selectAll().executeAsListAwait()
    assertTrue(stored.size <= 8)
}
```

测试只断言拒绝与上限。不要对真实发现地址洪水式发送 ping。

</details>

---

## 已检查、不作为本轮新漏洞

| 主题 | 结论 |
| --- | --- |
| FS-01～FS-31 | 源码与测试表明已落地或（FS-06）为误报，不重复开洞。第七轮 FS-29（MCP `allowUpload` 需 `files.write` + 宿主 AND）、FS-30（Local→Network 策略 + 叶子链接检查）、FS-31（SEND 不把 Intent flag 当 grant）均在树中。 |
| 链路分享 ticket 单次消费 | `consumeLinkShareTicket` 是一次 `SnapshotStateMap.remove`。顺序第二次兑换得到 null。Compose 快照 map 从 IO 线程并发写入并不理想，但未证实「同一 ticket 签发两个 12h 会话」的稳定利用链；本轮不另开 ID。 |
| MCP `folderspan_files_share_device` 无 `files.write` | 对端仍要心跳确认；不是 FS-29 那种静默可写入口。 |
| MCP `allowHidden` | 只影响已选 locator 的展示偏好，且 `FilesShare`+`FilesRead` 已能读这些路径。不按 FS-29 同类 BFLA 开洞。 |
| MCP mass assignment / BOPLA | `objectSchema` 设 `additionalProperties: false`；`McpJsonSchemaValidator.validateObject` 拒绝未知键。写工具测试覆盖未知字段。 |
| 口令限制器空主机 | 空白 key 归一成 `"unknown"`；限制器 Mutex 保护计数。不是桶塌陷。4096 IP 溢出驱逐只在理论局域网洪泛下成立，本轮不单开。 |
| StreamSaver `Service-Worker-Allowed: /` | 注册 scope 是 `./`（`/static/streamsaver/`）。过宽响应头是纵深防御气味，第五轮已记录。 |
| JS 预览无 sandbox | `text/javascript` 作为文档导航通常显示/下载，不当脚本执行。不按存储 XSS 开洞。 |
| CORS `Access-Control-Request-Headers` 回显 | 先做广告地址同源白名单（FS-21）。低风险，不开新 ID。 |
| 缺 HSTS | 局域网自签分享的预期行为，前几轮已排除。 |
| SFTP 空白 known_hosts | Android/JVM `RejectAll`，iOS 显式失败。 |
| WebDAV HTTPS | 系统信任 Ktor 客户端；PROPFIND 用正则而非 XML 解析器。 |
| 归档 zip-slip | `FolderSpanArchiveCodec.normalizeRelativePath` 拒绝 `..`、`\`、绝对路径。残留跟随问题归入 FS-33，不另开解析器洞。 |
| SQLDelight | 参数化查询。 |
| 剪贴板 `content://` | Android 读取器把非 `file://` 方案放进 texts；`openFromClipboard` 按用户发起的文件管理导航打开 `file://`。FS-31 的剪贴板 grant 残留视为已处理。粘贴复制走与 FS-33 相同的 `copyTo` 链。 |
| 桌面拖放到 Device | `requestDeviceDropUpload` 弹出确认；源路径经 `prepareDesktopExternalFiles`（FS-25）。不是新的 confused-deputy 入口。 |
| Trust-All 残留 | 生产搜索 0 命中；测试里的 `HostnameVerifier { _, _ -> true }` 仅用于 MCP HTTPS 测试。 |
| Capture Trust-All + access key | FS-20 / FS-24 已修；ping 用每请求连接池且不带 access key。 |

## 修复优先级建议

1. **FS-33（高）**：所有 Local 源打开改为 `NOFOLLOW`，并在持有 fd 之后重跑 `SensitiveFileAccessPolicy`。网盘 `upload` 必须在协议握手**之前**打开本地 fd。
2. **FS-34（高）**：JVM/Android `FTPSClient` 启用端点识别；用证书名不匹配的夹具锁回归。
3. **FS-32（中）**：口令尝试在 PBKDF2 之前占用；每 IP in-flight 校验上限为 1。
4. **FS-36（中）**：配对移出共享控制信号量（独立小桶或 202+轮询）。
5. **FS-37（中）**：connect/ping 与发现共用长度上限，并给未决未知设备加帽。
6. **FS-35（中）**：四处 TLS 套接字钉在 TLS 1.2/1.3；Android 8–9 是需要回归的平台。

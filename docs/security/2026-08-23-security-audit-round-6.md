# FolderSpan 安全审计报告（第六轮）

- 审计日期：2026-08-23
- 审计范围：全仓（`app/`、`core/`、`proMain/`），非仅当前 PR diff
- 前置：
  - 第一轮见 [2026-08-21 全仓安全审计报告](2026-08-21-security-audit-report.md)（FS-01～FS-05 已修复，FS-06 误报）
  - 第二轮见 [2026-08-21 全仓安全审计报告（第二轮）](2026-08-21-security-audit-round-2.md)（FS-07～FS-10 已修复）
  - 第三轮见 [2026-08-21 全仓安全审计报告（第三轮）](2026-08-21-security-audit-round-3.md)（FS-11～FS-16 已修复）
  - 第四轮见 [2026-08-22 全仓安全审计报告（第四轮）](2026-08-22-security-audit-round-4.md)（FS-17～FS-20 已修复）
  - 第五轮见 [2026-08-23 全仓安全审计报告（第五轮）](2026-08-23-security-audit-round-5.md)（FS-21～FS-27 已修复）
- 方法论：Claude Marketplaces 插件 `cybersecurity-skills@anthropic-cybersecurity-skills` v1.3.0
  覆盖技能：`testing-for-xss-vulnerabilities`、`performing-csrf-attack-simulation`、`testing-for-open-redirect-vulnerabilities`、`testing-for-xxe-injection-vulnerabilities`、`performing-clickjacking-attack-test`、`implementing-secret-scanning-with-gitleaks`、`performing-directory-traversal-testing`、`testing-for-sensitive-data-exposure`、`auditing-mcp-servers-for-tool-poisoning`、`testing-for-host-header-injection`、`exploiting-template-injection-vulnerabilities`、`testing-for-email-header-injection`
- 约束：本报告不含攻击载荷或 exploit PoC。折叠区内的「验证代码」均为防御性回归测试（断言安全属性），用于确认修复是否生效。
- 威胁模型：局域网邻机、同机恶意进程/网页、不可信 Android Intent、SFTP 中间人、已授权设备上的 token 盗用。本机文件分享服务按产品设计会对局域网可达。
- 本轮只登记相对前五轮**仍未修复的新问题**。已落地的 FS-01～FS-27、已排除项与产品明确文档化的同步行为不重复开洞。

## 审计发现总览

| ID | 严重度 | 类别 | 标题 | 状态 |
| --- | --- | --- | --- | --- |
| FS-28 | 高 | template_injection / command_injection | 链路分享批量下载脚本把 User-Agent、目录名与 URL 路径原样写入 bash / PowerShell / CMD 赋值，运行官方助手即可在访客本机执行命令 | 已修复 |

---

## FS-28：链路分享下载脚本对 User-Agent 与路径占位符未做 shell 转义

* 严重度：高
* 类别：template_injection / command_injection
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/service/http/server/linkshare/LinkShareRouteDispatcher.kt`（`readShellFile` 按语言转义占位符；`respondByRequestType` 的目录 HTML 带 `Cache-Control: no-store`）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/server/linkshare/LinkShareDownloadScriptEscaping.kt`（bash / PowerShell / CMD 各自的赋值转义、目录名清洗、ROOT_PATH 编码）
  - `core/src/commonMain/composeResources/files/share-file/shell/script.sh`（`USER_AGENT="#USER_AGENT#"`、`TARGET_DIR="./#TARGET_DIR#"`、`root_path="#ROOT_PATH#"`）
  - `core/src/commonMain/composeResources/files/share-file/shell/script.ps1`（`$USER_AGENT = "#USER_AGENT#"`、`$TARGET_DIR = ".\#TARGET_DIR#"`、`$ROOT_PATH = "#ROOT_PATH#"`）
  - `core/src/commonMain/composeResources/files/share-file/shell/script.bat`（`set "USER_AGENT=#USER_AGENT#"` 等，且 `EnableDelayedExpansion`）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/server/templates/route/IndexTemplate.kt`（`renderBatchDownloadModal` 把脚本正文放进 `<pre>`，约 519–521 行）
  - `core/src/commonMain/composeResources/files/share-file/static/share/share-index.js`（「下载脚本」按钮按 `pre.textContent` 生成 `download_script.sh|ps1|bat`）
  - 回归测试：`LinkShareDownloadScriptEscapingTest`、`LinkShareRouteDispatcherJvmTest.downloadScriptsEscapeUserAgentAndPathMetacharacters`
* 置信度：0.93

### 描述

目录页的「批量下载」会为当前请求现场生成三份官方助手脚本。`readShellFile` 用 `String.replace` 填占位符，**没有任何 shell / PowerShell / CMD 转义**：

```kotlin
return localizedTemplate
    .replace("#API_SERVER#", fullUrl)
    .replace("#USER_AGENT#", request.header("User-Agent").orEmpty())
    .replace("#LINK_SHARE_SESSION_HEADER#", LINK_SHARE_SESSION_HEADER)
    .replace("#LINK_SHARE_SESSION_TOKEN#", /* 会话 token */)
    .replace("#ROOT_PATH#", if (request.path == "/") "" else request.path)
    .replace("#TARGET_DIR#", if (paths.isEmpty()) "" else decodeLinkShareUrlComponent(paths.last(), plusAsSpace = true))
```

模板把这些值放进**会立刻执行的赋值语句**，而不是注释或纯数据文件：

- bash：`USER_AGENT="…"`、`TARGET_DIR="./…"`、`root_path="…"`
- PowerShell：`$USER_AGENT = "…"` 等
- CMD：`set "USER_AGENT=…"`，并开启 `EnableDelayedExpansion`

会话 token 与会话头名由服务端常量 / `32.randomString(includeSpecial = false)` 生成，字符集限于字母数字，本身进不了命令分隔符。`#API_SERVER#` 走 `resolvePublicHttpHost`（FS-21 之后只允许广告地址或 loopback），scheme 也来自当前连接。真正未约束的是：

1. **`User-Agent`**：HTTP 头解析会剥 CR/LF，但引号、`$()`、反引号、PowerShell 子表达式、CMD 的 `!` 延迟展开都可以进来。浏览器默认 UA 通常无害；扩展、代理、非浏览器客户端、或访客自己改 UA 后下载脚本则不受限。
2. **`TARGET_DIR`**：当前 URL 最后一段，URL 解码后的**目录名**。分享根下任何一层文件夹名都会进脚本。`resolveShareUploadItem` 只拒绝 `.` / `..` / NUL，**不拒绝引号或元字符**；开启上传的分享里，访客可以创建带元字符的目录。
3. **`ROOT_PATH`**：`request.path`（已解码的整段路径）。同一套目录名会出现在 bash 的 `root_path="…"` 里。

HTML 页用 kotlinx.html 的 `+scriptInfo.second` 输出，会转义 `<` `&` 等，**不是反射 XSS**。危害发生在产品明确引导的下一步：访客点「下载脚本」并在本机运行 `download_script.sh` / `.ps1` / `.bat`。赋值语句里一旦提前闭合引号，后续文本会按对应 shell 执行。

这与「分享者本来就能放一个恶意可执行文件」不是同一条面：

- 官方助手被宣传为安全的批量下载方式，访客对这份脚本的信任高于分享目录里的随机文件。
- 目录名本身看起来可以完全无害（或只是略奇怪的 Unicode / 标点），危险逻辑写在 FolderSpan 生成的包装赋值里。
- 开启上传时，**已获上传权的人**不必改分享者自己的文件，只要建一个目录，就能让之后浏览该目录并下载官方脚本的人中招。

### 影响

- 运行官方 bash / PowerShell / CMD 助手的访客，会在自己的用户权限下执行目录名或 User-Agent 注入的命令。
- 脚本里带有当前链路分享会话 token（`X-FolderSpan-Link-Session`）。命令执行成功后可直接复用该会话拉取分享内容；若脚本被转发，会话也会随文件走。
- 不需要突破 CSP、也不需要分享者本机中招；中招的是**下载并执行助手的那台机器**。

### 利用场景

主人开启局域网链路分享，并允许网页上传（或分享树里已有可被上传者创建的子目录）。另一台已授权设备在某个子目录下创建名称含 shell 元字符的文件夹。之后的访客进入该目录、使用「批量下载 → 下载脚本」、按页面提示运行生成的 `.sh` / `.ps1` / `.bat`。生成逻辑把该目录名写进未转义的赋值，命令在访客本机执行。

User-Agent 是第二条独立注入点：只要生成脚本的那次请求带有含元字符的 UA，同一份下载文件就会包含它。非浏览器客户端、本地代理改写 UA 后再保存脚本，效果相同。

<details>
<summary>修复方案</summary>

1. **不要把未校验的字符串拼进会执行的脚本语法。** 对 bash 使用单引号并转义内嵌 `'`（`'` → `'\''`）；对 PowerShell 使用单引号并加倍内嵌 `'`；对 CMD 禁止元字符或改用 `set "VAR=value"` 之前先把 `%` `!` `"` `&` `|` `<` `>` `^` 全部剔除/编码。更稳妥的做法是：占位符只允许 `[A-Za-z0-9._-]`，其余替换成 `_`，超长截断。
2. **`User-Agent` 不必进脚本。** curl / Invoke-WebRequest 可以固定为 `FolderSpan-LinkShare-Script` 一类产品 UA。会话绑定若仍要 UA 指纹，应在服务端对脚本通道放宽或改用只认 session token 的脚本专用校验，而不是回显客户端 UA。
3. **`TARGET_DIR` 不要用分享目录名。** 本地落盘目录改成固定相对名（例如 `./folderspan-download`），或让用户在运行时传入。需要保留原名时，先做与 `isSafeSharedRelativeSegments` 同类的白名单，再写入脚本。
4. **`ROOT_PATH` 按路径段分别百分号编码后再写入**，保证脚本里的远程路径只有 `/` 与 `%XX`，不再出现引号或 `$`。
5. 会话 token 已是安全字符集，可保留；仍应避免把完整脚本（含 token）缓存在可被第三方读取的 HTML 里过久。目录 HTML 可加 `Cache-Control: no-store`。
6. 现有 `ShareDownloadScriptResourceTest` 只检查模板字面量，应补「元字符进入占位符后，赋值语句仍是一条字面量、不会多出未闭合引号」的断言。

```kotlin
internal fun escapeForDoubleQuotedShell(value: String): String {
    return buildString(value.length) {
        value.forEach { char ->
            when {
                char == '\\' || char == '"' || char == '$' || char == '`' -> {
                    append('\\')
                    append(char)
                }
                char == '\n' || char == '\r' || char.code == 0 -> append('_')
                else -> append(char)
            }
        }
    }
}

internal fun sanitizeLinkShareScriptToken(value: String): String {
    val filtered = value.filter { char ->
        char.isLetterOrDigit() || char == '.' || char == '_' || char == '-'
    }
    return filtered.take(128)
}
```

`readShellFile` 应对 `USER_AGENT` / `TARGET_DIR` / `ROOT_PATH` 走转义或白名单，而不是 `replace` 原样填入。PowerShell 与 CMD 需要各自的转义函数，不能共用 bash 规则。

落地时会话指纹仍要比对原始 User-Agent，因此**没有**改成固定产品 UA，只按语言转义后写入脚本。`TARGET_DIR` 保留 Unicode 目录名，但去掉 `/` `\` 与控制字符；全被剥空时回落到 `folderspan-download`。bash / PowerShell 的 `ROOT_PATH` 按段百分号编码；CMD 不用 `%XX`（会触发环境变量展开），改为剔除 `%` `!` `"` `&` `|` `<` `>` `^`。目录 HTML 已加 `Cache-Control: no-store`。

</details>

<details>
<summary>验证代码</summary>

```kotlin
@Test
fun downloadScriptsEscapeUserAgentAndPathMetacharacters() = runBlocking {
    val userAgent = "Mozilla/5.0"
    val metacharacters = "\"\$`"
    val script = dispatcher.readShellFileForTest(
        templatePath = "files/share-file/shell/script.sh",
        userAgent = userAgent + metacharacters,
        requestPath = "/share/project$metacharacters",
    )
    val assignment = script.lineSequence()
        .first { line -> line.startsWith("USER_AGENT=") }
    assertTrue(assignment.startsWith("USER_AGENT='") || assignment.startsWith("USER_AGENT=\""))
    assertFalse(assignment.contains(metacharacters))
    assertEquals(1, assignment.lines().size)
    assertFalse(script.contains("root_path=\"/share/project$metacharacters\""))
}

@Test
fun downloadScriptsRejectControlCharactersInSubstitutedValues() {
    val script = dispatcher.readShellFileForTest(
        templatePath = "files/share-file/shell/script.ps1",
        userAgent = "ok\nInjected",
        requestPath = "/share/dir",
    )
    assertFalse(script.contains("\nInjected"))
    val userAgentLine = script.lineSequence().first { line -> line.contains("USER_AGENT") }
    assertTrue(userAgentLine.startsWith("$") || userAgentLine.contains("USER_AGENT"))
    assertFalse(userAgentLine.contains("\n"))
}

@Test
fun cmdDownloadScriptTargetDirContainsOnlySafeCharacters() {
    val script = dispatcher.readShellFileForTest(
        templatePath = "files/share-file/shell/script.bat",
        userAgent = "FolderSpan",
        requestPath = "/share/dir-name",
    )
    val targetDir = script.lineSequence().first { line ->
        line.contains("TARGET_DIR", ignoreCase = true)
    }
    val value = targetDir.substringAfter('=').trim().trim('"')
    assertTrue(value.all { char ->
        char.isLetterOrDigit() || char == '.' || char == '_' || char == '-' || char == '\\'
    })
}
```

测试只断言生成脚本里的赋值仍是单一字面量、控制字符与引号不会原样出现；不要在夹具里放可执行命令串。

</details>

---

## 已检查、不作为本轮新漏洞

| 主题 | 结论 |
| --- | --- |
| FS-01～FS-27 | 源码与测试表明已落地或（FS-06）为误报，不重复开洞。 |
| 反射 / 存储 XSS | 目录页、面包屑、文件名走 kotlinx.html 自动转义；`share-upload.js` / `share-index.js` 对用户可控字段调用 `escapeHtml`，snackbar 文本走 `textContent`。下载脚本嵌在 `<pre>` 里同样被转义，FS-28 的危害在「运行脚本」而不是「渲染 HTML」。 |
| 点击劫持 | 普通页 `X-Frame-Options: DENY` + CSP `frame-ancestors 'self'`；仅 StreamSaver `mitm.html` 为 SAMEORIGIN，属产品需要。 |
| 开放重定向 | `sanitizeLinkShareRedirect` 要求单斜杠相对路径，拒绝 `//`、反斜杠和控制字符。 |
| CSRF 上传 | 上传强制 `X-API-Request: true`。跨站表单发不出该自定义头；带 Origin 时还要过广告地址同源。Origin 为空只覆盖非浏览器/无 Origin 客户端，不构成浏览器 CSRF。 |
| CORS 回显 `Access-Control-Request-Headers` | 预检把 ACRH 写入 `Allow-Headers`。`readHttpLine` 会丢掉 CR、在 LF 处断行，请求头进不了响应头注入。Origin 仍受 FS-21 广告地址白名单约束。设备 API 预检用固定头列表，不回显 ACRH。 |
| 响应头 CRLF | `Content-Disposition` 已替换 CR/LF；会话 / 客户端 cookie 值经 `isSafeLinkShareToken`（字母数字、`-` `_`）。通用 `writeStatusAndHeaders` 仍不剥响应侧 CR/LF，但本轮能进响应头的用户输入都已有字符集约束。 |
| XXE | WebDAV `parsePropfindResponse` 用正则抽标签，不走带 DTD/entity 的 XML 解析器。 |
| zip-slip / 静态资源穿越 | `FolderSpanArchiveCodec.normalizeRelativePath` 与 `LinkShareStaticResourcePath` 均拒绝 `..` / 绝对路径 / 反斜杠；有对应测试。 |
| MCP tool poisoning | 工具名强制 `folderspan_` 前缀，描述与 schema 为内置常量，`openWorldHint=false`，调用前校验 scope。FilesShare / FavoritesWrite 已要求 FilesRead（FS-19 / FS-23）。 |
| 密钥扫描 | 仓库内无 `AKIA` / `ghp_` / `sk_live` / `BEGIN PRIVATE KEY` 匹配。`keystore.properties` 被 gitignore 且未跟踪，本报告不收录其内容。FS-11 / FS-22 的硬编码密钥已按前轮修复。 |
| 邮件头注入 | 产品无 SMTP 发信路径。 |
| 局域网明文 HTTP 分享 | 产品设计，前五轮已记录，不因「LAN 可达」本身再开洞。 |
| StreamSaver `Service-Worker-Allowed: /` | 作用域限于分享源；第五轮已记录，不另开 ID。 |
| 进程执行 | 桌面 DPI 探测使用 `ProcessBuilder(*command)` 参数数组，不经过 shell 拼接。 |
| 密码页 `redirect` | 先 `sanitizeLinkShareRedirect` 再进模板；kotlinx.html 转义属性。 |

## 修复优先级建议

1. **FS-28 已修复**：`readShellFile` 按 bash / PowerShell / CMD 各自转义 `USER_AGENT`、`TARGET_DIR`、`ROOT_PATH`；目录 HTML 使用 `Cache-Control: no-store`。会话指纹仍使用原始 UA，脚本里只是转义后的副本。

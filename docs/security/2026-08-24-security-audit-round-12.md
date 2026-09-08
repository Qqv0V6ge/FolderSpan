# FolderSpan 安全审计报告（第十二轮）

- 审计日期：2026-08-24
- 审计范围：全仓（`app/`、`core/`、`proMain/`），非仅当前 PR diff
- 焦点：相对 FS-01～FS-46 **尚未单独覆盖的漏洞类型**（整数溢出越界写、拷贝控制 IDOR、MCP 写中止 symlink、Shizuku confused deputy、设备信任证明、WebRTC 信令入房、Content-Disposition 头注入、块偏移/Content-Range 边界）
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
  - 第十一轮见 [2026-08-24 全仓安全审计报告（第十一轮）](2026-08-24-security-audit-round-11.md)（FS-45 / FS-46 已修复，工作树未提交）
- 方法论：在前十一轮技能面之外主动扫新类型。代码探索优先用 codebase-memory-mcp（`search_graph` / `search_code` / `get_code_snippet`），关键路径再 Read 当前源文件核对。
- 约束：本报告不含攻击载荷或 exploit PoC。折叠区内的「验证代码」均为防御性回归测试（断言安全属性）。
- 威胁模型：局域网邻机、同机恶意进程/网页、不可信 Android Intent、SFTP 中间人、已授权设备上的 token 盗用。本机文件分享服务按产品设计会对局域网可达。
- 本轮只登记相对 FS-01～FS-46 **新类型**、置信度约 ≥0.8、有可利用路径的问题。已落地项、已排除项与产品明确文档化的行为不重复开洞。纯 DoS / 无界缓冲已由第十一轮覆盖，本轮不重开。

## 审计发现总览

本轮**无新登记漏洞**（不分配 FS-47 及后续编号）。

对照第九轮修复提交 `82fb756a95` 以及工作树中第十一轮 FS-45 / FS-46 修复，扫了前十一轮技能面之外的入口。凡能落到具体代码路径的线索，要么需要已授权写权限且无法写出目标文件之外，要么依赖未证实的泄漏 / binder 暴露，要么已被现有规范化挡住，均不满足本轮开洞阈值。

| ID | 严重度 | 类别 | 标题 | 状态 |
| --- | --- | --- | --- | --- |
| — | — | — | 本轮无置信度 ≥0.8 的新可利用问题 | 无新登记 |

---

## 本轮复核结论

第十一轮落地后，下列入口仍对齐既有控制，本轮复测通过：

| 面 | 当前控制 | 对照 |
| --- | --- | --- |
| 链路分享非流式 body | 缓冲上限远低于流式上传；GET/HEAD/OPTIONS 禁非空 body；`readExactCapped` | FS-45 |
| 链路分享待审身份 | `deriveStableLinkShareClientId(IP, UA)` + 待审/授权配额 | FS-46 |
| 会话 cookie 字符集 | `isSafeLinkShareToken` 限 16–128 位 `[A-Za-z0-9_-]` | FS-46 / Cookie CRLF |
| HTML/SVG 预览 | CSP `sandbox; default-src 'none'` | 第十轮 XSS |
| 上传相对路径 | 分段拒绝 `..`；`isSharedPathSafe` | 路径穿越 |
| WebRTC 落盘 | `isAuthorizedWebRtcPeerToken` + `prepareStreamWrite`（敏感路径 + 写权限） | 设备 RPC |
| 账号设备证明 | 版本 / purpose / nonce / 时效 / 签名 / 重放缓存 | 本轮新扫 |
| WebRTC 信令入房 | Host 角色需本机 secret（恒定时间比较）；browser 不能抢 host、不能复用 peer id；poll/leave/send 绑 client token | 本轮新扫 |
| FileProvider | 无 `root-path`；`getUriForFile` 前 `SensitiveFileAccessPolicy`；`exported=false` | 第七 / 十轮 |

本轮额外核对、仍不单开 ID 的面见下一节。

---

## 已检查、不作为本轮新漏洞

| 主题 | 结论 |
| --- | --- |
| FS-01～FS-46 | 不重复开洞。第十轮 CSRF/XSS 等技能面、第十一轮内存暴增专项维持原结论。 |
| `prepareWriteBytes` Long 加法溢出 | `blockEnd = blockOffset + blockLength` 无溢出检查；乘法路径 `resolveBlockStartOffset` 有。若 `blockStartOffset` 接近 `Long.MAX_VALUE`，加法可绕过 `blockEnd > fileSize`。但调用方必须持有有效 token 且对该路径有写权限；`blockLength` 受 `maxBlockLength` 上限；溢出后的负偏移在 `FileChannel.write(position)` 会抛异常，正的超大偏移只会试图扩展**同一文件**（稀疏/磁盘配额失败），写不出另一个 inode。不够 0.8。 |
| JVM / Android `writeBytes` 无本地范围检查 | `FileUtils.writeBytes` / `localWriteBytes` 只 `truncate` 到声明 `fileSize` 再 `writeFully`。范围闸门在 `prepareWriteBytes`。同上，越界仍落在已授权文件内。hardening。 |
| `writeByteRanges` / MCP `writeRange` 的 `offset + size` | 同样可能 Long 溢出。MCP 路径还先 `requireOrdinaryPath` + `rejectUnsafeLocalParent`。影响限于已授权写文件。hardening。 |
| 拷贝控制缺 requestId 所有权 | `RemoteCopyControlRegistry` 是全局 `requestId → status`，`controlCopy` 只验 token 有效。官方客户端 id 为 `"device-copy:${task.key}"`，`task.key = nowMillis + Random.nextInt()`（约 31 位熵）。跨设备猜测不够 0.8。MCP `task.list` 会暴露 `task.key`，但是持有任务 scope 的产品能力。客户端可自报任意 requestId，碰撞窗口小。hardening，不单开。 |
| MCP `abortWrite` 不调用 `rejectUnsafeLocalEntry` | `delete` 有拒绝 symlink；`abortWrite` 只有 `requireOrdinaryPath`。JVM `File.delete()` 删的是链接本身、不跟随。最多是硬化缺口。 |
| Shizuku `onTransact` 无 `getCallingUid` | UserService 由本包 `Shizuku.bindUserService` 绑定，binder 通常只对绑定方可见；`RootFileService exported=false`。跨应用拿到 binder 的路径未证实。`TRANSACTION_DESTROY` 会 `Process.killProcess`，暴露面不够 0.8。 |
| 链路分享 header 会话跳过 clientId | `X-FolderSpan-Link-Session` 存在时 `clientId=null`，仍校验 IP+UA fingerprint。下载脚本走 header 是产品路径。需先泄漏 token 才构成会话盗用，不够单独开洞。 |
| 稳定 clientId 无 HMAC | `SHA-256(IP + "\n" + UA)` 无本机密钥。同 NAT / 同 UA 复用待审槽是 FS-46 有意设计（防库存污染）。cookie 仍可覆盖派生 id，但须过 `isSafeLinkShareToken`。不作为全新身份伪造洞重开。 |
| Cookie / Set-Cookie CRLF | `toHeaderValue()` 直接拼接，但 token 字符集已限。`contentDisposition` 把 `\r`/`\n` 换成 `_`，引号与反斜杠转义。 |
| FileProvider `external-path path="."` | 覆盖外部存储根，但 URI 由本应用对用户选中文件签发，签发前敏感路径拒绝 + 拒绝 symlink。不是任意邻机可读的导出组件。第七 / 十轮已覆盖。 |
| WebRTC 信令 | `HttpWebRtcHostAuth.verify` 恒定时间比较；外部 join 不能注册 host；browser 不能复用已有 peer id；有 browser 数量上限；send 限 browser→host。join 本身无应用层口令是产品「房间 URL 即入场」设计，后续数据通道仍走 peer token。 |
| 账号设备信任 | `verifyAccountDeviceProof` 绑 purpose、deviceKey、keyId、时效、nonce、签名与重放缓存。未见跳过签名的生产路径。 |
| SQL | SQLDelight 参数化，未见字符串拼接查询。 |
| 命令注入 | 无用户输入进入 `ProcessBuilder` / `Runtime.exec` 的生产路径。 |
| XXE | 无对网络或上传 XML 的 `DocumentBuilderFactory` 入口。GNOME `monitors.xml` 第九轮已排除。 |
| 反序列化 / JNI / WebView | 无 `ObjectInputStream`；未找到 C JNI 入口；无 `evaluateJavascript` / `addJavascriptInterface`。 |
| HTTP 走私 / keep-alive 残留 body | 链路分享 header 先读完再决定 body；chunked 上传路径拒绝；写响应前 `body.discard()`。并入 FS-45，不另开。 |
| 上传 Content-Range | `start !in 0L..endInclusive`、`endInclusive >= totalSize` 拒绝。下载 Range 同样 `start < 0 \|\| end >= fileSize \|\| start > end`。 |
| 插件 / 归档 zip-slip | 无独立「解压插件 zip 到任意路径」入口。归档规范化第八轮已排除；网盘目录下载 FS-43 已修。 |

<details>
<summary>残留硬化建议（不单开漏洞 ID）</summary>

这些项减小以后踩坑，但当前没有独立可利用路径，不分配 FS 编号。

1. **拷贝控制绑所有权**：`RemoteCopyControlRegistry.register` 同时记录发起方 token（或设备 id）；`controlCopy` 拒绝「有效但非所有者」的 Pause/Cancel。requestId 不要接受客户端任意字符串覆盖已有槽。
2. **写入范围用溢出安全比较**：`blockEnd` / `offset + size` 改成 `blockLength > fileSize - blockOffset`（先保证 `blockOffset <= fileSize`），并在 `writeBytes` 落地路径再拦一次。负偏移直接拒绝。
3. **MCP `abortWrite` 对齐 `delete`**：拿到 entry 后先 `rejectUnsafeLocalEntry`。
4. **Shizuku UserService**：`onTransact` 校验 `getCallingUid()` 为本应用 uid；`TRANSACTION_DESTROY` 不要无条件 `killProcess`。
5. **派生 clientId**：若以后要把 cookie 缺省身份当成抗伪造凭证，改为 HMAC（本机密钥）而不是裸 SHA-256。当前配额模型下不是洞。
6. 第十轮残留（`Service-Worker-Allowed`、CORS 允许头白名单、`POST /auth` 同源、SMB signing、StreamSaver `postMessage` origin）仍建议做，不阻塞。

</details>

<details>
<summary>防御性验证代码（确认本轮控制仍生效，不含攻击载荷）</summary>

下列断言只检查安全属性：块范围拒绝越界、拷贝控制对空白 id 无效、Content-Disposition 去掉换行、信令 host 角色不能由外部注册。不要构造跨设备碰撞、不要对局域网其它主机发请求、不要打印 token。

```kotlin
@Test
fun prepareWriteBytesRejectsRangePastFileSize() = runTest {
    val result = service.prepareWriteBytes(
        token,
        WriteBytesStreamRequest(
            fileSize = 1024L,
            blockIndex = 0L,
            blockLength = 16L,
            path = ordinaryWritableFile,
            blockStartOffset = 1020L,
        ),
    )
    assertTrue(result.isFailure)
}

@Test
fun prepareWriteBytesRejectsNegativeOffsetAfterOverflowAttempt() = runTest {
    val result = service.prepareWriteBytes(
        token,
        WriteBytesStreamRequest(
            fileSize = 1024L,
            blockIndex = 0L,
            blockLength = 8L,
            path = ordinaryWritableFile,
            blockStartOffset = Long.MAX_VALUE - 4L,
        ),
    )
    assertTrue(result.isFailure)
}

@Test
fun controlCopyBlankRequestIdIsRejected() = runTest {
    val result = copyService.controlCopy(
        token,
        CopyPathControlRequest(requestId = " ", action = CopyPathControlAction.Cancel),
    )
    assertTrue(result.isFailure)
}

@Test
fun contentDispositionStripsHeaderBreaks() {
    val header = contentDisposition(LinkShareFileDelivery.Attachment, "a\r\nb\"c")
    assertFalse(header.contains("\r"))
    assertFalse(header.contains("\n"))
}

@Test
fun externalJoinCannotRegisterHostRole() {
    val response = hub.join(
        role = HttpWebRtcSignalingRole.Host,
        device = SignalingDevice(id = "browser-1"),
        hostSecret = "not-the-server-secret",
    )
    assertNotEquals("OK", response.code)
}
```

测试只断言拒绝与头净化。不要打印 Access Key、口令、分享文件内容或 WebRTC host secret。

</details>

---

## 修复优先级建议

本轮无新登记漏洞，不新增必须立即落地的修复项。

若继续做纵深防御，优先顺序：

1. 拷贝控制把 requestId 绑到发起方 token，避免「任意有效设备」暂停他人复制。
2. 写入范围改溢出安全比较，并在 `writeBytes` 落地再拦一次。
3. MCP `abortWrite` 拒绝 symlink，与 `delete` 对齐。
4. 第十轮残留硬化（Service Worker scope、CORS 允许头、`POST /auth` 同源）。

后续审计不要重开 FS-01～FS-46。下一轮应把焦点放到本轮仍未覆盖的协议面（例如新的 MCP 工具、新的平台导出组件、新的分享传输），或回归验证第十一轮缓冲上限与待审配额是否仍生效。

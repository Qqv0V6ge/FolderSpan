# FolderSpan 安全审计报告（第十三轮）

- 审计日期：2026-09-02
- 审计范围：全仓（`app/`、`core/`、`proMain/`），非仅当前 PR diff
- 焦点：相对 FS-01～FS-46 **尚未单独覆盖的新协议面**（WebRTC Device Session 启动授权、设备分享路径隔离、FSAR2 落盘、分享复制审批、Device / Share → Network 流式复制）
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
  - 第十一轮见 [2026-08-24 全仓安全审计报告（第十一轮）](2026-08-24-security-audit-round-11.md)（FS-45 / FS-46 已修复）
  - 第十二轮见 [2026-08-24 全仓安全审计报告（第十二轮）](2026-08-24-security-audit-round-12.md)（整数溢出/拷贝 IDOR 等无新登记）
- 方法论：Claude Marketplaces 插件 `cybersecurity-skills@anthropic-cybersecurity-skills`
  覆盖技能：`testing-for-broken-access-control`、`performing-directory-traversal-testing`、`auditing-mcp-servers-for-tool-poisoning`、`testing-for-idor`、`testing-api-authentication`、`performing-crypto-audit`、`testing-android-intents-for-vulnerabilities`、`testing-for-xss-vulnerabilities`、`exploiting-insecure-deserialization`
  说明：本轮对齐全仓审计口径，不是只审当前 PR。新面集中在 8 月底之后落地的 Device Session over WebRTC、设备分享改复制、FSAR2 唯一归档格式，以及随后工作树新增的 Device / Share 目录流式上传到网盘（OpenSpec `stream-device-share-to-network`）。
- 约束：本报告不含攻击载荷或 exploit PoC。折叠区内的「验证代码」均为防御性回归测试（断言安全属性）。
- 威胁模型：局域网邻机、同机恶意进程/网页、不可信 Android Intent、SFTP 中间人、已授权设备上的 token 盗用、不可信分享主机。本机文件分享服务按产品设计会对局域网可达。网盘凭据属于用户，写入范围应限制在用户选定的目标目录内。
- 本轮只登记相对 FS-01～FS-46 **新类型**、置信度约 ≥0.8、有可利用路径的问题。已落地项、已排除项与产品明确文档化的行为不重复开洞。纯 DoS / 无界缓冲已由第十一轮覆盖，第十二轮残留硬化不重开。

## 审计发现总览

对照提交 `6f906875e6`（WebRTC 复用 Device Session）、`80db07e7fd`（设备分享改复制）、`a92bbffdd9` / `b73bddf816`（FSAR2 唯一格式）以及后续分享连接修复，扫了启动授权、分享路径隔离、归档落盘与复制审批。这些面凡能落到具体代码路径的线索，要么仍要额外用户批准或设备信任，要么已被白名单 / 虚拟路径 / 相对路径规范化挡住。

工作树新增的 Device / Share → Network 流式复制没有对等闸门，本轮登记 FS-47。

| ID | 严重度 | 类别 | 标题 | 状态 |
| --- | --- | --- | --- | --- |
| FS-47 | 高 | path_traversal / broken_access_control | Device / Share 目录流式复制到网盘时，`relativeRemotePath` 不折叠也不拒绝 `..`，文件上传走完整路径、不经 `joinPath` 单段检查，恶意 list 可把文件写到用户选定网盘目录之外 | 已修复 |

---

## FS-47：流式复制到网盘的相对路径穿越

* 严重度：高
* 类别：path_traversal / broken_access_control
* 修复状态：已修复
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/file/FileStateStreamCopySupport.kt`（`relativeRemotePath` 约 52–67 行；`joinRemotePath` 约 28–36 行；`streamCopyDirectoryToNetwork` 约 269–308 行）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/file/FileStateCopyCoordinator.kt`（`copyDeviceToNetwork` / `copyShareToNetwork` / `copySourceToNetwork`：目录遍历后把远端 path 交给 `relativeRemotePath`，再 `uploadFromSource`）
  - `core/src/commonMain/kotlin/com/folderspan/data/main/network/Network.kt`（`uploadFromSource` 约 1083–1098 行只查写权限；`createFolder`/`createFile` 走内部 `joinPath`，该 `joinPath` **不**调用 `isUnsafeNetworkPathSegment`）
  - `core/src/commonMain/kotlin/com/folderspan/data/main/share/Share.kt`（`getFileList` 约 87–121 行：会话 list 后只改 protocol，不过滤 `.` / `..`）
  - 对照已落地闸门：同文件 `Network.copyTo` 的 Network→Local `buildLocalPath` 使用 `FolderSpanArchiveCodec.normalizeRelativePath` + `PathUtils.isPathWithinRoot`；`containsUnsafeNetworkPathSegment` 仅用于 WebDAV/S3 **列出**远端条目，不用于上传目标
* 置信度：0.90

### 描述

Device / Share 目录同步到网盘时，不再整文件落到 `cache/sync-stage`，改为按 list 出的子项计算网盘目标路径，再 `uploadFromSource`。相对路径计算是：

```kotlin
internal fun relativeRemotePath(
    sourceRoot: String,
    sourcePath: String,
    destRoot: String,
    sourceSeparator: String,
    destSeparator: String,
): String {
    val srcSeparator = sourceSeparator.ifBlank { "/" }
    val remoteSeparator = destSeparator.ifBlank { "/" }
    val srcRoot = if (sourceRoot.endsWith(srcSeparator)) sourceRoot else sourceRoot + srcSeparator
    val relative = sourcePath.removePrefix(srcRoot).replace(srcSeparator, remoteSeparator)
    if (relative.isBlank() || relative == sourcePath) {
        return destRoot
    }
    return joinRemotePath(destRoot, relative, remoteSeparator)
}
```

`removePrefix` 只做字符串前缀切除，不规范化、不折叠 `..`、不调用已有的 `containsUnsafeNetworkPathSegment` / `FolderSpanArchiveCodec.normalizeRelativePath` / `PathUtils.isPathWithinRoot`。`joinRemotePath` 只拼接。

`streamCopyDirectoryToNetwork` 对每个非目录条目：

1. 用上面的函数得到 `remotePath`
2. 把完整路径交给 `copyFile` → `streamSingleFileToNetwork` → `networkAccess.uploadFromSource(remotePath, …)`

`Network.uploadFromSource` 只检查 `menuPermission.write`，然后把完整路径交给协议客户端。WebDAV `normalizeRemotePath` / SFTP `normalizePath` / SMB `normalizePath` / S3 `normalizeObjectKey` 大多只换分隔符或补前导 `/`，**不折叠 `..`**。WebDAV `encodePath` 按段 `encodeURLPathPart`，`.` / `..` 仍按原样进 URL。服务端（WebDAV / SFTP / SMB / FTP）会按协议解析父段，PUT/写文件落到用户选定目录之外。

目录创建走 `createRemoteFolder` → `splitRemoteParentAndName` → `Network.createFolder(parent, name)`。`Network.joinPath` **没有** `isUnsafeNetworkPathSegment`。客户端内部的 `joinPath(base, name)` 虽会拒绝单段 `..`，但流式上传与 `createFolder(完整路径)` 都不经过那条路径。名为 `..` 的目录因此也可能 MKCOL/mkdir 到父级；即便目录创建失败，带 `../` 的**文件**上传仍可成功。

分享端 list 不可信。`Share.getFileList` 对会话返回的 `path` / `name` 不做 `.` / `..` 过滤。设备 `paths.getList` 同样把远端条目原样用于相对路径。本机 FolderSpan 分享宿主会用 `DeviceSharePathScope.parseVirtualPath` 拒绝穿越，但**接收端复制到网盘时信任的是对端 list**，不是本机虚拟路径闸门。

对照：Network→Local 下载目录时，`buildLocalPath` 会 `normalizeRelativePath`（拒绝 `..`）再 `isPathWithinRoot`。反向的流式上传没有对等闸门。现有 `FileStateStreamCopySupportTest` 只覆盖泵送、失败删除、空目录进度，没有穿越用例。

单文件复制的目标是用户选定的 `destination.path`，不受本洞影响。本洞限于**目录** Device→Network / Share→Network（含系统分享目录走同一套 `relativeRemotePath`；本机 `listDirectory` 通常不含 `..` 子项，主要利用面仍是不可信 Device / Share）。

S3 object key 通常把 `folder/../x` 当字面键而不是父目录，穿越效果弱于 WebDAV / SFTP / SMB / FTP，但不改变其它协议上的可利用性。

### 影响

- 用户把不可信分享或设备上的文件夹同步到自己的 NAS / WebDAV / SFTP / SMB / FTP 时，对端可把内容写到用户选定文件夹的父级或同盘其它路径。
- 写入使用的是用户已保存的网盘凭据，属于授权范围内的越权写（broken access control），不是未授权连上网盘。
- 可覆盖目标根之外已有文件，造成数据破坏或把恶意文件放到用户以为未选中的目录。
- 不需要网盘口令、不需要再过一次本机敏感路径策略。需要用户主动发起「保存/复制该分享或设备目录到网盘」。

### 利用场景

用户连接到恶意分享或恶意设备，选中对方提供的文件夹，粘贴或保存到已登录网盘里的某个目录（例如 `/photos/inbox`）。对端 list 返回的子项 path 在源根前缀之后含父目录段。接收端把相对路径拼到 `/photos/inbox` 上，经 WebDAV PUT 或 SFTP/SMB 打开完整路径。协议服务端解析父段后，文件出现在 `/photos` 甚至更高层，而不是 `/photos/inbox`。本报告不提供 list 条目样例或请求字节。

<details>
<summary>漏洞相关代码（只读摘录，非攻击载荷）</summary>

相对路径拼接（无规范化）：

```kotlin
internal fun relativeRemotePath(
    sourceRoot: String,
    sourcePath: String,
    destRoot: String,
    sourceSeparator: String,
    destSeparator: String,
): String {
    val srcRoot = if (sourceRoot.endsWith(srcSeparator)) sourceRoot else sourceRoot + srcSeparator
    val relative = sourcePath.removePrefix(srcRoot).replace(srcSeparator, remoteSeparator)
    if (relative.isBlank() || relative == sourcePath) return destRoot
    return joinRemotePath(destRoot, relative, remoteSeparator)
}
```

目录复制把该路径直接交给文件上传：

```kotlin
val remotePath = relativeRemotePath(
    sourceRoot = source.path,
    sourcePath = entry.path,
    destRoot = destination.path,
    sourceSeparator = sourceSeparator,
    destSeparator = destSeparator,
)
val copied = copyFile(entry, remotePath)
```

网盘流式上传不检查路径段：

```kotlin
override suspend fun uploadFromSource(...): Result<Boolean> {
    if (menuPermission.write.not()) {
        return Result.failure(NetworkUnsupportedException(...))
    }
    return client.uploadFromSource(remotePath, size, onProgress, readChunk)
}
```

分享 list 不过滤父目录段：

```kotlin
val result = shareSession.list(path).map { entries ->
    entries.map { entry ->
        entry.withCopy(protocol = FileProtocol.Share, protocolId = id)
    }
}
```

对照 Network→Local 已有闸门（本洞路径没有等价物）：

```kotlin
val normalizedRelative = FolderSpanArchiveCodec.normalizeRelativePath(relative)
val localPath = FolderSpanArchiveCodec.buildTargetPath(rootPath = destRoot, ...)
if (!PathUtils.isPathWithinRoot(..., destRoot, localPath, allowNonExistentLeaf = true)) {
    throw AuthorityException(AppStrings.ui_download_path_exceeds_the_target_directory)
}
```

</details>

<details>
<summary>修复方案</summary>

1. **在拼出网盘目标后、任何 create/upload 之前拒绝不安全段**。对 `relative` 或最终 `remotePath` 调用已有 `containsUnsafeNetworkPathSegment`；命中则失败并中止该条目（建议中止整次目录任务，避免半写入）。不要只检查最后一段文件名。
2. **对齐 Network→Local**：相对路径先走 `FolderSpanArchiveCodec.normalizeRelativePath`（拒绝 `\`、空、绝对、`.` / `..`），再用 `isPathWithinRoot` 语义检查「拼出的远端路径仍落在 `destination.path` 之下」。网盘路径没有本机 canonical，可用规范化后的段比较：折叠后不得逃出 destRoot。
3. **`uploadFromSource` / `upload` / `createFolder(完整路径)` 增加同一闸门**，避免以后又有调用方只拼路径、不走 `relativeRemotePath`。`Network.joinPath(parent, name)` 应 `require(!isUnsafeNetworkPathSegment(name))`，与 SMB/SFTP 客户端内部 `joinPath` 一致。
4. **分享 / 设备 list 在进入遍历前过滤** `.` / `..` / 含分隔符的 name。这是纵深防御；主闸门仍应在写网盘前。
5. **补回归测试**（见下一折叠）。现有 `FileStateStreamCopySupportTest` 的目录用例全部是良性相对路径，挡不住本洞。
6. WebDAV `normalizeRemotePath`、SFTP/SMB `normalizePath`、S3 `normalizeObjectKey` 不要默默折叠 `..` 后继续写；应在客户端拒绝，以免各协议行为不一致（S3 字面键 vs WebDAV 解析）。

建议把检查收口到一处，例如：

```kotlin
internal fun resolveRemoteCopyPath(
    sourceRoot: String,
    sourcePath: String,
    destRoot: String,
    sourceSeparator: String,
    destSeparator: String,
): String {
    val joined = relativeRemotePath(sourceRoot, sourcePath, destRoot, sourceSeparator, destSeparator)
    if (containsUnsafeNetworkPathSegment(joined) || containsUnsafeNetworkPathSegment(destRoot)) {
        throw AuthorityException(AppStrings.ui_remote_path_invalid)
    }
    val relative = FolderSpanArchiveCodec.normalizeRelativePath(
        joined.removePrefix(destRoot.trimEnd('/')).trim('/').ifBlank {
            throw AuthorityException(AppStrings.ui_remote_path_invalid)
        },
    )
    val resolved = joinRemotePath(destRoot, relative, destSeparator)
    if (!isRemotePathWithinRoot(destRoot, resolved, destSeparator)) {
        throw AuthorityException(AppStrings.ui_download_path_exceeds_the_target_directory)
    }
    return resolved
}
```

上面伪代码仅说明控制意图：规范化 + within-root；落地时复用现有字符串工具，避免再复制一套分隔符逻辑。

</details>

<details>
<summary>防御性验证代码（确认修复生效，不含攻击载荷）</summary>

下列断言只检查：相对路径含父段时不得产生 destRoot 之外的目标；`uploadFromSource` / `createFolder` 拒绝不安全段。不要对真实 NAS 发 PUT，不要构造跨主机请求，不要打印网盘口令。

```kotlin
@Test
fun relativeRemotePathRejectsParentSegments() {
    assertTrue(
        containsUnsafeNetworkPathSegment(
            relativeRemotePath(
                sourceRoot = "/src/folder",
                sourcePath = "/src/folder/../outside.txt",
                destRoot = "/dst/folder",
                sourceSeparator = "/",
                destSeparator = "/",
            ),
        ),
    )
    assertTrue(
        containsUnsafeNetworkPathSegment(
            relativeRemotePath(
                sourceRoot = "/src/folder",
                sourcePath = "/src/folder/sub/../../outside.txt",
                destRoot = "/dst/folder",
                sourceSeparator = "/",
                destSeparator = "/",
            ),
        ),
    )
}

@Test
fun streamDirectoryCopyDoesNotUploadOutsideDestination() = runSuspendTest {
    val network = RecordingNetworkAccess()
    val result = streamCopyDirectoryToNetwork(
        source = directory("/src/folder"),
        destination = directory("/dst/folder"),
        sourceSeparator = "/",
        destSeparator = "/",
        networkAccess = network,
        ensureRunning = {},
        traversalKind = TraversalEndpointKind.Share,
        listChildren = { directory ->
            Result.success(
                if (directory.path == "/src/folder") {
                    listOf(file("/src/folder/ok.txt"), file("/src/folder/../outside.txt"))
                } else {
                    emptyList()
                },
            )
        },
        copyFile = { _, destPath ->
            error("must not copy unsafe dest: $destPath")
        },
    )
    assertTrue(result.isFailure)
    assertTrue(network.uploaded.none { item -> item.first.contains("outside") })
}

@Test
fun uploadFromSourceRejectsUnsafeRemotePath() = runSuspendTest {
    val network = /* 真实 Network 包装或带闸门的假客户端 */
    val result = network.uploadFromSource(
        remotePath = "/dst/folder/../outside.bin",
        size = 1L,
        onProgress = { _, _ -> },
        readChunk = { byteArrayOf(1) },
    )
    assertTrue(result.isFailure)
}

@Test
fun createFolderRejectsParentSegmentName() = runSuspendTest {
    val result = createRemoteFolder(
        networkAccess = RecordingNetworkAccess(),
        remotePath = "/dst/folder/..",
        destSeparator = "/",
    )
    assertTrue(result.isFailure)
}
```

测试只断言拒绝与「未写出 destRoot 外路径」。不要打印 Access Key、口令或分享文件内容。

</details>

---

## 本轮复核结论

第十二轮之后新落地的协议面，除 FS-47 外仍对齐既有控制：

| 面 | 当前控制 | 对照 |
| --- | --- | --- |
| WebRTC 启动授权 | 内存一次性 opaque（48 字符、60s TTL）；消费绑 initiator / target / attempt / role；失败不消费；并发单赢家 | 本轮新扫 |
| WebRTC CONNECT | `authorizeWebRtcPreapprovedSession` 拒绝非 STANDARD、带账号证明、device.id 不匹配；成功后才签发 Session token；`sharePathScope` 写入 token | 本轮新扫 |
| 信令 `connect-approved` | 仅 Host 可发；send/poll/leave 绑 clientToken；browser 不能复用 peer id、不能注册 Host | 第十二轮信令 + 本轮复核 |
| 分享 Session RPC | 白名单仅 `RootPaths` / `ListPath`；写 / 删 / 复制 / 书签 / 消息一律 `FORBIDDEN` | 本轮新扫 |
| 分享 Session 流 | 白名单仅 `Read` / `ArchiveRead`；`resolveDeviceShareSessionStreamOpen` 把虚拟路径映射到授权物理路径，失败 RST | 本轮新扫 |
| 分享路径 | `DeviceSharePathScope` 虚拟根；`parseVirtualPath` 拒绝 `..` / `.` / 空白 / Windows `\`；`checkPermission` 分享 token 非 read 直接拒绝；read 再走 symlink + canonical + `isPathWithinRoot` | 本轮新扫 |
| 分享 grant | 一次性消费；deviceId + TLS 指纹 + 恒定时间 nonce + 过期 | 本轮新扫 |
| HTTP `/api/devices/connect` | `allowShareSessionAuthorization = false`，分享 nonce 不能走设备 HTTP 入口 | 本轮新扫 |
| FSAR2 相对路径 | `normalizeRelativePath` 拒绝 `\`、空、绝对路径、`.` / `..`；解码器 MAGIC / header 长度 / 去重 / 阈值后再落盘 | 第八轮归档 + 本轮复核 |
| 服务端归档写 | `prepareDirectory` / `prepareStreamWrite` 走 bound token；分享 token 无写权限 | 本轮新扫 |
| Device/Share→Network 流式上传 | 按块 `readRange` + `uploadFromSource`，不经本地完整落地；失败 best-effort 删部分远端文件。目录相对路径缺少 within-root，见 FS-47 | 本轮新扫 |
| 目录遍历 symlink | `collectDirectoryEntriesAdaptive(..., rejectSymbolicLinkEntries = true)` | 本轮新扫 |
| 已知 size | `requireKnownUploadSize` 负 size 失败 | 本轮新扫 |
| Local→Network | 仍走 `upload(localPath)` + `rejectUnsafeLocalUploadSource`（敏感路径 + symlink） | 既有测试 `localToNetworkCopyRejectsProtectedApplicationData` |
| Network→Local | `normalizeRelativePath` + `isPathWithinRoot` + 敏感路径 | 对照 FS-47 缺失的反向闸门 |
| MCP 工具 | 名称必须 `folderspan_` 前缀；call 校验 scopes；`folderspan_files_share_link` + allowUpload 需 FilesWrite | 第十轮 MCP |
| 上传页 XSS | `innerHTML` 拼接走 `escapeHtml`；snackbar `textContent` | 第十轮 XSS |
| Android 导出组件 | `MainActivity` 导出是 SEND / VIEW；`FileProvider` / `BackgroundService` / `RootFileService` / `BootCompletedReceiver` 均 `exported=false` | 第七 / 十轮 |

本轮额外核对、仍不单开 ID 的面见下一节。

---

## 已检查、不作为本轮新漏洞

| 主题 | 结论 |
| --- | --- |
| FS-01～FS-46 | 不重复开洞。第十轮 CSRF/XSS、第十一轮内存暴增、第十二轮溢出/拷贝 IDOR 维持原结论。 |
| 二次 CONNECT 提权 | `handleConnect` 不拒绝已绑定会话再 CONNECT。分享会话第二次若仍带有效 `shareNonce`，nonce 已一次性消费，会 REJECTED。空 nonce 走完整 `evaluateDeviceConnect`：未知设备弹审批，已是 `AUTO_CONNECT` / 账号信任才自动签发**完整设备 token**。提权仍需用户批准或设备已信任，不够「仅凭已批准的只读分享会话即可写」的 0.8。hardening：已绑定后应拒绝再 CONNECT。 |
| 分享 nonce 无 scope | 签发后若 `getDeviceSharePathScope(token) == null`，立即 `removeToken` 并 REJECTED。HTTP 设备入口不允许分享授权。 |
| IDENTIFY 未授权 | 未绑定 token 也可 `Identify`，返回本机 `SocketDevice`。局域网发现 / 信令已有同类信息，不够单独开洞。 |
| 分享 list 子项 `/${entry.name}` | `buildProtocolPathEntries` 用 `withCopy(path = relativePath(...))` 覆盖物理路径，客户端拿不到本机绝对路径。深层列表把子项挂到虚拟根而非正确父路径是功能/混淆问题；读仍要 `resolveVirtualContentPath` + `isSafeDeviceShareContentPath`，不能越权读授权树外文件。 |
| 归档父目录 `FileAccessPermission.Allowed` | `ArchiveEntryExtractor` 写盘用 Allowed，但服务端先 `createFolders` / `prepareStreamWrite` 走 token。本地提取是本机受信路径。hardening，不单开。 |
| WebRTC `remoteHost = null` | 指纹 `clientIp` 为空。token 仍绑 deviceId + UA ALPN；启动授权已绑 attempt。空 IP 不构成跨会话盗用。 |
| 启动授权失败不消费 | 类型 / 过期 / initiator / target / attempt / role 任一失败不 `remove`；过期才删除。mutex 保证并发单赢家。 |
| 分享心跳 Host | `resolveShareCallbackHost` 用 `remoteHost` 覆盖广告 Host，防回调到攻击者指定地址。心跳本身不签发文件 token。 |
| 系统分享敏感源 | `copyLocalOrContentSourceToNetwork` 用 `FileAccessPermission.Allowed` 读，流式 `uploadFromSource` 不走 `rejectUnsafeLocalUploadSource`。入口 `ShareHandler.isClipboardSourceAllowed` 对 `file://` 已拒敏感路径与 symlink；未知 size 且本地路径可用时 fallback `uploadFileFromLocal` 仍会拒绝。未证明可从系统分享桌面稳定读到策略拒绝的路径，标 hardening，不够 0.8。 |
| 单文件 Device/Share→Network | 目标是用户选定的 `destination.path`，相对路径不参与。 |
| 失败删部分远端 | 设计如此（best-effort），不是越权。 |
| 已知 size / 0 字节走 `createFile` | 空文件仍经 `splitRemoteParentAndName`；若相对路径含 `..`，与 FS-47 同一根因，不另开 ID。 |
| S3 字面 object key | `folder/../x` 多半不会解析到父前缀，穿越弱。不单独开洞；修 FS-47 时应一并拒绝，避免协议分叉。 |
| `Network.joinPath` 无单段检查 | 流式目录创建会绕过客户端内部 `joinPath`。作为 FS-47 修复的一部分，不另开 ID。 |
| MCP 工具投毒 | 工具名强制 `folderspan_`；描述/schema 由本应用注册，不是模型或远端注入。call 再验 scope。第十轮已覆盖。 |
| XSS / innerHTML | 上传队列相对路径 `escapeHtml`；snackbar `textContent`。分享列表 href 百分号编码，第十轮已排除。 |
| 反序列化 | 无 `ObjectInputStream` / Java 原生反序列化。Session / 归档走 protobuf + 显式校验。 |
| 命令注入 | 无用户输入进入 `ProcessBuilder` / `Runtime.exec` 的生产路径。 |
| Android Intent | 导出组件与第七 / 十轮一致；本轮未见新导出入口。 |
| `app/desktopApp/share-history/` | 本地运行数据，不是源码审计对象。 |
| 第十二轮残留硬化 | 拷贝控制所有权、写入范围溢出安全比较、MCP `abortWrite` symlink、Shizuku `getCallingUid` 仍建议做，不另开 ID。 |
| 纯 DoS、限流、过时依赖、磁盘密钥 | 按口径排除。 |

<details>
<summary>残留硬化建议（不单开漏洞 ID）</summary>

这些项减小以后踩坑，但当前没有独立于 FS-47 的可利用路径，不另分配编号。

1. **已绑定 Session 拒绝再 CONNECT**：`handleConnect` 在 `boundToken != null` 时直接 `CONFLICT` / `FORBIDDEN`，避免分享会话在同一条通道上改走完整设备审批。二次 CONNECT 今天仍要用户批准或 AUTO_CONNECT，不是洞。
2. **分享 list 保留虚拟父路径**：子项 relativePath 用 `"$virtualPath/${entry.name}"` 而不是 `"/${entry.name}"`，避免客户端把深层条目当成虚拟根直接子项。读闸门不依赖这个字符串。
3. **归档提取父目录走 token**：`createArchiveParentDirectoryIfNeeded` 不要单独 `FileAccessPermission.Allowed`；目录创建已由 `prepareDirectory` 覆盖，可删冗余 Allowed 路径以免以后有人绕过 prepare。
4. **IDENTIFY 放到授权之后**：未绑定只返回最小字段（例如协议版本），完整 `SocketDevice` 等 CONNECT 成功。当前泄漏面与发现协议重叠。
5. **系统分享流式读补二次敏感路径检查**：`copyLocalOrContentSourceToNetwork` 的 `readRange` / 遍历在 `Allowed` 之前对每个 path 调 `SensitiveFileAccessPolicy.deniedException` 与 symlink 拒绝，与 `rejectUnsafeLocalUploadSource` 对齐。入口已滤，不是洞。
6. **分享 / 设备 list 过滤 `.` / `..`**：接收端不要把对端返回的父目录段送进任何复制管线（含以后的归档、重命名展示）。主闸门仍是 FS-47 的写前检查。
7. **WebDAV/SFTP/SMB `normalize*` 拒绝不安全段**：列出时已有部分过滤；写入路径应同样拒绝，作为 FS-47 的协议层兜底。
8. 第十二轮残留（拷贝控制绑 token、写入范围溢出安全比较、MCP `abortWrite` 拒绝 symlink、Shizuku uid 校验）以及第十轮残留（`Service-Worker-Allowed`、CORS 允许头白名单、`POST /auth` 同源）仍建议做，不阻塞 FS-47。

</details>

<details>
<summary>防御性验证代码（确认本轮既有控制仍生效，不含攻击载荷）</summary>

下列断言只检查安全属性：启动授权绑定失败不消费、分享虚拟路径拒绝穿越、归档相对路径拒绝 `..`、分享会话写流被拒、非 Host 不能发 `connect-approved`。不要构造跨设备碰撞、不要对局域网其它主机发请求、不要打印 token / opaque 授权 / 分享 nonce。FS-47 的穿越拒绝测试见该洞折叠区。

```kotlin
@Test
fun bootstrapConsumeMismatchDoesNotConsume() = runTest {
    val registry = DeviceSessionBootstrapAuthorizationRegistry()
    val issued = registry.issue(
        initiatorDeviceId = "peer-a",
        targetDeviceId = "host-b",
        connectionAttemptId = "attempt-1",
        roleId = 2L,
    )
    val denied = registry.consume(
        authorization = issued,
        initiatorDeviceId = "peer-other",
        targetDeviceId = "host-b",
        connectionAttemptId = "attempt-1",
        currentRoleId = 2L,
    )
    assertTrue(denied is DeviceSessionBootstrapAuthorizationConsumeResult.Denied)
    val granted = registry.consume(
        authorization = issued,
        initiatorDeviceId = "peer-a",
        targetDeviceId = "host-b",
        connectionAttemptId = "attempt-1",
        currentRoleId = 2L,
    )
    assertTrue(granted is DeviceSessionBootstrapAuthorizationConsumeResult.Granted)
}

@Test
fun shareVirtualPathRejectsTraversal() {
    val scope = DeviceSharePathScope(
        listOf(DeviceSharePathGrant(path = "/home/user/shared", isDirectory = true)),
        pathSeparator = "/",
    )
    assertNull(scope.resolveVirtualContentPath("/shared/../secret"))
    assertNull(scope.resolveVirtualContentPath("/shared/foo/../../etc/passwd"))
    assertNull(scope.resolveVirtualContentPath("shared/file"))
    assertEquals(
        "/home/user/shared/photo.jpg",
        scope.resolveVirtualContentPath("/shared/photo.jpg"),
    )
}

@Test
fun archiveRelativePathRejectsParentSegments() {
    assertFails { FolderSpanArchiveCodec.normalizeRelativePath("../secret") }
    assertFails { FolderSpanArchiveCodec.normalizeRelativePath("/abs") }
    assertFails { FolderSpanArchiveCodec.normalizeRelativePath("a\\b") }
    assertEquals("dir/file.txt", FolderSpanArchiveCodec.normalizeRelativePath("dir/file.txt"))
}

@Test
fun shareSessionRpcWhitelistRejectsWrites() {
    assertTrue(isDeviceShareSessionRpcAllowed(DEVICE_SESSION_RPC_ROOT_PATHS))
    assertTrue(isDeviceShareSessionRpcAllowed(DEVICE_SESSION_RPC_LIST_PATH))
    assertFalse(isDeviceShareSessionRpcAllowed(DEVICE_SESSION_RPC_CREATE_FILES))
    assertFalse(isDeviceShareSessionRpcAllowed(DEVICE_SESSION_RPC_COPY_PATH))
    assertFalse(isDeviceShareSessionStreamAllowed(DEVICE_SESSION_STREAM_WRITE))
    assertFalse(isDeviceShareSessionStreamAllowed(DEVICE_SESSION_STREAM_ARCHIVE_WRITE))
}

@Test
fun httpDeviceConnectRejectsShareNonce() = runTest {
    val response = dispatcher.handleDeviceConnect(shareNonceConnectRequest())
    assertEquals(DeviceConnectType.REJECTED, response.connectType)
    assertTrue(response.token.isBlank())
}

@Test
fun externalPeerCannotSendConnectApproved() {
    val response = hub.send(
        clientId = "browser-1",
        clientToken = browserToken,
        message = SignalingMessage(type = "connect-approved", from = browserPeer, to = hostPeer),
    )
    assertEquals("PEER_NOT_ALLOWED", response.code)
}
```

测试只断言拒绝与路径规范化。不要打印 Access Key、口令、分享 nonce、bootstrap opaque 或分享文件内容。

</details>

---

## 修复优先级建议

1. **立即**：落地 FS-47。目录流式复制到网盘前规范化相对路径并 within-root；`uploadFromSource` 拒绝不安全段。这是本轮唯一必须修的项。
2. 系统分享流式路径补敏感文件二次检查（hardening）。
3. 已绑定 Session 拒绝再 CONNECT，切断「分享会话改走完整设备审批」的通道（仍要用户批准才真正提权）。
4. 分享 list 子项保留虚拟父路径，避免客户端路径混淆。
5. 第十二轮残留：拷贝控制绑发起方 token；写入范围溢出安全比较。
6. 第十轮残留：`Service-Worker-Allowed`、CORS 允许头白名单、`POST /auth` 同源。

后续审计不要重开 FS-01～FS-47。下一轮应回归验证 `relativeRemotePath` / `uploadFromSource` 是否仍拒绝父段，或把焦点放到尚未覆盖的协议面（新 MCP 工具、新平台导出组件、账号设备证明变更）。

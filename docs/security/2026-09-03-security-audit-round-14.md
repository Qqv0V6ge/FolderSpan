# FolderSpan 安全审计报告（第十四轮）

- 审计日期：2026-09-03
- 审计范围：全仓（`app/`、`core/`、`proMain/`），非仅当前 PR diff
- 焦点：相对 FS-01～FS-47 **尚未单独覆盖的接收端写路径**（Device / Share 目录复制到本机、HTTP 复制后备、归档批次 fallback、二次 CONNECT / TLS 钉扎、未挂载 HTTP 路由、CLI 托管 WebUI 提案）
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
  - 第十三轮见 [2026-09-02 全仓安全审计报告（第十三轮）](2026-09-02-security-audit-round-13.md)（FS-47 已修复于 `2b3d6874ab`）
- 方法论：Claude Marketplaces 插件 `cybersecurity-skills@anthropic-cybersecurity-skills`
  覆盖技能：`testing-for-broken-access-control`、`performing-directory-traversal-testing`、`testing-cors-misconfiguration`、`performing-ssrf-vulnerability-exploitation`、`testing-for-open-redirect-vulnerabilities`、`testing-for-host-header-injection`、`exploiting-race-condition-vulnerabilities`、`testing-for-sensitive-data-exposure`、`testing-websocket-api-security`、`testing-for-business-logic-vulnerabilities`、`performing-csrf-attack-simulation`、`exploiting-broken-function-level-authorization`
  说明：本轮对齐全仓审计口径，不是只审当前 PR。代码探索优先用 codebase-memory-mcp（`search_graph` / `search_code` / `get_code_snippet`），关键路径再 Read 当前源文件核对。图索引行号可能落后于 HEAD，以源文件为准。
- 约束：本报告不含攻击载荷或 exploit PoC。折叠区内的「验证代码」均为防御性回归测试（断言安全属性）。
- 威胁模型：局域网邻机、同机恶意进程/网页、不可信 Android Intent、SFTP 中间人、已授权设备上的 token 盗用、不可信分享主机。本机文件分享服务按产品设计会对局域网可达。网盘凭据属于用户，写入范围应限制在用户选定的目标目录内。本机复制目标同样应限制在用户选定的目录内。
- 本轮只登记相对 FS-01～FS-47 **新类型**、置信度约 ≥0.8、有可利用路径的问题。已落地项、已排除项与产品明确文档化的行为不重复开洞。纯 DoS / 无界缓冲已由第十一轮覆盖，第十二轮残留硬化不重开。FS-47 已修，不重开。

## 审计发现总览

对照提交 `2b3d6874ab`（FS-47：流式复制到网盘的相对路径穿越已修）以及当前 `develop` HEAD，复核了分享只读会话、FSAR2 落盘、HTTP dispatch 收窄、分享 nonce 一次性绑定。这些面凡能落到具体代码路径的线索，要么仍要额外用户批准或设备信任，要么已被白名单 / 虚拟路径 / 相对路径规范化挡住。

第十三轮修的是 Device / Share → **Network**。对偶的 Device / Share → **Local** 目录复制原先走 `copyViaTransportClients`：扫描绕过 `isUnsafeRemoteListEntry`，目标路径字符串拼接后交给 `FileUtils`，没有 `normalizeRelativePath` / `isPathWithinRoot`。本轮登记 FS-48，随后已落地修复。

| ID | 严重度 | 类别 | 标题 | 状态 |
| --- | --- | --- | --- | --- |
| FS-48 | 高 | path_traversal / broken_access_control | Device / Share 目录复制到本机时，`copyViaTransportClients` 扫描不经过 list 过滤，相对路径 `replaceFirst` 后与 dest 直接拼接，`FileUtils.createFolder` / `writeBytes` 无 jail；恶意 list 可把文件写到用户选定本机目录之外 | 已修复 |

---

## FS-48：目录复制到本机的相对路径穿越

* 严重度：高
* 类别：path_traversal / broken_access_control
* 修复状态：已修复（`resolveLocalCopyPath` / `resolveDirectoryCopyTargetPath`；Device `copyViaTransportClients` 与 HTTP 后备 `PathRouteTransferClient.copyTo` 写前 jail；扫描 `isUnsafeRemoteListEntry`；归档 `remainingItems` 先 jail 再 fallback）
* 位置：
  - `core/src/commonMain/kotlin/com/folderspan/data/main/device/Device.kt`（`FileOperations.copyViaTransportClients`：扫描约 734–808 行走裸 `listPath`；`enqueueTraversalBatch` 约 899–901 行 `replaceFirst`；`processDirectoryEntry` 约 821 行、`copyFileEntry` 约 846 行 `destFileSimpleInfo.path + entry.path`；归档进度约 934 / 950 行同样拼接）
  - `core/src/commonMain/kotlin/com/folderspan/data/main/share/Share.kt`（`copyTo` 约 160–191 行：构造临时 `Device` 后调用 `files.copyTo`，必走上一路径）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/main/DeviceState.kt`（`resolveConnectedDevice` 约 1349–1354 行：`preferredDevice.id == deviceId` 时直接返回临时 Device）
  - `core/src/commonMain/kotlin/com/folderspan/ui/state/file/FileStateCopyCoordinator.kt`（`CopyRoute.ShareToLocal` 约 134–145 行 → `share.copyTo`；`CopyRoute.DeviceRoute` → `copyDeviceRouteWithUnsupportedFallback` → `Device.files.copyTo`）
  - `core/src/commonMain/kotlin/com/folderspan/service/http/client/PathRouteTransferClient.kt`（HTTP 后备 `copyTo` 约 530 / 586 行同类拼接；主路径 Share/Device 有 client 时不走这里，修 FS-48 时应一并收口）
  - `core/src/jvmMain/kotlin/com/folderspan/utils/FileUtils.jvm.kt`（`createFolder` 约 137–149 行 `File.mkdir()`，只拒叶子 symlink；`writeBytes` 约 316–344 行 `NoFollowFileChannels.openReadWrite`，NOFOLLOW 只作用于叶子）
  - `core/src/jvmMain/kotlin/com/folderspan/utils/NoFollowFileChannels.kt`（`open` 不规范化、不检查是否仍在用户选定根下）
  - 对照已落地闸门：Network→Local `Network.copyTo` 的 `buildLocalPath` 使用 `FolderSpanArchiveCodec.normalizeRelativePath` + `buildTargetPath` + `PathUtils.isPathWithinRoot` + `SensitiveFileAccessPolicy`；Device/Share→Network 的 `resolveRemoteCopyPath`（FS-47）使用 `containsUnsafeNetworkPathSegment` + `normalizeRelativePath` + `isRemotePathWithinRoot`。本洞路径没有等价物。
* 置信度：0.90

### 描述

用户把不可信分享或设备上的**目录**复制到本机选定文件夹时，接收端不信任对端文件系统，只信任对端 `listPath` 返回的 `name` / `path`。UI 列表已经过滤：

- `Share.getFileList` / `getRootList`：`filterNot { isUnsafeRemoteListEntry(entry.name, entry.path) }`
- `Device.paths.getList`：同样过滤

复制扫描**不走**这两处。`copyViaTransportClients` 在 `sourceDevice != null` 时：

```kotlin
sourcePathClient.listPath(
    request = ListRequest(directory.path),
    batchId = "device-copy-scan:${task.key}",
).map { entries ->
    entries.map { entry ->
        entry.withCopy(
            protocol = FileProtocol.Device,
            protocolId = srcFileSimpleInfo.protocolId,
        )
    }
}
```

条目原样进入遍历。相对路径计算是字符串前缀切除，不是规范化：

```kotlin
val normalizedEntries = entries.map { item ->
    item.withCopy(path = item.path.replaceFirst(srcFileSimpleInfo.path, ""))
}
```

目录与非归档文件随后：

```kotlin
val targetPath = destFileSimpleInfo.path + entry.path
FileUtils.createFolder(FileAccessPermission.Allowed, targetPath)
// copyFileEntry → transferSingleFileViaClients → FileUtils.writeBytes(..., path = targetPath, ...)
```

`File.mkdir()` / `NoFollowFileChannels.openReadWrite(..., CREATE)` 把路径交给操作系统解析。父段 `..` 会落到用户选定 dest 之外。NOFOLLOW 只阻止叶子是 symlink，不阻止路径中的 `..`，也不调用 `PathUtils.isPathWithinRoot`。

`Share.copyTo` 把源改成 `FileProtocol.Device`，构造带 `pathClient` / `fileClient` 的临时 Device。`resolveTransferDevice` 经 `preferredDevice` 命中该临时对象，因此分享复制**一定**进入 `copyViaTransportClients`，不会掉到 HTTP 后备。

小文件归档批次**不是**本洞的主利用面：`selectSmallFileArchiveBatches` 会对 `relativePath` 调用 `normalizeRelativePath`，含 `..` 的条目进入 `remainingItems`，再走上面的拼接 fallback。`ArchiveEntryExtractor` 本身用 `normalizeRelativePath` + `buildTargetPath`，FSAR2 落盘闸门仍成立。目录创建从不进归档，始终拼接。

单文件复制的目标是用户选定的 `destFileSimpleInfo.path`，相对路径不参与，不受本洞影响。本洞限于**目录** Device→Local / Share→Local（含 Device→Device 目录复制时，目标设备上同样拼接后 `createFolders` / `writeBytes`）。

第十三轮写「`Share.getFileList` 不过滤 `.` / `..`」已过时：当前源码已过滤。那只保护 UI 列出，不能当作复制路径的闸门。

### 影响

- 用户把不可信分享或设备上的文件夹保存到本机某个目录时，对端可把内容写到该目录的父级或同盘其它路径。
- 写入使用的是本机 `FileAccessPermission.Allowed`（用户已授权的本地文件访问），属于授权范围内的越权写，不是未授权打开本机磁盘。
- 可覆盖 dest 根之外已有文件，造成数据破坏或把恶意文件放到用户以为未选中的目录。
- 不需要再过一次本机敏感路径策略（拼接路径不调用 `SensitiveFileAccessPolicy`）。需要用户主动发起「保存/复制该分享或设备目录到本机」。
- Device→Device 目录复制时，对端 list 同样可把文件写到用户在目标设备上选定目录之外（目标设备 token 对该路径父级有写权限时）。

### 利用场景

用户连接到恶意分享或恶意设备，选中对方提供的文件夹，粘贴到本机某个目录。对端 list 返回的子项 path 在源根前缀之后含父目录段。接收端把相对路径拼到本机 dest 上，经 `mkdir` / `writeBytes` 打开完整路径。操作系统解析父段后，文件出现在 dest 的父级或更高层。本报告不提供 list 条目样例或请求字节。

<details>
<summary>漏洞相关代码（只读摘录，非攻击载荷）</summary>

复制扫描绕过 list 过滤：

```kotlin
sourcePathClient
    .listPath(
        request = ListRequest(directory.path),
        batchId = "device-copy-scan:${task.key}",
    )
    .map { entries ->
        entries.map { entry ->
            entry.withCopy(
                protocol = FileProtocol.Device,
                protocolId = srcFileSimpleInfo.protocolId,
            )
        }
    }
```

相对路径只做前缀切除：

```kotlin
val normalizedEntries = entries.map { item ->
    item.withCopy(path = item.path.replaceFirst(srcFileSimpleInfo.path, ""))
}
```

目录与文件落盘无 jail：

```kotlin
val targetPath = destFileSimpleInfo.path + entry.path
FileUtils.createFolder(FileAccessPermission.Allowed, targetPath)
// copyFileEntry:
transferSingleFileViaClients(..., targetPath = destFileSimpleInfo.path + entry.path, ...)
```

分享复制必走该引擎：

```kotlin
Device(
    id = id, name = name, pathSeparator = pathSeparator, ...
    pathClient = copySource.devicePathClient,
    fileClient = copySource.deviceFileClient,
).files.copyTo(
    task = task,
    srcFileSimpleInfo = srcFileSimpleInfo.withCopy(protocol = FileProtocol.Device, protocolId = id),
    destFileSimpleInfo = destFileSimpleInfo,
)
```

JVM 落盘不检查 within-root：

```kotlin
actual fun createFolder(...): Result<Boolean> {
    val file = File(path)
    if (file.isSymbolicLinkNoFollow()) return Result.failure(...)
    if (file.exists()) return Result.success(true)
    return Result.success(file.mkdir())
}
```

对照 Network→Local 已有闸门（本洞路径没有等价物）：

```kotlin
val normalizedRelative = FolderSpanArchiveCodec.normalizeRelativePath(relative)
val localPath = FolderSpanArchiveCodec.buildTargetPath(rootPath = destRoot, ...)
if (!PathUtils.isPathWithinRoot(..., destRoot, localPath, allowNonExistentLeaf = true)) {
    throw AuthorityException(AppStrings.ui_download_path_exceeds_the_target_directory)
}
SensitiveFileAccessPolicy.deniedException(localPath)?.let { error -> throw error }
```

</details>

<details>
<summary>修复方案</summary>

1. **在拼出本机（或目标设备）路径后、任何 mkdir / write 之前拒绝不安全段**。对相对路径调用已有 `containsUnsafeNetworkPathSegment` / `FolderSpanArchiveCodec.normalizeRelativePath`；命中则失败并中止该条目（建议中止整次目录任务，避免半写入）。不要只检查最后一段文件名。
2. **对齐 Network→Local `buildLocalPath` 与 FS-47 `resolveRemoteCopyPath`**：相对路径先规范化（拒绝 `\`、空、绝对、`.` / `..`），本机再用 `PathUtils.isPathWithinRoot(..., allowNonExistentLeaf = true)` + `SensitiveFileAccessPolicy`；目标为另一台设备时用 `isRemotePathWithinRoot`。
3. **扫描与 UI 共用同一过滤**：`copyViaTransportClients` / `PathRouteTransferClient.copyTo` 的 `listPath` 结果先 `filterNot { isUnsafeRemoteListEntry }`。这是纵深防御；主闸门仍应在写盘前。不要把 `getFileList` 已过滤当成复制已安全。
4. **归档 fallback 不得比归档本身更松**：`selectSmallFileArchiveBatches` 因 `normalizeRelativePath` 失败而进入 `remainingItems` 的条目，必须拒绝，而不是改走拼接 `copyFileEntry`。
5. **`FileUtils.createFolder` / `writeBytes` 不作为唯一闸门**。底层工具面向已授权路径，jail 应在复制协调层。可选 hardening：本地写增加 within-root 参数，避免以后又有调用方只拼接。
6. **补回归测试**（见下一折叠）。现有设备/分享复制测试没有「list 含父段不得写出 dest 外」的断言。

建议把检查收口到一处，例如复用 FS-47 的意图（规范化 + within-root），本机版：

```kotlin
internal fun resolveLocalCopyPath(
    sourceRoot: String,
    sourcePath: String,
    destRoot: String,
    sourceSeparator: String,
): String {
    val srcSeparator = sourceSeparator.ifBlank { PathUtils.getPathSeparator() }
    val localSeparator = PathUtils.getPathSeparator()
    val srcRoot = if (sourceRoot.endsWith(srcSeparator)) sourceRoot else sourceRoot + srcSeparator
    val relativeRaw = sourcePath.removePrefix(srcRoot).replace(srcSeparator, "/")
    if (relativeRaw.isBlank() || relativeRaw == sourcePath) {
        throw AuthorityException(AppStrings.ui_remote_path_invalid)
    }
    if (containsUnsafeNetworkPathSegment(relativeRaw)) {
        throw AuthorityException(AppStrings.ui_remote_path_invalid)
    }
    val relative = FolderSpanArchiveCodec.normalizeRelativePath(relativeRaw.trim('/'))
    val resolved = FolderSpanArchiveCodec.buildTargetPath(
        rootPath = destRoot,
        relativePath = relative,
        separator = localSeparator,
    )
    if (!PathUtils.isPathWithinRoot(
            FileAccessPermission.Allowed,
            destRoot,
            resolved,
            allowNonExistentLeaf = true,
        )
    ) {
        throw AuthorityException(AppStrings.ui_download_path_exceeds_the_target_directory)
    }
    SensitiveFileAccessPolicy.deniedException(resolved)?.let { error -> throw error }
    return resolved
}
```

上面伪代码仅说明控制意图。落地时 `processDirectoryEntry` / `copyFileEntry` / HTTP 后备拼接 / 归档 fallback 都应走同一函数；`replaceFirst` + `dest + entry.path` 删除。

</details>

<details>
<summary>防御性验证代码（确认修复生效，不含攻击载荷）</summary>

下列断言只检查：相对路径含父段时不得产生 destRoot 之外的目标；目录复制在 list 返回不安全条目时失败且未在 dest 外创建文件。不要对真实本机主目录外的系统路径写文件，不要构造跨主机请求，不要打印分享 token。

```kotlin
@Test
fun resolveLocalCopyPathRejectsParentSegments() {
    assertFails {
        resolveLocalCopyPath(
            sourceRoot = "/src/folder",
            sourcePath = "/src/folder/../outside.txt",
            destRoot = "/dst/folder",
            sourceSeparator = "/",
        )
    }
    assertFails {
        resolveLocalCopyPath(
            sourceRoot = "/src/folder",
            sourcePath = "/src/folder/sub/../../outside.txt",
            destRoot = "/dst/folder",
            sourceSeparator = "/",
        )
    }
    val ok = resolveLocalCopyPath(
        sourceRoot = "/src/folder",
        sourcePath = "/src/folder/ok.txt",
        destRoot = "/dst/folder",
        sourceSeparator = "/",
    )
    assertTrue(ok.startsWith("/dst/folder"))
    assertFalse(ok.contains(".."))
}

@Test
fun archivePlannerDoesNotKeepUnsafeRelativePathsInBatches() {
    val selection = selectSmallFileArchiveBatches(
        items = listOf(
            file(path = "/src/folder/ok.txt", size = 8L),
            file(path = "/src/folder/../outside.txt", size = 8L),
        ),
        sourcePath = FileSimpleInfo::path,
        relativePath = { entry -> entry.path.removePrefix("/src/folder").trimStart('/') },
        size = FileSimpleInfo::size,
    )
    val batchPaths = selection.batches.flatMap { batch -> batch.requestEntries.map { it.relativePath } }
    assertTrue(batchPaths.none { path -> path.contains("..") })
}

@Test
fun shareDirectoryCopyDoesNotWriteOutsideDestination() = runSuspendTest {
    val destRoot = createTempDirectory()
    val result = /* 调用 Share/Device 目录 copyTo，list 返回 dest 外相对路径的测试替身 */
        copyDirectoryWithStubList(
            sourceRoot = "/share/folder",
            destination = destRoot,
            listEntries = listOf(
                file("/share/folder/ok.txt"),
                file("/share/folder/../outside.txt"),
            ),
        )
    assertTrue(result.isFailure)
    assertFalse(File(destRoot, "../outside.txt").canonicalFile.exists())
    assertTrue(
        File(destRoot).canonicalFile.listFiles().orEmpty().none { item ->
            !item.canonicalPath.startsWith(File(destRoot).canonicalPath)
        },
    )
}
```

测试只断言拒绝与「未写出 destRoot 外路径」。不要打印 Access Key、口令或分享文件内容。

</details>

---

## 本轮复核结论

第十三轮落地后，下列入口仍对齐既有控制，本轮复测通过：

| 面 | 当前控制 | 对照 |
| --- | --- | --- |
| Device/Share→Network 流式上传 | `resolveRemoteCopyPath`：`containsUnsafeNetworkPathSegment` + `normalizeRelativePath` + `isRemotePathWithinRoot` | FS-47 已修 |
| Network→Local | `buildLocalPath`：`normalizeRelativePath` + `buildTargetPath` + `isPathWithinRoot` + 敏感路径 | FS-43 |
| 分享 Session RPC | 白名单仅 `RootPaths` / `ListPath`；写 / 删 / 复制 / 书签 / 消息一律 `FORBIDDEN` | 第十三轮 |
| 分享 Session 流 | 白名单仅 `Read` / `ArchiveRead`；写流 RST | 第十三轮 |
| 分享路径 | `DeviceSharePathScope.parseVirtualPath` 拒绝 `..` / `.`；`checkPermission` 分享 token 非 read 拒绝 | 第十三轮 |
| 分享 grant | 一次性 nonce；deviceId + TLS 指纹 + 过期 | 第十三轮 |
| HTTP `RawHttpApiDispatcher.dispatch` | 当前只路由 `/api/share/`；`handleDevice` / `handleFile` / `handleWebRtcSignaling` 源码仍在但未挂到 dispatch | 本轮复核 |
| FSAR2 相对路径 | `normalizeRelativePath` 拒绝 `\`、空、绝对、`.` / `..`；`ArchiveEntryExtractor.begin` 再 `buildTargetPath` | 第八轮 + 第十三轮 |
| 归档批次选择 | `selectSmallFileArchiveBatches` 对相对路径 `normalizeRelativePath`，失败进 `remainingItems`；FS-48 修复后 remaining 先走 `resolveCopyTarget`，不安全相对路径中止任务而不再拼接 fallback | 已修复 |
| UI 分享 / 设备 list | `isUnsafeRemoteListEntry` 已过滤（第十三轮描述过时） | 本轮复核 |
| CLI 托管 WebUI | 仅 OpenSpec `openspec/changes/add-cli-hosted-webui/`，无 cliApp 入口，不能当已部署攻击面 | 本轮复核 |
| 目录遍历 symlink | `collectDirectoryEntriesAdaptive(..., rejectSymbolicLinkEntries = true)` | 第十三轮 |

本轮额外核对、仍不单开 ID 的面见下一节。

---

## 已检查、不作为本轮新漏洞

| 主题 | 结论 |
| --- | --- |
| FS-01～FS-47 | 不重复开洞。第十轮 CSRF/XSS、第十一轮内存暴增、第十二轮溢出/拷贝 IDOR、第十三轮 FS-47 维持原结论。FS-47 已在 `2b3d6874ab` 修复。 |
| 归档提取器 zip-slip | `ArchiveEntryExtractor.begin` 用 `normalizeRelativePath` + `buildTargetPath`。含 `..` 的相对路径进不了归档批次，会落到 FS-48 的拼接 fallback，不另开 ID。 |
| PathRouteTransferClient 拼接 | 同类 `dest + entry.path`。Share/Device 主路径因存在 transport client 不走 HTTP 后备。作为 FS-48 修复的一部分，不另开 ID。 |
| 二次 CONNECT 提权 | `handleConnect` 不拒绝已绑定会话再 CONNECT。分享会话第二次若仍带有效 `shareNonce`，nonce 已一次性消费，会 REJECTED。空 nonce 走完整 `evaluateDeviceConnect`：未知设备弹审批，已是 `AUTO_CONNECT` / 账号信任才自动签发完整设备 token。HTTP `/api/devices/connect` 的 `allowShareSessionAuthorization = false`。提权仍需用户批准或设备已信任，不够 0.8。hardening：已绑定后应拒绝再 CONNECT。 |
| 分享 Connect 无条件 `trustDeviceTlsFingerprint` | `issueDeviceToken` 调用 `trustDeviceTlsFingerprint`，分享 nonce 路径不先 `verifyDeviceIdentityProof`。grant.matches 已绑 TLS 指纹，攻击者必须是用户刚允许的那条分享连接。覆盖已信任设备钉扎还需把 grant 的 deviceId 做成该设备 id。不够单独 0.8，标 hardening。 |
| IDENTIFY 未授权 | 未绑定 token 也可 `Identify`，返回本机 `SocketDevice`。局域网发现 / 信令已有同类信息，不够单独开洞。 |
| HTTP 设备 / 文件 / WebRTC 信令路由 | `dispatch` 不调用 `handleDevice` / `handleFile` / `handleWebRtcSignaling`。死代码不当现网攻击面。 |
| CLI 托管 WebUI | 提案中的密码会话、CSRF、路径 jail、TLS 尚未落地。 |
| `app/desktopApp/share-history/` | 本地运行数据，不是源码审计对象。 |
| CORS / Host 头 / Open Redirect | 本轮技能面未找到新的可利用入口。链路分享第十轮已覆盖。 |
| WebSocket | Device Session 是自定义多路帧，不是浏览器 WebSocket 资源。分享 token 不 `registerMessageEndpoint`。 |
| 剪贴板 SSRF | 不够 0.8 或不适用分享会话，维持第十三轮。 |
| 系统分享敏感源 | 第十三轮已标 hardening，未证明可稳定读到策略拒绝的路径。 |
| 单文件 Device/Share→Local | 目标是用户选定的 `destination.path`，相对路径不参与。 |
| MCP / XSS / 反序列化 / 命令注入 / Android Intent | 维持第十 / 十二 / 十三轮。无 `ObjectInputStream`；无用户输入进入 `ProcessBuilder`；导出组件未新增。 |
| 第十二轮残留硬化 | 拷贝控制所有权、写入范围溢出安全比较、MCP `abortWrite` symlink、Shizuku `getCallingUid` 仍建议做，不另开 ID。 |
| 纯 DoS、限流、过时依赖、磁盘密钥 | 按口径排除。 |

<details>
<summary>残留硬化建议（不单开漏洞 ID）</summary>

这些项减小以后踩坑，但当前没有独立于 FS-48 的可利用路径，不另分配编号。

1. **已绑定 Session 拒绝再 CONNECT**：`handleConnect` 在 `boundToken != null` 时直接 `CONFLICT` / `FORBIDDEN`，避免分享会话在同一条通道上改走完整设备审批。二次 CONNECT 今天仍要用户批准或 AUTO_CONNECT，不是洞。
2. **分享签发 token 先 `verifyDeviceIdentityProof`**：不要仅凭 grant 消费就 `trustDeviceTlsFingerprint`。grant 已绑指纹，这是纵深防御。
3. **IDENTIFY 放到授权之后**：未绑定只返回最小字段（例如协议版本），完整 `SocketDevice` 等 CONNECT 成功。
4. **删除或明确隔离未挂载的 HTTP 处理函数**：`handleDevice` / `handleFile` / `handleWebRtcSignaling` 若不再使用，避免以后误挂回 `dispatch`。
5. **复制扫描与 UI list 共用过滤**：即便修了写前 jail，扫描阶段也不应把 `..` 条目送进任务进度 / retry 路径。
6. **归档 fallback 失败即失败**：不要把 `normalizeRelativePath` 拒绝的条目改走更松的单文件复制。
7. 第十二轮残留（拷贝控制绑 token、写入范围溢出安全比较、MCP `abortWrite` 拒绝 symlink、Shizuku uid 校验）以及第十轮残留（`Service-Worker-Allowed`、CORS 允许头白名单、`POST /auth` 同源）仍建议做，不阻塞 FS-48。

</details>

<details>
<summary>防御性验证代码（确认本轮既有控制仍生效，不含攻击载荷）</summary>

下列断言只检查安全属性：FS-47 网盘相对路径拒绝穿越、归档相对路径拒绝 `..`、分享虚拟路径拒绝穿越、HTTP dispatch 不把未挂载路由当 200。不要构造跨设备碰撞、不要对局域网其它主机发请求、不要打印 token / 分享 nonce。FS-48 的穿越拒绝测试见该洞折叠区。

```kotlin
@Test
fun resolveRemoteCopyPathStillRejectsParentSegments() {
    assertFails {
        resolveRemoteCopyPath(
            sourceRoot = "/src/folder",
            sourcePath = "/src/folder/../outside.txt",
            destRoot = "/dst/folder",
            sourceSeparator = "/",
            destSeparator = "/",
        )
    }
}

@Test
fun archiveRelativePathRejectsParentSegments() {
    assertFails { FolderSpanArchiveCodec.normalizeRelativePath("../secret") }
    assertFails { FolderSpanArchiveCodec.normalizeRelativePath("/abs") }
    assertFails { FolderSpanArchiveCodec.normalizeRelativePath("a\\b") }
    assertEquals("dir/file.txt", FolderSpanArchiveCodec.normalizeRelativePath("dir/file.txt"))
}

@Test
fun shareVirtualPathRejectsTraversal() {
    val scope = DeviceSharePathScope(
        listOf(DeviceSharePathGrant(path = "/home/user/shared", isDirectory = true)),
        pathSeparator = "/",
    )
    assertNull(scope.resolveVirtualContentPath("/shared/../secret"))
    assertNull(scope.resolveVirtualContentPath("/shared/foo/../../etc/passwd"))
}

@Test
fun isUnsafeRemoteListEntryRejectsParentName() {
    assertTrue(isUnsafeRemoteListEntry("..", "/share/.."))
    assertTrue(isUnsafeRemoteListEntry("ok", "/share/foo/../bar"))
    assertFalse(isUnsafeRemoteListEntry("photo.jpg", "/share/photo.jpg"))
}
```

测试只断言拒绝与「未写出目标根外路径」。不要打印 Access Key、口令或分享文件内容。

</details>

## Context

需求最初按“四个平台外部拖放”设计，但实际产品能力不同：Desktop 有稳定的 AWT 拖入入口；Android 与 iOS 当前不支持跨应用拖放，分别由系统分享 Intent 与文件 URL 打开入口接收外部文件；Web 不启用分享功能。

现有 `FileShareState.updateIncomingFiles(files)` 已是统一外部导入通道。`FileShareScreen` 的 `LaunchedEffect(incomingFiles)` 会处理空列表、相同集合以及「替换 / 追加 / 取消」三种合并结果；另一个同步副作用会按自动更新偏好同步已授权设备。因此本变更只负责把不同平台的外部文件送入这条通道，并在移动端确保分享页被打开。

## Goals / Non-Goals

**Goals:**

- Desktop 分享页可见时，外部拖入优先进入分享列表；离开分享页后保留文件浏览器拖入行为。
- Android 文件 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 与 iOS 文件 URL 打开入口直接进入 `FileShareScreen`。
- 分享页已在栈顶时不重复导航；冷启动时也能先保存文件、再由页面消费。
- 复用现有合并与同步逻辑，并在待合并阶段保持 iOS security-scoped URL 可读。

**Non-Goals:**

- 不把 Android/iOS 跨应用拖放列为支持或验收目标。
- 不启用 Web 分享入口，也不把已存在的 Web 底层拖入代码视为已开放能力。
- 不新增分享列表内排序、向外拖出或第二套合并 UI。
- 不改变 `filterShareableFiles`、链接分享或设备分享的授权规则。

## Decisions

### 决策 1：Desktop 继续使用页面生命周期注册表

`FileShareScreen` 通过 `DisposableEffect` 向 `ShareListDropRegistry` 注册接收器。Desktop 的 `DesktopFileDropHandler` 在落地时先尝试投递到注册表；命中后调用 `updateIncomingFiles`，未命中则执行既有的浏览、设备上传或复制逻辑。

注册表适合 Desktop 的原因是 AWT 监听器位于 Compose 外部，而接收器的存在恰好表达页面仍在 composition 中。注册和注销均做接收器身份比对，避免重组期间旧实例清除新实例。

### 决策 2：移动端采用 state-first 的共享协调函数

新增 commonMain 的 `openIncomingFilesInFileShare(files, fileShareState, mainState, releaseResources)`：

1. 用 `normalizeShareListDropFiles` 去重并规范条目；
2. 先调用 `FileShareState.updateIncomingFiles`，让数据独立于 Composition 保存；
3. 若过滤后没有可接收条目，立即释放临时资源并停止；
4. 若提供资源释放回调，则按接受条目的路径登记到 `ShareListDropResourceRegistry`；
5. 仅当 `MainState.currentRoute` 不是 `FileShareScreen` 时调用 `requestOpenScreen`。

这样冷启动与页面已打开两种情况走同一路径，也不需要把文件列表作为导航参数复制到路由对象。`MainState.requestOpenScreen` 在 Navigator 尚未建立时会保存待打开页面，页面建立后再消费 `FileShareState.incomingFiles`。

### 决策 3：Android 区分文件分享与原有入口

`ShareIntentHandler` 对入口做以下路由：

| Intent | 行为 |
|---|---|
| 非文本 `ACTION_SEND` 且包含 `EXTRA_STREAM` | URI 归一化后直达 `FileShareScreen` |
| `ACTION_SEND_MULTIPLE` 且包含文件 | 批量归一化后直达 `FileShareScreen` |
| 文本 `ACTION_SEND` | 保持首页文本分享行为 |
| `ACTION_VIEW` | 保持首页文件查看行为 |

`MainActivity.prepareInitialScreen` 在冷启动阶段对文件分享直接预定 `FileShareScreen`，避免先显示首页再跳转。`ShareHandler.handleSharedFilesForShare` 读取 `ContentResolver` 元数据，产生 `FileProtocol.Share` + `SYSTEM_SHARE_DESK_ID` 条目；URI 访问继续使用 Intent 授予 Activity 的读取权限。

Android 既有拖放代码暂不删除，以免改变现有内部兼容路径，但它不再属于 Android 的支持范围或验收任务。

### 决策 4：iOS 文件 URL 直接进入分享页并按引用释放

SwiftUI/AppDelegate 已把系统传入的 URL 交给 `handleIosDocumentOpen` / `handleIosDocumentOpenUrls`。处理器对路径去重，向 `IosSecurityScopeStore` 注册 URL，通过 `FileUtils.getFile` 读取元数据，并以 `FileProtocol.Local` 条目调用共享协调函数。

无法读取的路径立即注销；成功条目的注销回调登记到 `ShareListDropResourceRegistry`。`FileShareScreen` 计算资源保留集合时同时包含当前列表、`incomingFiles` 和等待合并的 `pendingIncomingFiles`，避免页面刚打开或合并弹窗显示期间提前释放。条目被移除、替换或合并取消后，注册表会调用 `IosSecurityScopeStore.unregister`。

### 决策 5：Web 不启用分享产品能力

Web 的 js/wasmJs source set 已有底层文件拖入归一化实现，但 `HttpShareFileServer` 为空实现且产品未启用分享入口。本变更不新增 Web 分享页面入口、提示或手工验收任务。保留底层实现仅避免回退已有代码，不构成受支持能力承诺。

## Risks / Trade-offs

- **Android URI 授权时长取决于发送方与 Activity 生命周期。** 已验证 `FileProtocol.Share` 条目可由 HTTP 分享链路通过 `ContentResolver` 读取；当前实现不申请可持久化权限，应用进程结束后不承诺继续读取。
- **移动端数据与导航异步。** 先写 state 再导航能覆盖冷启动，但必须避免页面副作用只保留当前列表而提前释放待处理资源；因此保留集合显式包含 `incomingFiles` 与 `pendingIncomingFiles`。
- **iOS security scope 可能累计。** 资源注册表在取消、移除、替换及应用 teardown 时释放；如果平台未触发生命周期回调，仍可能延迟到进程退出。
- **Android 目录分享。** 标准 `ACTION_SEND` 通常提供文件 URI；当前归一化按文件处理，不承诺从系统分享 Intent 递归导入目录。Desktop 拖入目录仍保留目录条目。
- **Web 底层代码与产品能力不一致。** 文档和任务清单明确 Web 未启用，避免把可编译的实现误认为可交付功能。

## Resolved Questions

- **2026-08-11：Android content-URI 可经链接分享读取。** 实机验证以 `FileProtocol.Share` + `SYSTEM_SHARE_DESK_ID` 将媒体库 URI 放入分享列表后，已授权客户端下载内容与源文件 SHA-256 一致，因此文件分享 Intent 可保留原 URI 入列。
- **2026-08-11：移动端入口选型。** Android/iOS 不支持跨应用拖放，改为系统分享/文件打开直达 `FileShareScreen`；Desktop 拖放继续支持；Web 不启用分享。

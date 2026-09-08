## Why

`FileShareScreen` 已能通过 FAB、文件页或首页接收 `FileShareState.updateIncomingFiles`，但外部文件入口的落点并不一致：Desktop 拖入会被文件浏览器接管；Android 系统分享 Intent 与 iOS 文件打开入口也会先进入首页或文件页，用户还要再次进入分享页。Android 与 iOS 当前不支持外部拖放，Web 又不启用分享功能，因此不能继续把需求描述为“四个平台拖放”。

## What Changes

- Desktop：当 `FileShareScreen` 位于导航栈顶时，外部拖入的文件与文件夹进入分享列表；其他页面上的拖入行为保持现状。
- Android：接收到包含文件的 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 时，把 URI 归一化为分享条目，写入 `FileShareState.updateIncomingFiles`，并直接打开 `FileShareScreen`；纯文本分享与 `ACTION_VIEW` 保持原行为。
- iOS：通过系统文件打开入口接收到文件 URL 时，把文件归一化为分享条目，写入 `FileShareState.updateIncomingFiles`，并直接打开 `FileShareScreen`。
- 当分享页已经位于栈顶时只更新 `incomingFiles`，不重复压入页面；列表已有内容时继续复用 `FileShareMergeDialog` 的「替换 / 追加 / 取消」语义。
- Android `content://` 与 iOS security-scoped URL 的访问权在对应条目仍被分享列表引用期间保持有效，条目移除后释放。
- Web 不启用分享入口，本变更不要求 Web 端提供可访问的分享页或手工验收任务；既有的底层拖入归一化代码不代表 Web 产品能力已开放。
- 不新增分享同步代码：`FileShareScreen` 现有同步副作用继续处理链接分享、设备分享及自动更新关闭时的提示。

## Capabilities

### New Capabilities

- `share-list-drop-import`: 定义 Desktop 分享页拖入，以及 Android/iOS 系统外部文件入口直达分享列表时的路由、归一化、合并和资源生命周期。

### Modified Capabilities

（无。现有 spec 未描述这些外部文件入口，既有分享授权与同步要求不变。）

## Impact

- **共享 UI**：`FileShareScreen` 注册 Desktop 拖入接收方，并消费移动端写入的 `incomingFiles`；新增共享的“写入后打开分享页”协调函数。
- **Android**：`MainActivity`、`ShareIntentHandler` 与 `ShareHandler` 把文件分享 Intent 归一化后直达分享页。
- **iOS**：`IosDocumentOpenHandler` 把系统传入的文件 URL 归一化后直达分享页，`IosSecurityScopeStore` 负责引用期访问权。
- **Desktop**：`DesktopFileDropHandler` 在分享页可见时优先投递到分享列表。
- **Web**：不启用分享入口，不新增 Web 产品能力或验收任务。
- **测试**：覆盖共享路由、去重/合并依赖、资源释放及各平台编译。
